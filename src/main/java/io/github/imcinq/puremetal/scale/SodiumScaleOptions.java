package io.github.imcinq.puremetal.scale;

import io.github.imcinq.puremetal.Redact;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.ConfigState;
import io.github.imcinq.puremetal.Lang;
import io.github.imcinq.puremetal.lod.LodOptions;
import io.github.imcinq.puremetal.backend.Metal4Config;
import io.github.imcinq.puremetal.lod.TerrainLodConfig;
import io.github.imcinq.puremetal.perf.GenerationCapOptions;
import io.github.imcinq.puremetal.perf.GenerationFpsCap;
import net.caffeinemc.mods.sodium.api.config.option.OptionFlag;
import net.caffeinemc.mods.sodium.api.config.option.OptionImpact;
import net.caffeinemc.mods.sodium.api.config.structure.BooleanOptionBuilder;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.resources.Identifier;

/** Render-scale and terrain LOD pages in Sodium's settings screen. Only loaded by Sodium (the sodium:config_api_user entrypoint). */
public final class SodiumScaleOptions implements ConfigEntryPoint {
    @Override
    public void registerConfigLate(final ConfigBuilder builder) {
        if (Boolean.getBoolean("puremetal.mixinAudit")) preloadForAudit();
        builder.registerOwnModOptions()
            .setName("Pure Metal")
            .addPage(builder.createOptionPage()
                .setName(Lang.text("puremetal.options.page"))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Lang.text("puremetal.options.section.resolution"))
                    .addOption(builder.createIntegerOption(id("render_scale"))
                        .setName(ScaleOptions.scaleName())
                        .setTooltip(ScaleOptions::scaleTooltip)
                        .setImpact(OptionImpact.HIGH)
                        .setRange(ScaleConfig.MIN_SCALE, ScaleConfig.MAX_SCALE, 1)
                        .setValueFormatter(ScaleOptions::scaleValue)
                        .setDefaultValue(ScaleConfig.MAX_SCALE)
                        .setEnabledProvider(state -> !state.readBooleanOption(id("auto_render_scale")), id("auto_render_scale"))
                        .setStorageHandler(() -> {
                        })
                        .setBinding(ScaleConfig::setScale, ScaleConfig::savedScale))
                    .addOption(builder.createBooleanOption(id("auto_render_scale"))
                        .setName(ScaleOptions.autoName())
                        .setTooltip(ScaleOptions.autoTooltip())
                        .setImpact(OptionImpact.HIGH)
                        .setDefaultValue(false)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(ScaleConfig::setAutoScale, ScaleConfig::autoScale))
                    .addOption(builder.createEnumOption(id("upscaler"), ScaleConfig.Upscaler.class)
                        .setName(ScaleOptions.upscalerName())
                        .setTooltip(ScaleOptions::upscalerTooltip)
                        .setImpact(OptionImpact.LOW)
                        .setElementNameProvider(ScaleOptions::upscalerLabel)
                        .setDefaultValue(ScaleConfig.Upscaler.BILINEAR)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(ScaleConfig::setUpscaler, ScaleConfig::upscaler)))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Lang.text("puremetal.options.section.terrain"))
                    .addOption(builder.createBooleanOption(id("terrain_lod"))
                        .setName(LodOptions.enabledName())
                        .setTooltip(LodOptions.enabledTooltip())
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setDefaultValue(true)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(TerrainLodConfig::setEnabled, TerrainLodConfig::enabled))
                    .addOption(builder.createIntegerOption(id("terrain_lod_ratio"))
                        .setName(LodOptions.ratioName())
                        .setTooltip(LodOptions.ratioTooltip())
                        .setImpact(OptionImpact.MEDIUM)
                        .setFlags(OptionFlag.REQUIRES_RENDERER_RELOAD)
                        .setRange(TerrainLodConfig.MIN_RATIO, TerrainLodConfig.MAX_RATIO, 5)
                        .setValueFormatter(LodOptions::ratioLabel)
                        .setDefaultValue(TerrainLodConfig.DEFAULT_RATIO)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(TerrainLodConfig::setRatioPercent, TerrainLodConfig::ratioPercent)))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Lang.text("puremetal.options.section.misc"))
                    .addOption(builder.createBooleanOption(id("generation_fps_cap"))
                        .setName(GenerationCapOptions.name())
                        .setTooltip(GenerationCapOptions.tooltip())
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(false)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(GenerationFpsCap::setEnabled, GenerationFpsCap::enabled))
                    .addOption(builder.createBooleanOption(id("battery_saver"))
                        .setName(io.github.imcinq.puremetal.perf.BatterySaverOptions.name())
                        .setTooltip(io.github.imcinq.puremetal.perf.BatterySaverOptions.tooltip())
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(false)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(io.github.imcinq.puremetal.perf.BatterySaver::setEnabled, io.github.imcinq.puremetal.perf.BatterySaver::enabled)))
                .addOptionGroup(builder.createOptionGroup()
                    .setName(Lang.text("puremetal.options.section.experimental"))
                    .addOption(metal4Option(builder))
                    .addOption(builder.createBooleanOption(id("smooth_frame_pacing"))
                        .setName(io.github.imcinq.puremetal.perf.FramePacingOptions.name())
                        .setTooltip(io.github.imcinq.puremetal.perf.FramePacingOptions.tooltip())
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(false)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(io.github.imcinq.puremetal.perf.FramePacing::setEnabled, io.github.imcinq.puremetal.perf.FramePacing::enabled))
                    .addOption(builder.createBooleanOption(id("hdr_highlights"))
                        .setName(io.github.imcinq.puremetal.perf.HdrHighlightsOptions.name())
                        .setTooltip(value -> io.github.imcinq.puremetal.perf.HdrHighlightsOptions.tooltip())
                        .setImpact(OptionImpact.LOW)
                        .setFlags(OptionFlag.REQUIRES_GAME_RESTART)
                        .setDefaultValue(false)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(io.github.imcinq.puremetal.perf.HdrHighlights::setEnabled, io.github.imcinq.puremetal.perf.HdrHighlights::enabled))
                    .addOption(builder.createBooleanOption(id("high_refresh_boost"))
                        .setName(io.github.imcinq.puremetal.perf.HighRefreshBoostOptions.name())
                        .setTooltip(io.github.imcinq.puremetal.perf.HighRefreshBoostOptions.tooltip())
                        .setImpact(OptionImpact.LOW)
                        .setDefaultValue(false)
                        // This page is built at startup, before the refresh rate is known, so the check runs each time the screen opens.
                        .setEnabledProvider(state -> !io.github.imcinq.puremetal.perf.FramePacing.boostForced()
                            && io.github.imcinq.puremetal.perf.FramePacing.boostDisplay(), ConfigState.UPDATE_ON_REBUILD)
                        .setStorageHandler(() -> {
                        })
                        .setBinding(io.github.imcinq.puremetal.perf.FramePacing::setBoostEnabled, io.github.imcinq.puremetal.perf.FramePacing::boostEnabled))));
    }

    private static Identifier id(final String path) {
        return Identifier.fromNamespaceAndPath("puremetal", path);
    }

    /** The Metal 4 toggle: still bound to its saved value, but greyed out where Metal 4 cannot run. */
    private static BooleanOptionBuilder metal4Option(final ConfigBuilder builder) {
        BooleanOptionBuilder option = builder.createBooleanOption(id("metal4"))
            .setName(Lang.text("puremetal.options.metal4.short"))
            .setTooltip(ScaleOptions.metal4Tooltip())
            .setImpact(OptionImpact.LOW)
            .setDefaultValue(false)
            .setStorageHandler(() -> {
            })
            .setBinding(Metal4Config::setEnabled, Metal4Config::enabled);
        return Metal4Config.supported() ? option : option.setEnabled(false);
    }

    /** Debug only: loads the mixin targets through the game classloader so a broken mixin fails at startup, not when the screen opens. */
    private static void preloadForAudit() {
        for (final String name : new String[] {
            "net.caffeinemc.mods.sodium.client.gui.widgets.AbstractParentWidget",
            "net.caffeinemc.mods.sodium.client.gui.widgets.OptionListWidget",
            "net.minecraft.client.gui.screens.options.OptionsSubScreen",
            "net.minecraft.client.gui.screens.options.VideoSettingsScreen"
        }) {
            try {
                Class.forName(name, false, SodiumScaleOptions.class.getClassLoader());
                System.out.println("[PureMetal audit] loaded " + name);
            } catch (final Throwable t) {
                System.out.println("[PureMetal audit] FAILED " + name + ": " + Redact.error(t));
            }
        }
    }
}
