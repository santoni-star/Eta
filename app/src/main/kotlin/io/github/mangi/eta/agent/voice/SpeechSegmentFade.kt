package io.github.mangi.eta.agent.voice

/**
 * Довгий текст синтезується незалежно в сегменти та безперешкодно відтворюється. Кінцева та початкова вибірки двох сусідніх сегментів не обов’язково знаходяться поблизу нульової точки.
 * Крок хвилі буде почуто як звук "Чжи". Виконайте кілька мілісекунд лінійного поступового набуття та згасання на початку та в кінці кожного абзацу: для визначення кінця абзацу потрібно зачекати до кінця абзацу.
 * Таким чином, останні семпли [fadeSamples] завжди зберігаються до [end], коли вони зникають. Вхід - 16-бітний моно PCM з прямим порядком байтів,
 * Кожен запис є повним зразком.
 */
internal class SpeechSegmentFade(private val fadeSamples: Int = FADE_SAMPLES) {
    private var tail = ByteArray(0)
    private var emittedSamples = 0L

    /** Повертає розділ, який можна відтворити негайно, можливо, порожній.*/
    fun write(bytes: ByteArray): ByteArray {
        val joined = tail + bytes
        val keep = minOf(joined.size, fadeSamples * BYTES_PER_SAMPLE)
        tail = joined.copyOfRange(joined.size - keep, joined.size)
        return joined.copyOfRange(0, joined.size - keep).also(::fadeIn)
    }

    /** Завершіть поточний сегмент і поверніться до кінця сегмента після згасання, після чого ви можете почати наступний сегмент.*/
    fun end(): ByteArray {
        val last = tail
        tail = ByteArray(0)
        fadeIn(last)
        val samples = last.size / BYTES_PER_SAMPLE
        val ramp = minOf(samples, fadeSamples)
        for (index in samples - ramp until samples) {
            scale(last, index, (samples - 1 - index).toFloat() / ramp)
        }
        emittedSamples = 0
        return last
    }

    private fun fadeIn(bytes: ByteArray) {
        val samples = bytes.size / BYTES_PER_SAMPLE
        var index = 0
        while (index < samples && emittedSamples + index < fadeSamples) {
            scale(bytes, index, (emittedSamples + index).toFloat() / fadeSamples)
            index++
        }
        emittedSamples += samples
    }

    private fun scale(bytes: ByteArray, sample: Int, gain: Float) {
        val offset = sample * BYTES_PER_SAMPLE
        val value = (bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)
        val scaled = (value.toShort() * gain).toInt()
        bytes[offset] = scaled.toByte()
        bytes[offset + 1] = (scaled shr 8).toByte()
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        /** 5 мс при 24 кГц, досить коротко, щоб не почути зміни гучності, достатньо довго, щоб усунути кроки.*/
        const val FADE_SAMPLES = 120
    }
}
