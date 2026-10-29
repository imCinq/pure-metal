package io.github.imcinq.puremetal.mixin.lod;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.imcinq.puremetal.lod.CoarseMesher;
import io.github.imcinq.puremetal.lod.LodState;
import io.github.imcinq.puremetal.lod.VanillaEmitter;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla section mesher: merged cells replace their blocks' normal geometry in the outer LOD band. */
@Mixin(SectionCompiler.class)
public abstract class SectionCompilerMixin {
    @Shadow @Final private boolean ambientOcclusion;
    @Shadow @Final private BlockStateModelSet blockModelSet;
    @Shadow @Final private BlockColors blockColors;
    @Shadow @Final private FluidStateModelSet fluidModelSet;

    @Inject(method = "compile", at = @At("HEAD"))
    private void puremetal$beginLod(
        final SectionPos sectionPos, final RenderSectionRegion region, final VertexSorting sorting, final SectionBufferBuilderPack builders,
        final CallbackInfoReturnable<SectionCompiler.Results> cir
    ) {
        CoarseMesher.begin(region, sectionPos.x(), sectionPos.y(), sectionPos.z(), this.ambientOcclusion, this.blockModelSet, this.blockColors, this.fluidModelSet);
    }

    @WrapOperation(
        method = "compile",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFFLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V")
    )
    private void puremetal$coarseBlock(
        final ModelBlockRenderer renderer, final BlockQuadOutput output, final float x, final float y, final float z,
        final BlockAndTintGetter level, final BlockPos pos, final BlockState state, final BlockStateModel model, final long seed,
        final Operation<Void> original
    ) {
        CoarseMesher mesher = CoarseMesher.current();
        if (mesher != null) {
            int lx = pos.getX() & 15;
            int ly = pos.getY() & 15;
            int lz = pos.getZ() & 15;
            if (mesher.covers(lx, ly, lz)) {
                mesher.emitIfOrigin(lx, ly, lz, VanillaEmitter.of(output));
                return;
            }
        }
        original.call(renderer, output, x, y, z, level, pos, state, model, seed);
    }

    /** Emits a merged water top next to the fluid renderer's own faces; the renderer skips those tops itself. */
    @WrapWithCondition(
        method = "compile",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V")
    )
    private boolean puremetal$coarseWater(
        final FluidRenderer renderer, final BlockAndTintGetter level, final BlockPos pos, final FluidRenderer.Output output,
        final BlockState state, final FluidState fluid
    ) {
        CoarseMesher mesher = CoarseMesher.current();
        if (mesher != null) {
            int lx = pos.getX() & 15;
            int ly = pos.getY() & 15;
            int lz = pos.getZ() & 15;
            if (mesher.startsWaterTop(lx, ly, lz)) {
                mesher.emitWaterTop(lx, ly, lz, VanillaEmitter.ofFluid(output));
            }
        }
        return true;
    }

    @Inject(method = "compile", at = @At("RETURN"))
    private void puremetal$endLod(
        final SectionPos sectionPos, final RenderSectionRegion region, final VertexSorting sorting, final SectionBufferBuilderPack builders,
        final CallbackInfoReturnable<SectionCompiler.Results> cir
    ) {
        ((LodState) (Object) cir.getReturnValue()).puremetal$setLodState(CoarseMesher.end());
        VanillaEmitter.end();
    }
}
