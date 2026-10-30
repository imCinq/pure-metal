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
 */
public final class FramePacing {
    private static final long REFRESH_RECHECK_NS = 1_000_000_000L;

    private static volatile boolean enabled = readEnabled();

    private static long refreshNs;
    private static long refreshCheckedNs;
    private static long sentRefreshNs = -1L;
    private static int sentPacing = -1;

    private FramePacing() {
    }

    private static boolean readEnabled() {
        String property = System.getProperty("puremetal.smoothFramePacing");
        if (property != null) {
            return Boolean.parseBoolean(property.trim());
        }
        Properties p = ScaleConfig.readProperties();
        return p != null && Boolean.parseBoolean(p.getProperty("smoothFramePacing", "false").trim());
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

    /**
     * Render thread, once per frame: the refresh interval of the window's display (looked up once a second; also used
     * for the hitch count in the present stats) and whether presents are paced right now. Returns true when either changed,
     * so the caller passes them to the native surface.
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
        if (pacing == sentPacing && refreshNs == sentRefreshNs) {
            return false;
        }
        sentPacing = pacing;
        sentRefreshNs = refreshNs;
        return true;
    }

    public static long refreshNs() {
        return refreshNs;
    }

    public static int pacing() {
        return sentPacing;
    }
}
