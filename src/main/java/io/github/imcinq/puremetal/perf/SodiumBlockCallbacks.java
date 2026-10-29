package io.github.imcinq.puremetal.perf;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Reuses Sodium's two receiver-only method references without changing renderModel's calls or arguments. */
public final class SodiumBlockCallbacks {
    private static final String TARGET = "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer";
    private static final String CONTEXT = "net/caffeinemc/mods/sodium/client/render/model/AbstractBlockRenderContext";
    private static final String RENDER = "(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"
        + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V";
    private static final String CULL = "(Lnet/minecraft/core/Direction;)Z";
    private static final String BUFFER = "(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModelPart;"
        + "Ljava/util/function/Predicate;Ljava/util/function/Consumer;)V";
    private static final String PREDICATE = "Ljava/util/function/Predicate;";
    private static final String BUFFERER = "Lnet/caffeinemc/mods/sodium/client/services/PlatformModelEmitter$Bufferer;";

    private record Site(MethodNode method, InvokeDynamicInsnNode factory, String suffix, String type) {}

    private SodiumBlockCallbacks() {}

    /**
     * Sodium 0.9.2 and every later release, prereleases of 0.9.3 and up included. The version only rules out older
     * Sodium; whether a release really matches is decided by the bytecode check ({@link #supported}), which fails closed.
     */
    public static boolean supportsVersion(String version) {
        int plus = version.indexOf('+');
        String core = plus >= 0 ? version.substring(0, plus) : version;
        int dash = core.indexOf('-');
        boolean prerelease = dash >= 0;
        String[] parts = (prerelease ? core.substring(0, dash) : core).split("\\.");
        if (parts.length != 3) return false;
        int[] numbers = new int[3];
        try {
            for (int i = 0; i < 3; i++) numbers[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return false;
        }
        int order = java.util.Arrays.compare(numbers, new int[]{0, 9, 2});
        // A prerelease comes before its release, so 0.9.2-beta.1 is older than 0.9.2.
        return order > 0 || order == 0 && !prerelease;
    }

    /** Fail closed if another Sodium version/mod changed either factory, or our names already exist. */
    private static List<Site> sites(ClassNode node) {
        if (!TARGET.equals(node.name)) return List.of();
        if (node.fields.stream().anyMatch(f -> f.name.startsWith("puremetal$blockCallback"))
            || node.methods.stream().anyMatch(m -> m.name.startsWith("puremetal$blockCallback"))) return List.of();
        List<Site> found = new ArrayList<>();
        for (MethodNode method : node.methods) {
            if (!method.name.equals("renderModel") || !method.desc.equals(RENDER)) continue;
            for (AbstractInsnNode insn : method.instructions) {
                if (!(insn instanceof InvokeDynamicInsnNode factory)
                    || !factory.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                    || !factory.bsm.getName().equals("metafactory") || factory.bsmArgs.length != 3
                    || !(factory.bsmArgs[1] instanceof Handle implementation)
                    || implementation.getTag() != Opcodes.H_INVOKEVIRTUAL
                    || !implementation.getOwner().equals(CONTEXT)) continue;
                String suffix, type;
                if (implementation.getName().equals("isFaceCulled") && implementation.getDesc().equals(CULL)
                    && factory.name.equals("test")) { suffix = "Cull"; type = PREDICATE; }
                else if (implementation.getName().equals("bufferDefaultModel") && implementation.getDesc().equals(BUFFER)
                    && factory.name.equals("emit")) { suffix = "Buffer"; type = BUFFERER; }
                else continue;
                // Only the renderer is captured: never cache a callback carrying block/position/region state.
                if (factory.desc.equals("(L" + TARGET + ";)" + type)) {
                    found.add(new Site(method, factory, suffix, type));
                }
            }
        }
        return found.size() == 2 && !found.get(0).suffix.equals(found.get(1).suffix) ? found : List.of();
    }

    public static boolean supported(ClassNode node) { return sites(node).size() == 2; }

    /** Called after Mixin has merged the marker mixin. No constructor, model or visibility logic is replaced. */
    public static boolean apply(ClassNode node) {
        List<Site> sites = sites(node);
        if (sites.isEmpty()) return false;
        for (Site site : sites) {
            String name = "puremetal$blockCallback" + site.suffix;
            node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, name, site.type, null, null));
            MethodNode getter = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, name, "()" + site.type, null, null);
            InsnList code = getter.instructions;
            LabelNode cached = new LabelNode();
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, name, site.type));
            code.add(new JumpInsnNode(Opcodes.IFNONNULL, cached));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            // Preserve the exact original factory/dispatch, including virtual overrides.
            code.add(new InvokeDynamicInsnNode(site.factory.name, site.factory.desc, site.factory.bsm, site.factory.bsmArgs.clone()));
            code.add(new FieldInsnNode(Opcodes.PUTFIELD, node.name, name, site.type));
            code.add(cached);
            code.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
            code.add(new VarInsnNode(Opcodes.ALOAD, 0));
            code.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, name, site.type));
            code.add(new InsnNode(Opcodes.ARETURN));
            getter.maxStack = 2;
            getter.maxLocals = 1;
            node.methods.add(getter);
            site.method.instructions.set(site.factory,
                new MethodInsnNode(Opcodes.INVOKESPECIAL, node.name, name, "()" + site.type, false));
        }
        return true;
    }
}
