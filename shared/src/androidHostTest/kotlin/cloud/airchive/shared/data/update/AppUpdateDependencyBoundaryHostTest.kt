package cloud.airchive.shared.data.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Keeps updater implementations independent from device control and owner services. */
class AppUpdateDependencyBoundaryHostTest {

    @Test
    fun updaterSourcesDoNotReferenceThinqFirebaseCredentialStoreOrAcCommandPorts() {
        val repositoryRoot = findRepositoryRoot()
        val sourceFiles = buildList {
            addKotlinFiles(File(repositoryRoot, "shared/src/commonMain/kotlin/cloud/airchive/shared/data/update"))
            addKotlinFiles(File(repositoryRoot, "shared/src/androidMain/kotlin/cloud/airchive/shared/data/update"))
            addKotlinFiles(
                File(repositoryRoot, "shared/src/commonMain/kotlin/cloud/airchive/shared"),
                nameStartsWith = "AppUpdate",
            )
            addKotlinFiles(
                File(repositoryRoot, "androidApp/src/main/kotlin/cloud/airchive/personal"),
                nameStartsWith = "AndroidAppUpdate",
            )
        }.flatten().distinct().sortedBy { it.relativeTo(repositoryRoot).path }

        assertFalse(sourceFiles.isEmpty(), "No updater production sources were found.")
        val violations = sourceFiles.flatMap { source ->
            source.readLines().mapIndexedNotNull { index, line ->
                if (FORBIDDEN_DEPENDENCY.containsMatchIn(line)) {
                    "${source.relativeTo(repositoryRoot).path}:${index + 1}: ${line.trim()}"
                } else {
                    null
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Updater code must not depend on ThinQ, Firebase, credential stores, or the AC command port:\n" +
                violations.joinToString("\n"),
        )
    }

    private fun findRepositoryRoot(): File {
        val startingDirectory = File(requireNotNull(System.getProperty("user.dir"))).canonicalFile
        val candidates = generateSequence(startingDirectory) { it.parentFile }
        return requireNotNull(
            candidates.firstOrNull { directory ->
                File(directory, "shared/src/commonMain").isDirectory &&
                    File(directory, "androidApp/src/main").isDirectory
            },
        ) { "Could not find the AirchiveApp repository root from $startingDirectory." }
    }

    private fun MutableList<List<File>>.addKotlinFiles(
        directory: File,
        nameStartsWith: String? = null,
    ) {
        require(directory.isDirectory) { "Expected updater source directory is missing: $directory" }
        val files = requireNotNull(directory.listFiles()) { "Could not list updater sources in $directory" }
        add(
            files.filter { file ->
                file.isFile && file.extension == "kt" && (nameStartsWith == null || file.name.startsWith(nameStartsWith))
            },
        )
    }

    private companion object {
        val FORBIDDEN_DEPENDENCY = Regex(
            """com\.google\.firebase|cloud\.airchive\.(?:shared\.(?:data\.thinq|contract\.(?:thinq|credentials))|personal\.data\.credentials)|\b(?:Firebase[A-Za-z0-9_]*|ThinQCredential[A-Za-z0-9_]*|AndroidEncryptedThinQCredentialStore|AcCommand[A-Za-z0-9_]*)\b""",
        )
    }
}
