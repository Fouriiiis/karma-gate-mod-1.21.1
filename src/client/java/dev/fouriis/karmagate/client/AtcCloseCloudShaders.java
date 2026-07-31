package dev.fouriis.karmagate.client;

import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.RenderPhase;
import net.minecraft.util.Identifier;

public final class AtcCloseCloudShaders {
    public static final Identifier ID = Identifier.of("karma-gate-mod", "karma_atc_close_cloud_volume");
    public static ShaderProgram PROGRAM;

    private AtcCloseCloudShaders() {
    }

    public static RenderPhase.ShaderProgram phase() {
        return new RenderPhase.ShaderProgram(() -> PROGRAM);
    }
}
