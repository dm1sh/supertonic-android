package com.brahmadeo.supertonic.tts.viewmodel

import android.content.Context
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brahmadeo.supertonic.tts.utils.AssetManager
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {
    // UI State
    var inputText = mutableStateOf("")
    var isInitializing = mutableStateOf(true)
    var isSynthesizing = mutableStateOf(false)
    var canResume = mutableStateOf(false)

    // First-run model selection
    var showModelSelection = mutableStateOf(false)
    var modelSelectionEnglish = mutableStateOf(true)
    var modelSelectionV2 = mutableStateOf(false)
    var modelSelectionV3 = mutableStateOf(false)

    // Settings State
    var currentLang = mutableStateOf(DEFAULT_LANG)
    var selectedVoiceFile = mutableStateOf(DEFAULT_VOICE)
    var selectedVoiceFile2 = mutableStateOf(DEFAULT_VOICE_2)
    var isMixingEnabled = mutableStateOf(false)
    var mixAlpha = mutableFloatStateOf(0.5f)
    var currentSpeed = mutableFloatStateOf(DEFAULT_SPEED)
    var currentSteps = mutableIntStateOf(DEFAULT_STEPS)
    var isAdvancedNormalizationEnabled = mutableStateOf(false)
    var sibilanceMode = mutableIntStateOf(1) // 0: Off, 1: De-esser, 2: High-shelf, 3: Low-pass

    // Mini Player State
    var showMiniPlayer = mutableStateOf(false)
    var miniPlayerTitle = mutableStateOf("Now Playing")
    var miniPlayerIsPlaying = mutableStateOf(false)

    // Asset Download State
    var isDownloading = mutableStateOf(false)
    var downloadingVersion = mutableStateOf("v1")
    var downloadProgress = mutableFloatStateOf(0f)
    var downloadStatus = mutableStateOf("Checking assets...")
    var downloadedBytes = mutableLongStateOf(0L)
    var totalBytes = mutableLongStateOf(0L)
    var downloadError = mutableStateOf<String?>(null)

    // Dialog State
    var showQueueDialog = mutableStateOf(false)
    var queueDialogText = ""
    var showV2ConfirmDialog = mutableStateOf(false)
    var showV2DeleteDialog = mutableStateOf(false)
    var showV3ConfirmDialog = mutableStateOf(false)
    var showV3DeleteDialog = mutableStateOf(false)
    var pendingLangCode = ""

    // Data
    val voiceFiles = mutableStateMapOf<String, String>()

    /** Start a single model download, retaining the existing API for on-demand switching. */
    fun startDownload(context: Context, version: String, onComplete: (String) -> Unit) {
        startDownloads(context, listOf(version)) {
            onComplete(version)
        }
    }

    /**
     * Download a set of model versions in order. A failed version remains at the
     * head of the queue so retryDownload() resumes the same .part files and then
     * continues with the remaining versions.
     */
    fun startDownloads(context: Context, versions: List<String>, onComplete: () -> Unit) {
        if (isDownloading.value) return

        pendingDownloads.clear()
        pendingDownloads.addAll(
            versions.distinct().filterNot { AssetManager.isVersionReady(context, it) }
        )
        onDownloadsComplete = onComplete
        downloadError.value = null

        if (pendingDownloads.isEmpty()) {
            onDownloadsComplete = null
            onComplete()
            return
        }

        isDownloading.value = true
        downloadNext(context)
    }

    /** Retry the current queued version without discarding the rest of the queue. */
    fun retryDownload(context: Context) {
        if (isDownloading.value || pendingDownloads.isEmpty()) return
        downloadError.value = null
        isDownloading.value = true
        downloadNext(context)
    }

    private val pendingDownloads = mutableListOf<String>()
    private var onDownloadsComplete: (() -> Unit)? = null

    private fun downloadNext(context: Context) {
        val nextVersion = pendingDownloads.firstOrNull { !AssetManager.isVersionReady(context, it) }
        if (nextVersion == null) {
            pendingDownloads.clear()
            isDownloading.value = false
            val completion = onDownloadsComplete
            onDownloadsComplete = null
            completion?.invoke()
            return
        }

        downloadingVersion.value = nextVersion
        downloadProgress.floatValue = 0f
        downloadStatus.value = "Initializing..."
        downloadedBytes.longValue = 0L
        totalBytes.longValue = 0L

        viewModelScope.launch {
            try {
                val onProgress: (String, Float, Long, Long) -> Unit = { status, progress, downloaded, total ->
                    downloadStatus.value = status
                    downloadProgress.floatValue = progress
                    downloadedBytes.longValue = downloaded
                    totalBytes.longValue = total
                }

                when (nextVersion) {
                    "v1" -> AssetManager.downloadV1(context, onProgress)
                    "v2" -> AssetManager.downloadV2(context, onProgress)
                    "v3" -> AssetManager.downloadV3(context, onProgress)
                    else -> throw IllegalArgumentException("Unknown model version: $nextVersion")
                }

                pendingDownloads.remove(nextVersion)
                downloadNext(context)
            } catch (e: Exception) {
                isDownloading.value = false // Allow UI to show the error and retry.
                downloadError.value = e.message ?: "Unknown error"
            }
        }
    }

    companion object {
        const val DEFAULT_VOICE = "F3.json"
        const val DEFAULT_VOICE_2 = "M2.json"
        const val DEFAULT_LANG = "en"
        const val DEFAULT_SPEED = 1.1f
        const val DEFAULT_STEPS = 5
    }
}
