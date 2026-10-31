package com.noop.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.noop.ai.CoachLogAction
import java.util.Locale

/** Speaks through a voice explicitly marked offline by the installed Android TTS engine. */
internal class CoachSpeechOutput(context: Context, private val error: (String) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var initializing = true
    private var closed = false
    private var pending: Pair<String, () -> Unit>? = null
    private var completed: (() -> Unit)? = null
    private var engine: TextToSpeech? = null
    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            main.post {
                if (!closed) {
                    initializing = false
                    ready = status == TextToSpeech.SUCCESS
                    pending?.let { request -> pending = null; speak(request.first, request.second) }
                }
            }
        }
        engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (id == "noop-last") main.post { completed?.invoke(); completed = null } }
            @Deprecated("Android callback") override fun onError(id: String?) { main.post { completed = null; error("Offline read-aloud failed. Check installed TTS voices.") } }
        })
    }
    fun speak(text: String, onDone: () -> Unit = {}) {
        if (closed) return
        if (initializing) { pending = text to onDone; return }
        val voice = engine?.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == Locale.getDefault().language }
            ?.sortedByDescending { it.locale.country == Locale.getDefault().country }?.firstOrNull()
        if (!ready || voice == null) { error("No offline TTS voice is ready for this language. Download a voice in Android Text-to-speech settings."); return }
        engine?.voice = voice
        stop()
        completed = onDone
        val proposed = CoachLogAction.parse(text)?.let { "\nProposed log, not saved: ${it.preview()}. Use the on-screen Confirm button to save." }.orEmpty()
        val clean = (CoachLogAction.visibleText(text) + proposed).replace(Regex("[`*#]"), "")
        val chunks = clean.chunked(2500)
        if (chunks.isEmpty()) { completed?.invoke(); completed = null; return }
        chunks.forEachIndexed { i, part -> engine?.speak(part, TextToSpeech.QUEUE_ADD, null, if (i == chunks.lastIndex) "noop-last" else "noop-$i") }
    }
    fun stop() { completed = null; pending = null; engine?.stop() }
    fun close() { closed = true; stop(); ready = false; engine?.shutdown(); engine = null }
}
