package io.github.imcinq.puremetal.backend;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Diagnostic only: classifies every render pipeline into a coarse group so one A/B launch can compare safe and relaxed shader math
 * (vertex and fragment, like -Dpuremetal.safeMath=false) per group: -PbenchAB=fastMathAll, fastMathSky, fastMathPost, ...
 * Outside such a run nothing here changes any shader.
 */
final class MathGroups {
    enum Group {
        TERRAIN("Terrain"), TERRAIN_TRANSLUCENT("TerrainTranslucent"), ENTITY("Entity"), SKY("Sky"), CLOUDS("Clouds"), WEATHER("Weather"),
        PARTICLES("Particles"), WATER("Water"), POST("Post"), GUI("Gui"), LINES("Lines"), OTHER("Other");

        final String switchName;

        Group(final String suffix) {
            this.switchName = PREFIX + suffix;
        }

        int bit() {
            return 1 << this.ordinal();
        }
    }

    static final String PREFIX = "fastMath";
    static final int ALL_MASK = (1 << Group.values().length) - 1;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String AB_NAME = System.getProperty("puremetal.benchAB", "");
    /** Groups that get a relaxed variant in this run; 0 when no fastMath A/B is requested. */
    static final int SELECTED = selected(AB_NAME);
    static final boolean AB = SELECTED != 0;
    private static final Map<Group, TreeSet<String>> TABLE = new EnumMap<>(Group.class);

    private MathGroups() {
    }

    /** "fastMathAll" or "fastMathSky" (also "fastMath:sky"); anything else selects nothing. */
    private static int selected(final String abName) {
        if (!abName.startsWith(PREFIX)) {
            return 0;
        }
        String rest = abName.substring(PREFIX.length());
        if (rest.startsWith(":")) {
            rest = rest.substring(1);
        }
        if (rest.equalsIgnoreCase("all")) {
            return ALL_MASK;
        }
        for (Group group : Group.values()) {
            if (group.switchName.equalsIgnoreCase(PREFIX + rest)) {
                return group.bit();
            }
        }
        return 0;
    }

    static boolean isSelected(final Group group) {
        return (SELECTED & group.bit()) != 0;
    }

    /** Group by pipeline location ("minecraft:pipeline/sky"), with the shader names as a fallback for pipelines outside the vanilla naming. */
    static Group classify(final String pipelineName, final java.util.Collection<String> shaderNames) {
        String name = pipelineName;
        int colon = name.indexOf(':');
        String namespace = colon >= 0 ? name.substring(0, colon) : "";
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        if (name.startsWith("pipeline/")) {
            name = name.substring("pipeline/".length());
        }
        if (name.equals("oit_composite") || name.equals("oit_depth_bounds_cull")) {
            return Group.POST;
        }
        boolean oit = false;
        for (String prefix : new String[] {"oit_transmittance_", "oit_depth_bounds_", "oit_accumulate_"}) {
            if (name.startsWith(prefix)) {
                name = name.substring(prefix.length());
                oit = true;
                break;
            }
        }
        if (name.startsWith("blit_depth") || name.equals("integrate_depth")
            || name.contains("outline_blit") || name.equals("tracy_blit") || name.startsWith("outline_")) {
            return Group.POST;
        }
        if (name.startsWith("oit_")) {
            name = name.substring("oit_".length());
        }
        if (name.startsWith("terrain") || name.startsWith("translucent_terrain") || name.equals("translucent_block")) {
            return oit || name.startsWith("translucent") ? Group.TERRAIN_TRANSLUCENT : Group.TERRAIN;
        }
        if (name.startsWith("solid_terrain") || name.startsWith("cutout_terrain") || name.equals("solid_block") || name.equals("cutout_block")) {
            return Group.TERRAIN;
        }
        if (name.equals("water_mask")) {
            return Group.WATER;
        }
        if (name.equals("sky") || name.equals("end_sky") || name.equals("sunrise_sunset") || name.equals("stars") || name.equals("celestial")) {
            return Group.SKY;
        }
        if (name.equals("clouds") || name.equals("flat_clouds")) {
            return Group.CLOUDS;
        }
        if (name.equals("weather")) {
            return Group.WEATHER;
        }
        if (name.endsWith("_particle") || name.startsWith("particle")) {
            return Group.PARTICLES;
        }
        if (name.startsWith("gui") || name.startsWith("text") || name.equals("crosshair") || name.equals("vignette") || name.equals("mojang_logo")
            || name.equals("panorama") || name.endsWith("_screen_effect")) {
            return Group.GUI;
        }
        if (name.startsWith("lines") || name.startsWith("debug_") || name.startsWith("wireframe") || name.equals("secondary_block_outline")) {
            return Group.LINES;
        }
        if (name.startsWith("entity_") || name.startsWith("armor_") || name.startsWith("item_") || name.equals("eyes") || name.equals("energy_swirl")
            || name.equals("breeze_wind") || name.equals("banner_pattern") || name.equals("end_crystal_beam") || name.equals("leash")
            || name.startsWith("beacon_beam") || name.startsWith("glint") || name.equals("crumbling") || name.equals("end_portal")
            || name.equals("end_gateway") || name.equals("lightning") || name.equals("dragon_rays")) {
            return Group.ENTITY;
        }
        // Not a vanilla pipeline name (post effect chains, mods): guess from the pipeline and shader names.
        StringBuilder all = new StringBuilder(namespace).append(' ').append(name);
        for (String shader : shaderNames) {
            all.append(' ').append(shader);
        }
        String text = all.toString().toLowerCase(Locale.ROOT);
        if (text.contains("post") || text.contains("blit") || text.contains("blur") || text.contains("fxaa") || text.contains("upscale")
            || text.contains("composite") || text.contains("bloom") || text.contains("transparency")) {
            return Group.POST;
        }
        if (text.contains("translucent") && (text.contains("terrain") || text.contains("chunk") || text.contains("block"))) {
            return Group.TERRAIN_TRANSLUCENT;
        }
        if (text.contains("terrain") || text.contains("chunk") || text.contains("block_layer")) {
            return Group.TERRAIN;
        }
        if (text.contains("cloud")) {
            return Group.CLOUDS;
        }
        return Group.OTHER;
    }

    static synchronized void record(final Group group, final String pipelineName, final java.util.Collection<String> shaderNames) {
        TABLE.computeIfAbsent(group, g -> new TreeSet<>()).add(group == Group.OTHER ? pipelineName + " " + shaderNames : pipelineName);
    }

    private static boolean tableLogged;

    /** One-time dump of the group to pipeline mapping (everything compiled so far), at the first slice that switches relaxed math on. */
    static synchronized void logTable() {
        if (tableLogged) {
            return;
        }
        tableLogged = true;
        StringBuilder out = new StringBuilder("Pure Metal: math group table (pipelines compiled so far)");
        for (Group group : Group.values()) {
            TreeSet<String> names = TABLE.get(group);
            out.append("\n  ").append(group.switchName).append(" -> ").append(names == null ? "(none)" : String.join(", ", names));
        }
        LOGGER.info(out.toString());
    }
}
