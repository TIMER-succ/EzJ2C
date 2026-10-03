package by.radioegor146;

import by.radioegor146.instructions.GenericInstructionHandler;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

import java.util.*;

public final class PrimitiveArrays {

    private PrimitiveArrays() {
    }

    public static final class Plan {
        private final List<Pin> pins = new ArrayList<>();
        private final Map<LabelNode, List<Pin>> byHeader = new HashMap<>();
        private final Map<AbstractInsnNode, Pin> accessPin = new HashMap<>();
        private final Map<AbstractInsnNode, String> byteKind = new HashMap<>();
        private final Map<AbstractInsnNode, List<Pin>> fallthrough = new HashMap<>();
        private final List<Pin> endRelease = new ArrayList<>();
        private final List<Stub> stubs = new ArrayList<>();
        private final Map<LabelNode, String> enterNames = new HashMap<>();

        public String byteKind(AbstractInsnNode node) {
            return byteKind.get(node);
        }

        public void emitDecls(MethodContext context) {
            for (Pin pin : pins) {
                MethodProcessor.insertDecl(context, "jint pin_len_" + pin.id + " = 0;\n");
                MethodProcessor.insertDecl(context, elemType(pin.desc) + " *pin_data_" + pin.id + " = nullptr;\n");
                String mode = pin.write ? "0" : "JNI_ABORT";
                MethodProcessor.insertDecl(context, "auto pin_rel_" + pin.id + " = [&]() { if (pin_data_" + pin.id
                        + " != nullptr) { env->Release" + jniName(pin.desc) + "ArrayElements((" + jniArray(pin.desc)
                        + ") clocal" + pin.local + ".l, pin_data_" + pin.id + ", " + mode + "); pin_data_" + pin.id
                        + " = nullptr; } };\n");
            }
        }

        public String enter(MethodContext context, LabelNode label) {
            List<Pin> group = byHeader.get(label);
            if (group == null || group.isEmpty()) {
                return null;
            }
            String handler = GenericInstructionHandler.tryCatchHandler(context, triesAfter(context, label)).trim();
            StringBuilder sb = new StringBuilder();
            sb.append("    ").append(enterNames.get(label)).append(":\n");
            for (Pin pin : group) {
                String arr = "clocal" + pin.local + ".l";
                String cast = "(" + jniArray(pin.desc) + ") " + arr;
                sb.append("    if (").append(arr).append(" == nullptr) { utils::throw_re(env, ")
                        .append(context.getStringPool().get("java/lang/NullPointerException")).append(", ")
                        .append(context.getStringPool().get("pin npe")).append(", ")
                        .append(context.line).append("); ").append(handler).append(" }\n");
                sb.append("    pin_len_").append(pin.id).append(" = env->GetArrayLength(").append(cast).append(");\n");
                sb.append("    if (env->ExceptionCheck()) { ").append(handler).append(" }\n");
                sb.append("    pin_data_").append(pin.id).append(" = env->Get").append(jniName(pin.desc))
                        .append("ArrayElements(").append(cast).append(", nullptr);\n");
                sb.append("    if (pin_data_").append(pin.id).append(" == nullptr) { ").append(handler).append(" }\n");
            }
            return sb.toString();
        }

        public String prefix(MethodContext context, AbstractInsnNode node) {
            LinkedHashSet<Pin> rel = new LinkedHashSet<>();
            if (isReturn(node.getOpcode())) {
                for (Pin pin : pins) {
                    if (pin.body.contains(node)) {
                        rel.add(pin);
                    }
                }
            }
            List<Pin> extra = fallthrough.get(node);
            if (extra != null) {
                rel.addAll(extra);
            }
            if (rel.isEmpty()) {
                return null;
            }
            String handler = GenericInstructionHandler.tryCatchHandler(context, context.tryCatches).trim();
            return releaseText(rel) + handler + " ";
        }

        public String endPrefix(MethodContext context) {
            if (endRelease.isEmpty()) {
                return null;
            }
            String handler = GenericInstructionHandler.tryCatchHandler(context, context.tryCatches).trim();
            return releaseText(endRelease) + handler + " ";
        }

        public String replacement(MethodContext context, AbstractInsnNode node) {
            Pin pin = accessPin.get(node);
            if (pin == null) {
                return null;
            }
            int op = node.getOpcode();
            int sp = context.stackPointer;
            if (op == Opcodes.ARRAYLENGTH) {
                return "cstack" + (sp - 1) + ".i = pin_len_" + pin.id + ";";
            }
            int indexAt = indexSlot(op, sp);
            String handler = GenericInstructionHandler.tryCatchHandler(context, context.tryCatches).trim();
            StringBuilder sb = new StringBuilder();
            sb.append("{ jint pin_idx = cstack").append(indexAt).append(".i; if (pin_idx < 0 || pin_idx >= pin_len_")
                    .append(pin.id).append(") { ").append(releaseStmt(pin)).append("utils::throw_re(env, ")
                    .append(context.getStringPool().get("java/lang/ArrayIndexOutOfBoundsException")).append(", ")
                    .append(context.getStringPool().get("pin aioobe")).append(", ").append(context.line)
                    .append("); ").append(handler).append(" } ");
            if (isStore(op)) {
                sb.append(storeExpr(pin, valueSlot(op, sp)));
            } else {
                sb.append(loadExpr(pin, arraySlot(op, sp)));
            }
            sb.append(" }");
            return sb.toString();
        }

        public String jumpLabel(MethodContext context, JumpInsnNode jump) {
            LabelNode target = jump.label;
            List<Pin> group = byHeader.get(target);
            if (group != null && !group.get(0).body.contains(jump)) {
                return enterNames.get(target);
            }
            List<Pin> exiting = new ArrayList<>();
            for (Pin pin : pins) {
                if (pin.body.contains(jump) && !pin.body.contains(target)) {
                    exiting.add(pin);
                }
            }
            if (exiting.isEmpty()) {
                return context.getLabelPool().getName(target.getLabel());
            }
            Stub stub = new Stub();
            stub.name = "L_PIN_R" + stubs.size();
            stub.pins = exiting;
            stub.target = context.getLabelPool().getName(target.getLabel());
            stub.handler = GenericInstructionHandler.tryCatchHandler(context, context.tryCatches).trim();
            stubs.add(stub);
            return stub.name;
        }

        public void emitStubs(StringBuilder output) {
            for (Stub stub : stubs) {
                output.append("    ").append(stub.name).append(": ");
                output.append(releaseText(stub.pins));
                output.append(stub.handler).append(" goto ").append(stub.target).append(";\n");
            }
        }
    }

    public static Plan analyze(MethodNode method, String className) {
        boolean hasArray = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (isTrackedArrayOp(insn.getOpcode()) || insn.getOpcode() == Opcodes.BALOAD
                    || insn.getOpcode() == Opcodes.BASTORE || insn.getOpcode() == Opcodes.NEWARRAY) {
                hasArray = true;
                break;
            }
        }
        if (!hasArray || method.instructions.size() == 0) {
            return new Plan();
        }
        try {
            return analyzeImpl(method, className);
        } catch (AnalyzerException | RuntimeException ex) {
            return new Plan();
        }
    }

    private static Plan analyzeImpl(MethodNode method, String className) throws AnalyzerException {
        ArrInterpreter interpreter = new ArrInterpreter();
        Analyzer<Arr> analyzer = new Analyzer<>(interpreter);
        int access = method.access;
        method.access &= ~Opcodes.ACC_NATIVE;
        Frame<Arr>[] frames;
        try {
            frames = analyzer.analyze(className, method);
        } finally {
            method.access = access;
        }
        AbstractInsnNode[] insns = method.instructions.toArray();
        Plan plan = new Plan();
        for (int i = 0; i < insns.length; i++) {
            int op = insns[i].getOpcode();
            if ((op == Opcodes.BALOAD || op == Opcodes.BASTORE) && frames[i] != null) {
                Arr array = arrayOperand(frames[i], op);
                if (array != null && ("[B".equals(array.desc) || "[Z".equals(array.desc))) {
                    plan.byteKind.put(insns[i], array.desc);
                }
            }
        }

        Map<AbstractInsnNode, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < insns.length; i++) {
            indexOf.put(insns[i], i);
        }
        Map<AbstractInsnNode, List<AbstractInsnNode>> preds = new HashMap<>();
        for (AbstractInsnNode insn : insns) {
            for (AbstractInsnNode next : successors(insn)) {
                if (next == null || !indexOf.containsKey(next)) {
                    continue;
                }
                preds.computeIfAbsent(next, key -> new ArrayList<>()).add(insn);
            }
        }

        Map<LabelNode, Set<AbstractInsnNode>> loops = new LinkedHashMap<>();
        for (AbstractInsnNode insn : insns) {
            for (AbstractInsnNode next : successors(insn)) {
                if (!(next instanceof LabelNode) || !indexOf.containsKey(next)) {
                    continue;
                }
                if (indexOf.get(next) < indexOf.get(insn)) {
                    LabelNode header = (LabelNode) next;
                    Set<AbstractInsnNode> body = naturalLoop(header, insn, preds);
                    loops.computeIfAbsent(header, key -> new HashSet<>()).addAll(body);
                }
            }
        }

        List<Map.Entry<LabelNode, Set<AbstractInsnNode>>> ordered = new ArrayList<>(loops.entrySet());
        ordered.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        Set<AbstractInsnNode> claimed = new HashSet<>();
        int nextId = 0;
        for (Map.Entry<LabelNode, Set<AbstractInsnNode>> entry : ordered) {
            LabelNode header = entry.getKey();
            Set<AbstractInsnNode> body = entry.getValue();
            if (!loopAllowed(body) || !singleEntry(header, body, preds) || handlerInside(method, body)) {
                continue;
            }
            AbstractInsnNode prev = header.getPrevious();
            if (prev != null && body.contains(prev) && fallsThrough(prev)) {
                continue;
            }
            int headerIndex = indexOf.get(header);
            Frame<Arr> headerFrame = frames[headerIndex];
            if (headerFrame == null) {
                continue;
            }
            List<Pin> created = new ArrayList<>();
            Set<AbstractInsnNode> covered = new HashSet<>();
            for (int slot = 0; slot < headerFrame.getLocals(); slot++) {
                Arr local = headerFrame.getLocal(slot);
                if (local == null || !isPrimArray(local.desc)) {
                    continue;
                }
                if (stored(body, slot)) {
                    continue;
                }
                List<AbstractInsnNode> accesses = new ArrayList<>();
                boolean write = false;
                boolean ok = true;
                boolean overlap = false;
                for (AbstractInsnNode insn : body) {
                    int op = insn.getOpcode();
                    if (!isTrackedArrayOp(op)) {
                        continue;
                    }
                    Frame<Arr> frame = frames[indexOf.get(insn)];
                    if (frame == null) {
                        ok = false;
                        break;
                    }
                    Arr array = arrayOperand(frame, op);
                    if (array == null || array.origin != slot || !local.desc.equals(array.desc)) {
                        continue;
                    }
                    if (!opcodeMatches(op, local.desc)) {
                        ok = false;
                        break;
                    }
                    if (claimed.contains(insn)) {
                        overlap = true;
                    }
                    accesses.add(insn);
                    if (isStore(op)) {
                        write = true;
                    }
                }
                if (!ok || accesses.isEmpty()) {
                    continue;
                }
                if (overlap) {
                    for (AbstractInsnNode insn : accesses) {
                        if (claimed.contains(insn)) {
                            covered.add(insn);
                        }
                    }
                    continue;
                }
                Pin pin = new Pin();
                pin.id = nextId++;
                pin.local = slot;
                pin.desc = local.desc;
                pin.write = write;
                pin.body = body;
                pin.accesses = accesses;
                created.add(pin);
                covered.addAll(accesses);
            }
            boolean hole = false;
            for (AbstractInsnNode insn : body) {
                if (isTrackedArrayOp(insn.getOpcode()) && !covered.contains(insn) && !claimed.contains(insn)) {
                    hole = true;
                    break;
                }
            }
            if (hole) {
                continue;
            }
            if (created.isEmpty()) {
                continue;
            }
            String enter = "L_PIN_E" + created.get(0).id;
            plan.enterNames.put(header, enter);
            plan.byHeader.put(header, created);
            for (Pin pin : created) {
                plan.pins.add(pin);
                for (AbstractInsnNode insn : pin.accesses) {
                    plan.accessPin.put(insn, pin);
                    claimed.add(insn);
                }
            }
        }

        for (Pin pin : plan.pins) {
            for (AbstractInsnNode insn : pin.body) {
                if (!fallsThrough(insn)) {
                    continue;
                }
                AbstractInsnNode next = insn.getNext();
                if (next == null) {
                    plan.endRelease.add(pin);
                } else if (!pin.body.contains(next)) {
                    plan.fallthrough.computeIfAbsent(next, key -> new ArrayList<>()).add(pin);
                }
            }
        }
        return plan;
    }

    private static boolean loopAllowed(Set<AbstractInsnNode> body) {
        for (AbstractInsnNode insn : body) {
            if (!allowed(insn)) {
                return false;
            }
        }
        return true;
    }

    private static boolean singleEntry(LabelNode header, Set<AbstractInsnNode> body,
                                       Map<AbstractInsnNode, List<AbstractInsnNode>> preds) {
        for (AbstractInsnNode insn : body) {
            if (insn == header) {
                continue;
            }
            List<AbstractInsnNode> incoming = preds.get(insn);
            if (incoming == null) {
                continue;
            }
            for (AbstractInsnNode pred : incoming) {
                if (!body.contains(pred)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean handlerInside(MethodNode method, Set<AbstractInsnNode> body) {
        if (method.tryCatchBlocks == null) {
            return false;
        }
        for (TryCatchBlockNode block : method.tryCatchBlocks) {
            if (body.contains(block.handler)) {
                return true;
            }
        }
        return false;
    }

    private static boolean stored(Set<AbstractInsnNode> body, int slot) {
        for (AbstractInsnNode insn : body) {
            if (insn.getOpcode() == Opcodes.ASTORE && insn instanceof VarInsnNode
                    && ((VarInsnNode) insn).var == slot) {
                return true;
            }
        }
        return false;
    }

    private static Set<AbstractInsnNode> naturalLoop(LabelNode header, AbstractInsnNode tail,
                                                     Map<AbstractInsnNode, List<AbstractInsnNode>> preds) {
        Set<AbstractInsnNode> loop = new HashSet<>();
        ArrayDeque<AbstractInsnNode> work = new ArrayDeque<>();
        loop.add(header);
        if (loop.add(tail)) {
            work.push(tail);
        }
        while (!work.isEmpty()) {
            AbstractInsnNode cur = work.pop();
            List<AbstractInsnNode> incoming = preds.get(cur);
            if (incoming == null) {
                continue;
            }
            for (AbstractInsnNode pred : incoming) {
                if (loop.add(pred)) {
                    work.push(pred);
                }
            }
        }
        return loop;
    }

    private static List<AbstractInsnNode> successors(AbstractInsnNode insn) {
        List<AbstractInsnNode> next = new ArrayList<>();
        if (insn instanceof JumpInsnNode) {
            next.add(((JumpInsnNode) insn).label);
            if (insn.getOpcode() != Opcodes.GOTO && insn.getNext() != null) {
                next.add(insn.getNext());
            }
        } else if (insn instanceof TableSwitchInsnNode) {
            TableSwitchInsnNode table = (TableSwitchInsnNode) insn;
            next.add(table.dflt);
            next.addAll(table.labels);
        } else if (insn instanceof LookupSwitchInsnNode) {
            LookupSwitchInsnNode lookup = (LookupSwitchInsnNode) insn;
            next.add(lookup.dflt);
            next.addAll(lookup.labels);
        } else if (fallsThrough(insn) && insn.getNext() != null) {
            next.add(insn.getNext());
        }
        return next;
    }

    private static boolean fallsThrough(AbstractInsnNode insn) {
        int op = insn.getOpcode();
        if (op == Opcodes.GOTO || op == Opcodes.JSR || op == Opcodes.RET || op == Opcodes.ATHROW
                || op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH) {
            return false;
        }
        return op < Opcodes.IRETURN || op > Opcodes.RETURN;
    }

    private static boolean allowed(AbstractInsnNode insn) {
        int type = insn.getType();
        if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.LINE || type == AbstractInsnNode.FRAME) {
            return true;
        }
        if (insn instanceof LdcInsnNode) {
            Object cst = ((LdcInsnNode) insn).cst;
            return cst instanceof Integer || cst instanceof Long || cst instanceof Float
                    || cst instanceof Double || cst instanceof String;
        }
        switch (insn.getOpcode()) {
            case Opcodes.NOP:
            case Opcodes.ACONST_NULL:
            case Opcodes.ICONST_M1:
            case Opcodes.ICONST_0:
            case Opcodes.ICONST_1:
            case Opcodes.ICONST_2:
            case Opcodes.ICONST_3:
            case Opcodes.ICONST_4:
            case Opcodes.ICONST_5:
            case Opcodes.LCONST_0:
            case Opcodes.LCONST_1:
            case Opcodes.FCONST_0:
            case Opcodes.FCONST_1:
            case Opcodes.FCONST_2:
            case Opcodes.DCONST_0:
            case Opcodes.DCONST_1:
            case Opcodes.BIPUSH:
            case Opcodes.SIPUSH:
            case Opcodes.ILOAD:
            case Opcodes.LLOAD:
            case Opcodes.FLOAD:
            case Opcodes.DLOAD:
            case Opcodes.ALOAD:
            case Opcodes.IALOAD:
            case Opcodes.LALOAD:
            case Opcodes.FALOAD:
            case Opcodes.DALOAD:
            case Opcodes.BALOAD:
            case Opcodes.CALOAD:
            case Opcodes.SALOAD:
            case Opcodes.ISTORE:
            case Opcodes.LSTORE:
            case Opcodes.FSTORE:
            case Opcodes.DSTORE:
            case Opcodes.ASTORE:
            case Opcodes.IASTORE:
            case Opcodes.LASTORE:
            case Opcodes.FASTORE:
            case Opcodes.DASTORE:
            case Opcodes.BASTORE:
            case Opcodes.CASTORE:
            case Opcodes.SASTORE:
            case Opcodes.POP:
            case Opcodes.POP2:
            case Opcodes.DUP:
            case Opcodes.DUP_X1:
            case Opcodes.DUP_X2:
            case Opcodes.DUP2:
            case Opcodes.DUP2_X1:
            case Opcodes.DUP2_X2:
            case Opcodes.SWAP:
            case Opcodes.IADD:
            case Opcodes.LADD:
            case Opcodes.FADD:
            case Opcodes.DADD:
            case Opcodes.ISUB:
            case Opcodes.LSUB:
            case Opcodes.FSUB:
            case Opcodes.DSUB:
            case Opcodes.IMUL:
            case Opcodes.LMUL:
            case Opcodes.FMUL:
            case Opcodes.DMUL:
            case Opcodes.FDIV:
            case Opcodes.DDIV:
            case Opcodes.FREM:
            case Opcodes.DREM:
            case Opcodes.INEG:
            case Opcodes.LNEG:
            case Opcodes.FNEG:
            case Opcodes.DNEG:
            case Opcodes.ISHL:
            case Opcodes.LSHL:
            case Opcodes.ISHR:
            case Opcodes.LSHR:
            case Opcodes.IUSHR:
            case Opcodes.LUSHR:
            case Opcodes.IAND:
            case Opcodes.LAND:
            case Opcodes.IOR:
            case Opcodes.LOR:
            case Opcodes.IXOR:
            case Opcodes.LXOR:
            case Opcodes.IINC:
            case Opcodes.I2L:
            case Opcodes.I2F:
            case Opcodes.I2D:
            case Opcodes.L2I:
            case Opcodes.L2F:
            case Opcodes.L2D:
            case Opcodes.F2I:
            case Opcodes.F2L:
            case Opcodes.F2D:
            case Opcodes.D2I:
            case Opcodes.D2L:
            case Opcodes.D2F:
            case Opcodes.I2B:
            case Opcodes.I2C:
            case Opcodes.I2S:
            case Opcodes.LCMP:
            case Opcodes.FCMPL:
            case Opcodes.FCMPG:
            case Opcodes.DCMPL:
            case Opcodes.DCMPG:
            case Opcodes.IFEQ:
            case Opcodes.IFNE:
            case Opcodes.IFLT:
            case Opcodes.IFGE:
            case Opcodes.IFGT:
            case Opcodes.IFLE:
            case Opcodes.IF_ICMPEQ:
            case Opcodes.IF_ICMPNE:
            case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE:
            case Opcodes.IF_ICMPGT:
            case Opcodes.IF_ICMPLE:
            case Opcodes.IFNULL:
            case Opcodes.IFNONNULL:
            case Opcodes.GOTO:
            case Opcodes.IRETURN:
            case Opcodes.LRETURN:
            case Opcodes.FRETURN:
            case Opcodes.DRETURN:
            case Opcodes.ARETURN:
            case Opcodes.RETURN:
            case Opcodes.ARRAYLENGTH:
                return true;
            default:
                return false;
        }
    }

    private static boolean isTrackedArrayOp(int op) {
        switch (op) {
            case Opcodes.IALOAD:
            case Opcodes.LALOAD:
            case Opcodes.FALOAD:
            case Opcodes.DALOAD:
            case Opcodes.BALOAD:
            case Opcodes.CALOAD:
            case Opcodes.SALOAD:
            case Opcodes.IASTORE:
            case Opcodes.LASTORE:
            case Opcodes.FASTORE:
            case Opcodes.DASTORE:
            case Opcodes.BASTORE:
            case Opcodes.CASTORE:
            case Opcodes.SASTORE:
            case Opcodes.ARRAYLENGTH:
                return true;
            default:
                return false;
        }
    }

    private static boolean opcodeMatches(int op, String desc) {
        if (op == Opcodes.ARRAYLENGTH) {
            return true;
        }
        switch (desc) {
            case "[I":
                return op == Opcodes.IALOAD || op == Opcodes.IASTORE;
            case "[J":
                return op == Opcodes.LALOAD || op == Opcodes.LASTORE;
            case "[F":
                return op == Opcodes.FALOAD || op == Opcodes.FASTORE;
            case "[D":
                return op == Opcodes.DALOAD || op == Opcodes.DASTORE;
            case "[B":
            case "[Z":
                return op == Opcodes.BALOAD || op == Opcodes.BASTORE;
            case "[C":
                return op == Opcodes.CALOAD || op == Opcodes.CASTORE;
            case "[S":
                return op == Opcodes.SALOAD || op == Opcodes.SASTORE;
            default:
                return false;
        }
    }

    private static boolean isStore(int op) {
        switch (op) {
            case Opcodes.IASTORE:
            case Opcodes.LASTORE:
            case Opcodes.FASTORE:
            case Opcodes.DASTORE:
            case Opcodes.BASTORE:
            case Opcodes.CASTORE:
            case Opcodes.SASTORE:
                return true;
            default:
                return false;
        }
    }

    private static boolean isReturn(int op) {
        return op >= Opcodes.IRETURN && op <= Opcodes.RETURN;
    }

    private static boolean isPrimArray(String desc) {
        return desc != null && desc.length() == 2 && desc.charAt(0) == '['
                && "ZBCSIFJD".indexOf(desc.charAt(1)) >= 0;
    }

    private static Arr arrayOperand(Frame<Arr> frame, int op) {
        int at = arraySlot(op, frame.getStackSize());
        if (at < 0) {
            return null;
        }
        return frame.getStack(at);
    }

    private static int arraySlot(int op, int sp) {
        switch (op) {
            case Opcodes.IALOAD:
            case Opcodes.LALOAD:
            case Opcodes.FALOAD:
            case Opcodes.DALOAD:
            case Opcodes.BALOAD:
            case Opcodes.CALOAD:
            case Opcodes.SALOAD:
                return sp - 2;
            case Opcodes.IASTORE:
            case Opcodes.FASTORE:
            case Opcodes.BASTORE:
            case Opcodes.CASTORE:
            case Opcodes.SASTORE:
                return sp - 3;
            case Opcodes.LASTORE:
            case Opcodes.DASTORE:
                return sp - 4;
            case Opcodes.ARRAYLENGTH:
                return sp - 1;
            default:
                return -1;
        }
    }

    private static int indexSlot(int op, int sp) {
        switch (op) {
            case Opcodes.IALOAD:
            case Opcodes.LALOAD:
            case Opcodes.FALOAD:
            case Opcodes.DALOAD:
            case Opcodes.BALOAD:
            case Opcodes.CALOAD:
            case Opcodes.SALOAD:
                return sp - 1;
            case Opcodes.IASTORE:
            case Opcodes.FASTORE:
            case Opcodes.BASTORE:
            case Opcodes.CASTORE:
            case Opcodes.SASTORE:
                return sp - 2;
            case Opcodes.LASTORE:
            case Opcodes.DASTORE:
                return sp - 3;
            default:
                return -1;
        }
    }

    private static int valueSlot(int op, int sp) {
        if (op == Opcodes.LASTORE || op == Opcodes.DASTORE) {
            return sp - 2;
        }
        return sp - 1;
    }

    private static String elemType(String desc) {
        switch (desc) {
            case "[Z":
                return "jboolean";
            case "[B":
                return "jbyte";
            case "[C":
                return "jchar";
            case "[S":
                return "jshort";
            case "[I":
                return "jint";
            case "[J":
                return "jlong";
            case "[F":
                return "jfloat";
            case "[D":
                return "jdouble";
            default:
                return "jint";
        }
    }

    private static String jniArray(String desc) {
        switch (desc) {
            case "[Z":
                return "jbooleanArray";
            case "[B":
                return "jbyteArray";
            case "[C":
                return "jcharArray";
            case "[S":
                return "jshortArray";
            case "[I":
                return "jintArray";
            case "[J":
                return "jlongArray";
            case "[F":
                return "jfloatArray";
            case "[D":
                return "jdoubleArray";
            default:
                return "jarray";
        }
    }

    private static String jniName(String desc) {
        switch (desc) {
            case "[Z":
                return "Boolean";
            case "[B":
                return "Byte";
            case "[C":
                return "Char";
            case "[S":
                return "Short";
            case "[I":
                return "Int";
            case "[J":
                return "Long";
            case "[F":
                return "Float";
            case "[D":
                return "Double";
            default:
                return "Int";
        }
    }

    private static String releaseStmt(Pin pin) {
        return "pin_rel_" + pin.id + "(); ";
    }

    private static String releaseText(Collection<Pin> pins) {
        List<Pin> sorted = new ArrayList<>(pins);
        sorted.sort(Comparator.comparingInt(pin -> pin.body.size()));
        StringBuilder sb = new StringBuilder();
        Set<Integer> seen = new HashSet<>();
        for (Pin pin : sorted) {
            if (seen.add(pin.id)) {
                sb.append(releaseStmt(pin));
            }
        }
        return sb.toString();
    }

    private static String loadExpr(Pin pin, int slot) {
        String read = "pin_data_" + pin.id + "[pin_idx]";
        switch (pin.desc) {
            case "[I":
                return "cstack" + slot + ".i = " + read + ";";
            case "[J":
                return "cstack" + slot + ".j = " + read + ";";
            case "[F":
                return "cstack" + slot + ".f = " + read + ";";
            case "[D":
                return "cstack" + slot + ".d = " + read + ";";
            case "[Z":
            case "[B":
            case "[C":
            case "[S":
                return "cstack" + slot + ".i = (jint) " + read + ";";
            default:
                return "cstack" + slot + ".i = " + read + ";";
        }
    }

    private static String storeExpr(Pin pin, int slot) {
        String dest = "pin_data_" + pin.id + "[pin_idx]";
        switch (pin.desc) {
            case "[Z":
                return dest + " = (jboolean) (cstack" + slot + ".i & 1);";
            case "[B":
                return dest + " = (jbyte) cstack" + slot + ".i;";
            case "[C":
                return dest + " = (jchar) cstack" + slot + ".i;";
            case "[S":
                return dest + " = (jshort) cstack" + slot + ".i;";
            case "[I":
                return dest + " = cstack" + slot + ".i;";
            case "[J":
                return dest + " = cstack" + slot + ".j;";
            case "[F":
                return dest + " = cstack" + slot + ".f;";
            case "[D":
                return dest + " = cstack" + slot + ".d;";
            default:
                return dest + " = cstack" + slot + ".i;";
        }
    }

    private static Set<TryCatchBlockNode> triesAfter(MethodContext context, LabelNode label) {
        LinkedHashSet<TryCatchBlockNode> next = new LinkedHashSet<>();
        if (context.tryCatches != null) {
            next.addAll(context.tryCatches);
        }
        if (context.method.tryCatchBlocks == null) {
            return next;
        }
        for (TryCatchBlockNode block : context.method.tryCatchBlocks) {
            if (block.start.equals(label)) {
                next.add(block);
            }
        }
        for (TryCatchBlockNode block : context.method.tryCatchBlocks) {
            if (block.end.equals(label)) {
                next.remove(block);
            }
        }
        return next;
    }

    private static final class Pin {
        int id;
        int local;
        String desc;
        boolean write;
        Set<AbstractInsnNode> body;
        List<AbstractInsnNode> accesses;
    }

    private static final class Stub {
        String name;
        List<Pin> pins;
        String target;
        String handler;
    }

    private static final class Arr implements Value {
        final int size;
        final String desc;
        final int origin;

        Arr(int size, String desc, int origin) {
            this.size = size;
            this.desc = desc;
            this.origin = origin;
        }

        @Override
        public int getSize() {
            return size;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Arr)) {
                return false;
            }
            Arr arr = (Arr) o;
            return size == arr.size && origin == arr.origin && Objects.equals(desc, arr.desc);
        }

        @Override
        public int hashCode() {
            return Objects.hash(size, desc, origin);
        }
    }

    private static final class ArrInterpreter extends Interpreter<Arr> {
        ArrInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public Arr newValue(Type type) {
            if (type == null) {
                return new Arr(1, null, -1);
            }
            if (type.getSort() == Type.VOID) {
                return null;
            }
            if (type.getSort() == Type.ARRAY) {
                return new Arr(1, type.getDescriptor(), -1);
            }
            return new Arr(type.getSize(), null, -1);
        }

        @Override
        public Arr newOperation(AbstractInsnNode insn) throws AnalyzerException {
            switch (insn.getOpcode()) {
                case Opcodes.ACONST_NULL:
                    return new Arr(1, null, -1);
                case Opcodes.ICONST_M1:
                case Opcodes.ICONST_0:
                case Opcodes.ICONST_1:
                case Opcodes.ICONST_2:
                case Opcodes.ICONST_3:
                case Opcodes.ICONST_4:
                case Opcodes.ICONST_5:
                case Opcodes.BIPUSH:
                case Opcodes.SIPUSH:
                    return new Arr(1, null, -1);
                case Opcodes.LCONST_0:
                case Opcodes.LCONST_1:
                    return new Arr(2, null, -1);
                case Opcodes.FCONST_0:
                case Opcodes.FCONST_1:
                case Opcodes.FCONST_2:
                    return new Arr(1, null, -1);
                case Opcodes.DCONST_0:
                case Opcodes.DCONST_1:
                    return new Arr(2, null, -1);
                case Opcodes.LDC:
                    Object value = ((LdcInsnNode) insn).cst;
                    if (value instanceof Integer || value instanceof Float || value instanceof String
                            || value instanceof Type || value instanceof org.objectweb.asm.Handle) {
                        return new Arr(1, null, -1);
                    }
                    if (value instanceof Long || value instanceof Double) {
                        return new Arr(2, null, -1);
                    }
                    throw new AnalyzerException(insn, "ldc");
                case Opcodes.JSR:
                    return new Arr(1, null, -1);
                case Opcodes.GETSTATIC:
                    return newValue(Type.getType(((FieldInsnNode) insn).desc));
                case Opcodes.NEW:
                    return new Arr(1, null, -1);
                default:
                    throw new AnalyzerException(insn, "new " + insn.getOpcode());
            }
        }

        @Override
        public Arr copyOperation(AbstractInsnNode insn, Arr value) {
            if (insn.getOpcode() == Opcodes.ALOAD) {
                String desc = value == null ? null : value.desc;
                return new Arr(1, desc, ((VarInsnNode) insn).var);
            }
            return value;
        }

        @Override
        public Arr unaryOperation(AbstractInsnNode insn, Arr value) throws AnalyzerException {
            switch (insn.getOpcode()) {
                case Opcodes.INEG:
                case Opcodes.IINC:
                case Opcodes.L2I:
                case Opcodes.F2I:
                case Opcodes.D2I:
                case Opcodes.I2B:
                case Opcodes.I2C:
                case Opcodes.I2S:
                case Opcodes.ARRAYLENGTH:
                case Opcodes.INSTANCEOF:
                    return new Arr(1, null, -1);
                case Opcodes.FNEG:
                case Opcodes.I2F:
                case Opcodes.L2F:
                case Opcodes.D2F:
                    return new Arr(1, null, -1);
                case Opcodes.LNEG:
                case Opcodes.I2L:
                case Opcodes.F2L:
                case Opcodes.D2L:
                    return new Arr(2, null, -1);
                case Opcodes.DNEG:
                case Opcodes.I2D:
                case Opcodes.L2D:
                case Opcodes.F2D:
                    return new Arr(2, null, -1);
                case Opcodes.IFEQ:
                case Opcodes.IFNE:
                case Opcodes.IFLT:
                case Opcodes.IFGE:
                case Opcodes.IFGT:
                case Opcodes.IFLE:
                case Opcodes.TABLESWITCH:
                case Opcodes.LOOKUPSWITCH:
                case Opcodes.IRETURN:
                case Opcodes.LRETURN:
                case Opcodes.FRETURN:
                case Opcodes.DRETURN:
                case Opcodes.ARETURN:
                case Opcodes.PUTSTATIC:
                case Opcodes.ATHROW:
                case Opcodes.MONITORENTER:
                case Opcodes.MONITOREXIT:
                case Opcodes.IFNULL:
                case Opcodes.IFNONNULL:
                    return null;
                case Opcodes.GETFIELD:
                    return newValue(Type.getType(((FieldInsnNode) insn).desc));
                case Opcodes.NEWARRAY:
                    switch (((IntInsnNode) insn).operand) {
                        case Opcodes.T_BOOLEAN:
                            return new Arr(1, "[Z", -1);
                        case Opcodes.T_CHAR:
                            return new Arr(1, "[C", -1);
                        case Opcodes.T_FLOAT:
                            return new Arr(1, "[F", -1);
                        case Opcodes.T_DOUBLE:
                            return new Arr(1, "[D", -1);
                        case Opcodes.T_BYTE:
                            return new Arr(1, "[B", -1);
                        case Opcodes.T_SHORT:
                            return new Arr(1, "[S", -1);
                        case Opcodes.T_INT:
                            return new Arr(1, "[I", -1);
                        case Opcodes.T_LONG:
                            return new Arr(1, "[J", -1);
                        default:
                            throw new AnalyzerException(insn, "newarray");
                    }
                case Opcodes.ANEWARRAY:
                    String raw = ((TypeInsnNode) insn).desc;
                    String desc = raw.charAt(0) == '[' ? "[" + raw : "[L" + raw + ";";
                    return new Arr(1, desc, -1);
                case Opcodes.CHECKCAST:
                    String cast = ((TypeInsnNode) insn).desc;
                    if (cast.charAt(0) == '[') {
                        return new Arr(1, cast, -1);
                    }
                    return new Arr(1, null, -1);
                default:
                    throw new AnalyzerException(insn, "unary " + insn.getOpcode());
            }
        }

        @Override
        public Arr binaryOperation(AbstractInsnNode insn, Arr value1, Arr value2) throws AnalyzerException {
            switch (insn.getOpcode()) {
                case Opcodes.IALOAD:
                case Opcodes.BALOAD:
                case Opcodes.CALOAD:
                case Opcodes.SALOAD:
                case Opcodes.IADD:
                case Opcodes.ISUB:
                case Opcodes.IMUL:
                case Opcodes.IDIV:
                case Opcodes.IREM:
                case Opcodes.ISHL:
                case Opcodes.ISHR:
                case Opcodes.IUSHR:
                case Opcodes.IAND:
                case Opcodes.IOR:
                case Opcodes.IXOR:
                    return new Arr(1, null, -1);
                case Opcodes.FALOAD:
                case Opcodes.FADD:
                case Opcodes.FSUB:
                case Opcodes.FMUL:
                case Opcodes.FDIV:
                case Opcodes.FREM:
                    return new Arr(1, null, -1);
                case Opcodes.LALOAD:
                case Opcodes.LADD:
                case Opcodes.LSUB:
                case Opcodes.LMUL:
                case Opcodes.LDIV:
                case Opcodes.LREM:
                case Opcodes.LSHL:
                case Opcodes.LSHR:
                case Opcodes.LUSHR:
                case Opcodes.LAND:
                case Opcodes.LOR:
                case Opcodes.LXOR:
                    return new Arr(2, null, -1);
                case Opcodes.DALOAD:
                case Opcodes.DADD:
                case Opcodes.DSUB:
                case Opcodes.DMUL:
                case Opcodes.DDIV:
                case Opcodes.DREM:
                    return new Arr(2, null, -1);
                case Opcodes.AALOAD:
                    String desc = arrayDesc(value1, value2);
                    String comp = arrayComponent(desc);
                    if (comp != null) {
                        return new Arr(1, comp, -1);
                    }
                    return new Arr(1, null, -1);
                case Opcodes.LCMP:
                case Opcodes.FCMPL:
                case Opcodes.FCMPG:
                case Opcodes.DCMPL:
                case Opcodes.DCMPG:
                    return new Arr(1, null, -1);
                case Opcodes.IF_ICMPEQ:
                case Opcodes.IF_ICMPNE:
                case Opcodes.IF_ICMPLT:
                case Opcodes.IF_ICMPGE:
                case Opcodes.IF_ICMPGT:
                case Opcodes.IF_ICMPLE:
                case Opcodes.IF_ACMPEQ:
                case Opcodes.IF_ACMPNE:
                case Opcodes.PUTFIELD:
                    return null;
                default:
                    throw new AnalyzerException(insn, "binary " + insn.getOpcode());
            }
        }

        @Override
        public Arr ternaryOperation(AbstractInsnNode insn, Arr value1, Arr value2, Arr value3) {
            return null;
        }

        @Override
        public Arr naryOperation(AbstractInsnNode insn, List<? extends Arr> values) {
            int op = insn.getOpcode();
            if (op == Opcodes.MULTIANEWARRAY) {
                return newValue(Type.getType(((MultiANewArrayInsnNode) insn).desc));
            }
            if (op == Opcodes.INVOKEDYNAMIC) {
                return newValue(Type.getReturnType(((InvokeDynamicInsnNode) insn).desc));
            }
            return newValue(Type.getReturnType(((MethodInsnNode) insn).desc));
        }

        @Override
        public void returnOperation(AbstractInsnNode insn, Arr value, Arr expected) {
        }

        @Override
        public Arr merge(Arr value1, Arr value2) {
            if (value1.equals(value2)) {
                return value1;
            }
            if (value1.size != value2.size) {
                return new Arr(1, null, -1);
            }
            String desc = Objects.equals(value1.desc, value2.desc) ? value1.desc : null;
            int origin = value1.origin == value2.origin ? value1.origin : -1;
            return new Arr(value1.size, desc, origin);
        }

        private static String arrayDesc(Arr value1, Arr value2) {
            if (value1 != null && value1.desc != null && value1.desc.charAt(0) == '[') {
                return value1.desc;
            }
            if (value2 != null && value2.desc != null && value2.desc.charAt(0) == '[') {
                return value2.desc;
            }
            return null;
        }

        private static String arrayComponent(String desc) {
            if (desc == null || desc.length() < 2 || desc.charAt(0) != '[') {
                return null;
            }
            String rest = desc.substring(1);
            if (rest.charAt(0) == '[') {
                return rest;
            }
            return null;
        }
    }
}
