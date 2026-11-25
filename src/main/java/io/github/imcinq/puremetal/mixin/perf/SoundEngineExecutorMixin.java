package io.github.imcinq.puremetal.mixin.perf;

import io.github.imcinq.puremetal.perf.ThreadScheduler;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Promotes the {@code Sound engine} thread to {@code User Initiated} QoS so {@code SoundEngine.play()}'s synchronous
 * channel-creation join on the render thread does not queue behind CPU-bound {@code Worker-Main} threads.
 */
@Mixin(SoundEngineExecutor.class)
public abstract class SoundEngineExecutorMixin {
    @Unique
    private boolean puremetal$qosApplied;

    @Inject(method = "run", at = @At("HEAD"))
    private void puremetal$promoteSoundThreadOnStart(final CallbackInfo ci) {
        this.puremetal$qosApplied = ThreadScheduler.promoteSoundThread();
    }

    @Inject(method = "waitForTasks", at = @At("HEAD"))
    private void puremetal$promoteSoundThreadOnWait(final CallbackInfo ci) {
        if (!this.puremetal$qosApplied) {
            this.puremetal$qosApplied = ThreadScheduler.promoteSoundThread();
        }
    }
}
