package io.github.imcinq.puremetal.lod;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.IdentityHashMap;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * Writes merged faces through vanilla's section quad output (same layers and vertex format as normal blocks), and merged
 * water tops through the fluid renderer's layer builders (same vertices as its own water tops).
 */
public final class VanillaEmitter implements CoarseMesher.Emitter, CoarseMesher.WaterEmitter {
    private static final ThreadLocal<VanillaEmitter> LOCAL = ThreadLocal.withInitial(VanillaEmitter::new);
    private final QuadInstance instance = new QuadInstance();
    private final IdentityHashMap<BakedQuad, BakedQuad> scaledQuads = new IdentityHashMap<>();
    private BlockQuadOutput output;
    private FluidRenderer.Output fluidOutput;

    public static VanillaEmitter of(final BlockQuadOutput output) {
        VanillaEmitter e = LOCAL.get();
        if (e.output != output) {
            e.scaledQuads.clear();
        }
        e.output = output;
        return e;
    }

    public static VanillaEmitter ofFluid(final FluidRenderer.Output output) {
        VanillaEmitter e = LOCAL.get();
        e.fluidOutput = output;
        return e;
    }

    /** Release section outputs and geometry references after a worker finishes the section. */
    public static void end() {
        VanillaEmitter e = LOCAL.get();
        e.output = null;
        e.fluidOutput = null;
        e.scaledQuads.clear();
    }

    @Override
    public void quad(final BakedQuad q, final int cellX, final int cellY, final int cellZ, final int[] colors, final int[] lights) {
        for (int v = 0; v < 4; v++) {
            this.instance.setColor(v, colors[v]);
            this.instance.setLightCoords(v, lights[v]);
        }
        BakedQuad scaled = this.scaledQuads.get(q);
        if (scaled == null) {
            boolean repeat = LodTextureRepeat.ready(false) && LodTextureRepeat.eligible(q);
            scaled = new BakedQuad(
                doubled(q.position0()), doubled(q.position1()), doubled(q.position2()), doubled(q.position3()),
                repeat ? LodTextureRepeat.mark(q.packedUV0()) : q.packedUV0(),
                repeat ? LodTextureRepeat.mark(q.packedUV1()) : q.packedUV1(),
                repeat ? LodTextureRepeat.mark(q.packedUV2()) : q.packedUV2(),
                repeat ? LodTextureRepeat.mark(q.packedUV3()) : q.packedUV3(), q.direction(), q.materialInfo()
            );
            // Resource packs may supply many distinct faces: keep worker memory bounded.
            if (this.scaledQuads.size() >= 128) {
                this.scaledQuads.clear();
            }
            this.scaledQuads.put(q, scaled);
        }
        this.output.put(cellX, cellY, cellZ, scaled, this.instance);
    }

    @Override
    public void waterTop(final FluidModel model, final int x, final int y, final int z, final float height, final int[] colors, final int[] lights) {
        VertexConsumer builder = this.fluidOutput.getBuilder(model.layer());
        TextureAtlasSprite sprite = model.stillMaterial().sprite();
        // The sprite fills the whole 2x2 top, so it is always a face the texture repeat shader can tile.
        float shift = LodTextureRepeat.ready(false) ? 2.0F : 0.0F;
        float u0 = sprite.getU0() + shift;
        float u1 = sprite.getU1() + shift;
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        float top = y + height;
        // Corner index is 2 * east + south. Front face as in FluidRenderer, then the back face seen from below.
        vertex(builder, x, top, z, colors[0], u0, v0, lights[0]);
        vertex(builder, x, top, z + 2, colors[1], u0, v1, lights[1]);
        vertex(builder, x + 2, top, z + 2, colors[3], u1, v1, lights[3]);
        vertex(builder, x + 2, top, z, colors[2], u1, v0, lights[2]);
        vertex(builder, x, top, z, colors[0], u0, v0, lights[0]);
        vertex(builder, x + 2, top, z, colors[2], u1, v0, lights[2]);
        vertex(builder, x + 2, top, z + 2, colors[3], u1, v1, lights[3]);
        vertex(builder, x, top, z + 2, colors[1], u0, v1, lights[1]);
    }

    private static void vertex(final VertexConsumer builder, final float x, final float y, final float z, final int color, final float u, final float v, final int light) {
        builder.addVertex(x, y, z, color, u, v, OverlayTexture.NO_OVERLAY, light, 0.0F, 1.0F, 0.0F);
    }

    private static Vector3f doubled(final Vector3fc p) {
        return new Vector3f(p).mul(2.0F);
    }
}
