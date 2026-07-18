package dev.fouriis.karmagate.entity.oracle;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public abstract class OracleBehavior {
    protected final OracleEntity oracle;
    protected Vec3d oracleGetToPos;
    protected Vec3d baseGetToPos;
    protected Vec3d getToDir = new Vec3d(0.0, 1.0, 0.0);
    protected Vec3d lookPoint;
    protected int consistentBasePosCounter;

    protected OracleBehavior(OracleEntity oracle) {
        this.oracle = oracle;
        this.oracleGetToPos = oracle.getOracleCenter();
        this.baseGetToPos = defaultBasePos();
        this.lookPoint = oracle.getOracleCenter().add(0.0, 0.0, 4.0);
    }

    public void tick() {
        PlayerEntity player = oracle.findNearestPlayer(18.0);
        if (player != null) {
            lookPoint = player.getEyePos();
        }
        consistentBasePosCounter++;
    }

    public Vec3d oracleGetToPos() {
        return oracleGetToPos;
    }

    public Vec3d baseGetToPos() {
        return baseGetToPos;
    }

    public Vec3d getToDir() {
        if (getToDir.lengthSquared() < 1.0E-6) {
            return new Vec3d(0.0, 1.0, 0.0);
        }
        return getToDir.normalize();
    }

    public Vec3d lookPoint() {
        return lookPoint;
    }

    public int consistentBasePosCounter() {
        return consistentBasePosCounter;
    }

    protected Vec3d defaultBasePos() {
        return oracle.getOracleCenter().add(0.0, 3.5, 0.0);
    }

    protected Vec3d randomPointInBox(double xRadius, double yRadius, double zRadius) {
        Vec3d home = oracle.getHomePos();
        return home.add(
                (oracle.getRandom().nextDouble() * 2.0 - 1.0) * xRadius,
                (oracle.getRandom().nextDouble() * 2.0 - 1.0) * yRadius,
                (oracle.getRandom().nextDouble() * 2.0 - 1.0) * zRadius
        );
    }

    protected Vec3d clampAroundHome(Vec3d pos, double xRadius, double yRadius, double zRadius) {
        Vec3d home = oracle.getHomePos();
        return new Vec3d(
                MathHelper.clamp(pos.x, home.x - xRadius, home.x + xRadius),
                MathHelper.clamp(pos.y, home.y - yRadius, home.y + yRadius),
                MathHelper.clamp(pos.z, home.z - zRadius, home.z + zRadius)
        );
    }
}
