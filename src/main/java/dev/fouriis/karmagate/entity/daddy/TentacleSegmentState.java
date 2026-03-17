package dev.fouriis.karmagate.entity.daddy;

import net.minecraft.util.math.Vec3d;

public class TentacleSegmentState {
    private Vec3d pos;
    private Vec3d prevPos;

    public TentacleSegmentState(Vec3d pos) {
        this.pos = pos;
        this.prevPos = pos;
    }

    public Vec3d getPos() {
        return pos;
    }

    public Vec3d getPrevPos() {
        return prevPos;
    }

    public void verlet(Vec3d acceleration, double damping) {
        Vec3d velocity = pos.subtract(prevPos).multiply(damping);
        prevPos = pos;
        pos = pos.add(velocity).add(acceleration);
    }

    public void setPos(Vec3d newPos) {
        this.prevPos = this.pos;
        this.pos = newPos;
    }

    public void pin(Vec3d pinned) {
        this.prevPos = pinned;
        this.pos = pinned;
    }

    public Vec3d lerp(float tickDelta) {
        return prevPos.lerp(pos, tickDelta);
    }
}
