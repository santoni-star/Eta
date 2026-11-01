package io.github.mangi.eta.data.db

import android.database.Cursor
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Кожен рядок обмежений, а загальна довжина документа не обмежена; уникнення повної історії, що займає один рядок CursorWindow.*/
@Entity(tableName = "agent_text_chunks", primaryKeys = ["owner_table", "owner_id", "field", "chunk_index"])
internal data class AgentTextChunkEntity(
    @ColumnInfo(name = "owner_table") val ownerTable: String,
    @ColumnInfo(name = "owner_id") val ownerId: String,
    val field: String,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    val content: String,
)

/** Бізнес-DAO записує основний запис і фрагменти в одній транзакції Room, не відкриваючи посилання на сховище рівня домену.*/
internal interface ChunkedTextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTextChunk(chunk: AgentTextChunkEntity)

    @Query("DELETE FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field")
    suspend fun deleteTextChunks(table: String, owner: String, field: String)

    @Query("SELECT * FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field ORDER BY chunk_index LIMIT :limit OFFSET :offset")
    suspend fun textChunks(table: String, owner: String, field: String, limit: Int, offset: Int): List<AgentTextChunkEntity>

    @Query("SELECT chunk_index, content FROM agent_text_chunks WHERE owner_table = :table AND owner_id = :owner AND field = :field ORDER BY chunk_index")
    fun textChunkCursor(table: String, owner: String, field: String): Cursor

    suspend fun storeText(table: String, owner: String, field: String, text: String): String {
        deleteTextChunks(table, owner, field)
        if (text.length <= CHUNK_CHARS && !text.startsWith(REFERENCE_PREFIX)) return text
        var count = 0
        var offset = 0
        while (offset < text.length) {
            // Не розбивайте сурогатні пари UTF-16 на два ТЕКСТИ SQLite.
            var end = minOf(offset + CHUNK_CHARS, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            insertTextChunk(AgentTextChunkEntity(table, owner, field, count++, text.substring(offset, end)))
            offset = end
        }
        return "$REFERENCE_PREFIX$count:${text.length}"
    }

    suspend fun restoreText(table: String, owner: String, field: String, stored: String): String {
        if (!stored.startsWith(REFERENCE_PREFIX)) return stored
        val parts = stored.removePrefix(REFERENCE_PREFIX).split(':')
        check(parts.size == 2) { "Invalid history payload reference" }
        val count = parts[0].toInt()
        val length = parts[1].toInt()
        check(count > 0 && length > 0) { "Invalid history payload size" }
        val result = StringBuilder()
        var offset = 0
        while (offset < count) {
            val page = textChunks(table, owner, field, minOf(32, count - offset), offset)
            check(page.isNotEmpty()) { "History payload chunk missing" }
            page.forEach { chunk ->
                check(chunk.chunkIndex == offset++) { "History payload chunk out of order" }
                result.append(chunk.content)
            }
        }
        check(result.length == length) { "History payload length mismatch" }
        return result.toString()
    }

    companion object {
        private const val CHUNK_CHARS = 16_384
        const val REFERENCE_PREFIX = "@eta:chunks:v1:"
    }
}
