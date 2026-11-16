package io.github.imcinq.puremetal.perf;

import com.mojang.blaze3d.platform.Monitor;
import com.mojang.blaze3d.platform.VideoMode;
import io.github.imcinq.puremetal.backend.MetalNative;
import io.github.imcinq.puremetal.scale.ScaleConfig;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * Optional Battery Saver: only caps FPS, at 60 (or the display's refresh rate when that is lower). When macOS reports a serious
 * or critical thermal state it backs off to 30 FPS and only returns to normal after about ten calm seconds. Low Power Mode does
 * nothing. It never touches Render Scale or Auto Render Scale. A lower Max FPS the player set is never raised. Off by default;
 * toggled in Pure Metal's settings (stored as batterySaver in config/puremetal.properties), or forced at startup with
 * -Dpuremetal.batterySaver=true|false.
 * While off, the only cost is reading one flag. Render thread only, except the flag.
 */
public final class BatterySaver {
    private static final int NORMAL_CAP = 60;
    private static final int BACKOFF_CAP = 30;
    private static final long POLL_NS = 1_000_000_000L;
    /** How long the Mac must be calm before backing off ends. */
    private static final long CALM_NS = 10_000_000_000L;

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
        if (ScaleConfig.benchLocked()) {
            return;
        }
        if (value == enabled) {
            return;
        }
        enabled = value;
        resetState();
        ScaleConfig.writeProperties(p -> p.setProperty("batterySaver", Boolean.toString(value)));
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

    /** The active cap (60, or 30 when backing off, at most the refresh rate); 0 when Battery Saver is off or has not polled yet. */
    public static int activeCap() {
        return enabled ? cap : 0;
    }

    /** For the F3 line: 0 off, 1 on, 2 on and backing off. */
    public static int debugState() {
        return !enabled ? 0 : backingOff ? 2 : 1;
    }

    /** At most once a second: reads the thermal state, the display's refresh rate, and recomputes the cap. */
    private static void poll() {
        long now = System.nanoTime();
        if (polledNs != 0L && now - polledNs < POLL_NS) {
            return;
        }
        polledNs = now;
        int state = MetalNative.powerState();
        boolean hot = state >= 2;
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
