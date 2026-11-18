package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AgentFileResultFormatterTest {
    @Test
    fun appendReportsOnlyTheWrittenAmount() {
        val summary = summarize("write_file", """{"mode":"append","bytes_written":1536,"size_bytes":8192}""")
        assertEquals("Appended 1.5 KiB", summary)
        assertEquals("Written 0 B", summarize("write_file", """{"mode":"overwrite","bytes_written":0}"""))
    }

    @Test
    fun statDoesNotPresentDirectoryMetadataAsItsContentSize() {
        assertEquals("Directory", summarize("stat_file", """{"file":{"kind":"directory","size_bytes":4096}}"""))
        assertEquals("File · 2 MiB", summarize("stat_file", """{"file":{"kind":"file","size_bytes":2097152}}"""))
        assertEquals("Symbolic link", summarize("stat_file", """{"file":{"kind":"symlink","size_bytes":42}}"""))
    }

    @Test
    fun readDistinguishesPaginationFromScanningBeforeTheRequestedLine() {
        assertEquals("Read first 1 KiB · More available", summarize("read_file", """{"bytes_read":1024,"truncated":true,"start_line_reached":true}"""))
        assertEquals("Target line not reached · More to read", summarize("read_file", """{"bytes_read":0,"truncated":true,"start_line_reached":false,"stop_reason":"scan_limit"}"""))
        assertEquals("Target line not reached · End of file", summarize("read_file", """{"bytes_read":0,"truncated":false,"start_line_reached":false,"stop_reason":"eof"}"""))
    }

    @Test
    fun listingAndSearchCountOnlyReturnedResultsAndPreservePartialCoverage() {
        assertEquals("Listed 1 item · More available", summarize("list_directory", """{"entries":[{}],"has_more":true}"""))
        assertEquals("Directory is empty", summarize("list_directory", """{"entries":[]}"""))
        assertEquals("Found 2 files · More available · Limited scan scope", summarize("glob_files", """{"matches":[{},{}],"truncated":true,"partial":true}"""))
        assertEquals("Found 1 match · More available · Limited scan scope", summarize("grep_files", """{"matches":[{}],"truncated":true,"partial":true,"total_skipped":2,"next_cursor":"next-page"}"""))
        assertEquals("No matching content found · Limited scan scope", summarize("grep_files", """{"matches":[],"truncated":false,"partial":true,"total_skipped":3}"""))
    }

    @Test
    fun editDoesNotPresentTheWholeRewrittenFileAsChangedBytes() {
        assertEquals("Replaced 2 occurrences", summarize("edit_file", """{"replacements":2,"bytes_written":90000}"""))
    }

    @Test
    fun summariesNeverExposePathsContentOrSearchConditions() {
        val privateFields = JSONObject()
            .put("path", "/private/path-marker")
            .put("content", "content-marker")
            .put("pattern", "pattern-marker")
            .put("query", "query-marker")
        val results = listOf(
            "write_file" to """{"mode":"append","bytes_written":12}""",
            "read_file" to """{"bytes_read":12}""",
            "stat_file" to """{"file":{"kind":"file","size_bytes":12,"name":"name-marker","path":"nested-path-marker"}}""",
            "edit_file" to """{"replacements":1}""",
            "list_directory" to """{"entries":[{"name":"name-marker","path":"nested-path-marker"}]}""",
            "glob_files" to """{"matches":[{"path":"nested-path-marker"}]}""",
            "grep_files" to """{"matches":[{"path":"nested-path-marker","text":"matched-text-marker"}]}""",
        )
        results.forEach { (tool, raw) ->
            val json = JSONObject(raw)
            privateFields.keys().forEach { key -> json.put(key, privateFields.get(key)) }
            val summary = AgentFileResultFormatter.summarize(tool, json)!!
            assertFalse(summary, summary.contains("marker"))
        }
    }

    @Test
    fun unknownOrIncompleteSchemasFallBackWithoutInventingValues() {
        assertNull(summarize("write_file", """{"mode":"append"}"""))
        assertNull(summarize("write_file", """{"mode":"unexpected","bytes_written":1}"""))
        assertNull(summarize("read_file", """{"bytes_read":null}"""))
        assertNull(summarize("read_file", """{"bytes_read":-1}"""))
        assertNull(summarize("stat_file", """{"file":{"kind":"file"}}"""))
        assertNull(summarize("grep_files", """{"count":8}"""))
        assertNull(summarize("unrelated_tool", """{"entries":[]}"""))
    }

    private fun summarize(toolName: String, raw: String): String? =
        AgentFileResultFormatter.summarize(toolName, JSONObject(raw))
}
