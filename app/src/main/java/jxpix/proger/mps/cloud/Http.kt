package jxpix.proger.mps.cloud

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal class HttpResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

/**
 * The few kinds of request the app makes, on the HTTP client that every Android has.
 * Only GET, POST and DELETE are used: they work the same on a phone and in tests on a computer.
 */
internal object Http {
    private const val CONNECT_MS = 12_000
    private const val READ_MS = 30_000
    private const val TRANSFER_MS = 60_000

    @Throws(IOException::class)
    fun send(method: String, url: String, headers: Map<String, String>, body: ByteArray? = null): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = CONNECT_MS
            c.readTimeout = READ_MS
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            // a POST always carries a body, even an empty one: some gateways refuse it otherwise
            val payload = body ?: if (method == "POST") ByteArray(0) else null
            if (payload != null) {
                c.doOutput = true
                c.setFixedLengthStreamingMode(payload.size)
                c.outputStream.use { it.write(payload) }
            }
            return read(c)
        } finally {
            c.disconnect()
        }
    }

    /** Sends a file as the body of a POST. [progress] gets 0..1 and returns false to cancel. */
    @Throws(IOException::class)
    fun upload(url: String, headers: Map<String, String>, file: File, progress: (Float) -> Boolean): HttpResponse {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = CONNECT_MS
            c.readTimeout = TRANSFER_MS
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) c.setRequestProperty(k, v)
            c.doOutput = true
            val total = file.length()
            c.setFixedLengthStreamingMode(total)
            val buffer = ByteArray(32 * 1024)
            var sent = 0L
            file.inputStream().use { input ->
                c.outputStream.use { out ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        sent += n
                        if (!progress(if (total > 0) sent.toFloat() / total else 1f)) {
                            throw CloudException(CloudException.Kind.CANCELLED)
                        }
                    }
                }
            }
            return read(c)
        } finally {
            c.disconnect()
        }
    }

    /** Saves the body of a GET to [to]; nothing is left behind when it fails. Returns the HTTP status. */
    @Throws(IOException::class)
    fun download(url: String, to: File, progress: (Float) -> Boolean): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        val part = File(to.path + ".part")
        try {
            c.connectTimeout = CONNECT_MS
            c.readTimeout = TRANSFER_MS
            c.instanceFollowRedirects = true
            val code = c.responseCode
            if (code !in 200..299) return code
            val total = c.contentLengthLong
            val buffer = ByteArray(32 * 1024)
            var got = 0L
            to.parentFile?.mkdirs()
            c.inputStream.use { input ->
                part.outputStream().use { out ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        got += n
                        if (!progress(if (total > 0) (got.toFloat() / total).coerceAtMost(1f) else 0f)) {
                            throw CloudException(CloudException.Kind.CANCELLED)
                        }
                    }
                }
            }
            if (total in 1 until Long.MAX_VALUE && got != total) throw IOException("Incomplete download: $got of $total")
            to.delete()
            if (!part.renameTo(to)) throw IOException("Cannot save ${to.name}")
            return code
        } finally {
            part.delete()
            c.disconnect()
        }
    }

    private fun read(c: HttpURLConnection): HttpResponse {
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return HttpResponse(code, text)
    }
}
