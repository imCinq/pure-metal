package io.github.imcinq.puremetal.perf;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import io.github.imcinq.puremetal.scale.ScaleConfig;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * Optional smooth frame pacing: with VSync on, each frame is scheduled for the display refresh after the previous one
 * instead of going out the moment it is ready, so frames reach the screen at an even rhythm. It never delays a late frame.
 * Off by default and experimental; toggled in Pure Metal's settings (stored as smoothFramePacing in
 * config/puremetal.properties), or forced at startup with -Dpuremetal.smoothFramePacing=true|false.
 *
 * Also holds High Refresh Boost (off by default and experimental, stored as highRefreshBoost): with VSync off on a display
 * faster than 120 Hz, each frame is shown as soon as it is ready instead of at the next refresh, which may tear.
 * -Dpuremetal.allowTearing=true|false decides that for the whole session instead, on any display.
 */
public final class FramePacing {
    private static final long REFRESH_RECHECK_NS = 1_000_000_000L;
    /** High Refresh Boost acts on displays faster than 120 Hz; surface_display_sync in the native code has the same limit. */
    private static final long BOOST_REFRESH_NS = 8_333_333L;
    /** -Dpuremetal.allowTearing: 1 (true) or 0 (false) for the whole session, -1 when it is not set. */
    private static final int FORCED_TEARING = System.getProperty("puremetal.allowTearing") == null ? -1
        : Boolean.getBoolean("puremetal.allowTearing") ? 1 : 0;

    private static volatile boolean enabled = readEnabled();
    private static volatile boolean boost = saved("highRefreshBoost");

    private static long refreshNs;
    private static long refreshCheckedNs;
    private static long sentRefreshNs = -1L;
    private static int sentPacing = -1;
    private static int sentBoost;

    private FramePacing() {
    }

    private static boolean readEnabled() {
        String property = System.getProperty("puremetal.smoothFramePacing");
        if (property != null) {
            return Boolean.parseBoolean(property.trim());
        }
        return saved("smoothFramePacing");
    }

    /** A missing key means off. */
    private static boolean saved(final String key) {
        Properties p = ScaleConfig.readProperties();
        return p != null && Boolean.parseBoolean(p.getProperty(key, "false").trim());
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(final boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        ScaleConfig.writeProperties(p -> p.setProperty("smoothFramePacing", Boolean.toString(value)));
    }

    public static boolean boostEnabled() {
        return boost;
    }

    public static void setBoostEnabled(final boolean value) {
        if (value == boost) {
            return;
        }
        boost = value;
        ScaleConfig.writeProperties(p -> p.setProperty("highRefreshBoost", Boolean.toString(value)));
    }

    /** True while -Dpuremetal.allowTearing is set: it decides instead of High Refresh Boost, so the toggle does nothing. */
    public static boolean boostForced() {
        return FORCED_TEARING >= 0;
    }

    /** True when the window's display is fast enough for High Refresh Boost to act. Render thread. */
    public static boolean boostDisplay() {
        return refreshNs > 0L && refreshNs < BOOST_REFRESH_NS;
    }

    /**
     * What the native surface does with VSync off: 1 never waits for the refresh, 0 always does, -1 (High Refresh Boost)
     * stops waiting on displays faster than 120 Hz.
     */
    public static int allowTearing() {
        return FORCED_TEARING >= 0 ? FORCED_TEARING : boost ? -1 : 0;
    }

    /**
     * Render thread, once per frame: the refresh interval of the window's display (looked up once a second; also used
     * for the hitch count in the present stats), whether presents are paced right now, and allowTearing(). Returns true
     * when any of them changed, so the caller passes them to the native surface.
     */
    public static boolean update(final boolean vsync) {
        long now = System.nanoTime();
        if (refreshCheckedNs == 0L || now - refreshCheckedNs > REFRESH_RECHECK_NS) {
            refreshCheckedNs = now;
            Monitor monitor = Minecraft.getInstance().getWindow().findBestMonitor();
            VideoMode mode = monitor == null ? null : monitor.currentMode();
            float hz = mode == null ? 0.0F : mode.getRefreshRate();
            refreshNs = hz > 1.0F ? Math.round(1.0e9 / hz) : 0L;
        }
        int pacing = enabled && vsync && refreshNs > 0L ? 1 : 0;
        int tearing = allowTearing();
        if (pacing == sentPacing && refreshNs == sentRefreshNs && tearing == sentBoost) {
            return false;
        }
        sentPacing = pacing;
        sentRefreshNs = refreshNs;
        sentBoost = tearing;
        return true;
    }

    public static long refreshNs() {
        return refreshNs;
    }

    public static int pacing() {
        return sentPacing;
    }
}
