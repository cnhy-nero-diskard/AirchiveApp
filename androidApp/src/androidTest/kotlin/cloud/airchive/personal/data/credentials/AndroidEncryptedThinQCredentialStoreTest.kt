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
import cloud.airchive.shared.data.onboarding.OwnerSettingsActionFailure
import cloud.airchive.shared.data.onboarding.OwnerSettingsActionResult
import cloud.airchive.shared.data.onboarding.OwnerSettingsController
import cloud.airchive.shared.contract.credentials.SaveThinQResult
import cloud.airchive.shared.contract.credentials.ThinQCredentialSaveFailure
import cloud.airchive.shared.fakes.FakeLocalHistory
import cloud.airchive.shared.fakes.FakeOwnerIdentity
import cloud.airchive.shared.fakes.FakeThinQCredentialVerifier
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
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
        // Tests use a dedicated no-backup directory and Keystore alias; the
        // production `thinq/` record and key are never opened or cleared.
        testStorageDir.deleteRecursively()
        deleteKeyForTesting()
    }

    @After
    fun tearDown() {
        deleteKeyForTesting()
        testStorageDir.deleteRecursively()
    }

    // --- save + reopen -------------------------------------------------------

    @Test
    fun saveThenReopen_returnsSameToken() = runBlocking {
        val store = store()
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH, OWNER_A))
        assertEquals(TOKEN_A, store.readToken(OWNER_A))

        // A brand-new store over the same install (same noBackupFilesDir and
        // Keystore) must decrypt the persisted record.
        val reopened = store()
        assertEquals(TOKEN_A, reopened.readToken(OWNER_A))
    }

    @Test
    fun saveReplacingToken_olderTokenNoLongerReadable() = runBlocking {
        val store = store()
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH, OWNER_A))
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_B, COUNTRY_PH, OWNER_A))

        assertEquals("only the newest token must decrypt", TOKEN_B, store.readToken(OWNER_A))
        val state = store.observe(OWNER_A).first()
        val credential = state.credential!!
        assertEquals(maskOf(TOKEN_B), credential.maskedToken)
        assertEquals(COUNTRY_PH, credential.accountCountry)
    }

    @Test
    fun ownerBinding_sameOwnerCanRead_otherOwnerGetsAbsentAndRecordIsDeleted() = runBlocking {
        val store = store()
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH, OWNER_A))

        assertEquals(TOKEN_A, store.readToken(OWNER_A))
        assertNull(store.readToken(OWNER_B))
        assertFalse("a mismatched owner read deletes the old credential record", credentialFile().exists())
        assertFalse(store.observe(OWNER_B).first().present)
    }

    @Test
    fun legacyRecord_migratesOnlyAfterOwnerIsAuthorized() = runBlocking {
        writeLegacyRecord(TOKEN_A)
        val store = store()

        assertFalse("legacy credential is not visible before binding", store.observe(OWNER_A).first().present)
        assertTrue(store.migrateLegacyCredential(OWNER_A))
        assertEquals(TOKEN_A, store.readToken(OWNER_A))
        assertEquals(
            "legacy record is atomically rewritten in the owner-bound format",
            2,
            credentialFile().inputStream().use { java.io.DataInputStream(it).readInt() },
        )
    }

    @Test
    fun legacyMigrationFailure_discardsUnboundRecordAndFallsBackToAbsent() = runBlocking {
        writeLegacyRecord(TOKEN_A)
        val bytes = credentialFile().readBytes()
        val input = java.io.DataInputStream(bytes.inputStream())
        input.readInt()
        val ivLength = input.readInt()
        input.skipBytes(ivLength)
        val ciphertextLength = input.readInt()
        val ciphertextOffset = 4 + 4 + ivLength + 4
        bytes[ciphertextOffset + ciphertextLength - 1] = (bytes[ciphertextOffset + ciphertextLength - 1].toInt() xor 1).toByte()
        credentialFile().writeBytes(bytes)

        val store = store()
        assertFalse(store.migrateLegacyCredential(OWNER_A))
        assertFalse("unbound ciphertext is discarded when it cannot be migrated", credentialFile().exists())
        assertNull(store.readToken(OWNER_A))
        assertFalse(store.observe(OWNER_A).first().present)
    }

    // --- observable state is masked only -------------------------------------

    @Test
    fun observe_neverExposesPlaintextToken() = runBlocking {
        val store = store()
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_B, COUNTRY_PH, OWNER_A))

        val state = store.observe(OWNER_A).first()
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
        val store = store()
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH, OWNER_A))

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
        val credentialStore = store()
        assertEquals(SaveThinQResult.Success, credentialStore.save(TOKEN_B, COUNTRY_PH, OWNER_A))

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
        val first = store()
        val id1 = first.clientId
        val parsed = runCatching { UUID.fromString(id1) }.getOrNull()
        assertNotNull("client id must be a well-formed UUID, was '$id1'", parsed)
        assertEquals("canonical UUID string is 36 chars", 36, id1.length)

        first.save(TOKEN_A, COUNTRY_PH, OWNER_A)
        val second = store()
        assertEquals("client id must persist across store instances", id1, second.clientId)

        second.clear()
        val third = store()
        assertEquals("client id must survive a token clear", id1, third.clientId)
    }

    // --- clear ---------------------------------------------------------------

    @Test
    fun clear_removesTokenButKeepsClientId() = runBlocking {
        val store = store()
        val id = store.clientId
        store.save(TOKEN_A, COUNTRY_PH, OWNER_A)
        assertNotNull("precondition: a saved token exists", store.readToken(OWNER_A))

        store.clear()

        assertNull("token must be gone after clear", store.readToken(OWNER_A))
        val state = store.observe(OWNER_A).first()
        assertFalse(state.present)
        assertNull(state.credential)
        assertFalse(state.unlockFailed)
        assertEquals("client id must remain after clear", id, state.clientId)
        assertEquals(id, store.clientId)
        assertFalse("credential file must be deleted by clear", credentialFile().exists())
        assertFalse("ThinQ Keystore alias must be deleted by clear", hasKeyForTesting())
    }

    @Test
    fun undeletableCredentialFile_reportsRemovalFailureAndKeepsOwnerSignedIn() = runBlocking {
        val identity = FakeOwnerIdentity()
        identity.signIn()
        val owner = identity.currentSession!!
        val credentialStore = store()
        assertEquals(SaveThinQResult.Success, credentialStore.save(TOKEN_A, COUNTRY_PH, owner.uid))
        val controller = OwnerSettingsController(
            identity = identity,
            credentialStore = credentialStore,
            credentialVerifier = FakeThinQCredentialVerifier(),
            local = FakeLocalHistory(),
            firebaseProjectId = "credential-clear-test-project",
            configuredDeviceId = "credential-clear-test-device",
        )
        controller.refresh()
        controller.requestRemoveCredential()

        // A non-empty directory at the record path makes File.delete() fail on
        // Android without relying on permissions or device-specific behavior.
        assertTrue(credentialFile().delete())
        assertTrue(credentialFile().mkdir())
        File(credentialFile(), "occupied").writeText("synthetic fixture")

        val result = controller.removeCredentialConfirmed()
        assertTrue("removal failure is surfaced", result is OwnerSettingsActionResult.Failure)
        assertEquals(
            OwnerSettingsActionFailure.CREDENTIAL_STORE_CLEAR_FAILED,
            (result as OwnerSettingsActionResult.Failure).reason,
        )
        assertEquals("failed credential removal must retain the signed-in owner", owner, identity.currentSession)
        assertTrue("the undeletable record path remains", credentialFile().exists())
        assertFalse("the unusable ciphertext's key is still removed", hasKeyForTesting())
    }

    // --- validation keeps the prior record -----------------------------------

    @Test
    fun invalidSave_doesNotOverwritePriorRecord() = runBlocking {
        val store = store()
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH, OWNER_A))

        val blankToken = store.save("   \t ", COUNTRY_PH, OWNER_A)
        assertEquals(
            SaveThinQResult.Failure(ThinQCredentialSaveFailure.INVALID_INPUT),
            blankToken,
        )
        val threeLetterCountry = store.save(TOKEN_B, "PHX", OWNER_A)
        assertEquals(
            SaveThinQResult.Failure(ThinQCredentialSaveFailure.INVALID_INPUT),
            threeLetterCountry,
        )
        val numericCountry = store.save(TOKEN_B, "P2", OWNER_A)
        assertEquals(
            SaveThinQResult.Failure(ThinQCredentialSaveFailure.INVALID_INPUT),
            numericCountry,
        )

        assertEquals("failed saves must leave the prior record intact", TOKEN_A, store.readToken(OWNER_A))
    }

    // --- backup and storage location -----------------------------------------

    @Test
    fun recordLivesUnderNoBackupDirAndBackupDisabled() = runBlocking {
        val store = store()
        store.save(TOKEN_A, COUNTRY_PH, OWNER_A)

        val noBackup = context.noBackupFilesDir
        val recordFile = credentialFile()
        assertTrue(
            "record must live under noBackupFilesDir",
            recordFile.canonicalPath.startsWith(noBackup.canonicalPath),
        )
        assertTrue(recordFile.isFile)
        assertTrue(File(testStorageDir, "thinq_client_id").isFile)

        val backupEnabled = context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0
        assertFalse("android:allowBackup must be false in the manifest", backupEnabled)
    }

    // --- key loss and recovery -----------------------------------------------

    @Test
    fun keyLoss_readsNullSurfacesUnlockFailed_thenSaveRecovers() = runBlocking {
        val store = store()
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_A, COUNTRY_PH, OWNER_A))
        assertEquals(TOKEN_A, store.readToken(OWNER_A))

        // Simulate losing the platform-protected key by destroying the alias.
        assertTrue("precondition: store key must exist", hasKeyForTesting())
        deleteKeyForTesting()
        assertFalse("precondition: store key must be gone", hasKeyForTesting())

        // The persisted record can no longer be decrypted.
        assertNull("readToken must not crash or leak ciphertext after key loss", store.readToken(OWNER_A))
        val afterLoss = store.observe(OWNER_A).first()
        assertTrue("observe must reflect the failed unlock", afterLoss.unlockFailed)
        assertFalse("no usable credential while the key is lost", afterLoss.present)

        // A fresh save recovers by generating a new key and re-encrypting.
        assertEquals(SaveThinQResult.Success, store.save(TOKEN_B, COUNTRY_PH, OWNER_A))
        assertEquals(TOKEN_B, store.readToken(OWNER_A))
        val recovered = store.observe(OWNER_A).first()
        assertFalse(recovered.unlockFailed)
        assertTrue(recovered.present)
        assertEquals("client id must be untouched by the key-loss cycle", store.clientId, recovered.clientId)
    }

    @Test
    fun reopenAfterKeyLoss_surfacesUnlockFailedWithoutCrash() = runBlocking {
        val store = store()
        store.save(TOKEN_A, COUNTRY_PH, OWNER_A)
        deleteKeyForTesting()

        val reopened = store()
        val state = reopened.observe(OWNER_A).first()
        assertTrue("a reopened store must surface unlockFailed", state.unlockFailed)
        assertFalse(state.present)
        assertNull(reopened.readToken(OWNER_A))
    }

    // --- helpers ---------------------------------------------------------------

    private fun store(): AndroidEncryptedThinQCredentialStore =
        AndroidEncryptedThinQCredentialStore.forTesting(
            context = context,
            storageSubdirectory = TEST_STORAGE_SUBDIR,
            testKeyAlias = TEST_KEY_ALIAS,
        )

    private val testStorageDir: File
        get() = File(context.noBackupFilesDir, TEST_STORAGE_SUBDIR)

    private fun credentialFile(): File = File(testStorageDir, "thinq_credential.enc")

    private fun maskOf(token: String): String = "$MASK ${token.takeLast(4)}"

    /** Creates the pre-owner-binding v1 fixture using the test Keystore key. */
    private suspend fun writeLegacyRecord(token: String) {
        val store = store()
        assertEquals(SaveThinQResult.Success, store.save(token, COUNTRY_PH, OWNER_A))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .getKey(TEST_KEY_ALIAS, null) as javax.crypto.SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val legacy = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeInt(1)
                out.writeInt(cipher.iv.size)
                out.write(cipher.iv)
                out.writeInt(ciphertext.size)
                out.write(ciphertext)
                out.writeUTF(maskOf(token))
                out.writeUTF(COUNTRY_PH)
                out.writeLong(System.currentTimeMillis())
            }
            buffer.toByteArray()
        }
        credentialFile().writeBytes(legacy)
    }

    companion object {
        private const val OWNER_A = "firebase-owner-a"
        private const val OWNER_B = "firebase-owner-b"
        const val COUNTRY_PH = "PH"
        const val MASK = "\u2022\u2022\u2022\u2022"
        private const val TEST_STORAGE_SUBDIR = "thinq-credential-adapter-test"
        private const val TEST_KEY_ALIAS = "airchive_thinq_pat_test_key"

        private fun deleteKeyForTesting() {
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry(TEST_KEY_ALIAS)
            }
        }

        private fun hasKeyForTesting(): Boolean = runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(TEST_KEY_ALIAS)
        }.getOrDefault(false)

        // Clearly synthetic PAT fixtures; never a real token. `.example.invalid`
        // marks them as test data and keeps them out of any production-looking shape.
        const val TOKEN_A = "airchive-test-pat-a1b2c3d4e5.example.invalid"
        const val TOKEN_B = "airchive-test-pat-9f8e7d6c5b.example.invalid"
    }
}
