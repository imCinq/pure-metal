package io.github.imcinq.puremetal.mixin.perf;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import io.github.imcinq.puremetal.perf.NativeTerrainShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Marks the backend compile of the pinned Sodium solid terrain shader so the backend may use its native MSL (require = 0: no proof, no change). */
@Mixin(PipelineBuilder.class)
public abstract class NativeTerrainShaderMixin {
    @WrapOperation(method = "lambda$compilePipeline$0", require = 0,
        at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/api/GpuDeviceBackend;compilePipeline(Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline$CreateInfo;)Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline$Pending;"))
    private BackendRenderPipeline.Pending puremetal$nativeTerrain(final GpuDeviceBackend backend, final BackendRenderPipeline.CreateInfo info,
        final Operation<BackendRenderPipeline.Pending> original, @Local(argsOnly = true) final RenderPipeline pipeline,
        @Local(argsOnly = true) final ShaderSource source) {
        try (var scope = NativeTerrainShader.enter(pipeline, source)) {
            return original.call(backend, info);
        }
    }
}
