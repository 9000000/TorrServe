package ru.yourok.torrserve.ui.fragments.main.update.server

import com.google.gson.annotations.SerializedName

data class ServVersion(
    @SerializedName(value = "version", alternate = ["Version", "tag_name", "name"])
    val version: String = "",

    @SerializedName(value = "links", alternate = ["Links"])
    val links: Map<String, String> = emptyMap()
)