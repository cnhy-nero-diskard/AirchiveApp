package cloud.airchive.personal.data.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import cloud.airchive.shared.contract.credentials.SaveThinQResult
import cloud.airchive.shared.contract.credentials.ThinQCredential
import cloud.airchive.shared.contract.credentials.ThinQCredentialSaveFailure
import cloud.airchive.shared.contract.credentials.ThinQCredentialState
import cloud.airchive.shared.contract.credentials.ThinQCredentialStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
private const val KEY_ALIAS = "airchive_thinq_pat_key"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val GCM_TAG_BITS = 128
private const val LEGACY_RECORD_VERSION = 1
private const val RECORD_VERSION = 2
private const val STORAGE_SUBDIR = "thinq"
private const val CREDENTIAL_FILE_NAME = "thinq_credential.enc"
private const val CLIENT_ID_FILE_NAME = "thinq_client_id"
private const val TMP_SUFFIX = ".tmp"
private const val MASKED_PREFIX = "\u2022\u2022\u2022\u2022 "
private const val MAX_RECORD_BYTES = 1 shl 16
private val TWO_LETTER_COUNTRY = Regex("[a-zA-Z]{2}")

/**
 * Production Android [ThinQCredentialStore] (OpenSpec task 3.3).
 *
 * ## Security model
 *
 *  - The PAT is encrypted with AES/GCM/NoPadding under a key generated inside
 *    the system [AndroidKeyStore]. No biometric or lock-screen auth is required
 *    on the key; the Keystore itself still protects the material at rest.
 *  - `context.noBackupFilesDir` holds exactly two files:
 *      - `thinq/thinq_credential.enc` — GCM IV + ciphertext plus masked
 *        metadata (masked token, country, creation time), replaced atomically;
 *      - `thinq/thinq_client_id` — the stable random UUID LG client id.
 *    The plaintext PAT is never written to SharedPreferences, Room, logs,
 *    diagnostics, exported files, or auto-backup storage.
 *  - A failed replacement never clobbers the prior record: `save` reaches the
 *    on-disk record only after the new ciphertext exists and the atomic move
 *    completes.
 *  - A missing or invalidated Keystore key never crashes: `save` reports
 *    [ThinQCredentialSaveFailure.KEY_UNAVAILABLE], `readToken` returns `null`,
 *    and `observe` reflects `unlockFailed` so the owner is routed back through
 *    setup without exposing ciphertext.
 */
class AndroidEncryptedThinQCredentialStore private constructor(
    private val dispatcher: CoroutineDispatcher,
    private val storageDir: File,
    private val keyAlias: String,
) : ThinQCredentialStore {

    constructor(
        context: Context,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        dispatcher = dispatcher,
        storageDir = File(context.noBackupFilesDir, STORAGE_SUBDIR),
        keyAlias = KEY_ALIAS,
    )

    /** Test-only factory: every test uses its own no-backup directory and Keystore alias. */
    internal companion object TestFactory {
        fun forTesting(
            context: Context,
            storageSubdirectory: String,
            testKeyAlias: String,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): AndroidEncryptedThinQCredentialStore {
            require(storageSubdirectory.isNotBlank() && File(storageSubdirectory).name == storageSubdirectory) {
                "Test credential storage must be a simple subdirectory name."
            }
            require(testKeyAlias.isNotBlank()) { "A dedicated test Keystore alias is required." }
            return AndroidEncryptedThinQCredentialStore(
                dispatcher = dispatcher,
                storageDir = File(context.noBackupFilesDir, storageSubdirectory),
                keyAlias = testKeyAlias,
            )
        }
    }

    private val credentialFile: File = File(storageDir, CREDENTIAL_FILE_NAME)
    private val clientIdFile: File = File(storageDir, CLIENT_ID_FILE_NAME)
    private val mutex = Mutex()

    /** Stable per-install LG client id, generated once under no-backup storage. */
    override val clientId: String = loadOrCreateClientId()

    private val _state = MutableStateFlow(loadInitialState())
    @Volatile
    private var publishedOwnerUid: String? = loadInitialOwnerUid()

    override fun observe(ownerUid: String?): Flow<ThinQCredentialState> =
        _state.asStateFlow().map { state ->
            if (!ownerUid.isNullOrBlank() && (publishedOwnerUid == ownerUid || state.unlockFailed)) state
            else noCredentialState(unlockFailed = false)
        }

    override suspend fun save(rawToken: String, accountCountry: String, ownerUid: String?): SaveThinQResult {
        val country = accountCountry.trim()
        if (rawToken.isBlank() || !TWO_LETTER_COUNTRY.matches(country) || ownerUid.isNullOrBlank()) {
            return SaveThinQResult.Failure(ThinQCredentialSaveFailure.INVALID_INPUT)
        }
        return withContext(dispatcher) {
            mutex.withLock { saveLocked(rawToken, country, ownerUid) }
        }
    }

    override suspend fun readToken(ownerUid: String?): String? = withContext(dispatcher) {
        if (ownerUid.isNullOrBlank()) return@withContext null
        mutex.withLock { readTokenLocked(ownerUid) }
    }

    override suspend fun migrateLegacyCredential(ownerUid: String): Boolean = withContext(dispatcher) {
        if (ownerUid.isBlank()) return@withContext false
        mutex.withLock { migrateLegacyCredentialLocked(ownerUid) }
    }

    override suspend fun clear() {
        withContext(dispatcher) {
            mutex.withLock {
                clearCredentialLocked()
            }
        }
    }

    // --- save / read ---------------------------------------------------------

    private fun saveLocked(rawToken: String, country: String, ownerUid: String): SaveThinQResult = try {
        val key = getOrCreateKey() // returns a fresh key when one was invalidated
        val record = encrypt(rawToken, country, ownerUid, key)
        atomicReplace(credentialFile, encodeRecord(record))
        publishedOwnerUid = ownerUid
        _state.value = ThinQCredentialState(
            present = true,
            clientId = clientId,
            credential = record.toCredential(),
            unlockFailed = false,
        )
        SaveThinQResult.Success
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: GeneralSecurityException) {
        SaveThinQResult.Failure(ThinQCredentialSaveFailure.KEY_UNAVAILABLE)
    } catch (_: IOException) {
        SaveThinQResult.Failure(ThinQCredentialSaveFailure.UNKNOWN)
    } catch (_: Exception) {
        // Storage/provider runtime exceptions are reported as a typed save
        // failure too; never let a platform exception crash the onboarding UI.
        SaveThinQResult.Failure(ThinQCredentialSaveFailure.UNKNOWN)
    }

    private fun readTokenLocked(ownerUid: String): String? = try {
        val record = readRecordSafely() ?: return null
        if (record.version == LEGACY_RECORD_VERSION) {
            discardUnboundRecord()
            return null
        }
        val key = getExistingKeyOrNull()
        if (key == null) {
            markUnlockFailed()
            return null
        }
        val decrypted = decrypt(record, key)
        if (decrypted.ownerUid != ownerUid) {
            discardUnboundRecord()
            return null
        }
        decrypted.token
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: GeneralSecurityException) {
        markUnlockFailed()
        null
    } catch (_: IOException) {
        markUnlockFailed()
        null
    } catch (_: Exception) {
        markUnlockFailed()
        null
    }

    /** Migrate a legacy unbound record only after the owner passes access checks. */
    private fun migrateLegacyCredentialLocked(ownerUid: String): Boolean = try {
        val record = readRecordSafely() ?: return false
        val key = getExistingKeyOrNull()
        if (key == null) {
            discardUnboundRecord()
            return false
        }
        val decrypted = decrypt(record, key)
        if (record.version == RECORD_VERSION) {
            if (decrypted.ownerUid != ownerUid) {
                discardUnboundRecord()
                return false
            }
            publishedOwnerUid = ownerUid
            _state.value = ThinQCredentialState(
                present = true,
                clientId = clientId,
                credential = record.toCredential(),
                unlockFailed = false,
            )
            return true
        }

        val boundRecord = encrypt(decrypted.token, record.accountCountry, ownerUid, key)
        atomicReplace(credentialFile, encodeRecord(boundRecord))
        publishedOwnerUid = ownerUid
        _state.value = ThinQCredentialState(
            present = true,
            clientId = clientId,
            credential = boundRecord.toCredential(),
            unlockFailed = false,
        )
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        discardUnboundRecord()
        false
    }

    // --- observable state -----------------------------------------------------

    private fun loadInitialState(): ThinQCredentialState {
        val id = clientId
        if (!credentialFile.isFile) return noCredentialState(unlockFailed = false)
        return try {
            val record = readRecordSafely() ?: return noCredentialState(unlockFailed = false)
            val key = getExistingKeyOrNull()
            if (key == null) {
                unlockFailedState(id)
            } else {
                val decrypted = decrypt(record, key) // verifies the saved record is still usable
                if (record.version == LEGACY_RECORD_VERSION || decrypted.ownerUid.isNullOrBlank()) {
                    noCredentialState(unlockFailed = false)
                } else {
                    ThinQCredentialState(
                        present = true,
                        clientId = id,
                        credential = record.toCredential(),
                        unlockFailed = false,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GeneralSecurityException) {
            unlockFailedState(id)
        } catch (_: IOException) {
            unlockFailedState(id)
        } catch (_: Exception) {
            unlockFailedState(id)
        }
    }

    private fun loadInitialOwnerUid(): String? = try {
        val record = readRecordSafely() ?: return null
        if (record.version != RECORD_VERSION) return null
        val key = getExistingKeyOrNull() ?: return null
        decrypt(record, key).ownerUid
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun markUnlockFailed() {
        _state.value = noCredentialState(unlockFailed = true)
    }

    private fun noCredentialState(unlockFailed: Boolean) =
        ThinQCredentialState(present = false, clientId = clientId, credential = null, unlockFailed = unlockFailed)

    private fun unlockFailedState(id: String) =
        ThinQCredentialState(present = false, clientId = id, credential = null, unlockFailed = true)

    // --- AndroidKeyStore ------------------------------------------------------

    private fun getOrCreateKey(): SecretKey {
        getExistingKeyOrNull()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun getExistingKeyOrNull(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        keyStore.getKey(keyAlias, null) as? SecretKey
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IOException) {
        null
    }

    private fun encrypt(rawToken: String, country: String, ownerUid: String, key: SecretKey): EncryptedRecord {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val payload = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { out ->
                out.writeUTF(ownerUid)
                out.writeUTF(rawToken)
            }
            buffer.toByteArray()
        }
        val ciphertext = cipher.doFinal(payload)
        return EncryptedRecord(
            version = RECORD_VERSION,
            iv = cipher.iv.copyOf(),
            ciphertext = ciphertext,
            maskedToken = mask(rawToken),
            accountCountry = country,
            createdAtEpochMillis = System.currentTimeMillis(),
        )
    }

    private fun decrypt(record: EncryptedRecord, key: SecretKey): DecryptedCredential {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, record.iv))
        val plaintext = cipher.doFinal(record.ciphertext)
        if (record.version == LEGACY_RECORD_VERSION) {
            return DecryptedCredential(ownerUid = null, token = String(plaintext, Charsets.UTF_8))
        }
        return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
            DecryptedCredential(ownerUid = input.readUTF(), token = input.readUTF())
        }
    }

    // --- no-backup file IO ----------------------------------------------------

    private fun loadOrCreateClientId(): String = try {
        readFileOrNull(clientIdFile)
            ?.toString(Charsets.UTF_8)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { candidate ->
                // Only a well-formed UUID qualifies; anything else is regenerated.
                runCatching { UUID.fromString(candidate).toString() }.getOrNull()
            }
            ?: generateAndPersistClientId()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        generateAndPersistClientId()
    }

    private fun generateAndPersistClientId(): String {
        val id = UUID.randomUUID().toString()
        try {
            atomicReplace(clientIdFile, id.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // In-memory fallback: an unwritable no-backup directory must not
            // crash startup over a random LG client id.
        }
        return id
    }

    private fun readRecordSafely(): EncryptedRecord? {
        val bytes = readFileOrNull(credentialFile) ?: return null
        return decodeRecord(bytes)
    }

    private fun readFileOrNull(file: File): ByteArray? = if (file.isFile) file.readBytes() else null

    /**
     * Writes [bytes] into a sibling temp file, fsyncs it, then moves it over
     * [target] atomically. On any failure the previous [target] content is
     * left untouched.
     */
    private fun atomicReplace(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: throw IOException("No storage directory for ${target.name}")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create storage directory ${dir.path}")
        }
        val temp = tempFileFor(target)
        FileOutputStream(temp).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (moveFailed: IOException) {
            // Same-directory rename is atomic on the Android filesystems in use;
            // this fallback covers platforms without ATOMIC_MOVE support.
            if (!temp.renameTo(target)) {
                temp.delete()
                throw IOException("Failed to atomically replace ${target.name}", moveFailed)
            }
        }
    }

    private fun tempFileFor(target: File): File = File(target.parentFile, target.name + TMP_SUFFIX)

    /** Delete all encrypted material and the key, then verify every artifact is gone. */
    private fun clearCredentialLocked() {
        var failure: Exception? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (failure == null) failure = error
            }
        }
        fun deleteIfPresent(file: File) {
            if (file.exists() && !file.delete()) {
                throw IOException("Failed to delete ${file.name}")
            }
        }

        attempt { deleteIfPresent(credentialFile) }
        attempt { deleteIfPresent(tempFileFor(credentialFile)) }
        attempt {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias)
        }

        val encryptedMaterialRemains = try {
            credentialFile.exists() || tempFileFor(credentialFile).exists()
        } catch (error: Exception) {
            if (failure == null) failure = error
            true
        }
        val keyRemains = try {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }.containsAlias(keyAlias)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (failure == null) failure = error
            true
        }

        if (!encryptedMaterialRemains || !keyRemains) {
            // If either side of the encrypted record/key pair is gone, no PAT
            // can be used. Never keep publishing a stale usable credential.
            publishedOwnerUid = null
            _state.value = noCredentialState(
                unlockFailed = encryptedMaterialRemains && !keyRemains,
            )
        }
        if (encryptedMaterialRemains || keyRemains) {
            throw IOException("Failed to fully remove the protected ThinQ credential.", failure)
        }
    }

    private fun clearRecordLocked() {
        credentialFile.delete()
        tempFileFor(credentialFile).delete()
    }

    private fun discardUnboundRecord() {
        clearRecordLocked()
        publishedOwnerUid = null
        _state.value = noCredentialState(unlockFailed = false)
    }
}

/** Encrypted payload plus masked metadata, as persisted in `thinq_credential.enc`. */
private data class EncryptedRecord(
    val version: Int,
    val iv: ByteArray,
    val ciphertext: ByteArray,
    val maskedToken: String,
    val accountCountry: String,
    val createdAtEpochMillis: Long,
)

private data class DecryptedCredential(val ownerUid: String?, val token: String)

private fun EncryptedRecord.toCredential(): ThinQCredential =
    ThinQCredential(
        maskedToken = maskedToken,
        accountCountry = accountCountry,
        createdAtEpochMillis = createdAtEpochMillis,
    )

/** Masked form of [token] safe for UI and for on-disk metadata. */
private fun mask(token: String): String = MASKED_PREFIX + token.takeLast(4)

private fun encodeRecord(record: EncryptedRecord): ByteArray =
    ByteArrayOutputStream().use { buffer ->
        DataOutputStream(buffer).use { out ->
            out.writeInt(record.version)
            out.writeInt(record.iv.size)
            out.write(record.iv)
            out.writeInt(record.ciphertext.size)
            out.write(record.ciphertext)
            out.writeUTF(record.maskedToken)
            out.writeUTF(record.accountCountry)
            out.writeLong(record.createdAtEpochMillis)
        }
        buffer.toByteArray()
    }

private fun decodeRecord(bytes: ByteArray): EncryptedRecord =
    DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val version = input.readInt()
        if (version != LEGACY_RECORD_VERSION && version != RECORD_VERSION) {
            throw IOException("Unsupported ThinQ credential record version $version")
        }
        val ivLength = input.readInt()
        if (ivLength <= 0 || ivLength > MAX_RECORD_BYTES) {
            throw IOException("Unsupported ThinQ credential record lengths")
        }
        val iv = ByteArray(ivLength).also { input.readFully(it) }
        val ciphertextLength = input.readInt()
        if (ciphertextLength <= 0 || ciphertextLength > MAX_RECORD_BYTES) {
            throw IOException("Unsupported ThinQ credential record lengths")
        }
        val ciphertext = ByteArray(ciphertextLength).also { input.readFully(it) }
        val maskedToken = input.readUTF()
        val accountCountry = input.readUTF()
        val createdAtEpochMillis = input.readLong()
        if (maskedToken.isBlank() || accountCountry.isBlank()) {
            throw IOException("Malformed ThinQ credential record")
        }
        EncryptedRecord(version, iv, ciphertext, maskedToken, accountCountry, createdAtEpochMillis)
    }
