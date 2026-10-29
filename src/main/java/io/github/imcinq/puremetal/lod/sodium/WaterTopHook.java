package io.github.imcinq.puremetal.lod.sodium;

/**
 * Whether this Sodium's fluid renderer can skip the water tops that the LOD mesher merges. Set once by the mixin plugin
 * (the skip hook is only applied to a Sodium whose renderer was checked); without it water tops are never merged.
 */
public final class WaterTopHook {
    private static volatile boolean supported;

    private WaterTopHook() {
    }

    public static boolean supported() {
        return supported;
    }

    public static void setSupported(final boolean value) {
        supported = value;
    }
}
