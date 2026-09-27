package by.radioegor146;

import by.radioegor146.helpers.ProcessHelper;
import by.radioegor146.helpers.ProcessHelper.ProcessResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Stream;

public class ReceiverLoaderTest {

    @Test
    public void childLoaderSeesClassHiddenFromDefiningLoader() throws Exception {
        Path source = Paths.get(ReceiverLoaderTest.class.getResource("/receiver-loader").toURI());
        Path temp = Files.createTempDirectory("receiver-loader-");
        try {
            Path classes = temp.resolve("classes");
            Files.createDirectories(classes);
            ArrayList<String> javac = new ArrayList<>(Arrays.asList("javac", "-d", classes.toString()));
            try (Stream<Path> files = Files.list(source)) {
                files.filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(javac::add);
            }
            ProcessHelper.run(temp, 20_000, javac).check("Compilation");
            Path inputJar = temp.resolve("test.jar");
            ArrayList<String> jar = new ArrayList<>(Arrays.asList(
                    "jar", "cfe", inputJar.toString(), "Main", "-C", classes.toString(), "."));
            ProcessHelper.run(temp, 20_000, jar).check("Jar");

            for (Platform platform : Platform.values()) {
                Path output = temp.resolve("output_" + platform.name());
                Path cpp = output.resolve("cpp");
                Files.createDirectories(cpp);
                new NativeObfuscator().process(inputJar, output, Collections.emptyList(), Collections.emptyList(),
                        null, "native_library", null, platform, true, false, true);
                if (System.getProperty("os.name").toLowerCase().contains("windows")) {
                    String arch = "32".equals(System.getProperty("sun.arch.data.model")) ? "x86" : "x64";
                    ProcessHelper.run(cpp, 120_000, Arrays.asList("cmake", "-DCMAKE_GENERATOR_PLATFORM=" + arch, "."))
                            .check("CMake prepare");
                } else {
                    ProcessHelper.run(cpp, 120_000, Arrays.asList("cmake", ".")).check("CMake prepare");
                }
                ProcessHelper.run(cpp, 160_000, Arrays.asList("cmake", "--build", ".", "--config", "Release"))
                        .check("CMake build");
                try (Stream<Path> libs = Files.list(cpp.resolve("build").resolve("lib"))) {
                    libs.filter(Files::isRegularFile).forEach(path -> {
                        try {
                            Files.copy(path, output.resolve(path.getFileName()));
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });
                }
                ProcessResult result = ProcessHelper.run(output, 60_000, Arrays.asList(
                        "java", "-Djava.library.path=.", "-jar", output.resolve("test.jar").toString()));
                result.check(platform.name());
                String stdout = result.stdout.replace("\r\n", "\n");
                if (!"base-missing\nok\nbase-missing\nok\ndistinct\narr-missing\narr-ok\nrow-missing\nrow-ok\n".equals(stdout)) {
                    throw new RuntimeException(platform + " stdout:\n" + result.stdout + "\nstderr:\n" + result.stderr);
                }
            }
        } finally {
            try (Stream<Path> walk = Files.walk(temp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }
}
