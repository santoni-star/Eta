package io.github.mangi.eta.agent.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CancellationException

internal class SpeechPcmOutput : SpeechAudioOutput {
    private var track: AudioTrack? = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setBufferSizeInBytes(maxOf(SAMPLE_RATE, AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
        .setTransferMode(AudioTrack.MODE_STREAM).build()
    private var frames = 0L
    private var started = false
    private var finished = false

    override fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val written = synchronized(this) {
                val audio = track ?: throw CancellationException("Speech playback stopped")
                check(!finished) { "Speech audio input already finished" }
                val remaining = bytes.size - offset
                val count = audio.write(bytes, offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
                if (count < 0) throw SpeechFailure(SpeechErrorCode.AUDIO, "звукчастотатрансляціяпоставитивтратитипоразка")
                frames += count / BYTES_PER_FRAME
                // Якщо порожня звукова доріжка запущена заздалегідь, вона закінчиться та деактивується під час очікування першого пакета з хмари; якщо він заповнений, він буде запущений знову. Короткий запис також означає, що досягнуто верхньої межі запису.
                if (!started && frames > 0 && (frames >= audio.bufferSizeInFrames || count < remaining)) {
                    audio.play()
                    started = true
                }
                count
            }
            offset += written
            if (written == 0) Thread.sleep(5)
        }
    }

    @Synchronized override fun finish() {
        val audio = track ?: throw CancellationException("Speech playback stopped")
        if (finished) return
        // Короткий звук може ніколи не заповнити поріг запуску. Коли введення закінчиться, воно буде запущено відповідно до фактичної кількості кадрів, без додавання тиші та обрізання тексту.
        if (!started && frames > 0) {
            audio.setStartThresholdInFrames(frames.toInt())
            audio.play()
            started = true
        }
        finished = true
    }

    @Synchronized override fun drained(): Boolean = track?.let {
        finished && (it.playbackHeadPosition.toLong() and 0xffffffffL) >= frames
    } ?: true

    @Synchronized override fun close() {
        track?.let { it.pause(); it.flush(); it.release() }
        track = null
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_FRAME = 2
    }
}
