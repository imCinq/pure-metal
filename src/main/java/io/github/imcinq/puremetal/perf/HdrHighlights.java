package io.github.imcinq.puremetal.perf;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import io.github.imcinq.puremetal.backend.MetalUpscaler;
import io.github.imcinq.puremetal.scale.ScaleConfig;
import java.util.Properties;

/**
 * HDR highlights: on a screen with extended dynamic range (EDR) headroom, the present pass lets near-clipping world pixels
 * (the sun, lava, fire, glowing blocks) go brighter than SDR white while excluding the 2D HUD and menus via alpha coverage.
 * Off by default; toggled live in Pure Metal's settings (hdrHighlights in config/puremetal.properties) or forced with
 * -Dpuremetal.hdrHighlights=true|false.
 */
public final class HdrHighlights {
    /** Most the highlights are ever pushed past SDR white (1.0), whatever the screen can do. */
    public static final float MAX_BOOST = 2.0F;
    /** How often the screen's current EDR headroom is read. */
    public static final long HEADROOM_RECHECK_NS = 1_000_000_000L;

    private static volatile boolean enabled = readEnabled();
    /** Whether a 3D level rendered on the latest frame (menus without a world stay strictly SDR). */
    private static boolean worldRendered;

    // Latest snapshot, published by the present surface about once a second (the values come from the native main-thread watcher).
    private static volatile float shownHeadroom = 1.0F;
    private static volatile float shownMax = 1.0F;
    private static volatile float shownBoost = 1.0F;
    private static volatile boolean shownLinear;
    private static volatile boolean shownNoHdr;
    private static String debugText = "";
    private static long debugBuiltNs;

    /** True when HDR Highlights is on and the screen the window is on has no HDR (so nothing is boosted). */
    public static boolean screenHasNoHdr() {
        return enabled && shownNoHdr;
    }

    private HdrHighlights() {
    }

    /** Called at frame start so frames that skip world rendering (splash/loading/title screens) always stay SDR. */
    public static void beginFrame() {
        worldRendered = false;
    }

    /**
     * Called at the world/HUD boundary ({@code FogRenderer.endFrame}) after 3D rendering and any Render Scale upscale have
     * landed in {@code main} and before {@code GuiRenderer} draws the 2D HUD and menus. Computes each world pixel's highlight
     * weight into {@code main}'s alpha channel as {@code 1 - w} so HUD alpha writes attenuate it without creating new highlights.
     */
    public static void endWorld(final RenderTarget main, final boolean renderedLevel) {
        worldRendered = renderedLevel;
        if (!renderedLevel || !enabled || !MetalUpscaler.active()) {
            return;
        }
        GpuTextureView color = main.getColorTextureView();
        if (color != null) {
            MetalUpscaler.clearHdrWorldAlpha(color);
        }
    }

    /** Called from the render thread by the surface. */
    public static void publish(final float headroom, final float maxHeadroom, final float boost, final boolean linear, final boolean screenNoHdr) {
        shownHeadroom = headroom;
        shownMax = maxHeadroom;
        shownBoost = boost;
        shownLinear = linear;
        shownNoHdr = screenNoHdr;
    }

    /** The F3 line, or null when HDR Highlights is off. Rebuilt at most once a second. */
    public static String debugLine() {
        if (!enabled) {
            return null;
        }
        long now = System.nanoTime();
        if (debugText.isEmpty() || now - debugBuiltNs >= HEADROOM_RECHECK_NS) {
            debugBuiltNs = now;
            debugText = shownNoHdr
                ? "HDR: off (this screen has no HDR)"
                : String.format(java.util.Locale.ROOT, "HDR: headroom %.2f (max %.2f), target boost %.2f, colorspace %s",
                    shownHeadroom, shownMax, shownBoost, shownLinear ? "ok" : "untagged");
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
        if (ScaleConfig.benchLocked()) {
            return;
        }
        if (value == enabled) {
            return;
        }
        enabled = value;
        debugText = "";
        ScaleConfig.writeProperties(p -> p.setProperty("hdrHighlights", Boolean.toString(value)));
    }

    /**
     * The boost cap for the present pass, 1.0 meaning no change. Menus and HUD elements are excluded per-pixel via alpha
     * coverage in the present shader, so no screen-open fade is needed; frames without a 3D world (title/loading screens) and
     * Battery Saver stay at 1.0. Render thread, once per frame; no allocation.
     * The target comes from the screen's potential (maximum) headroom, not its current one: macOS only grants EDR headroom once
     * content above 1.0 is actually presented, so a target based on the current headroom (1.0 at rest) would never ask for any.
     * The present shader rolls the boosted range off toward the current headroom, so the image stays right while it ramps up.
     */
    public static float boost(final float maxHeadroom) {
        if (!enabled || BatterySaver.enabled() || !worldRendered) {
            return 1.0F;
        }
        return Math.max(1.0F, Math.min(maxHeadroom, MAX_BOOST));
    }
}
