package io.github.imcinq.puremetal.perf;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import io.github.imcinq.puremetal.backend.MetalNative;
import io.github.imcinq.puremetal.scale.ScaleConfig;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * Optional Battery Saver: caps FPS at 60 (or the display's refresh rate when that is lower) and, with Auto Render Scale on,
 * lets it trade sharpness for power. When macOS reports a serious or critical thermal state, or Low Power Mode is on, it backs
 * off further (30 FPS, lower Auto Render Scale) and only returns to normal after about ten calm seconds. A lower Max FPS the
 * player set is never raised. Off by default; toggled in Pure Metal's settings (stored as batterySaver in
 * config/puremetal.properties), or forced at startup with -Dpuremetal.batterySaver=true|false.
 * While off, the only cost is reading one flag. Render thread only, except the flag.
 */
public final class BatterySaver {
    private static final int NORMAL_CAP = 60;
    private static final int BACKOFF_CAP = 30;
    private static final long POLL_NS = 1_000_000_000L;
    /** How long the Mac must be calm before backing off ends. */
    private static final long CALM_NS = 10_000_000_000L;
    /** Share of the frame time Auto Render Scale tolerates for GPU work (1 = all of it): a lower share picks a lower scale. */
    private static final double NORMAL_BUDGET = 0.75;
    private static final double BACKOFF_BUDGET = 0.5;
    /** Backing off measures its share against the 60 FPS frame time, never the 30 FPS cap, so its budget stays below normal. */
    private static final double BACKOFF_FRAME_MS = 1000.0 / NORMAL_CAP;

    /** Debug: -Dpuremetal.batterySaverForceBackoff=true acts as if the thermal state were serious, to test the 30 FPS backoff. */
    private static final boolean FORCE_BACKOFF = Boolean.getBoolean("puremetal.batterySaverForceBackoff");

    private static volatile boolean enabled = readEnabled();

    /** Time of the last native poll (0 = never); survives enable and reset. */
    private static long polledNs;
    private static boolean backingOff;
    private static boolean calm;
    private static long calmSinceNs;
    private static int refreshHz;
    /** The FPS cap right now; 0 until the first poll. */
    private static int cap;

    private BatterySaver() {
    }

    private static boolean readEnabled() {
        String property = System.getProperty("puremetal.batterySaver");
        if (property != null) {
            return Boolean.parseBoolean(property.trim());
        }
        Properties p = ScaleConfig.readProperties();
        return p != null && Boolean.parseBoolean(p.getProperty("batterySaver", "false").trim());
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(final boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        resetState();
        ScaleConfig.writeProperties(p -> p.setProperty("batterySaver", Boolean.toString(value)));
    }

    /** Bench only: switches Battery Saver in memory without writing the config. */
    public static void setEnabledTransient(final boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        resetState();
    }

    /** Clears the power state but keeps the last poll time, so toggling never bypasses the once-a-second native poll. */
    private static void resetState() {
        backingOff = false;
        calm = false;
        cap = 0;
    }

    /** Render thread, once per frame: the frame limit vanilla is about to use. */
    public static int apply(final int limit) {
        if (!enabled) {
            return limit;
        }
        poll();
        return cap > 0 ? Math.min(limit, cap) : limit;
    }

    /** Auto Render Scale's FPS target, held to the cap while Battery Saver is on. */
    public static int capFps(final int fps) {
        return enabled && cap > 0 ? Math.min(fps, cap) : fps;
    }

    /** Backing off for heat or Low Power Mode right now. */
    public static boolean backingOff() {
        return enabled && backingOff;
    }

    /**
     * The GPU time in milliseconds Auto Render Scale may use per frame at the given FPS target: the whole frame time when off,
     * 75% of it when on, and while backing off 50% of the 60 FPS frame time at most (always below the normal budget).
     */
    public static double budgetMs(final int targetFps) {
        double frameMs = 1000.0 / targetFps;
        if (!enabled) {
            return frameMs;
        }
        return backingOff ? Math.min(frameMs, BACKOFF_FRAME_MS) * BACKOFF_BUDGET : frameMs * NORMAL_BUDGET;
    }

    /** For the F3 line: 0 off, 1 on, 2 on and backing off. */
    public static int debugState() {
        return !enabled ? 0 : backingOff ? 2 : 1;
    }

    /** At most once a second: reads the thermal state and Low Power Mode, the display's refresh rate, and recomputes the cap. */
    private static void poll() {
        long now = System.nanoTime();
        if (polledNs != 0L && now - polledNs < POLL_NS) {
            return;
        }
        polledNs = now;
        int state = MetalNative.powerState();
        boolean hot = FORCE_BACKOFF || (state & 3) >= 2 || (state & 4) != 0;
        if (hot) {
            backingOff = true;
            calm = false;
        } else if (backingOff) {
            if (!calm) {
                calm = true;
                calmSinceNs = now;
            } else if (now - calmSinceNs >= CALM_NS) {
                backingOff = false;
                calm = false;
            }
        }
        Monitor monitor = Minecraft.getInstance().getWindow().findBestMonitor();
        VideoMode mode = monitor == null ? null : monitor.currentMode();
        float hz = mode == null ? 0.0F : mode.getRefreshRate();
        refreshHz = hz > 1.0F ? Math.round(hz) : 0;
        int base = backingOff ? BACKOFF_CAP : NORMAL_CAP;
        cap = refreshHz > 0 ? Math.min(base, refreshHz) : base;
    }
}
