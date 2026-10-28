package cloud.airchive.shared.data.thinq

import cloud.airchive.shared.contract.thinq.AcCommand
import kotlinx.coroutines.CoroutineScope

/**
 * Graph-lifetime owner for one AC command submission (OpenSpec task 2.5).
 *
 * The host owns only the coroutine lifetime. The one non-queueing,
 * device-global slot remains [AcCommandController]'s existing mutex and is
 * shared across every context generation; this class adds no second gate,
 * command queue, retry or confirmation request. The supplied [scope] belongs
 * to the production graph/fixture, never to a disposable route composition.
 *
 * A tap calls [AcCommandController.dispatch], which synchronously attempts to
 * acquire its existing slot before this method returns. The resulting job then
 * survives route navigation/interface-detail recomposition. The port's bounded
 * confirmation remains inside its single `setState` operation; the existing
 * outcome is applied to the live controller with the exact tap-time
 * [ThinQContextKey] afterward.
 */
class PendingCommandHost(
    private val scope: CoroutineScope,
    private val commands: AcCommandController,
    private val live: ThinQLiveStateController,
) {

    /** Dispatches one owner-tapped command without queuing. */
    fun dispatch(command: AcCommand, capturedContext: ThinQContextKey?): AcCommandDispatchResult =
        commands.dispatch(command, capturedContext, scope) {
                when (val state = commands.state.value) {
                    is AcCommandUiState.Accepted -> live.acceptCommandOutcome(state.outcome, capturedContext)
                    is AcCommandUiState.Confirmed -> live.acceptCommandOutcome(state.outcome, capturedContext)
                    is AcCommandUiState.Unconfirmed -> live.acceptCommandOutcome(state.outcome, capturedContext)

                    is AcCommandUiState.Failed,
                    AcCommandUiState.Idle,
                    is AcCommandUiState.Sending,
                    -> Unit
                }
            }
}
