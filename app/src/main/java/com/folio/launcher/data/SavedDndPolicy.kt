package com.folio.launcher.data

import android.app.NotificationManager

/** Plain copy of a [NotificationManager.Policy], so the user's own DND can be written to disk. */
data class SavedDndPolicy(
    val categories: Int,
    val callSenders: Int,
    val messageSenders: Int,
    val suppressedVisualEffects: Int,
    val conversationSenders: Int,
) {
    fun encode(): String =
        listOf(categories, callSenders, messageSenders, suppressedVisualEffects, conversationSenders)
            .joinToString(",")

    fun toPolicy(): NotificationManager.Policy = NotificationManager.Policy(
        categories,
        callSenders,
        messageSenders,
        suppressedVisualEffects,
        conversationSenders,
    )

    companion object {
        const val FOLIO_SILENT_CATEGORIES: Int =
            NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
                NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA

        fun from(policy: NotificationManager.Policy) = SavedDndPolicy(
            categories = policy.priorityCategories,
            callSenders = policy.priorityCallSenders,
            messageSenders = policy.priorityMessageSenders,
            suppressedVisualEffects = policy.suppressedVisualEffects,
            conversationSenders = policy.priorityConversationSenders,
        )

        fun decode(raw: String?): SavedDndPolicy? {
            val parts = raw?.split(',')?.map { it.trim().toIntOrNull() } ?: return null
            if (parts.size != 5 || parts.any { it == null }) return null
            return SavedDndPolicy(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!, parts[4]!!)
        }

        /** True when the live policy is still the one Folio's Silent wrote. */
        fun isFolioSilent(priorityCategories: Int): Boolean =
            priorityCategories == FOLIO_SILENT_CATEGORIES
    }
}
