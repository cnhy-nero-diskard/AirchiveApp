package cloud.airchive.shared.data.update

import io.ktor.client.engine.cio.CIO

/** Creates the updater's isolated Android CIO engine; caller owns and closes the client. */
fun createAndroidAppUpdateHttpClient(versionName: String): AppUpdateHttpClient =
    AppUpdateHttpClient(CIO.create(), "Airchive/$versionName")
