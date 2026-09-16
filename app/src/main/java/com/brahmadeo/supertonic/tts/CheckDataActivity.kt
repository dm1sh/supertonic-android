package com.brahmadeo.supertonic.tts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.brahmadeo.supertonic.tts.utils.AssetManager
import java.util.ArrayList

/**
 * Activity that handles the CHECK_TTS_DATA intent.
 * This is required by some apps (like Tasker) to verify that the TTS engine is functional
 * and to discover which languages are supported.
 */
class CheckDataActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val availableVoices = ArrayList<String>()
        val unavailableVoices = ArrayList<String>()

        val v1Voices = listOf("eng-USA")
        val v2Voices = listOf(
            "eng-USA", "kor-KOR", "spa-ESP", "por-PRT", "fra-FRA"
        )
        val v3Voices = listOf(
            "eng-USA", "kor-KOR", "spa-ESP", "por-PRT", "fra-FRA", "jpn-JPN", "ara-ARA", "bul-BGR", "ces-CZE", "dan-DNK",
            "deu-DEU", "ell-GRC", "est-EST", "fin-FIN", "hin-IND", "hrv-HRV",
            "hun-HUN", "ind-IDN", "ita-ITA", "lit-LTU", "lav-LVA", "nld-NLD",
            "pol-POL", "ron-ROU", "rus-RUS", "slk-SVK", "slv-SVN", "swe-SWE",
            "tur-TUR", "ukr-UKR", "vie-VNM"
        )

        fun addAvailable(voices: List<String>) {
            voices.forEach {
                if (!availableVoices.contains(it)) availableVoices.add(it)
                unavailableVoices.remove(it)
            }
        }
        fun addUnavailable(voices: List<String>) {
            voices.forEach {
                if (!availableVoices.contains(it) && !unavailableVoices.contains(it)) {
                    unavailableVoices.add(it)
                }
            }
        }

        if (AssetManager.isV1Ready(this)) addAvailable(v1Voices) else addUnavailable(v1Voices)
        if (AssetManager.isV2Ready(this)) addAvailable(v2Voices) else addUnavailable(v2Voices)
        if (AssetManager.isV3Ready(this)) addAvailable(v3Voices) else addUnavailable(v3Voices)

        val result = if (availableVoices.isNotEmpty()) {
            TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
        } else {
            TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL
        }

        val returnIntent = Intent()
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableVoices)
        returnIntent.putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, unavailableVoices)

        setResult(result, returnIntent)
        finish()
    }
}
