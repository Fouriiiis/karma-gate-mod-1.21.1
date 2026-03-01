package dev.fouriis.karmagate.entity.centipede;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import software.bernie.geckolib.animatable.GeoAnimatable;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

/**
 * Base class for all centipede segments (heads and body parts).
 * Each segment is a separate entity with its own hitbox, linked together
 * by the parent RedCentipedeEntity controller via UUID references.
 *
 * Shared between Red Centipede and future normal Centipede variants.
 */
public abstract class CentipedeSegmentEntity extends MobEntity implements GeoAnimatable {

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);

    private static final RawAnimation IDLE_ANIM = RawAnimation.begin().thenLoop("idle");

    // Tracked data: the entity ID of the parent centipede controller
    private static final TrackedData<Integer> PARENT_ID = DataTracker.registerData(
            CentipedeSegmentEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // Tracked data: segment index within the chain (0 = front head, N-1 = rear head)
    private static final TrackedData<Integer> SEGMENT_INDEX = DataTracker.registerData(
            CentipedeSegmentEntity.class, TrackedDataHandlerRegistry.INTEGER);

    // Tracked data: whether this segment has its shell intact
    private static final TrackedData<Boolean> HAS_SHELL = DataTracker.registerData(
            CentipedeSegmentEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    // Physics state for chain simulation
    public Vec3d segmentVelocity = Vec3d.ZERO;
    public Vec3d prevTickPos = Vec3d.ZERO;

    // Limb simulation state per leg (0=left, 1=right)
    // Simulates C# Rain World Limb class: legs find surface grips,
    // plant on them, and release when body stretches them too far.
    public Vec3d[] legPos = new Vec3d[] { Vec3d.ZERO, Vec3d.ZERO };
    public Vec3d[] legLastPos = new Vec3d[] { Vec3d.ZERO, Vec3d.ZERO };
    public Vec3d[] legVel = new Vec3d[] { Vec3d.ZERO, Vec3d.ZERO };
    public Vec3d[] legGripTarget = new Vec3d[] { null, null };  // surface point gripped (null = no grip)
    public boolean[] legGripped = new boolean[] { false, false }; // true = foot planted at gripTarget
    public boolean legsInitialized = false;
    public int legUpdateAge = -1;

    // Surface normal for roll computation (set by parent controller's physics)
    // Mirrors C# CentipedeGraphics.bodyRotations / BestBodyRotatAtChunk
    public float surfaceNormalX = 0f;
    public float surfaceNormalY = 1f; // default: standing on floor
    public float surfaceNormalZ = 0f;
    // Previous tick values for smooth interpolation
    public float prevSurfaceNormalX = 0f;
    public float prevSurfaceNormalY = 1f;
    public float prevSurfaceNormalZ = 0f;

    public CentipedeSegmentEntity(EntityType<? extends MobEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        // Segments don't have their own AI; the parent controller drives them
        this.setAiDisabled(true);
    }

    public static DefaultAttributeContainer.Builder createSegmentAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.0)
                .add(EntityAttributes.GENERIC_ARMOR, 6.0)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 0.0);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(PARENT_ID, -1);
        builder.add(SEGMENT_INDEX, 0);
        builder.add(HAS_SHELL, true);
    }

    // --- Parent controller linkage ---

    public void setParentId(int id) {
        this.dataTracker.set(PARENT_ID, id);
    }

    public int getParentId() {
        return this.dataTracker.get(PARENT_ID);
    }

    /**
     * Resolve the parent centipede controller entity.
     */
    public RedCentipedeEntity getParentCentipede() {
        int pid = getParentId();
        if (pid == -1) return null;
        Entity e = this.getWorld().getEntityById(pid);
        if (e instanceof RedCentipedeEntity rce) return rce;
        return null;
    }

    // --- Segment index ---

    public void setSegmentIndex(int index) {
        this.dataTracker.set(SEGMENT_INDEX, index);
    }

    public int getSegmentIndex() {
        return this.dataTracker.get(SEGMENT_INDEX);
    }

    // --- Shell state ---

    public boolean hasShell() {
        return this.dataTracker.get(HAS_SHELL);
    }

    public void setHasShell(boolean shell) {
        this.dataTracker.set(HAS_SHELL, shell);
    }

    // --- Damage propagation ---

    @Override
    public boolean damage(DamageSource source, float amount) {
        // Immune to suffocation and fall damage
        if (source.isOf(DamageTypes.IN_WALL) || source.isOf(DamageTypes.FALL)) {
            return false;
        }

        // If shell is intact, reduce damage (like C# centipede armor)
        if (hasShell()) {
            // Shell absorbs most damage; small chance to break
            if (amount > 4.0f && this.random.nextFloat() < 0.3f) {
                setHasShell(false);
                // Shell fell off — take reduced damage this hit
                amount *= 0.1f;
            } else {
                amount *= 0.15f;
            }
        }

        // Propagate damage to parent controller's health pool
        RedCentipedeEntity parent = getParentCentipede();
        if (parent != null && !parent.isRemoved()) {
            parent.damage(source, amount);
            return false; // parent handles the actual damage
        }
        return super.damage(source, amount);
    }

    // --- Environment protection ---

    @Override
    public boolean isInsideWall() {
        // Segments are positioned by chain physics and may be inside blocks
        // (wall climbing). Never take suffocation damage.
        return false;
    }

    @Override
    public boolean isFireImmune() {
        return true;
    }

    @Override
    public boolean handleFallDamage(float fallDistance, float damageMultiplier, DamageSource damageSource) {
        // Centipede segments are immune to fall damage (wall/ceiling crawlers)
        return false;
    }

    // --- Prevent built-in movement/gravity from interfering with chain physics ---

    @Override
    public void travel(Vec3d movementInput) {
        // All movement is handled by parent controller's chain physics.
        // Do NOT call super.travel() — it would apply gravity and movement.
    }

    // --- Tick ---

    @Override
    public void tick() {
        this.noClip = true;
        // Save previous surface normal for smooth interpolation
        this.prevSurfaceNormalX = this.surfaceNormalX;
        this.prevSurfaceNormalY = this.surfaceNormalY;
        this.prevSurfaceNormalZ = this.surfaceNormalZ;
        this.prevTickPos = this.getPos();
        super.tick();

        if (!this.getWorld().isClient) {
            // If the parent is dead/gone, die too
            RedCentipedeEntity parent = getParentCentipede();
            if (parent == null || parent.isRemoved() || parent.isDead()) {
                this.discard();
            }
        } else {
            // Client: register with parent so renderers can find neighbors
            RedCentipedeEntity parent = getParentCentipede();
            if (parent != null) {
                parent.registerClientSegment(this);
            }
        }
    }

    // --- Prevent picking up / pushing ---

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canMoveVoluntarily() {
        return false;
    }

    // --- NBT ---

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putInt("SegmentIndex", getSegmentIndex());
        nbt.putBoolean("HasShell", hasShell());
        nbt.putInt("ParentEntityId", getParentId());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("SegmentIndex")) setSegmentIndex(nbt.getInt("SegmentIndex"));
        if (nbt.contains("HasShell")) setHasShell(nbt.getBoolean("HasShell"));
        if (nbt.contains("ParentEntityId")) setParentId(nbt.getInt("ParentEntityId"));
    }

    // --- GeckoLib ---

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 5, state -> {
            state.getController().setAnimation(IDLE_ANIM);
            return PlayState.CONTINUE;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    @Override
    public double getTick(Object entity) {
        return ((Entity) entity).age;
    }
}
