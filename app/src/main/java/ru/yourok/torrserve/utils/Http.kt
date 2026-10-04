package ru.yourok.torrserve.utils

import android.net.Uri
import android.os.Build
import info.guardianproject.netcipher.NetCipher
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_MOVED_PERM
import java.net.HttpURLConnection.HTTP_MOVED_TEMP
import java.net.HttpURLConnection.HTTP_OK
import java.net.HttpURLConnection.HTTP_PARTIAL
import java.net.HttpURLConnection.HTTP_SEE_OTHER
import java.net.URL
import java.security.GeneralSecurityException
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection


/**
 * Created by yourok on 07.11.17.
 */

class Http(url: Uri) {
    private var currUrl: String = url.toString()
    private var isConn: Boolean = false
    private var connection: HttpURLConnection? = null
    private var errMsg: String = ""
    private var inputStream: InputStream? = null
    private var auth: String = ""

    private var timeout = 30000

    fun connect(pos: Long = 0): Long {

        var responseCode: Int
        var redirCount = 0
        do {
            if (!currUrl.contains("://"))
                currUrl = currUrl.replace(":/", "://")

            val url = URL(currUrl)

            connection = if (currUrl.startsWith("https")) {
                val conn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    url.openConnection() as HttpsURLConnection
                } else {
                    NetCipher.getHttpsURLConnection(url)
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                    val trustAllHostnames = HostnameVerifier { _, _ ->
                        true // Just allow them all
                    }
                    conn.hostnameVerifier = trustAllHostnames
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    try {
                        // Only TLSv1.2 and TLSv1.3 protocol available and trust all certs (insecure).
                        conn.sslSocketFactory = TlsSocketFactory()
                    } catch (_: GeneralSecurityException) {
                    }
                }
                conn
            } else {
                url.openConnection() as HttpURLConnection
            }

            connection!!.connectTimeout = timeout
            connection!!.readTimeout = 60000
            connection!!.requestMethod = "GET"
            connection!!.doInput = true

            connection!!.setRequestProperty("User-Agent", "DWL/1.1.0 (Linux; Android)")
            connection!!.setRequestProperty("UserAgent", "DWL/1.1.0 (Linux; Android)")
            connection!!.setRequestProperty("Accept", "*/*")
            connection!!.setRequestProperty("Accept-Encoding", "identity")
            if (pos > 0)
                connection!!.setRequestProperty("Range", "bytes=$pos-")

            if (auth.isNotBlank())
                connection!!.setRequestProperty("Authorization", auth)

            connection!!.connect()

            responseCode = connection!!.responseCode
            val redirected =
                responseCode == HTTP_MOVED_PERM || responseCode == HTTP_MOVED_TEMP || responseCode == HTTP_SEE_OTHER || responseCode == 307 || responseCode == 308
            if (redirected) {
                val loc = connection!!.getHeaderField("Location")
                if (!loc.isNullOrBlank()) {
                    currUrl = try {
                        URL(URL(currUrl), loc).toString()
                    } catch (_: Exception) {
                        loc
                    }
                }
                connection!!.disconnect()
                redirCount++
            }

            if (responseCode == 429) {
                var retry = connection!!.getHeaderField("Retry-After")
                if (retry.isNullOrEmpty() || retry == "0")
                    retry = "1"
                redirCount++
                Thread.sleep(retry.toLong() * 1000L)
            }

            if (redirCount > 5) {
                throw IOException("Error connect to: $currUrl too many redirects")
            }
        } while (redirected)


        if (responseCode != HTTP_OK && responseCode != HTTP_PARTIAL) {
            throw IOException("Error connect to: " + currUrl + " (" + responseCode + " " + connection!!.responseMessage + ")")
        }
        isConn = true
        if (connection!!.getHeaderField("Accept-Ranges")?.lowercase(Locale.getDefault()) == "none")
            return -1
        return getSize()
    }

    fun setTimeout(timeout: Int) {
        this.timeout = timeout
    }

    fun isConnected(): Boolean {
        return isConn
    }

    fun setAuth(auth: String) {
        this.auth = auth
    }

    fun getSize(): Long {
        if (!isConn)
            return 0

        var cl = connection!!.getHeaderField("Content-Range")
        try {
            if (!cl.isNullOrEmpty()) {
                val cr = cl.split("/")
                if (cr.isNotEmpty())
                    cl = cr.last()
                return cl.toLong()
            }
        } catch (_: Exception) {
        }

        cl = connection!!.getHeaderField("Content-Length")
        try {
            if (!cl.isNullOrEmpty()) {
                return cl.toLong()
            }
        } catch (_: Exception) {
        }

        return 0
    }

    fun getUrl(): String {
        return currUrl
    }

    fun getInputStream(): InputStream? {
        if (inputStream == null && connection != null) {
            inputStream = if ("gzip" == connection?.contentEncoding)
                GZIPInputStream(connection!!.inputStream)
            else
                connection!!.inputStream
        }

        return inputStream
    }

    fun read(b: ByteArray): Int {
        if (!isConn or (getInputStream() == null))
            throw IOException("connect before read")
        var sz = getInputStream()!!.read(b)
        var size = sz
        while (sz > 0 && sz < b.size / 2) {
            try {
                sz = getInputStream()!!.read(b, size, b.size - size)
                if (sz > 0)
                    size += sz
                else
                    break
            } catch (e: Exception) {
                e.printStackTrace()
                break
            }
        }
        return size
    }

    fun getErrorMessage(): String {
        return errMsg
    }

    fun close() {
        try {
            inputStream?.close()
        } catch (_: Exception) {
        }
        connection?.disconnect()
        isConn = false
    }
}