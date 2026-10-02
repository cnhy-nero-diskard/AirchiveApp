package cloud.airchive.personal.data.auth

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No account selection or Firebase calls: validates the explicit-button request shape. */
@RunWith(AndroidJUnit4::class)
class GoogleSignInRequestTest {
    @Test
    fun continueWithGoogleUsesTheExplicitButtonOption() {
        val request = createGoogleSignInRequest("fixture.apps.googleusercontent.com")
        assertEquals(1, request.credentialOptions.size)
        assertTrue(request.credentialOptions.single() is GetSignInWithGoogleOption)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingWebClientIdFailsBeforeOpeningAChooser() {
        createGoogleSignInRequest(" ")
    }
}
