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

    /**
     * Ускоряем шедулинг тиков огня при добавлении и при тиках.
     * Ванилла: 30 + random.nextInt(10).
     * С модом: конфигурируемо (по умолчанию 6 + random.nextInt(6)) — огонь распространяется в ~4-5 раз быстрее.
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
     * Дополнительная агрессивная логика распространения, искр (embers) и сгорания дёрна.
     */
    @Inject(method = "scheduledTick", at = @At("TAIL"))
    private void onFireTickExtra(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        if (!world.getBlockState(pos).isOf(Blocks.FIRE)) {
            return;
        }

        BetterFireConfig config = BetterFireConfig.get();

        // 1. Сгорание дёрна в землю/бесплодную землю (выжженная земля)
        if (config.grassBlocksCatchFire) {
            BlockPos downPos = pos.down();
            BlockState downState = world.getBlockState(downPos);
            if (downState.isOf(Blocks.GRASS_BLOCK)) {
                if (random.nextDouble() < config.grassBlockBurnToDirtChance) {
                    world.setBlockState(downPos, Blocks.COARSE_DIRT.getDefaultState(), 3);
                }
            }
        }

        // 2. Механика искр (Embers/Sparks), перелетающих через воздух и поджигающих постройки и лес
        if (config.enableSparks && random.nextDouble() < config.sparkChancePerTick) {
            spawnAndSpreadSparks(world, pos, random, config);
        }
    }

    private void spawnAndSpreadSparks(ServerWorld world, BlockPos sourcePos, Random random, BetterFireConfig config) {
        int sparkCount = 1 + random.nextInt(config.maxSparksPerFireTick);
        int hRange = config.sparkRangeHorizontal;
        int vRange = config.sparkRangeVertical;

        for (int i = 0; i < sparkCount; i++) {
            int dx = random.nextInt(hRange * 2 + 1) - hRange;
            int dz = random.nextInt(hRange * 2 + 1) - hRange;
            int dy = random.nextInt(vRange * 2 + 1) - (vRange / 2); // искры поднимаются выше горячим воздухом

            // Не поджигаем в той же точке
            if (dx == 0 && dz == 0) continue;

            BlockPos targetAirPos = sourcePos.add(dx, dy, dz);

            // Визуальные эффекты искр (Server-side частицы отправляются всем игрокам вокруг)
            double sparkMidX = sourcePos.getX() + 0.5 + dx * 0.5;
            double sparkMidY = sourcePos.getY() + 1.0 + dy * 0.5;
            double sparkMidZ = sourcePos.getZ() + 0.5 + dz * 0.5;
            world.spawnParticles(ParticleTypes.LAVA, sparkMidX, sparkMidY, sparkMidZ, 1, 0.2, 0.2, 0.2, 0.02);
            world.spawnParticles(ParticleTypes.FLAME, sparkMidX, sparkMidY, sparkMidZ, 2, 0.3, 0.3, 0.3, 0.05);

            // Проверяем, можно ли зажечь огонь в целевой точке
            if (world.isAir(targetAirPos) && !world.hasRain(targetAirPos)) {
                // Огонь может загореться, если рядом есть горючий блок или блок снизу горючий
                if (canIgniteAt(world, targetAirPos)) {
                    // Ставим огонь с начальным возрастом
                    BlockState fireState = Blocks.FIRE.getDefaultState();
                    world.setBlockState(targetAirPos, fireState, 3);
                    world.spawnParticles(ParticleTypes.FLAME, targetAirPos.getX() + 0.5, targetAirPos.getY() + 0.5, targetAirPos.getZ() + 0.5, 5, 0.2, 0.2, 0.2, 0.02);
                }
            }
        }
    }

    /**
     * Проверка, может ли огонь загореться в целевом воздухе (проверяем соседей).
     * Учитываем противопожарные барьеры: пашня (Farmland), тропинки (Dirt Path), камень, вода НЕ дают искре разгореться.
     */
    private boolean canIgniteAt(ServerWorld world, BlockPos airPos) {
        BlockPos downPos = airPos.down();
        BlockState downState = world.getBlockState(downPos);

        // Если снизу противопожарная полоса (вспаханная земля, тропинка, камень, вода)
        if (downState.isOf(Blocks.FARMLAND) || downState.isOf(Blocks.DIRT_PATH) || downState.isOf(Blocks.WATER)) {
            // Если снизу защитная полоса, проверяем нет ли прямо вокруг других горючих блоков (деревянные стены и т.д.)
            return hasAdjacentFlammable(world, airPos);
        }

        // Если снизу полнотелый блок и рядом есть что-то горючее или сам блок снизу горючий
        if (isFlammable(downState)) {
            return true;
        }

        return hasAdjacentFlammable(world, airPos);
    }

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
