package com.hmdeepseek.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 录音 → 16bit PCM WAV。
 *
 * 官方 ASR 只认 16-bit PCM WAV / Ogg Opus，≤120 秒、≤8 MiB、整段上传，
 * 所以这里用 AudioRecord 自己录 PCM、自己封 WAV 头，格式最保险。
 */
class WavRecorder(private val sampleRate: Int = 16000) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var sink: ByteArrayOutputStream? = null

    @Volatile
    private var recording: Boolean = false

    private var startedAt: Long = 0L

    val isRecording: Boolean get() = recording

    fun elapsedMs(): Long = if (recording) System.currentTimeMillis() - startedAt else 0L

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, 8192)
            )
        } catch (e: Exception) {
            null
        } ?: return false

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return false
        }

        return try {
            rec.startRecording()
            val buffer = ByteArray(4096)
            val out = ByteArrayOutputStream()
            sink = out
            record = rec
            recording = true
            startedAt = System.currentTimeMillis()
            thread = Thread {
                while (recording) {
                    val n = try {
                        rec.read(buffer, 0, buffer.size)
                    } catch (e: Exception) {
                        -1
                    }
                    if (n > 0) {
                        synchronized(out) { out.write(buffer, 0, n) }
                    } else if (n < 0) {
                        break
                    }
                    if (System.currentTimeMillis() - startedAt > MAX_MS) {
                        recording = false
                    }
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
            true
        } catch (e: Exception) {
            recording = false
            runCatching { rec.release() }
            record = null
            false
        }
    }

    /** 停止并返回 WAV 字节；太短（<0.2 秒）返回 null */
    fun stop(): ByteArray? {
        if (!recording && record == null && sink == null) return null
        recording = false
        runCatching { thread?.join(600) }
        thread = null
        record?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        record = null
        val pcm = sink?.let { s -> synchronized(s) { s.toByteArray() } }
        sink = null
        if (pcm == null || pcm.size < 1600) return null
        return wrapWav(pcm)
    }

    fun cancel() {
        recording = false
        runCatching { thread?.join(300) }
        thread = null
        record?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        record = null
        sink = null
    }

    private fun wrapWav(pcm: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + pcm.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1.toShort())
        header.putShort(1.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2.toShort())
        header.putShort(16.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcm.size)

        val out = ByteArrayOutputStream(44 + pcm.size)
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        private const val MAX_MS = 60_000L
    }
}
