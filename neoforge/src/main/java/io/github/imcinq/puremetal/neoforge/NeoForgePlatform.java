package io.github.imcinq.puremetal.neoforge;

import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import io.github.imcinq.puremetal.ModPlatform;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.ProgramArgs;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforge.client.stencil.StencilOperation;
import net.neoforged.neoforge.client.stencil.StencilPerFaceTest;
import net.neoforged.neoforge.client.stencil.StencilTest;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * NeoForge implementation of {@link ModPlatform}. Uses {@link LoadingModList} and {@link FMLPaths} so every
 * lookup works both during early {@code IMixinConfigPlugin} startup and after mod loading finishes.
 */
public final class NeoForgePlatform implements ModPlatform {
    public NeoForgePlatform() {
        // Force linkage check during ModPlatform.Holder.load().
        FMLPaths.GAMEDIR.get();
    }

    private static LoadingModList modList() {
        FMLLoader loader = FMLLoader.getCurrentOrNull();
        return loader != null ? loader.getLoadingModList() : null;
    }

    @Override
    public String loaderName() {
        return "NeoForge";
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public boolean isModLoaded(final String modId) {
        LoadingModList list = modList();
        return list != null && list.getModFileById(modId) != null;
    }

    @Override
    public Optional<ModMeta> mod(final String modId) {
        LoadingModList list = modList();
        if (list == null) {
            return Optional.empty();
        }
        ModFileInfo file = list.getModFileById(modId);
        if (file == null) {
            return Optional.empty();
        }
        for (IModInfo info : file.getMods()) {
            if (modId.equals(info.getModId())) {
                return Optional.of(meta(info));
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<byte[]> readEntry(final String modId, final String entry, final int maxBytes) {
        LoadingModList list = modList();
        if (list == null) {
            return Optional.empty();
        }
        ModFileInfo file = list.getModFileById(modId);
        if (file == null) {
            return Optional.empty();
        }
        String clean = entry.startsWith("/") ? entry.substring(1) : entry;
        try (InputStream in = file.getFile().getContents().openFile(clean)) {
            if (in == null) {
                return Optional.empty();
            }
            byte[] bytes = in.readNBytes(maxBytes + 1);
            return bytes.length <= maxBytes ? Optional.of(bytes) : Optional.empty();
        } catch (IOException | RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ModMeta> findUniqueModForClass(final String classEntry, final Set<String> ignoreModIds) {
        LoadingModList list = modList();
        if (list == null) {
            return Optional.empty();
        }
        String clean = classEntry.startsWith("/") ? classEntry.substring(1) : classEntry;
        ModMeta unique = null;
        for (ModFileInfo file : list.getModFiles()) {
            boolean found;
            try {
                found = file.getFile().getContents().containsFile(clean);
            } catch (RuntimeException unavailable) {
                found = false;
            }
            if (!found) {
                continue;
            }
            for (IModInfo info : file.getMods()) {
                String id = info.getModId();
                if (ignoreModIds.contains(id)) {
                    continue;
                }
                if (unique != null) {
                    return Optional.empty();
                }
                unique = meta(info);
            }
        }
        return Optional.ofNullable(unique);
    }

    @Override
    public List<String> launchArguments() {
        FMLLoader loader = FMLLoader.getCurrentOrNull();
        ProgramArgs args = loader != null ? loader.getProgramArgs() : null;
        String[] raw = args != null ? args.getArguments() : null;
        return raw != null && raw.length > 0 ? List.of(raw) : List.of();
    }

    @Override
    public StencilState stencilState(final Object depthStencilState) {
        if (!(depthStencilState instanceof DepthStencilState state)) {
            return null;
        }
        StencilTest test = state.stencilTest();
        if (test == null) {
            return null;
        }
        StencilPerFaceTest front = test.front();
        StencilPerFaceTest back = test.back();
        return new StencilState(
            stencilOp(front.fail()), stencilOp(front.depthFail()), stencilOp(front.pass()), compareOp(front.compare()),
            stencilOp(back.fail()), stencilOp(back.depthFail()), stencilOp(back.pass()), compareOp(back.compare()),
            test.readMask() & 0xFF, test.writeMask() & 0xFF, test.referenceValue() & 0xFF
        );
    }

    private static int stencilOp(final StencilOperation op) {
        return switch (op) {
            case KEEP -> 0;
            case ZERO -> 1;
            case REPLACE -> 2;
            case INCR -> 3;
            case DECR -> 4;
            case INVERT -> 5;
            case INCR_WRAP -> 6;
            case DECR_WRAP -> 7;
        };
    }

    private static int compareOp(final CompareOp op) {
        return switch (op) {
            case ALWAYS_PASS -> 0;
            case LESS_THAN -> 1;
            case LESS_THAN_OR_EQUAL -> 2;
            case EQUAL -> 3;
            case NOT_EQUAL -> 4;
            case GREATER_THAN_OR_EQUAL -> 5;
            case GREATER_THAN -> 6;
            case NEVER_PASS -> 7;
        };
    }

    private static ModMeta meta(final IModInfo info) {
        return new ModMeta(info.getModId(), info.getDisplayName(), info.getVersion().toString());
    }
}
