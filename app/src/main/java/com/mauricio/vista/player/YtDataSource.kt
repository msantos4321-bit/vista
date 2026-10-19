package com.mauricio.vista.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.mauricio.vista.data.Net
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.io.InputStream

/**
 * Lee el video de los servidores de YouTube igual que la app oficial:
 * pide el pedazo exacto con «&range=» (evita que YouTube baje la velocidad)
 * y numera cada petición con «&rn=». Basado en el YoutubeHttpDataSource de NewPipe.
 */
@UnstableApi
class YtDataSource(private val client: OkHttpClient) : BaseDataSource(true) {

    class Factory(private val client: OkHttpClient = Net.client) : DataSource.Factory {
        override fun createDataSource(): DataSource = YtDataSource(client)
    }

    private var spec: DataSpec? = null
    private var response: Response? = null
    private var stream: InputStream? = null
    private var opened = false
    private var bytesToRead = 0L
    private var bytesRead = 0L
    private var requestNumber = 0L

    override fun getUri(): Uri? = spec?.uri

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        bytesRead = 0
        bytesToRead = 0
        transferInitializing(dataSpec)

        var url = dataSpec.uri.toString()
        val isPlayback = dataSpec.uri.path?.startsWith("/videoplayback") == true
        val unset = C.LENGTH_UNSET.toLong()
        val b = Request.Builder()
        if (isPlayback) {
            if (!url.contains("&rn=")) url += "&rn=" + (requestNumber++)
            if (dataSpec.position != 0L || dataSpec.length != unset) {
                url += "&range=" + dataSpec.position + "-" + (if (dataSpec.length != unset) (dataSpec.position + dataSpec.length - 1).toString() else "")
            }
            b.url(url)
            if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
                b.header("Origin", "https://www.youtube.com")
                b.header("Referer", "https://www.youtube.com")
                b.header("Sec-Fetch-Dest", "empty")
                b.header("Sec-Fetch-Mode", "cors")
                b.header("Sec-Fetch-Site", "cross-site")
            }
            val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) YoutubeParsingHelper.getVisionOsUserAgent(null) else Net.UA
            b.header("User-Agent", ua)
            b.header("Accept-Encoding", "identity")
            // Casi todos los clientes de YouTube piden el video con POST
            b.post(byteArrayOf(0x78, 0).toRequestBody(null))
        } else {
            b.url(url).header("User-Agent", Net.UA)
            if (dataSpec.position != 0L || dataSpec.length != unset) {
                b.header("Range", "bytes=" + dataSpec.position + "-" + (if (dataSpec.length != unset) (dataSpec.position + dataSpec.length - 1).toString() else ""))
            }
        }
        dataSpec.httpRequestHeaders.forEach { (k, v) -> b.header(k, v) }

        val resp = try {
            client.newCall(b.build()).execute()
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        if (!resp.isSuccessful) {
            val code = resp.code
            val msg = resp.message
            val headers = resp.headers.toMultimap()
            val body = runCatching { resp.body?.bytes() }.getOrNull() ?: ByteArray(0)
            resp.close()
            throw HttpDataSource.InvalidResponseCodeException(code, msg, null, headers, dataSpec, body)
        }
        response = resp
        val body = resp.body ?: run {
            resp.close()
            throw HttpDataSource.HttpDataSourceException("Respuesta vacía", dataSpec, androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
        stream = body.byteStream()
        val skip = if (!isPlayback && resp.code == 200 && dataSpec.position > 0) dataSpec.position else 0L
        val len = body.contentLength()
        bytesToRead = when {
            dataSpec.length != unset -> dataSpec.length
            len >= 0 -> len - skip
            else -> unset
        }
        opened = true
        transferStarted(dataSpec)
        if (skip > 0) skipFully(skip, dataSpec)
        return bytesToRead
    }

    private fun skipFully(n: Long, dataSpec: DataSpec) {
        var left = n
        val buf = ByteArray(8192)
        try {
            while (left > 0) {
                val r = stream!!.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                if (r < 0) throw IOException("Fin antes de tiempo")
                left -= r
            }
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, dataSpec, HttpDataSource.HttpDataSourceException.TYPE_OPEN)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        var len = length
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining <= 0) return C.RESULT_END_OF_INPUT
            len = minOf(len.toLong(), remaining).toInt()
        }
        val n = try {
            stream!!.read(buffer, offset, len)
        } catch (e: IOException) {
            throw HttpDataSource.HttpDataSourceException.createForIOException(e, spec!!, HttpDataSource.HttpDataSourceException.TYPE_READ)
        }
        if (n == -1) return C.RESULT_END_OF_INPUT
        bytesRead += n
        bytesTransferred(n)
        return n
    }

    override fun close() {
        try { stream?.close() } catch (_: IOException) {}
        try { response?.close() } catch (_: Exception) {}
        stream = null
        response = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
