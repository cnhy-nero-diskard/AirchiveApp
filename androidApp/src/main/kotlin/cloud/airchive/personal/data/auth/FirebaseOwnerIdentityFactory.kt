package cloud.airchive.personal.data.auth

import android.content.Context
import cloud.airchive.personal.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth

/**
 * Factory that wires the production owner sign-in stack. It initializes a
 * NAMED [FirebaseApp] (never the default app, so it cannot collide with a
 * google-services.json app) **only** when every public Firebase client option
 * is configured. If any option is blank the factory returns
 * [OwnerAuthConfiguration.MissingOptions] naming the exact config keys instead
 * of crashing, so the caller can render an actionable setup state.
 *
 * The client options (API key, application id, project id, Google Web OAuth
 * client id) are public identifiers read from the non-secret
 * `config/airchive-mobile.properties` via BuildConfig; no `google-services.json`
 * or secret is required to construct the app.
 */
object FirebaseOwnerIdentityFactory {

    /** Distinct app name so owner sign-in never collides with another FirebaseApp. */
    const val APP_NAME: String = "airchive-owner-signin"

    /** Builds the adapter stack from the fixed, non-secret BuildConfig values. */
    fun fromBuildConfig(context: Context): OwnerAuthConfiguration =
        fromOptions(context, OwnerClientOptions.fromBuildConfig())

    fun fromOptions(context: Context, options: OwnerClientOptions): OwnerAuthConfiguration {
        val missing = options.missingKeys()
        if (missing.isNotEmpty()) {
            return OwnerAuthConfiguration.MissingOptions(missing)
        }

        val app = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME }
            ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey(options.apiKey)
                    .setApplicationId(options.applicationId)
                    .setProjectId(options.projectId)
                    .build(),
                APP_NAME,
            )
        val auth = FirebaseAuth.getInstance(app)
        val googleIdTokenSource = CredentialManagerGoogleIdTokenSource(
            context = context,
            webOAuthClientId = options.googleWebClientId,
        )
        return OwnerAuthConfiguration.Ready(
            identity = FirebaseOwnerIdentity(auth = auth, googleIdTokenSource = googleIdTokenSource),
            firebaseAppName = app.name,
        )
    }
}

/** Result of wiring the owner sign-in stack. */
sealed interface OwnerAuthConfiguration {

    /** True when a usable [Ready] identity stack was created. */
    val isConfigured: Boolean

    /** The complete stack is ready; [identity] is usable. */
    data class Ready(
        val identity: FirebaseOwnerIdentity,
        val firebaseAppName: String,
    ) : OwnerAuthConfiguration {
        override val isConfigured: Boolean get() = true
    }

    /**
     * One or more public Firebase client options are blank. [missingKeys] names
     * the `config/airchive-mobile.properties` keys (for example
     * `firebase.googleWebClientId`) that the operator must fill in; no
     * FirebaseApp is created and nothing crashes.
     */
    data class MissingOptions(
        val missingKeys: Set<String>,
    ) : OwnerAuthConfiguration {
        override val isConfigured: Boolean get() = false

        /** Human-readable summary for an actionable settings/setup state. */
        val actionableMessage: String
            get() = "Firebase owner sign-in is not configured; provide: ${missingKeys.joinToString(", ")}"
    }
}

/**
 * The public, non-secret Firebase client identifiers the factory needs. These
 * come from BuildConfig (which reads `config/airchive-mobile.properties`) and
 * are intentionally blank when unconfigured. Never populated from secrets.
 */
data class OwnerClientOptions(
    val apiKey: String,
    val applicationId: String,
    val projectId: String,
    val googleWebClientId: String,
) {

    /** Config keys that are blank and therefore block a ready state. */
    fun missingKeys(): Set<String> = buildSet {
        if (apiKey.isBlank()) add("firebase.apiKey")
        if (applicationId.isBlank()) add("firebase.applicationId")
        if (projectId.isBlank()) add("firebase.projectId")
        if (googleWebClientId.isBlank()) add("firebase.googleWebClientId")
    }

    companion object {
        fun fromBuildConfig(): OwnerClientOptions = OwnerClientOptions(
            apiKey = BuildConfig.AIRCHIVE_FIREBASE_API_KEY.trim(),
            applicationId = BuildConfig.AIRCHIVE_FIREBASE_APPLICATION_ID.trim(),
            projectId = BuildConfig.AIRCHIVE_FIREBASE_PROJECT_ID.trim(),
            googleWebClientId = BuildConfig.AIRCHIVE_GOOGLE_WEB_CLIENT_ID.trim(),
        )
    }
}