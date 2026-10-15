package persistence

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Stable binary encoding for embeddings before AES-GCM encryption. */
object FloatVectorCodec {
    fun encode(values: List<Float>): ByteArray = ByteBuffer
        .allocate(values.size * java.lang.Float.BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .apply { values.forEach(::putFloat) }
        .array()

    fun decode(bytes: ByteArray): List<Float> {
        require(bytes.size % java.lang.Float.BYTES == 0) { "Invalid encoded float vector length" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return buildList(bytes.size / java.lang.Float.BYTES) {
            while (buffer.hasRemaining()) add(buffer.float)
        }
    }
}
