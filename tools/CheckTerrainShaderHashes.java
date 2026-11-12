import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Release check for the relaxed terrain math gate: prints the SHA-256 of every terrain shader file in a Minecraft client jar and a Sodium jar
 * and says whether each hash is one Pure Metal pins (it must appear in TerrainShaderProof.java). A file marked NEW means the stock text changed:
 * read the diff of that file, and if the change is harmless for relaxed math add the hash to the matching Variant (or map) in TerrainShaderProof.
 * Until then that version keeps standard math, which the F3 screen and the settings page report as "Terrain math: Standard (...)".
 *
 * Usage (from the repository root, no classpath needed):
 *   java tools/CheckTerrainShaderHashes.java <minecraft-client.jar> [sodium.jar] [path/to/TerrainShaderProof.java]
 * Exit code 0: everything pinned. 1: something is not pinned or missing. 2: bad arguments.
 *
 * Not covered by hashes, check by hand on a Minecraft bump: the ALPHA_CUTOUT and OIT_* defines the terrain pipelines set in RenderPipelines
 * (0.5 and 0.1 for vanilla, 0.5 and 0.01 for Sodium's ShaderChunkRenderer), and LevelRenderer.OIT_* constants (javap -c -constants).
 */
public final class CheckTerrainShaderHashes {
    private static final String DEFAULT_PROOF = "src/main/java/io/github/imcinq/puremetal/perf/TerrainShaderProof.java";
    private static final String MC = "assets/minecraft/shaders/";
    private static final String SODIUM = "assets/sodium/shaders/";
    private static final String[] MINECRAFT_FILES = {
        "core/terrain.vsh", "core/terrain.fsh", "core/block.vsh", "core/block.fsh",
        "include/chunksection.glsl", "include/terrainglobals.glsl", "include/sample_lightmap.glsl", "include/projection.glsl",
        "include/globals.glsl", "include/fog.glsl", "include/texture_sampling.glsl", "include/dynamictransforms.glsl",
        "include/oit.glsl", "include/oit_common.glsl", "include/oit_depth_sample.glsl", "include/oit_depth_bounds.glsl",
        "include/oit_add_transmittance.glsl", "include/oit_sample.glsl"};
    private static final String[] SODIUM_FILES = {
        "blocks/block_layer_opaque.vsh", "blocks/block_layer_opaque.fsh",
        "include/chunk_vertex.glsl", "include/fog.glsl", "include/globals.glsl", "include/chunk_material.glsl"};

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
        String pinned = Files.readString(proof, StandardCharsets.UTF_8);
        boolean ok = true;
        ok &= check("Minecraft", Path.of(args[0]), MC, MINECRAFT_FILES, pinned);
        if (args.length >= 2) {
            ok &= check("Sodium", Path.of(args[1]), SODIUM, SODIUM_FILES, pinned);
        }
        System.out.println(ok ? "All terrain shader hashes are pinned." : "Some hashes are NOT pinned: re-pin before the release (see the header of this tool).");
        System.exit(ok ? 0 : 1);
    }

    private static boolean check(final String label, final Path jar, final String root, final String[] files, final String pinned) throws IOException {
        System.out.println("== " + label + ": " + jar.getFileName());
        boolean ok = true;
        Map<String, String> hashes = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String file : files) {
                ZipEntry entry = zip.getEntry(root + file);
                if (entry == null) {
                    System.out.println("  MISSING " + file);
                    ok = false;
                    continue;
                }
                try (var in = zip.getInputStream(entry)) {
                    hashes.put(file, sha256(in.readAllBytes()));
                }
            }
        }
        for (var entry : hashes.entrySet()) {
            boolean known = pinned.contains(entry.getValue());
            ok &= known;
            System.out.println("  " + (known ? "pinned " : "NEW    ") + entry.getValue() + " " + entry.getKey());
        }
        return ok;
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
