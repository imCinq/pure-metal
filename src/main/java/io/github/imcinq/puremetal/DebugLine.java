package io.github.imcinq.puremetal;

import io.github.imcinq.puremetal.backend.DistantHorizonsExperiment;
import io.github.imcinq.puremetal.backend.MetalInfo;
import io.github.imcinq.puremetal.scale.RenderScale;
import io.github.imcinq.puremetal.scale.ScaleConfig;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/** The "Pure Metal: ..." line of the F3 screen. The text is rebuilt only when one of its inputs changes. */
public final class DebugLine implements DebugScreenEntry {
    public static final Identifier ID = Identifier.fromNamespaceAndPath("puremetal", "pure_metal");
    public static final DebugLine INSTANCE = new DebugLine();
    private static final String INACTIVE = "Pure Metal: inactive (OpenGL)";

    private @Nullable String backend;
    private String gpu = "";
    private int scale = -1;
    private boolean auto;
    private ScaleConfig.@Nullable Upscaler upscaler;
    private String text = INACTIVE;

    private DebugLine() {
    }

    @Override
    public void display(final DebugScreenDisplayer displayer, final @Nullable Level level, final @Nullable LevelChunk clientChunk,
                        final @Nullable LevelChunk serverChunk) {
        try {
            displayer.addLine(line());
            if (MetalInfo.backend() != null) {
                displayer.addLine(io.github.imcinq.puremetal.perf.TerrainMathStatus.line().getString());
            }
        } catch (Throwable t) {
            displayer.addLine("Pure Metal: unavailable");
        }
    }

    private String line() {
        String now = MetalInfo.backend();
        if (now == null) {
            this.backend = null;
            String dh = DistantHorizonsExperiment.debugNote();
            // "DH: on" describes the preflight, which is misleading when Pure Metal is not the one rendering.
            return dh == null || !dh.startsWith("DH: off") ? INACTIVE : INACTIVE + " | " + dh;
        }
        String name = MetalInfo.gpuName();
        int percent = ScaleConfig.scale();
        boolean autoScale = ScaleConfig.autoActive();
        ScaleConfig.Upscaler kind = RenderScale.lastUsedUpscaler();
        if (!now.equals(this.backend) || !name.equals(this.gpu) || percent != this.scale || autoScale != this.auto || kind != this.upscaler) {
            this.backend = now;
            this.gpu = name;
            this.scale = percent;
            this.auto = autoScale;
            this.upscaler = kind;
            String scaler = percent >= ScaleConfig.MAX_SCALE || kind == null ? "Native" : switch (kind) {
                case METALFX -> "Spatial";
                case TEMPORAL -> "Temporal";
                case BILINEAR -> "Bilinear";
            };
            String dh = DistantHorizonsExperiment.debugNote();
            this.text = "Pure Metal: " + now + (name.isEmpty() ? "" : " | " + name) + " | " + scaler + (autoScale ? " Auto " : " ") + percent + "%" + (dh == null ? "" : " | " + dh);
        }
        return this.text;
    }
}
