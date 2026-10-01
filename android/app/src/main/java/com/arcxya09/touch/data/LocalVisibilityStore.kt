package com.arcxya09.touch.data

/** Account-scoped rules live outside disposable message/cache rows. Call writes in the content transaction. */
class LocalVisibilityStore(private val cache: () -> TouchDatabase.Cache) {
    fun hidden(owner: String, messageId: String) = cache().hidden(owner, messageId)
    fun hide(owner: String, messageId: String) = cache().hide(TouchDatabase.VisibilityMark(owner, messageId))
    fun cutoff(owner: String) = cache().localCutoff(owner)?.throughTime ?: 0L
    fun advance(owner: String, floor: Long) {
        cache().cutoff(TouchDatabase.LocalCutoff(owner, maxOf(cutoff(owner), floor)))
    }
}
