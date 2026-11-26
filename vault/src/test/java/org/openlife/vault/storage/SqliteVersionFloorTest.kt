package org.openlife.vault.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P3-02 CI failure: the production SQL must run on the SQLite that ships in
 * the pinned SQLCipher, and everything newer than the CI runner's host
 * SQLite must sit behind a capability check that fails closed.
 *
 * [BUNDLED_SQLITE] is the SQLite inside sqlcipher-android 4.19.0's
 * `libsqlcipher.so` (3.53.4, read statically from the AAR, not observed on a
 * device). [CI_HOST_SQLITE] is the system SQLite of the ubuntu-22.04 runner
 * (Ubuntu's libsqlite3 3.37.2 package; the runner's value is printed by the
 * skipped search tests). The scan is textual and conservative: it looks for
 * each listed construct by name in the SQL-bearing sources.
 */
class SqliteVersionFloorTest {
    private val storage = File("src/main/java/org/openlife/vault/storage")

    private fun sources(): Map<String, String> = SQL_FILES.associateWith { File(storage, it).readText() }

    @Test
    fun theSqlFilesExist() {
        SQL_FILES.forEach { assertTrue("$it exists", File(storage, it).isFile) }
    }

    @Test
    fun productionSqlNeedsNothingNewerThanTheBundledSqlite() {
        val found = sources().flatMap { (file, text) ->
            CONSTRUCTS.filter { it.pattern.containsMatchIn(text) }.map { file to it }
        }
        val tooNew = found
            .filter { (_, c) -> c.since > BUNDLED_SQLITE }
            .map { (f, c) -> "$f: ${c.name} ${c.since}" }
        assertEquals("constructs newer than SQLite $BUNDLED_SQLITE", emptyList<String>(), tooNew)
    }

    @Test
    fun constructsNewerThanTheCiHostAreOnlyBehindAFailClosedCheck() {
        val found = sources().flatMap { (file, text) ->
            CONSTRUCTS.filter { it.pattern.containsMatchIn(text) }.map { file to it }
        }
        val unguarded = found
            .filter { (_, c) -> c.since > CI_HOST_SQLITE }
            .filterNot { (f, c) -> GUARDED[c.name] == f }
            .map { (f, c) -> "$f: ${c.name} ${c.since}" }
        assertEquals("constructs newer than SQLite $CI_HOST_SQLITE outside a guard", emptyList<String>(), unguarded)
    }

    private data class Construct(val name: String, val since: Version, val pattern: Regex)

    data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

        override fun toString() = "$major.$minor.$patch"
    }

    private companion object {
        val BUNDLED_SQLITE = Version(3, 53, 4)
        val CI_HOST_SQLITE = Version(3, 37, 2)

        val SQL_FILES = listOf(
            "FactSchemaTriggers.kt",
            "FactMigration.kt",
            "SourceRowRemoval.kt",
            "SearchIndex.kt",
            "OcrPointerSql.kt",
            "FactDao.kt",
            "OcrDao.kt",
            "SearchDao.kt",
        )

        /** FTS5 secure-delete is probed on a temp table; without it SearchIndex creates no index (fail closed). */
        val GUARDED = mapOf("FTS5 secure-delete" to "SearchIndex.kt")

        private fun c(name: String, since: Version, pattern: String) =
            Construct(name, since, Regex(pattern, RegexOption.IGNORE_CASE))

        val CONSTRUCTS = listOf(
            c(
                "UPDATE ... FROM",
                Version(3, 33, 0),
                """\bUPDATE\s+\w+\s+SET\s[^"]*?\bFROM\s+\w+\s+\w*\s*(WHERE|JOIN|,)""",
            ),
            c("RETURNING", Version(3, 35, 0), """\bRETURNING\b"""),
            c("ALTER TABLE DROP COLUMN", Version(3, 35, 0), """\bDROP\s+COLUMN\b"""),
            c("MATERIALIZED CTE hint", Version(3, 35, 0), """\bAS\s+(NOT\s+)?MATERIALIZED\b"""),
            c("STRICT table", Version(3, 37, 0), """\)\s*STRICT\b"""),
            c("JSON -> / ->> operator", Version(3, 38, 0), """\w\s*->>?\s*'"""),
            c("unixepoch()", Version(3, 38, 0), """\bunixepoch\s*\("""),
            c("IS [NOT] DISTINCT FROM", Version(3, 39, 0), """\bDISTINCT\s+FROM\b"""),
            c("RIGHT or FULL JOIN", Version(3, 39, 0), """\b(RIGHT|FULL)\s+(OUTER\s+)?JOIN\b"""),
            c("FTS5 secure-delete", Version(3, 42, 0), """secure-delete"""),
            c("FTS5 contentless_delete", Version(3, 43, 0), """contentless_delete"""),
            c("octet_length()", Version(3, 43, 0), """\boctet_length\s*\("""),
            c("concat() / concat_ws()", Version(3, 44, 0), """\bconcat(_ws)?\s*\("""),
            c("string_agg()", Version(3, 44, 0), """\bstring_agg\s*\("""),
            c(
                "ORDER BY inside an aggregate",
                Version(3, 44, 0),
                """\b(group_concat|json_group_array)\s*\([^)]*\bORDER\s+BY""",
            ),
            c("jsonb functions", Version(3, 45, 0), """\bjsonb\w*\s*\("""),
            c("unistr()", Version(3, 50, 0), """\bunistr\s*\("""),
        )
    }
}
