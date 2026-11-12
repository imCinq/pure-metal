package io.github.imcinq.puremetal.backend;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Relaxed (fast) shader math for terrain. Relaxed math in the terrain vertex shader is a large GPU win (about 40% of terrain GPU time
 * measured), but it is not safe for post effects, sky, fog, OIT resolve or GUI, so only pipelines whose vertex and fragment sources
 * proved to be the stock terrain ones get it ({@link io.github.imcinq.puremetal.perf.TerrainShaderProof#relaxedMath()}). Every terrain
 * pipeline (solid, cutout, translucent, the OIT passes, the block variants, Sodium) switches as one unit, so all passes compute the same
 * positions. Everything else keeps strict math.
 *
 * <p>-Dpuremetal.fastTerrainMath=on|off (default on; the old opaque and all values mean on). Diagnostic A/B: -PbenchAB=fastTerrainMath
 * builds both math variants per terrain pipeline and toggles them (A = strict, B = shipped relaxed).
 */
public final class FastTerrainMath {
    public static final String SWITCH = "fastTerrainMath";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** The configured default: relaxed terrain math on (no A/B). */
    private static final boolean ENABLED = parse(System.getProperty("puremetal.fastTerrainMath"));
    /** True only in a -PbenchAB=fastTerrainMath run: both variants are built and the bench switch picks one. */
    static final boolean AB = abRequested(System.getProperty("puremetal.benchAB", ""));
    private static final AtomicInteger DRAWN = new AtomicInteger();

    private FastTerrainMath() {
    }

    /** Whether the proof has to run at all: nothing to decide when the feature is off and no A/B needs the variants. */
    public static boolean wanted() {
        return ENABLED || AB;
    }

    private static boolean parse(final String value) {
        if (value == null) {
            return true;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "opaque", "all" -> true;
            case "off", "false" -> false;
            default -> throw new IllegalArgumentException("Unsupported -Dpuremetal.fastTerrainMath=" + value + " (use on or off)");
        };
    }

    /** The old fastTerrain / fastTerrainAll A/B switches are gone; any other fastTerrain* name would silently measure nothing. */
    private static boolean abRequested(final String name) {
        if (name.equals(SWITCH)) {
            return true;
        }
        if (name.toLowerCase(Locale.ROOT).startsWith("fastterrain")) {
            throw new IllegalArgumentException("Unsupported -PbenchAB=" + name + " (the only terrain math switch is " + SWITCH + ")");
        }
        return false;
    }

    /** First draw of a pipeline with relaxed terrain math: one log line each, so the log proves the shipped state is really drawn. */
    static void noteDraw(final String pipeline) {
        int count = DRAWN.incrementAndGet();
        LOGGER.info("Pure Metal: relaxed terrain math active for {} pipeline(s), latest {}", count, pipeline);
    }
}
