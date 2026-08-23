package dev.fouriis.karmagate.entity.coralbrain.client;

import dev.fouriis.karmagate.entity.coralbrain.Mycelium;
import dev.fouriis.karmagate.entity.coralbrain.WallMyceliaBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

/** Renders every WallMycelia strand as the C# half-pixel neural ribbon. */
public final class WallMyceliaBlockEntityRenderer
        implements BlockEntityRenderer<WallMyceliaBlockEntity> {
    private static final Identifier WHITE =
            Identifier.of("minecraft", "textures/misc/white.png");

    public WallMyceliaBlockEntityRenderer(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(WallMyceliaBlockEntity wall, float tickDelta,
                       MatrixStack matrices, VertexConsumerProvider consumers,
                       int light, int overlay) {
        if (wall.getMycelia().isEmpty()) return;
        Vec3d origin = Vec3d.of(wall.getPos());
        Vec3d camera = MinecraftClient.getInstance().gameRenderer.getCamera().getPos().subtract(origin);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumer vertices = consumers.getBuffer(RenderLayer.getEntityTranslucent(WHITE));

        for (Mycelium strand : wall.getMycelia()) {
            Vec3d[] worldPoints = strand.samplePoints(tickDelta);
            Vec3d[] localPoints = new Vec3d[worldPoints.length];
            for (int i = 0; i < worldPoints.length; i++) localPoints[i] = worldPoints[i].subtract(origin);
            MyceliumRenderUtil.renderRainWorldMycelium(
                    vertices, matrix, localPoints, camera,
                    0, 2, 3, 255, LightmapTextureManager.MAX_LIGHT_COORDINATE);
        }
    }

    @Override
    public boolean rendersOutsideBoundingBox(WallMyceliaBlockEntity blockEntity) {
        return true;
    }

    @Override
    public int getRenderDistance() {
        return 160;
    }
}
