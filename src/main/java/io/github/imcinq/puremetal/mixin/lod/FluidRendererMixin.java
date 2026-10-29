package io.github.imcinq.puremetal.mixin.lod;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import io.github.imcinq.puremetal.lod.CoarseMesher;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla fluid renderer: the tops that the LOD mesher merged into one 2x2 water quad are not drawn again. */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixin {
    /** The first same-fluid test in tesselate decides whether the top is drawn (renderUp = !sameFluid(above)). */
    @ModifyExpressionValue(
        method = "tesselate",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;isNeighborSameFluid(Lnet/minecraft/world/level/material/FluidState;Lnet/minecraft/world/level/material/FluidState;)Z", ordinal = 0)
    )
    private boolean puremetal$skipMergedTop(final boolean sameFluidAbove, @Local(argsOnly = true) final BlockPos pos) {
        if (!sameFluidAbove) {
            CoarseMesher mesher = CoarseMesher.current();
            if (mesher != null && mesher.hidesWaterTop(pos)) {
                return true;
            }
        }
        return sameFluidAbove;
    }
}
