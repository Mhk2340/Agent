package com.mhk2340.agent

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.os.Build
import android.os.Bundle
import diagnostics.AndroidDiagnosticsPersistence
import diagnostics.DiagnosticCode
import diagnostics.DiagnosticComponent
import diagnostics.DiagnosticField
import diagnostics.DiagnosticFieldKey
import diagnostics.DiagnosticLevel
import diagnostics.PreviousProcessCrashKind
import diagnostics.DiagnosticsLog
import java.io.File
import inference.HardwareBenchmarkDependenciesOwner
import inference.BenchmarkStarvationMonitorDependencies
import inference.HardwareBenchmarkWorkerDependencies
import runtime.AgentState
import runtime.AppInitializer
import runtime.RuntimeStateManager
import runtime.di.AgentContainer

/** Runs GEN-INIT-1 and opens the shared Room database from Android's Application lifecycle. */
class AgentApplication : Application(), HardwareBenchmarkDependenciesOwner {
    val diagnosticsLog: DiagnosticsLog by lazy {
        DiagnosticsLog(
            persistence = if (BuildConfig.DEBUG) {
                AndroidDiagnosticsPersistence(File(noBackupFilesDir, "diagnostics"))
            } else {
                null
            }
        )
    }
    val agentContainer: AgentContainer by lazy {
        AgentContainer(applicationContext = this, diagnostics = diagnosticsLog)
    }
    private var startedActivityCount = 0
    @Volatile
    private var previousProcessCrashed = false

    fun hasCrashNotice(): Boolean = previousProcessCrashed

    fun clearCrashNotice() {
        previousProcessCrashed = false
    }

    override val hardwareBenchmarkWorkerDependencies: HardwareBenchmarkWorkerDependencies
        get() = agentContainer.hardwareBenchmarkWorkerDependencies

    override val benchmarkStarvationMonitorDependencies: BenchmarkStarvationMonitorDependencies
        get() = agentContainer.benchmarkStarvationMonitorDependencies

    lateinit var initializationResults: List<AppInitializer.InitStepResult>
        private set

    private fun didPreviousProcessCrash(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return BuildConfig.DEBUG && diagnosticsLog.hasCrashEvent()
        }
        val exit = getSystemService(ActivityManager::class.java)
            ?.getHistoricalProcessExitReasons(packageName, 0, 8)
            ?.maxByOrNull { it.timestamp }
            ?: return false
        return exit.reason == ApplicationExitInfo.REASON_CRASH ||
            exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE
    }

    private fun previousProcessCrashKind(): PreviousProcessCrashKind {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return PreviousProcessCrashKind.JAVA
        val exit = getSystemService(ActivityManager::class.java)
            ?.getHistoricalProcessExitReasons(packageName, 0, 8)
            ?.maxByOrNull { it.timestamp }
        return if (exit?.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
            PreviousProcessCrashKind.NATIVE
        } else {
            PreviousProcessCrashKind.JAVA
        }
    }

    private fun installUncaughtExceptionHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            runCatching { diagnosticsLog.recordUncaughtException(exception) }
            if (previous != null) {
                previous.uncaughtException(thread, exception)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        previousProcessCrashed = didPreviousProcessCrash()
        if (previousProcessCrashed && !diagnosticsLog.hasCrashEvent()) {
            val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                previousProcessCrashKind()
            } else {
                PreviousProcessCrashKind.JAVA
            }
            diagnosticsLog.record(
                DiagnosticLevel.WARNING,
                DiagnosticComponent.APP,
                DiagnosticCode.PREVIOUS_PROCESS_CRASH,
                DiagnosticField.enum(DiagnosticFieldKey.PHASE, kind)
            )
        }
        diagnosticsLog.record(DiagnosticLevel.INFO, DiagnosticComponent.APP, DiagnosticCode.APP_STARTED)
        installUncaughtExceptionHandler()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    runCatching { agentContainer.appLockManager.onAppMovedToForeground() }
                    val unlocked = runCatching { agentContainer.appLockManager.isAppUnlocked() }.getOrDefault(false)
                    diagnosticsLog.record(
                        DiagnosticLevel.INFO,
                        DiagnosticComponent.APP_LOCK,
                        DiagnosticCode.APP_LOCK_STATE,
                        DiagnosticField.flag(DiagnosticFieldKey.SUCCESS, unlocked),
                        DiagnosticField.enum(DiagnosticFieldKey.PHASE, diagnostics.DiagnosticLifecycleState.FOREGROUND)
                    )
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivityCount > 0) startedActivityCount--
                if (startedActivityCount == 0) {
                    runCatching { agentContainer.appLockManager.onAppMovedToBackground() }
                    val unlocked = runCatching { agentContainer.appLockManager.isAppUnlocked() }.getOrDefault(false)
                    diagnosticsLog.record(
                        DiagnosticLevel.INFO,
                        DiagnosticComponent.APP_LOCK,
                        DiagnosticCode.APP_LOCK_STATE,
                        DiagnosticField.flag(DiagnosticFieldKey.SUCCESS, unlocked),
                        DiagnosticField.enum(DiagnosticFieldKey.PHASE, diagnostics.DiagnosticLifecycleState.BACKGROUND)
                    )
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        // Restore the durable security latch before any benchmark/profile work can be scheduled.
        agentContainer.restorePanicResetLockdown()
        // Best-effort asynchronous cache read only; no model execution/measurement occurs here.
        agentContainer.preloadCurrentHardwareProfile()
        val results = agentContainer.appInitializer.initialize().toMutableList()
        try {
            val persistence = agentContainer.initializePersistence()
            results.add(AppInitializer.InitStepResult.Success("DatabaseInitialization"))
            if (persistence.recoveredByReset) {
                results.add(
                    AppInitializer.InitStepResult.Failure(
                        "DatabaseRecovery",
                        "The previous database could not be opened and was recreated; existing persisted data was lost. " +
                            "Initial failure: ${persistence.initialFailure ?: "unknown database error"}"
                    )
                )
            }
        } catch (e: Exception) {
            results.add(
                AppInitializer.InitStepResult.Failure(
                    "DatabaseInitialization",
                    e.message ?: e::class.java.simpleName
                )
            )
            RuntimeStateManager.transitionTo(AgentState.ERROR)
        }
        initializationResults = results
    }
}
