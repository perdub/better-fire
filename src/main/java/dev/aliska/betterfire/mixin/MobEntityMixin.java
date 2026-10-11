package dev.aliska.betterfire.mixin;

import dev.aliska.betterfire.config.BetterFireConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MobEntity.class)
public abstract class MobEntityMixin extends LivingEntity {

    protected MobEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    /**
     * Горящий моб оставляет за собой огненный след (поджигает траву/блоки под ногами и спавнит искры/частицы).
     */
    @Inject(method = "tickMovement", at = @At("TAIL"))
    private void onTickMobFireTrail(CallbackInfo ci) {
        if (this.getWorld().isClient()) {
            return;
        }

        if (!this.isOnFire()) {
            return;
        }

        BetterFireConfig config = BetterFireConfig.get();
        if (!config.mobFireTrail) {
            return;
        }

        ServerWorld world = (ServerWorld) this.getWorld();

        // Не оставляем огонь под водой или в дождь на открытом воздухе
        if (this.isSubmergedInWater() || this.isTouchingWaterOrRain()) {
            return;
        }

        // Спавним шлейф дыма и пламени от бегущего горящего моба
        if (world.random.nextInt(3) == 0) {
            world.spawnParticles(
                ParticleTypes.FLAME,
                this.getX(), this.getY() + 0.5, this.getZ(),
                2, 0.2, 0.2, 0.2, 0.01
            );
            world.spawnParticles(
                ParticleTypes.SMOKE,
                this.getX(), this.getY() + 0.8, this.getZ(),
                1, 0.1, 0.2, 0.1, 0.01
            );
        }

        // Каждые N тиков проверяем, можно ли поджечь блок под ногами
        if (this.age % config.mobTrailIntervalTicks == 0) {
            if (world.random.nextDouble() < config.mobTrailIgniteChance) {
                BlockPos currentPos = this.getBlockPos();
                BlockPos belowPos = currentPos.down();

                BlockState currentState = world.getBlockState(currentPos);
                BlockState belowState = world.getBlockState(belowPos);

                // Если моб стоит на защитной полосе (вспаханная земля мотыгой, тропинка, камень, вода) — огонь не загорается!
                if (belowState.isOf(Blocks.FARMLAND) || belowState.isOf(Blocks.DIRT_PATH) || belowState.isOf(Blocks.WATER)) {
                    return;
                }

                // Если под ногами трава/цветок/растение — заменяем на огонь
                if (currentState.isOf(Blocks.SHORT_GRASS) || currentState.isOf(Blocks.TALL_GRASS) || currentState.isOf(Blocks.FERN)) {
                    world.setBlockState(currentPos, Blocks.FIRE.getDefaultState(), 3);
                } else if (world.isAir(currentPos)) {
                    // Если под ногами воздух и блок внизу твердый/горючий
                    if (belowState.isSolid() && !belowState.isOf(Blocks.FARMLAND) && !belowState.isOf(Blocks.DIRT_PATH)) {
                        world.setBlockState(currentPos, Blocks.FIRE.getDefaultState(), 3);
                    }
                }
            }
        }
    }
}
