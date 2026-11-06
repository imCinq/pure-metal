package io.github.imcinq.puremetal.perf;

import io.github.imcinq.puremetal.Lang;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
/** Name, tooltip and the vanilla Pure Metal screen entry for smooth frame pacing (Sodium's page reuses the text). */
public final class FramePacingOptions {
    private FramePacingOptions() {
    }

    public static Component name() {
        return Lang.text("puremetal.options.smoothFramePacing");
    }

    public static Component tooltip() {
        return Lang.text("puremetal.options.smoothFramePacing.tooltip");
    }

    /** Built on each call so the initial value is read from the live option (Sodium's page can change it). */
    public static OptionInstance<Boolean> vanilla() {
        return Lang.withCaption(OptionInstance.createBoolean(
            "puremetal.options.smoothFramePacing",
            value -> Tooltip.create(tooltip()),
            (caption, value) -> value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF,
            FramePacing.enabled(),
            FramePacing::setEnabled
        ), name());
    }
}
