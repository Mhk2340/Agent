package diagnostics

import java.io.File
import java.io.FileOutputStream

/** Two private debug-only chunks, capped at 128 KiB each (256 KiB total). */
internal class AndroidDiagnosticsPersistence(
    private val directory: File
) : DiagnosticPersistence {
    private val activeFile = File(directory, ACTIVE_FILE_NAME)
    private val previousFile = File(directory, PREVIOUS_FILE_NAME)

    @Synchronized
    override fun load(): List<DiagnosticEvent> = sequenceOf(previousFile, activeFile)
        .flatMap { file ->
            if (!file.isFile) emptySequence()
            else runCatching {
                file.readLines(Charsets.UTF_8).asSequence()
                    .filter { it.length <= MAX_LINE_CHARACTERS }
                    .mapNotNull(DiagnosticEvent::fromPersistenceLine)
            }.getOrElse { emptySequence() }
        }
        .toList()

    @Synchronized
    override fun append(event: DiagnosticEvent) {
        if (!directory.exists() && !directory.mkdirs()) return
        val bytes = (event.persistenceLine() + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) return
        val activeSize = activeFile.length()
        if (activeSize + bytes.size.toLong() > MAX_FILE_BYTES.toLong()) {
            previousFile.delete()
            if (activeSize in 1L..MAX_FILE_BYTES.toLong() && activeFile.exists() && !activeFile.renameTo(previousFile)) {
                activeFile.delete()
            } else if (activeSize > MAX_FILE_BYTES.toLong()) {
                activeFile.delete()
            }
        }
        runCatching {
            FileOutputStream(activeFile, true).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
        }
    }

    @Synchronized
    override fun clear() {
        activeFile.delete()
        previousFile.delete()
        directory.delete()
    }

    private companion object {
        const val ACTIVE_FILE_NAME = "events.log"
        const val PREVIOUS_FILE_NAME = "events.previous.log"
        const val MAX_FILE_BYTES = 128 * 1024
        const val MAX_LINE_CHARACTERS = 4096
    }
}
