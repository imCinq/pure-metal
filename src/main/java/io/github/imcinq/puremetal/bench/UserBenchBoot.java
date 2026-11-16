package io.github.imcinq.puremetal.bench;

import java.io.InputStream;

/**
 * The early half of the benchmark build, called from the mixin plugin's onLoad. It must reference JDK types only:
 * touching any Minecraft or other mod class here would load a mixin target too early and crash the launch.
 * Everything that uses Minecraft lives in {@link UserBench}, which is reached only at game time.
 */
public final class UserBenchBoot {
    static final String MARKER = "/puremetal-benchmark.flag";
    static final String LAP_START = "tp @s 0 140 0 0 15";
    private static final String COMMANDS = "gamemode spectator;time set noon;gamerule advance_time false;weather clear;" + LAP_START;

    private UserBenchBoot() {
    }

    /** Called from the mixin plugin, before {@link Bench} is initialized. */
    public static void apply() {
        try (InputStream marker = UserBenchBoot.class.getResourceAsStream(MARKER)) {
            if (marker == null) {
                return;
            }
        } catch (java.io.IOException | RuntimeException e) {
            return;
        }
        System.setProperty("puremetal.userBench", "true");
        System.setProperty("puremetal.bench", "30");
        System.setProperty("puremetal.benchWarmup", "50");
        System.setProperty("puremetal.benchWalk", "true");
        System.setProperty("puremetal.generationFpsCap", "false");
        System.setProperty("puremetal.benchCommand", COMMANDS);
        // Startup-only settings, overridden for this launch only. These properties win over config/puremetal.properties,
        // and nothing writes the file unless the player changes a setting, which the benchmark never does.
        System.setProperty("puremetal.metal4", "false");
        System.setProperty("puremetal.hdrHighlights", "false");
        System.setProperty("puremetal.batterySaver", "false");
    }
}
