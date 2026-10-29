package io.github.imcinq.puremetal.mixin.lod.sodium;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.imcinq.puremetal.lod.CoarseMesher;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.DefaultFluidRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Sodium fluid renderer: the tops that the LOD mesher merged into one 2x2 water quad are not drawn again. */
@Mixin(value = DefaultFluidRenderer.class, remap = false)
public abstract class DefaultFluidRendererMixin {
    /** The first visibility test in render is the top face (isFullBlockFluidVisible with Direction.UP). */
    @ModifyExpressionValue(
        method = "render",
        at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/DefaultFluidRenderer;isFullBlockFluidVisible(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)Z", ordinal = 0)
    )
    private boolean puremetal$skipMergedTop(final boolean visible, @Local(argsOnly = true, ordinal = 0) final BlockPos blockPos) {
        if (visible) {
            CoarseMesher mesher = CoarseMesher.current();
            if (mesher != null && mesher.hidesWaterTop(blockPos)) {
                return false;
            }
        }
        return visible;
    }
}
