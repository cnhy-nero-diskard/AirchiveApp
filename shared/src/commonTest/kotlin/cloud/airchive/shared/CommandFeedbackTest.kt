package cloud.airchive.shared

import cloud.airchive.shared.contract.thinq.AcCommand
import cloud.airchive.shared.contract.thinq.CommandFailure
import cloud.airchive.shared.contract.thinq.CommandOutcome
import cloud.airchive.shared.contract.thinq.CommandStatus
import cloud.airchive.shared.data.thinq.AcCommandUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandFeedbackTest {

    @Test
    fun unconfirmedCopyIsFailureIndependentAndOffersOnlyAReadAction() {
        val baseOutcome = CommandOutcome(
            commandId = "feedback-test",
            deviceId = "ac-1",
            command = AcCommand.SetPower(requestedAtEpochMillis = 10L, powerOn = true),
            status = CommandStatus.UNCONFIRMED,
            submittedAtEpochMillis = 10L,
        )

        for (simulated in listOf(false, true)) {
            val expected = AcCommandUiState.Unconfirmed(baseOutcome).feedbackText(simulated)
            assertTrue(expected.orEmpty().contains("may have been applied"))
            assertTrue(
                expected.orEmpty().contains(
                    if (simulated) "Refresh simulated state" else "Read current device state",
                ),
            )
            assertNoRetryWording(expected)

            CommandFailure.values().forEach { failure ->
                val actual = AcCommandUiState.Unconfirmed(
                    baseOutcome.copy(failure = failure),
                ).feedbackText(simulated)
                assertEquals(expected, actual, "failure=$failure simulated=$simulated")
                assertNoRetryWording(actual)
            }
        }
    }

    private fun assertNoRetryWording(text: String?) {
        val normalized = text.orEmpty().lowercase()
        listOf("retry", "resend", "send again", "try again").forEach { phrase ->
            assertFalse(phrase in normalized, "unexpected '$phrase' in: $normalized")
        }
    }
}
