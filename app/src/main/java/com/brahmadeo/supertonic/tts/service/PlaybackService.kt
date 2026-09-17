@file:Suppress("DEPRECATION")
package com.brahmadeo.supertonic.tts.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.brahmadeo.supertonic.tts.R
import com.brahmadeo.supertonic.tts.SupertonicTTS
import com.brahmadeo.supertonic.tts.utils.QueueItem
import com.brahmadeo.supertonic.tts.utils.QueueManager
import com.brahmadeo.supertonic.tts.utils.TextNormalizer
import com.brahmadeo.supertonic.tts.utils.WavUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.LinkedHashMap
import org.readium.r2.shared.publication.services.positions
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import androidx.core.content.edit

class PlaybackService : Service(), SupertonicTTS.ProgressListener, AudioManager.OnAudioFocusChangeListener {

    private val binder = object : IPlaybackService.Stub() {
        override fun synthesizeAndPlay(text: String, lang: String, stylePath: String, speed: Float, steps: Int, startIndex: Int) {
            this@PlaybackService.synthesizeAndPlay(text, lang, stylePath, speed, steps, startIndex)
        }

        override fun addToQueue(text: String, lang: String, stylePath: String, speed: Float, steps: Int, startIndex: Int) {
            this@PlaybackService.addToQueue(text, lang, stylePath, speed, steps, startIndex)
        }

        override fun play() {
            this@PlaybackService.play()
        }

        override fun pause() {
            this@PlaybackService.pause()
        }

        override fun skipToNextChunk() {
            this@PlaybackService.skipToNextChunk()
        }

        override fun skipToPreviousChunk() {
            this@PlaybackService.skipToPreviousChunk()
        }

        override fun stop() {
            this@PlaybackService.stopServicePlayback()
        }

        override fun isServiceActive(): Boolean {
            return this@PlaybackService.isServiceActive()
        }

        override fun setListener(listener: IPlaybackListener?) {
            this@PlaybackService.setListener(listener)
        }

        override fun removeListener(listener: IPlaybackListener?) {
            this@PlaybackService.removeListener(listener)
        }

        override fun exportAudio(text: String, lang: String, stylePath: String, speed: Float, steps: Int, outputPath: String) {
            this@PlaybackService.exportAudio(text, lang, stylePath, speed, steps, File(outputPath))
        }

        override fun getCurrentIndex(): Int {
            return currentSentenceIndex
        }

        override fun setSleepTimer(minutes: Int) {
            serviceScope.launch {
                this@PlaybackService.setSleepTimer(minutes)
            }
        }

        override fun getSleepTimerSeconds(): Int {
            return sleepTimerSecondsRemaining
        }
    }

    private val listeners = RemoteCallbackList<IPlaybackListener>()

    fun setListener(listener: IPlaybackListener?) {
        if (listener != null) {
            listeners.register(listener)
            try {
                listener.onStateChanged(isPlaying, audioTrack != null || isSynthesizing, isSynthesizing)
                listener.onProgress(currentSentenceIndex, -1)
                listener.onTransitioningChanged(isTransitioningChapter)
                listener.onSleepTimerUpdated(sleepTimerSecondsRemaining)
            } catch (_: RemoteException) {}
        }
    }

    fun removeListener(listener: IPlaybackListener?) {
        if (listener != null) {
            listeners.unregister(listener)
        }
    }

    private fun notifyListenerSleepTimer(seconds: Int) {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onSleepTimerUpdated(seconds)
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private lateinit var mediaSession: MediaSessionCompat
    private var audioTrack: AudioTrack? = null
    private var lastTrackRate: Int = -1
    @Volatile private var isPlaying = false
    @Volatile private var isSynthesizing = false
    private val textNormalizer = TextNormalizer()
    private val ebookParser by lazy { com.brahmadeo.supertonic.tts.utils.EbookParser(this) }
    private var resumeOnFocusGain = false
    @Volatile private var isTransitioningChapter = false
    @Volatile private var sleepTimerSecondsRemaining = 0
    
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val attributionContext: Context by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createAttributionContext("supertonic_playback")
        } else {
            this
        }
    }

    private data class PlaybackItem(val index: Int, val data: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as PlaybackItem

            if (index != other.index) return false
            if (!data.contentEquals(other.data)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = index
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    private data class ActivePlaybackRequest(
        val text: String,
        val lang: String,
        val stylePath: String,
        val speed: Float,
        val steps: Int,
        val sentences: List<String>
    )

    @Volatile private var currentSentenceIndex: Int = -1
    private var activePlaybackRequest: ActivePlaybackRequest? = null
    private val playbackCommandMutex = Mutex()
    private val processedAudioCache = LinkedHashMap<Int, ByteArray>(16, 0.75f, true)
    private val processedAudioCacheLock = Any()
    private var cachedBookPath: String? = null

    companion object {
        const val CHANNEL_ID = "supertonic_playback"
        const val NOTIFICATION_ID = 1
        const val TAG = "PlaybackService"
        const val VOLUME_BOOST_FACTOR = 2.5f
        const val AUDIO_WRITE_CHUNK_SIZE = 8192
        private const val MAX_PROCESSED_AUDIO_CACHE_CHUNKS = 16
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        com.brahmadeo.supertonic.tts.utils.LexiconManager.load(this)
        QueueManager.initialize(this)

        audioManager = attributionContext.getSystemService(AUDIO_SERVICE) as AudioManager
        val powerManager = attributionContext.getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Supertonic:PlaybackWakeLock").apply {
            setReferenceCounted(false)
        }
        
        mediaSession = MediaSessionCompat(attributionContext, "SupertonicMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { this@PlaybackService.play() }
                override fun onPause() { this@PlaybackService.pause() }
                override fun onSkipToNext() { this@PlaybackService.skipToNextChunk() }
                override fun onSkipToPrevious() { this@PlaybackService.skipToPreviousChunk() }
                override fun onStop() { this@PlaybackService.stopServicePlayback() }
            })
            isActive = true
        }

        val savedLang = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).getString("selected_lang", "en") ?: "en"
        val modelVersion = com.brahmadeo.supertonic.tts.utils.AssetManager.getAvailableModelVersionForLanguage(this, savedLang)
        val modelPath = File(filesDir, "$modelVersion/onnx").absolutePath
        val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
        SupertonicTTS.initialize(modelPath, libPath)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_PLAYBACK") {
            stopServicePlayback()
        } else if (intent?.action == "RESET_ENGINE") {
            // Stop playback properly first, which cancels native synthesis
            stopServicePlayback()
            
            // Perform release and initialization off the main thread to avoid ANRs
            serviceScope.launch(Dispatchers.IO) {
                SupertonicTTS.release()
                val savedLang = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).getString("selected_lang", "en") ?: "en"
                val modelVersion = com.brahmadeo.supertonic.tts.utils.AssetManager.getAvailableModelVersionForLanguage(this@PlaybackService, savedLang)
                val modelPath = File(filesDir, "$modelVersion/onnx").absolutePath
                val libPath = applicationInfo.nativeLibraryDir + "/libonnxruntime.so"
                SupertonicTTS.initialize(modelPath, libPath)
            }
        }
        return START_NOT_STICKY
    }

    fun isServiceActive(): Boolean {
        return isPlaying || isSynthesizing
    }

    fun addToQueue(text: String, lang: String, stylePath: String, speed: Float, steps: Int, startIndex: Int) {
        QueueManager.add(QueueItem(
            text = text,
            lang = lang,
            stylePath = stylePath,
            speed = speed,
            steps = steps,
            startIndex = startIndex
        ))
    }

    private var synthesisJob: Job? = null
    private var loadChapterJob: Job? = null

    fun synthesizeAndPlay(text: String, lang: String, stylePath: String, speed: Float, steps: Int, startIndex: Int = 0) {
        serviceScope.launch {
            playbackCommandMutex.withLock {
                startNewPlayback(text, lang, stylePath, speed, steps, startIndex)
            }
        }
    }

    private suspend fun startNewPlayback(
        text: String,
        lang: String,
        stylePath: String,
        speed: Float,
        steps: Int,
        startIndex: Int
    ) {
        cancelActiveSynthesis()
        stopPlayback(removeNotification = false, clearCachedAudio = false)
        clearProcessedAudioCache()

        val sentences = textNormalizer.splitIntoSentences(text, lang)
        if (sentences.isEmpty()) {
            activePlaybackRequest = null
            isSynthesizing = false
            stopPlayback()
            return
        }

        val request = ActivePlaybackRequest(text, lang, stylePath, speed, steps, sentences)
        activePlaybackRequest = request
        startPlayback(request, startIndex)
    }

    private suspend fun cancelActiveSynthesis() {
        if (synthesisJob?.isActive == true) {
            SupertonicTTS.setCancelled(true)
            synthesisJob?.cancelAndJoin()
        }
        synthesisJob = null
    }

    private fun getProcessedAudio(index: Int): ByteArray? {
        synchronized(processedAudioCacheLock) {
            return processedAudioCache[index]
        }
    }

    private fun cacheProcessedAudio(index: Int, data: ByteArray) {
        synchronized(processedAudioCacheLock) {
            processedAudioCache[index] = data
            while (processedAudioCache.size > MAX_PROCESSED_AUDIO_CACHE_CHUNKS) {
                val oldestIndex = processedAudioCache.entries.iterator().next().key
                processedAudioCache.remove(oldestIndex)
            }
        }
    }

    private fun clearProcessedAudioCache() {
        synchronized(processedAudioCacheLock) {
            processedAudioCache.clear()
        }
    }

    private suspend fun startPlayback(request: ActivePlaybackRequest, startIndex: Int) {
        val sentences = request.sentences
        val totalSentences = sentences.size
        val validStartIndex = if (startIndex in 0 until totalSentences) startIndex else 0

        isSynthesizing = true
        isPlaying = true
        SupertonicTTS.setCancelled(false)

        updatePlaybackState(PlaybackStateCompat.STATE_BUFFERING)
        startForegroundService(getString(R.string.notif_synthesizing), false)
        notifyListenerState(false)

        currentSentenceIndex = validStartIndex
        notifyListenerProgress(validStartIndex, totalSentences)

        wakeLock?.acquire(10 * 60 * 1000L)

        if (!requestAudioFocus()) {
            Log.w(TAG, "Audio Focus denied")
        }

        synthesisJob = serviceScope.launch(Dispatchers.IO) {
            val channel = kotlinx.coroutines.channels.Channel<PlaybackItem>(10)
            val preBufferComplete = CompletableDeferred<Unit>()

            // Generate one chunk at a time, while the consumer plays earlier chunks.
            launch {
                var producedCount = 0
                for (index in validStartIndex until totalSentences) {
                    if (SupertonicTTS.isCancelled() || !isActive) break

                    while (!isPlaying && isSynthesizing && isActive) {
                        delay(100.milliseconds)
                    }
                    if (SupertonicTTS.isCancelled() || !isActive || !isSynthesizing) break

                    val cachedAudio = getProcessedAudio(index)
                    val audioData = cachedAudio ?: run {
                        val sentence = sentences[index]
                        val sentenceLang = request.lang
                        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
                        val isAdvancedEnabled = prefs.getBoolean("is_advanced_normalization", false)
                        val sibilanceMode = prefs.getInt("sibilance_reduction_mode", 1)
                        val normalizedText = textNormalizer.normalize(sentence, sentenceLang, isAdvancedEnabled)

                        SupertonicTTS.generateAudio(
                            normalizedText,
                            sentenceLang,
                            request.stylePath,
                            request.speed,
                            0.0f,
                            request.steps,
                            VOLUME_BOOST_FACTOR,
                            null,
                            sibilanceMode
                        )
                    }

                    if (audioData != null && audioData.isNotEmpty() &&
                        !SupertonicTTS.isCancelled() && isActive
                    ) {
                        if (cachedAudio == null) {
                            // Keep a bounded in-memory history so navigation can reuse recent chunks.
                            cacheProcessedAudio(index, audioData)
                        }
                        channel.send(PlaybackItem(index, audioData))
                        producedCount++

                        if (producedCount >= 3 && !preBufferComplete.isCompleted) {
                            preBufferComplete.complete(Unit)
                            Log.d(TAG, "Pre-buffer complete: 3 chunks ready")
                        }
                    }
                }

                if (!preBufferComplete.isCompleted) {
                    preBufferComplete.complete(Unit)
                }
                channel.close()
            }

            preBufferComplete.await()

            withContext(Dispatchers.Main) {
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                notifyListenerState(true)
            }

            for (item in channel) {
                if (SupertonicTTS.isCancelled() || !isActive || !isSynthesizing) break

                withContext(Dispatchers.Main) {
                    currentSentenceIndex = item.index
                    notifyListenerProgress(item.index, totalSentences)
                }

                playAudioDataBlocking(item.data)
            }

            withContext(Dispatchers.Main) {
                if (isSynthesizing && isActive) {
                    val wasCancelled = SupertonicTTS.isCancelled()
                    isSynthesizing = false

                    if (!wasCancelled) {
                        notifyListenerProgress(totalSentences, totalSentences)
                    }

                    notifyListenerState(true)

                    if (!wasCancelled) {
                        val nextItem = QueueManager.next()
                        if (nextItem != null) {
                            SupertonicTTS.reset()
                            synthesizeAndPlay(
                                nextItem.text,
                                nextItem.lang,
                                nextItem.stylePath,
                                nextItem.speed,
                                nextItem.steps,
                                nextItem.startIndex
                            )
                        } else {
                            checkAutoPlayNextOrStop()
                        }
                    }
                }
            }
        }
    }

    fun skipToNextChunk() {
        navigateToChunk(1)
    }

    fun skipToPreviousChunk() {
        navigateToChunk(-1)
    }

    private fun navigateToChunk(delta: Int) {
        serviceScope.launch {
            playbackCommandMutex.withLock {
                val request = activePlaybackRequest ?: return@withLock
                if (isTransitioningChapter || (!isPlaying && !isSynthesizing)) return@withLock

                val currentIndex = currentSentenceIndex
                if (currentIndex !in request.sentences.indices) return@withLock

                val targetIndex = currentIndex + delta
                if (targetIndex < 0) return@withLock

                if (targetIndex >= request.sentences.size) {
                    // A next press on the last chunk advances the queue/chapter just like
                    // normal completion, without replaying the current chunk.
                    cancelActiveSynthesis()
                    isSynthesizing = false
                    stopPlayback(removeNotification = false, clearCachedAudio = true)
                    activePlaybackRequest = null

                    val nextItem = QueueManager.next()
                    if (nextItem != null) {
                        startNewPlayback(
                            nextItem.text,
                            nextItem.lang,
                            nextItem.stylePath,
                            nextItem.speed,
                            nextItem.steps,
                            nextItem.startIndex
                        )
                    } else {
                        checkAutoPlayNextOrStop()
                    }
                    return@withLock
                }

                // Stop the current AudioTrack and producer. The request and bounded cache
                // survive, allowing an already generated target to play immediately. If it
                // was not generated yet, the producer starts at that index and synthesizes it.
                cancelActiveSynthesis()
                stopPlayback(removeNotification = false, clearCachedAudio = false)
                startPlayback(request, targetIndex)
            }
        }
    }

    private suspend fun playAudioDataBlocking(data: ByteArray) {
        if (!currentCoroutineContext().isActive) return
        val rate = SupertonicTTS.getAudioSampleRate()
        
        // Reuse or create AudioTrack
        var track: AudioTrack? = null
        synchronized(this) {
            track = audioTrack
            try {
                if (track == null || lastTrackRate != rate || track.state == AudioTrack.STATE_UNINITIALIZED) {
                    try { track?.release() } catch (_: Exception) {}
                    
                    val minBufferSize = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4
                    val builder = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                        .setBufferSizeInBytes(minBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        builder.setContext(attributionContext)
                    }

                    track = builder.build()
                    
                    audioTrack = track
                    lastTrackRate = rate
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AudioTrack", e)
                return
            }
        }
        
        val safeTrack = track ?: return
        if (safeTrack.state != AudioTrack.STATE_INITIALIZED) return

        withContext(Dispatchers.Main) {
            if (isPlaying && safeTrack.state == AudioTrack.STATE_INITIALIZED && safeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                try {
                    safeTrack.play()
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "Failed to start AudioTrack", e)
                }
                notifyListenerState(true)
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            }
        }
        
        // Use a small wait logic to keep progress UI somewhat in sync
        // but start writing the next chunk before this one is fully finished.
        val startHead = try { safeTrack.playbackHeadPosition } catch (_: Exception) { 0 }
        val chunkSamples = data.size / 2
        
        // Write data in loop to handle pause/resume gracefully
        var offset = 0
        while (offset < data.size && currentCoroutineContext().isActive && isSynthesizing) {
            if (!isPlaying) {
                if (safeTrack.state == AudioTrack.STATE_INITIALIZED && safeTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try { safeTrack.pause() } catch (_: Exception) {}
                }
                delay(100.milliseconds)
                continue
            } else {
                if (safeTrack.state == AudioTrack.STATE_INITIALIZED && safeTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        safeTrack.play()
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "Failed to start AudioTrack in loop", e)
                        break
                    }
                }
            }
            
            val toWrite = (data.size - offset).coerceAtMost(AUDIO_WRITE_CHUNK_SIZE)
            val written = try {
                safeTrack.write(data, offset, toWrite, AudioTrack.WRITE_BLOCKING)
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack write exception", e)
                -1
            }
            
            if (written > 0) {
                offset += written
            } else {
                if (written < 0) {
                    Log.e(TAG, "AudioTrack write error: $written")
                } else {
                    Log.w(TAG, "AudioTrack write returned 0, stopping chunk playback")
                }
                break
            }
        }

        // To keep the progress indicator moving roughly with the audio, 
        // we wait until the head has moved significantly.
        // We leave about 100ms of "overlap" to ensure zero gap.
        val samplesToWait = chunkSamples - (rate / 10) // Wait until 100ms before end
        while (currentCoroutineContext().isActive && isSynthesizing && isPlaying) {
            val currentHead = try { safeTrack.playbackHeadPosition } catch (_: Exception) { startHead + samplesToWait }
            val headMove = currentHead - startHead
            if (headMove >= samplesToWait) break
            delay(50.milliseconds)
        }
    }

    override fun onProgress(sessionId: Long, current: Int, total: Int) {}
    override fun onAudioChunk(sessionId: Long, data: ByteArray) {}

    fun play() {
        resumeOnFocusGain = false
        if (!isPlaying) {
            if (requestAudioFocus()) {
                isPlaying = true
                try {
                    if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                        audioTrack?.play()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing audio track", e)
                }
                notifyListenerState(true)
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                startForegroundService(getString(R.string.notif_playing), true)
            }
        }
    }

    fun pause() {
        resumeOnFocusGain = false
        if (isPlaying) {
            isPlaying = false
            try {
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack?.pause()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error pausing audio track", e)
            }
            notifyListenerState(false)
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            updateNotification(getString(R.string.notif_paused))
        }
    }

    fun stopPlayback(
        removeNotification: Boolean = true,
        clearCachedAudio: Boolean = removeNotification
    ) {
        synchronized(this) {
            isPlaying = false
            try {
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack?.stop()
                }
                audioTrack?.release()
            } catch (_: Exception) { }
            audioTrack = null
        }
        resumeOnFocusGain = false
        notifyListenerState(false)
        abandonAudioFocus()
        
        if (isTransitioningChapter) {
            isTransitioningChapter = false
            notifyListenerTransitioning(false)
        }
        loadChapterJob?.cancel()

        if (clearCachedAudio) {
            activePlaybackRequest = null
            clearProcessedAudioCache()
        }

        if (removeNotification) {
            currentSentenceIndex = -1
            if (wakeLock?.isHeld == true) wakeLock?.release()
            setSleepTimer(0)
            getSharedPreferences("SupertonicPrefs", MODE_PRIVATE).edit {
                putBoolean("is_playing", false)
            }
                
            notifyListenerPlaybackStopped()
            updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    fun stopServicePlayback() {
        isPlaying = false
        try {
            if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack?.pause()
            }
        } catch (_: Exception) {}

        serviceScope.launch {
            playbackCommandMutex.withLock {
                SupertonicTTS.setCancelled(true)
                isSynthesizing = false
                cancelActiveSynthesis()
                stopPlayback()
            }
        }
    }

    private fun notifyListenerState(playing: Boolean) {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onStateChanged(playing, audioTrack != null || isSynthesizing, isSynthesizing)
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun notifyListenerProgress(current: Int, total: Int) {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onProgress(current, total)
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun notifyListenerChapterChanged(newText: String, chapterHref: String?, pageIndex: Int) {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onChapterChanged(newText, chapterHref, pageIndex)
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun notifyListenerTransitioning(isTransitioning: Boolean) {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onTransitioningChanged(isTransitioning)
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun notifyListenerPlaybackStopped() {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onPlaybackStopped()
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun notifyListenerExportComplete(success: Boolean, path: String) {
        val n = listeners.beginBroadcast()
        for (i in 0 until n) {
            try {
                listeners.getBroadcastItem(i).onExportComplete(success, path)
            } catch (_: RemoteException) {}
        }
        listeners.finishBroadcast()
    }

    private fun requestAudioFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(this)
                .build()
            return audioManager.requestAudioFocus(focusRequest!!) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            return audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }
    
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> stopServicePlayback()
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying) {
                    resumeOnFocusGain = true
                    isPlaying = false
                    try {
                        if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                            audioTrack?.pause()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error pausing on focus loss", e)
                    }
                    notifyListenerState(false)
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                try {
                    audioTrack?.setVolume(0.2f)
                } catch (_: Exception) {}
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                try {
                    audioTrack?.setVolume(1.0f)
                } catch (_: Exception) {}
                if (resumeOnFocusGain) play()
            }
        }
    }

    fun exportAudio(text: String, lang: String, stylePath: String, speed: Float, steps: Int, outputFile: File) {
        serviceScope.launch {
            playbackCommandMutex.withLock {
                cancelActiveSynthesis()
                stopPlayback(removeNotification = false, clearCachedAudio = true)
                activePlaybackRequest = null
                SupertonicTTS.setCancelled(false)
                isSynthesizing = true
                notifyListenerState(false)
                startForegroundService(getString(R.string.notif_exporting), false)

                synthesisJob = serviceScope.launch(Dispatchers.IO) {
                    var exportSuccess = false
                    try {
                        val sentences = textNormalizer.splitIntoSentences(text, lang)
                        if (sentences.isEmpty()) {
                            Log.w(TAG, "Export: No sentences found")
                            return@launch
                        }

                        val outputStream = ByteArrayOutputStream()
                        for ((index, sentence) in sentences.withIndex()) {
                            if (!isActive || SupertonicTTS.isCancelled()) break

                            withContext(Dispatchers.Main) {
                                notifyListenerProgress(index + 1, sentences.size)
                            }

                            val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
                            val isAdvancedEnabled = prefs.getBoolean("is_advanced_normalization", false)
                            val sibilanceMode = prefs.getInt("sibilance_reduction_mode", 1)
                            val normalizedText = textNormalizer.normalize(sentence, lang, isAdvancedEnabled)

                            val audioData = SupertonicTTS.generateAudio(
                                normalizedText,
                                lang,
                                stylePath,
                                speed,
                                0.0f,
                                steps,
                                VOLUME_BOOST_FACTOR,
                                null,
                                sibilanceMode
                            )
                            if (audioData != null && audioData.isNotEmpty()) {
                                outputStream.write(audioData)
                            } else if (SupertonicTTS.isCancelled()) {
                                break
                            }
                        }

                        if (isActive && !SupertonicTTS.isCancelled() && outputStream.size() > 0) {
                            WavUtils.saveWav(
                                outputFile,
                                outputStream.toByteArray(),
                                SupertonicTTS.getAudioSampleRate()
                            )
                            exportSuccess = true
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Export failed", e)
                    } finally {
                        withContext(Dispatchers.Main) {
                            isSynthesizing = false
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            notifyListenerExportComplete(exportSuccess, outputFile.absolutePath)
                            notifyListenerState(false)
                        }
                    }
                }
            }
        }
    }

    private fun updatePlaybackState(state: Int) {
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun startForegroundService(status: String, showControls: Boolean) {
        // Promote to a started service so it doesn't die when all activities unbind
        startService(Intent(this, PlaybackService::class.java))

        val notification = buildNotification(status, showControls)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0)
    }

    private fun updateNotification(status: String) {
        val notificationManager = attributionContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status, true))
    }

    private fun buildNotification(status: String, showControls: Boolean): android.app.Notification {
        val activityIntent = Intent(this, com.brahmadeo.supertonic.tts.PlaybackActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("is_resume", true)
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(status)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(if (showControls) 1 else 0)
            )

        val coverIcon = getBookCoverIcon()
        if (coverIcon != null) {
            builder.setLargeIcon(coverIcon)
        }

        if (showControls) {
            builder.addAction(
                android.R.drawable.ic_media_previous,
                getString(R.string.previous_chunk_label),
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                )
            )
            if (isPlaying) {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    getString(R.string.notif_paused),
                    androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_PAUSE
                    )
                )
            } else {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    getString(R.string.yes),
                    androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                        this,
                        PlaybackStateCompat.ACTION_PLAY
                    )
                )
            }
            builder.addAction(
                android.R.drawable.ic_media_next,
                getString(R.string.next_chunk_label),
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                )
            )
        } else {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.cancel),
                androidx.media.session.MediaButtonReceiver.buildMediaButtonPendingIntent(
                    this,
                    PlaybackStateCompat.ACTION_STOP
                )
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW)
            val manager = attributionContext.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private var sleepTimerJob: Job? = null

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes == 0) {
            sleepTimerSecondsRemaining = 0
            notifyListenerSleepTimer(0)
        } else {
            sleepTimerSecondsRemaining = minutes * 60
            notifyListenerSleepTimer(sleepTimerSecondsRemaining)
            startSleepTimerCountdown()
        }
    }

    private fun startSleepTimerCountdown() {
        sleepTimerJob = serviceScope.launch {
            while (sleepTimerSecondsRemaining > 0) {
                delay(1000L.milliseconds)
                if (isPlaying) {
                    sleepTimerSecondsRemaining -= 1
                    notifyListenerSleepTimer(sleepTimerSecondsRemaining)
                }
            }
            
            val intent = Intent("com.brahmadeo.supertonic.tts.SLEEP_TIMER_EXPIRED").apply {
                setPackage(packageName)
            }
            sendBroadcast(intent)
            stopServicePlayback()
        }
    }

    private fun checkAutoPlayNextOrStop() {
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        val autoPlayNext = prefs.getBoolean("pref_auto_play_next", false)
        val bookPath = prefs.getString("last_book_path", null)

        if (autoPlayNext && !bookPath.isNullOrEmpty()) {
            loadAndPlayNextChapterOrPage(bookPath)
        } else {
            stopPlayback()
        }
    }

    private fun loadAndPlayNextChapterOrPage(bookPath: String) {
        val ebookFile = File(bookPath)
        if (!ebookFile.exists()) {
            stopPlayback()
            return
        }

        isTransitioningChapter = true
        notifyListenerTransitioning(true)
        wakeLock?.acquire(5 * 60 * 1000L)

        loadChapterJob?.cancel()
        loadChapterJob = serviceScope.launch {
            val pubResult = ebookParser.openPublication(ebookFile)
            val publication = pubResult.getOrNull()
            if (publication == null) {
                isTransitioningChapter = false
                notifyListenerTransitioning(false)
                stopPlayback()
                return@launch
            }

            val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
            val currentLang = prefs.getString("last_lang", "en") ?: "en"
            val currentVoicePath = prefs.getString("last_voice_path", "") ?: ""
            val currentSpeed = prefs.getFloat("last_speed", 1.1f)
            val currentSteps = prefs.getInt("last_steps", 5)
            val currentChapterHref = prefs.getString("last_chapter_href", null)
            val currentPageIndex = prefs.getInt("last_page_index", -1)

            val conformsToPdf = publication.metadata.conformsTo.contains(org.readium.r2.shared.publication.Publication.Profile.PDF)
            val isPdfMediaType = publication.readingOrder.firstOrNull()?.mediaType?.matches(org.readium.r2.shared.util.mediatype.MediaType.PDF) == true
            val isPdf = conformsToPdf || isPdfMediaType

            if (isPdf) {
                val nextPageIndex = currentPageIndex + 1
                val totalPages = publication.positions().size
                if (nextPageIndex in 0 until totalPages) {
                    val extractResult = ebookParser.extractPages(ebookFile, publication, listOf(nextPageIndex))
                    extractResult.onSuccess { nextText ->
                        val preparedText = prepareTextForTts(nextText, currentLang)
                        
                        prefs.edit {
                            putString("last_text", preparedText)
                                .putInt("last_page_index", nextPageIndex)
                                .remove("last_chapter_href")
                                .putInt("last_index", 0)
                        }
                        
                        com.brahmadeo.supertonic.tts.utils.EbookManager.setLastReadChapter(this@PlaybackService, bookPath, "page_$nextPageIndex")
                        

                        notifyListenerChapterChanged(preparedText, null, nextPageIndex)

                        SupertonicTTS.reset()
                        synthesizeAndPlay(preparedText, currentLang, currentVoicePath, currentSpeed, currentSteps, 0)

                        isTransitioningChapter = false
                        notifyListenerTransitioning(false)
                    }.onFailure {
                        isTransitioningChapter = false
                        notifyListenerTransitioning(false)
                        stopPlayback()
                    }
                } else {
                    isTransitioningChapter = false
                    notifyListenerTransitioning(false)
                    stopPlayback()
                }
            } else {
                val toc = publication.tableOfContents
                val links = toc.ifEmpty { publication.readingOrder }
                
                fun List<org.readium.r2.shared.publication.Link>.flatten(): List<org.readium.r2.shared.publication.Link> {
                    val result = mutableListOf<org.readium.r2.shared.publication.Link>()
                    fun traverse(links: List<org.readium.r2.shared.publication.Link>) {
                        for (link in links) {
                            result.add(link)
                            traverse(link.children)
                        }
                    }
                    traverse(this)
                    return result
                }
                
                val flatLinks = links.flatten()
                val currentIndex = flatLinks.indexOfFirst { it.href.toString() == currentChapterHref }

                if (currentIndex != -1 && currentIndex + 1 < flatLinks.size) {
                    val nextLink = flatLinks[currentIndex + 1]
                    val nextHref = nextLink.href.toString()
                    
                    val extractResult = ebookParser.extractText(publication, nextLink)
                    extractResult.onSuccess { nextText ->
                        val preparedText = prepareTextForTts(nextText, currentLang)
                        
                        prefs.edit {
                            putString("last_text", preparedText)
                                .putString("last_chapter_href", nextHref)
                                .remove("last_page_index")
                                .putInt("last_index", 0)
                        }
                        
                        com.brahmadeo.supertonic.tts.utils.EbookManager.setLastReadChapter(this@PlaybackService, bookPath, nextHref)
                        

                        notifyListenerChapterChanged(preparedText, nextHref, -1)

                        SupertonicTTS.reset()
                        synthesizeAndPlay(preparedText, currentLang, currentVoicePath, currentSpeed, currentSteps, 0)
                        
                        isTransitioningChapter = false
                        notifyListenerTransitioning(false)
                    }.onFailure {
                        isTransitioningChapter = false
                        notifyListenerTransitioning(false)
                        stopPlayback()
                    }
                } else {
                    isTransitioningChapter = false
                    notifyListenerTransitioning(false)
                    stopPlayback()
                }
            }
        }
    }

    private fun prepareTextForTts(text: String?, lang: String): String {
        if (text.isNullOrEmpty()) return ""
        val trimmed = text.trim()
        if (lang.lowercase().startsWith("ko")) {
            return trimmed
        }
        return if (trimmed.endsWith(" .")) trimmed else "$trimmed ."
    }

    private fun getBookCoverIcon(): android.graphics.drawable.Icon? {
        val prefs = getSharedPreferences("SupertonicPrefs", MODE_PRIVATE)
        val bookPath = prefs.getString("last_book_path", null) ?: return null
        val lowerPath = bookPath.lowercase(java.util.Locale.US)
        
        val dir = File(cacheDir, "tts_output")
        if (!dir.exists()) dir.mkdirs()
        
        val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNightMode = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val suffix = if (isNightMode) "dark" else "light"
        
        val epubFallbackFile = File(dir, "epub_fallback_$suffix.png")
        val pdfFallbackFile = File(dir, "pdf_fallback_$suffix.png")
        val bookCoverFile = File(dir, "current_book_cover.png")
        
        val targetFile: File
        
        if (lowerPath.endsWith(".epub")) {
            if (bookPath == cachedBookPath && bookCoverFile.exists()) {
                val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", bookCoverFile)
                return android.graphics.drawable.Icon.createWithContentUri(uri)
            }
            
            val rawCover = extractEpubCover(bookPath)
            if (rawCover != null) {
                val bitmap = scaleAndPadToSquare(rawCover)
                if (bitmap != rawCover) {
                    rawCover.recycle()
                }
                
                try {
                    java.io.FileOutputStream(bookCoverFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bitmap.recycle()
                    cachedBookPath = bookPath
                    targetFile = bookCoverFile
                } catch (e: Exception) {
                    Log.e("PlaybackService", "Error saving cover bitmap to file", e)
                    bitmap.recycle()
                    return null
                }
            } else {
                if (epubFallbackFile.exists()) {
                    targetFile = epubFallbackFile
                } else {
                    val bitmap = createFallbackCover(isPdf = false)
                    try {
                        java.io.FileOutputStream(epubFallbackFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                        bitmap.recycle()
                        targetFile = epubFallbackFile
                    } catch (e: Exception) {
                        Log.e("PlaybackService", "Error saving epub fallback to file", e)
                        bitmap.recycle()
                        return null
                    }
                }
            }
        } else if (lowerPath.endsWith(".pdf")) {
            if (pdfFallbackFile.exists()) {
                targetFile = pdfFallbackFile
            } else {
                val bitmap = createFallbackCover(isPdf = true)
                try {
                    java.io.FileOutputStream(pdfFallbackFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    bitmap.recycle()
                    targetFile = pdfFallbackFile
                } catch (e: Exception) {
                    Log.e("PlaybackService", "Error saving pdf fallback to file", e)
                    bitmap.recycle()
                    return null
                }
            }
        } else {
            return null
        }
        
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            targetFile
        )
        return android.graphics.drawable.Icon.createWithContentUri(uri)
    }

    private fun extractEpubCover(filePath: String): Bitmap? {
        val file = File(filePath)
        if (!file.exists()) return null
        
        try {
            java.util.zip.ZipFile(file).use { zip ->
                // 1. Read META-INF/container.xml to find the OPF path
                val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
                val containerXml = zip.getInputStream(containerEntry).bufferedReader().use { it.readText() }
                
                val opfRegex = Regex("""<rootfile[^>]+full-path=["']([^"']+)["']""")
                val matchResult = opfRegex.find(containerXml) ?: return null
                val opfPath = matchResult.groupValues[1]
                
                val opfEntry = zip.getEntry(opfPath) ?: return null
                val opfXml = zip.getInputStream(opfEntry).bufferedReader().use { it.readText() }
                
                // 2. Locate the cover image href from the OPF file
                var coverHref: String? = null
                
                // Method A: Check manifest item with properties="cover-image" (EPUB 3)
                val propertiesRegex = Regex("""<item[^>]+href=["']([^"']+)["'][^>]+properties=["']cover-image["']""")
                val matchProp = propertiesRegex.find(opfXml)
                if (matchProp != null) {
                    coverHref = matchProp.groupValues[1]
                }
                
                // Method B: Check metadata tag name="cover" content="id"
                if (coverHref == null) {
                    val coverMetaRegex = Regex("""<meta[^>]+name=["']cover["'][^>]+content=["']([^"']+)["']""")
                    val matchMeta = coverMetaRegex.find(opfXml)
                    if (matchMeta != null) {
                        val coverId = matchMeta.groupValues[1]
                        val itemRegex = Regex("""<item[^>]+id=["']${Regex.escape(coverId)}["'][^>]+href=["']([^"']+)["']""")
                        val matchItem = itemRegex.find(opfXml)
                        if (matchItem != null) {
                            coverHref = matchItem.groupValues[1]
                        }
                    }
                }
                
                val coverZipPath = if (coverHref != null) {
                    val opfDir = File(opfPath).parent?.replace("\\", "/")
                    val hrefResolved = coverHref.replace("\\", "/")
                    if (!opfDir.isNullOrEmpty()) {
                        normalizePath("$opfDir/$hrefResolved")
                    } else {
                        hrefResolved
                    }
                } else null
                
                if (coverZipPath != null) {
                    val coverEntry = zip.getEntry(coverZipPath) ?: zip.getEntry(coverHref ?: "")
                    if (coverEntry != null) {
                        zip.getInputStream(coverEntry).use { input ->
                            return BitmapFactory.decodeStream(input)
                        }
                    }
                }
                
                // Method C: Fallback to scanning ZIP files for anything with "cover" and an image extension
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.lowercase()
                    if (name.contains("cover") && (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".webp"))) {
                        zip.getInputStream(entry).use { input ->
                            return BitmapFactory.decodeStream(input)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "Error extracting EPUB cover", e)
        }
        return null
    }

    private fun normalizePath(path: String): String {
        val parts = path.split("/")
        val result = mutableListOf<String>()
        for (part in parts) {
            if (part == "..") {
                if (result.isNotEmpty()) result.removeAt(result.size - 1)
            } else if (part != "." && part.isNotEmpty()) {
                result.add(part)
            }
        }
        return result.joinToString("/")
    }

    private fun scaleAndPadToSquare(bitmap: Bitmap): Bitmap {
        val targetSize = 1024
        val width = bitmap.width
        val height = bitmap.height
        
        val maxDim = maxOf(width, height)
        val scale = targetSize.toFloat() / maxDim
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        val scaledBitmap = bitmap.scale(newWidth, newHeight)
        val outputBitmap = createBitmap(targetSize, targetSize)
        val canvas = android.graphics.Canvas(outputBitmap)
        
        val left = (targetSize - newWidth) / 2f
        val top = (targetSize - newHeight) / 2f
        
        canvas.drawBitmap(scaledBitmap, left, top, null)
        
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        
        return outputBitmap
    }

    private fun createFallbackCover(isPdf: Boolean): Bitmap {
        val size = 768
        val bitmap = createBitmap(size, size)
        val canvas = android.graphics.Canvas(bitmap)
        
        // Detect Day/Night mode
        val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val isNightMode = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
        
        // Day/Night aware colors (vibrant for light mode, deep/muted for dark mode)
        val startColor = if (isPdf) {
            if (isNightMode) 0xFF880E4F.toInt() else 0xFFE57373.toInt()
        } else {
            if (isNightMode) 0xFF1A237E.toInt() else 0xFF7986CB.toInt()
        }
        
        val endColor = if (isPdf) {
            if (isNightMode) 0xFF2D0010.toInt() else 0xFFC62828.toInt()
        } else {
            if (isNightMode) 0xFF0A0E29.toInt() else 0xFF3F51B5.toInt()
        }
        
        val shader = android.graphics.LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            startColor, endColor,
            android.graphics.Shader.TileMode.CLAMP
        )
        
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = shader
        
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        
        paint.shader = null
        paint.color = 0x33FFFFFF
        
        val bookWidth = size * 0.4f
        val bookHeight = size * 0.55f
        val left = (size - bookWidth) / 2f
        val top = (size - bookHeight) / 2f
        val right = left + bookWidth
        val bottom = top + bookHeight
        
        canvas.drawRoundRect(left, top, right, bottom, 24f, 24f, paint)
        
        paint.color = 0x22000000
        canvas.drawRect(left, top, left + (bookWidth * 0.15f), bottom, paint)
        
        paint.color = 0xFFFFFFFF.toInt()
        paint.textSize = 64f
        paint.textAlign = android.graphics.Paint.Align.CENTER
        paint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        
        val formatText = if (isPdf) "PDF" else "EPUB"
        val fontMetrics = paint.fontMetrics
        val textY = (size / 2f) - (fontMetrics.descent + fontMetrics.ascent) / 2f
        
        canvas.drawText(formatText, size / 2f, textY, paint)
        
        return bitmap
    }

    override fun onDestroy() {
        super.onDestroy()
        SupertonicTTS.setCancelled(true)
        sleepTimerJob?.cancel()
        loadChapterJob?.cancel()
        if (wakeLock?.isHeld == true) {
            try { wakeLock?.release() } catch (_: Exception) {}
        }
        mediaSession.release()
        try {
            audioTrack?.release()
        } catch (_: Exception) {}
        activePlaybackRequest = null
        clearProcessedAudioCache()
        serviceScope.cancel()
        abandonAudioFocus()
    }
}