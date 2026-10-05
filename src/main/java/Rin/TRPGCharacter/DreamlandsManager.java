package Rin.TRPGCharacter;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import io.papermc.paper.event.player.PlayerDeepSleepEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.UUID;

/**
 * ドリームランド専用ワールドと、睡眠による確率転移・帰還地点を管理する。
 *
 * Paper 1.20.1 では独自DimensionTypeを直接登録せず、専用Worldを
 * 「ドリームランド・ディメンション」として扱うことで安定性を優先する。
 */
public class DreamlandsManager implements Listener {

    private final Plugin plugin;
    private final SessionManager sessionManager;
    private final File dataFile;
    private final Random random = new Random();
    private YamlConfiguration data;

    public DreamlandsManager(Plugin plugin, SessionManager sessionManager) {
        this.plugin = plugin;
        this.sessionManager = sessionManager;
        this.dataFile = new File(plugin.getDataFolder(), "dreamlands.yml");
        this.data = YamlConfiguration.loadConfiguration(dataFile);
        migrateTerrainSettings();
        ensureWorldLoaded();
    }

    public void reload() {
        data = YamlConfiguration.loadConfiguration(dataFile);
        if (isEnabled()) {
            ensureWorldLoaded();
        }
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("dreamlands.enabled", true);
    }

    public double getSleepChance() {
        return clampChance(plugin.getConfig().getDouble("dreamlands.sleep-teleport-chance", 10.0));
    }

    public boolean requiresActiveSession() {
        return plugin.getConfig().getBoolean("dreamlands.require-active-session", true);
    }

    private void migrateTerrainSettings() {
        int terrainVersion = plugin.getConfig().getInt("dreamlands.terrain-version", 0);
        String configuredName = plugin.getConfig().getString("dreamlands.world-name", "dreamlands");

        // v0 -> v1: 旧標準ワールドから神話地形へ。
        if (terrainVersion < 1 && "dreamlands".equalsIgnoreCase(configuredName)) {
            configuredName = "dreamlands_mythos";
            plugin.getConfig().set("dreamlands.world-name", configuredName);
            plugin.getConfig().set("dreamlands.terrain-version", 1);
            terrainVersion = 1;
        } else if (terrainVersion < 1) {
            plugin.getConfig().set("dreamlands.terrain-version", 1);
            terrainVersion = 1;
        }

        // v1 -> v2: 自然地域版。
        // 既存のdreamlands_mythosは削除せず、新ワールドへ安全に移行する。
        // KPが独自world-nameを指定している場合は勝手に名前を変えない。
        if (terrainVersion < 2 && "dreamlands_mythos".equalsIgnoreCase(configuredName)) {
            plugin.getConfig().set("dreamlands.world-name", "dreamlands_nature");
            plugin.getConfig().set("dreamlands.terrain-version", 2);
            plugin.saveConfig();
            plugin.getLogger().info(
                    "ドリームランド地形v2（自然地域）へ移行します。"
                            + "旧world『dreamlands_mythos』は削除せず、"
                            + "新world『dreamlands_nature』を使用します。"
            );
        } else if (terrainVersion < 2) {
            plugin.getConfig().set("dreamlands.terrain-version", 2);
            plugin.saveConfig();
        }
    }

    public String getWorldName() {
        String name = plugin.getConfig().getString("dreamlands.world-name", "dreamlands");
        return name == null || name.isBlank() ? "dreamlands" : name.trim();
    }

    public World getDreamlandsWorld() {
        World world = plugin.getServer().getWorld(getWorldName());
        if (world == null && isEnabled()) {
            world = ensureWorldLoaded();
        }
        return world;
    }

    public boolean isDreamlands(World world) {
        return world != null && world.getName().equalsIgnoreCase(getWorldName());
    }

    public boolean canEnter(Player player, boolean ignoreChance) {
        if (!isEnabled()) return false;
        if (player == null || !player.isOnline()) return false;
        if (player.getGameMode() == GameMode.SPECTATOR) return false;
        if (requiresActiveSession() && !sessionManager.isActive()) return false;
        if (isDreamlands(player.getWorld())) return false;
        return ignoreChance || getSleepChance() > 0.0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeepSleep(PlayerDeepSleepEvent event) {
        Player player = event.getPlayer();

        // ドリームランド内で深く眠ると、保存していた現実側の地点へ戻る。
        if (isDreamlands(player.getWorld())) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && isDreamlands(player.getWorld())) {
                    returnToReality(player, false);
                }
            }, 10L);
            return;
        }

        if (!canEnter(player, false)) return;

        double chance = getSleepChance();
        if (chance <= 0.0 || random.nextDouble() * 100.0 >= chance) return;

        player.sendMessage(color("&5あなたは深い眠りへと落ちていく……"));
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.DARKNESS, 50, 0, false, false, false
        ));
        player.getWorld().playSound(
                player.getLocation(), Sound.AMBIENT_CAVE, 0.65f, 0.65f
        );

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            enterDreamlands(player, true);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // 帰還地点は再起動・再ログイン後も必要なので削除しない。
        save();
    }

    public boolean enterDreamlands(Player player, boolean fromSleep) {
        if (!canEnter(player, true)) {
            if (!isEnabled()) {
                player.sendMessage(color("&c現在、KP設定でドリームランドへの移動は無効です。"));
            } else if (requiresActiveSession() && !sessionManager.isActive()) {
                player.sendMessage(color("&cセッションが開始されていないため、ドリームランドへ移動できません。"));
            }
            return false;
        }

        World dream = ensureWorldLoaded();
        if (dream == null) {
            player.sendMessage(color("&cドリームランドの生成・読み込みに失敗しました。"));
            return false;
        }

        saveReturnLocation(player, player.getLocation());

        Location destination = getDreamSpawn(dream);
        player.leaveVehicle();
        if (player.isSleeping()) player.wakeup(false);

        if (!player.teleport(destination)) {
            player.sendMessage(color("&cドリームランドへの転移に失敗しました。"));
            return false;
        }

        player.setFallDistance(0.0f);
        player.sendMessage(color("&5見知らぬ星空。遠くに広がる、現実には存在しない大地。"));
        player.sendMessage(color("&d――あなたは夢の国へ足を踏み入れた。"));
        dream.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 0.55f);

        if (!fromSleep) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.DARKNESS, 40, 0, false, false, false
            ));
        }
        return true;
    }

    public boolean enterByCrystalizer(Player player) {
        if (!isEnabled()) {
            player.sendMessage(color("&c現在、KP設定でドリームランドへの移動は無効です。"));
            return false;
        }
        if (requiresActiveSession() && !sessionManager.isActive()) {
            player.sendMessage(color("&cセッションが開始されていないため、夢のクリスタライザーは道を開きません。"));
            return false;
        }
        player.sendMessage(color("&b結晶の内側に、眠りの向こうの風景が映り込む……"));
        return enterDreamlands(player, false);
    }

    public boolean forceEnter(Player player) {
        if (!isEnabled()) {
            player.sendMessage(color("&cドリームランド機能が無効です。先に /dreamland enable on を使用してください。"));
            return false;
        }
        // KPの強制転移はセッション必須設定を無視する。
        if (isDreamlands(player.getWorld())) return true;

        World dream = ensureWorldLoaded();
        if (dream == null) return false;

        saveReturnLocation(player, player.getLocation());
        player.leaveVehicle();
        if (player.isSleeping()) player.wakeup(false);
        boolean ok = player.teleport(getDreamSpawn(dream));
        if (ok) {
            player.setFallDistance(0.0f);
            player.sendMessage(color("&dKPの力によってドリームランドへ移動しました。"));
        }
        return ok;
    }

    public boolean returnToReality(Player player, boolean forced) {
        Location destination = getReturnLocation(player);
        if (destination == null) {
            World fallback = plugin.getServer().getWorlds().stream()
                    .filter(w -> !isDreamlands(w))
                    .findFirst()
                    .orElse(null);
            if (fallback == null) {
                player.sendMessage(color("&c帰還先のワールドが見つかりません。"));
                return false;
            }
            destination = fallback.getSpawnLocation();
        }

        player.leaveVehicle();
        if (player.isSleeping()) player.wakeup(false);
        boolean ok = player.teleport(destination);
        if (!ok) return false;

        player.setFallDistance(0.0f);
        clearReturnLocation(player);
        if (forced) {
            player.sendMessage(color("&eKPの力によって現実世界へ戻されました。"));
        } else {
            player.sendMessage(color("&7夢は薄れ、あなたの意識は現実へ引き戻されていく……"));
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.2f);
        return true;
    }

    public void setSleepChance(double chance) {
        plugin.getConfig().set("dreamlands.sleep-teleport-chance", clampChance(chance));
        plugin.saveConfig();
    }

    public void setEnabled(boolean enabled) {
        plugin.getConfig().set("dreamlands.enabled", enabled);
        plugin.saveConfig();
        if (enabled) ensureWorldLoaded();
    }

    private World ensureWorldLoaded() {
        World existing = plugin.getServer().getWorld(getWorldName());
        if (existing != null) return existing;
        if (!isEnabled()) return null;

        try {
            WorldCreator creator = new WorldCreator(getWorldName());
            creator.environment(World.Environment.NORMAL);
            creator.type(WorldType.NORMAL);
            creator.generator(new DreamlandsGenerator());
            creator.generateStructures(false);

            World world = creator.createWorld();
            if (world != null) {
                world.setSpawnFlags(true, true);
                world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                world.setStorm(false);
                world.setThundering(false);

                // ドリームランドはセッション時計・通常世界の時刻から独立した常夜。
                if (plugin.getConfig().getBoolean("dreamlands.always-night", true)) {
                    long fixedTime = plugin.getConfig().getLong("dreamlands.night-time", 18000L);
                    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                    world.setTime(Math.floorMod(fixedTime, 24000L));
                }

                // 原点周辺はジェネレーター側で安全な高台にしている。
                int spawnY = world.getHighestBlockYAt(0, 0) + 1;
                world.setSpawnLocation(0, spawnY, 0);
            }
            return world;
        } catch (Exception e) {
            plugin.getLogger().severe("ドリームランドワールドの生成に失敗しました: " + e.getMessage());
            return null;
        }
    }

    private Location getDreamSpawn(World world) {
        Location spawn = world.getSpawnLocation().clone();
        return spawn.add(0.5, 0.1, 0.5);
    }

    private void saveReturnLocation(Player player, Location loc) {
        String base = "return." + player.getUniqueId();
        data.set(base + ".world", loc.getWorld().getUID().toString());
        data.set(base + ".x", loc.getX());
        data.set(base + ".y", loc.getY());
        data.set(base + ".z", loc.getZ());
        data.set(base + ".yaw", loc.getYaw());
        data.set(base + ".pitch", loc.getPitch());
        save();
    }

    private Location getReturnLocation(Player player) {
        String base = "return." + player.getUniqueId();
        String worldId = data.getString(base + ".world");
        if (worldId == null) return null;

        World world;
        try {
            world = plugin.getServer().getWorld(UUID.fromString(worldId));
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (world == null) return null;

        return new Location(
                world,
                data.getDouble(base + ".x"),
                data.getDouble(base + ".y"),
                data.getDouble(base + ".z"),
                (float) data.getDouble(base + ".yaw"),
                (float) data.getDouble(base + ".pitch")
        );
    }

    private void clearReturnLocation(Player player) {
        data.set("return." + player.getUniqueId(), null);
        save();
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("dreamlands.yml の保存に失敗しました: " + e.getMessage());
        }
    }

    private double clampChance(double chance) {
        return Math.max(0.0, Math.min(100.0, chance));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
