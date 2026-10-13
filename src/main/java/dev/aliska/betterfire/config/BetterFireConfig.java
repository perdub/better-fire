package dev.aliska.betterfire.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class BetterFireConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("betterfire.json").toFile();

    // Скорость и частота тиков огня (в ванилле 30 + rand(10))
    // Было 6 + 6 (очень бешено), теперь сбалансировано: 12 + 10 (~ в 2 раза быстрее ваниллы вместо 5)
    public int fireTickMinDelay = 12;
    public int fireTickRandomDelay = 10;

    // Множитель попыток распространения за тик
    public int extraSpreadAttempts = 2;

    // Сгорание дерна (Grass Block -> Coarse Dirt / Dirt)
    public boolean grassBlocksCatchFire = true;
    public double grassBlockBurnToDirtChance = 0.25;

    // Разлёт искр (ember/sparks)
    public boolean enableSparks = true;
    public double sparkChancePerTick = 0.12;
    public int sparkRangeHorizontal = 8;
    public int sparkRangeVertical = 4;
    public int maxSparksPerFireTick = 2;

    // Лимиты и оптимизация искр на серверном тике
    public int maxSparksPerWorldTick = 25; // Максимальное число искр за 1 игровой тик на мир
    public int maxFiresPerChunk = 64;      // Лимит блоков огня в одном чанке для защиты от лагов
    public boolean limitSparksInDenseFire = true; // Если вокруг уже сплошной огонь, не спавнить лишние искры

    // Огненный след от горящих мобов
    public boolean mobFireTrail = true;
    public double mobTrailIgniteChance = 0.25;
    public int mobTrailIntervalTicks = 15;

    // Защитные полосы (Firebreaks): земля, вспаханная земля (Farmland), тропинки (Dirt Path) не горят
    public boolean farmlandImmune = true;
    public boolean dirtPathImmune = true;

    private static BetterFireConfig INSTANCE;

    public static BetterFireConfig get() {
        if (INSTANCE == null) {
            load();
        }
        return INSTANCE;
    }

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                INSTANCE = GSON.fromJson(reader, BetterFireConfig.class);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (INSTANCE == null) {
            INSTANCE = new BetterFireConfig();
        }
        save();
    }

    public static void save() {
        if (INSTANCE == null) {
            INSTANCE = new BetterFireConfig();
        }
        try {
            CONFIG_FILE.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
