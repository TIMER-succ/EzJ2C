package by.radioegor146.instructions;

import by.radioegor146.*;
import by.radioegor146.bytecode.PreprocessorUtils;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class MethodHandler extends GenericInstructionHandler<MethodInsnNode> {

    private static Type simplifyType(Type type) {
        switch (type.getSort()) {
            case Type.OBJECT:
            case Type.ARRAY:
                return Type.getObjectType("java/lang/Object");
            case Type.METHOD:
                throw new RuntimeException();
        }
        return type;
    }

    private static String simplifyDesc(String desc) {
        return Type.getMethodType(simplifyType(Type.getReturnType(desc)), Arrays.stream(Type.getArgumentTypes(desc))
                .map(MethodHandler::simplifyType).toArray(Type[]::new)).getDescriptor();
    }

    @Override
    protected void process(MethodContext context, MethodInsnNode node) {
        if (PreprocessorUtils.isLookupLocal(node)) {
            context.output.append("if (lookup == nullptr) { lookup = utils::get_lookup(env, clazz); ")
                    .append(trimmedTryCatchBlock).append(" } cstack").append(context.stackPointer).append(".l = lookup;");
            instructionName = null;
            return;
        }
        if (PreprocessorUtils.isClassLoaderLocal(node)) {
            context.output.append("cstack").append(context.stackPointer).append(".l = classloader;");
            instructionName = null;
            return;
        }
        if (PreprocessorUtils.isClassLocal(node)) {
            context.output.append("cstack").append(context.stackPointer).append(".l = clazz;");
            instructionName = null;
            return;
        }
        if (PreprocessorUtils.isLinkCallSiteMethod(node)) {
            Type returnType = Type.getReturnType(node.desc);
            Type[] args = Type.getArgumentTypes(node.desc);
            instructionName += "_" + returnType.getSort();

            StringBuilder argsBuilder = new StringBuilder();
            List<Integer> argOffsets = new ArrayList<>();

            int stackOffset = context.stackPointer;
            for (Type argType : args) {
                stackOffset -= argType.getSize();
            }
            int argumentOffset = stackOffset;
            for (Type argType : args) {
                argOffsets.add(argumentOffset);
                argumentOffset += argType.getSize();
            }

            for (int i = 0; i < argOffsets.size(); i++) {
                argsBuilder.append(", ").append(context.getSnippets().getSnippet("INVOKE_ARG_" + args[i].getSort(),
                        Util.createMap("index", argOffsets.get(i))));
            }

            context.output.append("cstack").append(stackOffset).append(".l = utils::link_call_site(env")
                    .append(argsBuilder).append("); local_refs.push_back(cstack").append(stackOffset).append(".l); ");
            context.output.append(trimmedTryCatchBlock);
            instructionName = null;
            return;
        }
        if (PreprocessorUtils.isInvokeReverse(node)) {
            // stack - args, mh
            String methodDesc = simplifyDesc(node.desc);
            Type[] methodArguments = Type.getArgumentTypes(methodDesc);
            methodArguments[methodArguments.length - 1] = Type.getObjectType("java/lang/invoke/MethodHandle");
            methodDesc = Type.getMethodDescriptor(Type.getReturnType(methodDesc), methodArguments);
            String mhDesc = simplifyDesc(Type.getMethodType(Type.getReturnType(node.desc),
                    Util.reverse(Util.reverse(Arrays.stream(Type.getArgumentTypes(node.desc)))
                            .skip(1)).toArray(Type[]::new)).getDescriptor());

            HiddenMethodsPool.HiddenMethod hiddenMethod = context.obfuscator.getHiddenMethodsPool()
                    .getMethod("invokereverse", methodDesc, method -> {
                        method.visibleAnnotations = new ArrayList<>();
                        method.visibleAnnotations.add(new AnnotationNode("Ljava/lang/invoke/LambdaForm$Hidden;"));
                        method.visibleAnnotations.add(new AnnotationNode("Ljdk/internal/vm/annotation/Hidden;"));
                        int methodHandleIndex = 0;
                        for (Type argument : Type.getArgumentTypes(mhDesc)) {
                            methodHandleIndex += argument.getSize();
                        }
                        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, methodHandleIndex));
                        int index = 0;
                        for (Type argument : Type.getArgumentTypes(mhDesc)) {
                            method.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), index));
                            index += argument.getSize();
                        }
                        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "java/lang/invoke/MethodHandle", "invoke", mhDesc));
                        method.instructions.add(new InsnNode(Type.getReturnType(mhDesc).getOpcode(Opcodes.IRETURN)));
                    });

            node = (MethodInsnNode) node.clone(null);
            node.name = hiddenMethod.getMethodNode().name;
            node.owner = hiddenMethod.getClassNode().name;
            node.desc = hiddenMethod.getMethodNode().desc;
            node.setOpcode(Opcodes.INVOKESTATIC);
        }
        if (node.owner.equals("java/lang/invoke/MethodHandle") &&
                (node.name.equals("invokeExact") || node.name.equals("invoke")) &&
                node.getOpcode() == Opcodes.INVOKEVIRTUAL) {
            // stack - mh, args
            String methodDesc = simplifyDesc(Type.getMethodType(Type.getReturnType(node.desc),
                    Stream.concat(Arrays.stream(new Type[]{
                            Type.getObjectType("java/lang/invoke/MethodHandle")
                    }), Arrays.stream(Type.getArgumentTypes(node.desc))).toArray(Type[]::new)).getDescriptor());
            Type[] methodArguments = Type.getArgumentTypes(methodDesc);
            methodArguments[0] = Type.getObjectType("java/lang/invoke/MethodHandle");
            methodDesc = Type.getMethodDescriptor(Type.getReturnType(methodDesc), methodArguments);
            String mhDesc = simplifyDesc(node.desc);

            HiddenMethodsPool.HiddenMethod hiddenMethod = context.obfuscator.getHiddenMethodsPool()
                    .getMethod("mhinvoke", methodDesc, method -> {
                        method.visibleAnnotations = new ArrayList<>();
                        method.visibleAnnotations.add(new AnnotationNode("Ljava/lang/invoke/LambdaForm$Hidden;"));
                        method.visibleAnnotations.add(new AnnotationNode("Ljdk/internal/vm/annotation/Hidden;"));
                        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        int index = 1;
                        for (Type argument : Type.getArgumentTypes(mhDesc)) {
                            method.instructions.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), index));
                            index += argument.getSize();
                        }
                        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "java/lang/invoke/MethodHandle", "invoke", mhDesc));
                        method.instructions.add(new InsnNode(Type.getReturnType(mhDesc).getOpcode(Opcodes.IRETURN)));
                    });

            node = (MethodInsnNode) node.clone(null);
            node.name = hiddenMethod.getMethodNode().name;
            node.owner = hiddenMethod.getClassNode().name;
            node.desc = hiddenMethod.getMethodNode().desc;
            node.setOpcode(Opcodes.INVOKESTATIC);
        }

        if (tryInline(context, node)) {
            return;
        }

        if (tryDirect(context, node)) {
            return;
        }

        Type returnType = Type.getReturnType(node.desc);
        Type[] args = Type.getArgumentTypes(node.desc);
        instructionName += "_" + returnType.getSort();

        List<Integer> argOffsets = new ArrayList<>();

        int stackOffset = context.stackPointer;
        for (Type argType : args) {
            stackOffset -= argType.getSize();
        }
        int argumentOffset = stackOffset;
        for (Type argType : args) {
            argOffsets.add(argumentOffset);
            argumentOffset += argType.getSize();
        }

        boolean isStatic = node.getOpcode() == Opcodes.INVOKESTATIC;
        int objectOffset = isStatic ? 0 : 1;

        if (args.length == 0) {
            props.put("args", ", nullptr");
        } else {
            props.put("args", ", __nargs");
            afterSnippet = "}";
        }

        props.put("objectstackindex", String.valueOf(stackOffset - objectOffset));
        props.put("returnstackindex", String.valueOf(stackOffset - objectOffset));

        if (isStatic || node.getOpcode() == Opcodes.INVOKESPECIAL) {
            props.put("class_ptr", context.getCachedClasses().getPointer(node.owner));
        }

        int classId = context.getCachedClasses().getId(node.owner);
        CachedMethodInfo methodInfo = new CachedMethodInfo(node.owner, node.name, node.desc, isStatic);
        int methodId = context.getCachedMethods().getId(methodInfo);
        props.put("methodid", "m_use");
        MethodProcessor.declareMethodUse(context);
        MethodProcessor.declareClassUse(context, classId);
        boolean idOnly = node.getOpcode() == Opcodes.INVOKEVIRTUAL || node.getOpcode() == Opcodes.INVOKEINTERFACE;
        String fastClass = idOnly ? "" : String.format(" ccuse_%d = cclasses[%d];", classId, classId);
        context.output.append(String.format("if (cache_classes && cmethods[%d]) { m_use = cmethods[%d];%s } else { ", methodId, methodId, fastClass));
        MethodProcessor.emitClassResolve(context, classId, node.owner, trimmedTryCatchBlock);
        context.output.append(String.format(
                "if (cache_classes && cclasses[%1$d]) { if (!cmethods[%2$d]) { cmethods[%2$d] = env->Get%3$sMethodID(ccuse_%1$d, %4$s, %5$s); %6$s } m_use = cmethods[%2$d]; } else { m_use = env->Get%3$sMethodID(ccuse_%1$d, %4$s, %5$s); %6$s } } ",
                classId,
                methodId,
                isStatic ? "Static" : "",
                context.getStringPool().get(node.name),
                context.getStringPool().get(node.desc),
                trimmedTryCatchBlock));

        if (args.length > 0) {
            context.output.append("{ jvalue __nargs[").append(args.length).append("];");
            for (int i = 0; i < args.length; i++) {
                context.output.append(" __nargs[").append(i).append("] = cstack").append(argOffsets.get(i)).append(";");
            }
            context.output.append(" ");
        }
    }

    private boolean tryDirect(MethodContext context, MethodInsnNode node) {
        int opcode = node.getOpcode();
        if ("<init>".equals(node.name)) {
            return false;
        }
        boolean isStatic = opcode == Opcodes.INVOKESTATIC;
        if (!isStatic && opcode != Opcodes.INVOKESPECIAL && opcode != Opcodes.INVOKEVIRTUAL) {
            return false;
        }
        DirectTarget target = context.obfuscator.directTarget(MethodProcessor.directKey(node.owner, node.name, node.desc));
        if (target == null) {
            return false;
        }
        if (opcode == Opcodes.INVOKEVIRTUAL && !target.isPrivate) {
            return false;
        }
        if (isStatic != target.isStatic) {
            return false;
        }

        Type returnType = Type.getReturnType(node.desc);
        Type[] args = Type.getArgumentTypes(node.desc);
        List<Integer> argOffsets = new ArrayList<>();
        int stackOffset = context.stackPointer;
        for (Type argType : args) {
            stackOffset -= argType.getSize();
        }
        int argumentOffset = stackOffset;
        for (Type argType : args) {
            argOffsets.add(argumentOffset);
            argumentOffset += argType.getSize();
        }

        int classId = context.getCachedClasses().getId(node.owner);
        MethodProcessor.emitClassResolve(context, classId, node.owner, trimmedTryCatchBlock);
        int methodId = context.getCachedMethods().getId(new CachedMethodInfo(node.owner, node.name, node.desc, isStatic));
        MethodProcessor.declareMethodUse(context);
        context.output.append(String.format(
                "if (cache_classes && cclasses[%1$d]) { if (!cmethods[%2$d]) { cmethods[%2$d] = env->Get%3$sMethodID(ccuse_%1$d, %4$s, %5$s); %6$s } m_use = cmethods[%2$d]; } else { m_use = env->Get%3$sMethodID(ccuse_%1$d, %4$s, %5$s); %6$s } ",
                classId,
                methodId,
                isStatic ? "Static" : "",
                context.getStringPool().get(node.name),
                context.getStringPool().get(node.desc),
                trimmedTryCatchBlock));
        String returnZero = context.ret.getSort() == Type.VOID ? "return;" : "return (" + MethodProcessor.CPP_TYPES[context.ret.getSort()] + ") 0;";
        context.output.append("if (m_use != nullptr) { ");

        StringBuilder invocation = new StringBuilder();
        invocation.append(target.qualifiedBody()).append("(env, ");
        if (isStatic) {
            invocation.append("ccuse_").append(classId);
        } else {
            invocation.append("cstack").append(stackOffset - 1).append(".l");
        }
        for (int i = 0; i < args.length; i++) {
            invocation.append(", ").append(stackArg(args[i].getSort(), argOffsets.get(i)));
        }
        invocation.append(")");

        int slot = stackOffset - (isStatic ? 0 : 1);
        if (!isStatic) {
            context.output.append("if (cstack").append(stackOffset - 1).append(".l == nullptr) utils::throw_re(env, \"java/lang/NullPointerException\", \"null receiver\", ")
                    .append(context.line).append("); else ");
        }
        context.output.append("{ if (env->PushLocalFrame(64) < 0) { ").append(trimmedTryCatchBlock).append(" } ");
        int sort = returnType.getSort();
        if (sort == Type.VOID) {
            context.output.append(invocation).append("; env->PopLocalFrame(nullptr); ");
        } else if (sort == Type.ARRAY || sort == Type.OBJECT) {
            context.output.append("jobject __direct = ").append(invocation).append("; ");
            context.output.append("if (env->ExceptionCheck()) { env->PopLocalFrame(nullptr); ").append(trimmedTryCatchBlock).append(" } ");
            context.output.append("cstack").append(slot).append(".l = env->PopLocalFrame(__direct); local_refs.push_back(cstack").append(slot).append(".l); ");
        } else {
            String field = sort == Type.FLOAT ? ".f" : sort == Type.LONG ? ".j" : sort == Type.DOUBLE ? ".d" : ".i";
            String cast = sort == Type.INT || sort == Type.FLOAT || sort == Type.LONG || sort == Type.DOUBLE ? "" : "(jint) ";
            context.output.append(MethodProcessor.CPP_TYPES[sort]).append(" __direct = ").append(invocation).append("; env->PopLocalFrame(nullptr); ");
            context.output.append("cstack").append(slot).append(field).append(" = ").append(cast).append("__direct; ");
        }
        context.output.append("} } else { ").append(returnZero).append(" } ");
        context.output.append(trimmedTryCatchBlock);
        instructionName = null;
        return true;
    }

    private static String stackArg(int sort, int index) {
        String slot = "cstack" + index;
        switch (sort) {
            case Type.BOOLEAN:
                return "(jboolean) " + slot + ".i";
            case Type.CHAR:
                return "(jchar) " + slot + ".i";
            case Type.BYTE:
                return "(jbyte) " + slot + ".i";
            case Type.SHORT:
                return "(jshort) " + slot + ".i";
            case Type.INT:
                return slot + ".i";
            case Type.FLOAT:
                return slot + ".f";
            case Type.LONG:
                return slot + ".j";
            case Type.DOUBLE:
                return slot + ".d";
            case Type.ARRAY:
                return "(jarray) " + slot + ".l";
            default:
                return slot + ".l";
        }
    }

    private boolean tryInline(MethodContext context, MethodInsnNode node) {
        if (!context.obfuscator.isInline()) {
            return false;
        }
        int opcode = node.getOpcode();
        if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKESPECIAL) {
            return false;
        }
        int stackOffset = context.stackPointer;
        for (Type argType : Type.getArgumentTypes(node.desc)) {
            stackOffset -= argType.getSize();
        }
        int objectIndex = stackOffset - 1;
        if ("getClass".equals(node.name) && "()Ljava/lang/Class;".equals(node.desc)) {
            emitChecked(context, objectIndex, "getClass",
                    "{ cstack" + objectIndex + ".l = env->GetObjectClass(cstack" + objectIndex + ".l); local_refs.push_back(cstack" + objectIndex + ".l); }");
            return true;
        }
        if (!"java/lang/String".equals(node.owner)) {
            return false;
        }
        if ("length".equals(node.name) && "()I".equals(node.desc)) {
            emitChecked(context, objectIndex, "String.length",
                    "cstack" + objectIndex + ".i = env->GetStringLength((jstring) cstack" + objectIndex + ".l);");
            return true;
        }
        if ("isEmpty".equals(node.name) && "()Z".equals(node.desc)) {
            emitChecked(context, objectIndex, "String.isEmpty",
                    "cstack" + objectIndex + ".i = env->GetStringLength((jstring) cstack" + objectIndex + ".l) == 0 ? 1 : 0;");
            return true;
        }
        if ("equals".equals(node.name) && "(Ljava/lang/Object;)Z".equals(node.desc)) {
            emitChecked(context, objectIndex, "String.equals",
                    "cstack" + objectIndex + ".i = (jint) utils::string_equals(env, (jstring) cstack" + objectIndex + ".l, cstack" + stackOffset + ".l);");
            return true;
        }
        return false;
    }

    private void emitChecked(MethodContext context, int objectIndex, String detail, String body) {
        context.output.append("if (cstack").append(objectIndex).append(".l == nullptr) utils::throw_re(env, \"java/lang/NullPointerException\", \"")
                .append(detail).append("\", ").append(context.line).append("); else ").append(body).append(" ");
        context.output.append(trimmedTryCatchBlock);
        instructionName = null;
    }

    @Override
    public String insnToString(MethodContext context, MethodInsnNode node) {
        return String.format("%s %s.%s%s", Util.getOpcodeString(node.getOpcode()), node.owner, node.name, node.desc);
    }

    @Override
    public int getNewStackPointer(MethodInsnNode node, int currentStackPointer) {
        if (node.getOpcode() != Opcodes.INVOKESTATIC) {
            currentStackPointer -= 1;
        }
        return currentStackPointer - Arrays.stream(Type.getArgumentTypes(node.desc)).mapToInt(Type::getSize).sum()
                + Type.getReturnType(node.desc).getSize();
    }
}
