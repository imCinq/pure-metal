package io.github.imcinq.puremetal.mixin;

import io.github.imcinq.puremetal.Lang;
import io.github.imcinq.puremetal.scale.PureMetalOptionsScreen;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds a "Pure Metal" section to Video Settings with one button that opens Pure Metal's own screen; Sodium's screen gets its own page instead. */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Inject(method = "addOptions", at = @At("TAIL"))
    private void puremetal$addSection(final CallbackInfo ci) {
        OptionsList list = ((OptionsSubScreenAccessor) this).puremetal$list();
        VideoSettingsScreen screen = (VideoSettingsScreen) (Object) this;
        list.addHeader(Component.literal("Pure Metal"));
        Button open = Button.builder(Lang.text("puremetal.options.button"), button -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.gui.setScreen(new PureMetalOptionsScreen(screen, minecraft.options));
        }).width(list.getRowWidth()).build();
        list.addSmall(List.of(open));
    }
}
