package com.zhousl.aether.data

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val EngineInitTimeoutMillis = 5_000L
private const val SpeechStartTimeoutMillis = 10_000L

/**
 * Text-to-speech and media playback for Qing's device tools (v2, device batch 1).
 *
 * Unlike the other device capabilities, this state deliberately outlives a
 * single turn: the user asks Qing to read something out loud or play a track,
 * then keeps talking. So the engine and the player live in one process-wide
 * holder that a later turn can still stop. Both are created lazily and kept for
 * the process lifetime, which is what keeps a second "read this" request fast.
 */
internal object DeviceMediaController {
    private val utteranceCounter = AtomicInteger()

    @Volatile
    private var engine: TextToSpeech? = null

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var lastPlayerError: String = ""

    suspend fun speak(context: Context, text: String): JSONObject {
        val content = text.trim()
        if (content.isEmpty()) return failure("'text' is required for action=speak.")
        val synthesizer = ensureEngine(context)
            ?: return failure(
                "No Android text-to-speech engine responded. Install or enable a system TTS engine and try again.",
            )
        return withContext(Dispatchers.Main) {
            val languageStatus = runCatching { synthesizer.setLanguage(Locale.getDefault()) }
                .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            val started = CompletableDeferred<Boolean>()
            synthesizer.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        started.complete(true)
                    }

                    override fun onDone(utteranceId: String?) = Unit

                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(utteranceId: String?) {
                        started.complete(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        started.complete(false)
                    }
                },
            )
            val utteranceId = "qing-speak-${utteranceCounter.incrementAndGet()}"
            val queued = synthesizer.speak(content, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (queued != TextToSpeech.SUCCESS) {
                return@withContext failure("The text-to-speech engine rejected the request.")
            }
            // null means "still starting"; only an explicit false is a failure.
            val didStart = withTimeoutOrNull(SpeechStartTimeoutMillis) { started.await() }
            if (didStart == false) {
                return@withContext failure(
                    "The text-to-speech engine could not start. Check the system TTS engine and its voice data.",
                )
            }
            JSONObject().apply {
                put("ok", true)
                put("spoken_chars", content.length)
                put(
                    "language_available",
                    languageStatus != TextToSpeech.LANG_MISSING_DATA &&
                        languageStatus != TextToSpeech.LANG_NOT_SUPPORTED,
                )
                put("note", "Speech keeps playing after this call returns; use action=stop_media to stop it.")
            }
        }
    }

    suspend fun play(context: Context, url: String): JSONObject {
        val source = url.trim()
        if (source.isEmpty()) return failure("'url' is required for action=player_play.")
        return withContext(Dispatchers.IO) {
            stopPlayer()
            lastPlayerError = ""
            val media = MediaPlayer()
            runCatching {
                media.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                media.setDataSource(source)
                media.setOnErrorListener { _, what, extra ->
                    lastPlayerError = "MediaPlayer error what=$what extra=$extra"
                    true
                }
                media.prepareAsync()
                player = media
                JSONObject().apply {
                    put("ok", true)
                    put("source", source)
                    put("state", "preparing")
                    put("note", "Playback starts when the source is ready; use action=stop_media to stop it.")
                }
            }.getOrElse { error ->
                runCatching { media.release() }
                failure("Could not play '$source': ${error.message ?: error::class.java.simpleName}")
            }
        }
    }

    fun stop(): JSONObject {
        val stopped = mutableListOf<String>()
        val synthesizer = engine
        if (synthesizer != null) {
            val result = runCatching { synthesizer.stop() }.getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.SUCCESS) stopped += "speech"
        }
        if (player != null) {
            stopPlayer()
            stopped += "audio"
        }
        return JSONObject().apply {
            put("ok", true)
            put("stopped", stopped.joinToString(",").ifEmpty { "nothing" })
            if (lastPlayerError.isNotBlank()) put("last_error", lastPlayerError)
        }
    }

    private suspend fun ensureEngine(context: Context): TextToSpeech? {
        engine?.let { return it }
        return withContext(Dispatchers.Main) {
            engine?.let { return@withContext it }
            var created: TextToSpeech? = null
            val ready = withTimeoutOrNull(EngineInitTimeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    created = TextToSpeech(context.applicationContext) { status ->
                        if (continuation.isActive) {
                            continuation.resume(status == TextToSpeech.SUCCESS)
                        }
                    }
                }
            }
            val synthesizer = created
            if (ready != true || synthesizer == null) {
                synthesizer?.let { runCatching { it.shutdown() } }
                return@withContext null
            }
            engine = synthesizer
            synthesizer
        }
    }

    private fun stopPlayer() {
        val media = player ?: return
        player = null
        runCatching { if (media.isPlaying) media.stop() }
        runCatching { media.reset() }
        runCatching { media.release() }
    }

    private fun failure(message: String): JSONObject =
        JSONObject().apply {
            put("ok", false)
            put("errmsg", message)
        }
}
