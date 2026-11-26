package io.github.imcinq.puremetal;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks confirmed post-apply hooks for coupled optional mixin groups. Lives outside the mixin package and references
 * JDK types only so {@code PureMetalMixinPlugin} can update it during transformation without loading target classes early.
 */
public final class MixinHooks {
    public static final int VLOD_COMPILER = 1 << 0;
    public static final int VLOD_WATER_EMIT = 1 << 1;
    public static final int VLOD_WATER_SKIP = 1 << 2;
    public static final int VLOD_RESULTS = 1 << 3;
    public static final int VLOD_MESH = 1 << 4;
    public static final int VLOD_EXTRACTOR = 1 << 5;
    private static final int VLOD_CORE = VLOD_COMPILER | VLOD_RESULTS | VLOD_MESH | VLOD_EXTRACTOR;
    private static final int VLOD_WATER = VLOD_CORE | VLOD_WATER_EMIT | VLOD_WATER_SKIP;

    public static final int SLOD_TASK = 1 << 6;
    public static final int SLOD_WATER_EMIT = 1 << 7;
    public static final int SLOD_WATER_SKIP = 1 << 8;
    public static final int SLOD_OUTPUT = 1 << 9;
    public static final int SLOD_SECTION = 1 << 10;
    public static final int SLOD_MANAGER = 1 << 11;
    private static final int SLOD_CORE = SLOD_TASK | SLOD_OUTPUT | SLOD_SECTION | SLOD_MANAGER;
    private static final int SLOD_WATER = SLOD_CORE | SLOD_WATER_EMIT | SLOD_WATER_SKIP;

    public static final int EARLY_RENDERER = 1 << 12;
    public static final int EARLY_EXTRACTOR = 1 << 13;
    private static final int EARLY_ALL = EARLY_RENDERER | EARLY_EXTRACTOR;

    public static final int PACER_ALL = 1 << 14;
    public static final int SODIUM_UI_BRIDGE = 1 << 15;

    private static final AtomicInteger CONFIRMED = new AtomicInteger();

    private MixinHooks() {
    }

    public static int bits() {
        return CONFIRMED.get();
    }

    public static void confirm(final int mask) {
        CONFIRMED.getAndUpdate(v -> v | mask);
    }

    public static boolean has(final int mask) {
        return (CONFIRMED.get() & mask) == mask;
    }

    public static boolean vanillaLodReady() {
        return has(VLOD_CORE);
    }

    public static boolean vanillaWaterReady() {
        return has(VLOD_WATER);
    }

    public static boolean sodiumLodReady() {
        return has(SLOD_CORE);
    }

    public static boolean sodiumWaterReady() {
        return has(SLOD_WATER);
    }

    public static boolean earlyUploadReady() {
        return has(EARLY_ALL);
    }

    public static boolean pacerReady() {
        return has(PACER_ALL);
    }

    public static boolean sodiumUiBridgeReady() {
        return has(SODIUM_UI_BRIDGE);
    }
}
