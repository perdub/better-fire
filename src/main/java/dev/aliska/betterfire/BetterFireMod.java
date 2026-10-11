package dev.aliska.betterfire;

import dev.aliska.betterfire.config.BetterFireConfig;
import net.fabricmc.api.ModInitializer;
import net.minecraft.block.Blocks;
import net.minecraft.block.FireBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BetterFireMod implements ModInitializer {
    public static final String MOD_ID = "betterfire";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[BetterFire] Initializing server-side realistic wildfire mod...");
        BetterFireConfig.load();

        // Регистрируем горючесть дёрна/травы, если включено в конфиге
        if (BetterFireConfig.get().grassBlocksCatchFire) {
            FireBlock fireBlock = (FireBlock) Blocks.FIRE;
            // burnChance, spreadChance: трава быстро загорается и передаёт пламя
            fireBlock.registerFlammableBlock(Blocks.GRASS_BLOCK, 15, 30);
            fireBlock.registerFlammableBlock(Blocks.PODZOL, 15, 25);
            fireBlock.registerFlammableBlock(Blocks.MYCELIUM, 15, 25);
        }

        LOGGER.info("[BetterFire] Wildfire mechanics active! Firebreaks (farmland, paths, tilled soil, trenches) are effective against fire spreading.");
    }
}
