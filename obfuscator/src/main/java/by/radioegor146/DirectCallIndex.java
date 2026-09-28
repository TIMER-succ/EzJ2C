package by.radioegor146;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public final class DirectCallIndex {

    private DirectCallIndex() {
    }

    public static Map<String, DirectTarget> collect(JarFile jar, ClassMethodFilter filter) throws IOException {
        Map<String, DirectTarget> result = new LinkedHashMap<>();
        int classIndex = 0;
        for (JarEntry entry : jar.stream().collect(Collectors.toList())) {
            if (entry.getName().equals(JarFile.MANIFEST_NAME) || !entry.getName().endsWith(".class")) {
                continue;
            }
            byte[] src = read(jar, entry);
            if (src.length < 4 || Util.byteArrayToInt(Arrays.copyOfRange(src, 0, 4)) != 0xCAFEBABE) {
                continue;
            }
            ClassNode classNode = new ClassNode(Opcodes.ASM9);
            new ClassReader(src).accept(classNode, 0);
            if (!filter.shouldProcess(classNode) || classNode.methods.stream().noneMatch(method ->
                    MethodProcessor.shouldProcess(method) && filter.shouldProcess(classNode, method))) {
                continue;
            }
            boolean iface = Util.getFlag(classNode.access, Opcodes.ACC_INTERFACE);
            String namespace = "native_jvm::classes::__ngen_" + Util.classCppFile(classNode.name, classIndex);
            if (!iface) {
                for (int i = 0; i < classNode.methods.size(); i++) {
                    MethodNode method = classNode.methods.get(i);
                    if (!MethodProcessor.shouldProcess(method) || !filter.shouldProcess(classNode, method)) {
                        continue;
                    }
                    if ("<clinit>".equals(method.name) || Util.getFlag(method.access, Opcodes.ACC_SYNCHRONIZED)) {
                        continue;
                    }
                    String body = Util.cppSymbol("native_" + method.name + i) + "_body";
                    boolean isStatic = Util.getFlag(method.access, Opcodes.ACC_STATIC);
                    boolean isPrivate = Util.getFlag(method.access, Opcodes.ACC_PRIVATE);
                    result.put(MethodProcessor.nameFromNode(method, classNode),
                            new DirectTarget(namespace, body, isStatic, isPrivate, method.desc));
                }
            }
            classIndex++;
        }
        return result;
    }

    public static String header(Collection<DirectTarget> targets) {
        StringBuilder out = new StringBuilder();
        out.append("#pragma once\n");
        out.append("#include \"native_jvm.hpp\"\n\n");
        String namespace = null;
        for (DirectTarget target : targets) {
            if (!target.namespace.equals(namespace)) {
                if (namespace != null) {
                    out.append("}\n\n");
                }
                namespace = target.namespace;
                out.append("namespace ").append(namespace).append(" {\n\n");
            }
            Type ret = Type.getReturnType(target.desc);
            out.append("    ").append(MethodProcessor.CPP_TYPES[ret.getSort()]).append(" JNICALL ")
                    .append(target.bodyName).append("(")
                    .append(MethodProcessor.jniParameterList(false, target.isStatic, Type.getArgumentTypes(target.desc)))
                    .append(");\n");
        }
        if (namespace != null) {
            out.append("\n}\n");
        }
        return out.toString();
    }

    private static byte[] read(JarFile jar, JarEntry entry) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (InputStream in = jar.getInputStream(entry)) {
            Util.transfer(in, baos);
        }
        return baos.toByteArray();
    }
}
