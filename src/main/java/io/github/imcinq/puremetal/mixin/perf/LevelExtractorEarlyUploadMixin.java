package io.github.imcinq.puremetal.mixin.perf;

import io.github.imcinq.puremetal.perf.EarlyTerrainUpload;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Flushes any deferred terrain upload + occlusion update before {@code LevelExtractor.extract()} resets
 * {@code LevelRenderState} and flips {@code SectionUpdateTracker}'s added/removed chunk sets. Normal frames flush in
 * {@code LevelRenderer.endFrame()}; this guards offscreen/panorama callers that run {@code extract()} + {@code render()}
 * without calling {@code endFrame()}.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorEarlyUploadMixin {
    @Shadow @Final
    private LevelRenderer levelRenderer;

    @Inject(method = "extract", at = @At("HEAD"))
    private void puremetal$flushDeferredBeforeExtract(final CallbackInfo ci) {
        if (this.levelRenderer instanceof EarlyTerrainUpload.DeferredFlush flush) {
            flush.puremetal$flushDeferredUpload();
        }
    }
}
