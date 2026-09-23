package org.openlife.vault.model

import android.graphics.Matrix

/** Shared display transform and coordinate geometry for all image consumers. */
object OrientationTransform {
    private const val ROTATE_90_DEGREES = 90f
    private const val ROTATE_180_DEGREES = 180f
    private const val ROTATE_270_DEGREES = 270f
    private const val UNIT_SCALE = 1f
    private const val FLIPPED_SCALE = -1f

    fun matrixFor(orientation: Orientation): Matrix? {
        if (orientation == Orientation.NORMAL) return null
        return Matrix().apply {
            when (orientation) {
                Orientation.ROTATE_90 -> setRotate(ROTATE_90_DEGREES)

                Orientation.ROTATE_180 -> setRotate(ROTATE_180_DEGREES)

                Orientation.ROTATE_270 -> setRotate(ROTATE_270_DEGREES)

                Orientation.FLIP_HORIZONTAL -> setScale(FLIPPED_SCALE, UNIT_SCALE)

                Orientation.FLIP_VERTICAL -> setScale(UNIT_SCALE, FLIPPED_SCALE)

                Orientation.TRANSPOSE -> {
                    setRotate(ROTATE_90_DEGREES)
                    postScale(FLIPPED_SCALE, UNIT_SCALE)
                }

                Orientation.TRANSVERSE -> {
                    setRotate(ROTATE_270_DEGREES)
                    postScale(FLIPPED_SCALE, UNIT_SCALE)
                }

                Orientation.NORMAL -> Unit
            }
        }
    }

    fun displayWidth(sourceWidth: Int, sourceHeight: Int, orientation: Orientation): Int =
        if (swapsAxes(orientation)) sourceHeight else sourceWidth

    fun displayHeight(sourceWidth: Int, sourceHeight: Int, orientation: Orientation): Int =
        if (swapsAxes(orientation)) sourceWidth else sourceHeight

    /** Maps a displayed-image boundary point back to the original source. */
    fun sourcePointForDisplay(
        displayX: Int,
        displayY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        orientation: Orientation,
    ): Point = when (orientation) {
        Orientation.NORMAL -> Point(displayX, displayY)
        Orientation.ROTATE_90 -> Point(displayY, sourceHeight - displayX)
        Orientation.ROTATE_180 -> Point(sourceWidth - displayX, sourceHeight - displayY)
        Orientation.ROTATE_270 -> Point(sourceWidth - displayY, displayX)
        Orientation.FLIP_HORIZONTAL -> Point(sourceWidth - displayX, displayY)
        Orientation.FLIP_VERTICAL -> Point(displayX, sourceHeight - displayY)
        Orientation.TRANSPOSE -> Point(displayY, displayX)
        Orientation.TRANSVERSE -> Point(sourceWidth - displayY, sourceHeight - displayX)
    }

    fun swapsAxes(orientation: Orientation): Boolean = when (orientation) {
        Orientation.ROTATE_90,
        Orientation.ROTATE_270,
        Orientation.TRANSPOSE,
        Orientation.TRANSVERSE,
        -> true

        else -> false
    }

    data class Point(val x: Int, val y: Int)
}
