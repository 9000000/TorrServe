package ru.yourok.torrserve.ui.fragments.main.update.apk

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.text.Spanned
import androidx.core.content.FileProvider
import androidx.core.text.HtmlCompat
import com.google.gson.Gson
import ru.yourok.torrserve.BuildConfig
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.app.Consts
import ru.yourok.torrserve.utils.Http
import ru.yourok.torrserve.utils.Net
import java.io.File
import java.io.FileOutputStream

object UpdaterApk {
    private var versions: ApkVersions? = null
    private var newVersion: ApkVersion? = null

    private fun parseVersionParts(versionStr: String?): List<Int> {
        if (versionStr.isNullOrBlank()) return emptyList()
        val regex = Regex("\\d+")
        return regex.findAll(versionStr).mapNotNull { it.value.toIntOrNull() }.toList()
    }

    private fun compareVersions(v1: String, v2: String): Int {
        val p1 = parseVersionParts(v1)
        val p2 = parseVersionParts(v2)
        val maxLen = maxOf(p1.size, p2.size)
        for (i in 0 until maxLen) {
            val num1 = if (i < p1.size) p1[i] else 0
            val num2 = if (i < p2.size) p2[i] else 0
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }
        return 0
    }

    private fun extractVersionCode(versionStr: String?): Int {
        if (versionStr.isNullOrBlank()) return 0
        val parts = parseVersionParts(versionStr)
        return parts.firstOrNull() ?: 0
    }

    fun isNewerVersion(remoteVersion: String, remoteCode: Int): Boolean {
        val currentName = BuildConfig.VERSION_NAME.trim()
        val currentCode = BuildConfig.VERSION_CODE
        val rName = remoteVersion.trim()

        // Nếu trùng versionName hoàn toàn -> chỉ coi là mới nếu remoteCode lớn hơn currentCode (vd build hotfix)
        if (rName.equals(currentName, ignoreCase = true)) {
            return remoteCode > currentCode
        }

        // So sánh theo thứ tự semantic version (vd MatriX.147.Client > MatriX.146.2.Client)
        val cmp = compareVersions(rName, currentName)
        if (cmp > 0) {
            return true
        } else if (cmp < 0) {
            return false
        }

        // Nếu chuỗi version tương đương, so sánh theo versionCode
        if (remoteCode > currentCode) {
            return true
        }

        return false
    }

    fun check(): Boolean {
        newVersion = null
        val urls = listOf(
            Consts.UPDATE_APK_PATH,
            Consts.UPDATE_APK_PATH_CDN,
            Consts.UPDATE_APK_PATH_FALLBACK,
            Consts.UPDATE_APK_PATH_FALLBACK_CDN
        )
        val gson = Gson()
        val now = System.currentTimeMillis()

        for (url in urls) {
            try {
                val queryUrl = if (url.contains("?")) "$url&t=$now" else "$url?t=$now"
                val body = Net.get(queryUrl, 15000).trim()
                if (body.isEmpty()) continue

                if (body.startsWith("[")) {
                    val list = gson.fromJson(body, ApkVersions::class.java)
                    if (!list.isNullOrEmpty()) {
                        versions = list
                        var found = false
                        for (ver in list) {
                            val code = if (ver.versionInt > 0) ver.versionInt else extractVersionCode(ver.version)
                            if (isNewerVersion(ver.version, code)) {
                                newVersion = ver.copy(versionInt = code)
                                found = true
                                break
                            }
                        }
                        if (found) {
                            return true
                        }
                        // Nếu trong list tĩnh chưa có bản mới hơn, tiếp tục kiểm tra GitHub Releases API bên dưới
                        break
                    }
                } else if (body.startsWith("{")) {
                    val single = gson.fromJson(body, ApkVersion::class.java)
                    if (single != null && single.link.isNotBlank()) {
                        val code = if (single.versionInt > 0) single.versionInt else extractVersionCode(single.version)
                        val list = ApkVersions().apply { add(single.copy(versionInt = code)) }
                        versions = list
                        if (isNewerVersion(single.version, code)) {
                            newVersion = single.copy(versionInt = code)
                            return true
                        }
                        break
                    }
                }
            } catch (_: Exception) {
                // Try next mirror
            }
        }

        // Fallback: GitHub Releases API (nguồn chính xác tức thì khi phát hành bản mới)
        try {
            val apiUrl = "https://api.github.com/repos/9000000/TorrServe/releases"
            val body = Net.get(apiUrl, 15000).trim()
            if (body.startsWith("[")) {
                val releases = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                val list = ApkVersions()
                for (item in releases) {
                    val relObj = item.asJsonObject
                    val tag = relObj.get("tag_name")?.asString ?: ""
                    val bodyDesc = relObj.get("body")?.asString ?: ""
                    var apkUrl = ""
                    val assets = relObj.getAsJsonArray("assets")
                    if (assets != null) {
                        for (a in assets) {
                            val aObj = a.asJsonObject
                            val aName = aObj.get("name")?.asString ?: ""
                            if (aName.endsWith(".apk", ignoreCase = true)) {
                                apkUrl = aObj.get("browser_download_url")?.asString ?: ""
                                break
                            }
                        }
                    }
                    if (apkUrl.isNotBlank()) {
                        val code = extractVersionCode(tag)
                        list.add(ApkVersion(desc = bodyDesc, link = apkUrl, version = tag, versionInt = code))
                    }
                }
                if (list.isNotEmpty()) {
                    if (versions == null || versions?.isEmpty() == true) {
                        versions = list
                    }
                    for (ver in list) {
                        if (isNewerVersion(ver.version, ver.versionInt)) {
                            newVersion = ver
                            return true
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        return false
    }

    fun getVersion(): String {
        if (newVersion == null)
            check()
        return newVersion?.version ?: ""
    }

    fun getOverview(): Spanned {
        var ret = ""

        versions?.forEach { ver ->
            if (isNewerVersion(ver.version, ver.versionInt)) {
                ret += "<font color='white'><b>${ver.version}</b></font><br/><br/>"
                ret += "<i>${ver.desc.replace("\n", "<br/>")}</i><br/><br/><br/>"
            } else {
                ret += "${ver.version}<br/><br/>"
                ret += "<i>${ver.desc.replace("\n", "<br>")}</i><br/><br/><br/>"
            }
        }
        return HtmlCompat.fromHtml(ret.trim(), HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private val download = Any()

    private fun downloadApk(file: File, onProgress: ((prc: Int) -> Unit)?) {
        synchronized(download) {
            newVersion?.let { ver ->
                val urls = mutableListOf(ver.link)
                if (ver.link.contains("github.com", ignoreCase = true)) {
                    urls.add("https://ghproxy.net/${ver.link}")
                    urls.add("https://mirror.ghproxy.com/${ver.link}")
                }

                for (downloadUrl in urls) {
                    try {
                        if (file.exists()) file.delete()
                        val conn = Http(Uri.parse(downloadUrl))
                        conn.connect()
                        conn.getInputStream()?.use { input ->
                            FileOutputStream(file).use { fileOut ->
                                val contentLength = conn.getSize()
                                if (onProgress == null) {
                                    input.copyTo(fileOut)
                                } else {
                                    val buffer = ByteArray(65535)
                                    val length = contentLength + 1
                                    var offset: Long = 0
                                    while (true) {
                                        val readed = input.read(buffer)
                                        if (readed <= 0) break
                                        offset += readed
                                        val prc = if (length > 1) (offset * 100 / length).toInt() else 0
                                        onProgress(prc)
                                        fileOut.write(buffer, 0, readed)
                                    }
                                    fileOut.flush()
                                }
                                fileOut.flush()
                            }
                        }
                        conn.close()
                        if (file.exists() && file.length() > 0) {
                            return // Download succeeded
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        if (file.exists()) file.delete()
                    }
                }
            }
        }
    }

    fun installNewVersion(onProgress: ((prc: Int) -> Unit)?) {
        if (newVersion == null && !check())
            return

        newVersion?.let {
            val destination = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "TorrServe.apk"
            ).apply {
                mkdirs()
                deleteOnExit()
            }
            downloadApk(destination, onProgress)
            if (destination.exists()) {
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
                    val uri = Uri.fromFile(destination)
                    val install = Intent(Intent.ACTION_VIEW)
                    install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    install.setDataAndType(uri, "application/vnd.android.package-archive")
                    if (install.resolveActivity(App.context.packageManager) != null)
                        App.context.startActivity(install)
                    else
                        App.toast(R.string.error_app_not_found)
                } else {
                    val fileUri =
                        FileProvider.getUriForFile(
                            App.context,
                            BuildConfig.APPLICATION_ID + ".provider",
                            destination
                        )
                    val install = Intent(Intent.ACTION_VIEW, fileUri)
                    install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    install.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (install.resolveActivity(App.context.packageManager) != null)
                        App.context.startActivity(install)
                    else
                        App.toast(R.string.error_app_not_found)
                }
            } else {
                App.toast(R.string.error_retrieve_data)
            }
        }
    }
}