package com.personal.aicomputer.storage

import org.json.JSONArray
import java.io.File

/** Tiny atomic JSON-array file store used for tasks and memory (local-first, no server). */
class JsonFileStore(private val file: File) {
    @Synchronized
    fun readArray(): JSONArray = try {
        if (file.exists()) JSONArray(file.readText()) else JSONArray()
    } catch (e: Exception) {
        // Keep the unreadable file for inspection instead of silently overwriting it.
        runCatching { file.copyTo(File(file.path + ".corrupt"), overwrite = true) }
        JSONArray()
    }

    @Synchronized
    fun writeArray(arr: JSONArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }
}
