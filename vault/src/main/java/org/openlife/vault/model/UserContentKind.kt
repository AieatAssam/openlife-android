package org.openlife.vault.model

/**
 * The user's own answer to "Is this a message or request from someone?"
 * (P2-07-R3). It is routing context for later capabilities, never proof of
 * safety (design §6). "Not sure" is stored as no kind (null) with the time
 * the user answered.
 */
enum class UserContentKind {
    COMMUNICATION,
    NOT_COMMUNICATION,

    /** A stored value this build does not know (P1-18-R14); never written back. */
    UNRECOGNISED,
}
