package by.radioegor146;

import by.radioegor146.instructions.*;
import by.radioegor146.special.ClInitSpecialMethodProcessor;
import by.radioegor146.special.DefaultSpecialMethodProcessor;
import by.radioegor146.special.SpecialMethodProcessor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Field;
import java.util.*;
import java.util.stream.Collectors;

public class MethodProcessor {

    public static final Map<Integer, String> INSTRUCTIONS = new HashMap<>();

    static {
        try {
            for (Field f : Opcodes.class.getFields()) {
                INSTRUCTIONS.put((int) f.get(null), f.getName());
            }
        } catch (IllegalArgumentException | IllegalAccessException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static final String[] CPP_TYPES = {
            "void", // 0
            "jboolean", // 1
            "jchar", // 2
            "jbyte", // 3
            "jshort", // 4
            "jint", // 5
            "jfloat", // 6
            "jlong", // 7
            "jdouble", // 8
            "jarray", // 9
            "jobject", // 10
            "jobject" // 11
    };

    public static final int[] TYPE_TO_STACK = {
            1, 1, 1, 1, 1, 1, 1, 2, 2, 0, 0, 0
    };

    public static final int[] STACK_TO_STACK = {
            1, 1, 1, 2, 2, 0, 0, 0, 0
    };

    private final NativeObfuscator obfuscator;
    private final InstructionHandlerContainer<?>[] handlers;

    public MethodProcessor(NativeObfuscator obfuscator) {
        this.obfuscator = obfuscator;

        handlers = new InstructionHandlerContainer[16];
        addHandler(AbstractInsnNode.INSN, new InsnHandler(), InsnNode.class);
        addHandler(AbstractInsnNode.INT_INSN, new IntHandler(), IntInsnNode.class);
        addHandler(AbstractInsnNode.VAR_INSN, new VarHandler(), VarInsnNode.class);
        addHandler(AbstractInsnNode.TYPE_INSN, new TypeHandler(), TypeInsnNode.class);
        addHandler(AbstractInsnNode.FIELD_INSN, new FieldHandler(), FieldInsnNode.class);
        addHandler(AbstractInsnNode.METHOD_INSN, new MethodHandler(), MethodInsnNode.class);
        addHandler(AbstractInsnNode.INVOKE_DYNAMIC_INSN, new InvokeDynamicHandler(), InvokeDynamicInsnNode.class);
        addHandler(AbstractInsnNode.JUMP_INSN, new JumpHandler(), JumpInsnNode.class);
        addHandler(AbstractInsnNode.LABEL, new LabelHandler(), LabelNode.class);
        addHandler(AbstractInsnNode.LDC_INSN, new LdcHandler(), LdcInsnNode.class);
        addHandler(AbstractInsnNode.IINC_INSN, new IincHandler(), IincInsnNode.class);
        addHandler(AbstractInsnNode.TABLESWITCH_INSN, new TableSwitchHandler(), TableSwitchInsnNode.class);
        addHandler(AbstractInsnNode.LOOKUPSWITCH_INSN, new LookupSwitchHandler(), LookupSwitchInsnNode.class);
        addHandler(AbstractInsnNode.MULTIANEWARRAY_INSN, new MultiANewArrayHandler(), MultiANewArrayInsnNode.class);
        addHandler(AbstractInsnNode.FRAME, new FrameHandler(), FrameNode.class);
        addHandler(AbstractInsnNode.LINE, new LineNumberHandler(), LineNumberNode.class);
    }

    private <T extends AbstractInsnNode> void addHandler(int id, InstructionTypeHandler<T> handler, Class<T> instructionClass) {
        handlers[id] = new InstructionHandlerContainer<>(handler, instructionClass);
    }

    private SpecialMethodProcessor getSpecialMethodProcessor(String name) {
        switch (name) {
            case "<init>":
                return null;
            case "<clinit>":
                return new ClInitSpecialMethodProcessor();
            default:
                return new DefaultSpecialMethodProcessor();
        }
    }

    public static boolean shouldProcess(MethodNode method) {
        return !Util.getFlag(method.access, Opcodes.ACC_ABSTRACT) &&
                !Util.getFlag(method.access, Opcodes.ACC_NATIVE) &&
                !method.name.equals("<init>");
    }

    private static String classLookupRef(MethodContext context, String desc) {
        String lookup = desc;
        if (!lookup.startsWith("[") && lookup.startsWith("L") && lookup.endsWith(";")) {
            lookup = lookup.substring(1, lookup.length() - 1);
        }
        if (!lookup.startsWith("[")) {
            lookup = lookup.replace('/', '.');
        }
        return context.getCachedStrings().getPointer(lookup);
    }

    public static void declareClassUse(MethodContext context, int classId) {
        if (context.declaredClassUses.add(classId)) {
            insertDecl(context, String.format("jclass ccuse_%d = nullptr;\n", classId));
        }
    }

    public static void declareFieldUse(MethodContext context) {
        if (!context.declaredFieldUse) {
            insertDecl(context, "jfieldID f_use = nullptr;\n");
            context.declaredFieldUse = true;
        }
    }

    public static void declareMethodUse(MethodContext context) {
        if (!context.declaredMethodUse) {
            insertDecl(context, "jmethodID m_use = nullptr;\n");
            context.declaredMethodUse = true;
        }
    }

    public static void insertDecl(MethodContext context, String decl) {
        if (context.declInsertAt >= 0) {
            String line = "    " + decl;
            context.output.insert(context.declInsertAt, line);
            context.declInsertAt += line.length();
        } else {
            context.output.append(decl);
        }
    }

    public static void emitClassResolve(MethodContext context, int classId, String desc, String onException) {
        declareClassUse(context, classId);
        boolean isStatic = Util.getFlag(context.method.access, Opcodes.ACC_STATIC);
        String receiver = isStatic ? "nullptr" : "obj";
        String slot = isStatic ? "nullptr" : "&receiver_loader";
        context.output.append(String.format(
                "if (cache_classes && cclasses[%1$d]) ccuse_%1$d = cclasses[%1$d]; " +
                        "if (!ccuse_%1$d) { " +
                        "if (cache_classes) cclasses_mtx[%1$d].lock(); " +
                        "if (!cache_classes || !cclasses[%1$d]) { " +
                        "bool cacheable = false; " +
                        "if (jclass clazz = utils::resolve_class(env, &cdefining_head, classloader, %2$s, %3$s, %4$s, &cacheable)) { " +
                        "if (cacheable && cache_classes) { cclasses[%1$d] = (jclass) env->NewGlobalRef(clazz); env->DeleteLocalRef(clazz); ccuse_%1$d = cclasses[%1$d]; } " +
                        "else { ccuse_%1$d = clazz; } " +
                        "} } else { ccuse_%1$d = cclasses[%1$d]; } " +
                        "if (cache_classes) cclasses_mtx[%1$d].unlock(); %5$s } ",
                classId,
                classLookupRef(context, desc),
                receiver,
                slot,
                onException));
    }

    public void processMethod(MethodContext context) {
        MethodNode method = context.method;
        StringBuilder output = context.output;

        SpecialMethodProcessor specialMethodProcessor = getSpecialMethodProcessor(method.name);

        if (specialMethodProcessor == null) {
            throw new RuntimeException(String.format("Could not find special method processor for %s", method.name));
        }

        output.append("// ").append(Util.escapeCommentString(method.name)).append(Util.escapeCommentString(method.desc)).append("\n");

        String methodName = Util.cppSymbol(specialMethodProcessor.preProcess(context));
        context.cppNativeMethodName = methodName;
        String bodyName = methodName + "_body";

        boolean isStatic = Util.getFlag(method.access, Opcodes.ACC_STATIC);
        context.ret = Type.getReturnType(method.desc);
        Type[] args = Type.getArgumentTypes(method.desc);

        context.argTypes = new ArrayList<>(Arrays.asList(args));
        if (!isStatic) {
            context.argTypes.add(0, Type.getType(Object.class));
        }

        boolean proxy = context.proxyMethod != null;
        if (proxy) {
            context.nativeMethod = context.proxyMethod.getMethodNode();
            context.nativeMethod.access |= Opcodes.ACC_NATIVE;
        } else {
            context.nativeMethods.append(String.format("            { %s, %s, (void *)&%s },\n",
                    obfuscator.getStringPool().get(context.method.name),
                    obfuscator.getStringPool().get(method.desc), methodName));
        }

        ArrayList<String> argNames = new ArrayList<>();
        if (!isStatic) argNames.add("obj");
        for (int i = 0; i < args.length; i++) {
            argNames.add("arg" + i);
        }

        String parameters = jniParameterList(proxy, isStatic, args);
        String arguments = jniArgumentList(proxy, isStatic, args.length);
        String returnType = CPP_TYPES[context.ret.getSort()];
        String returnZero = context.ret.getSort() == Type.VOID ? "return;" : String.format("return (%s) 0;", returnType);

        output.append(String.format("%s JNICALL %s(%s);\n", returnType, bodyName, parameters));
        output.append(String.format("%s JNICALL %s(%s) {\n", returnType, methodName, parameters));
        if (proxy) {
            output.append("    env->DeleteLocalRef(ignored_hidden);\n");
        }
        if (!isStatic) {
            output.append("    if (obj == nullptr) { utils::throw_re(env, \"java/lang/NullPointerException\", \"null receiver\", 0); ")
                    .append(returnZero).append(" }\n");
        }
        if (context.ret.getSort() == Type.VOID) {
            output.append("    ").append(bodyName).append("(").append(arguments).append(");\n");
            output.append("    return;\n");
        } else {
            output.append("    return ").append(bodyName).append("(").append(arguments).append(");\n");
        }
        output.append("}\n\n");

        output.append(String.format("%s JNICALL %s(%s) {\n", returnType, bodyName, parameters));
        if (!isStatic) {
            output.append("    jobject receiver_loader = nullptr;\n");
        }
        output.append("    bool cache_classes = false;\n");
        output.append("    jobject classloader = nullptr;\n");
        if (!isStatic) {
            output.append("    jclass clazz = nullptr;\n");
        }
        output.append("    if (utils::defining_binding* binding = utils::single_defining_binding(&cdefining_head)) {\n");
        output.append("        classloader = binding->loader;\n");
        output.append("        cache_classes = true;\n");
        if (!isStatic) {
            output.append("        clazz = binding->clazz;\n");
        }
        output.append("    } else {\n");
        if (isStatic) {
            output.append("        classloader = utils::get_classloader_from_class(env, clazz);\n");
            output.append("        if (env->ExceptionCheck()) { ").append(returnZero).append(" }\n");
        } else {
            output.append("        jclass runtime_clazz = utils::get_class_from_object(env, obj);\n");
            output.append("        if (env->ExceptionCheck()) { ").append(returnZero).append(" }\n");
            output.append("        classloader = utils::get_classloader_from_class(env, runtime_clazz);\n");
            output.append("        if (env->ExceptionCheck()) { ").append(returnZero).append(" }\n");
            output.append("        env->DeleteLocalRef(runtime_clazz);\n");
            output.append("        if (classloader == nullptr) { env->FatalError(")
                    .append(context.getStringPool().get("classloader == null")).append("); ").append(returnZero).append(" }\n");
            output.append("        clazz = utils::find_class_wo_static(env, classloader, ")
                    .append(context.getCachedStrings().getPointer(context.clazz.name.replace('/', '.')))
                    .append(");\n");
            output.append("        if (env->ExceptionCheck()) { ").append(returnZero).append(" }\n");
        }
        output.append("    }\n");
        output.append("    if (classloader == nullptr) { env->FatalError(")
                .append(context.getStringPool().get("classloader == null")).append("); ").append(returnZero).append(" }\n");
        output.append("\n");
        output.append("    jobject lookup = nullptr;\n");

        if (method.tryCatchBlocks != null) {
            for (TryCatchBlockNode tryCatch : method.tryCatchBlocks) {
                context.getLabelPool().getName(tryCatch.start.getLabel());
                context.getLabelPool().getName(tryCatch.end.getLabel());
                context.getLabelPool().getName(tryCatch.handler.getLabel());
            }
            Set<String> classesForTryCatches = method.tryCatchBlocks.stream().filter((tryCatchBlock) -> (tryCatchBlock.type != null)).map(x -> x.type)
                    .collect(Collectors.toSet());
            classesForTryCatches.forEach((clazz) -> {
                int classId = context.getCachedClasses().getId(clazz);

                context.output.append(String.format("    // try-catch-class %s\n    ", Util.escapeCommentString(clazz)));
                emitClassResolve(context, classId, clazz, String.format("if (env->ExceptionCheck()) { return (%s) 0; }", CPP_TYPES[context.ret.getSort()]));
                context.output.append("\n");
            });
        }

        if (method.maxStack > 0) {
            output.append("    jvalue ");
            for (int i = 0; i < method.maxStack; i++) {
                output.append(String.format("cstack%s = {}", i));
                if (i != method.maxStack - 1) {
                    output.append(", ");
                }
            }
            output.append(";\n");
        }

        if (method.maxLocals > 0) {
            output.append("    jvalue ");
            for (int i = 0; i < method.maxLocals; i++) {
                output.append(String.format("clocal%s = {}", i));
                if (i != method.maxLocals - 1) {
                    output.append(", ");
                }
            }
            output.append(";\n");
        }

        output.append("    std::vector<jobject> local_refs;\n");
        output.append("\n");

        int localIndex = 0;
        for (int i = 0; i < context.argTypes.size(); ++i) {
            Type current = context.argTypes.get(i);
            output.append("    ").append(obfuscator.getSnippets().getSnippet(
                    "LOCAL_LOAD_ARG_" + current.getSort(), Util.createMap(
                            "index", localIndex,
                            "arg", argNames.get(i),
                            "rettype", CPP_TYPES[context.ret.getSort()]
                    ))).append("\n");
            localIndex += current.getSize();
        }
        output.append("\n");

        context.argTypes.forEach(t -> context.locals.add(TYPE_TO_STACK[t.getSort()]));

        context.arrayPlan = PrimitiveArrays.analyze(method, context.clazz.name);
        context.stackPointer = 0;
        context.declInsertAt = output.length();
        context.arrayPlan.emitDecls(context);

        for (int instruction = 0; instruction < method.instructions.size(); ++instruction) {
            AbstractInsnNode node = method.instructions.get(instruction);
            String prefix = context.arrayPlan.prefix(context, node);
            if (prefix != null) {
                output.append("    ").append(prefix).append("\n");
            }
            if (node instanceof LabelNode) {
                String enter = context.arrayPlan.enter(context, (LabelNode) node);
                if (enter != null) {
                    output.append(enter);
                }
            }
            context.output.append("    // ").append(Util.escapeCommentString(handlers[node.getType()]
                    .insnToString(context, node))).append("; Stack: ").append(context.stackPointer).append("\n");
            String replacement = context.arrayPlan.replacement(context, node);
            if (replacement != null) {
                output.append("    ").append(replacement).append("\n");
            } else {
                handlers[node.getType()].accept(context, node);
            }
            context.stackPointer = handlers[node.getType()].getNewStackPointer(node, context.stackPointer);
            context.output.append("    // New stack: ").append(context.stackPointer).append("\n");
        }

        String endRelease = context.arrayPlan.endPrefix(context);
        if (endRelease != null) {
            output.append("    ").append(endRelease).append("\n");
        }
        output.append(String.format("    return (%s) 0;\n", CPP_TYPES[context.ret.getSort()]));
        context.arrayPlan.emitStubs(output);

        boolean hasAddedNewBlocks = true;

        Set<CatchesBlock> proceedBlocks = new HashSet<>();

        while (hasAddedNewBlocks) {
            hasAddedNewBlocks = false;
            for (CatchesBlock catchBlock : new ArrayList<>(context.catches.keySet())) {
                if (proceedBlocks.contains(catchBlock)) {
                    continue;
                }
                proceedBlocks.add(catchBlock);
                output.append("    ").append(context.catches.get(catchBlock)).append(": ");
                CatchesBlock.CatchBlock currentCatchBlock = catchBlock.getCatches().get(0);
                if (currentCatchBlock.getClazz() == null) {
                    output.append(context.getSnippets().getSnippet("TRYCATCH_ANY_L", Util.createMap(
                            "handler_block", context.getLabelPool().getName(currentCatchBlock.getHandler().getLabel())
                    )));
                    output.append("\n");
                    continue;
                }
                output.append(context.getSnippets().getSnippet("TRYCATCH_CHECK_STACK", Util.createMap(
                        "exception_class_ptr", context.getCachedClasses().getPointer(currentCatchBlock.getClazz()),
                        "handler_block", context.getLabelPool().getName(currentCatchBlock.getHandler().getLabel())
                )));
                output.append("\n");
                if (catchBlock.getCatches().size() == 1) {
                    output.append("    ");
                    output.append(context.getSnippets().getSnippet("TRYCATCH_END_STACK", Util.createMap(
                            "rettype", CPP_TYPES[context.ret.getSort()]
                    )));
                    output.append("\n");
                    continue;
                }
                CatchesBlock nextCatchesBlock = new CatchesBlock(catchBlock.getCatches().stream().skip(1).collect(Collectors.toList()));
                if (context.catches.get(nextCatchesBlock) == null) {
                    context.catches.put(nextCatchesBlock, String.format("L_CATCH_%d", context.catches.size()));
                    hasAddedNewBlocks = true;
                }
                output.append("    ");
                output.append(context.getSnippets().getSnippet("TRYCATCH_ANY_L", Util.createMap(
                        "handler_block", context.catches.get(nextCatchesBlock)
                )));
                output.append("\n");
            }
        }

        output.append("}\n\n");

        method.localVariables.clear();
        method.tryCatchBlocks.clear();

        specialMethodProcessor.postProcess(context);
    }

    public static String nameFromNode(MethodNode m, ClassNode cn) {
        return directKey(cn.name, m.name, m.desc);
    }

    public static String directKey(String owner, String name, String desc) {
        return owner + '#' + name + '!' + desc;
    }

    public static String jniParameterList(boolean proxy, boolean isStatic, Type[] javaArgs) {
        StringBuilder sb = new StringBuilder("JNIEnv *env");
        if (proxy) {
            sb.append(", jobject ignored_hidden");
        }
        sb.append(isStatic ? ", jclass clazz" : ", jobject obj");
        for (int i = 0; i < javaArgs.length; i++) {
            sb.append(", ").append(CPP_TYPES[javaArgs[i].getSort()]).append(" arg").append(i);
        }
        return sb.toString();
    }

    public static String jniArgumentList(boolean proxy, boolean isStatic, int javaArgCount) {
        StringBuilder sb = new StringBuilder("env");
        if (proxy) {
            sb.append(", ignored_hidden");
        }
        sb.append(isStatic ? ", clazz" : ", obj");
        for (int i = 0; i < javaArgCount; i++) {
            sb.append(", arg").append(i);
        }
        return sb.toString();
    }
}
