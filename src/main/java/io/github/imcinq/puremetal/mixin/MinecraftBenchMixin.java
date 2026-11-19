package io.github.imcinq.puremetal.mixin;

import io.github.imcinq.puremetal.bench.Bench;
import io.github.imcinq.puremetal.bench.FrameProfile;
import io.github.imcinq.puremetal.bench.SubmissionProfile;
import io.github.imcinq.puremetal.bench.TerrainDrawProfile;
import io.github.imcinq.puremetal.bench.TerrainFrameProfile;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opt-in benchmark/profile hook. Optional: never fails the game if it can't apply. */
@Mixin(Minecraft.class)
public class MinecraftBenchMixin {
    @Inject(method = "runTick", at = @At("HEAD"), require = 0)
    private void puremetal$benchFrame(final boolean advanceGameTime, final CallbackInfo ci) {
        if (SubmissionProfile.ENABLED) {
            Minecraft minecraft = (Minecraft)(Object)this;
            FrameProfile.frame(System.nanoTime(), minecraft.level != null && minecraft.gui.screen() == null);
        }
        if (TerrainDrawProfile.ENABLED) {
            TerrainFrameProfile.flush();
        }
        if (Bench.SECONDS > 0) {
            Bench.frame((Minecraft)(Object)this);
        }
    }

    /** Player benchmark: an early quit or leaving the world puts the player's options back in memory before anything saves them. */
    @Inject(method = "stop", at = @At("HEAD"))
    private void puremetal$restoreOnStop(final CallbackInfo ci) {
        Bench.abortOnDisconnect(); // quitting mid-run: an INVALID report, not an older launch's result
        Bench.restoreUserOptions();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;ZZ)V", at = @At("HEAD"))
    private void puremetal$restoreOnDisconnect(final CallbackInfo ci) {
        Bench.abortOnDisconnect();
        Bench.restoreUserOptions();
    }
}
