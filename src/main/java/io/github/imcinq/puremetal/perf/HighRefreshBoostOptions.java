package io.github.imcinq.puremetal.perf;

import io.github.imcinq.puremetal.Lang;
import io.github.imcinq.puremetal.scale.ScaleOptions;
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
        return Lang.text("puremetal.options.highRefreshBoost.tooltip");
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
