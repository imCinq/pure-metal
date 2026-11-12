package io.github.imcinq.puremetal.backend;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Relaxed (fast) shader math for terrain. Relaxed math in the terrain vertex shader is a large GPU win (about 40% of terrain GPU time
 * measured), but it is not safe for post effects, sky, fog, OIT resolve or GUI, so only pipelines whose vertex and fragment sources
 * proved to be the stock terrain ones get it ({@link io.github.imcinq.puremetal.perf.TerrainShaderProof#relaxedMath}). Every terrain
 * pipeline (solid, cutout, translucent, the OIT passes, the block variants, Sodium) switches as one unit, so all passes compute the same
 * positions. Everything else keeps strict math.
 *
 * <p>-Dpuremetal.fastTerrainMath=on|off (default on; the old opaque and all values mean on, a typo logs a warning and keeps on). Diagnostic A/B: -PbenchAB=fastTerrainMath
 * builds both math variants per terrain pipeline and toggles them (A = strict, B = shipped relaxed).
 */
public final class FastTerrainMath {
    public static final String SWITCH = "fastTerrainMath";
    private static final Logger LOGGER = LogUtils.getLogger();

    /** macOS 15 added MTLMathModeRelaxed. Before it the only option is the legacy fastMathEnabled, which also assumes no NaN or infinity, so it is not the default. */
    private static final boolean RELAXED_MODE_AVAILABLE = relaxedModeAvailable(System.getProperty("os.name", ""), System.getProperty("os.version", ""));
    /** Why relaxed terrain math is off, or null when it is on: "turned off" (the JVM argument) or "macOS 15 required". Fixed at startup. */
    private static volatile String offReason;
    /** The configured default: relaxed terrain math on (no A/B), except where MTLMathModeRelaxed does not exist (macOS 14 and older). */
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

    static boolean relaxedModeAvailable(final String osName, final String osVersion) {
        if (!osName.toLowerCase(Locale.ROOT).contains("mac")) {
            return true;
        }
        try {
            String major = osVersion.trim().split("\\.")[0];
            return Integer.parseInt(major) >= 15;
        } catch (NumberFormatException unknown) {
            return true;
        }
    }

    /** The reason terrain math is standard (strict) by configuration, or null when it is on. */
    public static String offReason() {
        return offReason;
    }

    private static boolean defaultOn() {
        if (RELAXED_MODE_AVAILABLE) {
            return true;
        }
        offReason = "macOS 15 required";
        LOGGER.info("Pure Metal: relaxed terrain math is off by default on this macOS version (MTLMathModeRelaxed needs macOS 15); -Dpuremetal.fastTerrainMath=on forces the older fast math path");
        return false;
    }

    /** A typo in the JVM argument must never stop the game: warn and keep the default. */
    private static boolean parse(final String value) {
        if (value == null) {
            return defaultOn();
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "on", "true", "opaque", "all" -> {
                if (!RELAXED_MODE_AVAILABLE) {
                    LOGGER.warn("Pure Metal: -Dpuremetal.fastTerrainMath=on on a macOS version without MTLMathModeRelaxed uses the older fast math, which also assumes no NaN or infinity");
                }
                yield true;
            }
            case "off", "false" -> {
                offReason = "turned off";
                yield false;
            }
            default -> {
                LOGGER.warn("Pure Metal: unsupported -Dpuremetal.fastTerrainMath={} (use on or off), using the default", value);
                yield defaultOn();
            }
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

    /** First draw of a terrain pipeline in each math mode: one log line, so the log shows which mode was really bound (vertex and fragment). */
    static void noteDraw(final String pipeline, final boolean relaxed) {
        if (relaxed) {
            int count = DRAWN.incrementAndGet();
            LOGGER.info("Pure Metal: terrain draw with relaxed math (vertex and fragment): {} (relaxed terrain math active for {} pipeline(s))", pipeline, count);
        } else {
            LOGGER.info("Pure Metal: terrain draw with strict math: {}", pipeline);
        }
    }
}
