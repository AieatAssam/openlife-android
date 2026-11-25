package org.openlife.vault.storage

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
 * Foreign keys are on, as Room turns them on; recursive triggers are on, as
 * the pinned SQLCipher build compiles them on (SQLITE_DEFAULT_RECURSIVE_TRIGGERS).
 */
class HostSqlite : Closeable {
    class Failure(message: String) : RuntimeException(message)

    private val script: File = File.createTempFile("host-sqlite", ".py").apply {
        deleteOnExit()
        writeText(PROGRAM)
    }
    private val process: Process = ProcessBuilder("python3", "-I", script.absolutePath)
        .redirectErrorStream(false)
        .start()
    private val input: Writer = process.outputStream.bufferedWriter()
    private val output: BufferedReader = process.inputStream.bufferedReader()

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
        if (status.startsWith("ERR ")) throw Failure(String(unhex(status.removePrefix("ERR ")), Charsets.UTF_8))
        return rows
    }

    private fun nextLine(): String = output.readLine()
        ?: throw Failure("python3 exited: ${process.errorStream.bufferedReader().readText()}")

    fun long(sql: String, vararg args: Any?): Long = query(sql, *args).single().single() as Long

    /** A SupportSQLiteDatabase whose execSQL runs here, so a Room Migration runs unchanged. */
    fun asSupportDatabase(): SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        require(method.name == "execSQL") { "unexpected call ${method.name}" }
        @Suppress("UNCHECKED_CAST")
        val bind = (args.getOrNull(1) as? Array<Any?>) ?: emptyArray()
        exec(args[0] as String, *bind)
        null
    } as SupportSQLiteDatabase

    override fun close() {
        runCatching { input.close() }
        process.waitFor()
        script.delete()
    }

    companion object {
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
            con = sqlite3.connect(':memory:', isolation_level=None)
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
