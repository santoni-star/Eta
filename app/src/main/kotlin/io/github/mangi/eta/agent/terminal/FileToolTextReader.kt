package io.github.mangi.eta.agent.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

internal data class FileToolTextChunk(
    val text: String,
    val bytesConsumed: Int,
    val eof: Boolean,
)

/** Байтові курсори можуть переходити лише через повністю декодовані символи та не зараховують половину символів UTF-8 як прочитані.*/
internal class FileToolTextReader(private val backend: FileToolBackend) {
    fun read(stat: FileToolStat, offset: Long, maxBytes: Int, maxChars: Int = 16_000): FileToolTextChunk {
        val bytes = backend.readBytes(stat.path, offset, maxBytes.coerceIn(1, 16_000))
        if (bytes.any { it == 0.toByte() }) throw FileToolException("BINARY_FILE", "мистецтваштукСумкаМіститьдваВведітьсистемаВсерединіДозволити，будь ласказробитивикористовуватимистецтваштуккерівництвопозаабокінецькінецьпрацюватиІнструментвпричина")
        val eof = offset + bytes.size >= stat.sizeBytes
        val input = ByteBuffer.wrap(bytes)
        val output = CharBuffer.allocate(maxChars.coerceIn(2, 16_000))
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(input, output, eof)
        if (result.isError) throw FileToolException("NOT_UTF8_TEXT", "ВсерединіДозволитинемаєтакматиефект UTF-8 мистецтвакнига，абохарактерФестивальтурзнакбітнахарактерсимволсерединаміж")
        if (bytes.isNotEmpty() && input.position() == 0) {
            throw FileToolException("READ_LIMIT_TOO_SMALL", "характерФестивальначальникаобмеженнянемаєстопазачитатиВиберітьодиніндивідуальнийзакінченовсехарактерсимвол，будь ласказробитивикористовуватидомало 4 характерФестиваль")
        }
        if (bytes.isEmpty() && offset < stat.sizeBytes) throw FileToolException("FILE_CHANGED", "мистецтваштукіснуютьчитатиВиберітьОчікуйтеміжвжеЗміназмінити")
        output.flip()
        return FileToolTextChunk(output.toString(), input.position(), eof && input.position() == bytes.size)
    }
}
