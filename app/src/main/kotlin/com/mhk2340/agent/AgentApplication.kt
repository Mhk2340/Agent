package com.mhk2340.agent

import android.app.Activity
import android.app.Application
import android.os.Bundle
import inference.HardwareBenchmarkDependenciesOwner
import inference.BenchmarkStarvationMonitorDependencies
import inference.HardwareBenchmarkWorkerDependencies
import runtime.AgentState
import runtime.AppInitializer
import runtime.RuntimeStateManager
import runtime.di.AgentContainer

/** Runs GEN-INIT-1 and opens the shared Room database from Android's Application lifecycle. */
class AgentApplication : Application(), HardwareBenchmarkDependenciesOwner {
    val agentContainer: AgentContainer by lazy { AgentContainer(applicationContext = this) }
    private var startedActivityCount = 0

    override val hardwareBenchmarkWorkerDependencies: HardwareBenchmarkWorkerDependencies
        get() = agentContainer.hardwareBenchmarkWorkerDependencies

    override val benchmarkStarvationMonitorDependencies: BenchmarkStarvationMonitorDependencies
        get() = agentContainer.benchmarkStarvationMonitorDependencies

    lateinit var initializationResults: List<AppInitializer.InitStepResult>
        private set

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    runCatching { agentContainer.appLockManager.onAppMovedToForeground() }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivityCount > 0) startedActivityCount--
                if (startedActivityCount == 0) {
                    runCatching { agentContainer.appLockManager.onAppMovedToBackground() }
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
