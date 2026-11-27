package io.github.imcinq.puremetal;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Thin loader abstraction so Pure Metal's shared code runs on both Fabric (primary) and NeoForge
 * without importing loader-specific classes from shared packages. Safe during early Mixin plugin startup.
 */
public interface ModPlatform {
    record ModMeta(String id, String name, String version) {
    }

    /** Optional stencil test configuration (used by NeoForge's RenderPearl stencil extension; null on Fabric). */
    record StencilState(
        int frontFail, int frontDepthFail, int frontPass, int frontCompare,
        int backFail, int backDepthFail, int backPass, int backCompare,
        int readMask, int writeMask, int referenceValue
    ) {
    }

    String loaderName();

    Path gameDir();

    Path configDir();

    boolean isModLoaded(String modId);

    Optional<ModMeta> mod(String modId);

    /** Reads up to {@code maxBytes} of an entry inside a mod's jar or root, or empty if missing or larger than {@code maxBytes}. */
    Optional<byte[]> readEntry(String modId, String entry, int maxBytes);

    /**
     * Finds the unique non-ignored mod containing {@code classEntry} (for example {@code "com/example/Foo.class"}),
     * or empty when zero or multiple mods contain it.
     */
    Optional<ModMeta> findUniqueModForClass(String classEntry, Set<String> ignoreModIds);

    /** Raw game launch arguments (used to detect {@code --graphicsBackend} overrides). */
    List<String> launchArguments();

    /** Extracts loader-specific stencil state from a {@code DepthStencilState}, or {@code null} when unused. */
    default StencilState stencilState(final Object depthStencilState) {
        return null;
    }

    static ModPlatform get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        static final ModPlatform INSTANCE = load();

        private Holder() {
        }

        private static ModPlatform load() {
            ModPlatform loaded = tryInstantiate("io.github.imcinq.puremetal.FabricPlatform");
            if (loaded != null) {
                return loaded;
            }
            loaded = tryInstantiate("io.github.imcinq.puremetal.neoforge.NeoForgePlatform");
            if (loaded != null) {
                return loaded;
            }
            org.slf4j.LoggerFactory.getLogger("Pure Metal").warn(
                "Pure Metal: neither Fabric nor NeoForge platform initialized; falling back to headless platform");
            return new HeadlessFallback();
        }

        private static ModPlatform tryInstantiate(final String className) {
            try {
                Class<?> cls = Class.forName(className, true, ModPlatform.class.getClassLoader());
                return (ModPlatform) cls.getDeclaredConstructor().newInstance();
            } catch (Throwable unavailable) {
                return null;
            }
        }
    }

    /** Fallback for standalone headless unit tests run outside Fabric/NeoForge. */
    final class HeadlessFallback implements ModPlatform {
        @Override
        public String loaderName() {
            return "Headless";
        }

        @Override
        public Path gameDir() {
            return Path.of("").toAbsolutePath().normalize();
        }

        @Override
        public Path configDir() {
            return this.gameDir().resolve("config");
        }

        @Override
        public boolean isModLoaded(final String modId) {
            return false;
        }

        @Override
        public Optional<ModMeta> mod(final String modId) {
            return Optional.empty();
        }

        @Override
        public Optional<byte[]> readEntry(final String modId, final String entry, final int maxBytes) {
            return Optional.empty();
        }

        @Override
        public Optional<ModMeta> findUniqueModForClass(final String classEntry, final Set<String> ignoreModIds) {
            return Optional.empty();
        }

        @Override
        public List<String> launchArguments() {
            return List.of();
        }
    }
}
