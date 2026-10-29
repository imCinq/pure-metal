package io.github.imcinq.puremetal.lod;

import com.mojang.blaze3d.vertex.QuadInstance;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2BooleanOpenHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.ARGB;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * Coarse meshing for the outer LOD band, shared by the vanilla and Sodium section meshers.
 *
 * <p>A section is split into 2x2x2 cells. A cell is merged only when all eight blocks are opaque full cubes with plain
 * vanilla cube models (no block entity, fluid, random offset, translucency or extra geometry). Each exposed side of a
 * merged cell becomes one quad, twice the block size, using the most common block's face (texture stretched 2x). The
 * merged cell has exactly the outline of its eight blocks, so caves, overhangs and section borders keep their shape.
 * Corner light and ambient occlusion are copied from what vanilla computes for the block at that corner.
 *
 * <p>Three more simplifications use the same gate:
 * <ul>
 * <li>Leaves: a cell whose eight blocks are all plain full-cube leaf blocks (BlockTags.LEAVES, not waterlogged) merges
 * the same way but is drawn in the solid layer with the foliage tint sampled once, like vanilla's Fast leaves. Leaf
 * sides against another leaf block are not emitted, since leaves never hide the neighbor's own face; sides against
 * solid blocks keep the vanilla culling rule, and solid cells next to leaves keep their faces. A leaf cell hidden on
 * every side is still merged, so the inside of a canopy draws nothing.
 * <li>Small plants: short and tall grass, ferns, small and tall flowers, dead bushes, saplings and seagrass without
 * collision are not drawn at all (a waterlogged plant still draws its water). Crops, vines, kelp, bamboo, cactus,
 * mushrooms and anything with collision keep the normal path.
 * <li>Still water: when the four water source blocks of a 2x2 column footprint at one height all have air above, and the
 * twelve blocks around the footprint are water sources of the same flat height or solid blocks, their four top faces
 * become one 2x2 translucent quad and the fluid renderer skips those four tops. Sides, bottoms, flowing water,
 * waterlogged blocks and any footprint touching other neighbors keep the normal path.
 * </ul>
 * Every other block (partial cells, glass, custom or modded models) is meshed by the normal path at full detail.
 *
 * <p>One instance per mesher thread; begin/end bracket one section build.
 */
public final class CoarseMesher {
    /** Receives a merged face: the source quad, the cell origin (section-relative) and per-vertex color/light. */
    public interface Emitter {
        void quad(BakedQuad quad, int cellX, int cellY, int cellZ, int[] colors, int[] lights);
    }

    /**
     * Receives a merged water top: the 2x2 quad at section-relative block (x, y, z) at the given surface height, with
     * per-corner color and light indexed (a, b) = (east, south) as 2a + b.
     */
    public interface WaterEmitter {
        void waterTop(FluidModel model, int x, int y, int z, float height, int[] colors, int[] lights);
    }

    private static final ThreadLocal<CoarseMesher> LOCAL = ThreadLocal.withInitial(CoarseMesher::new);
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final float EPS = 1.0E-4F;
    /** Bit 6 of a merged cell's face mask: the cell is leaves (drawn solid), not stone-like blocks. */
    private static final int LEAF_FLAG = 1 << 6;
    private static final int NOT_MERGEABLE = 0;
    private static final int SOLID_KIND = 1;
    private static final int LEAF_KIND = 2;
    private static final float WATER_TOP_INSET = 0.001F;

    private final BlockState[] states = new BlockState[4096];
    private final long[] skip = new long[64];
    private final long[] plants = new long[64];
    // One bit per 2x2 column footprint and height, see waterIndex().
    private final long[] waterTops = new long[16];
    private final byte[] cellFaces = new byte[512];
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos scratch2 = new BlockPos.MutableBlockPos();
    // Kept separate from neighbor-query scratch: lighting needs the corner position throughout the call.
    private final BlockPos.MutableBlockPos cornerPos = new BlockPos.MutableBlockPos();
    private final RandomSource random = RandomSource.create(0L);
    private final List<BlockStateModelPart> parts = new ObjectArrayList<>();
    private final BlockModelLighter lighter = new BlockModelLighter();
    private final QuadInstance lightOut = new QuadInstance();
    private final Reference2BooleanOpenHashMap<BlockState> stateEligible = new Reference2BooleanOpenHashMap<>();
    private final Reference2BooleanOpenHashMap<BlockState> modelEligible = new Reference2BooleanOpenHashMap<>();
    private final IdentityHashMap<BakedQuad, BakedQuad> opaqueQuads = new IdentityHashMap<>();
    private final int[] cornerColor = new int[4];
    private final int[] cornerLight = new int[4];
    private final boolean[] cornerSet = new boolean[4];
    private final int[] vertexColor = new int[4];
    private final int[] vertexLight = new int[4];
    private final int[] subIndex = new int[4];
    private final boolean[] subExposed = new boolean[4];

    private @Nullable BlockStateModelSet cachedModels;
    private BlockAndTintGetter level;
    private BlockStateModelSet models;
    private BlockColors colors;
    private @Nullable FluidStateModelSet fluidModels;
    private boolean ambientOcclusion;
    private int ox;
    private int oy;
    private int oz;
    private boolean active;
    private byte result;
    private int mergedCells;
    private int hiddenPlants;
    private int waterTopCount;

    private CoarseMesher() {
    }

    /** Starts a section build without water merging (see the overload). */
    public static @Nullable CoarseMesher begin(
        final BlockAndTintGetter level, final int sectionX, final int sectionY, final int sectionZ,
        final boolean ambientOcclusion, final BlockStateModelSet models, final BlockColors colors
    ) {
        return begin(level, sectionX, sectionY, sectionZ, ambientOcclusion, models, colors, null);
    }

    /**
     * Starts a section build on this thread. Returns the mesher if anything is simplified, else null. Water tops are
     * merged only with fluid models; pass null when the fluid renderer cannot skip the merged tops.
     */
    public static @Nullable CoarseMesher begin(
        final BlockAndTintGetter level, final int sectionX, final int sectionY, final int sectionZ,
        final boolean ambientOcclusion, final BlockStateModelSet models, final BlockColors colors,
        final @Nullable FluidStateModelSet fluidModels
    ) {
        CoarseMesher m = LOCAL.get();
        m.active = false;
        m.result = TerrainLod.NONE;
        m.mergedCells = 0;
        m.hiddenPlants = 0;
        m.waterTopCount = 0;
        if (TerrainLod.radius() < 0) {
            return null;
        }
        boolean coarse = TerrainLod.coarseAt(sectionX, sectionZ);
        m.fluidModels = fluidModels;
        m.prepare(level, sectionX << 4, sectionY << 4, sectionZ << 4, coarse, ambientOcclusion, models, colors);
        return m.active ? m : null;
    }

    /** The mesher of the section being built on this thread, or null if nothing is merged. */
    public static @Nullable CoarseMesher current() {
        CoarseMesher m = LOCAL.get();
        return m.active ? m : null;
    }

    /** Ends the section build; returns its built state (TerrainLod.NONE/FULL/COARSE). */
    public static byte end() {
        CoarseMesher m = LOCAL.get();
        m.active = false;
        m.level = null;
        m.fluidModels = null;
        return m.result;
    }

    /** Number of merged cells in the last build on this thread (tests and debugging). */
    public static int lastMergedCells() {
        return LOCAL.get().mergedCells;
    }

    /** Must the normal mesher skip this block's model (section-relative)? True for merged cells and hidden plants. */
    public boolean covers(final int x, final int y, final int z) {
        int i = index(x, y, z);
        return ((this.skip[i >>> 6] | this.plants[i >>> 6]) & (1L << i)) != 0;
    }

    /** Called for every covered block; emits the merged faces once, at the cell's lowest corner. */
    public void emitIfOrigin(final int x, final int y, final int z, final Emitter out) {
        if (((x | y | z) & 1) == 0) {
            int i = index(x, y, z);
            if ((this.skip[i >>> 6] & (1L << i)) != 0) {
                this.emitCell(x, y, z, out);
            }
        }
    }

    /** Does a merged water top start at this block, i.e. is it the north-west block of its footprint? */
    public boolean startsWaterTop(final int x, final int y, final int z) {
        return ((x | z) & 1) == 0 && this.hasWaterTop(x, y, z);
    }

    /** Must the fluid renderer skip the top face of the water block at this world position? */
    public boolean hidesWaterTop(final BlockPos pos) {
        int x = pos.getX() - this.ox;
        int y = pos.getY() - this.oy;
        int z = pos.getZ() - this.oz;
        return (x | y | z) >= 0 && x < 16 && y < 16 && z < 16 && this.hasWaterTop(x, y, z);
    }

    private boolean hasWaterTop(final int x, final int y, final int z) {
        int i = waterIndex(x, y, z);
        return (this.waterTops[i >>> 6] & (1L << i)) != 0;
    }

    private static int waterIndex(final int x, final int y, final int z) {
        return (y << 6) | ((z >> 1) << 3) | (x >> 1);
    }

    private static int index(final int x, final int y, final int z) {
        return (y << 8) | (z << 4) | x;
    }

    private void prepare(
        final BlockAndTintGetter level, final int ox, final int oy, final int oz, final boolean coarse,
        final boolean ambientOcclusion, final BlockStateModelSet models, final BlockColors colors
    ) {
        this.level = level;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.models = models;
        this.colors = colors;
        this.ambientOcclusion = ambientOcclusion;
        if (this.cachedModels != models) {
            this.stateEligible.clear();
            this.modelEligible.clear();
            this.opaqueQuads.clear();
            this.cachedModels = models;
        }
        java.util.Arrays.fill(this.skip, 0L);
        java.util.Arrays.fill(this.plants, 0L);
        java.util.Arrays.fill(this.waterTops, 0L);
        if (coarse) {
            boolean anyBlock = false;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockPos pos = this.scratch.set(ox + x, oy + y, oz + z);
                        BlockState s = level.getBlockState(pos);
                        int i = index(x, y, z);
                        this.states[i] = s;
                        if (!s.isAir()) {
                            anyBlock = true;
                            if (this.isHiddenPlant(s, pos)) {
                                this.plants[i >>> 6] |= 1L << i;
                                this.hiddenPlants++;
                            }
                        }
                    }
                }
            }
            if (!anyBlock) {
                return;
            }
        } else {
            // Near sections only need the first eligible exposed cell. Fetch states lazily, retaining
            // a section-local snapshot for repeated neighbor checks without pre-reading all 4096 blocks.
            java.util.Arrays.fill(this.states, null);
        }
        for (int cy = 0; cy < 16; cy += 2) {
            for (int cz = 0; cz < 16; cz += 2) {
                for (int cx = 0; cx < 16; cx += 2) {
                    int kind = this.cellKind(cx, cy, cz);
                    if (kind == NOT_MERGEABLE) {
                        continue;
                    }
                    int faces = this.exposedFaces(cx, cy, cz);
                    // Hidden stone draws nothing either way, but hidden leaves would still draw their inner sides.
                    if ((faces == 0 && kind == SOLID_KIND) || !this.cellModelsEligible(cx, cy, cz)) {
                        continue;
                    }
                    if (!coarse) {
                        // A full-detail build only needs to know a coarse build would differ.
                        this.result = TerrainLod.FULL;
                        return;
                    }
                    this.cellFaces[(cy << 5) | (cz << 2) | (cx >> 1)] = (byte) (faces | (kind == LEAF_KIND ? LEAF_FLAG : 0));
                    for (int i = 0; i < 8; i++) {
                        int b = index(cx + (i & 1), cy + (i >> 2), cz + ((i >> 1) & 1));
                        this.skip[b >>> 6] |= 1L << b;
                    }
                    this.mergedCells++;
                }
            }
        }
        if (!coarse) {
            // No cell would merge, but plants or water tops may still differ in a coarse build.
            if (this.anyCoarseDifference()) {
                this.result = TerrainLod.FULL;
            }
            return;
        }
        this.scanWaterTops();
        // A coarse attempt that changed nothing equals the full mesh: NONE, so it is never rebuilt for LOD again.
        this.active = this.mergedCells > 0 || this.hiddenPlants > 0 || this.waterTopCount > 0;
        this.result = this.active ? TerrainLod.COARSE : TerrainLod.NONE;
    }

    private BlockState stateAt(final int x, final int y, final int z) {
        if ((x | y | z) >= 0 && x < 16 && y < 16 && z < 16) {
            int i = index(x, y, z);
            BlockState state = this.states[i];
            if (state == null) {
                state = this.level.getBlockState(this.scratch2.set(this.ox + x, this.oy + y, this.oz + z));
                this.states[i] = state;
            }
            return state;
        }
        return this.level.getBlockState(this.scratch2.set(this.ox + x, this.oy + y, this.oz + z));
    }

    /** Which kind of cell the eight blocks form: all plain solid cubes, all plain leaf cubes, or neither. */
    private int cellKind(final int cx, final int cy, final int cz) {
        boolean leaf = !this.stateAt(cx, cy, cz).isSolidRender();
        for (int i = 0; i < 8; i++) {
            BlockState s = this.stateAt(cx + (i & 1), cy + (i >> 2), cz + ((i >> 1) & 1));
            if (leaf ? !this.isLeafEligible(s) : !s.isSolidRender() || !this.isStateEligible(s)) {
                return NOT_MERGEABLE;
            }
        }
        return leaf ? LEAF_KIND : SOLID_KIND;
    }

    /** Leaf blocks get the same structural checks as solid ones; tags can change with the world, so they are not cached. */
    private boolean isLeafEligible(final BlockState s) {
        return this.isStateEligible(s) && !s.isSolidRender() && s.is(BlockTags.LEAVES);
    }

    private boolean isStateEligible(final BlockState s) {
        if (this.stateEligible.containsKey(s)) {
            return this.stateEligible.getBoolean(s);
        }
        boolean ok = s.getRenderShape() == RenderShape.MODEL
            && !s.hasBlockEntity()
            && s.getFluidState().isEmpty()
            && !s.hasOffsetFunction()
            && isVanillaModel(this.models.get(s));
        // A fixed vanilla model cannot become mergeable at a different position. Reject it once
        // per model set rather than testing eight copies in every exposed near/far cell.
        if (ok && this.models.get(s).getClass() == SingleVariant.class) {
            ok = this.modelEligible(s, BlockPos.ZERO);
        }
        this.stateEligible.put(s, ok);
        return ok;
    }

    /** Modded model classes may emit custom geometry (connected textures, emissive layers); keep those at full detail. */
    private static boolean isVanillaModel(final BlockStateModel model) {
        return model.getClass().getName().startsWith("net.minecraft.");
    }

    /** Bit per direction with at least one of the cell's four blocks on that side visible. */
    private int exposedFaces(final int cx, final int cy, final int cz) {
        int faces = 0;
        for (Direction d : DIRECTIONS) {
            for (int k = 0; k < 4; k++) {
                if (this.subExposed(cx, cy, cz, d, k)) {
                    faces |= 1 << d.ordinal();
                    break;
                }
            }
        }
        return faces;
    }

    /** Section-relative position of sub-block k (0..3) on side d of the cell, packed as index(). */
    private static int subBlock(final int cx, final int cy, final int cz, final Direction d, final int k) {
        int layer = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 : 0;
        int a = k >> 1;
        int b = k & 1;
        return switch (d.getAxis()) {
            case X -> index(cx + layer, cy + a, cz + b);
            case Y -> index(cx + a, cy + layer, cz + b);
            case Z -> index(cx + a, cy + b, cz + layer);
        };
    }

    private boolean subExposed(final int cx, final int cy, final int cz, final Direction d, final int k) {
        int i = subBlock(cx, cy, cz, d, k);
        int x = i & 15;
        int y = i >> 8;
        int z = (i >> 4) & 15;
        BlockState neighbor = this.stateAt(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
        BlockState self = this.states[i];
        // Two merged leaf cells are both opaque, so the side between them is never seen. Against any other leaf
        // (full detail, or in the next section) the side stays: its cutout holes would show the merged cell's inside.
        if (!self.isSolidRender() && this.inMergedLeafCell(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) {
            return false;
        }
        return Block.shouldRenderFace(self, neighbor, d);
    }

    /** Is this section-relative block part of a leaf cell that merges (leaf cells merge even when hidden)? */
    private boolean inMergedLeafCell(final int x, final int y, final int z) {
        if ((x | y | z) < 0 || x >= 16 || y >= 16 || z >= 16) {
            return false;
        }
        int cx = x & ~1;
        int cy = y & ~1;
        int cz = z & ~1;
        return this.cellKind(cx, cy, cz) == LEAF_KIND && this.cellModelsEligible(cx, cy, cz);
    }

    private boolean cellModelsEligible(final int cx, final int cy, final int cz) {
        for (int i = 0; i < 8; i++) {
            int x = cx + (i & 1);
            int y = cy + (i >> 2);
            int z = cz + ((i >> 1) & 1);
            if (!this.modelEligible(this.states[index(x, y, z)], this.scratch.set(this.ox + x, this.oy + y, this.oz + z))) {
                return false;
            }
        }
        return true;
    }

    /** Every part has only culled, non-translucent quads that each cover one whole face of the unit cube. */
    private boolean modelEligible(final BlockState state, final BlockPos pos) {
        // Only this exact vanilla model class has position-independent parts. Weighted/multipart/custom
        // models must still be checked at each position, including subclasses with changed behavior.
        boolean cacheable = this.models.get(state).getClass() == SingleVariant.class;
        if (cacheable && this.modelEligible.containsKey(state)) {
            return this.modelEligible.getBoolean(state);
        }
        boolean eligible = this.validateModel(state, pos);
        if (cacheable) {
            this.modelEligible.put(state, eligible);
        }
        return eligible;
    }

    private boolean validateModel(final BlockState state, final BlockPos pos) {
        this.collectParts(state, pos);
        try {
            if (this.parts.isEmpty()) {
                return false;
            }
            for (Direction d : DIRECTIONS) {
                boolean any = false;
                for (int partIndex = 0; partIndex < this.parts.size(); partIndex++) {
                    List<BakedQuad> quads = this.parts.get(partIndex).getQuads(d);
                    for (int quadIndex = 0; quadIndex < quads.size(); quadIndex++) {
                        BakedQuad q = quads.get(quadIndex);
                        if (q.direction() != d || q.materialInfo().layer().translucent() || !isFullFace(q, d)) {
                            return false;
                        }
                        any = true;
                    }
                }
                if (!any) {
                    return false;
                }
            }
            for (int partIndex = 0; partIndex < this.parts.size(); partIndex++) {
                if (!this.parts.get(partIndex).getQuads(null).isEmpty()) {
                    return false;
                }
            }
            return true;
        } finally {
            this.parts.clear();
        }
    }

    private void collectParts(final BlockState state, final BlockPos pos) {
        this.parts.clear();
        this.random.setSeed(state.getSeed(pos));
        this.models.get(state).collectParts(this.random, this.parts);
    }

    static boolean isFullFace(final BakedQuad q, final Direction d) {
        int axis = d.getAxis().ordinal();
        int t1 = axis == 0 ? 1 : 0;
        int t2 = axis == 2 ? 1 : 2;
        float plane = d.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0F : 0.0F;
        float min1 = Float.MAX_VALUE;
        float max1 = -Float.MAX_VALUE;
        float min2 = Float.MAX_VALUE;
        float max2 = -Float.MAX_VALUE;
        int corners = 0, firstCorner = -1, previousCorner = -1;
        for (int v = 0; v < 4; v++) {
            Vector3fc p = q.position(v);
            if (!(Math.abs(p.get(axis) - plane) < EPS)) {
                return false;
            }
            float a = p.get(t1), b = p.get(t2);
            if ((!nearEndpoint(a)) || (!nearEndpoint(b))) return false;
            int corner = (a > 0.5F ? 2 : 0) | (b > 0.5F ? 1 : 0);
            if (previousCorner != -1 && (previousCorner ^ corner) == 3) return false;
            if (firstCorner == -1) firstCorner = corner;
            previousCorner = corner;
            int bit = 1 << corner;
            if ((corners & bit) != 0) return false;
            corners |= bit;
            min1 = Math.min(min1, p.get(t1));
            max1 = Math.max(max1, p.get(t1));
            min2 = Math.min(min2, p.get(t2));
            max2 = Math.max(max2, p.get(t2));
        }
        return corners == 15 && (firstCorner ^ previousCorner) != 3 && Math.abs(min1) < EPS && Math.abs(max1 - 1.0F) < EPS && Math.abs(min2) < EPS && Math.abs(max2 - 1.0F) < EPS;
    }

    private static boolean nearEndpoint(float value) {
        return Math.abs(value) < EPS || Math.abs(value - 1.0F) < EPS;
    }

    private void emitCell(final int cx, final int cy, final int cz, final Emitter out) {
        int faces = this.cellFaces[(cy << 5) | (cz << 2) | (cx >> 1)];
        for (Direction d : DIRECTIONS) {
            if ((faces & (1 << d.ordinal())) != 0) {
                this.emitFace(cx, cy, cz, d, (faces & LEAF_FLAG) != 0, out);
            }
        }
    }

    private void emitFace(final int cx, final int cy, final int cz, final Direction d, final boolean leaf, final Emitter out) {
        int axis = d.getAxis().ordinal();
        int t1 = axis == 0 ? 1 : 0;
        int t2 = axis == 2 ? 1 : 2;
        for (int k = 0; k < 4; k++) {
            this.subIndex[k] = subBlock(cx, cy, cz, d, k);
            this.subExposed[k] = this.subExposed(cx, cy, cz, d, k);
            this.cornerSet[k] = false;
        }
        // Corner k = (a, b) of the merged face is corner (a, b) of sub-block k's own face: light it exactly like vanilla.
        for (int k = 0; k < 4; k++) {
            if (this.subExposed[k]) {
                this.lightCorner(k, d, t1, t2);
            }
        }
        for (int k = 0; k < 4; k++) {
            if (!this.cornerSet[k]) {
                int from = this.cornerSet[k ^ 2] ? k ^ 2 : this.cornerSet[k ^ 1] ? k ^ 1 : k ^ 3;
                this.cornerColor[k] = this.cornerColor[from];
                this.cornerLight[k] = this.cornerLight[from];
            }
        }

        // The face shows the most common block on that side; ties go to the upper block (grass over dirt).
        int rep = 0;
        int repCount = 0;
        for (int k = 0; k < 4; k++) {
            BlockState s = this.states[this.subIndex[k]];
            int count = 0;
            for (int j = 0; j < 4; j++) {
                if (this.states[this.subIndex[j]] == s) {
                    count++;
                }
            }
            if (count > repCount || (count == repCount && (this.subIndex[k] >> 8) > (this.subIndex[rep] >> 8))) {
                rep = k;
                repCount = count;
            }
        }
        int ri = this.subIndex[rep];
        BlockState state = this.states[ri];
        BlockPos pos = new BlockPos(this.ox + (ri & 15), this.oy + (ri >> 8), this.oz + ((ri >> 4) & 15));
        // A leaf cell takes its tint from one fixed block, so all of its faces match across biome borders and mixed leaves.
        BlockState tintState = leaf ? this.states[index(cx, cy, cz)] : state;
        BlockPos tintPos = leaf ? new BlockPos(this.ox + cx, this.oy + cy, this.oz + cz) : pos;
        this.collectParts(state, pos);
        try {
            List<BlockTintSource> tints = null;
            int tintIndexCached = -1;
            int tintCached = -1;
            for (int partIndex = 0; partIndex < this.parts.size(); partIndex++) {
                List<BakedQuad> quads = this.parts.get(partIndex).getQuads(d);
                for (int quadIndex = 0; quadIndex < quads.size(); quadIndex++) {
                    BakedQuad q = quads.get(quadIndex);
                    int tintIndex = q.materialInfo().tintIndex();
                    int tint = -1;
                    if (tintIndex != -1) {
                        if (tintIndex != tintIndexCached) {
                            if (tints == null) {
                                tints = this.colors.getTintSources(tintState);
                            }
                            BlockTintSource source = tintIndex < tints.size() ? tints.get(tintIndex) : null;
                            tintCached = source != null ? source.colorInWorld(tintState, this.level, tintPos) : -1;
                            tintIndexCached = tintIndex;
                        }
                        tint = tintCached;
                    }
                    for (int v = 0; v < 4; v++) {
                        Vector3fc p = q.position(v);
                        int corner = (p.get(t1) > 0.5F ? 2 : 0) | (p.get(t2) > 0.5F ? 1 : 0);
                        this.vertexColor[v] = tint != -1 ? ARGB.multiply(this.cornerColor[corner], tint) : this.cornerColor[corner];
                        this.vertexLight[v] = this.cornerLight[corner];
                    }
                    out.quad(leaf ? this.opaque(q) : q, cx, cy, cz, this.vertexColor, this.vertexLight);
                }
            }
        } finally {
            this.parts.clear();
        }
    }

    /** The quad in the solid layer, like vanilla's Fast leaves; one copy per source quad so emitter caches keep working. */
    private BakedQuad opaque(final BakedQuad q) {
        BakedQuad solid = this.opaqueQuads.get(q);
        if (solid == null) {
            BakedQuad.MaterialInfo info = q.materialInfo();
            solid = new BakedQuad(
                q.position0(), q.position1(), q.position2(), q.position3(),
                q.packedUV0(), q.packedUV1(), q.packedUV2(), q.packedUV3(), q.direction(),
                new BakedQuad.MaterialInfo(
                    info.sprite(), ChunkSectionLayer.SOLID, info.itemRenderType(), info.itemGlintRenderType(),
                    info.itemGlintSpecialRenderType(), info.tintIndex(), info.shadeDirectionOverride(), info.lightEmission()
                )
            );
            // Resource packs may supply many distinct faces: keep worker memory bounded.
            if (this.opaqueQuads.size() >= 128) {
                this.opaqueQuads.clear();
            }
            this.opaqueQuads.put(q, solid);
        }
        return solid;
    }

    /** A small decorative plant without collision: not drawn in the outer band. Waterlogged ones still draw their water. */
    private boolean isHiddenPlant(final BlockState s, final BlockPos pos) {
        if (s.isSolidRender() || s.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        Block b = s.getBlock();
        boolean plant = b == Blocks.SHORT_GRASS || b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.LARGE_FERN
            || b == Blocks.DEAD_BUSH || b == Blocks.SEAGRASS || b == Blocks.TALL_SEAGRASS
            || b == Blocks.SUNFLOWER || b == Blocks.LILAC || b == Blocks.ROSE_BUSH || b == Blocks.PEONY || b == Blocks.PITCHER_PLANT
            || s.is(BlockTags.SMALL_FLOWERS) || s.is(BlockTags.SAPLINGS);
        // Azalea bushes are saplings with a collision box; only the passable plants go.
        return plant && s.getCollisionShape(this.level, pos).isEmpty();
    }

    /** Full-detail builds: one pass over the section for a hidden plant or a mergeable water top. */
    private boolean anyCoarseDifference() {
        boolean water = this.fluidModels != null;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState s = this.stateAt(x, y, z);
                    if (s.isAir()) {
                        continue;
                    }
                    if (water && ((x | z) & 1) == 0 && s.getBlock() == Blocks.WATER && this.isWaterTopFootprint(x, y, z)) {
                        return true;
                    }
                    if (this.isHiddenPlant(s, this.scratch.set(this.ox + x, this.oy + y, this.oz + z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Marks the water tops that a coarse build merges. Needs fluid models. */
    private void scanWaterTops() {
        if (this.fluidModels == null) {
            return;
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z += 2) {
                for (int x = 0; x < 16; x += 2) {
                    if (!this.isWaterTopFootprint(x, y, z)) {
                        continue;
                    }
                    int i = waterIndex(x, y, z);
                    this.waterTops[i >>> 6] |= 1L << i;
                    this.waterTopCount++;
                }
            }
        }
    }

    /**
     * Four water sources with air above, whose surface vanilla draws flat at the same height all over: every corner of
     * the footprint only sees these sources and the ring around them, which must be solid or flat water sources too.
     */
    private boolean isWaterTopFootprint(final int x, final int y, final int z) {
        for (int i = 0; i < 4; i++) {
            int bx = x + (i >> 1);
            int bz = z + (i & 1);
            BlockState s = this.stateAt(bx, y, bz);
            if (s.getBlock() != Blocks.WATER || !s.getFluidState().isSource() || !this.stateAt(bx, y + 1, bz).isAir()) {
                return false;
            }
        }
        for (int dx = -1; dx <= 2; dx++) {
            for (int dz = -1; dz <= 2; dz++) {
                boolean inside = dx >= 0 && dx <= 1 && dz >= 0 && dz <= 1;
                if (!inside && !this.isFlatWaterRing(x + dx, y, z + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Vanilla's corner height treats solid blocks as absent and a water source with no water above as full surface height. */
    private boolean isFlatWaterRing(final int x, final int y, final int z) {
        BlockState s = this.stateAt(x, y, z);
        FluidState fluid = s.getFluidState();
        if (fluid.getType().isSame(Fluids.WATER)) {
            return fluid.isSource() && !this.stateAt(x, y + 1, z).getFluidState().getType().isSame(Fluids.WATER);
        }
        return s.isSolid();
    }

    /** Emits the merged top of the footprint starting at (x, y, z); one tint sample, light copied per corner like vanilla. */
    public void emitWaterTop(final int x, final int y, final int z, final WaterEmitter out) {
        BlockState state = this.states[index(x, y, z)];
        FluidModel model = this.fluidModels.get(state.getFluidState());
        BlockTintSource tint = model.tintSource();
        int base = tint != null ? tint.colorInWorld(state, this.level, new BlockPos(this.ox + x, this.oy + y, this.oz + z)) : -1;
        int color = ARGB.scaleRGB(base, this.level.cardinalLighting().up());
        for (int k = 0; k < 4; k++) {
            BlockPos pos = this.scratch.set(this.ox + x + (k >> 1), this.oy + y, this.oz + z + (k & 1));
            this.vertexColor[k] = color;
            this.vertexLight[k] = LightCoordsUtil.max(
                LightCoordsUtil.getLightCoords(this.level, pos),
                LightCoordsUtil.getLightCoords(this.level, this.scratch2.setWithOffset(pos, Direction.UP))
            );
        }
        out.waterTop(model, x, y, z, state.getFluidState().getOwnHeight() - WATER_TOP_INSET, this.vertexColor, this.vertexLight);
    }

    private void lightCorner(final int k, final Direction d, final int t1, final int t2) {
        int i = this.subIndex[k];
        BlockState state = this.states[i];
        BlockPos pos = this.cornerPos.set(this.ox + (i & 15), this.oy + (i >> 8), this.oz + ((i >> 4) & 15));
        this.collectParts(state, pos);
        try {
            BakedQuad q = null;
            for (int partIndex = 0; partIndex < this.parts.size(); partIndex++) {
                List<BakedQuad> quads = this.parts.get(partIndex).getQuads(d);
                if (!quads.isEmpty()) {
                    q = quads.getFirst();
                    break;
                }
            }
            if (q == null) {
                return;
            }
            if (this.ambientOcclusion && state.getLightEmission() == 0 && this.parts.getFirst().useAmbientOcclusion()) {
                this.lighter.prepareQuadAmbientOcclusion(this.level, state, pos, q, this.lightOut);
            } else {
                int light = this.lighter.getLightCoords(state, this.level, this.scratch2.setWithOffset(pos, d));
                this.lighter.prepareQuadFlat(this.level, state, pos, light, q, this.lightOut);
            }
            float a = k >> 1;
            float b = k & 1;
            int vertex = 0;
            for (int v = 0; v < 4; v++) {
                Vector3fc p = q.position(v);
                if (Math.abs(p.get(t1) - a) < EPS && Math.abs(p.get(t2) - b) < EPS) {
                    vertex = v;
                    break;
                }
            }
            this.cornerColor[k] = this.lightOut.getColor(vertex);
            this.cornerLight[k] = this.lightOut.getLightCoords(vertex);
            this.cornerSet[k] = true;
        } finally {
            this.parts.clear();
        }
    }
}
