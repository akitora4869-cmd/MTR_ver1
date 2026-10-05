package Rin.TRPGCharacter;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * ドリームランド専用の自然スポーン制御。
 *
 * - ドリームランドでは通常Mobの自然発生を遮断。
 * - 代わりに登録済みのドリームランド神話生物6種を抽選して自然発生。
 * - KP/管理者によるCOMMAND/CUSTOM/SPAWNER_EGG召喚は妨げない。
 */
public class DreamlandsMobManager implements Listener {

    private static final List<String> DREAMLAND_IDS = List.of(
            "nightgaunt",
            "gug",
            "moon_beast",
            "zoog",
            "spider_of_leng",
            "shantak"
    );

    private final Plugin plugin;
    private final DreamlandsManager dreamlandsManager;
    private final MythosManager mythosManager;
    private final CharacterManager characterManager;
    private final Random random = new Random();

    private BukkitTask task;

    public DreamlandsMobManager(Plugin plugin,
                                DreamlandsManager dreamlandsManager,
                                MythosManager mythosManager,
                                CharacterManager characterManager) {
        this.plugin = plugin;
        this.dreamlandsManager = dreamlandsManager;
        this.mythosManager = mythosManager;
        this.characterManager = characterManager;
    }

    public void start() {
        shutdown();

        long interval = Math.max(
                20L,
                plugin.getConfig().getLong("dreamlands.mobs.check-interval-ticks", 100L)
        );

        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tickNaturalSpawns,
                interval,
                interval
        );
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("dreamlands.mobs.enabled", true);
    }

    public double getSpawnRatePercent() {
        return clamp(plugin.getConfig().getDouble("dreamlands.mobs.spawn-rate-percent", 35.0));
    }

    public void setEnabled(boolean enabled) {
        plugin.getConfig().set("dreamlands.mobs.enabled", enabled);
        plugin.saveConfig();
    }

    public void setSpawnRatePercent(double percent) {
        plugin.getConfig().set("dreamlands.mobs.spawn-rate-percent", clamp(percent));
        plugin.saveConfig();
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!dreamlandsManager.isDreamlands(event.getLocation().getWorld())) {
            return;
        }

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();

        // KPコマンド、プラグイン召喚、スポーンエッグは手動演出用として許可。
        if (reason == CreatureSpawnEvent.SpawnReason.CUSTOM
                || reason == CreatureSpawnEvent.SpawnReason.COMMAND
                || reason == CreatureSpawnEvent.SpawnReason.SPAWNER_EGG) {
            return;
        }

        // それ以外は通常Mobの自然発生・スポナー・増援等を含めて遮断。
        event.setCancelled(true);
    }

    private void tickNaturalSpawns() {
        if (!isEnabled() || !dreamlandsManager.isEnabled()) {
            return;
        }
        if (plugin.getTimeStopManager() != null
                && plugin.getTimeStopManager().isStopped()) {
            return;
        }

        World world = dreamlandsManager.getDreamlandsWorld();
        if (world == null) {
            return;
        }

        int maxNear = Math.max(
                1,
                plugin.getConfig().getInt("dreamlands.mobs.max-near-player", 6)
        );
        double minDistance = Math.max(
                8.0,
                plugin.getConfig().getDouble("dreamlands.mobs.min-spawn-distance", 18.0)
        );
        double maxDistance = Math.max(
                minDistance + 4.0,
                plugin.getConfig().getDouble("dreamlands.mobs.max-spawn-distance", 34.0)
        );

        List<Player> players = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (player.isOnline()
                    && player.getGameMode() != org.bukkit.GameMode.SPECTATOR
                    && !characterManager.isDeadCharacter(player)) {
                players.add(player);
            }
        }

        for (Player player : players) {
            if (random.nextDouble() * 100.0 >= getSpawnRatePercent()) {
                continue;
            }

            int nearby = 0;
            for (org.bukkit.entity.Entity entity :
                    player.getNearbyEntities(maxDistance, maxDistance, maxDistance)) {
                if (mythosManager.getDefinition(entity) != null) {
                    nearby++;
                }
            }
            if (nearby >= maxNear) {
                continue;
            }

            Location spawn = findSpawnLocation(player, minDistance, maxDistance);
            if (spawn == null) {
                continue;
            }

            String id = pickCreatureId(spawn);
            mythosManager.summonAt(spawn, id, true);
        }
    }

    private String pickCreatureId(Location location) {
        DreamlandsRegion region = DreamlandsRegion.at(
                location.getWorld().getSeed(),
                location.getBlockX(),
                location.getBlockZ()
        );

        int roll = random.nextInt(100);

        return switch (region) {
            // 安全寄り。ズーグ中心で、空からの遭遇が少しある。
            case MOON_MEADOW ->
                    roll < 65 ? "zoog"
                            : roll < 85 ? "nightgaunt"
                            : "shantak";

            // ズーグの主要生息地。木々の間にレンの蜘蛛やガグも混ざる。
            case ENCHANTED_FOREST ->
                    roll < 50 ? "zoog"
                            : roll < 68 ? "spider_of_leng"
                            : roll < 82 ? "nightgaunt"
                            : roll < 94 ? "gug"
                            : "shantak";

            // 崖・裂け目の主役はレンの蜘蛛とガグ。
            case MOSS_RAVINE ->
                    roll < 48 ? "spider_of_leng"
                            : roll < 73 ? "gug"
                            : roll < 90 ? "nightgaunt"
                            : "zoog";

            // 湿地は地上型の危険生物を濃くする。
            case OTHERWORLD_SWAMP ->
                    roll < 30 ? "moon_beast"
                            : roll < 56 ? "spider_of_leng"
                            : roll < 80 ? "zoog"
                            : "nightgaunt";

            // 菌類林は小型生物が多いが、奥には大型もいる。
            case FUNGAL_GROVE ->
                    roll < 38 ? "zoog"
                            : roll < 61 ? "moon_beast"
                            : roll < 82 ? "spider_of_leng"
                            : "gug";

            // 開けた湖上空は飛行生物の領域。
            case MOONLAKE ->
                    roll < 48 ? "shantak"
                            : roll < 83 ? "nightgaunt"
                            : "moon_beast";

            // 地下性・暗所性を強くした地域。
            case GLOW_CAVES ->
                    roll < 47 ? "gug"
                            : roll < 82 ? "spider_of_leng"
                            : "zoog";

            // 従来の荒野は最も危険な混成域。
            case BARRENS ->
                    roll < 30 ? "nightgaunt"
                            : roll < 54 ? "shantak"
                            : roll < 75 ? "gug"
                            : roll < 95 ? "moon_beast"
                            : "spider_of_leng";
        };
    }

    private Location findSpawnLocation(Player player,
                                       double minDistance,
                                       double maxDistance) {
        World world = player.getWorld();

        for (int attempt = 0; attempt < 14; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = minDistance
                    + random.nextDouble() * (maxDistance - minDistance);

            int x = (int) Math.floor(player.getLocation().getX() + Math.cos(angle) * distance);
            int z = (int) Math.floor(player.getLocation().getZ() + Math.sin(angle) * distance);

            DreamlandsRegion region = DreamlandsRegion.at(world.getSeed(), x, z);
            int highest = world.getHighestBlockYAt(x, z);

            // 月影の湖は飛行生物用に水面上へ直接スポーン可能。
            if (region == DreamlandsRegion.MOONLAKE) {
                int airY = Math.min(world.getMaxHeight() - 4, highest + 10 + random.nextInt(8));
                Block air = world.getBlockAt(x, airY, z);
                if (air.isPassable()) {
                    return new Location(world, x + 0.5, airY, z + 0.5);
                }
                continue;
            }

            // 水面を含む地域でも、水の下の固い地面まで探索する。
            int groundY = highest;
            while (groundY > world.getMinHeight() + 2
                    && !world.getBlockAt(x, groundY, z).getType().isSolid()) {
                groundY--;
            }

            int y = groundY + 1;
            if (y <= world.getMinHeight() + 2 || y >= world.getMaxHeight() - 3) {
                continue;
            }

            Block feet = world.getBlockAt(x, y, z);
            Block head = world.getBlockAt(x, y + 1, z);
            Block ground = world.getBlockAt(x, groundY, z);

            if (!feet.isPassable() || !head.isPassable()) {
                continue;
            }
            if (!ground.getType().isSolid() || ground.getType() == Material.BEDROCK) {
                continue;
            }

            return new Location(world, x + 0.5, y, z + 0.5);
        }

        return null;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }
}
