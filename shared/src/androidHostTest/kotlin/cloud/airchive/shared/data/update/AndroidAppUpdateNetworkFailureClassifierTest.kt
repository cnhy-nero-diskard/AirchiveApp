package cloud.airchive.shared.data.update

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AndroidAppUpdateNetworkFailureClassifierTest {

    @Test
    fun mapsJvmDnsAndConnectionFailuresToOffline() {
        assertEquals(
            UpdateUnavailableCause.OFFLINE,
            classifyAndroidAppUpdateNetworkFailure(UnknownHostException("unavailable host")),
        )
        assertEquals(
            UpdateUnavailableCause.OFFLINE,
            classifyAndroidAppUpdateNetworkFailure(UnresolvedAddressException()),
        )
        assertEquals(
            UpdateUnavailableCause.OFFLINE,
            classifyAndroidAppUpdateNetworkFailure(IllegalStateException("wrapped", ConnectException("no route"))),
        )
    }

    @Test
    fun mapsSocketAndEngineTimeoutsToTimeout() {
        assertEquals(
            UpdateUnavailableCause.TIMEOUT,
            classifyAndroidAppUpdateNetworkFailure(SocketTimeoutException("read timed out")),
        )
        assertEquals(
            UpdateUnavailableCause.TIMEOUT,
            classifyAndroidAppUpdateNetworkFailure(IllegalStateException("wrapped", EngineConnectTimeoutException())),
        )
    }

    @Test
    fun leavesFeedAndParsingFailuresForGenericFeedError() {
        assertNull(classifyAndroidAppUpdateNetworkFailure(AppUpdateHttpException.HttpStatus(404)))
        assertNull(classifyAndroidAppUpdateNetworkFailure(IllegalArgumentException("malformed manifest")))
    }

    private class EngineConnectTimeoutException : Exception("connection timeout")
}
