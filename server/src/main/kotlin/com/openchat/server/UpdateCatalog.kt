package com.openchat.server

import com.openchat.server.protocol.UpdateResponse
import kotlinx.serialization.json.Json
import java.io.File

internal class UpdateCatalog(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true }

    fun manifest(): UpdateResponse? {
        val file = File(directory, "update.json")
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<UpdateResponse>(file.readText()) }.getOrNull()
    }

    fun apk(): File? {
        val file = File(directory, "openchat.apk")
        return file.takeIf { it.isFile && it.length() > 0 }
    }
}
