package io.github.imcinq.puremetal.scale;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** The dismissal is independent of the toast and survives normal mod updates. */
public final class GitHubCardDismissal {
    private static final Path MARKER = FabricLoader.getInstance().getConfigDir().resolve("puremetal-github-card-hidden");

    private GitHubCardDismissal() { }

    public static boolean hidden() { return Files.exists(MARKER); }

    public static boolean hide() {
        try {
            Files.createDirectories(MARKER.getParent());
            Files.createFile(MARKER);
            return true;
        } catch (FileAlreadyExistsException e) {
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
