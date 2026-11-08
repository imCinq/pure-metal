package io.github.imcinq.puremetal;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/** A locally stored, one-time invitation. No network access until the player opens the link. */
public final class GitHubReminder {
    public static final URI REPOSITORY = URI.create("https://github.com/imCinq/Pure-Metal");
    private static final Path DIR = FabricLoader.getInstance().getConfigDir();
    private static final Path SHOWN = DIR.resolve("puremetal-star-reminder-shown");
    private static final Path STATE = DIR.resolve("puremetal-star-reminder.properties");
    private static boolean loaded, disabled;
    private static long elapsed, target, previous, saved, remainderNanos;
    private GitHubReminder() {}

    public static void frame(final Minecraft client) {
        if (disabled) return;
        if (!loaded) {
            loaded = true;
            if (Files.exists(SHOWN)) { disabled = true; return; }
            Properties state = new Properties();
            try {
                if (Files.exists(STATE)) {
                    try (var in = Files.newInputStream(STATE)) { state.load(in); }
                    target = Long.parseLong(state.getProperty("targetMillis"));
                    elapsed = Long.parseLong(state.getProperty("elapsedMillis"));
                    if (target < 300_000 || target > 3_600_000 || elapsed < 0) throw new IOException("invalid reminder state");
                } else {
                    target = ThreadLocalRandom.current().nextLong(300_000, 3_600_001);
                    if (!save()) { disabled = true; return; }
                }
                saved = elapsed;
            } catch (IOException | RuntimeException e) { disabled = true; return; }
        }
        long now = System.nanoTime();
        boolean playing = client.level != null && client.player != null && !client.isPaused();
        if (playing && previous != 0) {
            long nanos = Math.min(Math.max(0, now - previous), 1_000_000_000L) + remainderNanos;
            elapsed += nanos / 1_000_000;
            remainderNanos = nanos % 1_000_000;
        }
        previous = playing ? now : 0;
        if (elapsed - saved >= 5000) {
            if (save()) saved = elapsed;
            else { disabled = true; return; }
        }
        if (playing && client.gui.screen() == null && elapsed >= target) {
            try {
                Files.createFile(SHOWN); // Atomic across concurrent instances, and saved before display.
                disabled = true;
                show(client);
            } catch (IOException e) {
                // A pre-existing marker means another instance has already shown it. Other errors also fail closed.
                disabled = true;
            }
        }
    }

    private static void show(final Minecraft client) {
        if (client.gui.toastManager().getToast(StarToast.class, net.minecraft.client.gui.components.toasts.Toast.NO_TOKEN) == null) {
            client.gui.toastManager().addToast(new StarToast());
        }
    }

    private static boolean save() {
        Properties p = new Properties();
        p.setProperty("targetMillis", Long.toString(target));
        p.setProperty("elapsedMillis", Long.toString(elapsed));
        Path tmp = null;
        try {
            Files.createDirectories(DIR);
            tmp = Files.createTempFile(DIR, "puremetal-reminder-", ".tmp");
            try (var out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) { p.store(out, "Pure Metal reminder"); }
            try { Files.move(tmp, STATE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(tmp, STATE, StandardCopyOption.REPLACE_EXISTING); }
            return true;
        } catch (IOException e) {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            return false;
        }
    }
}
