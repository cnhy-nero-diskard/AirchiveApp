package cloud.airchive.gradle;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Builds self-contained archive fixtures with private keys that exist only during this task. */
@DisableCachingByDefault(because = "The APKs are signed with newly generated throwaway keys.")
public abstract class GenerateAppUpdateVerifierFixturesTask extends DefaultTask {
    private static final String FIXTURE_KEY_PASSPHRASE = "airchive-test-fixture-only";

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAaptExecutable();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getApkSignerExecutable();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getAndroidJar();

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getKeytoolExecutable();

    @OutputDirectory
    public abstract DirectoryProperty getFixtureDirectory();

    @TaskAction
    public void generateFixtures() throws IOException, InterruptedException {
        File outputDirectory = getFixtureDirectory().get().getAsFile();
        deleteTree(outputDirectory);
        Files.createDirectories(outputDirectory.toPath());
        File workDirectory = new File(outputDirectory, ".fixture-work");
        Files.createDirectories(workDirectory.toPath());

        File primaryKey = new File(workDirectory, "primary-test-only.p12");
        File secondaryKey = new File(workDirectory, "secondary-test-only.p12");
        try {
            createKeyStore(primaryKey, "airchive-test-primary", "Airchive Update Test Primary");
            createKeyStore(secondaryKey, "airchive-test-secondary", "Airchive Update Test Secondary");

            signFixture("valid", 9000, false, primaryKey, "airchive-test-primary", outputDirectory);
            signFixture("wrong-signer", 9000, false, secondaryKey, "airchive-test-secondary", outputDirectory);
            signFixture("debuggable", 9000, true, primaryKey, "airchive-test-primary", outputDirectory);
            signFixture("lower-version", 1, false, primaryKey, "airchive-test-primary", outputDirectory);
            signMultipleSigners(primaryKey, secondaryKey, outputDirectory);
        } finally {
            deleteTree(workDirectory);
        }
    }

    private void createKeyStore(File path, String alias, String commonName) throws IOException, InterruptedException {
        runCommand(
                getKeytoolExecutable().get().getAsFile().getAbsolutePath(),
                "-genkeypair", "-noprompt",
                "-alias", alias,
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-dname", "CN=" + commonName,
                "-keystore", path.getAbsolutePath(),
                "-storetype", "PKCS12",
                "-storepass", FIXTURE_KEY_PASSPHRASE,
                "-keypass", FIXTURE_KEY_PASSPHRASE);
    }

    private void signFixture(
            String name,
            int versionCode,
            boolean debuggable,
            File keyStore,
            String alias,
            File outputDirectory) throws IOException, InterruptedException {
        File unsignedApk = createUnsignedApk(name, versionCode, debuggable, outputDirectory);
        runCommand(
                getApkSignerExecutable().get().getAsFile().getAbsolutePath(), "sign",
                "--v4-signing-enabled", "false",
                "--ks", keyStore.getAbsolutePath(),
                "--ks-key-alias", alias,
                "--ks-pass", "pass:" + FIXTURE_KEY_PASSPHRASE,
                "--key-pass", "pass:" + FIXTURE_KEY_PASSPHRASE,
                "--out", new File(outputDirectory, name + ".apk").getAbsolutePath(),
                unsignedApk.getAbsolutePath());
    }

    private void signMultipleSigners(File primaryKey, File secondaryKey, File outputDirectory)
            throws IOException, InterruptedException {
        File unsignedApk = createUnsignedApk("multiple-signers", 9000, false, outputDirectory);
        runCommand(
                getApkSignerExecutable().get().getAsFile().getAbsolutePath(), "sign",
                "--v1-signing-enabled", "false",
                "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "false",
                "--ks", primaryKey.getAbsolutePath(),
                "--ks-key-alias", "airchive-test-primary",
                "--ks-pass", "pass:" + FIXTURE_KEY_PASSPHRASE,
                "--key-pass", "pass:" + FIXTURE_KEY_PASSPHRASE,
                "--next-signer",
                "--ks", secondaryKey.getAbsolutePath(),
                "--ks-key-alias", "airchive-test-secondary",
                "--ks-pass", "pass:" + FIXTURE_KEY_PASSPHRASE,
                "--key-pass", "pass:" + FIXTURE_KEY_PASSPHRASE,
                "--out", new File(outputDirectory, "multiple-signers.apk").getAbsolutePath(),
                unsignedApk.getAbsolutePath());
    }

    private File createUnsignedApk(String name, int versionCode, boolean debuggable, File outputDirectory)
            throws IOException, InterruptedException {
        File manifestDirectory = new File(new File(outputDirectory, ".fixture-work"), "manifest-" + name);
        Files.createDirectories(manifestDirectory.toPath());
        File manifest = new File(manifestDirectory, "AndroidManifest.xml");
        String manifestText = ""
                + "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" "
                + "package=\"cloud.airchive.personal\" android:versionCode=\"" + versionCode + "\" "
                + "android:versionName=\"" + versionCode + "\">"
                + "<uses-sdk android:minSdkVersion=\"26\" android:targetSdkVersion=\"27\" />"
                + "<application android:hasCode=\"false\" android:label=\"Airchive update verifier fixture\" "
                + "android:debuggable=\"" + debuggable + "\" />"
                + "</manifest>";
        Files.writeString(manifest.toPath(), manifestText);
        File unsignedApk = new File(new File(outputDirectory, ".fixture-work"), name + "-unsigned.apk");
        runCommand(
                getAaptExecutable().get().getAsFile().getAbsolutePath(), "package", "-f",
                "-M", manifest.getAbsolutePath(),
                "-I", getAndroidJar().get().getAsFile().getAbsolutePath(),
                "-F", unsignedApk.getAbsolutePath());
        return unsignedApk;
    }

    private static void runCommand(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(new ArrayList<>(Arrays.asList(command)))
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Updater APK fixture command failed (" + command[0] + "): " + output);
        }
    }

    private static void deleteTree(File target) throws IOException {
        if (!target.exists()) return;
        try (Stream<java.nio.file.Path> paths = Files.walk(target.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    throw new IllegalStateException("Could not clean generated updater fixture files.", error);
                }
            });
        }
    }
}
