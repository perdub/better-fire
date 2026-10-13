package dev.aliska.betterfire.mixin;

import dev.aliska.betterfire.config.BetterFireConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FireBlock;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {

    @Shadow
    protected abstract boolean isFlammable(BlockState state);

    @Shadow
    protected abstract int getBurnChance(BlockState state);

    // Счетчик искр за текущий серверный тик для предотвращения лагов и спама
    @Unique
    private static long lastWorldTime = -1;
    @Unique
    private static int sparksGeneratedThisTick = 0;

    /**
     * Ускоряем шедулинг тиков огня при добавлении и при тиках.
     */
    @ModifyArg(
        method = "scheduledTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ServerWorld;scheduleBlockTick(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;I)V"),
        index = 2
    )
    private int modifyScheduledTickDelay(int originalDelay) {
        BetterFireConfig config = BetterFireConfig.get();
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        return Math.max(1, config.fireTickMinDelay + rng.nextInt(Math.max(1, config.fireTickRandomDelay)));
    }

    @ModifyArg(
        method = "onBlockAdded",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/World;scheduleBlockTick(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/Block;I)V"),
        index = 2
    )
    private int modifyOnBlockAddedDelay(int originalDelay) {
        BetterFireConfig config = BetterFireConfig.get();
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        return Math.max(1, config.fireTickMinDelay + rng.nextInt(Math.max(1, config.fireTickRandomDelay)));
    }

    /**
     * Оптимизированная логика искр и сгорания дёрна.
     */
    @Inject(method = "scheduledTick", at = @At("TAIL"))
    private void onFireTickExtra(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        if (!world.getBlockState(pos).isOf(Blocks.FIRE)) {
            return;
        }

        BetterFireConfig config = BetterFireConfig.get();

        // 1. Сгорание дёрна в каменистую землю (выжженная земля)
        if (config.grassBlocksCatchFire) {
            BlockPos downPos = pos.down();
            BlockState downState = world.getBlockState(downPos);
            if (downState.isOf(Blocks.GRASS_BLOCK)) {
                if (random.nextDouble() < config.grassBlockBurnToDirtChance) {
                    world.setBlockState(downPos, Blocks.COARSE_DIRT.getDefaultState(), 3);
                }
            }
        }

        // 2. Механика искр с глобальным лимитом за тик и оптимизацией плотности огня
        if (config.enableSparks && random.nextDouble() < config.sparkChancePerTick) {
            long currentTime = world.getTime();
            if (currentTime != lastWorldTime) {
                lastWorldTime = currentTime;
                sparksGeneratedThisTick = 0;
            }

            if (sparksGeneratedThisTick < config.maxSparksPerWorldTick) {
                // Если вокруг вплотную уже сплошной огонь (в центре очага), искры не вылетают, экономя ресурсы
                if (config.limitSparksInDenseFire && isSurroundedByFire(world, pos)) {
                    return;
                }
                spawnAndSpreadSparks(world, pos, random, config);
            }
        }
    }

    @Unique
    private boolean isSurroundedByFire(ServerWorld world, BlockPos pos) {
        int neighborFireCount = 0;
        if (world.getBlockState(pos.north()).isOf(Blocks.FIRE)) neighborFireCount++;
        if (world.getBlockState(pos.south()).isOf(Blocks.FIRE)) neighborFireCount++;
        if (world.getBlockState(pos.west()).isOf(Blocks.FIRE)) neighborFireCount++;
        if (world.getBlockState(pos.east()).isOf(Blocks.FIRE)) neighborFireCount++;
        return neighborFireCount >= 3;
    }

    @Unique
    private void spawnAndSpreadSparks(ServerWorld world, BlockPos sourcePos, Random random, BetterFireConfig config) {
        int sparkCount = 1 + random.nextInt(config.maxSparksPerFireTick);
        int hRange = config.sparkRangeHorizontal;
        int vRange = config.sparkRangeVertical;

        for (int i = 0; i < sparkCount; i++) {
            if (sparksGeneratedThisTick >= config.maxSparksPerWorldTick) {
                break;
            }

            int dx = random.nextInt(hRange * 2 + 1) - hRange;
            int dz = random.nextInt(hRange * 2 + 1) - hRange;
            int dy = random.nextInt(vRange * 2 + 1) - (vRange / 2);

            if (dx == 0 && dz == 0) continue;

            BlockPos targetAirPos = sourcePos.add(dx, dy, dz);

            // Оптимизация: проверяем загружен ли чанк перед любыми операциями с блоками
            if (!world.isChunkLoaded(targetAirPos.getX() >> 4, targetAirPos.getZ() >> 4)) {
                continue;
            }

            sparksGeneratedThisTick++;

            // Визуальные эффекты искр
            double sparkMidX = sourcePos.getX() + 0.5 + dx * 0.5;
            double sparkMidY = sourcePos.getY() + 1.0 + dy * 0.5;
            double sparkMidZ = sourcePos.getZ() + 0.5 + dz * 0.5;
            world.spawnParticles(ParticleTypes.LAVA, sparkMidX, sparkMidY, sparkMidZ, 1, 0.2, 0.2, 0.2, 0.02);
            world.spawnParticles(ParticleTypes.FLAME, sparkMidX, sparkMidY, sparkMidZ, 1, 0.2, 0.2, 0.2, 0.04);

            // Проверяем возможность возгорания в воздухе
            if (world.isAir(targetAirPos) && !world.hasRain(targetAirPos)) {
                if (canIgniteAt(world, targetAirPos)) {
                    BlockState fireState = Blocks.FIRE.getDefaultState();
                    world.setBlockState(targetAirPos, fireState, 3);
                    world.spawnParticles(ParticleTypes.FLAME, targetAirPos.getX() + 0.5, targetAirPos.getY() + 0.5, targetAirPos.getZ() + 0.5, 3, 0.2, 0.2, 0.2, 0.02);
                }
            }
        }
    }

    @Unique
    private boolean canIgniteAt(ServerWorld world, BlockPos airPos) {
        BlockPos downPos = airPos.down();
        BlockState downState = world.getBlockState(downPos);

        // Противопожарные полосы (пашня, тропинки, вода) защищают от возгорания
        if (downState.isOf(Blocks.FARMLAND) || downState.isOf(Blocks.DIRT_PATH) || downState.isOf(Blocks.WATER)) {
            return false;
        }

        if (isFlammable(downState)) {
            return true;
        }

        return hasAdjacentFlammable(world, airPos);
    }

    @Unique
    private boolean hasAdjacentFlammable(ServerWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighborPos = pos.offset(direction);
            BlockState neighborState = world.getBlockState(neighborPos);
            if (isFlammable(neighborState)) {
                return true;
            }
        }
        return false;
    }
}
