package cn.huanong.canteen.data

import android.content.Context

/**
 * Durable offline-first sync journal. Local database writes stay authoritative until
 * their generation has been accepted by the cloud.
 */
class OfflineSyncStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cloud_sync_state", Context.MODE_PRIVATE)
    private val lock = Any()

    private fun key(accountId: String, field: String) = "account.$accountId.$field"
    fun generation(accountId: String): Long = prefs.getLong(key(accountId, "generation"), 0L)
    fun lastRevision(accountId: String): Int = prefs.getInt(key(accountId, "revision"), 0)
    fun hasPending(accountId: String): Boolean =
        generation(accountId) > prefs.getLong(key(accountId, "synced_generation"), 0L)

    fun markPending(accountId: String): Long = synchronized(lock) {
        val next = generation(accountId) + 1L
        prefs.edit().putLong(key(accountId, "generation"), next).apply()
        next
    }

    fun markGuestPending() { prefs.edit().putBoolean("guest_pending", true).apply() }
    fun hasGuestPending(): Boolean = prefs.getBoolean("guest_pending", false)
    fun clearGuestPending() { prefs.edit().remove("guest_pending").apply() }

    fun markSyncedIfUnchanged(accountId: String, expectedGeneration: Long, revision: Int): Boolean =
        synchronized(lock) {
            val editor = prefs.edit().putInt(key(accountId, "revision"), revision)
            val unchanged = generation(accountId) == expectedGeneration
            if (unchanged) editor.putLong(key(accountId, "synced_generation"), expectedGeneration)
            editor.apply()
            unchanged
        }

    fun acceptRemote(accountId: String, revision: Int) = synchronized(lock) {
        val current = generation(accountId)
        prefs.edit()
            .putInt(key(accountId, "revision"), revision)
            .putLong(key(accountId, "synced_generation"), current)
            .apply()
    }

    fun setRevision(accountId: String, revision: Int) {
        prefs.edit().putInt(key(accountId, "revision"), revision).apply()
    }

    fun offlineMode(): Boolean = prefs.getBoolean("offline_mode", false)
    fun setOfflineMode(enabled: Boolean) { prefs.edit().putBoolean("offline_mode", enabled).apply() }
}

