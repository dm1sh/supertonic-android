package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
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

    // Used when a producer omits dims. When dims are present, their declared
    // dimensions are used after validating that they are positive and 3-D.
    private val defaultDimensions = mapOf(
        "style_ttl" to listOf(1, 50, 256),
        "style_dp" to listOf(1, 8, 16)
    )

    class InvalidVoiceStyleException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    data class ImportResult(
        val fileName: String,
        val displayName: String
    )

    /**
     * Copy, normalize, validate, and install a user-provided style in the
     * active model's voice_styles directory. The source URI is consumed
     * immediately, so no external storage permission or persisted URI grant is
     * required.
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

            // JSONObject is used here only for the small voice-style file. The
            // importer rewrites flat tensor data into the nested shape expected
            // by the native loader and supplies the omitted float32 type.
            val normalizedJson = normalizeJson(json)
            tempFile.bufferedWriter(Charsets.UTF_8).use { it.write(normalizedJson) }

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

            // Copy the normalized bytes to a staging file, then replace the
            // destination. This avoids leaving a partial JSON file behind if
            // the cache and files directories are different mounts.
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

    /**
     * Normalize both accepted input forms into the three-dimensional arrays
     * consumed by Rust: [batch][row][column]. Flat arrays are reshaped when
     * they contain at least the number of values required by dims. Extra
     * values are ignored because the declared tensor dimensions determine the
     * native tensor size.
     */
    private fun normalizeJson(json: String): String {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw InvalidVoiceStyleException("Invalid JSON", e)
        }

        normalizeComponent(root, "style_ttl")
        normalizeComponent(root, "style_dp")
        return root.toString()
    }

    private fun normalizeComponent(root: JSONObject, name: String) {
        val component = root.optJSONObject(name)
            ?: throw InvalidVoiceStyleException("Missing $name component")
        val dimensions = readDimensions(component, name)
        val data = component.optJSONArray("data")
            ?: throw InvalidVoiceStyleException("Missing $name data array")

        val values = ArrayList<Any>()
        flattenNumbers(data, values, name)
        var requiredValues = 1L
        for (dimension in dimensions) {
            if (requiredValues > Int.MAX_VALUE.toLong() / dimension.toLong()) {
                throw InvalidVoiceStyleException("$name dims are too large")
            }
            requiredValues *= dimension.toLong()
        }
        val requiredCount = requiredValues.toInt()
        if (values.size < requiredCount) {
            throw InvalidVoiceStyleException(
                "$name data has ${values.size} values, but ${requiredValues} are required"
            )
        }

        component.put("dims", JSONArray().also { array ->
            dimensions.forEach { array.put(it) }
        })
        component.put("data", reshape(values, dimensions))

        val typeValue = component.opt("type")
        if (typeValue == null || typeValue == JSONObject.NULL ||
            typeValue !is String || typeValue.isBlank()
        ) {
            component.put("type", "float32")
        }
    }

    private fun readDimensions(component: JSONObject, name: String): List<Int> {
        val dimensions = component.optJSONArray("dims")
        if (dimensions == null) return defaultDimensions.getValue(name)
        if (dimensions.length() != 3) {
            throw InvalidVoiceStyleException("$name dims must contain exactly three values")
        }

        val result = (0 until dimensions.length()).map { dimensions.optInt(it, 0) }
        if (result.any { it <= 0 }) {
            throw InvalidVoiceStyleException("$name dims must contain positive values")
        }
        return result
    }

    private fun flattenNumbers(value: Any?, output: MutableList<Any>, name: String) {
        when (value) {
            is JSONArray -> {
                for (index in 0 until value.length()) {
                    flattenNumbers(value.opt(index), output, name)
                }
            }
            is Number -> output.add(value)
            else -> throw InvalidVoiceStyleException("$name data must contain only numeric values")
        }
    }

    private fun reshape(values: List<Any>, dimensions: List<Int>): JSONArray {
        var offset = 0

        fun build(level: Int): JSONArray {
            val array = JSONArray()
            repeat(dimensions[level]) {
                if (level == dimensions.lastIndex) {
                    array.put(values[offset++])
                } else {
                    array.put(build(level + 1))
                }
            }
            return array
        }

        return build(0)
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
