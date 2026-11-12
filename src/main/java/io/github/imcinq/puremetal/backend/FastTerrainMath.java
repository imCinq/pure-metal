package io.github.imcinq.puremetal.backend;

import java.util.Set;

/**
 * Per pipeline relaxed shader math for the stock terrain fragment shader. Relaxed math is a large GPU win on terrain but is not
 * safe for post effects, sky, fog, OIT resolve or GUI, so only an allowlist of pipelines gets it, and only when the terrain
 * shader sources are the unmodified stock ones. Vertex shaders always keep safe math, so positions and depth never change.
 * -Dpuremetal.fastTerrainMath=off|opaque|all (default opaque).
 */
final class FastTerrainMath {
    enum Mode { OFF, OPAQUE, ALL }

    static final Mode MODE = parse(System.getProperty("puremetal.fastTerrainMath"));

    /** Group 1: opaque terrain. */
    private static final Set<String> OPAQUE = Set.of(
        "minecraft:pipeline/solid_terrain", "minecraft:pipeline/solid_terrain_multidraw",
        "minecraft:pipeline/cutout_terrain", "minecraft:pipeline/cutout_terrain_multidraw");
    /** Group 2: translucent and order independent transparency terrain, which feed blending and transmittance math. */
    private static final Set<String> TRANSLUCENT = Set.of(
        "minecraft:pipeline/translucent_terrain", "minecraft:pipeline/translucent_terrain_multidraw",
        "minecraft:pipeline/oit_transmittance_terrain", "minecraft:pipeline/oit_transmittance_terrain_multidraw",
        "minecraft:pipeline/oit_depth_bounds_terrain", "minecraft:pipeline/oit_depth_bounds_terrain_multidraw",
        "minecraft:pipeline/oit_accumulate_terrain", "minecraft:pipeline/oit_accumulate_terrain_multidraw");

    /** True only in a fastTerrain / fastTerrainAll A/B run: both math variants are built and the bench switch picks one. */
    static final boolean AB = System.getProperty("puremetal.benchAB", "").startsWith("fastTerrain");

    private FastTerrainMath() {
    }

    private static Mode parse(final String value) {
        if (value == null) {
            return Mode.OPAQUE;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "off" -> Mode.OFF;
            case "all" -> Mode.ALL;
            default -> Mode.OPAQUE;
        };
    }

    /** 0 = not a terrain pipeline on the allowlist, 1 = opaque group, 2 = translucent / OIT group. */
    static int group(final String pipelineName) {
        return OPAQUE.contains(pipelineName) ? 1 : TRANSLUCENT.contains(pipelineName) ? 2 : 0;
    }

    /** Whether the configured mode alone (no A/B) gives this group relaxed fragment math. */
    static boolean configured(final int group) {
        return group == 1 ? MODE != Mode.OFF : group == 2 && MODE == Mode.ALL;
    }
}
