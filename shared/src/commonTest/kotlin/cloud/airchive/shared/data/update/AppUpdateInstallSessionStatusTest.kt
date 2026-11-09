package cloud.airchive.shared.data.update

import kotlin.test.Test
import kotlin.test.assertEquals

class AppUpdateInstallSessionStatusTest {
    @Test
    fun everyPackageInstallerStatusMapsToItsUpdateOutcome() {
        val expected = mapOf(
            AppUpdateInstallStatus.PENDING_USER_ACTION to AppUpdateInstallOutcome.AwaitingUserAction,
            AppUpdateInstallStatus.SUCCESS to AppUpdateInstallOutcome.Succeeded,
            AppUpdateInstallStatus.FAILURE_ABORTED to AppUpdateInstallOutcome.Cancelled,
            AppUpdateInstallStatus.FAILURE_CONFLICT to AppUpdateInstallOutcome.Failed(UpdateFailureReason.INSTALL_CONFLICT),
            AppUpdateInstallStatus.FAILURE_INCOMPATIBLE to
                AppUpdateInstallOutcome.Failed(UpdateFailureReason.INSTALL_INCOMPATIBLE),
            AppUpdateInstallStatus.FAILURE_STORAGE to AppUpdateInstallOutcome.Failed(UpdateFailureReason.INSTALL_STORAGE),
            AppUpdateInstallStatus.FAILURE_BLOCKED to AppUpdateInstallOutcome.Failed(UpdateFailureReason.INSTALL_BLOCKED),
            AppUpdateInstallStatus.FAILURE_INVALID to AppUpdateInstallOutcome.Failed(UpdateFailureReason.INSTALL_ERROR),
            AppUpdateInstallStatus.FAILURE_TIMEOUT to AppUpdateInstallOutcome.Failed(UpdateFailureReason.INSTALL_ERROR),
            AppUpdateInstallStatus.FAILURE to AppUpdateInstallOutcome.Failed(UpdateFailureReason.INSTALL_ERROR),
        )

        assertEquals(AppUpdateInstallStatus.entries.toSet(), expected.keys)
        expected.forEach { (status, outcome) -> assertEquals(outcome, mapAppUpdateInstallStatus(status)) }
    }

    @Test
    fun userActionIsRequiredForApi31AndNewer() {
        assertEquals(false, appUpdateInstallSessionPolicy(apiLevel = 26).requireUserAction)
        assertEquals(false, appUpdateInstallSessionPolicy(apiLevel = 30).requireUserAction)
        assertEquals(true, appUpdateInstallSessionPolicy(apiLevel = 31).requireUserAction)
        assertEquals(true, appUpdateInstallSessionPolicy(apiLevel = 37).requireUserAction)
    }
}
