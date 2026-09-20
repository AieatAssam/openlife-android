package org.openlife.vault.repository

import android.os.StatFs
import java.io.File

/** Filesystem space boundary used by import preflight and its tests. */
fun interface StorageSpace {
    fun availableBytes(dir: File): Long

    object Default : StorageSpace {
        override fun availableBytes(dir: File): Long = StatFs(dir.path).availableBytes
    }
}
