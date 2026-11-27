package io.github.imcinq.puremetal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/** Fabric Loader implementation of {@link ModPlatform}. Excluded from the NeoForge build. */
public final class FabricPlatform implements ModPlatform {
    public FabricPlatform() {
        // Force linkage check during ModPlatform.Holder.load() so NeoForge falls through cleanly if ever present.
        FabricLoader.getInstance();
    }

    @Override
    public String loaderName() {
        return "Fabric";
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public boolean isModLoaded(final String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public Optional<ModMeta> mod(final String modId) {
        return FabricLoader.getInstance().getModContainer(modId).map(FabricPlatform::meta);
    }

    @Override
    public Optional<byte[]> readEntry(final String modId, final String entry, final int maxBytes) {
        Optional<Path> path = FabricLoader.getInstance().getModContainer(modId).flatMap(c -> c.findPath(entry));
        if (path.isEmpty() || !Files.isRegularFile(path.get())) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(path.get())) {
            byte[] bytes = in.readNBytes(maxBytes + 1);
            return bytes.length <= maxBytes ? Optional.of(bytes) : Optional.empty();
        } catch (IOException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ModMeta> findUniqueModForClass(final String classEntry, final Set<String> ignoreModIds) {
        ModMeta unique = null;
        for (ModContainer container : FabricLoader.getInstance().getAllMods()) {
            String id = container.getMetadata().getId();
            if (ignoreModIds.contains(id)) {
                continue;
            }
            if (container.findPath(classEntry).isPresent()) {
                if (unique != null) {
                    return Optional.empty();
                }
                unique = meta(container);
            }
        }
        return Optional.ofNullable(unique);
    }

    @Override
    public List<String> launchArguments() {
        return List.of(FabricLoader.getInstance().getLaunchArguments(true));
    }

    private static ModMeta meta(final ModContainer container) {
        var m = container.getMetadata();
        return new ModMeta(m.getId(), m.getName(), m.getVersion().getFriendlyString());
    }
}
