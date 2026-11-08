package io.github.imcinq.puremetal.scale;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Fingerprints the Sodium settings classes whose exact structure is used by the optional widgets. */
public final class SodiumSettingsFingerprint {
    private static final Map<String, String> CLASSES = Map.of(
        "net/caffeinemc/mods/sodium/client/gui/widgets/OptionListWidget.class", "3b1f351c0209d039016da774e29b112a1d01666ede44dec04cf84b1ac99a733d",
        "net/caffeinemc/mods/sodium/client/gui/widgets/AbstractParentWidget.class", "af062d8dfc85248c9935e6dbbf2f302b831e55fe5f859277e61dd5d22b0ff194",
        "net/caffeinemc/mods/sodium/client/config/structure/ModOptions.class", "630e79eca75523a1a9fed88b16c5f3d55f878c0e69d0cde9342b2b0c0e3d7de0");
    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private SodiumSettingsFingerprint() { }

    public static boolean matches(Function<String, Optional<Path>> findPath) {
        try {
            for (var entry : CLASSES.entrySet()) {
                var path = findPath.apply(entry.getKey());
                if (path.isEmpty() || !Files.isRegularFile(path.get())) return false;
                byte[] bytes;
                try (InputStream in = Files.newInputStream(path.get())) {
                    bytes = in.readNBytes(MAX_BYTES + 1);
                }
                if (bytes.length > MAX_BYTES || !entry.getValue().equals(
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) return false;
            }
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }
}
