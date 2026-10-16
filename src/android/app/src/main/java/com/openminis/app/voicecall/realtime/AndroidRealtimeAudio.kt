package com.openminis.app.voicecall.realtime

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import androidx.core.content.ContextCompat
import java.util.concurrent.LinkedBlockingQueue

/**
 * 全双工的麦克风 + 扬声器。回声消除是全双工的前提（否则模型会听到自己说话而自我打断）：
 * 麦克风用 VOICE_COMMUNICATION 音源（系统在这个音源上默认启用回声消除/降噪），
 * 并在设备支持时显式挂上 AEC / 降噪 / 自动增益；播放用 VOICE_COMMUNICATION 用途，让系统拿到回放参考信号。
 * 不同机型的回声消除效果无法在 CI 验证，必须在真机上确认；耳机/听筒下回声风险最小。
 */
class AndroidRealtimeAudio(context: Context) : RealtimeAudio {
    private val appContext = context.applicationContext

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var effects = emptyList<android.media.audiofx.AudioEffect>()
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    private val playQueue = LinkedBlockingQueue<ByteArray>()
    private val trackLock = Any()
    @Volatile private var running = false

    override fun start(inputSampleRate: Int, outputSampleRate: Int, onFrame: (ByteArray) -> Unit): Boolean {
        stop()
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val inMin = AudioRecord.getMinBufferSize(inputSampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outMin = AudioTrack.getMinBufferSize(outputSampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (inMin <= 0 || outMin <= 0) return false
        val frameBytes = inputSampleRate / FRAMES_PER_SECOND * 2
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, inputSampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(inMin, frameBytes * 4),
            )
        } catch (_: SecurityException) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(outputSampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(outMin, outputSampleRate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        effects = buildList {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }?.let(::add)
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }?.let(::add)
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(rec.audioSessionId)?.also { it.enabled = true }?.let(::add)
        }
        record = rec
        track = out
        running = true
        playQueue.clear()
        runCatching { rec.startRecording() }.onFailure {
            stop()
            return false
        }
        out.play()
        captureThread = Thread({
            val buffer = ByteArray(frameBytes)
            while (running) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read > 0) onFrame(buffer.copyOf(read)) else if (read < 0) break
            }
        }, "realtime-capture").also { it.start() }
        playThread = Thread({
            while (running) {
                val chunk = try { playQueue.take() } catch (_: InterruptedException) { break }
                synchronized(trackLock) { if (running) track?.write(chunk, 0, chunk.size) }
            }
        }, "realtime-playback").also { it.start() }
        return true
    }

    override fun play(pcm: ByteArray) {
        if (running && pcm.isNotEmpty()) playQueue.offer(pcm)
    }

    override fun flush() {
        playQueue.clear()
        synchronized(trackLock) {
            val t = track ?: return
            runCatching {
                t.pause()
                t.flush()
                t.play()
            }
        }
    }

    override fun stop() {
        running = false
        captureThread?.interrupt()
        playThread?.interrupt()
        captureThread = null
        playThread = null
        playQueue.clear()
        effects.forEach { runCatching { it.release() } }
        effects = emptyList()
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        synchronized(trackLock) {
            track?.let { runCatching { it.stop() }; it.release() }
            track = null
        }
    }

    private companion object {
        /** 每帧 20 ms。 */
        const val FRAMES_PER_SECOND = 50
    }
}
