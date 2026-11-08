package io.github.imcinq.puremetal.lod.sodium;

import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Shape checks for the Sodium classes that Terrain LOD hooks. The plugin calls these before applying the mixins, so a
 * Sodium release that moves or renames an injection point skips Terrain LOD instead of failing at startup. The mixins
 * are only correct as a set: the state interfaces on the build output and the render section must match the mesher and
 * the manager that use them, so one shared result enables or skips all four.
 */
public final class SodiumLodSupport {
    private static final String MESHING_TASK = "net/caffeinemc/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask";
    private static final String RENDER_MANAGER = "net/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager";
    private static final String RENDER_SECTION = "net/caffeinemc/mods/sodium/client/render/chunk/RenderSection";
    private static final String BUILD_OUTPUT = "net/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput";
    private static final String BUILD_CONTEXT = "net/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext";
    private static final String RENDER_CONTEXT = "net/caffeinemc/mods/sodium/client/world/cloned/ChunkRenderContext";
    private static final String RENDER_CACHE = "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderCache";
    private static final String BLOCK_RENDERER = "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer";
    private static final String FLUID_RENDERER = "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/FluidRenderer";

    private static final String EXECUTE = "(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
        + "Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)"
        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;";
    private static final String PREPARE = "(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;"
        + "Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;"
        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;)V";
    private static final String RENDER_MODEL = "(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"
        + "Lnet/minecraft/world/level/block/state/BlockState;"
        + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V";
    private static final String RENDER_FLUID = "(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;"
        + "Lnet/minecraft/world/level/block/state/BlockState;"
        + "Lnet/minecraft/world/level/material/FluidState;"
        + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;"
        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;"
        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V";
    private static final String UPDATE_WITH_RESULT = "(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;"
        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;"
        + "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;Ljava/util/List;)I";
    private static final String PREPARE_FRAME = "(Lorg/joml/Vector3dc;)V";
    private static final String SCHEDULE_REBUILD = "(IIIZ)V";

    private SodiumLodSupport() {
    }

    /** The mesher must still expose the render context field and call the renderer methods the hooks wrap. */
    public static boolean meshingTask(ClassNode node) {
        if (!MESHING_TASK.equals(node.name)) return false;
        if (!hasField(node, "renderContext", "L" + RENDER_CONTEXT + ";")) return false;
        MethodNode execute = method(node, "execute", EXECUTE);
        if (execute == null) return false;
        boolean prepare = false;
        boolean model = false;
        boolean fluid = false;
        for (AbstractInsnNode insn : execute.instructions) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (BLOCK_RENDERER.equals(call.owner) && "prepare".equals(call.name) && PREPARE.equals(call.desc)) prepare = true;
            if (BLOCK_RENDERER.equals(call.owner) && "renderModel".equals(call.name) && RENDER_MODEL.equals(call.desc)) model = true;
            if (FLUID_RENDERER.equals(call.owner) && "render".equals(call.name) && RENDER_FLUID.equals(call.desc)) fluid = true;
        }
        return prepare && model && fluid;
    }

    /** The manager must still own the arrival, frame and rebuild entry points the transition hooks use. */
    public static boolean renderManager(ClassNode node) {
        return RENDER_MANAGER.equals(node.name)
            && method(node, "updateWithResult", UPDATE_WITH_RESULT) != null
            && method(node, "prepareFrame", PREPARE_FRAME) != null
            && method(node, "scheduleRebuild", SCHEDULE_REBUILD) != null;
    }

    /** The section state hooks read these getters when they schedule a rebuild. */
    public static boolean renderSection(ClassNode node) {
        return RENDER_SECTION.equals(node.name)
            && method(node, "getChunkX", "()I") != null
            && method(node, "getChunkY", "()I") != null
            && method(node, "getChunkZ", "()I") != null
            && method(node, "isDisposed", "()Z") != null;
    }

    /** The build output only needs to exist; the state interface adds its own fields. */
    public static boolean buildOutput(ClassNode node) {
        return BUILD_OUTPUT.equals(node.name);
    }

    /** The injected start hook reads both context fields directly. */
    public static boolean buildContext(ClassNode node) {
        return BUILD_CONTEXT.equals(node.name)
            && hasField(node, "buffers", "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;")
            && hasField(node, "cache", "L" + RENDER_CACHE + ";");
    }

    /** The injected start hook reads the section origin. */
    public static boolean renderContext(ClassNode node) {
        return RENDER_CONTEXT.equals(node.name) && method(node, "getOrigin", "()Lnet/minecraft/core/SectionPos;") != null;
    }

    /** The injected start hook reads the world slice and block models through the cache. */
    public static boolean renderCache(ClassNode node) {
        return RENDER_CACHE.equals(node.name)
            && method(node, "getWorldSlice", "()Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;") != null
            && method(node, "getBlockModels", "()Lnet/minecraft/client/renderer/block/BlockStateModelSet;") != null;
    }

    private static boolean hasField(ClassNode node, String name, String desc) {
        return node.fields.stream().anyMatch(f -> name.equals(f.name) && desc.equals(f.desc));
    }

    private static MethodNode method(ClassNode node, String name, String desc) {
        return node.methods.stream().filter(m -> name.equals(m.name) && desc.equals(m.desc)).findFirst().orElse(null);
    }
}
