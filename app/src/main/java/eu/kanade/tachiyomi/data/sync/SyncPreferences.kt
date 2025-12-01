package eu.kanade.tachiyomi.data.sync

import tachiyomi.core.common.preference.PreferenceStore

class SyncPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun syncUrl() = preferenceStore.getString("mihon_sync_url", "")
    fun syncSecret() = preferenceStore.getString("mihon_sync_secret", "")

    fun syncInterval() = preferenceStore.getInt("mihon_sync_interval", 0)
}
