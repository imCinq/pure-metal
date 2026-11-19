package io.github.imcinq.puremetal.perf;

import io.github.imcinq.puremetal.Lang;
import io.github.imcinq.puremetal.scale.ScaleOptions;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/** Name, tooltip and the vanilla Pure Metal screen entry for High Refresh Boost (Sodium's page reuses the text). */
public final class HighRefreshBoostOptions {
    private HighRefreshBoostOptions() {
    }

    public static Component name() {
        return Lang.text("puremetal.options.highRefreshBoost");
    }

    public static Component tooltip() {
        Component base = Lang.text("puremetal.options.highRefreshBoost.tooltip");
        Component status = status();
        return status == null ? base : Component.empty().append(base).append(Component.literal("\n\n")).append(status);
    }

    /** Why the option does nothing right now, or null when it can act. */
    private static Component status() {
        if (FramePacing.boostForced()) {
            return Lang.text("puremetal.options.highRefreshBoost.forced");
        }
        if (Minecraft.getInstance().options.enableVsync().get()) {
            return Lang.text("puremetal.options.highRefreshBoost.vsyncOn");
        }
        int hz = FramePacing.refreshHz();
        if (hz > 0 && !FramePacing.boostDisplay()) {
            return Lang.text("puremetal.options.highRefreshBoost.slowDisplay", hz);
        }
        return null;
    }

    /**
     * Built on each call so the initial value is read from the live option (Sodium's page can change it). The button is
     * inactive while -Dpuremetal.allowTearing decides instead.
     */
    public static OptionInstance<Boolean> vanilla() {
        return Lang.withCaption(new OptionInstance<>(
            "puremetal.options.highRefreshBoost",
            value -> Tooltip.create(tooltip()),
            (caption, value) -> value ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF,
            FramePacing.boostForced() ? new ScaleOptions.LockedBooleanValues() : OptionInstance.BOOLEAN_VALUES,
            FramePacing.boostEnabled(),
            FramePacing::setBoostEnabled
        ), name());
    }
}
