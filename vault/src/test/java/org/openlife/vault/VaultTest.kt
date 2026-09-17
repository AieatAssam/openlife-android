package org.openlife.vault

import org.junit.Assert.assertEquals
import org.junit.Test
import org.openlife.vault.storage.OpenLifeDatabase
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class VaultTest {
    @Test
    fun schemaVersionMatchesRoomDatabaseVersion() {
        assertEquals(roomDatabaseVersion(), Vault.SCHEMA_VERSION)
    }

    /** Room's @Database has CLASS/BINARY retention, so inspect its class-file annotation metadata. */
    private fun roomDatabaseVersion(): Int {
        val resource = "/${OpenLifeDatabase::class.java.name.replace('.', '/')}.class"
        val bytes = OpenLifeDatabase::class.java.getResourceAsStream(resource)?.readBytes()
            ?: error("compiled OpenLifeDatabase class is missing")
        return ClassFile(bytes).roomDatabaseVersion()
    }

    private class ClassFile(bytes: ByteArray) {
        private val input = DataInputStream(ByteArrayInputStream(bytes))
        private val utf8: Array<String?>
        private val integers: Array<Int?>

        init {
            require(input.readInt() == 0xCAFEBABE.toInt()) { "invalid class file" }
            input.readUnsignedShort()
            input.readUnsignedShort()
            val count = input.readUnsignedShort()
            utf8 = arrayOfNulls(count)
            integers = arrayOfNulls(count)
            var index = 1
            while (index < count) {
                when (input.readUnsignedByte()) {
                    1 -> {
                        val length = input.readUnsignedShort()
                        utf8[index] = String(input.readNBytes(length), Charsets.UTF_8)
                    }

                    3 -> integers[index] = input.readInt()

                    4, 9, 10, 11, 12, 17, 18 -> input.skipBytes(4)

                    5, 6 -> {
                        input.skipBytes(8)
                        index++
                    }

                    7, 8, 16, 19, 20 -> input.skipBytes(2)

                    15 -> input.skipBytes(3)

                    else -> error("unsupported constant-pool tag")
                }
                index++
            }
        }

        fun roomDatabaseVersion(): Int {
            input.skipBytes(6)
            repeat(input.readUnsignedShort()) { input.skipBytes(2) }
            skipMembers()
            skipMembers()
            repeat(input.readUnsignedShort()) {
                val name = utf8[input.readUnsignedShort()]
                val length = input.readInt()
                val attribute = input.readNBytes(length)
                if (name == "RuntimeInvisibleAnnotations") {
                    parseAnnotations(attribute)?.let { return it }
                }
            }
            error("Room @Database annotation is missing")
        }

        private fun skipMembers() {
            repeat(input.readUnsignedShort()) {
                input.skipBytes(6)
                repeat(input.readUnsignedShort()) {
                    input.skipBytes(2)
                    input.skipBytes(input.readInt())
                }
            }
        }

        private fun parseAnnotations(bytes: ByteArray): Int? {
            val annotations = DataInputStream(ByteArrayInputStream(bytes))
            repeat(annotations.readUnsignedShort()) {
                val descriptor = utf8[annotations.readUnsignedShort()]
                val pairCount = annotations.readUnsignedShort()
                var version: Int? = null
                repeat(pairCount) {
                    val name = utf8[annotations.readUnsignedShort()]
                    val value = readElementValue(annotations)
                    if (descriptor == "Landroidx/room/Database;" && name == "version") version = value
                }
                if (version != null) return version
            }
            return null
        }

        private fun readElementValue(data: DataInputStream): Int? = when (data.readUnsignedByte().toChar()) {
            'I' -> integers[data.readUnsignedShort()]

            'B', 'C', 'D', 'F', 'J', 'S', 'Z', 's', 'c' -> {
                data.skipBytes(2)
                null
            }

            'e' -> {
                data.skipBytes(4)
                null
            }

            '@' -> {
                skipAnnotation(data)
                null
            }

            '[' -> {
                repeat(data.readUnsignedShort()) { readElementValue(data) }
                null
            }

            else -> error("unsupported annotation element")
        }

        private fun skipAnnotation(data: DataInputStream) {
            data.skipBytes(2)
            repeat(data.readUnsignedShort()) {
                data.skipBytes(2)
                readElementValue(data)
            }
        }
    }
}
