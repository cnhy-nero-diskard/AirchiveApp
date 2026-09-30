package cloud.airchive.personal.data.credentials

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cloud.airchive.shared.contract.history.HistoryPartition
import cloud.airchive.shared.contract.telemetry.EnergyUnit
import cloud.airchive.shared.contract.telemetry.Observation
import cloud.airchive.shared.data.local.RoomLocalHistoryPort
import cloud.airchive.shared.data.local.openAirchiveDatabase
import cloud.airchive.shared.contract.credentials.SaveThinQResult
import cloud.airchive.shared.contract.credentials.ThinQCredentialSaveFailure
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for [AndroidEncryptedThinQCredentialStore]
 * (OpenSpec task 3.3).
 *
 * Every fixture token is clearly synthetic — it carries the `.example.invalid`
 * marker and is not any real LG PAT. The tests verify:
 *
 *  - save + reopen returns the same token;
 *  - the observable state never contains the plaintext token;
 *  - the on-disk `noBackupFilesDir` record contains no plaintext token;
 *  - the per-install client id is a UUID stable across store instances and
 *    after `clear()`;
 *  - `clear()` removes the token but not the client id;
 *  - invalid saves never overwrite the prior record;
 *  - the record lives under `noBackupFilesDir` and manifest backup is disabled;
 *  - losing the AndroidKeyStore key (via the test-only helper) yields `null`
 *    reads and an `unlockFailed` observable state without a crash, and a later
 *    save recovers by generating a fresh key.
 */
@RunWith(AndroidJUnit4::class)
class AndroidEncryptedThinQCredentialStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Wipe any leftover PAT record and Keystore key from prior tests, but
        // deliberately KEEP the persisted per-install client id: its stability
        // across store instances is asserted separately below.
        runBlocking { AndroidEncryptedThinQCredentialStore(context).clear() }
        ThinQCredentialKeyTestHelper.deleteKeyForTesting()
    }

    @After
    fun tearDown() {
        runBlocking { AndroidEncryptedThinQCredentialStore(context).clear() }
        ThinQCredentialKeyTestHelper.deleteKeyForTesting()
    }

    // --- save + reopen -------------------------------------------------------

    @Test
    fun saveThenReopen_returnsSameToken() = runBlocking {
        val store = AndroidEncryptedThinQCredentialStore(context)
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH))
        assertEquals(TOKEN_A, store.readToken())

        // A brand-new store over the same install (same noBackupFilesDir and
        // Keystore) must decrypt the persisted record.
        val reopened = AndroidEncryptedThinQCredentialStore(context)
        assertEquals(TOKEN_A, reopened.readToken())
    }

    @Test
    fun saveReplacingToken_olderTokenNoLongerReadable() = runBlocking {
        val store = AndroidEncryptedThinQCredentialStore(context)
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH))
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_B, COUNTRY_PH))

        assertEquals("only the newest token must decrypt", TOKEN_B, store.readToken())
        val state = store.observe().first()
        val credential = state.credential!!
        assertEquals(maskOf(TOKEN_B), credential.maskedToken)
        assertEquals(COUNTRY_PH, credential.accountCountry)
    }

    // --- observable state is masked only -------------------------------------

    @Test
    fun observe_neverExposesPlaintextToken() = runBlocking {
        val store = AndroidEncryptedThinQCredentialStore(context)
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_B, COUNTRY_PH))

        val state = store.observe().first()
        assertTrue("present after a successful save", state.present)
        assertFalse(state.unlockFailed)
        val credential = state.credential!!
        val masked = credential.maskedToken
        assertFalse("the observable mask must never be the plaintext token", masked.contains(TOKEN_B))
        assertTrue("the mask keeps only the trailing four chars behind bullets", masked.endsWith(TOKEN_B.takeLast(4)))
        assertTrue("the mask keeps only the trailing four chars behind bullets", masked.startsWith(MASK))
        assertEquals(COUNTRY_PH, credential.accountCountry)
    }

    // --- on-disk record is encrypted -----------------------------------------

    @Test
    fun onDiskRecord_containsNoPlaintext() = runBlocking {
        val store = AndroidEncryptedThinQCredentialStore(context)
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH))

        val recordFile = credentialFile()
        assertTrue("credential file must exist under noBackupFilesDir", recordFile.isFile)
        val onDisk = recordFile.readBytes().toString(Charsets.UTF_8)
        assertFalse("plaintext PAT must never be written on-disk", onDisk.contains(TOKEN_A))
        assertFalse("not even the token prefix may leak into the record", onDisk.contains(TOKEN_A.dropLast(4)))
        // Masked metadata (allowed) is persisted alongside IV + ciphertext.
        assertTrue("masked metadata is persisted", onDisk.contains(maskOf(TOKEN_A)))
    }

    @Test
    fun plaintextPatNeverAppearsInRoomHistoryFile(): Unit = runBlocking {
        val credentialStore = AndroidEncryptedThinQCredentialStore(context)
        assertEquals(SaveThinQResult.Success, credentialStore.save(TOKEN_B, COUNTRY_PH))

        val partition = HistoryPartition(
            firebaseProjectId = "demo-firebase-project",
            ownerUid = "owner-uid-credential-test",
            deviceId = "demo-credential-device",
        )
        val databaseFile = File(context.noBackupFilesDir, "credential-history-test.db")
        val database = openAirchiveDatabase(databaseFile.absolutePath)
        try {
            RoomLocalHistoryPort(database).upsertObservations(
                partition,
                listOf(
                    Observation(
                        sampleId = "credential-isolation-sample",
                        deviceId = partition.deviceId,
                        intervalStartEpochMillis = 1_000L,
                        intervalEndEpochMillis = 301_000L,
                        energyValue = 2.25,
                        recordedUnit = EnergyUnit.WH,
                    ),
                ),
            )
        } finally {
            // Room 3 runtime is an implementation detail of :shared and is not
            // part of the app module's compile API; close via reflection so the
            // test doesn't leak a direct dependency on Room's superclass.
            database.javaClass.getMethod("close").invoke(database)
        }

        val roomContents = databaseFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("ThinQ plaintext must never be stored in Room history", roomContents.contains(TOKEN_B))
        databaseFile.delete()
        File(databaseFile.parentFile, "${databaseFile.name}-wal").delete()
        File(databaseFile.parentFile, "${databaseFile.name}-shm").delete()
    }

    // --- stable per-install client id ----------------------------------------

    @Test
    fun clientId_stableAcrossStoresAndSurvivesClear() = runBlocking {
        val first = AndroidEncryptedThinQCredentialStore(context)
        val id1 = first.clientId
        val parsed = runCatching { UUID.fromString(id1) }.getOrNull()
        assertNotNull("client id must be a well-formed UUID, was '$id1'", parsed)
        assertEquals("canonical UUID string is 36 chars", 36, id1.length)

        first.save(TOKEN_A, COUNTRY_PH)
        val second = AndroidEncryptedThinQCredentialStore(context)
        assertEquals("client id must persist across store instances", id1, second.clientId)

        second.clear()
        val third = AndroidEncryptedThinQCredentialStore(context)
        assertEquals("client id must survive a token clear", id1, third.clientId)
    }

    // --- clear ---------------------------------------------------------------

    @Test
    fun clear_removesTokenButKeepsClientId() = runBlocking {
        val store = AndroidEncryptedThinQCredentialStore(context)
        val id = store.clientId
        store.save(TOKEN_A, COUNTRY_PH)
        assertNotNull("precondition: a saved token exists", store.readToken())

        store.clear()

        assertNull("token must be gone after clear", store.readToken())
        val state = store.observe().first()
        assertFalse(state.present)
        assertNull(state.credential)
        assertFalse(state.unlockFailed)
        assertEquals("client id must remain after clear", id, state.clientId)
        assertEquals(id, store.clientId)
        assertFalse("credential file must be deleted by clear", credentialFile().exists())
    }

    // --- validation keeps the prior record -----------------------------------

    @Test
    fun invalidSave_doesNotOverwritePriorRecord() = runBlocking {
        val store = AndroidEncryptedThinQCredentialStore(context)
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH))

        val blankToken = store.save("   \t ", COUNTRY_PH)
        assertEquals(
            SaveThinQResult.Failure(ThinQCredentialSaveFailure.INVALID_INPUT),
            blankToken,
        )
        val threeLetterCountry = store.save(TOKEN_B, "PHX")
        assertEquals(
            SaveThinQResult.Failure(ThinQCredentialSaveFailure.INVALID_INPUT),
            threeLetterCountry,
        )
        val numericCountry = store.save(TOKEN_B, "P2")
        assertEquals(
            SaveThinQResult.Failure(ThinQCredentialSaveFailure.INVALID_INPUT),
            numericCountry,
        )

        assertEquals("failed saves must leave the prior record intact", TOKEN_A, store.readToken())
    }

    // --- backup and storage location -----------------------------------------

    @Test
    fun recordLivesUnderNoBackupDirAndBackupDisabled() = runBlocking {
        val store = AndroidEncryptedThinQCredentialStore(context)
        store.save(TOKEN_A, COUNTRY_PH)

        val noBackup = context.noBackupFilesDir
        val recordFile = credentialFile()
        assertTrue(
            "record must live under noBackupFilesDir",
            recordFile.canonicalPath.startsWith(noBackup.canonicalPath),
        )
        assertTrue(recordFile.isFile)
        assertTrue(File(noBackup, "thinq/thinq_client_id").isFile)

        val backupEnabled = context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0
        assertFalse("android:allowBackup must be false in the manifest", backupEnabled)
    }

    // --- key loss and recovery -----------------------------------------------

    @Test
    fun keyLoss_readsNullSurfacesUnlockFailed_thenSaveRecovers() = runBlocking {
        val store = AndroidEncryptedThinQCredentialStore(context)
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH))
        assertEquals(TOKEN_A, store.readToken())

        // Simulate losing the platform-protected key by destroying the alias.
        assertTrue("precondition: store key must exist", ThinQCredentialKeyTestHelper.hasKey())
        ThinQCredentialKeyTestHelper.deleteKeyForTesting()
        assertFalse("precondition: store key must be gone", ThinQCredentialKeyTestHelper.hasKey())

        // The persisted record can no longer be decrypted.
        assertNull("readToken must not crash or leak ciphertext after key loss", store.readToken())
        val afterLoss = store.observe().first()
        assertTrue("observe must reflect the failed unlock", afterLoss.unlockFailed)
        assertFalse("no usable credential while the key is lost", afterLoss.present)

        // A fresh save recovers by generating a new key and re-encrypting.
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_B, COUNTRY_PH))
        assertEquals(TOKEN_B, store.readToken())
        val recovered = store.observe().first()
        assertFalse(recovered.unlockFailed)
        assertTrue(recovered.present)
        assertEquals("client id must be untouched by the key-loss cycle", store.clientId, recovered.clientId)
    }

    @Test
    fun reopenAfterKeyLoss_surfacesUnlockFailedWithoutCrash() = runBlocking {
        val store = AndroidEncryptedThinQCredentialStore(context)
        store.save(TOKEN_A, COUNTRY_PH)
        ThinQCredentialKeyTestHelper.deleteKeyForTesting()

        val reopened = AndroidEncryptedThinQCredentialStore(context)
        val state = reopened.observe().first()
        assertTrue("a reopened store must surface unlockFailed", state.unlockFailed)
        assertFalse(state.present)
        assertNull(reopened.readToken())
    }

    // --- helpers ---------------------------------------------------------------

    private fun credentialFile(): File =
        File(context.noBackupFilesDir, "thinq/thinq_credential.enc")

    private fun maskOf(token: String): String = "$MASK ${token.takeLast(4)}"

    companion object {
        const val COUNTRY_PH = "PH"
        const val MASK = "\u2022\u2022\u2022\u2022"

        // Clearly synthetic PAT fixtures; never a real token. `.example.invalid`
        // marks them as test data and keeps them out of any production-looking shape.
        const val TOKEN_A = "airchive-test-pat-a1b2c3d4e5.example.invalid"
        const val TOKEN_B = "airchive-test-pat-9f8e7d6c5b.example.invalid"
    }
}
