package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Imports user-created Supertonic voice-style files into the currently active
 * model bundle. A voice style is a JSON object containing style_ttl and
 * style_dp tensor components.
 */
object VoiceStyleManager {
    private const val TEMP_FILE_NAME = "voice_style_import.tmp"
    private const val INSTALL_FILE_NAME = ".voice_style.installing"
    private const val MAX_IMPORT_BYTES = 32L * 1024L * 1024L

    private val builtInVoiceFiles = setOf(
        "M1.json", "M2.json", "M3.json", "M4.json", "M5.json",
        "F1.json", "F2.json", "F3.json", "F4.json", "F5.json"
    )

    class InvalidVoiceStyleException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    data class ImportResult(
        val fileName: String,
        val displayName: String
    )

    /**
     * Copy, validate, and install a user-provided style in the active model's
     * voice_styles directory. The source URI is consumed immediately, so no
     * external storage permission or persisted URI grant is required.
     */
    fun importFromUri(context: Context, uri: Uri, modelVersion: String): ImportResult {
        require(modelVersion in setOf("v1", "v2", "v3")) {
            "Unknown model version: $modelVersion"
        }

        val tempFile = File(context.cacheDir, TEMP_FILE_NAME)
        tempFile.delete()

        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open selected file")
            val copied = source.use { input ->
                FileOutputStream(tempFile).use { output ->
                    copyWithCap(input, output, MAX_IMPORT_BYTES)
                }
            }
            if (copied < 0) {
                throw InvalidVoiceStyleException("Voice style file is larger than 32 MB")
            }

            val json = try {
                tempFile.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                throw InvalidVoiceStyleException("Unable to read voice style JSON", e)
            }
            validateJson(json)

            val requestedName = resolveDisplayName(context, uri)
            val fileName = safeFileName(requestedName)
            val voiceDir = File(context.filesDir, "$modelVersion/voice_styles")
            if (!voiceDir.exists() && !voiceDir.mkdirs()) {
                throw IOException("Unable to create voice style directory")
            }

            val destination = File(voiceDir, fileName)
            val canonicalDir = voiceDir.canonicalFile
            if (destination.canonicalFile.parentFile != canonicalDir) {
                throw IOException("Invalid voice style destination")
            }

            // Copy the already-validated bytes to a staging file, then replace
            // the destination. This avoids leaving a partial JSON file behind
            // if the cache and files directories are different mounts.
            val staging = File(voiceDir, INSTALL_FILE_NAME)
            staging.delete()
            try {
                tempFile.inputStream().use { input ->
                    FileOutputStream(staging).use { output -> input.copyTo(output) }
                }
                if (destination.exists() && !destination.delete()) {
                    throw IOException("Unable to replace existing voice style")
                }
                if (!staging.renameTo(destination)) {
                    staging.copyTo(destination, overwrite = true)
                    staging.delete()
                }
            } finally {
                staging.delete()
            }

            return ImportResult(
                fileName = fileName,
                displayName = fileName.removeSuffix(".json")
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun validateJson(json: String) {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw InvalidVoiceStyleException("Invalid JSON", e)
        }

        validateComponent(root, "style_ttl")
        validateComponent(root, "style_dp")
    }

    private fun validateComponent(root: JSONObject, name: String) {
        val component = root.optJSONObject(name)
            ?: throw InvalidVoiceStyleException("Missing $name component")
        val dimensions = component.optJSONArray("dims")
        val data = component.optJSONArray("data")
        val type = component.optString("type")
        if (dimensions == null || dimensions.length() != 3 ||
            data == null || data.length() == 0 ||
            data.optJSONArray(0) == null || type.isBlank()
        ) {
            throw InvalidVoiceStyleException("Invalid $name tensor component")
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun safeFileName(displayName: String?): String {
        val raw = displayName?.trim()?.let { File(it).name }.orEmpty()
        val withoutExtension = if (raw.endsWith(".json", ignoreCase = true)) {
            raw.dropLast(5)
        } else {
            raw
        }
        var base = withoutExtension
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .trim('.')
        if (base.isBlank()) base = "custom_voice_${System.currentTimeMillis()}"

        var fileName = "$base.json"
        if (builtInVoiceFiles.contains(fileName)) {
            fileName = "custom_$fileName"
        }
        return fileName
    }

    /** Return -1 when the source exceeds [cap]. */
    private fun copyWithCap(input: InputStream, output: FileOutputStream, cap: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return total
            total += read
            if (total > cap) return -1L
            output.write(buffer, 0, read)
        }
    }
}
