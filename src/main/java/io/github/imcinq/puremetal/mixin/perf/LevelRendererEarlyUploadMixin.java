package io.github.imcinq.puremetal.mixin.perf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.imcinq.puremetal.perf.EarlyTerrainUpload;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.ChunkLoadingRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Defers {@code SectionRenderDispatcher.uploadTerrainBuffersToGpu()} and the immediately following
 * {@code SectionOcclusionGraph.update(...)} from the end of {@code LevelRenderer.render()} to
 * {@code LevelRenderer.endFrame()} when Pure Metal is active.
 *
 * <p>{@code Minecraft.renderFrame()} calls {@code LevelRenderer.endFrame()} unconditionally after
 * {@code CommandEncoder.submit()} advances {@code MetalBuffer.recordingSubmit} (whether or not a drawable is presented)
 * and before the next frame's {@code LevelExtractor.extract()}. Running both steps there preserves vanilla ordering
 * ({@code upload N -> occlusion update N -> extract N+1 -> draw N+1}) while keeping chunk Uber buffers
 * {@code unusedThisSubmit()} so uploads take the early command buffer and never split the Sky/Main pass before GUI.
 *
 * <p>While {@code levelRenderState.playerCompiledSectionCallback} is pending (world/dimension entry), deferral is
 * skipped so vanilla's immediate {@code isSectionCompiledAndVisible} check sees freshly uploaded meshes on the same
 * frame. Any non-standard caller that invokes {@code extract()} + {@code render()} without {@code endFrame()} (e.g.,
 * panorama or offscreen renders) flushes any pending deferral at {@code LevelExtractor.extract()} HEAD before
 * {@code LevelRenderState.reset()} and {@code flipUpdateTrackingSets()} clear chunk-loading deltas (with a fallback
 * check at {@code render()} HEAD for callers that skip {@code extract()}).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererEarlyUploadMixin implements EarlyTerrainUpload.DeferredFlush {
    @Shadow
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

    @Shadow @Final
    private SectionOcclusionGraph sectionOcclusionGraph;

    @Shadow @Final
    private LevelRenderState levelRenderState;

    @Unique
    private @Nullable SectionRenderDispatcher puremetal$deferredDispatcher;
    @Unique
    private boolean puremetal$hasDeferredOcclusion;
    @Unique
    private @Nullable CameraRenderState puremetal$deferredCamera;
    @Unique
    private int puremetal$deferredFov;
    @Unique
    private @Nullable ChunkLoadingRenderState puremetal$deferredChunkLoading;

    @Override
    public void puremetal$flushDeferredUpload() {
        if (this.puremetal$deferredDispatcher != null) {
            this.puremetal$flushDeferred();
        }
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void puremetal$flushPendingBeforeRender(final CallbackInfo ci) {
        if (this.puremetal$deferredDispatcher != null) {
            this.puremetal$flushDeferred();
        }
    }

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"
        )
    )
    private void puremetal$deferTerrainUpload(
        final SectionRenderDispatcher dispatcher, final Operation<Void> original
    ) {
        if (!EarlyTerrainUpload.active() || this.levelRenderState.playerCompiledSectionCallback != null) {
            this.puremetal$clearDeferred();
            original.call(dispatcher);
            return;
        }
        this.puremetal$deferredDispatcher = dispatcher;
    }

    @WrapOperation(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/SectionOcclusionGraph;update(Lnet/minecraft/client/renderer/state/level/CameraRenderState;ILnet/minecraft/client/renderer/state/level/ChunkLoadingRenderState;)V"
        )
    )
    private void puremetal$deferOcclusionUpdate(
        final SectionOcclusionGraph graph, final CameraRenderState camera, final int fov,
        final ChunkLoadingRenderState chunkLoading, final Operation<Void> original
    ) {
        if (this.puremetal$deferredDispatcher == null) {
            original.call(graph, camera, fov, chunkLoading);
            return;
        }
        this.puremetal$hasDeferredOcclusion = true;
        this.puremetal$deferredCamera = camera;
        this.puremetal$deferredFov = fov;
        this.puremetal$deferredChunkLoading = chunkLoading;
    }

    @Inject(method = "endFrame", at = @At("TAIL"))
    private void puremetal$flushDeferredTerrainUpload(final CallbackInfo ci) {
        this.puremetal$flushDeferred();
    }

    @Inject(method = "invalidateCompiledGeometry", at = @At("HEAD"))
    private void puremetal$clearDeferredOnInvalidate(final CallbackInfo ci) {
        this.puremetal$clearDeferred();
    }

    @Inject(method = "resetLevelRenderData", at = @At("HEAD"))
    private void puremetal$clearDeferredOnReset(final CallbackInfo ci) {
        this.puremetal$clearDeferred();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void puremetal$clearDeferredOnClose(final CallbackInfo ci) {
        this.puremetal$clearDeferred();
    }

    @Unique
    private void puremetal$flushDeferred() {
        SectionRenderDispatcher dispatcher = this.puremetal$deferredDispatcher;
        boolean runOcclusion = this.puremetal$hasDeferredOcclusion;
        CameraRenderState camera = this.puremetal$deferredCamera;
        int fov = this.puremetal$deferredFov;
        ChunkLoadingRenderState chunkLoading = this.puremetal$deferredChunkLoading;
        this.puremetal$clearDeferred();
        if (dispatcher == null || dispatcher != this.sectionRenderDispatcher) {
            return;
        }
        ProfilerFiller profiler = Profiler.get();
        dispatcher.lock();
        profiler.push("uploadTerrainBuffers");
        try {
            dispatcher.uploadTerrainBuffersToGpu();
        } finally {
            profiler.pop();
            dispatcher.unlock();
        }
        if (runOcclusion && camera != null && chunkLoading != null) {
            profiler.push("updateSectionOcclusion");
            try {
                this.sectionOcclusionGraph.update(camera, fov, chunkLoading);
            } finally {
                profiler.pop();
            }
        }
    }

    @Unique
    private void puremetal$clearDeferred() {
        this.puremetal$deferredDispatcher = null;
        this.puremetal$hasDeferredOcclusion = false;
        this.puremetal$deferredCamera = null;
        this.puremetal$deferredFov = 0;
        this.puremetal$deferredChunkLoading = null;
    }
}
