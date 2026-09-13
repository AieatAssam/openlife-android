package org.openlife.vault.model

/**
 * Display transform applied when rendering a Source's original bytes.
 * Orientation is metadata about how to display the image, never a rewrite
 * of the stored bytes themselves (design §4: "Record display orientation
 * separately").
 */
enum class Orientation {
    NORMAL,
    ROTATE_90,
    ROTATE_180,
    ROTATE_270,
    FLIP_HORIZONTAL,
    FLIP_VERTICAL,
    TRANSPOSE,
    TRANSVERSE,
}
