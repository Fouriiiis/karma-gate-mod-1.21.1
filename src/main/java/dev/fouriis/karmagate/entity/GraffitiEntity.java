package dev.fouriis.karmagate.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public class GraffitiEntity extends Entity {
    private static final TrackedData<Integer> FACING = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<String> TEXTURE_PATH = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.STRING);
    
    // Default texture if none is set
    public static final String DEFAULT_TEXTURE = "actualverticalpink.png";
    
    // Corner positions as horizontal (H) and vertical (V) offsets from entity center
    // Order: bottom-left (0), bottom-right (1), top-right (2), top-left (3)
    private static final TrackedData<Float> CORNER_0_H = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_0_V = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_1_H = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_1_V = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_2_H = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_2_V = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_3_H = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_3_V = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> CORNER_0_OPACITY = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_1_OPACITY = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_2_OPACITY = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_3_OPACITY = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);

    private static final TrackedData<Float> CORNER_0_MELT = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_1_MELT = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_2_MELT = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    private static final TrackedData<Float> CORNER_3_MELT = DataTracker.registerData(GraffitiEntity.class, TrackedDataHandlerRegistry.FLOAT);
    
    // Default size (same as renderer defaults)
    private static final float DEFAULT_HALF_WIDTH = (203f / 16f * 0.15f) / 2f;
    private static final float DEFAULT_HALF_HEIGHT = (339f / 16f * 0.15f) / 2f;

    public GraffitiEntity(EntityType<?> type, World world) {
        super(type, world);
        this.noClip = true;
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        builder.add(FACING, Direction.NORTH.getId());
        builder.add(TEXTURE_PATH, DEFAULT_TEXTURE);
        // Default rectangular shape
        builder.add(CORNER_0_H, -DEFAULT_HALF_WIDTH);  // bottom-left
        builder.add(CORNER_0_V, -DEFAULT_HALF_HEIGHT);
        builder.add(CORNER_1_H, DEFAULT_HALF_WIDTH);   // bottom-right
        builder.add(CORNER_1_V, -DEFAULT_HALF_HEIGHT);
        builder.add(CORNER_2_H, DEFAULT_HALF_WIDTH);   // top-right
        builder.add(CORNER_2_V, DEFAULT_HALF_HEIGHT);
        builder.add(CORNER_3_H, -DEFAULT_HALF_WIDTH);  // top-left
        builder.add(CORNER_3_V, DEFAULT_HALF_HEIGHT);

        builder.add(CORNER_0_OPACITY, 1.0f);
        builder.add(CORNER_1_OPACITY, 1.0f);
        builder.add(CORNER_2_OPACITY, 1.0f);
        builder.add(CORNER_3_OPACITY, 1.0f);

        builder.add(CORNER_0_MELT, 0.0f);
        builder.add(CORNER_1_MELT, 0.0f);
        builder.add(CORNER_2_MELT, 0.0f);
        builder.add(CORNER_3_MELT, 0.0f);
    }

    public void setFacing(Direction direction) {
        this.dataTracker.set(FACING, direction.getId());
    }

    public Direction getFacing() {
        return Direction.byId(this.dataTracker.get(FACING));
    }
    
    public void setTexturePath(String texturePath) {
        this.dataTracker.set(TEXTURE_PATH, texturePath);
    }
    
    public String getTexturePath() {
        return this.dataTracker.get(TEXTURE_PATH);
    }
    
    // Get corner position (h = horizontal offset, v = vertical offset from entity center)
    public float getCornerH(int corner) {
        return switch (corner) {
            case 0 -> dataTracker.get(CORNER_0_H);
            case 1 -> dataTracker.get(CORNER_1_H);
            case 2 -> dataTracker.get(CORNER_2_H);
            case 3 -> dataTracker.get(CORNER_3_H);
            default -> 0f;
        };
    }
    
    public float getCornerV(int corner) {
        return switch (corner) {
            case 0 -> dataTracker.get(CORNER_0_V);
            case 1 -> dataTracker.get(CORNER_1_V);
            case 2 -> dataTracker.get(CORNER_2_V);
            case 3 -> dataTracker.get(CORNER_3_V);
            default -> 0f;
        };
    }
    
    public void setCorner(int corner, float h, float v) {
        switch (corner) {
            case 0 -> { dataTracker.set(CORNER_0_H, h); dataTracker.set(CORNER_0_V, v); }
            case 1 -> { dataTracker.set(CORNER_1_H, h); dataTracker.set(CORNER_1_V, v); }
            case 2 -> { dataTracker.set(CORNER_2_H, h); dataTracker.set(CORNER_2_V, v); }
            case 3 -> { dataTracker.set(CORNER_3_H, h); dataTracker.set(CORNER_3_V, v); }
        }
    }

    public float getCornerOpacity(int corner) {
        return switch (corner) {
            case 0 -> dataTracker.get(CORNER_0_OPACITY);
            case 1 -> dataTracker.get(CORNER_1_OPACITY);
            case 2 -> dataTracker.get(CORNER_2_OPACITY);
            case 3 -> dataTracker.get(CORNER_3_OPACITY);
            default -> 1.0f;
        };
    }

    public void setCornerOpacity(int corner, float value) {
        float clamped = clamp01(value);
        switch (corner) {
            case 0 -> dataTracker.set(CORNER_0_OPACITY, clamped);
            case 1 -> dataTracker.set(CORNER_1_OPACITY, clamped);
            case 2 -> dataTracker.set(CORNER_2_OPACITY, clamped);
            case 3 -> dataTracker.set(CORNER_3_OPACITY, clamped);
        }
    }

    public float getCornerMelt(int corner) {
        return switch (corner) {
            case 0 -> dataTracker.get(CORNER_0_MELT);
            case 1 -> dataTracker.get(CORNER_1_MELT);
            case 2 -> dataTracker.get(CORNER_2_MELT);
            case 3 -> dataTracker.get(CORNER_3_MELT);
            default -> 0.0f;
        };
    }

    public void setCornerMelt(int corner, float value) {
        float clamped = clamp01(value);
        switch (corner) {
            case 0 -> dataTracker.set(CORNER_0_MELT, clamped);
            case 1 -> dataTracker.set(CORNER_1_MELT, clamped);
            case 2 -> dataTracker.set(CORNER_2_MELT, clamped);
            case 3 -> dataTracker.set(CORNER_3_MELT, clamped);
        }
    }
    
    // Get all corners as array of [h, v] pairs
    public float[][] getCorners() {
        return new float[][] {
            {getCornerH(0), getCornerV(0)},
            {getCornerH(1), getCornerV(1)},
            {getCornerH(2), getCornerV(2)},
            {getCornerH(3), getCornerV(3)}
        };
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        if (nbt.contains("Facing")) {
            setFacing(Direction.byId(nbt.getInt("Facing")));
        }
        if (nbt.contains("TexturePath")) {
            setTexturePath(nbt.getString("TexturePath"));
        }
        for (int i = 0; i < 4; i++) {
            if (nbt.contains("Corner" + i + "H")) {
                setCorner(i, nbt.getFloat("Corner" + i + "H"), nbt.getFloat("Corner" + i + "V"));
            }
            if (nbt.contains("Corner" + i + "Opacity")) {
                setCornerOpacity(i, nbt.getFloat("Corner" + i + "Opacity"));
            }
            if (nbt.contains("Corner" + i + "Melt")) {
                setCornerMelt(i, nbt.getFloat("Corner" + i + "Melt"));
            }
        }
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("Facing", getFacing().getId());
        nbt.putString("TexturePath", getTexturePath());
        for (int i = 0; i < 4; i++) {
            nbt.putFloat("Corner" + i + "H", getCornerH(i));
            nbt.putFloat("Corner" + i + "V", getCornerV(i));
            nbt.putFloat("Corner" + i + "Opacity", getCornerOpacity(i));
            nbt.putFloat("Corner" + i + "Melt", getCornerMelt(i));
        }
    }

    private static float clamp01(float value) {
        return value < 0.0f ? 0.0f : Math.min(value, 1.0f);
    }

    @Override
    public void tick() {
        // Graffiti doesn't move
    }

    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return false;
    }
}
