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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LocalRefEmitTest {

    @Test
    public void localRefsTrackOnlyFreshLocals() throws Exception {
        URL resource = LocalRefEmitTest.class.getClassLoader().getResource("tests/perf/04-local-refs");
        Path source = new File(resource.toURI()).toPath();
        Path temp = Files.createTempDirectory("local-refs-");
        try {
            Path classes = temp.resolve("classes");
            Files.createDirectories(classes);
            List<String> javac = new ArrayList<>(Arrays.asList("javac", "-d", classes.toString()));
            Files.list(source).filter(path -> path.toString().endsWith(".java")).map(Path::toString).forEach(javac::add);
            ProcessHelper.run(temp, 20_000, javac).check("Compilation");
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
            assertFalse(all.contains("refs.insert"));
            assertFalse(all.contains("unordered_set"));
            assertFalse(all.contains("NewLocalRef"));

            String sum = method(all, "// sum(I)I\n");
            assertTrue(sum.contains("std::vector<jobject> local_refs;"));
            assertTrue(sum.contains("clocal0.l = obj;"));
            assertFalse(sum.contains("local_refs.push_back"));
            assertTrue(sum.contains("GetIntField"));
            assertTrue(sum.contains("utils::clear_refs(env, local_refs);"));
            assertTrue(sum.contains("utils::untrack_ref(local_refs, clocal"));
            int aload = 0;
            int from = 0;
            while (true) {
                int at = sum.indexOf("// ALOAD ", from);
                if (at < 0) {
                    break;
                }
                int end = sum.indexOf("// New stack:", at);
                assertTrue(end > at);
                String insn = sum.substring(at, end);
                assertFalse(insn.contains("push_back"), insn);
                assertFalse(insn.contains("insert"), insn);
                aload++;
                from = end;
            }
            assertTrue(aload > 0);

            String dropped = method(all, "// dropped()I\n");
            String created = region(dropped, "NEW Test$Box");
            assertTrue(created.contains("AllocObject"));
            assertTrue(created.contains("local_refs.push_back"));

            String make = method(all, "// make()LTest$Box;\n");
            String ret = region(make, "ARETURN");
            assertFalse(ret.contains("DeleteLocalRef"), ret);
            assertTrue(ret.contains("return"));

            String pass = method(all, "// pass(LTest$Box;)LTest$Box;\n");
            assertFalse(pass.contains("local_refs.push_back"));
            assertFalse(region(pass, "ARETURN").contains("DeleteLocalRef"));

            String runtime = new String(Files.readAllBytes(out.resolve("cpp").resolve("native_jvm.cpp")), StandardCharsets.UTF_8);
            int clearAt = runtime.indexOf("void clear_refs");
            assertTrue(clearAt >= 0);
            int clearEnd = runtime.indexOf("jstring get_interned", clearAt);
            String clear = runtime.substring(clearAt, clearEnd);
            assertFalse(clear.contains("GetObjectRefType"));
            assertTrue(clear.contains("DeleteLocalRef"));
        } finally {
            Files.walk(temp).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static String method(String cpp, String marker) {
        int at = cpp.indexOf(marker);
        assertTrue(at >= 0, marker);
        int end = cpp.indexOf("\n    // ", at + marker.length());
        if (end < 0) {
            end = cpp.length();
        }
        return cpp.substring(at, end);
    }

    private static String region(String cpp, String marker) {
        int at = cpp.indexOf(marker);
        assertTrue(at >= 0, marker);
        int end = cpp.indexOf("// New stack:", at);
        assertTrue(end > at, marker);
        return cpp.substring(at, end);
    }
}
