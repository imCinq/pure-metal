package io.github.imcinq.puremetal.lod.sodium;

import io.github.imcinq.puremetal.lod.CoarseMesher;
import io.github.imcinq.puremetal.lod.LodTextureRepeat;
import java.util.IdentityHashMap;
import net.caffeinemc.mods.sodium.api.util.ColorARGB;
import net.caffeinemc.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.DefaultMaterials;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.material.Material;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TranslucentGeometryCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.caffeinemc.mods.sodium.api.util.NormI8;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector3fc;

/**
 * Writes merged faces into Sodium's section buffers, the way Sodium's BlockRenderer buffers an opaque quad, and merged
 * water tops the way its fluid renderer buffers a translucent one (including the translucency sorting data).
 */
public final class SodiumEmitter implements CoarseMesher.Emitter, CoarseMesher.WaterEmitter {
    private static final ThreadLocal<SodiumEmitter> LOCAL = ThreadLocal.withInitial(SodiumEmitter::new);
    private final ChunkVertexEncoder.Vertex[] vertices = ChunkVertexEncoder.Vertex.uninitializedQuad();
    private final ChunkVertexEncoder.Vertex[] flipped = new ChunkVertexEncoder.Vertex[4];
    private ChunkBuildBuffers buffers;
    private TranslucentGeometryCollector collector;
    private final IdentityHashMap<Material, Material> repeatMaterials = new IdentityHashMap<>();

    public static SodiumEmitter of(final ChunkBuildBuffers buffers) {
        SodiumEmitter e = LOCAL.get();
        e.buffers = buffers;
        e.collector = null;
        return e;
    }

    /** For water tops; the collector is null when Sodium's translucency sorting is off. */
    public static SodiumEmitter of(final ChunkBuildBuffers buffers, final TranslucentGeometryCollector collector) {
        SodiumEmitter e = of(buffers);
        e.collector = collector;
        return e;
    }

    @Override
    public void quad(final BakedQuad q, final int cellX, final int cellY, final int cellZ, final int[] colors, final int[] lights) {
        int emission = q.materialInfo().lightEmission();
        for (int v = 0; v < 4; v++) {
            Vector3fc p = q.position(v);
            ChunkVertexEncoder.Vertex out = this.vertices[v];
            out.x = cellX + p.x() * 2.0F;
            out.y = cellY + p.y() * 2.0F;
            out.z = cellZ + p.z() * 2.0F;
            // Brightness (shade and AO) is already multiplied into the color, as vanilla does.
            out.color = ColorARGB.toABGR(colors[v]);
            out.ao = 1.0F;
            long uv = q.packedUV(v);
            out.u = UVPair.unpackU(uv);
            out.v = UVPair.unpackV(uv);
            out.light = LightCoordsUtil.lightCoordsWithEmission(lights[v], emission);
        }
        Material material = DefaultMaterials.forChunkLayer(q.materialInfo().layer());
        if (LodTextureRepeat.ready(true) && LodTextureRepeat.eligible(q)) {
            material = this.repeating(material);
        }
        ChunkModelBuilder builder = this.buffers.get(material);
        builder.getVertexBuffer(ModelQuadFacing.fromDirection(q.direction())).push(this.vertices, material);
        builder.addSprite(q.materialInfo().sprite());
    }

    @Override
    public void waterTop(final FluidModel model, final int x, final int y, final int z, final float height, final int[] colors, final int[] lights) {
        TextureAtlasSprite sprite = model.stillMaterial().sprite();
        Material material = DefaultMaterials.forChunkLayer(model.layer());
        if (LodTextureRepeat.ready(true)) {
            material = this.repeating(material);
        }
        float top = y + height;
        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        // Corner index is 2 * east + south. Same vertex order and flat aligned normal as DefaultFluidRenderer's top.
        this.waterVertex(0, x + 2, top, z, colors[2], u1, v0, lights[2]);
        this.waterVertex(1, x, top, z, colors[0], u0, v0, lights[0]);
        this.waterVertex(2, x, top, z + 2, colors[1], u0, v1, lights[1]);
        this.waterVertex(3, x + 2, top, z + 2, colors[3], u1, v1, lights[3]);
        ChunkModelBuilder builder = this.buffers.get(material);
        builder.addSprite(sprite);
        this.pushWaterTop(builder, material, ModelQuadFacing.POS_Y, false);
        this.pushWaterTop(builder, material, ModelQuadFacing.NEG_Y, true);
    }

    private void waterVertex(final int i, final float x, final float y, final float z, final int color, final float u, final float v, final int light) {
        ChunkVertexEncoder.Vertex out = this.vertices[i];
        out.x = x;
        out.y = y;
        out.z = z;
        out.color = ColorARGB.toABGR(color);
        out.ao = 1.0F;
        out.u = u;
        out.v = v;
        out.light = light;
    }

    /** The back face reuses the vertices in the reversed order DefaultFluidRenderer flips them to. */
    private void pushWaterTop(final ChunkModelBuilder builder, final Material material, final ModelQuadFacing facing, final boolean flip) {
        ChunkVertexEncoder.Vertex[] quad = this.vertices;
        if (flip) {
            quad = this.flipped;
            for (int i = 0; i < 4; i++) {
                quad[(3 - i + 1) & 3] = this.vertices[i];
            }
        }
        int normal = flip ? NormI8.flipPacked(facing.getPackedAlignedNormal()) : facing.getPackedAlignedNormal();
        if (material.isTranslucent() && this.collector != null && this.collector.appendQuad(quad, facing, normal)) {
            return;
        }
        builder.getVertexBuffer(facing).push(quad, material);
    }

    private Material repeating(final Material material) {
        return this.repeatMaterials.computeIfAbsent(material, original -> new Material(original.pass, original.alphaCutoff, original.mipped) {
            @Override public int bits() { return original.bits() | LodTextureRepeat.SODIUM_FLAG; }
        });
    }
}
