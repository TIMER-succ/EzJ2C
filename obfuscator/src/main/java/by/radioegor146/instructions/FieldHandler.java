package by.radioegor146.instructions;

import by.radioegor146.CachedFieldInfo;
import by.radioegor146.MethodContext;
import by.radioegor146.MethodProcessor;
import by.radioegor146.Util;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.FieldInsnNode;

public class FieldHandler extends GenericInstructionHandler<FieldInsnNode> {

    @Override
    protected void process(MethodContext context, FieldInsnNode node) {
        boolean isStatic = node.getOpcode() == Opcodes.GETSTATIC || node.getOpcode() == Opcodes.PUTSTATIC;
        CachedFieldInfo info = new CachedFieldInfo(node.owner, node.name, node.desc, isStatic);

        instructionName += "_" + Type.getType(node.desc).getSort();
        if (isStatic) {
            props.put("class_ptr", context.getCachedClasses().getPointer(node.owner));
        }

        int classId = context.getCachedClasses().getId(node.owner);
        int fieldId = context.getCachedFields().getId(info);
        props.put("fieldid", "f_use");
        MethodProcessor.declareFieldUse(context);
        MethodProcessor.declareClassUse(context, classId);
        String fieldKind = isStatic ? "Static" : "";
        String fastClass = isStatic ? String.format(" ccuse_%d = cclasses[%d];", classId, classId) : "";
        context.output.append(String.format("if (cache_classes && cfields[%d]) { f_use = cfields[%d];%s } else { ", fieldId, fieldId, fastClass));
        MethodProcessor.emitClassResolve(context, classId, node.owner, trimmedTryCatchBlock);
        context.output.append(String.format(
                "if (cache_classes && cclasses[%1$d]) { if (!cfields[%2$d]) { cfields[%2$d] = env->Get%3$sFieldID(ccuse_%1$d, %4$s, %5$s); %6$s } f_use = cfields[%2$d]; } else { f_use = env->Get%3$sFieldID(ccuse_%1$d, %4$s, %5$s); %6$s } } ",
                classId,
                fieldId,
                fieldKind,
                context.getStringPool().get(node.name),
                context.getStringPool().get(node.desc),
                trimmedTryCatchBlock));
    }

    @Override
    public String insnToString(MethodContext context, FieldInsnNode node) {
        return String.format("%s %s.%s %s", Util.getOpcodeString(node.getOpcode()), node.owner, node.name, node.desc);
    }

    @Override
    public int getNewStackPointer(FieldInsnNode node, int currentStackPointer) {
        if (node.getOpcode() == Opcodes.GETFIELD || node.getOpcode() == Opcodes.PUTFIELD) {
            currentStackPointer -= 1;
        }
        if (node.getOpcode() == Opcodes.GETSTATIC || node.getOpcode() == Opcodes.GETFIELD) {
            currentStackPointer += Type.getType(node.desc).getSize();
        }
        if (node.getOpcode() == Opcodes.PUTSTATIC || node.getOpcode() == Opcodes.PUTFIELD) {
            currentStackPointer -= Type.getType(node.desc).getSize();
        }
        return currentStackPointer;
    }
}
