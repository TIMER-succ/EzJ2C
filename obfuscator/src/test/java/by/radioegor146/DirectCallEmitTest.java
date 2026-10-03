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

public class DirectCallEmitTest {

    @Test
    public void generatedCallsMatchDirectRules() throws Exception {
        URL resource = DirectCallEmitTest.class.getClassLoader().getResource("tests/perf/06-direct-call");
        Path source = new File(resource.toURI()).toPath();
        Path temp = Files.createTempDirectory("direct-call-");
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

            String header = new String(Files.readAllBytes(out.resolve("cpp").resolve("native_jvm_direct.hpp")), StandardCharsets.UTF_8);
            StringBuilder cpp = new StringBuilder();
            List<Path> classCpp = Files.list(out.resolve("cpp").resolve("output"))
                    .filter(path -> path.getFileName().toString().endsWith(".cpp"))
                    .filter(path -> !path.getFileName().toString().startsWith("data_"))
                    .collect(Collectors.toList());
            assertFalse(classCpp.isEmpty());
            for (Path path : classCpp) {
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                assertTrue(text.contains("#include \"../native_jvm_direct.hpp\""), path.getFileName().toString());
                cpp.append(text).append('\n');
            }
            String all = cpp.toString();

            String same = region(all, "INVOKESTATIC Same.add(II)I");
            assertTrue(same.contains("_body("));
            assertFalse(same.contains("CallStaticIntMethod"));
            assertTrue(same.contains("cclasses["));
            assertTrue(same.contains("ExceptionCheck") || same.contains("goto L_CATCH_"));
            assertDeclared(header, same);

            String inc = region(all, "INVOKESTATIC Same.inc(I)I");
            assertTrue(inc.contains("_body("));
            assertFalse(inc.contains("CallStaticIntMethod"));
            assertDeclared(header, inc);

            assertTrue(region(all, "INVOKESTATIC Other.add(II)I").contains("_body("));
            assertTrue(region(all, "INVOKESTATIC Fact.fact(J)J").contains("_body("));
            assertTrue(region(all, "INVOKESPECIAL SuperS.f()Ljava/lang/String;").contains("_body("));
            String priv = all.contains("INVOKESPECIAL Priv.secret(I)I")
                    ? region(all, "INVOKESPECIAL Priv.secret(I)I")
                    : region(all, "INVOKEVIRTUAL Priv.secret(I)I");
            assertTrue(priv.contains("_body("));
            assertFalse(priv.contains("Call"));
            String arr = region(all, "INVOKESTATIC Arr.sum([I)I");
            assertTrue(arr.contains("_body("));
            assertTrue(arr.contains("(jarray)"));
            assertTrue(arr.contains("cache_classes && cclasses["));
            assertTrue(arr.contains("m_use != nullptr"));
            assertTrue(region(all, "INVOKESTATIC B.run()V").contains("_body("));
            assertTrue(region(all, "INVOKESTATIC B.run()V").contains("cclasses["));
            assertTrue(region(all, "INVOKESTATIC Boom.boom()V").contains("_body("));
            assertTrue(region(all, "INVOKESTATIC Same.flag(I)Z").contains("_body("));
            assertFalse(region(all, "INVOKESTATIC Same.flag(I)Z").contains("CallStaticBooleanMethod"));
            String name = region(all, "INVOKESTATIC Same.name()Ljava/lang/String;");
            assertTrue(name.contains("local_refs.push_back"));
            assertTrue(name.contains("PushLocalFrame"));
            assertTrue(name.contains("PopLocalFrame"));

            String virtual = virtualF(all);
            assertFalse(virtual.contains("_body("));
            assertTrue(virtual.contains("Call"));

            assertStaysJni(all, "INVOKESTATIC Sync.add(II)I");
            assertStaysJni(all, "INVOKESPECIAL Box.<init>(I)V");
            assertStaysJni(all, "INVOKESTATIC java/lang/Math.max(II)I");
            assertStaysJni(all, "INVOKESTATIC Ext.plain(I)I");
        } finally {
            Files.walk(temp).sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static void assertStaysJni(String cpp, String marker) {
        String region = region(cpp, marker);
        assertFalse(region.contains("_body("), marker);
        assertTrue(region.contains("Call"), marker);
    }

    private static void assertDeclared(String header, String region) {
        int call = region.indexOf("_body(");
        int start = region.lastIndexOf("native_jvm::", call);
        String qual = region.substring(start, call + "_body".length());
        String namespace = qual.substring(0, qual.lastIndexOf("::"));
        String name = qual.substring(qual.lastIndexOf("::") + 2);
        assertTrue(header.contains("namespace " + namespace), qual);
        assertTrue(header.contains(name + "("), qual);
    }

    private static String virtualF(String cpp) {
        int at = cpp.indexOf("INVOKEVIRTUAL");
        while (at >= 0) {
            int end = cpp.indexOf("// New stack:", at);
            String part = cpp.substring(at, end);
            if (part.contains(".f()")) {
                return part;
            }
            at = cpp.indexOf("INVOKEVIRTUAL", end);
        }
        throw new AssertionError("missing invokevirtual f()");
    }

    private static String region(String cpp, String marker) {
        int at = cpp.indexOf(marker);
        assertTrue(at >= 0, marker);
        int end = cpp.indexOf("// New stack:", at);
        assertTrue(end > at, marker);
        return cpp.substring(at, end);
    }
}
