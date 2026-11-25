package io.github.imcinq.puremetal.perf;

import io.github.imcinq.puremetal.backend.MetalUpscaler;
import java.util.function.BooleanSupplier;

/**
 * Policy gate for deferring {@code SectionRenderDispatcher.uploadTerrainBuffersToGpu()} and the immediately following
 * {@code SectionOcclusionGraph.update(...)} from the end of {@code LevelRenderer.render()} to
 * {@code LevelRenderer.endFrame()} while rendering on Pure Metal.
 *
 * <p>In vanilla, {@code uploadTerrainBuffersToGpu()} runs after the frame graph has already drawn from the chunk Uber
 * buffers in the current submit, so {@code MetalCommandEncoder.writeToBuffer} cannot take the early upload path and
 * closes the parked Sky/Main render pass before GUI (forcing a full-resolution color+depth store/reload).
 * {@code Minecraft.renderFrame()} calls {@code LevelRenderer.endFrame()} after {@code CommandEncoder.submit()} advances
 * {@code MetalBuffer.recordingSubmit} (even when presentation is skipped) and before the next frame's
 * {@code LevelExtractor.extract()}. Running both steps there preserves vanilla ordering while keeping destination
 * buffers {@code unusedThisSubmit()} so uploads go into the early command buffer and the main+GUI pass stays merged.
 *
 * <p>Opt-out: {@code -Dpuremetal.disableEarlyTerrainUpload=true}.
 */
public final class EarlyTerrainUpload {
    /** Implemented by {@code LevelRendererEarlyUploadMixin} so {@code LevelExtractor} can flush before resetting state. */
    public interface DeferredFlush {
        void puremetal$flushDeferredUpload();
    }

    public static final boolean ENABLED = !Boolean.getBoolean("puremetal.disableEarlyTerrainUpload");
    private static volatile BooleanSupplier metalActive = MetalUpscaler::active;

    private EarlyTerrainUpload() {
    }

    /** True when terrain uploads and occlusion updates should be deferred to {@code LevelRenderer.endFrame()}. */
    public static boolean active() {
        return ENABLED && metalActive.getAsBoolean();
    }

    /** Test hook: injects deterministic backend readiness. */
    public static void setMetalActiveForTest(final BooleanSupplier active) {
        metalActive = active;
    }
}
