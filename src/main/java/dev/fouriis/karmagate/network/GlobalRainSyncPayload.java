package dev.fouriis.karmagate.network;

import dev.fouriis.karmagate.KarmaGateMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record GlobalRainSyncPayload(float intensity,
                                    float rainDirection,
                                    float bulletRainDensity,
                                    float rumbleSound,
                                    float screenShake,
                                    float microScreenShake) implements CustomPayload {

    public static final CustomPayload.Id<GlobalRainSyncPayload> ID =
            new CustomPayload.Id<>(Identifier.of(KarmaGateMod.MOD_ID, "global_rain_sync"));

    public static final PacketCodec<RegistryByteBuf, GlobalRainSyncPayload> CODEC = new PacketCodec<>() {
        @Override
        public GlobalRainSyncPayload decode(RegistryByteBuf buf) {
            return new GlobalRainSyncPayload(
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat(),
                    buf.readFloat()
            );
        }

        @Override
        public void encode(RegistryByteBuf buf, GlobalRainSyncPayload payload) {
            buf.writeFloat(payload.intensity());
            buf.writeFloat(payload.rainDirection());
            buf.writeFloat(payload.bulletRainDensity());
            buf.writeFloat(payload.rumbleSound());
            buf.writeFloat(payload.screenShake());
            buf.writeFloat(payload.microScreenShake());
        }
    };

    @Override
    public Id<? extends CustomPayload> getId() {
        return ID;
    }
}