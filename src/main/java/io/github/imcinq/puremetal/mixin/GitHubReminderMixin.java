package io.github.imcinq.puremetal.mixin;

import io.github.imcinq.puremetal.GitHubReminder;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class GitHubReminderMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void puremetal$reminder(final boolean advanceGameTime, final CallbackInfo ci) {
        GitHubReminder.frame((Minecraft) (Object) this);
    }
}
