package by.radioegor146;

import by.radioegor146.helpers.ProcessHelper;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class ArrayPinEmitTest {

    @Test
    public void generatedArrayAccessMatchesPinRules() throws Exception {
        URL resource = ArrayPinEmitTest.class.getClassLoader().getResource("tests/perf/05-arrays");
        Path source = new File(resource.toURI()).toPath();
        Path temp = Files.createTempDirectory("array-pin-");
        try {
            Path classes = temp.resolve("classes");
            Files.createDirectories(classes);
            List<String> javac = new ArrayList<>(Arrays.asList("javac", "-d", classes.toString()));
            Files.list(source).filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(javac::add);
            ProcessHelper.run(temp, 20_000, javac).check("Compilation");
            boolean krak = true;
            try {
                ProcessHelper.run(temp, 20_000, Arrays.asList("krak2", "-V")).check("krak2");
            } catch (Exception ex) {
                krak = false;
            }
            if (krak) {
                Files.list(source).filter(path -> path.toString().endsWith(".j")).forEach(path -> {
                    try {
                        String name = path.getFileName().toString().replaceAll("\\.j$", ".class");
                        ProcessHelper.run(temp, 20_000, Arrays.asList("krak2", "asm", "--out",
                                classes.resolve(name).toString(), path.toString())).check("Krakatau");
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
            }
            Path jar = temp.resolve("test.jar");
            ProcessHelper.run(temp, 20_000, Arrays.asList("jar", "cvfe", jar.toString(), "Test", "-C", classes.toString(), "."))
                    .check("Jar");
            Path out = temp.resolve("out");
            Files.createDirectories(out);
            new NativeObfuscator().process(jar, out, Collections.emptyList(), Collections.emptyList(),
                    null, "native_library", null, Platform.HOTSPOT, true, true, true);

            StringBuilder cpp = new StringBuilder();
            List<Path> classCpp = Files.list(out.resolve("cpp").resolve("output"))
                    .filter(path -> path.getFileName().toString().endsWith(".cpp"))
                    .filter(path -> !path.getFileName().toString().startsWith("data_"))
                    .collect(Collectors.toList());
            assertFalse(classCpp.isEmpty());
            for (Path path : classCpp) {
                cpp.append(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).append('\n');
            }
            String all = cpp.toString();

            String sum = method(all, "intSum([I)I");
            assertTrue(sum.contains("GetIntArrayElements"), sum);
            assertTrue(sum.contains("ReleaseIntArrayElements"), sum);
            assertFalse(sum.contains("GetIntArrayRegion"), sum);

            String call = method(all, "callInLoop([I)I");
            assertFalse(call.contains("GetIntArrayElements"), call);
            assertTrue(call.contains("GetIntArrayRegion"), call);

            String escape = method(all, "escape(I)I");
            assertFalse(escape.contains("GetIntArrayElements"), escape);

            String onceByte = method(all, "byteOnce()I");
            assertTrue(onceByte.contains("GetByteArrayRegion"), onceByte);
            assertTrue(onceByte.contains("SetByteArrayRegion"), onceByte);
            assertFalse(onceByte.contains("IsInstanceOf"), onceByte);
            assertFalse(onceByte.contains("utils::baload"), onceByte);
            assertFalse(onceByte.contains("utils::bastore"), onceByte);

            if (krak) {
                String once = method(all, "once([Z)I");
                assertTrue(once.contains("SetBooleanArrayRegion"), once);
                assertTrue(once.contains("GetBooleanArrayRegion"), once);
                assertTrue(once.contains("& 1"), once);
                assertFalse(once.contains("IsInstanceOf"), once);
                assertFalse(once.contains("utils::bastore"), once);
                String fill = method(all, "fill([ZI)I");
                assertTrue(fill.contains("GetBooleanArrayElements"), fill);
                assertTrue(fill.contains("ReleaseBooleanArrayElements"), fill);
                assertTrue(fill.contains("& 1"), fill);
                assertFalse(fill.contains("IsInstanceOf"), fill);
            }

            assertEquals(count(all, "GetIntArrayElements"), count(all, "ReleaseIntArrayElements"));
            assertEquals(count(all, "GetByteArrayElements"), count(all, "ReleaseByteArrayElements"));
            assertEquals(count(all, "GetBooleanArrayElements"), count(all, "ReleaseBooleanArrayElements"));
            assertEquals(count(all, "GetCharArrayElements"), count(all, "ReleaseCharArrayElements"));
            assertEquals(count(all, "GetShortArrayElements"), count(all, "ReleaseShortArrayElements"));
            assertEquals(count(all, "GetLongArrayElements"), count(all, "ReleaseLongArrayElements"));
            assertEquals(count(all, "GetFloatArrayElements"), count(all, "ReleaseFloatArrayElements"));
            assertEquals(count(all, "GetDoubleArrayElements"), count(all, "ReleaseDoubleArrayElements"));
            assertFalse(all.contains("GetPrimitiveArrayCritical"));
        } finally {
            Files.walk(temp).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static int count(String text, String token) {
        int n = 0;
        int at = 0;
        while ((at = text.indexOf(token, at)) >= 0) {
            n++;
            at += token.length();
        }
        return n;
    }

    private static String method(String cpp, String nameDesc) {
        int start = cpp.indexOf(nameDesc);
        assertTrue(start >= 0, nameDesc);
        Matcher matcher = Pattern.compile("//\\s*[a-z<][A-Za-z0-9_]*\\(").matcher(cpp);
        int from = start + nameDesc.length();
        int end = cpp.length();
        while (matcher.find(from)) {
            end = matcher.start();
            break;
        }
        return cpp.substring(start, end);
    }
}
