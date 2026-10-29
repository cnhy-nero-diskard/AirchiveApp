package cloud.airchive.personal.data.preferences

import android.content.Context
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.airchive.shared.InterfaceDetail
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated adapter checks; never reads or mutates the production preference file. */
@RunWith(AndroidJUnit4::class)
class AndroidInterfaceDetailPreferencesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun clearIsolatedPreferenceFile() {
        isolatedPreferences().edit().clear().commit()
    }

    @After fun clearIsolatedPreferenceFileAfterTest() {
        isolatedPreferences().edit().clear().commit()
    }

    @Test fun absentAndUnrecognizedValuesResolveToSimpleInFreshAdapters() {
        assertEquals(InterfaceDetail.SIMPLE, AndroidInterfaceDetailPreferences(context, TEST_PREFERENCE_FILE).detail.value)

        isolatedPreferences().edit().putString(KEY, "future-detail-mode").commit()
        assertEquals(InterfaceDetail.SIMPLE, AndroidInterfaceDetailPreferences(context, TEST_PREFERENCE_FILE).detail.value)
    }

    @Test fun selectionSurvivesFreshAdapterAndIsWrittenToPrivateDisk() {
        val preferences = AndroidInterfaceDetailPreferences(context, TEST_PREFERENCE_FILE)
        preferences.select(InterfaceDetail.DETAILED)
        assertEquals("detailed", isolatedPreferences().getString(KEY, null))

        val reopened = AndroidInterfaceDetailPreferences(context, TEST_PREFERENCE_FILE)
        assertEquals(InterfaceDetail.DETAILED, reopened.detail.value)

        // apply() writes asynchronously; inspect only this named test file and
        // wait for its nonsecret enum value to reach the private XML store.
        val file = File(context.applicationInfo.dataDir, "shared_prefs/$TEST_PREFERENCE_FILE.xml")
        val deadline = SystemClock.uptimeMillis() + 5_000
        var persisted = false
        while (SystemClock.uptimeMillis() < deadline && !persisted) {
            persisted = file.isFile && file.readText().contains("<string name=\"$KEY\">detailed</string>")
            if (!persisted) SystemClock.sleep(25)
        }
        assertTrue("the isolated installation preference reaches disk", persisted)
        assertEquals(mapOf(KEY to "detailed"), isolatedPreferences().all)
    }

    private fun isolatedPreferences() = context.getSharedPreferences(TEST_PREFERENCE_FILE, Context.MODE_PRIVATE)

    private companion object {
        const val TEST_PREFERENCE_FILE = "airchive_interface_adapter_test"
        const val KEY = "interface_detail"
    }
}
