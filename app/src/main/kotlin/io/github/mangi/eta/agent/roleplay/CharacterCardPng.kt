package io.github.mangi.eta.agent.roleplay

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32

internal object CharacterCardPng {
    const val MAX_FILE_BYTES = 32 * 1024 * 1024
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private data class Chunk(val type: String, val data: ByteArray)

    fun isPng(bytes: ByteArray): Boolean = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(signature)

    fun validate(bytes: ByteArray) { chunks(bytes) }

    fun read(bytes: ByteArray): CharacterCard {
        val text = chunks(bytes).filter { it.type == "tEXt" }.mapNotNull { chunk ->
            val separator = chunk.data.indexOf(0)
            if (separator <= 0) null else chunk.data.copyOfRange(0, separator).toString(Charsets.ISO_8859_1).lowercase() to
                chunk.data.copyOfRange(separator + 1, chunk.data.size)
        }
        val v3 = text.firstOrNull { it.first == "ccv3" }?.second
        val encoded = v3
            ?: text.firstOrNull { it.first == "chara" }?.second
            ?: throw CharacterCardException("CARD_METADATA_MISSING", "цеВІДЧИНЕНОмалюнокшматокбезматирігколіркартканомервідповідно до，будь ласкавибративибратиОригіналпочатокрігколіркартка PNG")
        return try {
            CharacterCardCodec.decodeBytes(Base64.getDecoder().decode(encoded))
        } catch (failure: IllegalArgumentException) {
            if (failure is CharacterCardException && failure.code == "CARD_TOO_LARGE") throw failure
            if (v3 != null) throw CharacterCardException("CARD_V3_INVALID", "PNG серединаз V3 рігколірномервідповідно допошкодженняпогано，ще нізробитивикористовуватистарийверсіякниганомервідповідно додляпокоління", failure)
            throw CharacterCardException("CARD_INVALID_PNG", "PNG серединазрігколірномервідповідно досіткаРежимнемаєефект", failure)
        }
    }

    fun write(image: ByteArray, card: CharacterCard): ByteArray {
        val original = chunks(image)
        val output = ByteArrayOutputStream()
        output.write(signature)
        DataOutputStream(output).use { stream ->
            original.forEach { chunk ->
                val keyword = if (chunk.type == "tEXt") chunk.data.takeWhile { it != 0.toByte() }
                    .toByteArray().toString(Charsets.ISO_8859_1).lowercase() else ""
                if (keyword in setOf("chara", "ccv3")) return@forEach
                if (chunk.type == "IEND") {
                    listOf("chara" to 2, "ccv3" to 3).forEach { (key, version) ->
                        val encoded = Base64.getEncoder().encode(CharacterCardCodec.exportView(card, version).toByteArray(Charsets.UTF_8))
                        writeChunk(stream, Chunk("tEXt", key.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + encoded))
                    }
                }
                writeChunk(stream, chunk)
            }
        }
        if (output.size() > MAX_FILE_BYTES) throw CharacterCardException("CARD_TOO_LARGE", "PNG рігколіркарткаобганятиПас 32 MiB，будь ласказмінитивикористовувати JSON керівництвопоза")
        return output.toByteArray()
    }

    private fun chunks(bytes: ByteArray): List<Chunk> {
        require(bytes.size <= MAX_FILE_BYTES) { "рігколіркарткамалюнокшматокобганятиПас 32 MiB обмеженнясистема" }
        require(isPng(bytes)) { "малюнокшматокнемаєтак PNG сіткаРежим" }
        val result = mutableListOf<Chunk>()
        var offset = 8
        while (offset < bytes.size) {
            require(bytes.size - offset >= 12) { "PNG номервідповідно донемаєзакінченовсе" }
            val size = ByteBuffer.wrap(bytes, offset, 4).int
            require(size >= 0 && size <= bytes.size - offset - 12) { "PNG номервідповідно дошматокдовговитрачатинемаєефект" }
            val typeBytes = bytes.copyOfRange(offset + 4, offset + 8)
            val data = bytes.copyOfRange(offset + 8, offset + 8 + size)
            val expectedCrc = ByteBuffer.wrap(bytes, offset + 8 + size, 4).int
            val crc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
            require(crc == expectedCrc) { "PNG номервідповідно дошколатествтратитипоразка" }
            val type = typeBytes.toString(Charsets.US_ASCII)
            result += Chunk(type, data)
            offset += size + 12
            if (type == "IEND") break
        }
        require(result.firstOrNull()?.type == "IHDR" && result.lastOrNull()?.type == "IEND") { "PNG вузолструктуранемаєзакінченовсе" }
        val header = result.first().data
        require(header.size == 13 && result.count { it.type == "IHDR" } == 1) { "PNG малюнокшматокголованемаєефект" }
        val width = ByteBuffer.wrap(header, 0, 4).int
        val height = ByteBuffer.wrap(header, 4, 4).int
        require(width > 0 && height > 0 && width.toLong() * height <= 64_000_000) { "PNG малюнокшматоклінійкадюймПасвеликийабонемаєефект" }
        val bitDepth = header[8].toInt() and 0xff
        val colorType = header[9].toInt() and 0xff
        val allowedDepths = when (colorType) {
            0 -> setOf(1, 2, 4, 8, 16)
            2, 4, 6 -> setOf(8, 16)
            3 -> setOf(1, 2, 4, 8)
            else -> emptySet()
        }
        require(bitDepth in allowedDepths && header[10] == 0.toByte() && header[11] == 0.toByte() && header[12].toInt() in 0..1) {
            "PNG малюнокшматокголовасіткаРежимнемаєефект"
        }
        require(result.any { it.type == "IDAT" } && result.last().data.isEmpty()) { "PNG відсутністьмаломалюнокшматокномервідповідно до" }
        return result
    }

    private fun writeChunk(stream: DataOutputStream, chunk: Chunk) {
        val type = chunk.type.toByteArray(Charsets.US_ASCII)
        stream.writeInt(chunk.data.size)
        stream.write(type)
        stream.write(chunk.data)
        stream.writeInt(CRC32().apply { update(type); update(chunk.data) }.value.toInt())
    }
}
