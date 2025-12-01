package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.sync.SyncPreferences
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import android.webkit.URLUtil
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.util.system.toast



object SettingsSyncScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.label_backup

    @Composable
    override fun getPreferences(): List<Preference> {
        val syncPreferences = remember { Injekt.get<SyncPreferences>() }
        val context = LocalContext.current

        return persistentListOf(
            Preference.PreferenceGroup(
                title = "Configuración del Servidor",
                preferenceItems = persistentListOf(

                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncPreferences.syncUrl(),
                        title = "URL del Servidor",
                        subtitle = "Debe empezar con https://",
                        onValueChanged = { newValue ->
                            val url = newValue as String
                            if (URLUtil.isNetworkUrl(url) && url.startsWith("https://")) {
                                true
                            } else {
                                context.toast("URL inválida. Debe ser https://...")
                                false
                            }
                        }
                    ),

                    Preference.PreferenceItem.EditTextPreference(
                        preference = syncPreferences.syncSecret(),
                        title = "Secret Token",
                        subtitle = "Tu contraseña definida en Cloud Functions",
                        onValueChanged = { true }
                    ),
                ),
            ),
        )
    }
}
