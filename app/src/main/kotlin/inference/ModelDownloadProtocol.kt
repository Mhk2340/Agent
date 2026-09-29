package inference

import runtime.AgentLogger
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * GEN-NET-1 — Model Asset Download Protocol
 * Handles secure, resumable asset ingestion with:
 * 1. HTTPS enforcement (TLS 1.2+)
 * 2. Range request support for resumability
 * 3. SHA-256 Checksum verification
 * 4. Exponential backoff retry logic
 * 5. Metered network awareness (requires explicit user confirmation if metered)
 */
class ModelDownloadProtocol(
    private val isMeteredNetworkProvider: () -> Boolean,
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000L
) {
    sealed class DownloadResult {
        data class Success(val bytes: ByteArray, val sha256: String) : DownloadResult()
        data class RequiresMeteredConfirmation(val url: String, val totalBytes: Long) : DownloadResult()
        data class IntegrityFailure(val expectedHash: String, val actualHash: String) : DownloadResult()
        data class NetworkFailure(val reason: String, val attemptsMade: Int) : DownloadResult()
    }

    interface HttpStreamSource {
        fun openRangeStream(url: String, startByte: Long): InputStream
        fun getContentLength(url: String): Long
    }

    fun downloadAsset(
        url: String,
        expectedSha256: String,
        streamSource: HttpStreamSource,
        userMeteredConfirmed: Boolean = false
    ): DownloadResult {
        if (!url.startsWith("https://", ignoreCase = true)) {
            return DownloadResult.NetworkFailure("Only HTTPS (TLS 1.2+) is allowed for model asset downloads", 0)
        }

        val totalLength = streamSource.getContentLength(url)
        val isMetered = isMeteredNetworkProvider()

        if (isMetered && !userMeteredConfirmed) {
            return DownloadResult.RequiresMeteredConfirmation(url, totalLength)
        }

        val output = ByteArrayOutputStream()
        var downloadedBytes = 0L
        var attempts = 0

        while (downloadedBytes < totalLength && attempts < maxRetries) {
            attempts++
            try {
                streamSource.openRangeStream(url, downloadedBytes).use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                    }
                }
            } catch (e: Exception) {
                AgentLogger.w("ModelDownloadProtocol", "Attempt $attempts failed at $downloadedBytes bytes: ${e.message}")
                if (attempts >= maxRetries) {
                    return DownloadResult.NetworkFailure("Max retries exceeded: ${e.message}", attempts)
                }
                // Exponential backoff
                val backoff = baseDelayMs * (1L shl (attempts - 1))
                Thread.sleep(backoff.coerceAtMost(5000L))
            }
        }

        val downloadedData = output.toByteArray()
        val calculatedHash = ChecksumUtil.sha256(downloadedData)

        if (!calculatedHash.equals(expectedSha256, ignoreCase = true)) {
            return DownloadResult.IntegrityFailure(expectedHash = expectedSha256, actualHash = calculatedHash)
        }

        return DownloadResult.Success(bytes = downloadedData, sha256 = calculatedHash)
    }
}
