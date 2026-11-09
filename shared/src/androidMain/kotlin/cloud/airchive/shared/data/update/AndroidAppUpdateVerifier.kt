package cloud.airchive.shared.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest

sealed interface AndroidAppUpdateVerificationResult {
    data object Verified : AndroidAppUpdateVerificationResult
    data class Rejected(val reason: UpdateFailureReason) : AndroidAppUpdateVerificationResult
}

/** Rechecks the downloaded bytes and inspects archive metadata before the installer can use the APK. */
class AndroidAppUpdateVerifier(
    context: Context,
    private val expectedPackageName: String,
    private val installedVersionCode: Long,
    pinnedSignerSha256: String,
) {
    private val packageManager = context.packageManager
    private val pinnedSignerSha256 = pinnedSignerSha256.lowercase()

    init {
        require(expectedPackageName.isNotBlank()) { "Expected app package name must not be blank." }
        require(installedVersionCode >= 0L) { "Installed app version code must not be negative." }
        require(SHA256_PATTERN.matches(this.pinnedSignerSha256)) { "Pinned signer must be a SHA-256 hex digest." }
    }

    fun verify(
        download: AppUpdateDownloadResult,
        manifest: UpdateManifest,
    ): AndroidAppUpdateVerificationResult {
        val file = download.file
        if (!file.isFile || file.length() != manifest.apk.size || download.size != manifest.apk.size) {
            return rejected(file, UpdateFailureReason.APK_SIZE_MISMATCH)
        }

        val actualFileSha256 = try {
            file.sha256()
        } catch (_: IOException) {
            return rejected(file, UpdateFailureReason.APK_INVALID)
        }
        if (!actualFileSha256.equals(manifest.apk.sha256, ignoreCase = true) ||
            !download.sha256.equals(manifest.apk.sha256, ignoreCase = true)
        ) {
            return rejected(file, UpdateFailureReason.HASH_MISMATCH)
        }

        val archiveInfo = getArchiveInfo(file) ?: return rejected(file, UpdateFailureReason.APK_INVALID)
        if (archiveInfo.packageName != expectedPackageName) {
            return rejected(file, UpdateFailureReason.APK_PACKAGE_MISMATCH)
        }

        val archiveVersionCode = archiveInfo.appUpdateVersionCode()
        if (archiveVersionCode != manifest.versionCode || archiveVersionCode <= installedVersionCode) {
            return rejected(file, UpdateFailureReason.APK_VERSION_MISMATCH)
        }

        if (((archiveInfo.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            return rejected(file, UpdateFailureReason.APK_DEBUGGABLE)
        }

        val signerCertificates = archiveInfo.signerCertificates()
            ?: return rejected(file, UpdateFailureReason.APK_SIGNER_MISMATCH)
        if (signerCertificates.size > 1) {
            return rejected(file, UpdateFailureReason.APK_HAS_MULTIPLE_SIGNERS)
        }
        if (signerCertificates.size != 1) {
            return rejected(file, UpdateFailureReason.APK_SIGNER_MISMATCH)
        }

        val signerSha256 = signerCertificates.single().toByteArray().sha256()
        if (!signerSha256.equals(pinnedSignerSha256, ignoreCase = true)) {
            return rejected(file, UpdateFailureReason.APK_SIGNER_MISMATCH)
        }

        return AndroidAppUpdateVerificationResult.Verified
    }

    @Suppress("DEPRECATION")
    private fun getArchiveInfo(file: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return try {
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        } catch (_: RuntimeException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun PackageInfo.signerCertificates(): Array<android.content.pm.Signature>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners
        } else {
            signatures
        }

    private fun PackageInfo.appUpdateVersionCode(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
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

    private fun ByteArray.toHexString(): String = buildString(size * 2) {
        for (byte in this@toHexString) {
            val value = byte.toInt() and 0xff
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0f])
        }
    }

    private fun rejected(file: File, reason: UpdateFailureReason): AndroidAppUpdateVerificationResult.Rejected {
        if (file.exists()) file.delete()
        return AndroidAppUpdateVerificationResult.Rejected(reason)
    }

    companion object {
        private const val HASH_BUFFER_BYTES = 32 * 1024
        private const val HEX_DIGITS = "0123456789abcdef"
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
