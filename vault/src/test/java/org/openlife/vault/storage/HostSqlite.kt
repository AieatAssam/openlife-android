package org.openlife.vault.storage

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.Writer
import java.lang.reflect.Proxy

/**
 * P2-08 JVM evidence without a device or a new Gradle dependency: a real
 * SQLite engine with FTS5, reached through the host's `python3` standard
 * library (`sqlite3` module) over a line protocol. Every value crosses as
 * hex, so hostile strings (quotes, newlines, FTS5 operators) arrive intact.
 *
 * This is the host's SQLite (3.53.x), not SQLCipher. It proves the SQL —
 * DDL, triggers, cascades, MATCH syntax and the repository's queries — on the
 * same FTS5 code; it is not device evidence (docs/decisions/0010-local-search.md).
 * Foreign keys are on, as Room turns them on. No production code sets
 * `recursive_triggers`; its value on a device is the build default. The
 * pinned `libsqlcipher.so` lists `DEFAULT_RECURSIVE_TRIGGERS` among its
 * compile-option strings (read from the AAR, not observed on a device), so
 * [recursiveTriggers] defaults to on, and the suites whose result could
 * depend on it run in both modes (P3-02 review M-05). Cascades fire the FTS
 * delete triggers with recursive triggers on or off (host check, P2-08
 * review). `PRAGMA secure_delete` is left at its default (off), as in the
 * pinned build. [path] null is an in-memory database; a file path lets a test
 * close and reopen the same database.
 */
class HostSqlite(path: File? = null, recursiveTriggers: Boolean = true) : Closeable {
    /**
     * The SQLite error text, the failing statement's kind (its leading
     * keywords and object name, never values) and the host SQLite version,
     * so a CI log explains a failure without row content.
     */
    class Failure(message: String) : RuntimeException(message)

    private val script: File = File.createTempFile("host-sqlite", ".py").apply {
        deleteOnExit()
        writeText(PROGRAM)
    }
    private val command: List<String> = interpreter(script) + listOfNotNull(path?.absolutePath)
    private val process: Process = ProcessBuilder(command)
        .redirectErrorStream(false)
        .start()
    private val input: Writer = process.outputStream.bufferedWriter()
    private val output: BufferedReader = process.inputStream.bufferedReader()

    init {
        if (!recursiveTriggers) exec("PRAGMA recursive_triggers = OFF")
        if (!versionReported) {
            versionReported = true
            System.err.println("HostSqlite: host SQLite ${version()}")
        }
    }

    fun exec(sql: String, vararg args: Any?) {
        query(sql, *args)
    }

    fun query(sql: String, vararg args: Any?): List<List<Any?>> {
        val line = buildString {
            append(hex(sql.toByteArray()))
            args.forEach { append(' ').append(encode(it)) }
        }
        input.write(line)
        input.write("\n")
        input.flush()
        val status = nextLine()
        val rows = mutableListOf<List<Any?>>()
        while (true) {
            val row = nextLine()
            if (row == "END") break
            rows += row.split(' ').map(::decode)
        }
        if (status.startsWith("ERR ")) {
            val error = String(unhex(status.removePrefix("ERR ")), Charsets.UTF_8)
            throw Failure("$error [statement: ${statementKind(sql)}; host SQLite ${version()}]")
        }
        return rows
    }

    private var knownVersion: String? = null

    /** The engine's `sqlite_version()`, read once per connection. */
    fun version(): String = knownVersion ?: run {
        knownVersion = "unknown" // a failing version query must not recurse through Failure
        runCatching { query("SELECT sqlite_version()").single().single() as String }
            .getOrDefault("unknown")
            .also { knownVersion = it }
    }

    private fun nextLine(): String = output.readLine()
        ?: throw Failure("python3 exited: ${process.errorStream.bufferedReader().readText()}")

    fun long(sql: String, vararg args: Any?): Long = query(sql, *args).single().single() as Long

    /**
     * A SupportSQLiteDatabase whose execSQL, query(String) and transaction
     * calls run here, so a Room Migration or Callback runs unchanged.
     * [statements] records every SQL text it executes, in order.
     */
    fun asSupportDatabase(statements: MutableList<String> = mutableListOf()): SupportSQLiteDatabase {
        var successful = false
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    @Suppress("UNCHECKED_CAST")
                    val bind = (args.getOrNull(1) as? Array<Any?>) ?: emptyArray()
                    statements += args[0] as String
                    exec(args[0] as String, *bind)
                    null
                }

                "query" -> {
                    val sql = requireNotNull(args.singleOrNull() as? String) { "only query(String) is supported" }
                    statements += sql
                    cursorOf(query(sql))
                }

                "beginTransaction" -> {
                    successful = false
                    exec("SAVEPOINT host_tx")
                    null
                }

                "setTransactionSuccessful" -> {
                    successful = true
                    null
                }

                "endTransaction" -> {
                    if (!successful) exec("ROLLBACK TO host_tx")
                    exec("RELEASE host_tx")
                    null
                }

                else -> error("unexpected call ${method.name}")
            }
        } as SupportSQLiteDatabase
    }

    /** A read-only Cursor over rows already fetched; enough for moveToFirst/Next and getInt/Long/String. */
    private fun cursorOf(rows: List<List<Any?>>): Cursor {
        var position = -1
        return Proxy.newProxyInstance(Cursor::class.java.classLoader, arrayOf(Cursor::class.java)) { _, method, args ->
            fun value() = rows[position][args[0] as Int]
            when (method.name) {
                "moveToFirst" -> {
                    position = 0
                    rows.isNotEmpty()
                }

                "moveToNext" -> {
                    position++
                    position < rows.size
                }

                "getCount" -> rows.size

                "getInt" -> (value() as Number).toInt()

                "getLong" -> (value() as Number).toLong()

                "getString" -> value()?.toString()

                "isNull" -> value() == null

                "close" -> null

                else -> error("unexpected cursor call ${method.name}")
            }
        } as Cursor
    }

    override fun close() {
        runCatching { input.close() }
        process.waitFor()
        script.delete()
    }

    companion object {
        /** The engine version is printed once per test JVM (CI log; Gradle shows standard error). */
        @Volatile
        private var versionReported = false

        /** Creates every table and index of an exported Room schema (e.g. 4.json) on this database. */
        fun HostSqlite.createFromExportedSchema(json: File) {
            val text = json.readText()
            CREATE_SQL.findAll(text).forEach { match ->
                val raw = match.groupValues[1].replace("\\\"", "\"")
                val owner = text.lastIndexOf("\"tableName\": \"", match.range.first)
                val table = TABLE_NAME.find(text, owner)!!.groupValues[1]
                exec(raw.replace("\${TABLE_NAME}", table))
            }
        }

        /**
         * The host interpreter: `python3 -I <script>` unless the environment
         * variable `OPENLIFE_HOST_SQLITE` names another command speaking the
         * same line protocol (for example one over an older SQLite, to
         * reproduce a CI runner's engine; P3-02 CI failure).
         */
        private fun interpreter(script: File): List<String> =
            System.getenv("OPENLIFE_HOST_SQLITE")
                ?.split(' ')
                ?.filter(String::isNotBlank)
                ?.takeIf { it.isNotEmpty() }
                ?: listOf("python3", "-I", script.absolutePath)

        private val STATEMENT_KIND = Regex(
            "^\\s*((?:CREATE|DROP|ALTER|INSERT|REPLACE|UPDATE|DELETE|SELECT|PRAGMA|SAVEPOINT|RELEASE|ROLLBACK)" +
                "(?:\\s+(?:OR\\s+\\w+|TEMP|TEMPORARY|UNIQUE|VIRTUAL|TABLE|INDEX|TRIGGER|VIEW|" +
                "INTO|FROM|IF|NOT|EXISTS))*" +
                "(?:\\s+[\\w`\"]+)?)",
            RegexOption.IGNORE_CASE,
        )

        /** Leading keywords plus the object name: schema identifiers only, never literals or bound values. */
        internal fun statementKind(sql: String): String =
            STATEMENT_KIND.find(sql)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ") ?: "other"

        private val CREATE_SQL = Regex("\"createSql\": \"((?:[^\"\\\\]|\\\\.)*)\"")
        private val TABLE_NAME = Regex("\"tableName\": \"([^\"]+)\"")

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        private fun unhex(text: String) =
            ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

        private fun encode(value: Any?): String = when (value) {
            null -> "n"
            is Int, is Long -> "i:$value"
            is Float, is Double -> "r:$value"
            is ByteArray -> "b:${hex(value)}"
            is Boolean -> if (value) "i:1" else "i:0"
            else -> "s:${hex(value.toString().toByteArray())}"
        }

        private fun decode(token: String): Any? {
            val kind = token.substringBefore(':')
            val body = token.substringAfter(':', "")
            return when (kind) {
                "n" -> null
                "i" -> body.toLong()
                "r" -> body.toDouble()
                "b" -> unhex(body)
                "s" -> String(unhex(body), Charsets.UTF_8)
                else -> throw Failure("bad token")
            }
        }

        private val PROGRAM = """
            import sqlite3, sys
            con = sqlite3.connect(sys.argv[1] if len(sys.argv) > 1 else ':memory:', isolation_level=None)
            con.execute('PRAGMA foreign_keys=ON')
            con.execute('PRAGMA recursive_triggers=ON')
            def dec(t):
                k, _, v = t.partition(':')
                if k == 'n': return None
                if k == 'i': return int(v)
                if k == 'r': return float(v)
                if k == 'b': return bytes.fromhex(v)
                return bytes.fromhex(v).decode('utf-8')
            def enc(v):
                if v is None: return 'n'
                if isinstance(v, int): return 'i:%d' % v
                if isinstance(v, float): return 'r:' + repr(v)
                if isinstance(v, bytes): return 'b:' + v.hex()
                return 's:' + str(v).encode('utf-8').hex()
            for line in sys.stdin:
                parts = line.split()
                if not parts: continue
                sql = bytes.fromhex(parts[0]).decode('utf-8')
                try:
                    rows = con.execute(sql, [dec(p) for p in parts[1:]]).fetchall()
                    out = ['OK'] + [' '.join(enc(v) for v in r) for r in rows]
                except Exception as e:
                    out = ['ERR ' + str(e).encode('utf-8').hex()]
                sys.stdout.write('\n'.join(out + ['END']) + '\n')
                sys.stdout.flush()
        """.trimIndent()
    }
}
