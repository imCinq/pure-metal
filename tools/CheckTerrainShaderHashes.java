import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Release check for the relaxed terrain math gate. It reads the pinned families from TerrainShaderProof.java (shader id, and per variant the vertex
 * hash, fragment hash and the include set, plus the OIT includes every family also needs), hashes the same files in a Minecraft client jar and a Sodium
 * jar, and decides per family exactly like the runtime proof: some ONE variant must match completely (each file against its own pinned hash).
 * Mixing hashes of different variants, or a hash pinned for another file, does not pass. When no variant matches, the closest one is printed with the
 * files that differ and their current hashes, which is what to pin after reading the diff of those files. Until pinned, that version keeps standard
 * math, reported in F3 and the settings as "Terrain math: Standard (...)".
 *
 * Usage (from the repository root, no classpath needed):
 *   java tools/CheckTerrainShaderHashes.java <minecraft-client.jar> [sodium.jar] [path/to/TerrainShaderProof.java]
 * Without a Sodium jar the Sodium family is skipped (and reported as skipped). Exit code 0: every checked family matches a pinned variant.
 * 1: some family matches none (or a file is missing). 2: bad arguments.
 *
 * Not covered by hashes, check by hand on a Minecraft bump: the ALPHA_CUTOUT and OIT_* defines the terrain pipelines set in RenderPipelines
 * (0.5 and 0.1 for vanilla, 0.5 and 0.01 for Sodium's ShaderChunkRenderer), and LevelRenderer.OIT_* constants (javap -c -constants).
 */
public final class CheckTerrainShaderHashes {
    private static final String DEFAULT_PROOF = "src/main/java/io/github/imcinq/puremetal/perf/TerrainShaderProof.java";
    private static final Pattern MAP = Pattern.compile("Map<String,String>\\s+(\\w+)\\s*=\\s*Map\\.of\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern PAIR = Pattern.compile("\"([^\"]+)\"\\s*,\\s*\"([0-9a-f]{64})\"");
    private static final Pattern FAMILY = Pattern.compile("new Family\\(Identifier\\.fromNamespaceAndPath\\(\"([^\"]+)\",\"([^\"]+)\"\\)");
    private static final Pattern VARIANT = Pattern.compile("new Variant\\(\"([0-9a-f]{64})\",\"([0-9a-f]{64})\",(\\w+)\\)");

    private record Variant(String mapName, Map<String, String> files) {}

    private record Family(String id, List<Variant> variants) {}

    private CheckTerrainShaderHashes() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            System.err.println("usage: java tools/CheckTerrainShaderHashes.java <minecraft-client.jar> [sodium.jar] [TerrainShaderProof.java]");
            System.exit(2);
        }
        Path proof = Path.of(args.length == 3 ? args[2] : DEFAULT_PROOF);
        if (!Files.isRegularFile(proof)) {
            System.err.println("pinned hashes not found: " + proof + " (run from the repository root or pass the path)");
            System.exit(2);
        }
        String text = Files.readString(proof, StandardCharsets.UTF_8);
        Map<String, Map<String, String>> maps = new LinkedHashMap<>();
        Matcher maps0 = MAP.matcher(text);
        while (maps0.find()) {
            Map<String, String> pairs = new LinkedHashMap<>();
            Matcher pair = PAIR.matcher(maps0.group(2));
            while (pair.find()) {
                pairs.put(pair.group(1), pair.group(2));
            }
            maps.put(maps0.group(1), pairs);
        }
        Map<String, String> oit = maps.get("OIT");
        List<Family> families = parseFamilies(text, maps, oit);
        if (families.isEmpty() || oit == null) {
            System.err.println("could not read the pinned families from " + proof + " (did its layout change? update this tool)");
            System.exit(2);
        }
        try (ZipFile minecraft = new ZipFile(Path.of(args[0]).toFile());
             ZipFile sodium = args.length >= 2 ? new ZipFile(Path.of(args[1]).toFile()) : null) {
            boolean ok = true;
            for (Family family : families) {
                if (family.id().startsWith("sodium:") && sodium == null) {
                    System.out.println("== " + family.id() + ": skipped (no Sodium jar given)");
                    continue;
                }
                ok &= checkFamily(family, minecraft, sodium);
            }
            System.out.println(ok ? "All checked terrain shader families match a pinned variant." : "Some families match NO pinned variant: re-pin before the release (see the header of this tool).");
            System.exit(ok ? 0 : 1);
        }
    }

    private static List<Family> parseFamilies(final String text, final Map<String, Map<String, String>> maps, final Map<String, String> oit) {
        List<Family> families = new ArrayList<>();
        Matcher start = FAMILY.matcher(text);
        List<int[]> spans = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        while (start.find()) {
            spans.add(new int[] {start.end(), text.length()});
            ids.add(start.group(1) + ":" + start.group(2));
        }
        for (int i = 0; i < spans.size(); i++) {
            int end = i + 1 < spans.size() ? spans.get(i + 1)[0] : text.length();
            String chunk = text.substring(spans.get(i)[0], end);
            List<Variant> variants = new ArrayList<>();
            Matcher variant = VARIANT.matcher(chunk);
            String id = ids.get(i);
            while (variant.find()) {
                Map<String, String> files = new LinkedHashMap<>();
                String stem = id.substring(id.indexOf(':') + 1);
                files.put(id.substring(0, id.indexOf(':')) + ":" + stem + ".vsh", variant.group(1));
                files.put(id.substring(0, id.indexOf(':')) + ":" + stem + ".fsh", variant.group(2));
                Map<String, String> includes = maps.get(variant.group(3));
                if (includes != null) {
                    includes.forEach((k, v) -> files.put(k, v));
                }
                oit.forEach((k, v) -> files.put(k, v));
                variants.add(new Variant(variant.group(3), files));
            }
            families.add(new Family(id, variants));
        }
        return families;
    }

    /** Jar path of a pinned file id: "ns:path.vsh|fsh" is a shader, "ns:name.glsl" an include. */
    private static String entryName(final String id) {
        String ns = id.substring(0, id.indexOf(':'));
        String path = id.substring(id.indexOf(':') + 1);
        return "assets/" + ns + "/shaders/" + (path.endsWith(".glsl") ? "include/" : "") + path;
    }

    private static boolean checkFamily(final Family family, final ZipFile minecraft, final ZipFile sodium) throws IOException {
        System.out.println("== " + family.id());
        List<String> bestDiffs = null;
        String bestName = "";
        for (Variant variant : family.variants()) {
            List<String> diffs = new ArrayList<>();
            for (var file : variant.files().entrySet()) {
                String id = file.getKey();
                ZipFile jar = id.startsWith("sodium:") ? sodium : minecraft;
                String actual = jar == null ? null : hashOf(jar, entryName(id));
                if (!file.getValue().equals(actual)) {
                    diffs.add("    " + id + ": pinned " + file.getValue() + ", jar " + (actual == null ? "MISSING" : actual));
                }
            }
            if (diffs.isEmpty()) {
                System.out.println("  OK: matches pinned variant " + variant.mapName() + " completely (" + variant.files().size() + " files)");
                return true;
            }
            if (bestDiffs == null || diffs.size() < bestDiffs.size()) {
                bestDiffs = diffs;
                bestName = variant.mapName();
            }
        }
        System.out.println("  NO pinned variant matches. Closest is " + bestName + "; these files differ:");
        if (bestDiffs != null) {
            bestDiffs.forEach(System.out::println);
        }
        return false;
    }

    private static String hashOf(final ZipFile zip, final String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            return null;
        }
        try (var in = zip.getInputStream(entry)) {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
