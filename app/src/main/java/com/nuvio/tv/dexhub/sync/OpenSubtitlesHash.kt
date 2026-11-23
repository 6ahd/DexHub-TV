package com.nuvio.tv.dexhub.sync

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * The OpenSubtitles hash of the playing file (its size plus the 64-bit sums of its first and last 64 KB), read
 * with two ranged requests like a player's; the same on every device for the same file. Null when the link does
 * not answer ranges.
 */
internal object OpenSubtitlesHash {
    private const val Chunk = 65536
    private val http = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

    private fun range(url: String, headers: Map<String, String>, from: Long): Pair<ByteArray, Long>? {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=$from-${from + Chunk - 1}").build()
        return http.newCall(request).execute().use { r ->
            if (r.code != 206) return null
            val total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: return null
            val bytes = r.body?.byteStream()?.readNBytesCompat(Chunk) ?: return null
            bytes to total
        }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val k = read(out, got, n - got)
            if (k < 0) break
            got += k
        }
        return if (got == n) out else out.copyOf(got)
    }

    fun sum(size: Long, head: ByteArray, tail: ByteArray): String {
        var h = size
        for (buf in listOf(head, tail)) {
            val b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
            while (b.remaining() >= 8) h += b.long
        }
        return "%016x".format(h)
    }

    /** (hash, size), or null. */
    fun of(url: String, headers: Map<String, String>): Pair<String, Long>? = runCatching {
        if (!url.startsWith("http")) return null
        val (head, size) = range(url, headers, 0) ?: return null
        if (size < Chunk * 2 || head.size < Chunk) return null
        val (tail, _) = range(url, headers, size - Chunk) ?: return null
        if (tail.size < Chunk) return null
        sum(size, head, tail) to size
    }.getOrNull()
}
