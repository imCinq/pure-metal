package io.github.imcinq.puremetal.mixin;

import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Keeps benchmark/profile hooks out of normal play and picks the terrain LOD hooks. */
public final class PureMetalMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Pure Metal");
    private static final boolean SODIUM = FabricLoader.getInstance().isModLoaded("sodium");
    private static Boolean sodiumMemoryTrimSupported;
    private static Boolean sodiumOptionListSupported;
    private static Boolean sodiumLodSupported;
    @Override
    public void onLoad(final String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(final String targetClassName, final String mixinClassName) {
        if (mixinClassName.endsWith(".compat.DistantHorizonsApiMixin")) {
            return io.github.imcinq.puremetal.backend.DistantHorizonsExperiment.enabled();
        }
        if (mixinClassName.endsWith(".perf.sodium.FluidDefaultContextMixin")) {
            if (!SODIUM || Boolean.getBoolean("puremetal.disableFluidCallbacks")) return false;
            boolean version = FabricLoader.getInstance().getModContainer("sodium")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .filter(io.github.imcinq.puremetal.perf.SodiumBlockCallbacks::supportsVersion).isPresent();
            if (!version) return false;
            try {
                return io.github.imcinq.puremetal.perf.FabricFluidCallbacks.supportsOwner(
                    MixinService.getService().getBytecodeProvider().getClassNode(targetClassName, false));
            } catch (Exception unavailable) { return false; }
        }
        if (mixinClassName.endsWith(".perf.FabricFluidCallbacksMixin")) {
            if (Boolean.getBoolean("puremetal.disableFluidCallbacks")) return false;
            boolean version = FabricLoader.getInstance().getModContainer("fabric-rendering-fluids-v1")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .filter(io.github.imcinq.puremetal.perf.FabricFluidCallbacks::supportsVersion).isPresent();
            if (!version) return false;
            try {
                return io.github.imcinq.puremetal.perf.FabricFluidCallbacks.supported(
                    MixinService.getService().getBytecodeProvider().getClassNode(targetClassName, false));
            } catch (Exception unavailable) { return false; }
        }
        if (mixinClassName.endsWith(".perf.sodium.BlockRendererCallbacksMixin")) {
            if (!SODIUM || Boolean.getBoolean("puremetal.disableSodiumBlockCallbacks")) return false;
            // Sodium 0.9.2 and later; the bytecode check below decides whether this release really matches.
            boolean version = FabricLoader.getInstance().getModContainer("sodium")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .filter(io.github.imcinq.puremetal.perf.SodiumBlockCallbacks::supportsVersion).isPresent();
            if (!version) return false;
            try {
                return io.github.imcinq.puremetal.perf.SodiumBlockCallbacks.supported(
                    MixinService.getService().getBytecodeProvider().getClassNode(targetClassName, false));
            } catch (Exception unavailable) { return false; }
        }
        if (mixinClassName.contains(".memory.sodium.")) {
            return SODIUM && isSodiumMemoryTrimSupported(targetClassName);
        }
        if (mixinClassName.contains(".ui.sodium.")) {
            return SODIUM && isSodiumOptionListSupported();
        }
        if (mixinClassName.contains(".diagnostics.")) {
            return (Boolean.getBoolean("puremetal.terrainDiagnostics") || Boolean.getBoolean("puremetal.terrainOcclusionProbe"))
                && (mixinClassName.contains(".diagnostics.sodium.") == SODIUM);
        }
        // The proof also gates relaxed terrain fragment math (FastTerrainMath), which is on by default.
        if (mixinClassName.endsWith(".perf.TerrainShaderProofMixin")) {
            return Boolean.getBoolean("puremetal.terrainOcclusionProbe") || !"off".equalsIgnoreCase(System.getProperty("puremetal.fastTerrainMath"));
        }
        if (mixinClassName.endsWith(".perf.PalettedContainerMixin")) {
            return !Boolean.getBoolean("puremetal.disablePaletteScratch");
        }
        if (mixinClassName.endsWith(".MinecraftBenchMixin")) {
            return Integer.getInteger("puremetal.bench", 0) > 0 || Boolean.getBoolean("puremetal.profile")
                || Boolean.getBoolean("puremetal.terrainDiagnostics") || Boolean.getBoolean("puremetal.terrainOcclusionProbe");
        }
        // Terrain LOD hooks the mesher that is actually in use: Sodium's when it is installed, vanilla's otherwise.
        if (mixinClassName.endsWith(".lod.LodTextureShaderMixin")) {
            return Boolean.parseBoolean(System.getProperty("puremetal.experimentalLodTextures", "true"))
                || Boolean.parseBoolean(System.getProperty("puremetal.rgssEarlyOut", "true"));
        }
        if (mixinClassName.contains(".lod.sodium.DefaultFluidRenderer")) {
            // Water tops are only merged when a hook is there to skip them. Sodium 0.9.2 tests the top face with
            // isFullBlockFluidVisible, 0.9.3 with isFullBlockFluidSideVisible; each mixin hooks one, others keep the tops.
            String top = SODIUM ? sodiumFluidTopCall(targetClassName) : null;
            String wanted = mixinClassName.endsWith("SideVisibleMixin") ? "isFullBlockFluidSideVisible" : "isFullBlockFluidVisible";
            boolean supported = wanted.equals(top);
            if (supported) {
                io.github.imcinq.puremetal.lod.sodium.WaterTopHook.setSupported(true);
            } else if (SODIUM && top == null && mixinClassName.endsWith(".DefaultFluidRendererMixin")) {
                LOGGER.info("Pure Metal: Terrain LOD keeps Sodium's water surfaces as they are, this Sodium version is not supported");
            }
            return supported;
        }
        if (mixinClassName.contains(".lod.sodium.")) {
            // The state interfaces, mesher and manager hooks only work together; one shared shape check skips all of them.
            return SODIUM && isSodiumLodSupported();
        }
        if (mixinClassName.contains(".lod.")) {
            return !SODIUM;
        }
        return true;
    }

    /**
     * Terrain LOD injects at exact Sodium methods and reads context fields by name. A release that changes any of those
     * shapes skips the whole set instead of failing during mixin application or later in the mesher.
     */
    private static synchronized boolean isSodiumLodSupported() {
        if (sodiumLodSupported == null) {
            sodiumLodSupported = checkSodiumLod();
            if (!sodiumLodSupported) {
                LOGGER.warn("Pure Metal: Terrain LOD skipped, this Sodium version's chunk mesher does not match");
            }
        }
        return sodiumLodSupported;
    }

    private static boolean checkSodiumLod() {
        try {
            final var provider = MixinService.getService().getBytecodeProvider();
            return io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.meshingTask(provider.getClassNode(
                    "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask", false))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.renderManager(provider.getClassNode(
                    "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", false))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.renderSection(provider.getClassNode(
                    "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection", false))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.buildOutput(provider.getClassNode(
                    "net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput", false))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.buildContext(provider.getClassNode(
                    "net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext", false))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.renderContext(provider.getClassNode(
                    "net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext", false))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.renderCache(provider.getClassNode(
                    "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache", false));
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** The memory trim is optional, so a Sodium version whose ArenaAggregator internals changed skips it instead of crashing. */
    private static synchronized boolean isSodiumMemoryTrimSupported(final String targetClassName) {
        if (sodiumMemoryTrimSupported == null) {
            sodiumMemoryTrimSupported = checkSodiumMemoryTrimTarget(targetClassName);
            if (!sodiumMemoryTrimSupported) {
                LOGGER.warn("Pure Metal: Sodium memory trim skipped, this Sodium version is not supported");
            }
        }
        return sodiumMemoryTrimSupported;
    }

    /**
     * The options-screen note is cosmetic, so a Sodium whose option list changed shows no note instead of crashing.
     * Both the list mixin and its invoker accessor are gated on the same result, and every member must be declared
     * on the exact class Mixin targets (inherited members do not count).
     */
    private static synchronized boolean isSodiumOptionListSupported() {
        if (sodiumOptionListSupported == null) {
            sodiumOptionListSupported = hasVerifiedSodiumSettings() && checkSodiumOptionList();
            if (!sodiumOptionListSupported) {
                LOGGER.debug("Pure Metal: Sodium options note and card skipped, settings classes are not verified");
            }
        }
        return sodiumOptionListSupported;
    }

    /** Exact bytes protect the local-variable injection points across Sodium versions. */
    private static boolean hasVerifiedSodiumSettings() {
        return FabricLoader.getInstance().getModContainer("sodium")
            .map(mod -> io.github.imcinq.puremetal.scale.SodiumSettingsFingerprint.matches(mod::findPath))
            .orElse(false);
    }

    private static boolean checkSodiumOptionList() {
        try {
            final ClassNode list = readClass("net/caffeinemc/mods/sodium/client/gui/widgets/OptionListWidget", org.objectweb.asm.ClassReader.SKIP_FRAMES);
            final ClassNode parent = readClass("net/caffeinemc/mods/sodium/client/gui/widgets/AbstractParentWidget");
            final ClassNode mod = readClass("net/caffeinemc/mods/sodium/client/config/structure/ModOptions");
            return hasField(list, "entryHeight", "I")
                && hasRenderAllPagesLocals(list)
                && hasMethod(parent, "addRenderableChild", "(Lnet/minecraft/client/gui/components/events/GuiEventListener;)Lnet/minecraft/client/gui/components/events/GuiEventListener;")
                && hasMethod(mod, "configId", "()Ljava/lang/String;");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * The mixin captures renderAllPages locals by slot (5 = int offset, 7 = ModOptions) after the first
     * addRenderableChild call; confirm the debug local table still says so, so a mismatch skips the note instead of failing the inject.
     */
    private static boolean hasRenderAllPagesLocals(final ClassNode list) {
        final String listName = "net/caffeinemc/mods/sodium/client/gui/widgets/OptionListWidget";
        final String modName = "Lnet/caffeinemc/mods/sodium/client/config/structure/ModOptions;";
        for (final org.objectweb.asm.tree.MethodNode m : list.methods) {
            if (!"renderAllPages".equals(m.name) || !"(Lnet/minecraft/client/gui/screens/Screen;III)I".equals(m.desc)) continue;
            if (m.localVariables == null || m.instructions == null) return false;
            final boolean offset = m.localVariables.stream().anyMatch(v -> v.index == 5 && "I".equals(v.desc));
            final boolean mod = m.localVariables.stream().anyMatch(v -> v.index == 7 && modName.equals(v.desc));
            final boolean page = m.localVariables.stream().anyMatch(v -> v.index == 12 && "Lnet/caffeinemc/mods/sodium/client/config/structure/Page;".equals(v.desc));
            boolean add = false;
            boolean section = false;
            for (final org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && listName.equals(call.owner) && "addRenderableChild".equals(call.name)) {
                    add = true;
                }
                if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type && insn.getOpcode() == org.objectweb.asm.Opcodes.NEW
                    && (listName + "$SectionInfo").equals(type.desc)) section = true;
            }
            return offset && mod && page && add && section;
        }
        return false;
    }

    private static boolean hasField(final ClassNode node, final String name, final String desc) {
        return node.fields.stream().anyMatch(f -> name.equals(f.name) && desc.equals(f.desc));
    }

    private static boolean hasMethod(final ClassNode node, final String name, final String desc) {
        return node.methods.stream().anyMatch(m -> name.equals(m.name) && desc.equals(m.desc));
    }

    /** Reads class bytes from the resource without loading or initialising the class. */
    private static ClassNode readClass(final String internalName) throws java.io.IOException {
        return readClass(internalName, org.objectweb.asm.ClassReader.SKIP_CODE);
    }

    private static ClassNode readClass(final String internalName, final int flags) throws java.io.IOException {
        try (java.io.InputStream in = PureMetalMixinPlugin.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in == null) throw new java.io.FileNotFoundException(internalName);
            final ClassNode node = new ClassNode();
            new org.objectweb.asm.ClassReader(in).accept(node, flags);
            return node;
        }
    }

    private static String sodiumFluidTop;
    private static boolean sodiumFluidTopChecked;

    /**
     * The visibility call render() tests the top face with: the first call of either known shape must pass Direction.UP.
     * Returns its name, or null when the shape is unknown (then no water hook applies).
     */
    private static synchronized String sodiumFluidTopCall(final String targetClassName) {
        if (!sodiumFluidTopChecked) {
            sodiumFluidTop = checkSodiumFluidTop(targetClassName);
            sodiumFluidTopChecked = true;
        }
        return sodiumFluidTop;
    }

    private static String checkSodiumFluidTop(final String targetClassName) {
        try {
            final ClassNode node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName, false);
            final String visible = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Z";
            final String sideVisible = "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)Z";
            for (final org.objectweb.asm.tree.MethodNode method : node.methods) {
                if (!"render".equals(method.name) || !method.desc.startsWith("(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;")) {
                    continue;
                }
                // 0.9.2 also calls isFullBlockFluidSideVisible for the sides, so only a render without the old call may use it.
                boolean old = false;
                for (final org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                    if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && "isFullBlockFluidVisible".equals(call.name)) {
                        old = true;
                    }
                }
                for (final org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
                    if (!(insn instanceof org.objectweb.asm.tree.MethodInsnNode call)) {
                        continue;
                    }
                    boolean match = old ? "isFullBlockFluidVisible".equals(call.name) && visible.equals(call.desc)
                        : "isFullBlockFluidSideVisible".equals(call.name) && sideVisible.equals(call.desc);
                    if (!match) {
                        continue;
                    }
                    org.objectweb.asm.tree.AbstractInsnNode previous = call.getPrevious();
                    while (previous != null && !(previous instanceof org.objectweb.asm.tree.FieldInsnNode)) {
                        previous = previous.getPrevious();
                    }
                    boolean up = previous instanceof org.objectweb.asm.tree.FieldInsnNode field
                        && "net/minecraft/core/Direction".equals(field.owner) && "UP".equals(field.name);
                    return up ? call.name : null;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean checkSodiumMemoryTrimTarget(final String targetClassName) {
        try {
            final ClassNode node = MixinService.getService().getBytecodeProvider().getClassNode(targetClassName, false);
            return node.fields.stream().anyMatch(f -> "freeBuffers".equals(f.name) && "[Lcom/mojang/renderpearl/api/buffers/GpuBuffer;".equals(f.desc))
                && node.fields.stream().anyMatch(f -> "freeBufferCount".equals(f.name) && "I".equals(f.desc))
                && node.methods.stream().anyMatch(m -> "releaseBufferForReuse".equals(m.name) && "(Lcom/mojang/renderpearl/api/buffers/GpuBuffer;)V".equals(m.desc))
                && node.methods.stream().anyMatch(m -> "update".equals(m.name) && "()V".equals(m.desc));
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void acceptTargets(final Set<String> myTargets, final Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
        if (mixinClassName.endsWith(".perf.FabricFluidCallbacksMixin")) {
            boolean applied = io.github.imcinq.puremetal.perf.FabricFluidCallbacks.apply(targetClass);
            LOGGER.info("Pure Metal: fluid scope carrier cache {}", applied ? "enabled" : "skipped (scope shape changed)");
        }
        if (mixinClassName.endsWith(".perf.sodium.BlockRendererCallbacksMixin")) {
            boolean applied = io.github.imcinq.puremetal.perf.SodiumBlockCallbacks.apply(targetClass);
            LOGGER.info("Pure Metal: Sodium block callback cache {}", applied ? "enabled" : "skipped (factory shape changed)");
        }
    }
}
