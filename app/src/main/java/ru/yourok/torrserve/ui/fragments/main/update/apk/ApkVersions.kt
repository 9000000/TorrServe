package ru.yourok.torrserve.ui.fragments.main.update.apk

import com.google.gson.annotations.SerializedName

class ApkVersions : ArrayList<ApkVersion>()

data class ApkVersion(
    @SerializedName(value = "desc", alternate = ["description", "body", "Name", "name"])
    val desc: String = "",

    @SerializedName(value = "link", alternate = ["Link", "browser_download_url"])
    val link: String = "",

    @SerializedName(value = "version", alternate = ["Version", "tag_name"])
    val version: String = "",

    @SerializedName(value = "versionInt", alternate = ["versionCode", "VersionCode"])
    val versionInt: Int = 0
)