package io.github.imcinq.puremetal.bench;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

/**
 * Benchmark build for players. Only active when the jar contains the marker resource {@value UserBenchBoot#MARKER}; a normal jar never touches it.
 * It presets the existing {@link Bench} properties (flight route generated during a 50 s warm-up, then a teleport back to the start, a 5 s settle and a 30 s measured pass over the same route; spectator mode, frozen time)
 * and writes the result, with hardware details, to puremetal-benchmark.txt in the game folder. No user name or path is recorded.
 */
public final class UserBench {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final int RENDER_DISTANCE = 16;
    static final long REFERENCE_SEED = -4953692208966999560L;
    static final String LAP_START = UserBenchBoot.LAP_START;
    static final long LAP_SETTLE_NS = 5_000_000_000L;

    private UserBench() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean("puremetal.userBench");
    }

    /** Writes the result file; never throws. Frame times are in nanoseconds, sorted ascending. */
    static void write(final Minecraft minecraft, final long[] sorted, final long elapsedNs) {
        try {
            int count = sorted.length;
            String backend = RenderSystem.getDevice().getDeviceInfo().backendName();
            StringBuilder out = new StringBuilder();
            if (!"Metal".equals(backend)) {
                out.append("NOT PURE METAL: this run used the ").append(backend)
                    .append(" backend, so the result is INVALID. Set Graphics API to Default in Video Settings and run it again.\n\n");
            }
            if (FabricLoader.getInstance().isModLoaded("sodium")) {
                out.append("WARNING: Sodium installed: compare only with other Sodium runs.\n\n");
            }
            String seed = worldSeed(minecraft);
            if (seed == null) {
                out.append("WARNING: World seed unknown (not a singleplayer world): results may not match the reference table.\n\n");
            } else if (!seed.equals(Long.toString(REFERENCE_SEED))) {
                out.append("WARNING: Different world seed: results will not match the reference table.\n\n");
            }
            out.append("Pure Metal benchmark\n");
            out.append("World seed: ").append(seed == null ? "unknown" : seed).append('\n');
            out.append("Pure Metal: ").append(FabricLoader.getInstance().getModContainer("puremetal")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
            out.append("Minecraft: ").append(FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?")).append('\n');
            out.append("Backend: ").append(backend).append('\n');
            out.append("Metal 4 setting: ").append(io.github.imcinq.puremetal.backend.Metal4Config.effective() ? "on" : "off (forced for the benchmark, Metal 3)").append('\n');
            out.append("Sodium: ").append(FabricLoader.getInstance().isModLoaded("sodium") ? "yes" : "no").append('\n');
            out.append("Chip: ").append(sysctl("machdep.cpu.brand_string")).append('\n');
            out.append("Memory GB: ").append(memoryGb()).append('\n');
            out.append("macOS: ").append(System.getProperty("os.version")).append('\n');
            out.append("Resolution: ").append(minecraft.getWindow().getWidth()).append('x').append(minecraft.getWindow().getHeight()).append('\n');
            out.append("Render distance: ").append(minecraft.options.renderDistance().get()).append(" (forced for the benchmark)\n");
            out.append("Render Scale: Native, 100% (forced for the benchmark)\n");
            out.append("Auto Render Scale: off (forced for the benchmark)\n");
            out.append("Terrain LOD: ").append(io.github.imcinq.puremetal.lod.TerrainLodConfig.enabled() ? "on" : "off")
                .append(", ratio ").append(io.github.imcinq.puremetal.lod.TerrainLodConfig.ratioPercent()).append("% (default, forced for the benchmark)\n");
            out.append("Battery Saver: ").append(io.github.imcinq.puremetal.perf.BatterySaver.enabled() ? "on" : "off").append(" (forced for the benchmark)\n");
            out.append("HDR Highlights: ").append(io.github.imcinq.puremetal.perf.HdrHighlights.ACTIVE ? "on" : "off").append(" (forced for the benchmark)\n");
            out.append("VSync: off (forced for the benchmark)\n");
            out.append("Max FPS: unlimited (forced for the benchmark)\n");
            out.append("Generation FPS cap: off (forced for the benchmark)\n");
            out.append("Inactivity FPS limit: minimized only, no AFK cap (forced for the benchmark)\n");
            java.util.Properties settings = io.github.imcinq.puremetal.scale.ScaleConfig.readProperties();
            for (String key : new String[] {"upscaler", "sharpening"}) {
                out.append("Pure Metal ").append(key).append(": ")
                    .append(settings == null ? "default" : settings.getProperty(key, "default")).append('\n');
            }
            out.append("Frames: ").append(count).append(" in ").append(String.format("%.1f", elapsedNs / 1e9)).append(" s\n");
            out.append("Average FPS: ").append(String.format("%.1f", count / (elapsedNs / 1e9))).append('\n');
            out.append("Median frame ms: ").append(ms(sorted[count / 2])).append('\n');
            out.append("1% low FPS: ").append(lowFps(sorted, 0.99)).append('\n');
            out.append("0.1% low FPS: ").append(lowFps(sorted, 0.999)).append('\n');
            out.append("Worst frame ms: ").append(ms(sorted[count - 1])).append('\n');
            Path file = minecraft.gameDirectory.toPath().resolve("puremetal-benchmark.txt");
            Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
            LOGGER.info("BENCH-USER wrote puremetal-benchmark.txt");
        } catch (Exception e) {
            LOGGER.error("BENCH-USER report failed", e);
        }
    }

    private static final java.util.List<java.util.concurrent.CompletableFuture<String>> SETUP = new java.util.ArrayList<>();
    private static String setupFailure;

    /**
     * Runs the setup commands on the integrated server with full permission, as the player, so the world does not need cheats.
     * Singleplayer only: anywhere else the run is invalid (see {@link #pollSetup()}).
     */
    static void runSetup(final Minecraft minecraft, final String commands) {
        net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null || minecraft.player == null) {
            setupFailure = "not a singleplayer world, the benchmark needs one";
            return;
        }
        java.util.UUID id = minecraft.player.getUUID();
        SETUP.add(server.submit(() -> {
            net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null) {
                return "player not found on the server";
            }
            net.minecraft.commands.CommandSourceStack source = player.createCommandSourceStack()
                .withPermission(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER);
            for (String command : commands.split(";")) {
                try {
                    server.getCommands().getDispatcher().execute(command.trim(), source);
                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                    if (isAlreadyAtTime(e)) {
                        continue; // the clock is already where the setup wants it (a reused world), not a failure
                    }
                    String detail = e.getMessage();
                    LOGGER.error("BENCH-USER setup command '{}' failed: {}", command.trim(), detail);
                    return "/" + command.trim().split(" ")[0] + " was rejected: " + detail;
                } catch (RuntimeException e) {
                    LOGGER.error("BENCH-USER setup command '{}' threw", command.trim(), e);
                    return "/" + command.trim().split(" ")[0] + " was rejected: " + e;
                }
            }
            return null;
        }));
    }

    /** /time set refuses when the clock is already at the target (26.x), which is the state the setup wants. */
    private static boolean isAlreadyAtTime(final com.mojang.brigadier.exceptions.CommandSyntaxException e) {
        net.minecraft.network.chat.Component message = net.minecraft.network.chat.ComponentUtils.fromMessage(e.getRawMessage());
        return message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents translatable
            && translatable.getKey().startsWith("commands.time.set.already_at_time");
    }

    /** The reason a setup command failed, or null while all went fine so far. */
    static String pollSetup() {
        if (setupFailure != null) {
            return setupFailure;
        }
        java.util.Iterator<java.util.concurrent.CompletableFuture<String>> it = SETUP.iterator();
        while (it.hasNext()) {
            java.util.concurrent.CompletableFuture<String> future = it.next();
            if (!future.isDone()) {
                continue;
            }
            it.remove();
            try {
                String result = future.get();
                if (result != null) {
                    setupFailure = result;
                }
            } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
                setupFailure = "setup command error";
            }
        }
        return setupFailure;
    }

    /** The run did not measure the reference route: the result file says so and carries no numbers. */
    static void writeInvalid(final Minecraft minecraft, final String reason) {
        try {
            String text = "INVALID: setup commands failed (" + reason + "). Nothing was measured. Use a singleplayer world and run it again.\n";
            Files.writeString(minecraft.gameDirectory.toPath().resolve("puremetal-benchmark.txt"), text, StandardCharsets.UTF_8);
            LOGGER.error("BENCH-USER invalid: {}", reason);
        } catch (Exception e) {
            LOGGER.error("BENCH-USER report failed", e);
        }
    }

    /** The singleplayer world's seed as text, or null when there is no integrated server. */
    private static String worldSeed(final Minecraft minecraft) {
        try {
            net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
            return server == null ? null : Long.toString(server.overworld().getSeed());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String ms(final long ns) {
        return String.format("%.2f", ns / 1e6);
    }

    private static String lowFps(final long[] sorted, final double p) {
        int index = Math.min(sorted.length - 1, (int)Math.ceil(p * sorted.length) - 1);
        return String.format("%.1f", 1e9 / sorted[Math.max(0, index)]);
    }

    private static String memoryGb() {
        try {
            return String.valueOf(Long.parseLong(sysctl("hw.memsize")) / (1024L * 1024 * 1024));
        } catch (NumberFormatException e) {
            return "?";
        }
    }

    private static String sysctl(final String key) {
        try {
            Process process = new ProcessBuilder("/usr/sbin/sysctl", "-n", key).redirectErrorStream(true).start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor(2, TimeUnit.SECONDS);
            return text.isEmpty() ? "?" : text;
        } catch (Exception e) {
            return "?";
        }
    }
}
