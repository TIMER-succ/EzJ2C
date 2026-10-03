package by.radioegor146.instructions;

import by.radioegor146.CatchesBlock;
import by.radioegor146.MethodContext;
import by.radioegor146.MethodProcessor;
import by.radioegor146.Util;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;

import java.util.*;
import java.util.stream.Collectors;

public abstract class GenericInstructionHandler<T extends AbstractInsnNode> implements InstructionTypeHandler<T> {

    protected Map<String, String> props;
    protected String instructionName;
    protected String trimmedTryCatchBlock;
    protected String afterSnippet;

    @Override
    public void accept(MethodContext context, T node) {
        props = new HashMap<>();
        afterSnippet = "";
        instructionName = MethodProcessor.INSTRUCTIONS.getOrDefault(node.getOpcode(), "NOTFOUND");
        props.put("line", String.valueOf(context.line));
        String tryCatchText = tryCatchHandler(context, context.tryCatches);
        props.put("trycatchhandler", tryCatchText);
        props.put("rettype", MethodProcessor.CPP_TYPES[context.ret.getSort()]);
        trimmedTryCatchBlock = tryCatchText.trim().replace('\n', ' ');

        for (int i = -5; i <= 5; i++) {
            props.put("stackindex" + (i >= 0 ? i : "m" + (-i)), String.valueOf(context.stackPointer + i));
        }

        context.output.append("    ");
        process(context, node);

        if (instructionName != null) {
            context.output.append(context.obfuscator.getSnippets().getSnippet(instructionName, props));
        }
        if (!afterSnippet.isEmpty()) {
            context.output.append(afterSnippet);
        }
        context.output.append("\n");
    }

    public static String tryCatchHandler(MethodContext context, Collection<TryCatchBlockNode> active) {
        List<TryCatchBlockNode> tryCatchBlockNodeList = new ArrayList<>();
        if (context.method.tryCatchBlocks != null && active != null) {
            for (TryCatchBlockNode tryCatchBlock : context.method.tryCatchBlocks) {
                if (!active.contains(tryCatchBlock)) {
                    continue;
                }
                if (tryCatchBlockNodeList.stream().noneMatch(tryCatchBlockNode ->
                        Objects.equals(tryCatchBlockNode.type, tryCatchBlock.type))) {
                    tryCatchBlockNodeList.add(tryCatchBlock);
                }
            }
        }
        StringBuilder tryCatch = new StringBuilder("\n");
        tryCatch.append("    ");
        if (tryCatchBlockNodeList.size() > 0) {
            String tryCatchLabelName = context.catches.computeIfAbsent(new CatchesBlock(tryCatchBlockNodeList.stream().map(item ->
                    new CatchesBlock.CatchBlock(item.type, item.handler)).collect(Collectors.toList())),
                    key -> String.format("L_CATCH_%d", context.catches.size()));
            tryCatch.append(context.getSnippets().getSnippet("TRYCATCH_START"));
            tryCatch.append(" goto ").append(tryCatchLabelName).append("; }");
        } else {
            tryCatch.append(context.getSnippets().getSnippet("TRYCATCH_EMPTY", Util.createMap(
                    "rettype", MethodProcessor.CPP_TYPES[context.ret.getSort()]
            )));
        }
        return tryCatch.toString();
    }

    protected abstract void process(MethodContext context, T node);
}
