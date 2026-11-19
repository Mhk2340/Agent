package diagnostics

import java.util.ArrayDeque
import java.util.Collections
import java.util.IdentityHashMap

/** Closed vocabularies keep diagnostic records free of caller-supplied text. */
enum class DiagnosticLevel { DEBUG, INFO, WARNING, ERROR }

enum class DiagnosticComponent {
    APP,
    APP_LOCK,
    CHAT_STORE,
    CHAT_CONTROLLER,
    MODEL,
    NATIVE,
    SELF_CHECK,
    PANIC_RESET
}

enum class DiagnosticCode(val wireCode: String) {
    APP_STARTED("APP-1"),
    APP_LOCK_STATE("LOCK-1"),
    CHAT_STORE_READ_BEGIN("CHAT-STORE-1"),
    CHAT_STORE_READ_OK("CHAT-STORE-2"),
    CHAT_STORE_CRYPTO_FAILURE("CHAT-STORE-3"),
    CHAT_STORE_READ_FAILURE("CHAT-STORE-4"),
    CHAT_STORE_ARCHIVE_FAILURE("CHAT-STORE-5"),
    CHAT_STORE_WRITE_FAILURE("CHAT-STORE-6"),
    CHAT_STORE_FALLBACK("CHAT-STORE-7"),
    CHAT_STORE_CLEARED("CHAT-STORE-8"),
    CHAT_STORE_WRITE_OK("CHAT-STORE-9"),
    CHAT_STORE_ACCESS_DENIED("CHAT-STORE-10"),
    CHAT_STORE_WRITE_BEGIN("CHAT-STORE-11"),
    CHAT_SEND_BEGIN("CHAT-GEN-1"),
    CHAT_SEND_FIRST_TOKEN("CHAT-GEN-2"),
    CHAT_SEND_FINISHED("CHAT-GEN-3"),
    CHAT_SEND_CANCELLED("CHAT-GEN-4"),
    CHAT_SEND_FAILED("CHAT-GEN-5"),
    MODEL_LOAD_BEGIN("MODEL-1"),
    MODEL_LOAD_FINISHED("MODEL-2"),
    MODEL_LOAD_FAILED("MODEL-3"),
    NATIVE_LOAD_FAILED("NATIVE-1"),
    SELF_CHECK_RESULT("DIAG-SELF-1"),
    PANIC_RESET_CLEARED("RESET-1"),
    PANIC_RESET_STARTED("RESET-2"),
    PANIC_RESET_STAGE("RESET-3"),
    PANIC_RESET_FAILED("RESET-4"),
    UNCAUGHT_EXCEPTION("CRASH-1"),
    PREVIOUS_PROCESS_CRASH("CRASH-2")
}

enum class DiagnosticLifecycleState { FOREGROUND, BACKGROUND }
enum class DiagnosticAppLockState { LOCKED, UNLOCKED }
enum class PreviousProcessCrashKind { JAVA, NATIVE }
enum class PanicResetOutcome { STARTED, SUCCEEDED, FAILED }
enum class PanicResetStage {
    RESET,
    LOCKDOWN_LATCH,
    LEVEL_3_BUDGET,
    LONG_TERM_MEMORY,
    SESSION_MEMORY,
    PROVIDER_SECRETS,
    ACTIVE_JOBS,
    BENCHMARK_WORK,
    SHARED_WORKSPACE,
    LOCAL_CHAT_HISTORY,
    POLICY_CONFIG,
    SECURITY_EPOCH,
    AUDIT_KEY_RETIREMENT,
    MEMORY_FALLBACK_PURGE,
    AUDIT_HISTORY_FALLBACK_PURGE,
    GENESIS_AUDIT
}

enum class DiagnosticFieldKey {
    DURATION_MS,
    TOKEN_COUNT,
    BYTE_COUNT,
    MEMORY_BYTES,
    SUCCESS,
    PHASE,
    EXCEPTION_TYPE,
    CAUSE_TYPE_1,
    CAUSE_TYPE_2,
    CAUSE_TYPE_3,
    RESULT_TYPE,
    PROMPT_TOKEN_COUNT,
    OUTPUT_TOKEN_COUNT,
    TIME_TO_FIRST_TOKEN_MS,
    PREFILL_TOKENS_PER_SECOND,
    DECODE_TOKENS_PER_SECOND,
    STACK_FRAME_1,
    STACK_FRAME_2,
    STACK_FRAME_3,
    STACK_FRAME_4,
    STACK_FRAME_5,
    STACK_FRAME_6,
    STACK_FRAME_7,
    STACK_FRAME_8,
    STACK_FRAME_9,
    STACK_FRAME_10
}

/** Values can only be built from numbers, booleans, enum constants, or exception classes. */
class DiagnosticField private constructor(
    internal val key: DiagnosticFieldKey,
    internal val value: SafeDiagnosticValue
) {
    companion object {
        @JvmStatic
        fun number(key: DiagnosticFieldKey, value: Long): DiagnosticField =
            DiagnosticField(key, SafeDiagnosticValue.NumberValue(value))

        @JvmStatic
        fun decimal(key: DiagnosticFieldKey, value: Double): DiagnosticField {
            require(value.isFinite())
            return DiagnosticField(key, SafeDiagnosticValue.DecimalValue(value))
        }

        @JvmStatic
        fun flag(key: DiagnosticFieldKey, value: Boolean): DiagnosticField =
            DiagnosticField(key, SafeDiagnosticValue.BooleanValue(value))

        @JvmStatic
        fun enum(key: DiagnosticFieldKey, value: Enum<*>): DiagnosticField =
            DiagnosticField(key, SafeDiagnosticValue.EnumValue(value.javaClass.name, value.name))

        internal fun exceptionType(key: DiagnosticFieldKey, value: Class<out Throwable>): DiagnosticField =
            DiagnosticField(key, SafeDiagnosticValue.ExceptionClassValue(value.name))

        internal fun classType(key: DiagnosticFieldKey, value: Class<*>): DiagnosticField =
            DiagnosticField(key, SafeDiagnosticValue.ExceptionClassValue(value.name))

        internal fun stackFrame(key: DiagnosticFieldKey, frame: StackTraceElement): DiagnosticField =
            DiagnosticField(key, SafeDiagnosticValue.StackFrameValue(
                className = safeStackClassName(frame.className),
                methodName = safeStackMethodName(frame.methodName),
                lineNumber = frame.lineNumber.coerceAtLeast(-1)
            ))

        private fun safeStackClassName(value: String): String = value
            .filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '$' }
            .take(160)
            .ifEmpty { "unknown" }

        private fun safeStackMethodName(value: String): String = value
            .filter { it.isLetterOrDigit() || it == '_' || it == '$' || it == '<' || it == '>' }
            .take(120)
            .ifEmpty { "unknown" }
    }
}

internal sealed interface SafeDiagnosticValue {
    fun wireValue(): String
    fun reportValue(): String

    data class NumberValue(val number: Long) : SafeDiagnosticValue {
        override fun wireValue() = "N:$number"
        override fun reportValue() = number.toString()
    }

    data class DecimalValue(val number: Double) : SafeDiagnosticValue {
        override fun wireValue() = "D:$number"
        override fun reportValue() = number.toString()
    }

    data class BooleanValue(val flag: Boolean) : SafeDiagnosticValue {
        override fun wireValue() = "B:$flag"
        override fun reportValue() = flag.toString()
    }

    data class EnumValue(val typeName: String, val enumName: String) : SafeDiagnosticValue {
        override fun wireValue() = "E:$typeName~$enumName"
        override fun reportValue() = "$typeName.$enumName"
    }

    data class ExceptionClassValue(val typeName: String) : SafeDiagnosticValue {
        override fun wireValue() = "C:$typeName"
        override fun reportValue() = typeName
    }

    data class StackFrameValue(val className: String, val methodName: String, val lineNumber: Int) : SafeDiagnosticValue {
        override fun wireValue() = "S:$className~$methodName~$lineNumber"
        override fun reportValue() = "$className.$methodName:$lineNumber"
    }
}

internal data class DiagnosticEvent(
    val timestampEpochMs: Long,
    val level: DiagnosticLevel,
    val component: DiagnosticComponent,
    val code: DiagnosticCode,
    val fields: List<Pair<DiagnosticFieldKey, SafeDiagnosticValue>>
) {
    fun reportLine(): String = buildString {
        append(timestampEpochMs).append(' ')
        append(level.name).append(' ')
        append(component.name).append(' ')
        append(code.wireCode)
        fields.forEach { (key, value) ->
            append(' ').append(key.name).append('=').append(value.reportValue())
        }
    }

    fun persistenceLine(): String = buildString {
        append(timestampEpochMs).append('\t').append(level.name).append('\t')
        append(component.name).append('\t').append(code.name).append('\t')
        append(fields.joinToString(";") { (key, value) -> "${key.name}=${value.wireValue()}" })
    }

    companion object {
        fun fromPersistenceLine(line: String): DiagnosticEvent? = runCatching {
            val parts = line.split('\t', limit = 5)
            require(parts.size == 5)
            val eventFields = parts[4].split(';').filter(String::isNotBlank).map { encoded ->
                val pair = encoded.split('=', limit = 2)
                require(pair.size == 2)
                val key = DiagnosticFieldKey.valueOf(pair[0])
                val typeAndValue = pair[1].split(':', limit = 2)
                require(typeAndValue.size == 2)
                val value = when (typeAndValue[0]) {
                    "N" -> SafeDiagnosticValue.NumberValue(typeAndValue[1].toLong())
                    "D" -> SafeDiagnosticValue.DecimalValue(typeAndValue[1].toDouble().also { require(it.isFinite()) })
                    "B" -> SafeDiagnosticValue.BooleanValue(typeAndValue[1].toBooleanStrict())
                    "E" -> {
                        val enumParts = typeAndValue[1].split('~', limit = 2)
                        require(enumParts.size == 2)
                        SafeDiagnosticValue.EnumValue(enumParts[0], enumParts[1])
                    }
                    "C" -> SafeDiagnosticValue.ExceptionClassValue(typeAndValue[1])
                    "S" -> {
                        val frameParts = typeAndValue[1].split('~', limit = 3)
                        require(frameParts.size == 3)
                        val safeClassName = frameParts[0].filter { it.isLetterOrDigit() || it == '_' || it == '.' || it == '$' }.take(160).ifEmpty { "unknown" }
                        val safeMethodName = frameParts[1].filter { it.isLetterOrDigit() || it == '_' || it == '$' || it == '<' || it == '>' }.take(120).ifEmpty { "unknown" }
                        val lineNumber = frameParts[2].toInt().also { require(it >= -1) }
                        SafeDiagnosticValue.StackFrameValue(safeClassName, safeMethodName, lineNumber)
                    }
                    else -> error("Unknown diagnostic value type")
                }
                key to value
            }
            DiagnosticEvent(
                timestampEpochMs = parts[0].toLong(),
                level = DiagnosticLevel.valueOf(parts[1]),
                component = DiagnosticComponent.valueOf(parts[2]),
                code = DiagnosticCode.valueOf(parts[3]),
                fields = eventFields
            )
        }.getOrNull()
    }
}

internal interface DiagnosticPersistence {
    fun load(): List<DiagnosticEvent>
    fun append(event: DiagnosticEvent)
    fun clear()
}

/** In-memory ring buffer with an optional debug-only persistence sink. */
class DiagnosticsLog internal constructor(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val persistence: DiagnosticPersistence? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val events = ArrayDeque<DiagnosticEvent>()

    init {
        require(capacity > 0)
        persistence?.load().orEmpty().takeLast(capacity).forEach(events::addLast)
    }

    @Synchronized
    fun record(
        level: DiagnosticLevel,
        component: DiagnosticComponent,
        code: DiagnosticCode,
        vararg fields: DiagnosticField
    ) = appendEvent(level, component, code, fields.toList())

    @Synchronized
    fun recordFailure(
        level: DiagnosticLevel,
        component: DiagnosticComponent,
        code: DiagnosticCode,
        exception: Throwable,
        vararg fields: DiagnosticField
    ) {
        val safe = fields.toMutableList()
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        var current: Throwable? = exception
        var depth = 0
        while (current != null && depth <= 3 && seen.add(current)) {
            val key = when (depth) {
                0 -> DiagnosticFieldKey.EXCEPTION_TYPE
                1 -> DiagnosticFieldKey.CAUSE_TYPE_1
                2 -> DiagnosticFieldKey.CAUSE_TYPE_2
                else -> DiagnosticFieldKey.CAUSE_TYPE_3
            }
            @Suppress("UNCHECKED_CAST")
            safe += DiagnosticField.exceptionType(key, current.javaClass as Class<out Throwable>)
            current = current.cause
            depth++
        }
        appendEvent(level, component, code, safe)
    }

    @Synchronized
    fun snapshot(level: DiagnosticLevel? = null, limit: Int = DEFAULT_CAPACITY): List<String> {
        val safeLimit = limit.coerceIn(0, capacity)
        return events.asSequence()
            .filter { level == null || it.level == level }
            .toList()
            .takeLast(safeLimit)
            .map(DiagnosticEvent::reportLine)
    }

    @Synchronized
    fun size(): Int = events.size

    /** Threshold-filtered, explicitly exportable report containing only bounded safe events. */
    @Synchronized
    fun exportReport(minimumLevel: DiagnosticLevel = DiagnosticLevel.DEBUG, limit: Int = 100): String {
        val safeLimit = limit.coerceIn(0, capacity)
        val selected = events.asSequence()
            .filter { it.level.ordinal >= minimumLevel.ordinal }
            .toList()
            .takeLast(safeLimit)
        return buildString {
            append("ATOM diagnostics v1")
            selected.forEach { append('\n').append(it.reportLine()) }
        }
    }

    @Synchronized
    fun hasCrashEvent(): Boolean = events.any {
        it.code == DiagnosticCode.UNCAUGHT_EXCEPTION || it.code == DiagnosticCode.PREVIOUS_PROCESS_CRASH
    }

    /** Captures only the exception class and at most ten sanitized class/method/line frames. */
    @Synchronized
    fun recordUncaughtException(exception: Throwable) {
        val frameKeys = arrayOf(
            DiagnosticFieldKey.STACK_FRAME_1, DiagnosticFieldKey.STACK_FRAME_2,
            DiagnosticFieldKey.STACK_FRAME_3, DiagnosticFieldKey.STACK_FRAME_4,
            DiagnosticFieldKey.STACK_FRAME_5, DiagnosticFieldKey.STACK_FRAME_6,
            DiagnosticFieldKey.STACK_FRAME_7, DiagnosticFieldKey.STACK_FRAME_8,
            DiagnosticFieldKey.STACK_FRAME_9, DiagnosticFieldKey.STACK_FRAME_10
        )
        val fields = mutableListOf(DiagnosticField.classType(DiagnosticFieldKey.EXCEPTION_TYPE, exception.javaClass))
        exception.stackTrace.take(frameKeys.size).forEachIndexed { index, frame ->
            fields += DiagnosticField.stackFrame(frameKeys[index], frame)
        }
        appendEvent(DiagnosticLevel.ERROR, DiagnosticComponent.APP, DiagnosticCode.UNCAUGHT_EXCEPTION, fields)
    }

    @Synchronized
    fun clear() {
        events.clear()
        persistence?.clear()
    }

    private fun appendEvent(
        level: DiagnosticLevel,
        component: DiagnosticComponent,
        code: DiagnosticCode,
        fields: List<DiagnosticField>
    ) {
        val safeFields = fields.take(MAX_FIELDS).map { it.key to it.value }
        val event = DiagnosticEvent(clock(), level, component, code, safeFields)
        while (events.size >= capacity) events.removeFirst()
        events.addLast(event)
        runCatching { persistence?.append(event) }
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
        private const val MAX_FIELDS = 32
    }
}
