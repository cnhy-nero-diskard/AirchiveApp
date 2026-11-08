package cloud.airchive.personal.data.firebase

import cloud.airchive.personal.data.tasks.awaitCancellable
import cloud.airchive.shared.data.onboarding.OwnerDocumentCache
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lazily supplies the process's default Firestore instance and resets its
 * persistent document cache between owners. While clearing is pending, callers
 * cannot obtain an instance; a failed clear keeps the provider blocked so the
 * next owner transition can retry against the terminated instance.
 */
internal class FirebaseOwnerFirestore(
    private val app: FirebaseApp,
) : OwnerDocumentCache {

    @Volatile
    private var instance: FirebaseFirestore? = null

    @Volatile
    private var pendingClear: FirebaseFirestore? = null

    @Volatile
    private var clearing: Boolean = false

    @Synchronized
    fun get(): FirebaseFirestore {
        check(!clearing) { "Firestore is unavailable while the owner cache is being cleared." }
        return instance ?: FirebaseFirestore.getInstance(app).also { instance = it }
    }

    override suspend fun clear() {
        val target = synchronized(this) {
            clearing = true
            (pendingClear ?: instance ?: FirebaseFirestore.getInstance(app).also { instance = it })
                .also { pendingClear = it }
        }

        try {
            target.terminate().awaitCancellable()
            target.clearPersistence().awaitCancellable()
            synchronized(this) {
                instance = null
                pendingClear = null
                clearing = false
            }
        } catch (cancelled: CancellationException) {
            // Keep access closed and retain the terminated instance so cleanup
            // can resume without exposing its old document cache.
            throw cancelled
        }
    }
}
