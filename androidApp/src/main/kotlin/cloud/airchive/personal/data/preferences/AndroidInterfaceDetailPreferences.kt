package cloud.airchive.personal.data.preferences

import android.content.Context
import cloud.airchive.shared.InterfaceDetail
import cloud.airchive.shared.InterfaceDetailPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Nonsecret, installation-wide display choice. Independent of credentials and account cache. */
class AndroidInterfaceDetailPreferences(
    context: Context,
    name: String = "airchive_interface",
) : InterfaceDetailPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(InterfaceDetail.fromStoredValue(preferences.getString(KEY, null)))
    override val detail = state.asStateFlow()

    override fun select(detail: InterfaceDetail) {
        preferences.edit().putString(KEY, detail.storedValue).apply()
        state.value = detail
    }

    private companion object { const val KEY = "interface_detail" }
}
