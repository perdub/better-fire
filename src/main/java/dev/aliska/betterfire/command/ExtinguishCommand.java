package dev.aliska.betterfire.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.aliska.betterfire.config.BetterFireConfig;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;

public class ExtinguishCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("extinguish")
                .requires(source -> source.hasPermissionLevel(2)) // Требуются права оператора / OP
                // 1. /extinguish (по умолчанию радиус 50 вокруг источника команды)
                .executes(context -> extinguishAround(context.getSource(), context.getSource().getWorld(), BlockPos.ofFloored(context.getSource().getPosition()), 50))
                // 2. /extinguish <radius>
                .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 500))
                    .executes(context -> {
                        int radius = IntegerArgumentType.getInteger(context, "radius");
                        return extinguishAround(context.getSource(), context.getSource().getWorld(), BlockPos.ofFloored(context.getSource().getPosition()), radius);
                    })
                    // 3. /extinguish <radius> <pos>
                    .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                        .executes(context -> {
                            int radius = IntegerArgumentType.getInteger(context, "radius");
                            BlockPos pos = BlockPosArgumentType.getLoadedBlockPos(context, "pos");
                            return extinguishAround(context.getSource(), context.getSource().getWorld(), pos, radius);
                        })
                    )
                )
                // 4. /extinguish all (тушит абсолютно все загруженные чанки в текущем мире)
                .then(CommandManager.literal("all")
                    .executes(context -> extinguishAllLoaded(context.getSource(), context.getSource().getWorld()))
                )
                // 5. /extinguish reload (перезагрузка конфига на лету)
                .then(CommandManager.literal("reload")
                    .executes(context -> {
                        BetterFireConfig.load();
                        context.getSource().sendFeedback(() -> Text.literal("[BetterFire] Конфигурация успешно перезагружена!").formatted(Formatting.GREEN), true);
                        return 1;
                    })
                )
        );
    }

    private static int extinguishAround(ServerCommandSource source, ServerWorld world, BlockPos center, int radius) {
        int count = 0;
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minY = Math.max(world.getBottomY(), center.getY() - radius);
        int maxY = Math.min(world.getTopY(), center.getY() + radius);
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;

        int rSq = radius * radius;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                int dx = x - center.getX();
                int dz = z - center.getZ();
                if (dx * dx + dz * dz > rSq) {
                    continue;
                }

                for (int y = minY; y <= maxY; y++) {
                    mutable.set(x, y, z);
                    if (world.getBlockState(mutable).isOf(Blocks.FIRE) || world.getBlockState(mutable).isOf(Blocks.SOUL_FIRE)) {
                        world.removeBlock(mutable, false);
                        count++;
                    }
                }
            }
        }

        // Также тушим всех горящих мобов и игроков в этой зоне
        Box entityBox = new Box(minX, minY, minZ, maxX, maxY, maxZ);
        List<Entity> entities = world.getOtherEntities(null, entityBox, Entity::isOnFire);
        for (Entity entity : entities) {
            entity.extinguish();
        }

        final int finalCount = count;
        final int finalEntities = entities.size();
        source.sendFeedback(
            () -> Text.literal(String.format("[BetterFire] Потушено %d блоков огня и %d горящих сущностей в радиусе %d блоков.", finalCount, finalEntities, radius)).formatted(Formatting.GREEN),
            true
        );

        return count;
    }

    private static int extinguishAllLoaded(ServerCommandSource source, ServerWorld world) {
        int count = 0;
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        // Проходим по всем чанкам, которые сейчас загружены у игроков
        for (var player : world.getPlayers()) {
            BlockPos playerPos = player.getBlockPos();
            int chunkRadius = 160; // 10 чанков вокруг каждого игрока
            for (int x = playerPos.getX() - chunkRadius; x <= playerPos.getX() + chunkRadius; x++) {
                for (int z = playerPos.getZ() - chunkRadius; z <= playerPos.getZ() + chunkRadius; z++) {
                    for (int y = world.getBottomY(); y <= world.getTopY(); y++) {
                        mutable.set(x, y, z);
                        if (world.isChunkLoaded(x >> 4, z >> 4) && (world.getBlockState(mutable).isOf(Blocks.FIRE) || world.getBlockState(mutable).isOf(Blocks.SOUL_FIRE))) {
                            world.removeBlock(mutable, false);
                            count++;
                        }
                    }
                }
            }
        }

        // Потушить всех горящих мобов во всем мире
        for (Entity entity : world.iterateEntities()) {
            if (entity.isOnFire()) {
                entity.extinguish();
            }
        }

        final int finalCount = count;
        source.sendFeedback(
            () -> Text.literal(String.format("[BetterFire] Успешно потушено %d блоков огня во всем загруженном мире!", finalCount)).formatted(Formatting.GOLD),
            true
        );

        return count;
    }
}
