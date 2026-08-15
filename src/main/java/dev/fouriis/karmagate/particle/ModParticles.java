// ModParticles.java
package dev.fouriis.karmagate.particle;

import dev.fouriis.karmagate.KarmaGateMod;
import net.fabricmc.fabric.api.particle.v1.FabricParticleTypes;
import net.minecraft.particle.SimpleParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModParticles {
    private ModParticles() {}

    public static final SimpleParticleType STEAM = FabricParticleTypes.simple();

    public static void register() {
        Registry.register(Registries.PARTICLE_TYPE, Identifier.of(KarmaGateMod.MOD_ID, "steam"), STEAM);
    }
}
