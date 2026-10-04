package ru.yourok.torrserve.ui.fragments.main.update.server

import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import kotlinx.coroutines.*
import ru.yourok.torrserve.R
import ru.yourok.torrserve.app.App
import ru.yourok.torrserve.app.Consts
import ru.yourok.torrserve.server.api.Api
import ru.yourok.torrserve.server.local.ServerFile
import ru.yourok.torrserve.server.local.TorrService
//import ru.yourok.torrserve.utils.Accessibility
import ru.yourok.torrserve.utils.Http
import ru.yourok.torrserve.utils.Net
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object UpdaterServer {
    private var version: ServVersion? = null
    private var error: String = ""
    private val serverFile = ServerFile()

    suspend fun getLocalVersion(): String {
        var lv: String
        if (TorrService.isLocal()) {
            if (!serverFile.exists()) {
                lv = App.context.getString(R.string.not_installed)
            } else {
                //TorrService.start()
                withContext(Dispatchers.IO) {
                    lv = Api.echo()
                }
            }
        } else {
            lv = App.context.getString(R.string.not_used)
        }
        return lv
    }

    fun updateFromNet(onProgress: ((prc: Int) -> Unit)?) {
        val url = getLink()
        if (url.isNotBlank()) {
            val http = Http(Uri.parse(url))
            http.connect()
            if (TorrService.isLocal() && Api.echo().isNotEmpty()) {
//                if (Accessibility.isEnabledService(App.context))
//                    Accessibility.enableService(App.context, false)
                TorrService.stop()
                ServerFile().stop()
            }
            http.getInputStream().also { content ->
                content ?: throw IOException("error connect server, url: $url")

                val serverFile = ServerFile()
                val updateFile = File(App.context.filesDir, "torrserver_update")
                val contentLength = http.getSize()

                FileOutputStream(updateFile).use { fileOut ->
                    if (onProgress == null)
                        content.copyTo(fileOut)
                    else {
                        val buffer = ByteArray(65535)
                        val length = contentLength + 1
                        var offset: Long = 0
                        while (true) {
                            val readed = content.read(buffer)
                            offset += readed
                            val prc = (offset * 100 / length).toInt()
                            onProgress(prc)
                            if (readed <= 0) break
                            fileOut.write(buffer, 0, readed)
                        }
                        fileOut.flush()
                    }
                    fileOut.flush()
                    fileOut.close()
                    if (!updateFile.renameTo(serverFile)) {
                        updateFile.delete()
                        throw IOException("error write torrserver update")
                    }
                    if (!serverFile.setExecutable(true)) {
                        serverFile.delete()
                        throw IOException("error set exec permission")
                    }
                }
            }
        }
        if (TorrService.isLocal()) {
            TorrService.start()
        }
    }

    fun updateFromFile(filePath: String) {
        if (TorrService.isLocal() && Api.echo().isNotEmpty()) {
//            if (Accessibility.isEnabledService(App.context))
//                Accessibility.enableService(App.context, false)
            TorrService.stop()
            ServerFile().stop()
        }
        val file = File(filePath)
        if (file.canRead()) {
            val serverFile = ServerFile()
            serverFile.delete()
            val input = FileInputStream(file)
            val output = FileOutputStream(serverFile)
            input.copyTo(output)
            input.close()
            output.flush()
            output.close()
            if (!serverFile.setExecutable(true))
                throw IOException("error set server exec permission")
        }
        if (TorrService.isLocal()) {
            TorrService.start()
        }
    }

    private fun normalizeArch(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val abi = raw.trim().lowercase()
        return when {
            abi.contains("arm64") || abi.contains("aarch64") || abi.contains("armv8") -> "arm64"
            abi.contains("x86_64") || abi.contains("amd64") || abi == "x64" -> "amd64"
            abi.contains("x86") || abi.contains("i386") || abi.contains("i686") || abi == "386" -> "386"
            abi.contains("v7") || abi.contains("arm7") || abi.startsWith("armeabi") || abi.startsWith("arm") -> "armv7"
            else -> ""
        }
    }

    @Suppress("DEPRECATION")
    fun getArch(): String {
        val candidates = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS?.let { candidates.addAll(it) }
        }
        if (!Build.CPU_ABI.isNullOrBlank()) candidates.add(Build.CPU_ABI)
        if (!Build.CPU_ABI2.isNullOrBlank()) candidates.add(Build.CPU_ABI2)
        System.getProperty("os.arch")?.let {
            if (it.isNotBlank()) candidates.add(it)
        }

        for (abi in candidates) {
            val arch = normalizeArch(abi)
            if (arch.isNotEmpty()) {
                return arch
            }
        }
        return ""
    }

    fun check(): Boolean {
        return try {
            val body = Net.get(Consts.UPDATE_SERVER_PATH)
            val gson = Gson()
            version = gson.fromJson(body, ServVersion::class.java)
            true
        } catch (e: Exception) {
            error = e.message ?: App.context.getString(R.string.warn_error_check_ver)
            App.toast(error)
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    fun downloadFFProbe(onProgress: ((prc: Int) -> Unit)?) {
        val fileZip = File(App.context.filesDir, "ffprobe.zip")
        val file = File(App.context.filesDir, "ffprobe")

        if (file.exists())
            return

        val arch = getArch()
        var link = ""
        when (arch) {
            "arm7", "armv7" -> link = "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v4.4.1/ffprobe-4.4.1-linux-armhf-32.zip"
            "arm64" -> link = "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v4.4.1/ffprobe-4.4.1-linux-arm-64.zip"
            "386" -> link = "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v4.4.1/ffprobe-4.4.1-linux-32.zip"
            "amd64" -> link = "https://github.com/ffbinaries/ffbinaries-prebuilt/releases/download/v4.4.1/ffprobe-4.4.1-linux-64.zip"
        }
        val http = Http(Uri.parse(link))
        http.connect()
        http.getInputStream().also { content ->
            content ?: let {
                fileZip.delete()
                file.delete()
                throw IOException("error connect server, url: $link")
            }

            val contentLength = http.getSize()

            FileOutputStream(fileZip).use { fileOut ->
                if (onProgress == null)
                    content.copyTo(fileOut)
                else {
                    val buffer = ByteArray(65535)
                    val length = contentLength + 1
                    var offset: Long = 0
                    while (true) {
                        val readed = content.read(buffer)
                        offset += readed
                        val prc = (offset * 100 / length).toInt()
                        onProgress(prc)
                        if (readed <= 0) break
                        fileOut.write(buffer, 0, readed)
                    }
                    fileOut.flush()
                }
                fileOut.flush()
                fileOut.close()

                fileZip.unzip(App.context.filesDir)
                fileZip.delete()
                if (!file.setExecutable(true)) {
                    file.delete()
                    throw IOException("error set exec permission")
                }
            }
        }
    }

    fun getRemoteVersion(): String {
        var rv = App.context.getString(R.string.no_updates)
        if (version == null)
            check()
        runBlocking {
            val lv: Deferred<String> = async(context = Dispatchers.IO) {
                getLocalVersion()
            }
            version?.let {
                if (it.version != lv.await())
                    rv = it.version
            }
        }
        if (version == null)
            rv = App.context.getString(R.string.no_data) // error
        return rv
    }

    private fun getLink(): String {
        if (version == null)
            check()
        if (version == null)
            return ""
        version?.let { ver ->
            val arch = getArch()
            if (arch.isEmpty())
                throw IOException("error get arch")

            val isOldAndroid = Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP
            val primaryPrefix = if (isOldAndroid) "linux" else "android"
            val fallbackPrefix = if (isOldAndroid) "android" else "linux"

            val keyCandidates = mutableListOf<String>()

            fun addArchKeys(prefix: String) {
                when (arch) {
                    "armv7", "arm7", "arm" -> {
                        keyCandidates.add("$prefix-armv7")
                        keyCandidates.add("$prefix-arm7")
                        keyCandidates.add("$prefix-arm")
                        keyCandidates.add("$prefix-armeabi-v7a")
                    }
                    "arm64" -> {
                        keyCandidates.add("$prefix-arm64")
                        keyCandidates.add("$prefix-arm64-v8a")
                        keyCandidates.add("$prefix-aarch64")
                    }
                    "amd64" -> {
                        keyCandidates.add("$prefix-amd64")
                        keyCandidates.add("$prefix-x86_64")
                    }
                    "386" -> {
                        keyCandidates.add("$prefix-386")
                        keyCandidates.add("$prefix-x86")
                    }
                    else -> keyCandidates.add("$prefix-$arch")
                }
            }

            addArchKeys(primaryPrefix)
            addArchKeys(fallbackPrefix)

            for (key in keyCandidates) {
                val url = ver.links[key]
                if (!url.isNullOrBlank()) {
                    return url
                }
            }

            for (key in keyCandidates) {
                val found = ver.links.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
                if (!found.isNullOrBlank()) {
                    return found
                }
            }
        }
        return ""
    }

    data class ZipIO(val entry: ZipEntry, val output: File)

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun File.unzip(unzipLocationRoot: File? = null) {

        val rootFolder = unzipLocationRoot ?: File(parentFile!!.absolutePath + File.separator + nameWithoutExtension)
        if (!rootFolder.isDirectory) rootFolder.mkdirs()

        ZipFile(this).use { zip ->
            zip.entries().asSequence().map {
                val outputFile = File(rootFolder.absolutePath + File.separator + it.name)
                ZipIO(it, outputFile)
            }.map {
                it.output.parentFile?.run {
                    if (!exists()) mkdirs()
                }
                it
            }.filter { !it.entry.isDirectory }.forEach { (entry, output) ->
                zip.getInputStream(entry).use { input ->
                    output.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }

    }

//    private suspend fun <T> withZipFromUri(
//        context: Context,
//        uri: Uri, block: suspend (ZipInputStream) -> T
//    ): T =
//        withContext(Dispatchers.IO) {
//            kotlin.run {
//                context.contentResolver.openInputStream(uri).use { input ->
//                    if (input == null) throw FileNotFoundException("openInputStream failed")
//                    ZipInputStream(input).use {
//                        block.invoke(it)
//                    }
//                }
//            }
//        }

}
