/*
 * Copyright 2026 The Dogo Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package app.dogo.launcher.voice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * One live conversation with the OpenAI Realtime API: streams the microphone
 * up as 24 kHz PCM and plays the reply as it arrives. The user can talk over
 * the reply; it stops at once (barge-in).
 *
 * [token] is a short-lived client secret (ek_...) or an API key. Events are
 * delivered on a background thread; callers hop to the main thread.
 */
class VoiceSession(
    private val token: String,
    private val model: String,
    private val instructions: String,
    private val voice: String,
    private val onEvent: (RealtimeProtocol.Event) -> Unit,
    private val client: OkHttpClient = defaultClient,
    /**
     * Debug builds only: 24 kHz PCM streamed as if spoken, once, right after
     * the session starts. Lets on-device tests exercise the whole voice path
     * without someone talking. Null in normal use.
     */
    private val injectedSpeech: java.io.File? = null,
) {
    private var socket: WebSocket? = null
    private val running = AtomicBoolean(false)
    private val muted = AtomicBoolean(false)
    private var record: AudioRecord? = null
    private var player: AudioTrack? = null

    val isRunning: Boolean get() = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val request = Request.Builder()
            .url(RealtimeProtocol.url(model))
            .header("Authorization", "Bearer $token")
            .build()
        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(RealtimeProtocol.sessionUpdate(instructions, voice).toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (val event = RealtimeProtocol.parse(text)) {
                        RealtimeProtocol.Event.Ready -> {
                            startAudio()
                            onEvent(event)
                        }

                        is RealtimeProtocol.Event.Audio -> play(event.base64)

                        // Barge-in: drop whatever is still queued to play.
                        RealtimeProtocol.Event.UserStartedSpeaking -> {
                            interruptPlayback()
                            onEvent(event)
                        }

                        RealtimeProtocol.Event.Ignored -> Unit

                        else -> onEvent(event)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!running.get()) return
                    val why = when (response?.code) {
                        401, 403 -> "The voice key was refused. Check the key, or that the token service is running."
                        null -> t.message ?: "Lost the connection."
                        else -> "The voice service said ${response.code}."
                    }
                    stop()
                    onEvent(RealtimeProtocol.Event.Failed(why))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (running.get()) {
                        stop()
                        onEvent(RealtimeProtocol.Event.Failed(reason.ifBlank { "The voice session ended." }))
                    }
                }
            },
        )
    }

    fun send(event: JSONObject) {
        socket?.send(event.toString())
    }

    fun setMuted(value: Boolean) = muted.set(value)

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        socket?.close(1000, "bye")
        socket = null
        record?.let { r ->
            runCatching { r.stop() }
            r.release()
        }
        record = null
        player?.let { p ->
            runCatching {
                p.pause()
                p.flush()
            }
            p.release()
        }
        player = null
    }

    @SuppressLint("MissingPermission") // Checked by the screen before start().
    private fun startAudio() {
        if (record != null) return
        val rate = RealtimeProtocol.SAMPLE_RATE
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minIn, CHUNK_BYTES * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            stop()
            onEvent(RealtimeProtocol.Event.Failed("Couldn't open the microphone."))
            return
        }
        // Keep the speaker out of the mic, or the model hears itself and barges in on its own reply.
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.enabled = true
        record = rec

        val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minOut, rate * 2))
            .setSessionId(rec.audioSessionId)
            .build()
            .also { it.play() }

        rec.startRecording()
        val injected = injectedSpeech?.takeIf { it.isFile }?.readBytes()
        thread(name = "dogo-voice-mic", isDaemon = true) {
            val buf = ByteArray(CHUNK_BYTES)
            val silence = ByteArray(CHUNK_BYTES)
            var injectAt = 0
            while (running.get()) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) continue
                val chunk = when {
                    injected != null && injectAt < injected.size -> {
                        val end = minOf(injected.size, injectAt + n)
                        injected.copyOfRange(injectAt, end).copyOf(n).also { injectAt = end }
                    }

                    muted.get() -> silence

                    else -> buf
                }
                socket?.send(RealtimeProtocol.appendAudio(Base64.encodeToString(chunk, 0, n, Base64.NO_WRAP)).toString())
            }
        }
    }

    private fun play(base64: String) {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        player?.write(bytes, 0, bytes.size)
    }

    private fun interruptPlayback() {
        player?.let { p ->
            runCatching {
                p.pause()
                p.flush()
                p.play()
            }
        }
        send(RealtimeProtocol.cancelResponse())
    }

    companion object {
        /** 100 ms of 24 kHz 16-bit mono. */
        const val CHUNK_BYTES = RealtimeProtocol.SAMPLE_RATE * 2 / 10

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
