package dev.fouriis.karmagate.entity.garbworm.client;

import dev.fouriis.karmagate.entity.garbworm.GarbageWormEntity;
import net.minecraft.client.sound.MovingSoundInstance;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class GarbageWormLoopSoundInstance extends MovingSoundInstance {

    private final GarbageWormEntity worm;
    private SoundEvent currentEvent;
    private boolean markedDone = false;

    public GarbageWormLoopSoundInstance(GarbageWormEntity worm, SoundEvent initialEvent, float volume, float pitch) {
        super(initialEvent, SoundCategory.HOSTILE, SoundInstance.createRandom());
        this.worm = worm;
        this.currentEvent = initialEvent;

        this.repeat = true;
        this.repeatDelay = 0;
        this.relative = false;

        this.volume = volume;
        this.pitch = pitch;

        Vec3d pos = worm.getPos();
        this.x = pos.x;
        this.y = pos.y;
        this.z = pos.z;
    }

    @Override
    public void tick() {
        if (markedDone || worm == null || !worm.isAlive() || worm.isRemoved() || worm.getExtended() <= 0f) {
            setDone();
            return;
        }

        Vec3d pos = worm.getPos();
        this.x = pos.x;
        this.y = pos.y;
        this.z = pos.z;

        float stress = worm.getStress();
        float extended = worm.getExtended();

        this.volume = worm.getAttackCtr() > 40 && worm.getAttackCtr() < 190
                ? 1.0f
                : MathHelper.clamp(stress * extended, 0.08f, 1.0f);

        this.pitch = worm.getAttackCtr() > 40 && worm.getAttackCtr() < 190
                ? 1.0f
                : MathHelper.lerp(stress, 0.9f, 1.1f);
    }

    public SoundEvent getCurrentEvent() {
        return currentEvent;
    }

    public void switchTo(SoundEvent newEvent) {
        this.currentEvent = newEvent;
    }

    public void stopNow() {
        markedDone = true;
        setDone();
    }
}
