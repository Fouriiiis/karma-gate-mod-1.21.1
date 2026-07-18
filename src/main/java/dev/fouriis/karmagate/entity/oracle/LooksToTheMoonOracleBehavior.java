package dev.fouriis.karmagate.entity.oracle;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

public class LooksToTheMoonOracleBehavior extends OracleBehavior {
    public LooksToTheMoonOracleBehavior(OracleEntity oracle) {
        super(oracle);
        this.oracleGetToPos = oracle.getHomePos();
        this.baseGetToPos = oracle.getHomePos().add(-2.6, 1.4, 0.0);
        this.getToDir = new Vec3d(-1.0, 0.0, 0.0);
        this.lookPoint = oracle.getHomePos().add(-3.0, 0.0, 0.0);
    }

    @Override
    public void tick() {
        consistentBasePosCounter++;
        oracleGetToPos = oracle.getHomePos();
        baseGetToPos = oracle.getHomePos().add(-2.6, 1.4, 0.0);
        getToDir = new Vec3d(-1.0, 0.0, 0.0);

        PlayerEntity player = oracle.findNearestPlayer(14.0);
        if (player != null) {
            lookPoint = player.getEyePos();
        } else {
            lookPoint = oracleGetToPos.add(-3.0, 0.2, 0.0);
        }
    }
}
