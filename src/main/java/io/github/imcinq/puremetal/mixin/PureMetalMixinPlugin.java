package io.github.imcinq.puremetal.mixin;

import io.github.imcinq.puremetal.MixinHooks;
import io.github.imcinq.puremetal.ModPlatform;
import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.spongepowered.asm.service.MixinService;

/** Keeps benchmark/profile hooks out of normal play and picks the terrain LOD hooks. */
public final class PureMetalMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("Pure Metal");
    private static final java.util.concurrent.atomic.AtomicBoolean BOOT_APPLIED = new java.util.concurrent.atomic.AtomicBoolean();
    private static final boolean SODIUM = ModPlatform.get().isModLoaded("sodium");
    private static Boolean sodiumMemoryTrimSupported;
    private static Boolean sodiumOptionListSupported;
    private static Boolean sodiumLodSupported;
    @Override
    public void onLoad(final String mixinPackage) {
        if (BOOT_APPLIED.compareAndSet(false, true)) {
            io.github.imcinq.puremetal.bench.UserBenchBoot.apply();
        }
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
            boolean version = ModPlatform.get().mod("sodium")
                .map(ModPlatform.ModMeta::version)
                .filter(io.github.imcinq.puremetal.perf.SodiumBlockCallbacks::supportsVersion).isPresent();
            if (!version) return false;
            try {
                return io.github.imcinq.puremetal.perf.FabricFluidCallbacks.supportsOwner(inspectClass(targetClassName));
            } catch (Exception unavailable) { return false; }
        }
        if (mixinClassName.endsWith(".perf.FabricFluidCallbacksMixin")) {
            if (Boolean.getBoolean("puremetal.disableFluidCallbacks")) return false;
            boolean version = ModPlatform.get().mod("fabric-rendering-fluids-v1")
                .map(ModPlatform.ModMeta::version)
                .filter(io.github.imcinq.puremetal.perf.FabricFluidCallbacks::supportsVersion).isPresent();
            if (!version) return false;
            try {
                return io.github.imcinq.puremetal.perf.FabricFluidCallbacks.supported(inspectClass(targetClassName));
            } catch (Exception unavailable) { return false; }
        }
        if (mixinClassName.endsWith(".perf.sodium.BlockRendererCallbacksMixin")) {
            if (!SODIUM || Boolean.getBoolean("puremetal.disableSodiumBlockCallbacks")) return false;
            // Sodium 0.9.2 and later; the bytecode check below decides whether this release really matches.
            boolean version = ModPlatform.get().mod("sodium")
                .map(ModPlatform.ModMeta::version)
                .filter(io.github.imcinq.puremetal.perf.SodiumBlockCallbacks::supportsVersion).isPresent();
            if (!version) return false;
            try {
                return io.github.imcinq.puremetal.perf.SodiumBlockCallbacks.supported(inspectClass(targetClassName));
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
        // The proof gates relaxed terrain math (FastTerrainMath), on by default; -Dpuremetal.fastTerrainMath=off removes the hook unless an A/B or probe needs it.
        if (mixinClassName.endsWith(".perf.TerrainShaderProofMixin")) {
            String fastTerrain = System.getProperty("puremetal.fastTerrainMath", "on").trim();
            return Boolean.getBoolean("puremetal.terrainOcclusionProbe") || !(fastTerrain.equalsIgnoreCase("off") || fastTerrain.equalsIgnoreCase("false"))
                || System.getProperty("puremetal.benchAB", "").toLowerCase(java.util.Locale.ROOT).startsWith("fastterrain");
        }
        if (mixinClassName.endsWith(".perf.PalettedContainerMixin")) {
            return !Boolean.getBoolean("puremetal.disablePaletteScratch");
        }
        if (mixinClassName.endsWith(".perf.SoundEngineExecutorMixin")) {
            return !Boolean.getBoolean("puremetal.disableThreadQos");
        }
        if (mixinClassName.endsWith(".perf.SectionRenderDispatcherPacerMixin")) {
            return !SODIUM && !Boolean.getBoolean("puremetal.disableChunkBuildPacer") && isChunkBuildPacerSupported(targetClassName);
        }
        if (mixinClassName.endsWith(".perf.LevelRendererEarlyUploadMixin")
            || mixinClassName.endsWith(".perf.LevelExtractorEarlyUploadMixin")) {
            return !SODIUM && !Boolean.getBoolean("puremetal.disableEarlyTerrainUpload");
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
            // WaterTopHook is enabled in postApply only once the injection actually lands in DefaultFluidRenderer.
            String top = SODIUM ? sodiumFluidTopCall(targetClassName) : null;
            String wanted = mixinClassName.endsWith("SideVisibleMixin") ? "isFullBlockFluidSideVisible" : "isFullBlockFluidVisible";
            boolean supported = wanted.equals(top);
            if (!supported && SODIUM && top == null && mixinClassName.endsWith(".DefaultFluidRendererMixin")) {
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
            return io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.meshingTask(inspectClass(
                    "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask"))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.renderManager(inspectClass(
                    "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager"))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.renderSection(inspectClass(
                    "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection"))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.buildOutput(inspectClass(
                    "net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput"))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.buildContext(inspectClass(
                    "net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext"))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.renderContext(inspectClass(
                    "net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext"))
                && io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport.renderCache(inspectClass(
                    "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache"));
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
        return ModPlatform.get().isModLoaded("sodium")
            && io.github.imcinq.puremetal.scale.SodiumSettingsFingerprint.matchesBytes(
                path -> ModPlatform.get().readEntry("sodium", path, 2 * 1024 * 1024));
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

    /**
     * Reads class bytecode for shape guards without loading the target class. Prefers untransformed bytecode (Fabric);
     * NeoForge's FML Mixin service rejects {@code runTransformers = false}, so fall back to {@code true} there.
     */
    private static ClassNode inspectClass(final String className) throws Exception {
        final var provider = MixinService.getService().getBytecodeProvider();
        try {
            return provider.getClassNode(className, false);
        } catch (IllegalArgumentException untransformedUnsupported) {
            return provider.getClassNode(className, true);
        }
    }

    /** Reads class bytes from the resource without loading or initialising the class. */
    private static ClassNode readClass(final String internalName) throws java.io.IOException {
        return readClass(internalName, org.objectweb.asm.ClassReader.SKIP_CODE);
    }

    private static ClassNode readClass(final String internalName, final int flags) throws java.io.IOException {
        try (java.io.InputStream in = PureMetalMixinPlugin.class.getClassLoader().getResourceAsStream(internalName + ".class")) {
            if (in != null) {
                final ClassNode node = new ClassNode();
                new org.objectweb.asm.ClassReader(in).accept(node, flags);
                return node;
            }
        }
        try {
            return MixinService.getService().getBytecodeProvider().getClassNode(internalName, true, flags);
        } catch (Exception unavailable) {
            throw new java.io.FileNotFoundException(internalName);
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
            final ClassNode node = inspectClass(targetClassName);
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
            final ClassNode node = inspectClass(targetClassName);
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

    private static boolean isChunkBuildPacerSupported(final String targetClassName) {
        try {
            final ClassNode node = inspectClass(targetClassName);
            if (!hasField(node, "queue", "Lnet/minecraft/client/renderer/chunk/SectionTaskDynamicQueue;")
                || !hasField(node, "executor", "Lnet/minecraft/TracingExecutor;")
                || !hasField(node, "closed", "Z")
                || !hasMethod(node, "getFreeBufferCount", "()I")) {
                return false;
            }
            boolean scheduleOk = false, runTaskOk = false;
            for (final org.objectweb.asm.tree.MethodNode m : node.methods) {
                if (m.instructions == null) continue;
                if ("schedule".equals(m.name)) {
                    for (final org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
                        if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                            && "net/minecraft/TracingExecutor".equals(call.owner)
                            && "execute".equals(call.name) && "(Ljava/lang/Runnable;)V".equals(call.desc)) {
                            scheduleOk = true;
                        }
                    }
                } else if ("runTask".equals(m.name) && "()V".equals(m.desc)) {
                    boolean acquire = false, execute = false;
                    for (final org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
                        if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                            if ("net/minecraft/client/renderer/SectionBufferBuilderPool".equals(call.owner)
                                && "acquire".equals(call.name)) acquire = true;
                            if ("net/minecraft/TracingExecutor".equals(call.owner)
                                && "execute".equals(call.name) && "(Ljava/lang/Runnable;)V".equals(call.desc)) execute = true;
                        }
                    }
                    runTaskOk = acquire && execute;
                }
            }
            return scheduleOk && runTaskOk;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean hasWovenCall(final ClassNode targetClass, final String hookFragment) {
        for (final org.objectweb.asm.tree.MethodNode m : targetClass.methods) {
            if (m.instructions == null) continue;
            for (final org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && targetClass.name.equals(call.owner) && call.name.contains(hookFragment)) {
                    return true;
                }
                if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                    for (final Object arg : indy.bsmArgs) {
                        if (arg instanceof org.objectweb.asm.Handle h
                            && targetClass.name.equals(h.getOwner()) && h.getName().contains(hookFragment)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasMethodContaining(final ClassNode targetClass, final String fragment) {
        for (final org.objectweb.asm.tree.MethodNode m : targetClass.methods) {
            if (m.name.contains(fragment)) return true;
        }
        return false;
    }

    private static boolean methodHasWovenCall(final ClassNode targetClass, final org.objectweb.asm.tree.MethodNode m,
                                              final String hookFragment) {
        if (m.instructions == null) return false;
        for (final org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                && targetClass.name.equals(call.owner) && call.name.contains(hookFragment)) {
                return true;
            }
            if (insn instanceof org.objectweb.asm.tree.InvokeDynamicInsnNode indy && indy.bsmArgs != null) {
                for (final Object arg : indy.bsmArgs) {
                    if (arg instanceof org.objectweb.asm.Handle h
                        && targetClass.name.equals(h.getOwner()) && h.getName().contains(hookFragment)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean methodHasTargetCall(final org.objectweb.asm.tree.MethodNode m, final String callOwner,
                                               final String callName, final String callDesc) {
        if (m.instructions == null) return false;
        for (final org.objectweb.asm.tree.AbstractInsnNode insn : m.instructions) {
            if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                && callOwner.equals(call.owner) && callName.equals(call.name) && callDesc.equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTargetCall(final ClassNode targetClass, final String methodName, final String callOwner,
                                         final String callName, final String callDesc) {
        for (final org.objectweb.asm.tree.MethodNode m : targetClass.methods) {
            if (!methodName.equals(m.name)) continue;
            if (methodHasTargetCall(m, callOwner, callName, callDesc)) return true;
        }
        return false;
    }

    private static boolean hasConcreteMethod(final ClassNode targetClass, final String methodName) {
        for (final org.objectweb.asm.tree.MethodNode m : targetClass.methods) {
            if (methodName.equals(m.name) && (m.access & org.objectweb.asm.Opcodes.ACC_ABSTRACT) == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean implementsInterface(final ClassNode targetClass, final String internalName) {
        return targetClass.interfaces != null && targetClass.interfaces.contains(internalName);
    }

    @Override
    public void postApply(final String targetClassName, final ClassNode targetClass, final String mixinClassName, final IMixinInfo mixinInfo) {
        if (mixinClassName.endsWith(".perf.FabricFluidCallbacksMixin")) {
            boolean applied = io.github.imcinq.puremetal.perf.FabricFluidCallbacks.apply(targetClass);
            LOGGER.info("Pure Metal: fluid scope carrier cache {}", applied ? "enabled" : "skipped (scope shape changed)");
        } else if (mixinClassName.endsWith(".perf.sodium.BlockRendererCallbacksMixin")) {
            boolean applied = io.github.imcinq.puremetal.perf.SodiumBlockCallbacks.apply(targetClass);
            LOGGER.info("Pure Metal: Sodium block callback cache {}", applied ? "enabled" : "skipped (factory shape changed)");
        } else if (mixinClassName.endsWith(".lod.SectionCompilerMixin")) {
            // MixinExtras (@WrapOperation/@WrapWithCondition) runs in Extensions.postApply right after plugin postApply,
            // so verify standard @Inject calls and the exact target call all live in the SAME compile() overload
            // (important on NeoForge, which keeps a deprecated 4-arg delegator alongside the 5-arg compile body).
            final String blockDesc = "(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V";
            final String fluidDesc = "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V";
            boolean compilerOk = false;
            boolean waterOk = false;
            for (final org.objectweb.asm.tree.MethodNode m : targetClass.methods) {
                if (!"compile".equals(m.name)) continue;
                boolean beginEnd = methodHasWovenCall(targetClass, m, "puremetal$beginLod")
                    && methodHasWovenCall(targetClass, m, "puremetal$endLod");
                if (beginEnd && methodHasTargetCall(m, "net/minecraft/client/renderer/block/ModelBlockRenderer", "tesselateBlock", blockDesc)) {
                    compilerOk = true;
                }
                if (beginEnd && methodHasTargetCall(m, "net/minecraft/client/renderer/block/FluidRenderer", "tesselate", fluidDesc)) {
                    waterOk = true;
                }
            }
            if (compilerOk && hasMethodContaining(targetClass, "puremetal$coarseBlock")) {
                MixinHooks.confirm(MixinHooks.VLOD_COMPILER);
            }
            if (waterOk && hasMethodContaining(targetClass, "puremetal$coarseWater")) {
                MixinHooks.confirm(MixinHooks.VLOD_WATER_EMIT);
            }
        } else if (mixinClassName.endsWith(".lod.FluidRendererMixin")) {
            if (hasMethodContaining(targetClass, "puremetal$skipMergedTop")
                && hasTargetCall(targetClass, "tesselate", "net/minecraft/client/renderer/block/FluidRenderer", "isNeighborSameFluid",
                    "(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/FluidState;)Z")) {
                MixinHooks.confirm(MixinHooks.VLOD_WATER_SKIP);
            }
        } else if (mixinClassName.endsWith(".lod.SectionCompilerResultsMixin")) {
            if (implementsInterface(targetClass, "io/github/imcinq/puremetal/lod/LodState")) {
                MixinHooks.confirm(MixinHooks.VLOD_RESULTS);
            }
        } else if (mixinClassName.endsWith(".lod.CompiledSectionMeshMixin")) {
            if (implementsInterface(targetClass, "io/github/imcinq/puremetal/lod/LodState")
                && hasWovenCall(targetClass, "puremetal$copyLod")) {
                MixinHooks.confirm(MixinHooks.VLOD_MESH);
            }
        } else if (mixinClassName.endsWith(".lod.LevelExtractorMixin")) {
            if (hasWovenCall(targetClass, "puremetal$lodTransitions")) {
                MixinHooks.confirm(MixinHooks.VLOD_EXTRACTOR);
            }
        } else if (mixinClassName.endsWith(".lod.sodium.ChunkBuilderMeshingTaskMixin")) {
            if (hasWovenCall(targetClass, "puremetal$beginLod")
                && hasWovenCall(targetClass, "puremetal$endLod")
                && hasMethodContaining(targetClass, "puremetal$coarseBlock")
                && hasTargetCall(targetClass, "execute", "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer", "renderModel",
                    "(Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)V")) {
                MixinHooks.confirm(MixinHooks.SLOD_TASK);
            }
            if (hasMethodContaining(targetClass, "puremetal$coarseWater")
                && hasTargetCall(targetClass, "execute", "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/FluidRenderer", "render",
                    "(Lnet/caffeinemc/mods/sodium/client/world/LevelSlice;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;Lnet/caffeinemc/mods/sodium/client/render/chunk/translucent_sorting/TranslucentGeometryCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildBuffers;)V")) {
                MixinHooks.confirm(MixinHooks.SLOD_WATER_EMIT);
            }
        } else if (mixinClassName.contains(".lod.sodium.DefaultFluidRenderer")) {
            boolean sideVisible = mixinClassName.endsWith("SideVisibleMixin");
            String targetMethod = sideVisible ? "isFullBlockFluidSideVisible" : "isFullBlockFluidVisible";
            String targetDesc = sideVisible
                ? "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/material/FluidState;)Z"
                : "(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Z";
            if (hasMethodContaining(targetClass, "puremetal$skipMergedTop")
                && hasTargetCall(targetClass, "render", "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/DefaultFluidRenderer", targetMethod, targetDesc)) {
                MixinHooks.confirm(MixinHooks.SLOD_WATER_SKIP);
                io.github.imcinq.puremetal.lod.sodium.WaterTopHook.setSupported(true);
            }
        } else if (mixinClassName.endsWith(".lod.sodium.ChunkBuildOutputMixin")) {
            if (implementsInterface(targetClass, "io/github/imcinq/puremetal/lod/LodState")) {
                MixinHooks.confirm(MixinHooks.SLOD_OUTPUT);
            }
        } else if (mixinClassName.endsWith(".lod.sodium.RenderSectionMixin")) {
            if (implementsInterface(targetClass, "io/github/imcinq/puremetal/lod/LodState")) {
                MixinHooks.confirm(MixinHooks.SLOD_SECTION);
            }
        } else if (mixinClassName.endsWith(".lod.sodium.RenderSectionManagerMixin")) {
            if (hasWovenCall(targetClass, "puremetal$trackLod")
                && hasWovenCall(targetClass, "puremetal$lodTransitions")) {
                MixinHooks.confirm(MixinHooks.SLOD_MANAGER);
            }
        } else if (mixinClassName.endsWith(".perf.LevelRendererEarlyUploadMixin")) {
            if (implementsInterface(targetClass, "io/github/imcinq/puremetal/perf/EarlyTerrainUpload$DeferredFlush")
                && hasWovenCall(targetClass, "puremetal$flushPendingBeforeRender")
                && hasWovenCall(targetClass, "puremetal$flushDeferredTerrainUpload")
                && hasWovenCall(targetClass, "puremetal$clearDeferredOnInvalidate")
                && hasWovenCall(targetClass, "puremetal$clearDeferredOnReset")
                && hasWovenCall(targetClass, "puremetal$clearDeferredOnClose")
                && hasMethodContaining(targetClass, "puremetal$deferTerrainUpload")
                && hasTargetCall(targetClass, "render", "net/minecraft/client/renderer/chunk/SectionRenderDispatcher", "uploadTerrainBuffersToGpu", "()V")
                && hasMethodContaining(targetClass, "puremetal$deferOcclusionUpdate")
                && hasTargetCall(targetClass, "render", "net/minecraft/client/renderer/SectionOcclusionGraph", "update",
                    "(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V")) {
                MixinHooks.confirm(MixinHooks.EARLY_RENDERER);
            }
        } else if (mixinClassName.endsWith(".perf.LevelExtractorEarlyUploadMixin")) {
            if (hasWovenCall(targetClass, "puremetal$flushDeferredBeforeExtract")) {
                MixinHooks.confirm(MixinHooks.EARLY_EXTRACTOR);
            }
        } else if (mixinClassName.endsWith(".perf.SectionRenderDispatcherPacerMixin")) {
            if (hasWovenCall(targetClass, "puremetal$initLoadingState")
                && hasWovenCall(targetClass, "puremetal$onFrameUpload")
                && hasMethodContaining(targetClass, "puremetal$pacedSchedule")
                && hasTargetCall(targetClass, "schedule", "net/minecraft/TracingExecutor", "execute", "(Ljava/lang/Runnable;)V")
                && hasMethodContaining(targetClass, "puremetal$trackAcquire")
                && hasTargetCall(targetClass, "runTask", "net/minecraft/client/renderer/SectionBufferBuilderPool", "acquire", "()Lnet/minecraft/client/renderer/SectionBufferBuilderPack;")
                && hasMethodContaining(targetClass, "puremetal$suppressRecursiveDispatch")
                && hasTargetCall(targetClass, "runTask", "net/minecraft/TracingExecutor", "execute", "(Ljava/lang/Runnable;)V")) {
                MixinHooks.confirm(MixinHooks.PACER_ALL);
            }
        } else if (mixinClassName.endsWith(".ui.sodium.AbstractParentWidgetInvoker")) {
            if (hasConcreteMethod(targetClass, "puremetal$addRenderableChild")) {
                MixinHooks.confirm(MixinHooks.SODIUM_UI_BRIDGE);
            }
        }
    }
}
