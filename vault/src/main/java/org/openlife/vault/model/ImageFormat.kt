package org.openlife.vault.model

/** The only two supported formats in C0 (design §8). */
enum class ImageFormat(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png"),
}
