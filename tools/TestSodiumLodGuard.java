import io.github.imcinq.puremetal.lod.sodium.SodiumLodSupport;
import java.util.function.Predicate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/** Headless: checks Terrain LOD's Sodium shape guards against the pinned Sodium classes. */
public final class TestSodiumLodGuard {
    private static ClassNode read(String name) throws Exception {
        ClassNode node = new ClassNode();
        try (var input = TestSodiumLodGuard.class.getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (input == null) throw new AssertionError("Missing test class " + name);
            new ClassReader(input).accept(node, 0);
        }
        return node;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void accepted(String name, Predicate<ClassNode> shape) throws Exception {
        check(shape.test(read(name)), "Actual Sodium shape rejected: " + name);
    }

    private static void rejected(String name, Predicate<ClassNode> shape, java.util.function.Consumer<ClassNode> change) throws Exception {
        ClassNode node = read(name);
        change.accept(node);
        check(!shape.test(node), "Changed shape accepted: " + name);
    }

    private static java.util.function.Consumer<ClassNode> rename() {
        return node -> node.name += "$changed";
    }

    private static java.util.function.Consumer<ClassNode> removeMethod(String name) {
        return node -> node.methods.removeIf(method -> method.name.equals(name));
    }

    public static void main(String[] args) throws Exception {
        String meshing = "net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask";
        String manager = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager";
        String section = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSection";
        String output = "net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput";
        String context = "net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext";
        String renderContext = "net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext";
        String cache = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderCache";

        accepted(meshing, SodiumLodSupport::meshingTask);
        accepted(manager, SodiumLodSupport::renderManager);
        accepted(section, SodiumLodSupport::renderSection);
        accepted(output, SodiumLodSupport::buildOutput);
        accepted(context, SodiumLodSupport::buildContext);
        accepted(renderContext, SodiumLodSupport::renderContext);
        accepted(cache, SodiumLodSupport::renderCache);

        rejected(meshing, SodiumLodSupport::meshingTask, rename());
        rejected(manager, SodiumLodSupport::renderManager, rename());
        rejected(section, SodiumLodSupport::renderSection, rename());
        rejected(output, SodiumLodSupport::buildOutput, rename());
        rejected(context, SodiumLodSupport::buildContext, rename());
        rejected(renderContext, SodiumLodSupport::renderContext, rename());
        rejected(cache, SodiumLodSupport::renderCache, rename());

        rejected(meshing, SodiumLodSupport::meshingTask, node -> {
            for (MethodNode method : node.methods) method.instructions.clear();
        });
        rejected(manager, SodiumLodSupport::renderManager, removeMethod("prepareFrame"));
        rejected(section, SodiumLodSupport::renderSection, removeMethod("getChunkX"));
        rejected(context, SodiumLodSupport::buildContext, node -> node.fields.removeIf(field -> field.name.equals("buffers")));
        rejected(renderContext, SodiumLodSupport::renderContext, removeMethod("getOrigin"));
        rejected(cache, SodiumLodSupport::renderCache, removeMethod("getWorldSlice"));

        System.out.println("PASS: Sodium Terrain LOD shape guards accept the pinned release and reject changed shapes");
    }
}
