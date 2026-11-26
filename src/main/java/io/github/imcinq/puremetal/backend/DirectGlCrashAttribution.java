package io.github.imcinq.puremetal.backend;

import io.github.imcinq.puremetal.Redact;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Post-crash attribution for mods that call LWJGL OpenGL directly under Pure Metal (where no GL context exists,
 * causing a native SIGSEGV/SIGABRT in HotSpot). Inspects the previous launch's {@code hs_err_pid*.log} on the next
 * start, verifies Pure Metal was loaded as a dynamic library (or matched by {@link StartupGuard}'s Metal PID), finds
 * the first non-LWJGL caller above the OpenGL fault, maps it to a Fabric mod when unambiguous, and logs a clear
 * warning once per crash file. Never rejects mods preemptively and never leaks local user paths.
 */
public final class DirectGlCrashAttribution {
    private static final Logger LOGGER = LoggerFactory.getLogger("Pure Metal");
    private static final Pattern HS_ERR_NAME = Pattern.compile("^hs_err_pid(\\d+)\\.log$");
    private static final Pattern HEADER_PID = Pattern.compile("\\bpid=(\\d+)\\b");
    /** HotSpot Java frame: {@code j  pkg.Cls.m(}, {@code J 123 c2 pkg.Cls.m(}, {@code J 123% jvmci pkg.Cls.m(}. */
    private static final Pattern JAVA_FRAME = Pattern.compile(
        "^\\s*(?:j\\s+|J\\s+\\d+%?\\s+(?:(?:c[12]|jvmci)\\s+)?)([A-Za-z0-9_.$]+(?:/0x[0-9a-fA-F]+)?)\\.([^\\s(.;\\[/]+)\\("
    );
    /** SpongeMixin and MixinExtras generated handler prefixes carrying the owning Fabric mod ID. */
    private static final Pattern MIXIN_HANDLER_MOD = Pattern.compile(
        "^(?:handler|redirect|modify[A-Za-z]*|args|constant|localvar|wrap[A-Za-z]+|mixinextras\\$bridge)\\$[a-z0-9]+\\$([a-z][a-z0-9_-]{1,63})\\$"
    );
    /** macOS HotSpot Dynamic libraries row: {@code 0x0000000120000000 \t/path/to/libpuremetal[-hash].dylib}. */
    private static final Pattern PUREMETAL_DYLIB_ROW = Pattern.compile(
        "^\\s*0x[0-9a-fA-F]+\\s+.*[/\\\\]libpuremetal(?:-[A-Za-z0-9._-]+)?\\.dylib\\s*$"
    );
    private static final Pattern NATIVE_GL_SYMBOL = Pattern.compile("(Java_org_lwjgl_opengl_[A-Za-z0-9_]{1,120})");
    private static final Pattern NATIVE_GL_IMAGE = Pattern.compile("\\[(liblwjgl_opengl[^\\]+\\s]*|GLEngine|OpenGL|libGL[^\\]+\\s]*)(?:\\+0x[0-9a-fA-F]+)?\\]");
    private static final Pattern DYNAMIC_LIB_ROW = Pattern.compile("^\\s*0x[0-9a-fA-F]+\\s+\\S.*");
    private static final Pattern LOCAL_PATH = Pattern.compile(
        "(?:[A-Za-z]:\\\\|\\\\\\\\|~[/\\\\]|(?<![A-Za-z0-9_.:])/)[^\\s\"'()<>\\]]+"
    );

    private static final int MAX_BYTES = 1024 * 1024;
    private static final int HEADER_MAX_BYTES = 8192;
    private static final int MAX_LINES = 12000;
    private static final int MAX_LINE_CHARS = 4096;
    private static final int MAX_MOD_NAME_CHARS = 64;
    private static final long MAX_AGE_MS = 24L * 60L * 60L * 1000L;
    private static final long EXACT_PID_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final int MAX_MARKER_BYTES = 256;

    /** Infrastructure packages skipped when walking down from an OpenGL fault to find the calling mod. */
    private static final List<String> SKIP_PREFIXES = List.of(
        "org.lwjgl.",
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "com.sun.",
        "io.github.imcinq.puremetal."
    );

    private static final Set<String> BUILTIN_MODS = Set.of(
        "minecraft", "java", "fabricloader", "mixinextras", "puremetal"
    );

    private static volatile @Nullable Attribution lastAttribution;

    private DirectGlCrashAttribution() {
    }

    /** Parsed direct-OpenGL crash evidence from one {@code hs_err_pid*.log} file. */
    public record ParsedCrash(
        String fileName,
        long headerPid,
        String glCall,
        @Nullable String callerClass,
        @Nullable String callerMethod,
        boolean pureMetalDylibLoaded,
        boolean hitVanillaGlBoundary
    ) {
    }

    /** Resolved attribution linking a direct-OpenGL crash to a Fabric mod (when identifiable) or caller class. */
    public record Attribution(
        String fileName,
        String glCall,
        @Nullable String callerClass,
        @Nullable String callerMethod,
        @Nullable String modId,
        @Nullable String modName,
        boolean confirmedMetal
    ) {
        public String summary() {
            String safeFile = sanitize(fileName);
            String safeCall = sanitize(glCall);
            String target = callerClass != null
                ? sanitize(callerMethod != null ? callerClass + "." + callerMethod : callerClass)
                : "unknown caller";
            if (modId != null) {
                String safeId = sanitize(modId);
                String label = formatModLabel(safeId, modName);
                if (confirmedMetal) {
                    return "Pure Metal: the previous Metal session crash (" + safeFile + ") called OpenGL directly (" + safeCall
                        + ") from mod " + label + " (" + target + "). Under Metal there is no OpenGL context; "
                        + "remove or disable " + label + " (or add -Dpuremetal.incompatibleMods=" + safeId
                        + " to fall back automatically) to use Metal cleanly.";
                }
                return "Pure Metal: the previous crash (" + safeFile + ") called OpenGL (" + safeCall
                    + ") from mod " + label + " (" + target + ") while Pure Metal was loaded. "
                    + "If Metal was the active backend, this mod may be incompatible with Pure Metal "
                    + "(try disabling " + label + " or adding -Dpuremetal.incompatibleMods=" + safeId + ").";
            }
            if (confirmedMetal) {
                boolean vanillaOrUnknown = callerClass == null
                    || callerClass.startsWith("net.minecraft.")
                    || callerClass.startsWith("com.mojang.")
                    || callerClass.startsWith("io.github.imcinq.puremetal.");
                if (vanillaOrUnknown) {
                    return "Pure Metal: the previous Metal session crash (" + safeFile + ") called OpenGL (" + safeCall
                        + ") from " + target + ". Under Metal there is no OpenGL context; "
                        + "switch Graphics API to OpenGL in Video Settings if this keeps happening.";
                }
                return "Pure Metal: the previous Metal session crash (" + safeFile + ") called OpenGL directly (" + safeCall
                    + ") from " + target + ". Under Metal there is no OpenGL context; "
                    + "remove the mod providing that class or switch Graphics API to OpenGL in Video Settings.";
            }
            return "Pure Metal: the previous crash (" + safeFile + ") called OpenGL (" + safeCall
                + ") from " + target + " while Pure Metal was loaded. "
                + "If Metal was the active backend, a mod may have called OpenGL without an active GL context.";
        }
    }

    /** Function mapping a class name (and optional method name for Mixin handlers) to a mod (id, displayName). */
    @FunctionalInterface
    public interface ModResolver {
        @Nullable ModRef resolve(String className, @Nullable String methodName);
    }

    public record ModRef(String id, String name) {
    }

    public static @Nullable Attribution lastAttribution() {
        return lastAttribution;
    }

    /**
     * Checks for a recent unreported direct-OpenGL {@code hs_err_pid*.log} crash from Pure Metal and logs attribution.
     *
     * @param previousMetalPid PID from {@link StartupGuard}'s unfinished Metal marker, or {@code -1} if none.
     */
    public static void checkPreviousCrash(final long previousMetalPid) {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            Path gameDir = loader.getGameDir().toAbsolutePath().normalize();
            Path cwd = Path.of("").toAbsolutePath().normalize();
            List<Path> dirs = cwd.equals(gameDir) ? List.of(gameDir) : List.of(gameDir, cwd);
            Path markerFile = loader.getConfigDir().resolve("puremetal-last-gl-crash.txt");
            Attribution found = checkDirectories(dirs, markerFile, previousMetalPid, System.currentTimeMillis(), DirectGlCrashAttribution::resolveFabricMod);
            if (found != null) {
                lastAttribution = found;
                LOGGER.warn("{}", found.summary());
            }
        } catch (Throwable t) {
            LOGGER.debug("Pure Metal post-crash GL attribution check failed: {}", Redact.error(t));
        }
    }

    /** Package-visible for headless tests (no FabricLoader or Minecraft dependency). */
    static @Nullable Attribution checkDirectories(
        final List<Path> searchDirs,
        final Path markerFile,
        final long previousMetalPid,
        final long nowMs,
        final ModResolver resolver
    ) {
        Candidate chosen = selectCandidate(searchDirs, previousMetalPid, nowMs);
        if (chosen == null) {
            return null;
        }
        String token = fileToken(chosen.path());
        if (token == null) {
            return null;
        }
        Path lockFile = markerFile.toAbsolutePath().resolveSibling("puremetal-last-gl-crash.lock");
        try {
            Path parent = lockFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            return null;
        }
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock lock = ch.tryLock()) {
            if (lock == null) {
                return null;
            }
            if (token.equals(readMarker(markerFile))) {
                return null;
            }
            ParsedCrash parsed = parseHsErr(chosen.path());
            if (parsed == null) {
                return null;
            }
            if (chosen.exactPid()) {
                if (parsed.headerPid() != previousMetalPid) {
                    return null;
                }
            } else if (!parsed.pureMetalDylibLoaded() || parsed.hitVanillaGlBoundary()) {
                return null;
            }
            ModRef mod = parsed.callerClass() != null && !parsed.hitVanillaGlBoundary()
                ? resolver.resolve(parsed.callerClass(), parsed.callerMethod())
                : null;
            Attribution attribution = new Attribution(
                parsed.fileName(),
                parsed.glCall(),
                parsed.callerClass(),
                parsed.callerMethod(),
                mod != null ? mod.id() : null,
                mod != null ? mod.name() : null,
                chosen.exactPid()
            );
            writeMarker(markerFile, token);
            return attribution;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Pure Metal could not claim GL crash attribution marker: {}", Redact.error(e));
            return null;
        }
    }

    private record Candidate(Path path, long mtimeMs, boolean exactPid) {
    }

    /**
     * Picks one candidate across all search directories: exact previous Metal PID (within 7 d) wins across every
     * directory before falling back to the single newest recent report (within 24 h) across every directory.
     */
    private static @Nullable Candidate selectCandidate(final List<Path> dirs, final long previousMetalPid, final long nowMs) {
        Set<Path> uniqueDirs = new LinkedHashSet<>();
        for (Path d : dirs) {
            if (d != null && Files.isDirectory(d)) {
                uniqueDirs.add(d.toAbsolutePath().normalize());
            }
        }
        if (uniqueDirs.isEmpty()) {
            return null;
        }
        if (previousMetalPid > 0L) {
            Candidate bestExact = null;
            String expectedName = "hs_err_pid" + previousMetalPid + ".log";
            for (Path dir : uniqueDirs) {
                Path exact = dir.resolve(expectedName);
                if (!Files.isRegularFile(exact)) continue;
                long mtime = lastModifiedMs(exact);
                if (mtime > 0L && nowMs - mtime <= EXACT_PID_MAX_AGE_MS && readHeaderPid(exact) == previousMetalPid) {
                    if (bestExact == null || mtime > bestExact.mtimeMs()) {
                        bestExact = new Candidate(exact, mtime, true);
                    }
                }
            }
            if (bestExact != null) {
                return bestExact;
            }
        }
        List<Candidate> recent = new ArrayList<>();
        for (Path dir : uniqueDirs) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "hs_err_pid*.log")) {
                for (Path p : stream) {
                    if (!Files.isRegularFile(p)) continue;
                    String name = p.getFileName().toString();
                    if (!HS_ERR_NAME.matcher(name).matches()) continue;
                    long mtime = lastModifiedMs(p);
                    if (mtime > 0L && nowMs - mtime <= MAX_AGE_MS) {
                        recent.add(new Candidate(p, mtime, false));
                    }
                }
            } catch (IOException ignored) {
            }
        }
        if (recent.isEmpty()) {
            return null;
        }
        recent.sort(Comparator.comparingLong(Candidate::mtimeMs).reversed());
        return recent.getFirst();
    }

    /** Parses one HotSpot {@code hs_err} file; returns null when the fault does not involve direct OpenGL. */
    static @Nullable ParsedCrash parseHsErr(final Path file) {
        long headerPid = -1L;
        boolean expectProblematicFrame = false;
        boolean inNativeFrames = false;
        boolean inJavaFrames = false;
        boolean sawNativeBlock = false;
        boolean sawJavaBlock = false;
        boolean inDynamicLibs = false;
        boolean sawDynamicLibs = false;
        boolean topFaultChain = true;
        boolean sawGl = false;
        boolean pureMetalDylibLoaded = false;
        boolean callerSearchStopped = false;
        boolean hitVanillaGlBoundary = false;
        String glCall = null;
        String callerClass = null;
        String callerMethod = null;

        try (InputStream raw = Files.newInputStream(file);
             BoundedInputStream bounded = new BoundedInputStream(raw, MAX_BYTES)) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
            Reader reader = new InputStreamReader(bounded, decoder);
            StringBuilder lineBuf = new StringBuilder(256);
            for (int n = 0; n < MAX_LINES; n++) {
                String line = readBoundedLine(reader, lineBuf);
                if (line == null) break;

                if (headerPid < 0L && n < 20 && line.startsWith("#")) {
                    Matcher pm = HEADER_PID.matcher(line);
                    if (pm.find()) {
                        try {
                            headerPid = Long.parseLong(pm.group(1));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                if (expectProblematicFrame) {
                    expectProblematicFrame = false;
                    if (line.startsWith("#")) {
                        String pf = line.substring(1).trim();
                        String nativeGl = matchNativeGl(pf);
                        if (nativeGl != null) {
                            sawGl = true;
                            glCall = nativeGl;
                        } else {
                            Matcher jm = JAVA_FRAME.matcher(pf);
                            if (jm.find()) {
                                if (jm.group(1).startsWith("org.lwjgl.opengl.")) {
                                    sawGl = true;
                                    glCall = jm.group(1) + "." + jm.group(2);
                                } else if (!isNeutralJavaFaultFrame(jm.group(1))) {
                                    topFaultChain = false;
                                }
                            } else if (!isNeutralFaultFrame(pf)) {
                                topFaultChain = false;
                            }
                        }
                    }
                    continue;
                }
                if (n < 30 && line.startsWith("# Problematic frame:")) {
                    expectProblematicFrame = true;
                    continue;
                }

                if (!sawDynamicLibs && line.startsWith("Dynamic libraries:")) {
                    inDynamicLibs = true;
                    sawDynamicLibs = true;
                    inNativeFrames = false;
                    inJavaFrames = false;
                    continue;
                }
                if (inDynamicLibs) {
                    if (PUREMETAL_DYLIB_ROW.matcher(line).matches()) {
                        pureMetalDylibLoaded = true;
                    } else if (!line.isBlank() && !DYNAMIC_LIB_ROW.matcher(line).matches()) {
                        inDynamicLibs = false;
                    }
                    continue;
                }

                if (!sawNativeBlock && line.startsWith("Native frames:")) {
                    inNativeFrames = true;
                    inJavaFrames = false;
                    sawNativeBlock = true;
                    continue;
                }
                if (!sawJavaBlock && line.startsWith("Java frames:")) {
                    inJavaFrames = true;
                    inNativeFrames = false;
                    sawJavaBlock = true;
                    continue;
                }
                if ((inNativeFrames || inJavaFrames) && line.isBlank()) {
                    inNativeFrames = false;
                    inJavaFrames = false;
                    continue;
                }

                if (inNativeFrames || inJavaFrames) {
                    if (inNativeFrames) {
                        String nativeGl = matchNativeGl(line);
                        if (nativeGl != null && (sawGl || topFaultChain)) {
                            sawGl = true;
                            if (glCall == null) {
                                glCall = nativeGl;
                            }
                            continue;
                        }
                    }
                    Matcher m = JAVA_FRAME.matcher(line);
                    if (m.find()) {
                        String cls = m.group(1);
                        String method = m.group(2);
                        if (cls.startsWith("org.lwjgl.opengl.")) {
                            if (sawGl || topFaultChain) {
                                sawGl = true;
                                if (glCall == null || glCall.startsWith("native:")) {
                                    glCall = cls + "." + method;
                                }
                            }
                        } else if (sawGl) {
                            if (!callerSearchStopped) {
                                String rawMixinMod = extractRawMixinModId(method);
                                if (rawMixinMod != null && !BUILTIN_MODS.contains(rawMixinMod)) {
                                    callerClass = cls;
                                    callerMethod = method;
                                    callerSearchStopped = true;
                                } else if ("puremetal".equals(rawMixinMod)) {
                                    // Pure Metal's own mixin frame on a host class: skip without misattributing.
                                } else if (cls.startsWith("com.mojang.blaze3d.opengl.")) {
                                    // Stop at the vanilla OpenGL boundary so an OpenGL-backend crash never blames a mod farther down.
                                    callerClass = cls;
                                    callerMethod = method;
                                    hitVanillaGlBoundary = true;
                                    callerSearchStopped = true;
                                } else if (!isSkippedCaller(cls)) {
                                    callerClass = cls;
                                    callerMethod = method;
                                    callerSearchStopped = true;
                                }
                            }
                        } else if (!isNeutralJavaFaultFrame(cls)) {
                            // Hit a non-GL application/mod frame before any GL frame: not a direct-GL fault.
                            topFaultChain = false;
                        }
                    } else if (inNativeFrames && !sawGl && !isNeutralFaultFrame(line)) {
                        topFaultChain = false;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (!sawGl) {
            return null;
        }
        return new ParsedCrash(
            file.getFileName().toString(),
            headerPid,
            glCall != null ? glCall : "org.lwjgl.opengl",
            callerClass,
            callerMethod,
            pureMetalDylibLoaded,
            hitVanillaGlBoundary
        );
    }

    private static long readHeaderPid(final Path file) {
        try (InputStream raw = Files.newInputStream(file);
             BoundedInputStream bounded = new BoundedInputStream(raw, HEADER_MAX_BYTES)) {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
            Reader reader = new InputStreamReader(bounded, decoder);
            StringBuilder lineBuf = new StringBuilder(256);
            for (int n = 0; n < 20; n++) {
                String line = readBoundedLine(reader, lineBuf);
                if (line == null) break;
                if (line.startsWith("#")) {
                    Matcher pm = HEADER_PID.matcher(line);
                    if (pm.find()) {
                        try {
                            long pid = Long.parseLong(pm.group(1));
                            if (pid > 0L) return pid;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) {
        }
        return -1L;
    }

    private static @Nullable String matchNativeGl(final String line) {
        Matcher sym = NATIVE_GL_SYMBOL.matcher(line);
        if (sym.find()) {
            return "native:" + sym.group(1);
        }
        Matcher img = NATIVE_GL_IMAGE.matcher(line);
        if (img.find()) {
            return "native:" + img.group(1);
        }
        return null;
    }

    /** True for native/VM/null-PC frames that commonly sit directly above an LWJGL OpenGL fault. */
    private static boolean isNeutralFaultFrame(final String line) {
        String s = line.trim();
        if (s.isEmpty() || s.startsWith("V ") || s.startsWith("v ") || s.startsWith("[")) {
            return true;
        }
        if (s.startsWith("C  0x0000000000000000") || s.startsWith("C  0x0 ") || s.equals("C  0x0")) {
            return true;
        }
        return s.contains("[libjvm") || s.contains("[libsystem_") || s.contains("[dyld")
            || s.contains("[liblwjgl.dylib") || s.contains("[libobjc");
    }

    private static boolean isNeutralJavaFaultFrame(final String cls) {
        return cls.startsWith("org.lwjgl.system.") || cls.startsWith("java.") || cls.startsWith("jdk.")
            || cls.startsWith("sun.");
    }

    private static boolean isSkippedCaller(final String className) {
        if (className.startsWith("jdk.proxy") || className.contains(".$Proxy")) {
            return true;
        }
        for (String prefix : SKIP_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Strips generated hidden/lambda suffixes so {@code ModContainer.findPath} can locate the declaring class. */
    static String normalizeClassName(final String className) {
        int lambda = className.indexOf("$$Lambda");
        if (lambda > 0) {
            return className.substring(0, lambda);
        }
        int slash = className.indexOf('/');
        if (slash > 0) {
            return className.substring(0, slash);
        }
        return className;
    }

    /** Extracts a non-builtin Fabric mod ID from a Mixin/MixinExtras handler name when a mod injected into a host class. */
    static @Nullable String extractMixinModId(final @Nullable String methodName) {
        String id = extractRawMixinModId(methodName);
        return id != null && !BUILTIN_MODS.contains(id) ? id : null;
    }

    private static @Nullable String extractRawMixinModId(final @Nullable String methodName) {
        if (methodName == null) {
            return null;
        }
        Matcher m = MIXIN_HANDLER_MOD.matcher(methodName);
        return m.find() ? m.group(1) : null;
    }

    private static @Nullable ModRef resolveFabricMod(final String className, final @Nullable String methodName) {
        FabricLoader loader = FabricLoader.getInstance();
        String mixinModId = extractMixinModId(methodName);
        if (mixinModId != null) {
            Optional<ModContainer> mod = loader.getModContainer(mixinModId);
            if (mod.isPresent()) {
                return new ModRef(mod.get().getMetadata().getId(), mod.get().getMetadata().getName());
            }
        }
        String normalized = normalizeClassName(className);
        if (normalized.startsWith("com.mojang.blaze3d.opengl.") || isSkippedCaller(normalized)) {
            return null;
        }
        String entry = normalized.replace('.', '/') + ".class";
        ModRef unique = null;
        for (ModContainer mod : loader.getAllMods()) {
            String id = mod.getMetadata().getId();
            if (BUILTIN_MODS.contains(id)) {
                continue;
            }
            if (mod.findPath(entry).isPresent()) {
                if (unique != null) {
                    // Class appears in multiple mods (e.g. shaded library): stay conservative instead of guessing.
                    return null;
                }
                unique = new ModRef(id, mod.getMetadata().getName());
            }
        }
        return unique;
    }

    private static String formatModLabel(final String safeId, final @Nullable String modName) {
        if (modName == null || modName.contains("/") || modName.contains("\\")) {
            return safeId;
        }
        String safeName = sanitize(modName);
        if (safeName.length() > MAX_MOD_NAME_CHARS) {
            safeName = safeName.substring(0, MAX_MOD_NAME_CHARS).trim();
        }
        return !safeName.isEmpty() && !safeName.equalsIgnoreCase(safeId)
            ? safeName + " (" + safeId + ")"
            : safeId;
    }

    private static String sanitize(final String value) {
        String redacted = Redact.home(value);
        StringBuilder out = new StringBuilder(redacted.length());
        for (int i = 0; i < redacted.length(); i++) {
            char c = redacted.charAt(i);
            int type = Character.getType(c);
            if (Character.isISOControl(c) || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
                out.append(' ');
            } else {
                out.append(c);
            }
        }
        return LOCAL_PATH.matcher(out.toString()).replaceAll("<path>").trim();
    }

    private static @Nullable String readBoundedLine(final Reader reader, final StringBuilder buf) throws IOException {
        buf.setLength(0);
        int ch = reader.read();
        if (ch < 0) {
            return null;
        }
        while (ch >= 0 && ch != '\n') {
            if (ch != '\r' && buf.length() < MAX_LINE_CHARS) {
                buf.append((char) ch);
            }
            ch = reader.read();
        }
        return buf.toString();
    }

    private static final class BoundedInputStream extends InputStream {
        private final InputStream in;
        private int remaining;

        BoundedInputStream(final InputStream in, final int maxBytes) {
            this.in = in;
            this.remaining = maxBytes;
        }

        @Override
        public int read() throws IOException {
            if (this.remaining <= 0) return -1;
            int b = this.in.read();
            if (b >= 0) this.remaining--;
            return b;
        }

        @Override
        public int read(final byte[] b, final int off, final int len) throws IOException {
            if (this.remaining <= 0) return -1;
            int n = this.in.read(b, off, Math.min(len, this.remaining));
            if (n > 0) this.remaining -= n;
            return n;
        }

        @Override
        public void close() throws IOException {
            this.in.close();
        }
    }

    private static long lastModifiedMs(final Path p) {
        try {
            FileTime t = Files.getLastModifiedTime(p);
            return t.toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static @Nullable String fileToken(final Path p) {
        long mtime = lastModifiedMs(p);
        if (mtime <= 0L) {
            return null;
        }
        return p.getFileName().toString() + ":" + mtime;
    }

    private static @Nullable String readMarker(final Path markerFile) {
        if (!Files.isRegularFile(markerFile)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(markerFile)) {
            byte[] bytes = in.readNBytes(MAX_MARKER_BYTES + 1);
            if (bytes.length > MAX_MARKER_BYTES) {
                return null;
            }
            return new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            return null;
        }
    }

    private static void writeMarker(final Path markerFile, final String token) {
        try {
            Path parent = markerFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(markerFile, token + "\n", StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("Pure Metal could not write GL crash attribution marker: {}", Redact.error(e));
        }
    }
}
