package org.openlife.vault.model

/** How an explicit import began (design §8). */
enum class IntakeKind {
    SHARE,
    PHOTO_PICKER,

    /** The stored value is not one this build knows (P1-18-R14); never written. */
    UNRECOGNISED,
}
