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
     *
     * The name is passed through [gaelicPhonetic] first so that Android TTS produces
     * a reasonable approximation of Scottish Gaelic pronunciation rather than reading
     * short particles as individual letters (e.g. "na" → "N A").
     */
    fun pronounce(text: String) {
        try {
            tts.speak(gaelicPhonetic(text), TextToSpeech.QUEUE_FLUSH, null, "pronounce")
        } catch (e: Exception) {
            Log.w(TAG, "TTS pronounce failed: ${e.message}")
        }
    }

    /**
     * Converts Scottish Gaelic words and particles to phonetic approximations that
     * Android TTS (British English) will pronounce intelligibly.
     *
     * Short Gaelic particles (na, nan, am, an) look like abbreviations to TTS and are
     * read as individual letters. Lenited initials (Bh-, Mh-, Gh-, Dh-) are assigned
     * their correct V / silent-or-Y sounds.  Accented vowels (à, è, ì, ò, ù) are
     * stripped so TTS doesn't stumble over them.
     */
    private fun gaelicPhonetic(text: String): String {
        var s = text
        // ── Particles and articles (whole-word matches — must come before root words) ──
        s = s.replace(Regex("\\bnan\\b",  RegexOption.IGNORE_CASE), "nahn")
        s = s.replace(Regex("\\bna\\b",   RegexOption.IGNORE_CASE), "nah")
        s = s.replace(Regex("\\bnam\\b",  RegexOption.IGNORE_CASE), "nahm")
        // ── Lenited (mutated) initial consonants ──────────────────────────────────────
        s = s.replace(Regex("\\bBheinn\\b", RegexOption.IGNORE_CASE), "Ven")
        s = s.replace(Regex("\\bBheag\\b",  RegexOption.IGNORE_CASE), "Veck")
        s = s.replace(Regex("\\bMh[oò]r\\b", RegexOption.IGNORE_CASE), "Wore")
        s = s.replace(Regex("\\bMheall\\b", RegexOption.IGNORE_CASE), "Vyowl")
        // ── Unmutated root words ──────────────────────────────────────────────────────
        s = s.replace(Regex("\\bBeinn\\b",  RegexOption.IGNORE_CASE), "Ben")
        s = s.replace(Regex("\\bBeag\\b",   RegexOption.IGNORE_CASE), "Beck")
        s = s.replace(Regex("\\bMeall\\b",  RegexOption.IGNORE_CASE), "Myowl")
        s = s.replace(Regex("\\bM[oò]r\\b", RegexOption.IGNORE_CASE), "More")
        s = s.replace(Regex("\\bSg[uùú]rr\\b", RegexOption.IGNORE_CASE), "Skoor")
        s = s.replace(Regex("\\bSg[oò]rr\\b",  RegexOption.IGNORE_CASE), "Skor")
        s = s.replace(Regex("\\bDearg\\b",  RegexOption.IGNORE_CASE), "Jerack")
        s = s.replace(Regex("\\bDubh\\b",   RegexOption.IGNORE_CASE), "Doo")
        s = s.replace(Regex("\\bRuadh\\b",  RegexOption.IGNORE_CASE), "Roo-a")
        s = s.replace(Regex("\\bLiath\\b",  RegexOption.IGNORE_CASE), "Lee-a")
        s = s.replace(Regex("\\bGarbh\\b",  RegexOption.IGNORE_CASE), "Garv")
        s = s.replace(Regex("\\bAonach\\b", RegexOption.IGNORE_CASE), "Oo-nach")
        s = s.replace(Regex("\\bCoire\\b",  RegexOption.IGNORE_CASE), "Corra")
        s = s.replace(Regex("\\bChoire\\b", RegexOption.IGNORE_CASE), "Chorra")
        s = s.replace(Regex("\\bGleann\\b", RegexOption.IGNORE_CASE), "Glyown")
        s = s.replace(Regex("\\bBealach\\b", RegexOption.IGNORE_CASE), "Byalach")
        s = s.replace(Regex("\\bSr[oò]n\\b", RegexOption.IGNORE_CASE), "Strawn")
        s = s.replace(Regex("\\bBidean\\b", RegexOption.IGNORE_CASE), "Bee-jen")
        // ── Strip Gaelic accents so TTS doesn't stall on unknown characters ──────────
        s = s.replace('à', 'a').replace('è', 'e').replace('ì', 'i')
             .replace('ò', 'o').replace('ù', 'u')
             .replace('À', 'A').replace('È', 'E').replace('Ì', 'I')
             .replace('Ò', 'O').replace('Ù', 'U')
        return s
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
