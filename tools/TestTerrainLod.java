// Offline terrain LOD checks: bootstraps Minecraft's registries headlessly (no window, no GPU) and runs the real
// CoarseMesher + VanillaEmitter on synthetic sections. Checks the policy table, hysteresis, exact coverage of the
// visible surface (no holes, only hidden extras), cell geometry, corner lighting, texture choice, ineligible
// categories, section borders and per-thread state reset.
//
// Reproduce geometry and vanilla/Sodium Fabric transformation checks:
//   python3 -I tools/test_terrain_lod.py
import com.mojang.blaze3d.vertex.QuadInstance;
import io.github.imcinq.puremetal.lod.CoarseMesher;
import io.github.imcinq.puremetal.lod.TerrainLod;
import io.github.imcinq.puremetal.lod.VanillaEmitter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Vector3f;
import org.joml.Vector3fc;

public final class TestTerrainLod {
    static int failures;
    static int quadReads;

    static void check(boolean ok, String what) {
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what);
        }
    }

    // ---- synthetic models: unit cubes whose UV u encodes the block id, v the face ----
    record Part(Map<Direction, List<BakedQuad>> quads, List<BakedQuad> unculled) implements BlockStateModelPart {
        public List<BakedQuad> getQuads(Direction d) {
            quadReads++;
            return d == null ? unculled : quads.getOrDefault(d, List.of());
        }

        public boolean useAmbientOcclusion() {
            return true;
        }

        public Material.Baked particleMaterial() {
            return null;
        }

        public int materialFlags() {
            return 0;
        }
    }

    /** A model class outside net.minecraft, like a modded/custom model. */
    record CustomModel(BlockStateModelPart part) implements BlockStateModel {
        public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
            output.add(part);
        }

        public Material.Baked particleMaterial() {
            return null;
        }

        public int materialFlags() {
            return 0;
        }
    }

    static long uv(float u, float v) {
        return ((long) Float.floatToRawIntBits(u) << 32) | (Float.floatToRawIntBits(v) & 0xFFFFFFFFL);
    }

    static BakedQuad face(Direction d, int id, float inset, ChunkSectionLayer layer, int tint) {
        float lo = inset;
        float hi = 1.0F - inset;
        float p = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
        Vector3f[] v = new Vector3f[4];
        float[][] t = {{lo, lo}, {lo, hi}, {hi, hi}, {hi, lo}};
        for (int i = 0; i < 4; i++) {
            float a = t[i][0];
            float b = t[i][1];
            v[i] = switch (d.getAxis()) {
                case X -> new Vector3f(p, a, b);
                case Y -> new Vector3f(a, p, b);
                case Z -> new Vector3f(a, b, p);
            };
        }
        BakedQuad.MaterialInfo info = new BakedQuad.MaterialInfo(null, layer, null, null, null, tint, null, 0);
        float u = id;
        float vv = d.ordinal();
        return new BakedQuad(v[0], v[1], v[2], v[3], uv(u, vv), uv(u + 0.25F, vv), uv(u + 0.5F, vv), uv(u + 0.75F, vv), d, info);
    }

    static Part cube(int id, float inset, ChunkSectionLayer layer, boolean grass) {
        Map<Direction, List<BakedQuad>> q = new HashMap<>();
        for (Direction d : Direction.values()) {
            List<BakedQuad> list = new ArrayList<>();
            list.add(face(d, id, inset, layer, grass && d == Direction.UP ? 0 : -1));
            if (grass && d.getAxis() != Direction.Axis.Y) {
                list.add(face(d, id + 100, inset, ChunkSectionLayer.CUTOUT, 0)); // grass side overlay
            }
            q.put(d, list);
        }
        return new Part(q, List.of());
    }

    // ---- synthetic world ----
    static final class World implements BlockAndTintGetter {
        final Map<Long, BlockState> blocks = new HashMap<>();
        int stateReads;

        BlockState at(int x, int y, int z) {
            return blocks.getOrDefault(BlockPos.asLong(x, y, z), Blocks.AIR.defaultBlockState());
        }

        void set(int x, int y, int z, BlockState s) {
            blocks.put(BlockPos.asLong(x, y, z), s);
        }

        public BlockState getBlockState(BlockPos pos) {
            stateReads++;
            return at(pos.getX(), pos.getY(), pos.getZ());
        }

        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        public int getHeight() {
            return 384;
        }

        public int getMinY() {
            return -64;
        }

        public CardinalLighting cardinalLighting() {
            return CardinalLighting.DEFAULT;
        }

        public LevelLightEngine getLightEngine() {
            return LevelLightEngine.EMPTY;
        }

        public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return 0xFF30A040;
        }

        @Override
        public int getBrightness(LightLayer layer, BlockPos pos) {
            int h = pos.getX() * 7 + pos.getY() * 3 + pos.getZ() * 5;
            return layer == LightLayer.SKY ? Math.floorMod(h, 16) : Math.floorMod(h >> 2, 16);
        }
    }

    record Face(int x, int y, int z, Direction d) {
    }

    static final Direction[] DIRS = Direction.values();

    /** Faces vanilla would draw for the blocks of section (0,0,0) at full detail (cube models only). */
    static Set<Face> fullFaces(World w, Map<BlockState, BlockStateModel> models) {
        Set<Face> out = new HashSet<>();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState s = w.at(x, y, z);
                    if (s.isAir() || !models.containsKey(s)) {
                        continue;
                    }
                    for (Direction d : DIRS) {
                        if (Block.shouldRenderFace(s, w.at(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()), d)) {
                            out.add(new Face(x, y, z, d));
                        }
                    }
                }
            }
        }
        return out;
    }

    record Emitted(int cx, int cy, int cz, BakedQuad quad, int[] colors, int[] lights) {
    }

    static final class Result {
        final Set<Face> faces = new HashSet<>();
        final List<Emitted> coarse = new ArrayList<>();
        int fineQuads;
        int coarseQuads;
        byte state;
        int merged;
        final Set<Long> covered = new HashSet<>();
    }

    /** Runs the coarse path the way the vanilla mixin does: covered blocks emit at their cell origin, others normally. */
    static Result build(World w, Map<BlockState, BlockStateModel> models, BlockStateModelSet set, BlockColors colors, boolean ao) {
        Result r = new Result();
        CoarseMesher m = CoarseMesher.begin(w, 0, 0, 0, ao, set, colors);
        VanillaEmitter emitter = VanillaEmitter.of((x, y, z, quad, inst) -> {
            int[] c = new int[4];
            int[] l = new int[4];
            for (int v = 0; v < 4; v++) {
                c[v] = inst.getColor(v);
                l[v] = inst.getLightCoords(v);
            }
            r.coarse.add(new Emitted((int) x, (int) y, (int) z, quad, c, l));
        });
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    BlockState s = w.at(x, y, z);
                    if (s.isAir() || !models.containsKey(s)) {
                        continue;
                    }
                    if (m != null && m.covers(x, y, z)) {
                        r.covered.add(BlockPos.asLong(x, y, z));
                        m.emitIfOrigin(x, y, z, emitter);
                        continue;
                    }
                    for (Direction d : DIRS) {
                        if (Block.shouldRenderFace(s, w.at(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ()), d)) {
                            r.faces.add(new Face(x, y, z, d));
                            r.fineQuads += s.is(Blocks.GRASS_BLOCK) && d.getAxis() != Direction.Axis.Y ? 2 : 1;
                        }
                    }
                }
            }
        }
        r.state = CoarseMesher.end();
        VanillaEmitter.end();
        r.merged = CoarseMesher.lastMergedCells();
        for (Emitted e : r.coarse) {
            r.coarseQuads++;
            BakedQuad q = e.quad;
            Direction d = q.direction();
            float minX = 9, minY = 9, minZ = 9, maxX = -9, maxY = -9, maxZ = -9;
            for (int v = 0; v < 4; v++) {
                Vector3fc p = q.position(v);
                minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
                minY = Math.min(minY, p.y()); maxY = Math.max(maxY, p.y());
                minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
            }
            float plane = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 2 : 0;
            float[] mn = {minX, minY, minZ};
            float[] mx = {maxX, maxY, maxZ};
            int axis = d.getAxis().ordinal();
            boolean shapeOk = true;
            for (int i = 0; i < 3; i++) {
                if (i == axis) {
                    shapeOk &= mn[i] == plane && mx[i] == plane;
                } else {
                    shapeOk &= mn[i] == 0 && mx[i] == 2;
                }
            }
            check(shapeOk, "coarse quad spans exactly one 2x2 cell face " + e);
            check((e.cx | e.cy | e.cz) % 2 == 0, "cell origin even");
            int layer = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            for (int a = 0; a < 2; a++) {
                for (int b = 0; b < 2; b++) {
                    int[] o = new int[3];
                    o[axis] = layer;
                    o[axis == 0 ? 1 : 0] = a;
                    o[axis == 2 ? 1 : 2] = b;
                    r.faces.add(new Face(e.cx + o[0], e.cy + o[1], e.cz + o[2], d));
                }
            }
        }
        return r;
    }

    public static void main(String[] args) throws Exception {
        checkLodSchedule();
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        policyChecks();

        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        BlockState custom = Blocks.DIAMOND_ORE.defaultBlockState();
        BlockState inset = Blocks.COBBLESTONE.defaultBlockState();
        Map<BlockState, BlockStateModel> models = new HashMap<>();
        models.put(stone, new SingleVariant(cube(1, 0, ChunkSectionLayer.SOLID, false)));
        models.put(dirt, new SingleVariant(cube(2, 0, ChunkSectionLayer.SOLID, false)));
        models.put(grass, new SingleVariant(cube(3, 0, ChunkSectionLayer.SOLID, true)));
        models.put(glass, new SingleVariant(cube(4, 0, ChunkSectionLayer.CUTOUT, false)));
        models.put(leaves, new SingleVariant(cube(5, 0, ChunkSectionLayer.CUTOUT, false)));
        models.put(custom, new CustomModel(cube(6, 0, ChunkSectionLayer.SOLID, false)));
        models.put(inset, new SingleVariant(cube(7, 0.0625F, ChunkSectionLayer.SOLID, false)));
        BlockStateModelSet set = new BlockStateModelSet(models, new SingleVariant(cube(9, 0, ChunkSectionLayer.SOLID, false)));
        BlockColors colors = BlockColors.createDefault();
        check(stone.isSolidRender() && grass.isSolidRender() && custom.isSolidRender() && inset.isSolidRender(), "test blocks are solid");
        check(!glass.isSolidRender() && !leaves.isSolidRender(), "glass/leaves are not solid");

        // 1) Off / inside the full-detail radius: nothing merged.
        World flat = new World();
        for (int x = -16; x < 32; x++) {
            for (int z = -16; z < 32; z++) {
                for (int y = -16; y <= 7; y++) {
                    flat.set(x, y, z, y == 7 ? grass : y >= 5 ? dirt : stone);
                }
            }
        }
        TerrainLod.setForTest(0, 0, -1);
        Result off = build(flat, models, set, colors, true);
        check(off.coarse.isEmpty() && off.state == TerrainLod.NONE, "LOD off: no merging, state NONE");
        TerrainLod.setForTest(0, 0, 13);
        flat.stateReads = 0;
        Result near = build(flat, models, set, colors, true);
        check(flat.stateReads < 4096, "near eligible terrain avoids full section prefetch: " + flat.stateReads);
        System.out.println("near eligibility block reads: " + flat.stateReads + " (previous prefetch alone: 4096)");
        check(near.coarse.isEmpty() && near.state == TerrainLod.FULL, "inside radius: full detail, state FULL (candidates)");
        check(near.faces.equals(fullFaces(flat, models)), "inside radius: same faces as full detail");

        // 2) Flat terrain in the outer band (camera 14 chunks away).
        TerrainLod.setForTest(-14, 0, 13);
        Result fr = build(flat, models, set, colors, true);
        check(fr.state == TerrainLod.COARSE && fr.merged > 0, "outer band: merged, state COARSE");
        coverage("flat", flat, models, fr);
        cacheChecks(flat, models, set, colors, stone);
        // Top surface (y=7 grass) is in cell y 6..7 whose top layer is grass: top faces must use the grass texture (id 3).
        boolean grassTop = false;
        for (Emitted e : fr.coarse) {
            if (e.quad.direction() == Direction.UP) {
                float u = Float.intBitsToFloat((int) (e.quad.packedUV0() >>> 32));
                check(u == 3.0F, "flat top merged face uses grass texture, got u=" + u);
                grassTop = true;
                // tinted: green channel dominates after multiplying by the 0x30A040 biome tint
                int c = e.colors[0];
                check(((c >> 8) & 0xFF) > ((c >> 16) & 0xFF), "grass top is tinted");
            }
        }
        check(grassTop, "flat terrain produced merged grass tops");
        System.out.printf("flat surface: full %d quads -> lod %d fine + %d merged (%d cells)%n",
            fullQuadCount(flat, models), fr.fineQuads, fr.coarseQuads, fr.merged);

        // 3) Noisy terrain with caves, overhangs, glass, leaves, custom and inset models and random section borders.
        Random rnd = new Random(42);
        World noisy = new World();
        BlockState[] solids = {stone, stone, stone, dirt, custom, inset};
        for (int x = -16; x < 32; x++) {
            for (int z = -16; z < 32; z++) {
                int h = 8 + (int) Math.round(4 * Math.sin(x * 0.4) * Math.cos(z * 0.3)) + rnd.nextInt(2);
                for (int y = -16; y < 32; y++) {
                    BlockState s;
                    if (y > h) {
                        s = y == h + 1 && rnd.nextInt(9) == 0 ? leaves : Blocks.AIR.defaultBlockState();
                    } else if (y == h) {
                        s = grass;
                    } else if (rnd.nextInt(14) == 0) {
                        s = Blocks.AIR.defaultBlockState(); // cave voids
                    } else if (rnd.nextInt(30) == 0) {
                        s = glass;
                    } else {
                        s = solids[rnd.nextInt(solids.length)];
                    }
                    noisy.set(x, y, z, s);
                }
            }
        }
        // An overhang: a solid slab of stone over air.
        for (int x = 2; x < 10; x++) {
            for (int z = 2; z < 10; z++) {
                for (int y = 0; y < 4; y++) {
                    noisy.set(x, y, z, Blocks.AIR.defaultBlockState());
                }
                for (int y = 4; y < 6; y++) {
                    noisy.set(x, y, z, stone);
                }
            }
        }
        Result nr = build(noisy, models, set, colors, true);
        coverage("noisy", noisy, models, nr);
        for (long p : nr.covered) {
            BlockState s = noisy.at(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
            check(s == stone || s == dirt || s == grass, "only plain cube blocks are merged, got " + s);
        }
        System.out.printf("noisy terrain: full %d quads -> lod %d fine + %d merged (%d cells)%n",
            fullQuadCount(noisy, models), nr.fineQuads, nr.coarseQuads, nr.merged);

        // 4) Corner lighting, flat shading (no AO): each corner takes the light outside its own block.
        Result lit = build(flat, models, set, colors, false);
        BlockModelLighter lighter = new BlockModelLighter();
        int checkedCorners = 0;
        for (Emitted e : lit.coarse) {
            Direction d = e.quad.direction();
            int axis = d.getAxis().ordinal();
            int layer = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
            for (int v = 0; v < 4; v++) {
                Vector3fc p = e.quad.position(v);
                int[] o = new int[3];
                o[axis] = layer;
                for (int i = 0; i < 3; i++) {
                    if (i != axis) {
                        o[i] = p.get(i) > 1.0F ? 1 : 0;
                    }
                }
                BlockPos sub = new BlockPos(e.cx + o[0], e.cy + o[1], e.cz + o[2]);
                BlockState s = flat.getBlockState(sub);
                if (!Block.shouldRenderFace(s, flat.getBlockState(sub.relative(d)), d)) {
                    continue;
                }
                int expected = lighter.getLightCoords(s, flat, sub.relative(d));
                check(e.lights[v] == expected, "corner light matches the block outside that corner");
                checkedCorners++;
            }
        }
        check(checkedCorners > 0, "corner light checked");

        // 5) Per-thread state resets: a coarse build followed by an all-air section and an inside-radius build.
        World empty = new World();
        Result er = build(empty, models, set, colors, true);
        check(er.coarse.isEmpty() && er.covered.isEmpty() && er.state == TerrainLod.NONE, "empty section after a coarse build");
        TerrainLod.setForTest(0, 0, 13);
        Result again = build(noisy, models, set, colors, true);
        check(again.covered.isEmpty() && again.faces.equals(fullFaces(noisy, models)), "full detail again after coarse");

        // Resource packs may have a diamond or crossed quad with the same bounding box as a full face.
        // Those are not cube faces and must not be stretched into a coarse cell.
        for (boolean crossed : new boolean[]{false, true}) {
            Map<Direction, List<BakedQuad>> faces = new HashMap<>();
            for (Direction d : DIRS) {
                BakedQuad q = face(d, 12, 0, ChunkSectionLayer.SOLID, -1);
                Vector3fc[] v = {q.position0(), q.position1(), q.position2(), q.position3()};
                if (crossed) {
                    Vector3fc swap = v[1]; v[1] = v[2]; v[2] = swap;
                } else {
                    int a = d.getAxis() == Direction.Axis.X ? 1 : 0;
                    int b = d.getAxis() == Direction.Axis.Z ? 1 : 2;
                    float[][] corners = {{0,0.5F},{0.5F,1},{1,0.5F},{0.5F,0}};
                    for (int i=0;i<4;i++) {
                        Vector3f p = new Vector3f(v[i]);
                        p.setComponent(a, corners[i][0]); p.setComponent(b, corners[i][1]); v[i]=p;
                    }
                }
                faces.put(d, List.of(new BakedQuad(v[0],v[1],v[2],v[3],q.packedUV0(),q.packedUV1(),q.packedUV2(),q.packedUV3(),d,q.materialInfo())));
            }
            Map<BlockState, BlockStateModel> malformed = new HashMap<>(models);
            malformed.put(stone, new SingleVariant(new Part(faces, List.of())));
            World cubeWorld = new World();
            for (int x=0;x<2;x++) for (int y=0;y<2;y++) for (int z=0;z<2;z++) cubeWorld.set(x,y,z,stone);
            TerrainLod.setForTest(-14,0,13);
            Result rejected = build(cubeWorld, malformed, new BlockStateModelSet(malformed, malformed.get(stone)), colors, true);
            check(rejected.coarse.isEmpty(), (crossed ? "crossed" : "diamond") + " resource-pack faces stay full detail");
        }

        // 6) Section of only custom/inset models in the band: nothing merged, state NONE (never rebuilt for LOD again).
        World mod = new World();
        for (int x = -1; x < 17; x++) {
            for (int z = -1; z < 17; z++) {
                for (int y = -1; y < 8; y++) {
                    mod.set(x, y, z, (x + z) % 2 == 0 ? custom : inset);
                }
            }
        }
        TerrainLod.setForTest(-14, 0, 13);
        Result mr = build(mod, models, set, colors, true);
        check(mr.coarse.isEmpty() && mr.state == TerrainLod.NONE, "custom/inset models stay full detail, state NONE");
        TerrainLod.setForTest(0, 0, 13);
        Result nearRejected = build(mod, models, set, colors, true);
        check(nearRejected.coarse.isEmpty() && nearRejected.state == TerrainLod.NONE,
            "near ineligible models do not request an unnecessary coarse rebuild");
        check(nearRejected.faces.equals(fullFaces(mod, models)), "near model rejection preserves detailed faces");
        World vines = new World();
        for (int x = 0; x < 2; x++) for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) {
            vines.set(x, y, z, Blocks.VINE.defaultBlockState());
        }
        for (int camera : new int[]{0, -14}) {
            TerrainLod.setForTest(camera, 0, 13);
            Result vineResult = build(vines, models, set, colors, true);
            check(vineResult.covered.isEmpty() && vineResult.state == TerrainLod.NONE,
                "vines retain detailed geometry and do not enter solid-cell LOD");
        }

        // Binds the tags the new rules read; everything above ran with unbound tags, so leaves were not merged there.
        bindTestTags();
        leafChecks(models, colors);
        plantChecks(models, set, colors);
        waterChecks(set, colors);

        System.out.println(failures == 0 ? "ALL TERRAIN LOD CHECKS PASSED" : failures + " FAILURES");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- leaves, small plants and still water ----
    record WaterTop(int x, int y, int z, float height, int[] colors, int[] lights) {
    }

    static final class Special {
        byte state;
        int merged;
        final Set<Long> covered = new HashSet<>();
        final Set<Long> hidden = new HashSet<>();
        final List<Emitted> quads = new ArrayList<>();
        final List<WaterTop> tops = new ArrayList<>();
    }

    /** Like build(), for any block: records covered blocks, merged quads, merged water tops and hidden water tops. */
    static Special buildSpecial(World w, BlockStateModelSet set, BlockColors colors, FluidStateModelSet fluids) {
        Special r = new Special();
        CoarseMesher m = CoarseMesher.begin(w, 0, 0, 0, true, set, colors, fluids);
        VanillaEmitter emitter = VanillaEmitter.of((x, y, z, quad, inst) -> {
            int[] c = new int[4];
            int[] l = new int[4];
            for (int v = 0; v < 4; v++) {
                c[v] = inst.getColor(v);
                l[v] = inst.getLightCoords(v);
            }
            r.quads.add(new Emitted((int) x, (int) y, (int) z, quad, c, l));
        });
        CoarseMesher.WaterEmitter water = (model, x, y, z, height, c, l) -> r.tops.add(new WaterTop(x, y, z, height, c.clone(), l.clone()));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    if (w.at(x, y, z).isAir() || m == null) {
                        continue;
                    }
                    if (m.startsWaterTop(x, y, z)) {
                        m.emitWaterTop(x, y, z, water);
                    }
                    if (m.hidesWaterTop(new BlockPos(x, y, z))) {
                        r.hidden.add(BlockPos.asLong(x, y, z));
                    }
                    if (m.covers(x, y, z)) {
                        r.covered.add(BlockPos.asLong(x, y, z));
                        m.emitIfOrigin(x, y, z, emitter);
                    }
                }
            }
        }
        r.state = CoarseMesher.end();
        VanillaEmitter.end();
        r.merged = CoarseMesher.lastMergedCells();
        return r;
    }

    static List<Holder<Block>> holders(Block... blocks) {
        List<Holder<Block>> out = new ArrayList<>();
        for (Block b : blocks) {
            out.add(BuiltInRegistries.BLOCK.wrapAsHolder(b));
        }
        return out;
    }

    static void bindTestTags() {
        Map<TagKey<Block>, List<Holder<Block>>> tags = new HashMap<>();
        List<Holder<Block>> leaves = new ArrayList<>();
        BuiltInRegistries.BLOCK.stream().filter(b -> b instanceof LeavesBlock).forEach(b -> leaves.add(BuiltInRegistries.BLOCK.wrapAsHolder(b)));
        tags.put(BlockTags.LEAVES, leaves);
        tags.put(BlockTags.SMALL_FLOWERS, holders(Blocks.DANDELION, Blocks.POPPY));
        tags.put(BlockTags.SAPLINGS, holders(Blocks.OAK_SAPLING, Blocks.AZALEA));
        // The bootstrapped registry has no datapack tags: bind the test tags the way a world load would.
        ((MappedRegistry<Block>) BuiltInRegistries.BLOCK).prepareTagReload(new TagLoader.LoadResult<>(Registries.BLOCK, tags)).apply();
    }

    static void fill(World w, int x0, int y0, int z0, int x1, int y1, int z1, BlockState s) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    w.set(x, y, z, s);
                }
            }
        }
    }

    /** Directions of a merged cell with at least one visible sub-block side, by the rules in CoarseMesher's class doc. */
    static Set<Direction> expectedFaces(World w, Set<Long> covered, int cx, int cy, int cz) {
        Set<Direction> out = new HashSet<>();
        for (Direction d : DIRS) {
            for (int k = 0; k < 4; k++) {
                int layer = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
                int a = k >> 1;
                int b = k & 1;
                int x = cx + switch (d.getAxis()) { case X -> layer; default -> a; };
                int y = cy + switch (d.getAxis()) { case Y -> layer; case X -> a; case Z -> b; };
                int z = cz + switch (d.getAxis()) { case Z -> layer; default -> b; };
                BlockState self = w.at(x, y, z);
                BlockState n = w.at(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
                int nx = x + d.getStepX(), ny = y + d.getStepY(), nz = z + d.getStepZ();
                boolean mergedLeaf = n.is(BlockTags.LEAVES) && covered.contains(BlockPos.asLong(nx, ny, nz));
                boolean visible = Block.shouldRenderFace(self, n, d) && (self.isSolidRender() || !mergedLeaf);
                if (visible) {
                    out.add(d);
                    break;
                }
            }
        }
        return out;
    }

    static void leafChecks(Map<BlockState, BlockStateModel> models, BlockColors colors) {
        BlockState oak = Blocks.OAK_LEAVES.defaultBlockState();
        BlockState persistent = oak.setValue(LeavesBlock.PERSISTENT, true);
        BlockState wet = oak.setValue(LeavesBlock.WATERLOGGED, true);
        BlockState birch = Blocks.BIRCH_LEAVES.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        Map<Direction, List<BakedQuad>> faces = new HashMap<>();
        for (Direction d : DIRS) {
            faces.put(d, List.of(face(d, 5, 0, ChunkSectionLayer.CUTOUT, 0)));
        }
        BlockStateModelPart leafPart = new Part(faces, List.of());
        Map<BlockState, BlockStateModel> lm = new HashMap<>(models);
        for (BlockState s : new BlockState[]{oak, persistent, wet, birch}) {
            lm.put(s, new SingleVariant(leafPart));
        }
        BlockStateModelSet set = new BlockStateModelSet(lm, new SingleVariant(cube(9, 0, ChunkSectionLayer.SOLID, false)));
        check(oak.is(BlockTags.LEAVES) && birch.is(BlockTags.LEAVES) && !stone.is(BlockTags.LEAVES), "leaf tag bound for the test");
        check(!oak.isSolidRender() && wet.getFluidState().isSource(), "oak leaves are not solid; waterlogged ones hold water");
        check(leafPart.getQuads(Direction.UP).getFirst().materialInfo().layer() == ChunkSectionLayer.CUTOUT, "source leaf model is cutout");

        // A 6x6x6 leaf ball (mixed oak, persistent and birch) next to a 2x2x2 stone cell.
        TerrainLod.setForTest(-14, 0, 13);
        World ball = new World();
        for (int x = 4; x < 10; x++) {
            for (int y = 4; y < 10; y++) {
                for (int z = 4; z < 10; z++) {
                    ball.set(x, y, z, (x + y + z) % 3 == 0 ? persistent : (x + y + z) % 3 == 1 ? oak : birch);
                }
            }
        }
        fill(ball, 10, 4, 4, 11, 5, 5, stone);
        Special r = buildSpecial(ball, set, colors, null);
        check(r.state == TerrainLod.COARSE && r.merged == 28, "leaf ball: 27 leaf cells + 1 stone cell merge, got " + r.merged);
        check(r.covered.size() == 224, "leaf ball: 224 blocks covered, got " + r.covered.size());
        Map<String, Set<Direction>> byCell = new HashMap<>();
        int leafQuads = 0;
        for (Emitted e : r.quads) {
            byCell.computeIfAbsent(e.cx() + "," + e.cy() + "," + e.cz(), key -> new HashSet<>()).add(e.quad().direction());
            check(e.quad().materialInfo().layer() == ChunkSectionLayer.SOLID, "merged faces are drawn solid");
            if (e.cx() < 10) {
                leafQuads++;
                check(e.quad().materialInfo().tintIndex() == 0, "merged leaf faces keep their tint index");
                for (int v = 0; v < 4; v++) {
                    check(((e.colors()[v] >> 8) & 0xFF) > ((e.colors()[v] >> 16) & 0xFF), "merged leaf face is foliage tinted");
                }
            }
        }
        // 6 sides x 9 cells, minus the one east cell whose side lies against the stone cell (vanilla culls it; the stone draws its own).
        check(leafQuads == 53, "leaf ball shows only its outside, got " + leafQuads);
        for (int cx = 4; cx <= 10; cx += 2) {
            for (int cy = 4; cy <= 8; cy += 2) {
                for (int cz = 4; cz <= 8; cz += 2) {
                    if (cx == 10 && (cy != 4 || cz != 4)) {
                        continue;
                    }
                    Set<Direction> shown = byCell.getOrDefault(cx + "," + cy + "," + cz, Set.of());
                    check(shown.equals(expectedFaces(ball, r.covered, cx, cy, cz)), "cell " + cx + "," + cy + "," + cz + " shows " + shown);
                }
            }
        }
        check(!byCell.containsKey("6,6,6"), "the inner leaf cell emits nothing");
        check(byCell.get("4,4,4").size() == 3, "a corner leaf cell shows three sides");
        check(byCell.get("10,4,4").contains(Direction.WEST), "the stone cell keeps the face that touches the leaves");

        // A merged leaf cell next to a leaf cell that stays detailed keeps that side: the cutout holes would show it.
        World edge = new World();
        fill(edge, 4, 4, 4, 7, 5, 5, oak);
        edge.set(7, 5, 5, dirt);
        Special er2 = buildSpecial(edge, set, colors, null);
        check(er2.covered.contains(BlockPos.asLong(4, 4, 4)) && !er2.covered.contains(BlockPos.asLong(6, 4, 4)),
            "only the all-leaf cell merges");
        boolean eastSide = er2.quads.stream().anyMatch(e -> e.cx() == 4 && e.quad().direction() == Direction.EAST);
        check(eastSide, "merged leaf cell keeps its side against detailed leaves");

        // Near the camera nothing changes, but the section knows a coarse build would differ.
        TerrainLod.setForTest(0, 0, 13);
        Special near = buildSpecial(ball, set, colors, null);
        check(near.covered.isEmpty() && near.quads.isEmpty() && near.state == TerrainLod.FULL, "leaves in the full-detail radius stay detailed, state FULL");
        World leavesOnly = new World();
        fill(leavesOnly, 4, 4, 4, 7, 7, 7, oak);
        Special nearLeaves = buildSpecial(leavesOnly, set, colors, null);
        check(nearLeaves.state == TerrainLod.FULL && nearLeaves.covered.isEmpty(), "leaf-only near section requests a coarse rebuild");

        // Cells that are not all plain leaves stay detailed.
        TerrainLod.setForTest(-14, 0, 13);
        World mixed = new World();
        fill(mixed, 4, 4, 4, 5, 5, 5, oak);
        mixed.set(5, 5, 5, dirt);
        Special mx = buildSpecial(mixed, set, colors, null);
        check(mx.covered.isEmpty() && mx.state == TerrainLod.NONE, "a cell with one dirt block among leaves is not merged");
        World soaked = new World();
        fill(soaked, 4, 4, 4, 5, 5, 5, oak);
        soaked.set(4, 4, 4, wet);
        Special sk = buildSpecial(soaked, set, colors, null);
        check(sk.covered.isEmpty() && sk.state == TerrainLod.NONE, "a waterlogged leaf block keeps its cell detailed");
        System.out.println("leaves: ball " + leafQuads + " leaf quads (" + r.merged + " cells), mixed and waterlogged cells stay detailed");
    }

    static void plantChecks(Map<BlockState, BlockStateModel> models, BlockStateModelSet set, BlockColors colors) {
        TerrainLod.setForTest(-14, 0, 13);
        BlockState lowerGrass = Blocks.TALL_GRASS.defaultBlockState();
        BlockState upperGrass = lowerGrass.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
        BlockState lowerSun = Blocks.SUNFLOWER.defaultBlockState();
        BlockState upperSun = lowerSun.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER);
        BlockState[] hiddenSingles = {
            Blocks.SHORT_GRASS.defaultBlockState(), Blocks.FERN.defaultBlockState(), Blocks.DEAD_BUSH.defaultBlockState(),
            Blocks.DANDELION.defaultBlockState(), Blocks.POPPY.defaultBlockState(), Blocks.OAK_SAPLING.defaultBlockState(),
            Blocks.SEAGRASS.defaultBlockState(), Blocks.TALL_SEAGRASS.defaultBlockState(), lowerGrass, lowerSun
        };
        BlockState[] kept = {
            Blocks.AZALEA.defaultBlockState(), Blocks.WHEAT.defaultBlockState(), Blocks.SUGAR_CANE.defaultBlockState(),
            Blocks.VINE.defaultBlockState(), Blocks.KELP.defaultBlockState(), Blocks.CACTUS.defaultBlockState(),
            Blocks.RED_MUSHROOM.defaultBlockState(), Blocks.BAMBOO.defaultBlockState(), Blocks.CORNFLOWER.defaultBlockState(),
            Blocks.LILY_PAD.defaultBlockState()
        };
        World w = new World();
        fill(w, 0, 0, 0, 15, 3, 15, Blocks.STONE.defaultBlockState());
        for (int i = 0; i < hiddenSingles.length; i++) {
            w.set(1 + i, 4, 1, hiddenSingles[i]);
        }
        w.set(1 + 8, 5, 1, upperGrass);
        w.set(1 + 9, 5, 1, upperSun);
        for (int i = 0; i < kept.length; i++) {
            w.set(1 + i, 4, 4, kept[i]);
        }
        Special r = buildSpecial(w, set, colors, null);
        check(r.state == TerrainLod.COARSE, "plants and terrain: state COARSE");
        for (int i = 0; i < hiddenSingles.length; i++) {
            check(r.covered.contains(BlockPos.asLong(1 + i, 4, 1)), hiddenSingles[i] + " is hidden in the outer band");
        }
        check(r.covered.contains(BlockPos.asLong(9, 5, 1)) && r.covered.contains(BlockPos.asLong(10, 5, 1)), "upper halves of tall plants are hidden too");
        for (int i = 0; i < kept.length; i++) {
            check(!r.covered.contains(BlockPos.asLong(1 + i, 4, 4)), kept[i] + " keeps its normal geometry");
        }

        // Plants alone: a coarse build differs, a near build knows it would; crops alone change nothing.
        World lone = new World();
        lone.set(3, 3, 3, Blocks.POPPY.defaultBlockState());
        Special coarse = buildSpecial(lone, set, colors, null);
        check(coarse.state == TerrainLod.COARSE && coarse.covered.size() == 1 && coarse.quads.isEmpty(), "a lone flower is hidden and emits nothing");
        TerrainLod.setForTest(0, 0, 13);
        Special near = buildSpecial(lone, set, colors, null);
        check(near.state == TerrainLod.FULL && near.covered.isEmpty(), "a near section with a flower requests a coarse rebuild");
        World crops = new World();
        crops.set(3, 3, 3, Blocks.WHEAT.defaultBlockState());
        for (int camera : new int[]{0, -14}) {
            TerrainLod.setForTest(camera, 0, 13);
            Special c = buildSpecial(crops, set, colors, null);
            check(c.state == TerrainLod.NONE && c.covered.isEmpty(), "crops alone do not change the mesh, camera " + camera);
        }
        System.out.println("plants: " + hiddenSingles.length + " singles and 2 upper halves hidden, " + kept.length + " other plants untouched");
    }

    static void waterChecks(BlockStateModelSet set, BlockColors colors) {
        TerrainLod.setForTest(-14, 0, 13);
        FluidModel wm = new FluidModel(ChunkSectionLayer.TRANSLUCENT, null, null, null, null);
        FluidStateModelSet fluids = new FluidStateModelSet(Map.of(Fluids.WATER, wm, Fluids.FLOWING_WATER, wm), wm);
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        float height = water.getFluidState().getOwnHeight() - 0.001F;

        // A basin of two source layers inside stone walls, open air above.
        World basin = new World();
        fill(basin, 0, 0, 0, 15, 2, 15, stone);
        fill(basin, 1, 3, 1, 12, 4, 12, stone);
        fill(basin, 2, 3, 2, 11, 4, 11, water);
        Special r = buildSpecial(basin, set, colors, fluids);
        check(r.tops.size() == 25, "basin: 5x5 footprints merge, got " + r.tops.size());
        Set<Long> expectedHidden = new HashSet<>();
        for (int x = 2; x <= 11; x++) {
            for (int z = 2; z <= 11; z++) {
                expectedHidden.add(BlockPos.asLong(x, 4, z));
            }
        }
        check(r.hidden.equals(expectedHidden), "exactly the 100 top-layer water blocks hide their top, got " + r.hidden.size());
        for (WaterTop t : r.tops) {
            check(t.y() == 4 && t.x() % 2 == 0 && t.z() % 2 == 0 && t.x() >= 2 && t.x() <= 10 && t.z() >= 2 && t.z() <= 10, "merged top sits on an even footprint of the top layer " + t);
            check(t.height() == height, "merged top uses the source surface height");
            for (int k = 0; k < 4; k++) {
                BlockPos p = new BlockPos(t.x() + (k >> 1), t.y(), t.z() + (k & 1));
                check(t.lights()[k] == LightCoordsUtil.max(LightCoordsUtil.getLightCoords(basin, p), LightCoordsUtil.getLightCoords(basin, p.above())),
                    "water top corner light is what vanilla uses for the block at that corner");
                check(t.colors()[k] == ARGB.scaleRGB(-1, basin.cardinalLighting().up()), "water top color is the tint times the top shade");
            }
        }
        check(!r.covered.contains(BlockPos.asLong(5, 4, 5)), "water blocks are not covered: their sides keep the normal path");

        // Anything that is not four plain sources with a matching ring keeps the normal path.
        World flowing = copy(basin);
        flowing.set(6, 4, 6, water.setValue(LiquidBlock.LEVEL, 3));
        Special f = buildSpecial(flowing, set, colors, fluids);
        check(f.tops.size() == 21 && f.tops.stream().noneMatch(t -> t.x() >= 4 && t.x() <= 6 && t.z() >= 4 && t.z() <= 6),
            "flowing water splits its footprint and the footprints that see it, got " + f.tops.size());
        check(!f.hidden.contains(BlockPos.asLong(6, 4, 6)) && !f.hidden.contains(BlockPos.asLong(7, 4, 7)), "split footprints draw their own tops");
        World covered = copy(basin);
        covered.set(6, 5, 6, stone);
        check(buildSpecial(covered, set, colors, fluids).tops.size() == 24, "a block over one source keeps that footprint detailed");
        World logged = copy(basin);
        logged.set(6, 4, 6, Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true));
        check(buildSpecial(logged, set, colors, fluids).tops.size() == 24, "waterlogged blocks are never merged");
        World open = copy(basin);
        fill(open, 12, 3, 6, 12, 4, 6, Blocks.AIR.defaultBlockState());
        Special o = buildSpecial(open, set, colors, fluids);
        check(o.tops.size() == 23 && o.tops.stream().noneMatch(t -> t.x() == 10 && t.z() >= 4 && t.z() <= 6),
            "a footprint beside open air keeps its edge detailed, got " + o.tops.size());
        Special off = buildSpecial(basin, set, colors, null);
        check(off.tops.isEmpty() && off.hidden.isEmpty(), "without fluid models nothing is merged or hidden");

        // An ocean section: no solid cells, so only the water tops make the section differ.
        World ocean = new World();
        fill(ocean, -2, 4, -2, 17, 4, 17, water);
        Special coarse = buildSpecial(ocean, set, colors, fluids);
        check(coarse.state == TerrainLod.COARSE && coarse.tops.size() == 64 && coarse.merged == 0, "ocean section merges 64 tops, got " + coarse.tops.size());
        CoarseMesher m = CoarseMesher.begin(ocean, 0, 0, 0, true, set, colors, fluids);
        check(m != null && !m.hidesWaterTop(new BlockPos(18, 4, 2)) && !m.hidesWaterTop(new BlockPos(2, 5, 2)) && m.hidesWaterTop(new BlockPos(2, 4, 2)),
            "hidden tops are limited to the section being built");
        CoarseMesher.end();
        TerrainLod.setForTest(0, 0, 13);
        Special near = buildSpecial(ocean, set, colors, fluids);
        check(near.state == TerrainLod.FULL && near.tops.isEmpty() && near.hidden.isEmpty(), "near ocean section requests a coarse rebuild and stays detailed");
        check(buildSpecial(ocean, set, colors, null).state == TerrainLod.NONE, "near ocean section without fluid models has nothing to simplify");
        System.out.println("water: basin " + r.tops.size() + " merged tops, ocean " + coarse.tops.size() + " tops");
    }

    static World copy(World source) {
        World out = new World();
        out.blocks.putAll(source.blocks);
        return out;
    }

    static void checkLodSchedule() {
        var schedule = new io.github.imcinq.puremetal.lod.LodScanSchedule();
        schedule.meshArrived();
        check(schedule.shouldScan(true, true), "movement scans immediately");
        int stationary = 0;
        for (int i = 0; i < 1000; i++) if (schedule.shouldScan(false, true)) stationary++;
        check(stationary == 0, "unchanged stationary meshes avoid 50 periodic scans");
        for (int i = 0; i < 19; i++) check(!schedule.shouldScan(false, true), "idle periodic phase retained");
        schedule.meshArrived();
        check(schedule.shouldScan(false, true), "newly completed mesh uses existing periodic phase");
        schedule.continueNextFrame();
        check(schedule.shouldScan(false, true), "rebuild budget exhaustion continues next frame");
        check(!schedule.shouldScan(false, true), "completed scan does not keep scanning");
        check(schedule.shouldScan(true, true), "new camera policy rescans unchanged meshes immediately");
        check(!schedule.shouldScan(true, false), "empty manager skips scans");
        schedule.meshArrived();
        check(schedule.shouldScan(true, true), "world/section arrival resumes scanning");
    }

    static int fullQuadCount(World w, Map<BlockState, BlockStateModel> models) {
        int n = 0;
        for (Face f : fullFaces(w, models)) {
            n += w.at(f.x, f.y, f.z).is(Blocks.GRASS_BLOCK) && f.d.getAxis() != Direction.Axis.Y ? 2 : 1;
        }
        return n;
    }

    /** Every visible face is covered; extra merged faces lie against an opaque full block (never visible). */
    static void coverage(String name, World w, Map<BlockState, BlockStateModel> models, Result r) {
        Set<Face> full = fullFaces(w, models);
        int missing = 0;
        for (Face f : full) {
            if (!r.faces.contains(f)) {
                missing++;
            }
        }
        check(missing == 0, name + ": " + missing + " visible faces missing (holes)");
        int visibleExtra = 0;
        for (Face f : r.faces) {
            if (!full.contains(f)) {
                BlockState n = w.at(f.x + f.d.getStepX(), f.y + f.d.getStepY(), f.z + f.d.getStepZ());
                if (!n.isSolidRender()) {
                    visibleExtra++;
                }
            }
        }
        check(visibleExtra == 0, name + ": " + visibleExtra + " extra faces would be visible");
    }

    static void cacheChecks(World world, Map<BlockState, BlockStateModel> models, BlockStateModelSet set,
                            BlockColors colors, BlockState stone) throws Exception {
        var local = CoarseMesher.class.getDeclaredField("LOCAL");
        local.setAccessible(true);
        Object mesher = ((ThreadLocal<?>) local.get(null)).get();
        var validate = CoarseMesher.class.getDeclaredMethod("modelEligible", BlockState.class, BlockPos.class);
        validate.setAccessible(true);
        CoarseMesher.begin(world, 0, 0, 0, true, set, colors);
        check((boolean) validate.invoke(mesher, stone, BlockPos.ZERO), "fixed cube is eligible");
        int reads = quadReads;
        check((boolean) validate.invoke(mesher, stone, new BlockPos(1, 2, 3)), "cached cube remains eligible");
        check(quadReads == reads, "fixed cube validation reuses cached result");
        CoarseMesher.end();

        BlockStateModelPart cube = cube(1, 0, ChunkSectionLayer.SOLID, false);
        BlockStateModelPart inset = cube(1, 0.0625F, ChunkSectionLayer.SOLID, false);
        BlockStateModel alternating = new SingleVariant(cube) {
            int calls;
            @Override public void collectParts(RandomSource random, List<BlockStateModelPart> output) {
                output.add((calls++ & 1) == 0 ? cube : inset);
            }
        };
        Map<BlockState, BlockStateModel> changed = new HashMap<>(models);
        changed.put(stone, alternating);
        var changedSet = new BlockStateModelSet(changed, alternating);
        CoarseMesher.begin(world, 0, 0, 0, true, changedSet, colors);
        boolean first = (boolean) validate.invoke(mesher, stone, BlockPos.ZERO);
        boolean second = (boolean) validate.invoke(mesher, stone, new BlockPos(1, 2, 3));
        check(first != second, "variant subclasses are validated every time");
        CoarseMesher.end();
        changed.put(stone, new SingleVariant(inset));
        changedSet = new BlockStateModelSet(changed, changed.get(stone));
        CoarseMesher.begin(world, 0, 0, 0, true, changedSet, colors);
        check(!(boolean) validate.invoke(mesher, stone, BlockPos.ZERO), "model reload discards cube eligibility");
        reads = quadReads;
        validate.invoke(mesher, stone, BlockPos.ZERO);
        check(quadReads == reads, "ineligible fixed models are also cached");
        CoarseMesher.end();

        BakedQuad source = cube.getQuads(Direction.UP).getFirst();
        List<BakedQuad> emitted = new ArrayList<>();
        var emitter = VanillaEmitter.of((x, y, z, q, inst) -> emitted.add(q));
        int[] values = {1, 2, 3, 4};
        emitter.quad(source, 0, 0, 0, values, values);
        emitter.quad(source, 2, 0, 0, values, values);
        check(emitted.get(0) == emitted.get(1), "same section reuses scaled quad");
        VanillaEmitter.end();
        emitter = VanillaEmitter.of((x, y, z, q, inst) -> emitted.add(q));
        emitter.quad(source, 0, 0, 0, values, values);
        check(emitted.get(0) != emitted.get(2), "scaled cache is released between sections");
        VanillaEmitter.end();
    }

    static void policyChecks() {
        int[][] table = {{8, -1}, {12, -1}, {15, -1}, {16, 13}, {20, 16}, {24, 20}, {32, 26}};
        for (int[] row : table) {
            check(TerrainLod.fullRadius(row[0], true, 80) == row[1], "fullRadius RD" + row[0] + " -> " + row[1]);
        }
        check(TerrainLod.fullRadius(32, false, 80) == -1, "disabled -> no LOD");
        check(TerrainLod.fullRadius(16, true, 95) == -1, "95% of 16 rounds up to RD: no band");
        check(TerrainLod.wantsCoarse(13, 100_000, 100_000), "large teleport distances do not overflow");
        check(TerrainLod.needsRebuild(TerrainLod.COARSE, -1, 14, 0), "coarse mesh repairs after disabling LOD");
        check(TerrainLod.needsRebuild(TerrainLod.COARSE, 20, 14, 0), "increasing full radius restores detailed mesh");
        // Walk the camera back and forth across chunk borders around a section 14 chunks away (RD16, R=13).
        int r = 13;
        byte built = TerrainLod.wantsCoarse(r, 14, 0) ? TerrainLod.COARSE : TerrainLod.FULL;
        int rebuilds = 0;
        int[] path = {0, 1, 0, 1, 0, 1, 0, -1, 0, -1, 0, -1, -2, -1, 0, 1};
        for (int cam : path) {
            int dx = 14 - cam;
            if (TerrainLod.needsRebuild(built, r, dx, 0)) {
                built = TerrainLod.wantsCoarse(r, dx, 0) ? TerrainLod.COARSE : TerrainLod.FULL;
                rebuilds++;
            }
            check(!(built == TerrainLod.COARSE && dx * dx <= r * r), "never coarse inside the full-detail radius");
        }
        check(rebuilds <= 4, "hysteresis bounds level switching, rebuilds=" + rebuilds);
        System.out.println("policy: RD12->" + TerrainLod.fullRadius(12, true, 80) + " RD16->" + TerrainLod.fullRadius(16, true, 80)
            + " RD24->" + TerrainLod.fullRadius(24, true, 80) + " RD32->" + TerrainLod.fullRadius(32, true, 80)
            + "; back-and-forth walk rebuilds=" + rebuilds);
    }
}
