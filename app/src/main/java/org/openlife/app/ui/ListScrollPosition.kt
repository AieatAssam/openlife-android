package org.openlife.app.ui

/**
 * Review N-02: the list's scroll position as two ints, kept by the list
 * ViewModel across entries and recreation. No Compose state, no layout
 * result and nothing that reaches a bitmap or an activity.
 */
class ListScrollPosition {
    var index: Int = 0
        private set
    var offset: Int = 0
        private set

    fun save(index: Int, offset: Int) {
        this.index = index.coerceAtLeast(0)
        this.offset = offset.coerceAtLeast(0)
    }
}
