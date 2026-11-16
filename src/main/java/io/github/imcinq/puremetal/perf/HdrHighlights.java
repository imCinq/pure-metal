package io.github.imcinq.puremetal.perf;

import io.github.imcinq.puremetal.scale.ScaleConfig;
import java.util.Properties;
import net.minecraft.client.Minecraft;

/**
 * Experimental HDR highlights: on a screen with extended dynamic range (EDR) headroom, the present pass lets near-clipping
 * pixels (the sun, lava, fire, glowing blocks) go brighter than SDR white. Off by default; toggled in Pure Metal's settings
 * (hdrHighlights in config/puremetal.properties) or forced with -Dpuremetal.hdrHighlights=true|false.
 * The layer format is chosen when the window surface is created, so a change needs a restart: ACTIVE is read once.
 */
public final class HdrHighlights {
    /** Most the highlights are ever pushed past SDR white (1.0), whatever the screen can do. */
    public static final float MAX_BOOST = 2.0F;
    /** How often the screen's current EDR headroom is read. */
    public static final long HEADROOM_RECHECK_NS = 1_000_000_000L;

    /**
     * Dev/debug only (-Dpuremetal.hdrCompare=true): with HDR Highlights on, only the left half of the screen gets the boost, the
     * right half shows the same image at boost 1.0, with a thin divider between them. Read once, with the surface.
     */
    public static final boolean COMPARE = Boolean.getBoolean("puremetal.hdrCompare");

    /** How long the boost takes to fade out when a screen opens, and back in when it closes. */
    private static final long RAMP_NS = 200_000_000L;
    /** 0 = no boost, 1 = full boost; moves toward the goal by the time passed since the last frame. */
    private static float level = 1.0F;
    private static long rampNs;

    private static volatile boolean enabled = readEnabled();
    /** What the surface was created with: true when the layer is the HDR one for this run. */
    public static final boolean ACTIVE = enabled;

    // Latest snapshot, published by the present surface about once a second (the values come from the native main-thread watcher).
    private static volatile float shownHeadroom = 1.0F;
    private static volatile float shownMax = 1.0F;
    private static volatile float shownBoost = 1.0F;
    private static volatile boolean shownLinear;
    private static volatile boolean shownNoHdr;
    private static String debugText = "";
    private static long debugBuiltNs;

    /** True when HDR Highlights is on for this run and the screen the window is on has no HDR (so nothing is boosted). */
    public static boolean screenHasNoHdr() {
        return ACTIVE && enabled && shownNoHdr;
    }

    private HdrHighlights() {
    }

    /** Called from the render thread by the surface. */
    public static void publish(final float headroom, final float maxHeadroom, final float boost, final boolean linear, final boolean screenNoHdr) {
        shownHeadroom = headroom;
        shownMax = maxHeadroom;
        shownBoost = boost;
        shownLinear = linear;
        shownNoHdr = screenNoHdr;
    }

    /** The F3 line, or null when this run has no HDR layer. Rebuilt at most once a second. */
    public static String debugLine() {
        if (!ACTIVE) {
            return null;
        }
        long now = System.nanoTime();
        if (debugText.isEmpty() || now - debugBuiltNs >= HEADROOM_RECHECK_NS) {
            debugBuiltNs = now;
            debugText = shownNoHdr
                ? "HDR: off (this screen has no HDR)"
                : String.format(java.util.Locale.ROOT, "HDR: headroom %.2f (max %.2f), target boost %.2f, colorspace %s, compare %s",
                    shownHeadroom, shownMax, shownBoost, shownLinear ? "ok" : "untagged", COMPARE ? "on" : "off");
        }
        return debugText;
    }

    private static boolean readEnabled() {
        String property = System.getProperty("puremetal.hdrHighlights");
        if (property != null) {
            return Boolean.parseBoolean(property.trim());
        }
        Properties p = ScaleConfig.readProperties();
        return p != null && Boolean.parseBoolean(p.getProperty("hdrHighlights", "false").trim());
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void setEnabled(final boolean value) {
        if (value == enabled) {
            return;
        }
        enabled = value;
        ScaleConfig.writeProperties(p -> p.setProperty("hdrHighlights", Boolean.toString(value)));
    }

    /**
     * The boost cap for the present pass, 1.0 meaning no change. The final image already has the HUD and menus in it and
     * cannot tell them from the world, so the boost fades out over RAMP_NS whenever a screen is open (the HUD still shows during
     * play) and back in when it closes, instead of snapping. It is always 1.0 while Battery Saver is on. Render thread, once per
     * frame; no allocation.
     * The target comes from the screen's potential (maximum) headroom, not its current one: macOS only grants EDR headroom once
     * content above 1.0 is actually presented, so a target based on the current headroom (1.0 at rest) would never ask for any.
     * The present shader rolls the boosted range off toward the current headroom, so the image stays right while it ramps up.
     */
    public static float boost(final float maxHeadroom) {
        long now = System.nanoTime();
        if (BatterySaver.enabled()) {
            level = 0.0F;
            rampNs = now;
            return 1.0F;
        }
        float goal = Minecraft.getInstance().gui.screen() != null ? 0.0F : 1.0F;
        if (rampNs != 0L && level != goal) {
            float step = (float) ((now - rampNs) / (double) RAMP_NS);
            level = goal > level ? Math.min(goal, level + step) : Math.max(goal, level - step);
        } else if (rampNs == 0L) {
            level = goal;
        }
        rampNs = now;
        return 1.0F + (Math.max(1.0F, Math.min(maxHeadroom, MAX_BOOST)) - 1.0F) * level;
    }
}
