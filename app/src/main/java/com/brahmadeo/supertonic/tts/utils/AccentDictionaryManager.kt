package com.brahmadeo.supertonic.tts.utils

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import android.util.Log
import android.util.MalformedJsonException
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.regex.Pattern

/**
 * Large, word-indexed pronunciation dictionary imported from a flat JSON
 * object, for example: { "замок": "замо́к" }.
 *
 * This is deliberately separate from [LexiconManager]. The hand-authored
 * lexicon is a small ordered list of rules; this dictionary is a large map
 * and is looked up once per word during text normalization.
 *
 * Import is intentionally two-stage: the selected content URI is copied to a
 * capped temporary file and that file is parsed with [JsonReader]. The
 * temporary file is already valid JSON, so installation moves/copies it into
 * place instead of serializing the in-memory map a second time.
 */
object AccentDictionaryManager {
    private const val TAG = "AccentDictionary"
    private const val FILE_NAME = "accent_dictionary.json"
    private const val TEMP_FILE_NAME = "accent_import.tmp"
    private const val INSTALL_FILE_NAME = "accent_dictionary.installing"
    const val MAX_FILE_SIZE_MB = 250
    private const val MAX_FILE_BYTES = 250L * 1024L * 1024L

    private const val META_PREFS = "AccentDictionaryMeta"
    private const val META_KEY_SOURCE = "source"
    private const val META_KEY_ENTRIES = "entries"
    private const val META_KEY_LOADED_AT = "loaded_at"
    private const val META_KEY_SIZE_BYTES = "size_bytes"

    /** Negative results returned by [importFromUri]. */
    const val ERR_IO = -1
    const val ERR_OOM = -2
    const val ERR_PARSE = -3
    const val ERR_TOO_LARGE = -4

    data class Metadata(
        val source: String,
        val entries: Int,
        val loadedAtMs: Long,
        val sizeBytes: Long
    )

    @Volatile private var entries: Map<String, String> = emptyMap()
    @Volatile private var isLoaded = false

    // Include combining marks so a user rule that already added U+0301 is
    // consumed as part of the token and is not stressed a second time.
    private val wordPattern = Pattern.compile("[\\p{L}\\p{M}]+")

    /** Load the installed dictionary once per process. */
    @Synchronized
    fun load(context: Context) {
        if (isLoaded) return

        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            entries = emptyMap()
            isLoaded = true
            return
        }

        try {
            FileInputStream(file).use { input ->
                entries = parseJsonStream(input, file.length())
            }
            Log.i(TAG, "Loaded ${entries.size} accent entries")
        } catch (oom: OutOfMemoryError) {
            // A corrupt/too-large installed file must not take down the TTS
            // service. The UI can still clear it and import a smaller file.
            Log.e(TAG, "Out of memory loading accent dictionary", oom)
            entries = emptyMap()
        } catch (e: IOException) {
            Log.e(TAG, "I/O error loading accent dictionary", e)
            entries = emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Invalid accent dictionary JSON", e)
            entries = emptyMap()
        }
        isLoaded = true
    }

    @Synchronized
    fun reload(context: Context) {
        isLoaded = false
        load(context)
    }

    fun size(): Int = entries.size

    fun isReady(): Boolean = entries.isNotEmpty()

    fun getMetadata(context: Context): Metadata? {
        if (!File(context.filesDir, FILE_NAME).exists()) return null
        val prefs = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
        val source = prefs.getString(META_KEY_SOURCE, null) ?: return null
        return Metadata(
            source = source,
            entries = prefs.getInt(META_KEY_ENTRIES, 0),
            loadedAtMs = prefs.getLong(META_KEY_LOADED_AT, 0L),
            sizeBytes = prefs.getLong(META_KEY_SIZE_BYTES, 0L)
        )
    }

    /**
     * Apply imported entries to words in [text]. The caller controls the
     * language-specific path; the current normalizer deliberately skips this
     * for Korean, as it did for the existing lexicon.
     */
    fun apply(text: String, lang: String = ""): String {
        val activeEntries = entries
        if (activeEntries.isEmpty()) return text

        val matcher = wordPattern.matcher(text)
        val output = StringBuffer()
        while (matcher.find()) {
            val original = matcher.group() ?: continue
            val dictionaryValue = activeEntries[original.lowercase(Locale.ROOT)] ?: continue
            val replacement = applyCasing(original, dictionaryValue)
            matcher.appendReplacement(
                output,
                replacement.replace("\\", "\\\\").replace("$", "\\$")
            )
        }
        matcher.appendTail(output)
        return output.toString()
    }

    /**
     * Copy and parse a user-selected content URI without materializing it as
     * bytes or text. Returns the number of installed entries, zero for an
     * empty object, or a negative [ERR_*] code.
     */
    @Synchronized
    fun importFromUri(context: Context, uri: Uri): Int {
        val tmp = File(context.cacheDir, TEMP_FILE_NAME)
        tmp.delete()
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return ERR_IO
            val copied = input.use { source ->
                FileOutputStream(tmp).use { output ->
                    copyWithCap(source, output, MAX_FILE_BYTES)
                }
            }
            if (copied < 0) {
                tmp.delete()
                return ERR_TOO_LARGE
            }

            val parsed = FileInputStream(tmp).use { inputStream ->
                parseJsonStream(inputStream, tmp.length())
            }
            if (parsed.isEmpty()) {
                tmp.delete()
                return 0
            }

            installFromTemp(context, tmp, parsed, "Imported JSON file")
            parsed.size
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Out of memory importing accent dictionary", oom)
            tmp.delete()
            // Keep the previously installed dictionary active. The validated
            // temp file is not installed when parsing fails.
            ERR_OOM
        } catch (e: MalformedJsonException) {
            Log.e(TAG, "Malformed accent dictionary JSON", e)
            tmp.delete()
            ERR_PARSE
        } catch (e: IOException) {
            Log.e(TAG, "I/O error importing accent dictionary", e)
            tmp.delete()
            ERR_IO
        } catch (e: Exception) {
            Log.e(TAG, "Invalid accent dictionary JSON", e)
            tmp.delete()
            ERR_PARSE
        }
    }

    @Synchronized
    fun clear(context: Context) {
        entries = emptyMap()
        isLoaded = true
        File(context.filesDir, FILE_NAME).delete()
        File(context.filesDir, INSTALL_FILE_NAME).delete()
        context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(META_KEY_SOURCE)
            .remove(META_KEY_ENTRIES)
            .remove(META_KEY_LOADED_AT)
            .remove(META_KEY_SIZE_BYTES)
            .apply()
    }

    /**
     * Install the validated JSON file and then atomically swap the live map.
     * The map is never converted back to JSON, avoiding a second huge
     * allocation on top of the parsed dictionary.
     */
    private fun installFromTemp(
        context: Context,
        tmp: File,
        parsed: Map<String, String>,
        source: String
    ) {
        val target = File(context.filesDir, FILE_NAME)
        val installing = File(context.filesDir, INSTALL_FILE_NAME)
        installing.delete()

        try {
            if (!tmp.renameTo(target)) {
                // cacheDir and filesDir can be different mounts on some OEMs.
                // Copy the already-validated bytes; do not reserialize parsed.
                tmp.inputStream().use { input ->
                    FileOutputStream(installing).use { output ->
                        input.copyTo(output)
                    }
                }
                if (target.exists() && !target.delete()) {
                    throw IOException("Unable to replace installed dictionary")
                }
                if (!installing.renameTo(target)) {
                    throw IOException("Unable to install accent dictionary")
                }
                tmp.delete()
            }

            entries = parsed
            isLoaded = true
            val sizeBytes = target.length()
            context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(META_KEY_SOURCE, source)
                .putInt(META_KEY_ENTRIES, parsed.size)
                .putLong(META_KEY_LOADED_AT, System.currentTimeMillis())
                .putLong(META_KEY_SIZE_BYTES, sizeBytes)
                .apply()
        } finally {
            tmp.delete()
            installing.delete()
        }
    }

    /**
     * Parse { "word": "pronunciation", ... } one key/value pair at a time.
     * Only the final lookup map remains resident; there is no whole-file
     * String, object tree, or parse tree.
     */
    private fun parseJsonStream(input: InputStream, knownSize: Long): HashMap<String, String> {
        // The common Russian dictionary averages roughly 30–50 bytes/entry.
        // A bounded estimate avoids repeated HashMap growth without creating
        // an enormous table for a sparse or unusually compact input.
        val estimatedEntries = if (knownSize > 0) {
            (knownSize / 32L).toInt().coerceIn(64, 5_000_000)
        } else {
            64
        }
        val result = HashMap<String, String>(estimatedEntries)

        JsonReader(InputStreamReader(BufferedInputStream(input), Charsets.UTF_8)).use { reader ->
            reader.isLenient = true
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                val value = reader.nextString()
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key.lowercase(Locale.ROOT)] = value
                }
            }
            reader.endObject()
        }
        return result
    }

    /** Return -1 if more than [cap] bytes are present. */
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

    private fun applyCasing(original: String, replacement: String): String {
        val lower = original.lowercase(Locale.ROOT)
        if (original == lower) return replacement
        if (original == original.uppercase(Locale.ROOT)) {
            return replacement.uppercase(Locale.ROOT)
        }
        if (original.length > 1 && original[0].isUpperCase() &&
            original.substring(1) == original.substring(1).lowercase(Locale.ROOT)
        ) {
            return replacement.replaceFirstChar { it.uppercase() }
        }
        return replacement
    }
}
