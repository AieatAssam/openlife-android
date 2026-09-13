package org.openlife.app

import android.app.Application

/**
 * Composition root. No dependency-injection framework is used for C0
 * (openlife-design-v0.2.md §7) — dependencies are constructed explicitly here
 * and passed down. This currently constructs nothing because the vault
 * repository does not exist yet (Stage 1+); it exists as the single place
 * that wiring will be added.
 */
class OpenLifeApp : Application()
