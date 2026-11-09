package cloud.airchive.personal.data.update

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cloud.airchive.shared.data.update.AndroidAppUpdateVerificationResult
import cloud.airchive.shared.data.update.AndroidAppUpdateVerifier
import cloud.airchive.shared.data.update.AppUpdateDownloadResult
import cloud.airchive.shared.data.update.UpdateApk
import cloud.airchive.shared.data.update.UpdateFailureReason
import cloud.airchive.shared.data.update.UpdateManifest
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAppUpdateVerifierTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext get() = instrumentation.targetContext

    @Test
    fun rejectsHashMismatchAndDeletesTheArchive() {
        val file = fixture("valid")
        try {
            val pin = signerSha256(file)
            val manifest = manifest(file, versionCode = FIXTURE_VERSION_CODE, pinnedSignerSha256 = pin, expectedSha256 = "0".repeat(64))

            assertRejected(file, verify(file, manifest, pin), UpdateFailureReason.HASH_MISMATCH)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsWrongSignerAndDeletesTheArchive() {
        val pin = primarySignerPin()
        val file = fixture("wrong-signer")
        try {
            val manifest = manifest(file, FIXTURE_VERSION_CODE, pin)

            assertRejected(file, verify(file, manifest, pin), UpdateFailureReason.APK_SIGNER_MISMATCH)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsTwoSignersAndDeletesTheArchive() {
        val pin = primarySignerPin()
        val file = fixture("multiple-signers")
        try {
            val manifest = manifest(file, FIXTURE_VERSION_CODE, pin)

            assertRejected(file, verify(file, manifest, pin), UpdateFailureReason.APK_HAS_MULTIPLE_SIGNERS)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsDebuggableArchiveAndDeletesIt() {
        val pin = primarySignerPin()
        val file = fixture("debuggable")
        try {
            val manifest = manifest(file, FIXTURE_VERSION_CODE, pin)

            assertRejected(file, verify(file, manifest, pin), UpdateFailureReason.APK_DEBUGGABLE)
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsLowerVersionAndDeletesTheArchive() {
        val pin = primarySignerPin()
        val file = fixture("lower-version")
        try {
            val manifest = manifest(file, versionCode = 1L, pinnedSignerSha256 = pin)

            assertRejected(
                file,
                verify(file, manifest, pin, installedVersionCode = 2L),
                UpdateFailureReason.APK_VERSION_MISMATCH,
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun acceptsHigherNonDebuggableArchiveSignedWithTheThrowawayPin() {
        val file = fixture("valid")
        try {
            val pin = signerSha256(file)
            val manifest = manifest(file, FIXTURE_VERSION_CODE, pin)

            assertEquals(
                AndroidAppUpdateVerificationResult.Verified,
                verify(file, manifest, pin, installedVersionCode = 1L),
            )
            assertTrue("verified archive remains available for the installer", file.isFile)
        } finally {
            file.delete()
        }
    }

    private fun verify(
        file: File,
        manifest: UpdateManifest,
        pin: String,
        installedVersionCode: Long = 2L,
    ) = AndroidAppUpdateVerifier(
        context = targetContext,
        expectedPackageName = EXPECTED_PACKAGE,
        installedVersionCode = installedVersionCode,
        pinnedSignerSha256 = pin,
    ).verify(downloadResult(file), manifest)

    private fun assertRejected(
        file: File,
        result: AndroidAppUpdateVerificationResult,
        reason: UpdateFailureReason,
    ) {
        assertEquals(AndroidAppUpdateVerificationResult.Rejected(reason), result)
        assertFalse("rejected archive is deleted before install", file.exists())
    }

    private fun downloadResult(file: File): AppUpdateDownloadResult =
        AppUpdateDownloadResult(file, file.length(), file.sha256())

    private fun manifest(
        file: File,
        versionCode: Long,
        pinnedSignerSha256: String,
        expectedSha256: String = file.sha256(),
    ) = UpdateManifest(
        schemaVersion = 1,
        channel = "stable",
        packageName = EXPECTED_PACKAGE,
        versionCode = versionCode,
        versionName = versionCode.toString(),
        commit = "a".repeat(40),
        publishedAt = "2026-11-09T00:00:00Z",
        releaseNotes = "Verifier fixture",
        apk = UpdateApk(
            url = "https://github.com/example/app.apk",
            sha256 = expectedSha256,
            size = file.length(),
            signingCertSha256 = pinnedSignerSha256,
        ),
    )

    private fun primarySignerPin(): String {
        val validFixture = fixture("valid")
        return try {
            signerSha256(validFixture)
        } finally {
            validFixture.delete()
        }
    }

    private fun fixture(name: String): File {
        val destination = File(targetContext.cacheDir, "update-verifier-$name-${System.nanoTime()}.apk")
        instrumentation.context.assets.open("$name.apk").use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination
    }

    @Suppress("DEPRECATION")
    private fun signerSha256(file: File): String {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageInfo: PackageInfo = requireNotNull(targetContext.packageManager.getPackageArchiveInfo(file.absolutePath, flags))
        val signer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requireNotNull(packageInfo.signingInfo).apkContentsSigners.single()
        } else {
            requireNotNull(packageInfo.signatures).single()
        }
        return signer.toByteArray().sha256()
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHexString()
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHexString()

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        val value = byte.toInt() and 0xff
        HEX_DIGITS[value ushr 4].toString() + HEX_DIGITS[value and 0x0f]
    }

    companion object {
        private const val EXPECTED_PACKAGE = "cloud.airchive.personal"
        private const val FIXTURE_VERSION_CODE = 9_000L
        private const val HASH_BUFFER_BYTES = 32 * 1024
        private const val HEX_DIGITS = "0123456789abcdef"
    }
}
