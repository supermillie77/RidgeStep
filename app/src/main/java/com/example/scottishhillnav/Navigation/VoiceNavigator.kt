package com.example.scottishhillnav.navigation

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Wraps Android TextToSpeech and fires route instructions as the walker progresses.
 *
 * Usage:
 *   1. Call setInstructions() when a route is built.
 *   2. Call onProgressUpdate(metres) on every GPS location update.
 *   3. Call shutdown() in Activity.onDestroy().
 */
class VoiceNavigator(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VoiceNavigator"
    }

    private val tts = TextToSpeech(context.applicationContext, this)
    private var ttsInitialized = false  // onInit called with SUCCESS
    private var ready = false           // TTS initialized AND preferred language set
    // Text queued to speak as soon as TTS finishes initialising (e.g. 🔊 tapped early)
    private var pendingSpeak: String? = null

    /** Set to true to silence all announcements without clearing instructions. */
    var muted: Boolean = false

    private var instructions: List<RouteInstruction> = emptyList()

    // Track which instruction distances have already been announced so each fires once only.
    private val announced = mutableSetOf<Double>()

    // ── TTS lifecycle ────────────────────────────────────────────────────────

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed (status=$status)")
            pendingSpeak = null  // can't speak — discard queued text
            return
        }
        ttsInitialized = true
        // Route audio through the media stream so it is not silenced by notification/ring mute.
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        // Prefer British English for Scottish hills — fall back to device locale
        var result = tts.setLanguage(Locale.UK)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = tts.setLanguage(Locale.ENGLISH)
        }
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            result = tts.setLanguage(Locale.getDefault())
        }
        ready = result == TextToSpeech.LANG_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        if (!ready) Log.w(TAG, "TTS language unavailable on this device")
        // Speak anything that was queued while TTS was still initialising
        pendingSpeak?.let { text ->
            pendingSpeak = null
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pronounce_queued")
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun setInstructions(list: List<RouteInstruction>) {
        instructions = list
        announced.clear()
        tts.stop()
    }

    fun clearInstructions() {
        instructions = emptyList()
        announced.clear()
        tts.stop()
    }

    /**
     * Called on each GPS update. Fires any instruction whose trigger distance
     * has just been passed (and hasn't been announced yet).
     */
    fun onProgressUpdate(progressMeters: Double) {
        if (muted || !ready) return
        for (inst in instructions) {
            if (inst.distanceFromStart !in announced && progressMeters >= inst.distanceFromStart) {
                announced.add(inst.distanceFromStart)
                tts.speak(inst.text, TextToSpeech.QUEUE_ADD, null, "nav_${announced.size}")
            }
        }
    }

    /** Speak an arbitrary string immediately (interrupts any current speech). */
    fun speakNow(text: String) {
        if (!ready || muted) return
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nav_immediate")
    }

    /**
     * Pronounce a name on user request — always speaks even when navigation is muted.
     * Calls tts.speak() unconditionally; Android TTS returns ERROR (no crash) if not
     * yet initialised, so we don't need to gate on ttsInitialized here.
     */
    fun pronounce(text: String) {
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pronounce")
        } catch (e: Exception) {
            Log.w(TAG, "TTS pronounce failed: ${e.message}")
        }
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
