package io.github.imcinq.puremetal.scale;

import io.github.imcinq.puremetal.Lang;
import io.github.imcinq.puremetal.lod.LodOptions;
import io.github.imcinq.puremetal.perf.GenerationCapOptions;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.FocusableTextWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** Pure Metal's own screen, opened from Video Settings: Resolution, Terrain, Miscellaneous and Experimental (Sodium's page groups the same options). */
public final class PureMetalOptionsScreen extends OptionsSubScreen {
    public PureMetalOptionsScreen(final Screen lastScreen, final Options options) {
        super(lastScreen, options, Component.literal("Pure Metal"));
    }

    @Override
    protected void addOptions() {
        FocusableTextWidget note = FocusableTextWidget.builder(io.github.imcinq.puremetal.perf.TerrainMathStatus.noteWithStatus(), this.getFont(), 4)
            .maxWidth(this.list.getRowWidth())
            .alwaysShowBorder(false)
            .backgroundFill(FocusableTextWidget.BackgroundFill.NEVER)
            .build();
        note.setCentered(true);
        final NoteEntry entry = new NoteEntry(note);
        this.list.addEntry(entry, entry.rowHeight());
        this.list.addHeader(Lang.text("puremetal.options.section.resolution"));
        this.list.addSmall(ScaleOptions.vanillaResolution());
        this.list.addHeader(Lang.text("puremetal.options.section.terrain"));
        this.list.addSmall(LodOptions.vanilla());
        this.list.addHeader(Lang.text("puremetal.options.section.misc"));
        this.list.addSmall(GenerationCapOptions.vanilla(), io.github.imcinq.puremetal.perf.BatterySaverOptions.vanilla());
        this.list.addHeader(Lang.text("puremetal.options.section.experimental"));
        this.list.addSmall(ScaleOptions.vanillaMetal4(), io.github.imcinq.puremetal.perf.FramePacingOptions.vanilla());
        // This screen is rebuilt on each open, so High Refresh Boost is only listed on a display it can act on.
        this.list.addSmall(io.github.imcinq.puremetal.perf.HdrHighlightsOptions.vanilla(),
            io.github.imcinq.puremetal.perf.FramePacing.boostDisplay() ? io.github.imcinq.puremetal.perf.HighRefreshBoostOptions.vanilla() : null);
    }
}
