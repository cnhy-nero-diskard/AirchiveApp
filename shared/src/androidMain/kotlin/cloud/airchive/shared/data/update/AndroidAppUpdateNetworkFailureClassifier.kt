package cloud.airchive.shared.data.update

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/** Maps Android/JVM socket failures that do not share the common IOException type. */
fun classifyAndroidAppUpdateNetworkFailure(error: Throwable): UpdateUnavailableCause? {
    val causes = generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).toList()

    if (causes.any { cause ->
            cause is SocketTimeoutException || cause.javaClass.simpleName.contains("timeout", ignoreCase = true)
        }
    ) {
        return UpdateUnavailableCause.TIMEOUT
    }

    if (causes.any { cause ->
            cause is UnknownHostException ||
                cause is ConnectException ||
                cause is NoRouteToHostException ||
                cause is SocketException ||
                cause is UnresolvedAddressException
        }
    ) {
        return UpdateUnavailableCause.OFFLINE
    }

    return null
}

private const val MAX_CAUSE_DEPTH = 8
