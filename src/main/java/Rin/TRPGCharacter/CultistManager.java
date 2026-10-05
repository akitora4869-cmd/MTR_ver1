package Rin.TRPGCharacter;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Light;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vindicator;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

/**
 * シナリオ「白い部屋」祭壇用の狂信者管理。
 * 通常時は祈り状態（AI停止）。プレイヤーを視認すると同じ祭壇グループが一斉に戦闘へ移行する。
 */
public class CultistManager implements Listener, CommandExecutor, TabCompleter {

    private static final String ROLE_NORMAL = "normal";
    private static final String ROLE_PRIEST = "priest";
    private static final String GROUP_ALTAR = "altar";

    private final Plugin plugin;
    private final File file;
    private final NamespacedKey roleKey;
    private final NamespacedKey groupKey;
    private final NamespacedKey alertedKey;
    private final NamespacedKey reinforcementKey;
    private YamlConfiguration config;
    private BukkitTask scanTask;
    private BukkitTask alarmTask;
    private BukkitTask reinforcementTask;
    private final Map<Location, BlockData> alarmLightOriginals = new HashMap<>();
    private final Map<UUID, StealthState> stealthStates = new HashMap<>();
    private final Map<UUID, List<DoorLockManager.DoorLockSnapshot>> alarmDoorSnapshots = new HashMap<>();
    private final Set<UUID> reinforcementTriggered = new HashSet<>();

    private static final class StealthState {
        long hideUntil;
        double hideRangeMultiplier = 1.0;
        double hideFovMultiplier = 1.0;
        long sneakUntil;
        double sneakRangeMultiplier = 1.0;

        boolean active(long now) {
            return hideUntil > now || sneakUntil > now;
        }
    }

    public CultistManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cultists.yml");
        this.roleKey = new NamespacedKey(plugin, "cultist_role");
        this.groupKey = new NamespacedKey(plugin, "cultist_group");
        this.alertedKey = new NamespacedKey(plugin, "cultist_alerted");
        this.reinforcementKey = new NamespacedKey(plugin, "cultist_reinforcement");

        if (!file.exists()) {
            plugin.saveResource("cultists.yml", false);
        }
        reload();
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
        applyLoadedCultists();
    }

    public void start() {
        shutdown();
        long period = Math.max(2L, config.getLong("detection.scan-period-ticks", 5L));
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::scanCultists, 20L, period);
    }

    public void shutdown() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        stopAlarmEffects();
        cancelReinforcementTask();
        restoreAlarmDoors();
    }

    public EnemyDefinition getEnemyDefinition(Entity entity) {
        String role = getRole(entity);
        if (role == null) {
            return null;
        }

        String base = "roles." + role;
        return new EnemyDefinition(
                "CULTIST_" + role.toUpperCase(Locale.ROOT),
                config.getString(base + ".name", role.equals(ROLE_PRIEST) ? "儀式司祭" : "狂信者"),
                Math.max(1, config.getInt(base + ".hp", role.equals(ROLE_PRIEST) ? 13 : 11)),
                clamp(config.getInt(base + ".hit", role.equals(ROLE_PRIEST) ? 50 : 45), 0, 100),
                config.getString(base + ".damage", role.equals(ROLE_PRIEST) ? "1d6" : "1d4"),
                Math.max(0, config.getInt(base + ".armor", 0)),
                config.getBoolean(base + ".player-armor", true)
        );
    }

    public boolean isCultist(Entity entity) {
        return getRole(entity) != null;
    }

    private String getRole(Entity entity) {
        if (entity == null) {
            return null;
        }
        return entity.getPersistentDataContainer().get(roleKey, PersistentDataType.STRING);
    }

    private String getGroup(Entity entity) {
        return entity.getPersistentDataContainer().get(groupKey, PersistentDataType.STRING);
    }

    private boolean isAlerted(Entity entity) {
        Byte value = entity.getPersistentDataContainer().get(alertedKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    private void scanCultists() {
        if (!config.getBoolean("enabled", true) || plugin.getTimeStopManager().isStopped()) {
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            for (LivingEntity living : world.getLivingEntities()) {
                if (!(living instanceof Mob cultist) || !isCultist(cultist) || isAlerted(cultist)) {
                    continue;
                }

                Player spotted = findVisiblePlayer(cultist);
                if (spotted != null) {
                    String group = getGroup(cultist);
                    alertGroup(world, group == null ? GROUP_ALTAR : group, spotted, true);
                }
            }
        }
    }

    private Player findVisiblePlayer(Mob cultist) {
        double normalRange = Math.max(1.0, config.getDouble("detection.range", 10.0));
        double sneakingRange = Math.max(1.0, config.getDouble("detection.sneaking-range", 5.0));
        double fov = clampDouble(config.getDouble("detection.field-of-view-degrees", 105.0), 1.0, 360.0);
        double minDot = Math.cos(Math.toRadians(fov / 2.0));

        Player best = null;
        double bestDistance = Double.MAX_VALUE;
        Location eye = cultist.getEyeLocation();
        Vector forward = eye.getDirection().normalize();

        for (Player player : cultist.getWorld().getPlayers()) {
            if (player.getGameMode().name().equals("SPECTATOR") || !player.isOnline() || player.isDead()) {
                continue;
            }
            if (plugin.getCharacterManagerInternal() != null && plugin.getCharacterManagerInternal().isDeadCharacter(player)) {
                continue;
            }

            double range = player.isSneaking() ? sneakingRange : normalRange;
            double playerMinDot = minDot;
            long now = System.currentTimeMillis();
            StealthState stealth = stealthStates.get(player.getUniqueId());
            if (stealth != null) {
                if (stealth.hideUntil > now) {
                    range *= stealth.hideRangeMultiplier;
                    double adjustedFov = clampDouble(fov * stealth.hideFovMultiplier, 1.0, 360.0);
                    playerMinDot = Math.cos(Math.toRadians(adjustedFov / 2.0));
                }
                if (stealth.sneakUntil > now) {
                    range *= stealth.sneakRangeMultiplier;
                }
                if (!stealth.active(now)) {
                    stealthStates.remove(player.getUniqueId());
                }
            }
            range = Math.max(0.75, range);
            double distance = cultist.getLocation().distance(player.getLocation());
            if (distance > range || distance >= bestDistance) {
                continue;
            }
            if (!cultist.hasLineOfSight(player)) {
                continue;
            }

            Vector toPlayer = player.getEyeLocation().toVector().subtract(eye.toVector());
            if (toPlayer.lengthSquared() < 0.0001) {
                return player;
            }
            toPlayer.normalize();
            if (fov < 359.9 && forward.dot(toPlayer) < playerMinDot) {
                continue;
            }

            best = player;
            bestDistance = distance;
        }
        return best;
    }

    /**
     * 隠れる / 忍び歩きの通常技能判定結果を狂信者索敵へ反映する。
     * RollManager から全結果（成功・失敗・ファンブル）で呼び出される。
     */
    public void onStealthSkillCheck(Player player, String skillId, CheckResult result) {
        if (player == null || skillId == null || result == null) {
            return;
        }
        boolean hide = "hide".equalsIgnoreCase(skillId);
        boolean sneak = "sneak".equalsIgnoreCase(skillId);
        if (!hide && !sneak) {
            return;
        }

        long now = System.currentTimeMillis();
        StealthState state = stealthStates.computeIfAbsent(player.getUniqueId(), id -> new StealthState());
        String base = "stealth-skills." + (hide ? "hide" : "sneak");

        if (result.isSuccess()) {
            String tier = result == CheckResult.CRITICAL ? "critical"
                    : result == CheckResult.SPECIAL ? "special" : "success";
            int seconds = Math.max(1, config.getInt(base + "." + tier + ".seconds",
                    result == CheckResult.CRITICAL ? 30 : result == CheckResult.SPECIAL ? 25 : 20));
            double rangeMultiplier = clampDouble(config.getDouble(base + "." + tier + ".range-multiplier",
                    hide ? (result == CheckResult.CRITICAL ? 0.15 : result == CheckResult.SPECIAL ? 0.30 : 0.45)
                            : (result == CheckResult.CRITICAL ? 0.30 : result == CheckResult.SPECIAL ? 0.45 : 0.60)), 0.0, 2.0);

            if (hide) {
                state.hideUntil = now + seconds * 1000L;
                state.hideRangeMultiplier = rangeMultiplier;
                state.hideFovMultiplier = clampDouble(config.getDouble(base + "." + tier + ".fov-multiplier",
                        result == CheckResult.CRITICAL ? 0.45 : result == CheckResult.SPECIAL ? 0.65 : 0.80), 0.05, 2.0);
            } else {
                state.sneakUntil = now + seconds * 1000L;
                state.sneakRangeMultiplier = rangeMultiplier;
            }

            player.sendMessage(color(config.getString("messages.stealth-success",
                    "&2[隠密] &f技能成功。&a狂信者に発見されにくくなった。")
                    .replace("{skill}", hide ? "隠れる" : "忍び歩き")
                    .replace("{seconds}", String.valueOf(seconds))));
            return;
        }

        // 失敗した技能の有効効果だけ解除する。もう片方の技能効果は残す。
        if (hide) {
            state.hideUntil = 0L;
            state.hideRangeMultiplier = 1.0;
            state.hideFovMultiplier = 1.0;
        } else {
            state.sneakUntil = 0L;
            state.sneakRangeMultiplier = 1.0;
        }
        if (!state.active(now)) {
            stealthStates.remove(player.getUniqueId());
        }

        if (result == CheckResult.FUMBLE) {
            double noiseRadius = Math.max(1.0, config.getDouble(base + ".fumble-alert-radius", 16.0));
            boolean alerted = alertNearbyFromNoise(player, noiseRadius);
            player.sendMessage(color(config.getString(alerted ? "messages.stealth-fumble-alert" : "messages.stealth-fumble",
                    alerted
                            ? "&4☠ 大きな物音を立てた！ 近くの狂信者がこちらに気付いた！"
                            : "&4☠ 隠密に失敗し、大きな物音を立ててしまった。")));
        }
    }

    private boolean alertNearbyFromNoise(Player player, double radius) {
        Mob nearest = null;
        double best = radius * radius;
        for (LivingEntity living : player.getWorld().getLivingEntities()) {
            if (!(living instanceof Mob mob) || !isCultist(mob) || isAlerted(mob)) {
                continue;
            }
            double d = mob.getLocation().distanceSquared(player.getLocation());
            if (d <= best) {
                best = d;
                nearest = mob;
            }
        }
        if (nearest == null) {
            return false;
        }
        String group = getGroup(nearest);
        alertGroup(player.getWorld(), group == null ? GROUP_ALTAR : group, player, true);
        return true;
    }

    public String getStealthStatus(Player player) {
        StealthState state = stealthStates.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (state == null || !state.active(now)) {
            stealthStates.remove(player.getUniqueId());
            return "&7隠密技能効果なし";
        }
        long hideSeconds = Math.max(0L, (state.hideUntil - now + 999L) / 1000L);
        long sneakSeconds = Math.max(0L, (state.sneakUntil - now + 999L) / 1000L);
        return "&a隠れる:" + hideSeconds + "秒 &b忍び歩き:" + sneakSeconds + "秒";
    }

    private void alertGroup(World world, String group, Player firstTarget, boolean announce) {
        boolean changed = false;
        for (LivingEntity living : world.getLivingEntities()) {
            if (!(living instanceof Mob mob) || !isCultist(mob)) {
                continue;
            }
            if (!group.equalsIgnoreCase(String.valueOf(getGroup(mob)))) {
                continue;
            }

            if (!isAlerted(mob)) {
                changed = true;
            }
            setAlerted(mob, true);
            Player target = nearestValidPlayer(mob, firstTarget);
            if (target != null) {
                mob.setTarget(target);
            }
        }

        if (announce && changed) {
            double radius = Math.max(10.0, config.getDouble("messages.alert-radius", 30.0));
            Location center = firstTarget.getLocation();
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= radius * radius) {
                    player.sendMessage(color(config.getString("messages.alert", "&4&l『器が逃げたぞ！』")));
                }
            }
            startAlarmEffects(world, firstTarget);
            lockAlarmDoors(world);
            scheduleReinforcements(world, group, firstTarget);
        }
    }

    private void lockAlarmDoors(World world) {
        if (!config.getBoolean("alarm-door-lock.enabled", true)) return;
        UUID worldId = world.getUID();
        if (alarmDoorSnapshots.containsKey(worldId)) return;

        DoorLockManager lockManager = plugin.getDoorLockManager();
        if (lockManager == null) return;
        boolean hardlock = config.getBoolean("alarm-door-lock.hardlock", false);
        String keyId = config.getString("alarm-door-lock.key-id", "");
        if (keyId != null && keyId.isBlank()) keyId = null;

        List<DoorLockManager.DoorLockSnapshot> snapshots = new ArrayList<>();
        for (Map<?, ?> map : config.getMapList("alarm-door-lock.doors")) {
            int x = (int) Math.round(number(map.get("x"), 0));
            int y = (int) Math.round(number(map.get("y"), 0));
            int z = (int) Math.round(number(map.get("z"), 0));
            DoorLockManager.DoorLockSnapshot snapshot = lockManager.setScenarioLock(world, x, y, z, hardlock, keyId);
            if (snapshot != null) snapshots.add(snapshot);
        }
        if (!snapshots.isEmpty()) {
            alarmDoorSnapshots.put(worldId, snapshots);
            double radius = Math.max(8.0, config.getDouble("alarm.player-radius", 32.0));
            Location center = new Location(world, config.getDouble("altar.center.x", 12.0),
                    config.getDouble("altar.y", -58.0), config.getDouble("altar.center.z", -5.0));
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= radius * radius) {
                    player.sendMessage(color(config.getString("messages.alarm-lock", "&4[警報] &c隔離扉が自動施錠された！")));
                    player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 0.9f, 0.7f);
                }
            }
        }
    }

    private void restoreAlarmDoors() {
        DoorLockManager lockManager = plugin.getDoorLockManager();
        if (lockManager == null || alarmDoorSnapshots.isEmpty()) return;
        for (Map.Entry<UUID, List<DoorLockManager.DoorLockSnapshot>> entry : new ArrayList<>(alarmDoorSnapshots.entrySet())) {
            World world = Bukkit.getWorld(entry.getKey());
            if (world == null) continue;
            for (DoorLockManager.DoorLockSnapshot snapshot : entry.getValue()) {
                lockManager.restoreScenarioLock(world, snapshot);
            }
        }
        alarmDoorSnapshots.clear();
    }

    private void restoreAlarmDoors(World world) {
        if (world == null) return;
        List<DoorLockManager.DoorLockSnapshot> snapshots = alarmDoorSnapshots.remove(world.getUID());
        if (snapshots == null) return;
        DoorLockManager lockManager = plugin.getDoorLockManager();
        if (lockManager == null) return;
        for (DoorLockManager.DoorLockSnapshot snapshot : snapshots) {
            lockManager.restoreScenarioLock(world, snapshot);
        }
    }

    private void scheduleReinforcements(World world, String group, Player firstTarget) {
        if (!config.getBoolean("reinforcements.enabled", true)) return;
        UUID worldId = world.getUID();
        if (!reinforcementTriggered.add(worldId)) return;
        cancelReinforcementTask();
        long delay = Math.max(0L, config.getLong("reinforcements.delay-ticks", 30L));
        UUID targetId = firstTarget == null ? null : firstTarget.getUniqueId();
        reinforcementTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            reinforcementTask = null;
            Player preferred = targetId == null ? null : Bukkit.getPlayer(targetId);
            spawnReinforcementWave(world, group, preferred);
        }, delay);
    }

    private void spawnReinforcementWave(World world, String group, Player preferred) {
        if (world == null || !config.getBoolean("reinforcements.enabled", true)) return;
        int maxAlive = Math.max(0, config.getInt("reinforcements.max-alive", 3));
        int alive = countAliveReinforcements(world);
        int perWave = Math.max(0, config.getInt("reinforcements.per-wave", 3));
        int canSpawn = Math.min(perWave, Math.max(0, maxAlive - alive));
        if (canSpawn <= 0) return;

        List<Map<?, ?>> points = config.getMapList("reinforcements.spawn-points");
        if (points.isEmpty()) return;
        Location center = new Location(world, config.getDouble("altar.center.x", 12.0),
                config.getDouble("altar.y", -58.0), config.getDouble("altar.center.z", -5.0));
        int spawned = 0;
        for (Map<?, ?> map : points) {
            if (spawned >= canSpawn) break;
            Location loc = new Location(world,
                    number(map.get("x"), 6.5), number(map.get("y"), -58.0), number(map.get("z"), -12.5));
            Vindicator mob = spawnCultist(loc, ROLE_NORMAL, group == null ? GROUP_ALTAR : group, center);
            mob.getPersistentDataContainer().set(reinforcementKey, PersistentDataType.BYTE, (byte) 1);
            setAlerted(mob, true);
            Player target = nearestValidPlayer(mob, preferred);
            if (target != null) mob.setTarget(target);
            spawned++;
        }
        if (spawned > 0) {
            String message = color(config.getString("messages.reinforcements", "&4[警報] &c増援の狂信者が駆けつけた！"));
            for (Player player : world.getPlayers()) player.sendMessage(message);
            world.playSound(center, Sound.ENTITY_VINDICATOR_AMBIENT, 1.2f, 0.75f);
        }
    }

    private int countAliveReinforcements(World world) {
        int count = 0;
        for (LivingEntity living : world.getLivingEntities()) {
            Byte marker = living.getPersistentDataContainer().get(reinforcementKey, PersistentDataType.BYTE);
            if (marker != null && marker == (byte) 1 && !living.isDead()) count++;
        }
        return count;
    }

    private void cancelReinforcementTask() {
        if (reinforcementTask != null) {
            reinforcementTask.cancel();
            reinforcementTask = null;
        }
    }

    private void startAlarmEffects(World world, Player spotted) {
        if (!config.getBoolean("alarm.enabled", true)) {
            return;
        }
        stopAlarmEffects();

        int durationTicks = Math.max(20, config.getInt("alarm.duration-ticks", 120));
        int intervalTicks = Math.max(2, config.getInt("alarm.flash-interval-ticks", 10));
        double radius = Math.max(4.0, config.getDouble("alarm.player-radius", 32.0));
        Location center = new Location(world,
                config.getDouble("altar.center.x", 12.0),
                config.getDouble("alarm.center-y", -56.0),
                config.getDouble("altar.center.z", -5.0));

        if (config.getBoolean("alarm.title.enabled", true)) {
            String title = color(config.getString("alarm.title.title", "&4&l警報"));
            String subtitle = color(config.getString("alarm.title.subtitle", "&c発見された――逃げろ！"));
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= radius * radius) {
                    player.sendTitle(title, subtitle, 5, 35, 10);
                }
            }
        }

        List<Location> lights = alarmLightLocations(world);
        for (Location loc : lights) {
            Block block = loc.getBlock();
            if (block.getType() == Material.AIR || block.getType() == Material.LIGHT) {
                alarmLightOriginals.put(loc.clone(), block.getBlockData().clone());
            }
        }

        final int[] elapsed = {0};
        final boolean[] bright = {false};
        alarmTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (elapsed[0] >= durationTicks) {
                stopAlarmEffects();
                return;
            }
            bright[0] = !bright[0];
            setAlarmLights(bright[0]);
            playAlarmPulse(world, center, radius, bright[0]);
            elapsed[0] += intervalTicks;
        }, 0L, intervalTicks);
    }

    private List<Location> alarmLightLocations(World world) {
        List<Location> locations = new ArrayList<>();
        List<Map<?, ?>> configured = config.getMapList("alarm.lights");
        if (!configured.isEmpty()) {
            for (Map<?, ?> map : configured) {
                double x = number(map.get("x"), 12.0);
                double y = number(map.get("y"), -56.0);
                double z = number(map.get("z"), -5.0);
                locations.add(new Location(world, x, y, z));
            }
            return locations;
        }
        locations.add(new Location(world, 9, -56, -5));
        locations.add(new Location(world, 12, -56, -5));
        locations.add(new Location(world, 15, -56, -5));
        locations.add(new Location(world, 12, -56, -2));
        return locations;
    }

    private double number(Object value, double fallback) {
        return value instanceof Number n ? n.doubleValue() : fallback;
    }

    private void setAlarmLights(boolean bright) {
        int level = bright ? clamp(config.getInt("alarm.light-level-bright", 15), 0, 15)
                : clamp(config.getInt("alarm.light-level-dark", 0), 0, 15);
        for (Location loc : new ArrayList<>(alarmLightOriginals.keySet())) {
            Block block = loc.getBlock();
            if (block.getType() != Material.AIR && block.getType() != Material.LIGHT) {
                continue;
            }
            BlockData data = Bukkit.createBlockData(Material.LIGHT);
            if (data instanceof Light light) {
                light.setLevel(level);
            }
            block.setBlockData(data, false);
        }
    }

    private void playAlarmPulse(World world, Location center, double radius, boolean bright) {
        if (config.getBoolean("alarm.sound.enabled", true)) {
            float volume = (float) config.getDouble("alarm.sound.volume", 1.1);
            float pitch = bright ? 0.65f : 0.45f;
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= radius * radius) {
                    player.playSound(player.getLocation(), Sound.BLOCK_BELL_USE, volume, pitch);
                    if (bright) {
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
                    }
                }
            }
        }
        if (config.getBoolean("alarm.particles.enabled", true)) {
            int count = Math.max(1, config.getInt("alarm.particles.count", 30));
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(190, 0, 0), 1.4f);
            world.spawnParticle(Particle.REDSTONE, center.clone().add(0, 1.0, 0), count, 4.5, 1.5, 4.5, 0.0, dust);
        }
    }

    private void stopAlarmEffects() {
        if (alarmTask != null) {
            BukkitTask task = alarmTask;
            alarmTask = null;
            task.cancel();
        }
        if (!alarmLightOriginals.isEmpty()) {
            for (Map.Entry<Location, BlockData> entry : new ArrayList<>(alarmLightOriginals.entrySet())) {
                Block block = entry.getKey().getBlock();
                if (block.getType() == Material.LIGHT || block.getType() == Material.AIR) {
                    block.setBlockData(entry.getValue(), false);
                }
            }
            alarmLightOriginals.clear();
        }
    }

    private Player nearestValidPlayer(Mob mob, Player preferred) {
        if (preferred != null && preferred.isOnline() && !preferred.isDead()) {
            return preferred;
        }
        Player best = null;
        double distance = Double.MAX_VALUE;
        for (Player player : mob.getWorld().getPlayers()) {
            if (player.isDead() || player.getGameMode().name().equals("SPECTATOR")) {
                continue;
            }
            double d = mob.getLocation().distanceSquared(player.getLocation());
            if (d < distance) {
                distance = d;
                best = player;
            }
        }
        return best;
    }

    private void setAlerted(Mob mob, boolean alerted) {
        mob.getPersistentDataContainer().set(alertedKey, PersistentDataType.BYTE, alerted ? (byte) 1 : (byte) 0);
        mob.setAI(alerted);
        mob.setAware(alerted);
        if (!alerted) {
            mob.setTarget(null);
        }
    }

    private Vindicator spawnCultist(Location location, String role, String group, Location lookAt) {
        Vindicator mob = (Vindicator) location.getWorld().spawnEntity(location, EntityType.VINDICATOR);
        mob.getPersistentDataContainer().set(roleKey, PersistentDataType.STRING, role);
        mob.getPersistentDataContainer().set(groupKey, PersistentDataType.STRING, group);
        mob.getPersistentDataContainer().set(alertedKey, PersistentDataType.BYTE, (byte) 0);
        mob.setCustomName(color(config.getString("roles." + role + ".name", role.equals(ROLE_PRIEST) ? "儀式司祭" : "狂信者")));
        mob.setCustomNameVisible(config.getBoolean("appearance.name-visible", true));
        mob.setRemoveWhenFarAway(false);
        mob.setCanPickupItems(false);
        mob.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_AXE));
        mob.getEquipment().setItemInMainHandDropChance(0.0f);
        face(mob, lookAt);
        setAlerted(mob, false);
        plugin.getEnemyManager().applyBalance(mob);
        return mob;
    }

    private void face(Mob mob, Location lookAt) {
        if (lookAt == null) {
            return;
        }
        Location loc = mob.getLocation();
        Vector direction = lookAt.toVector().subtract(loc.toVector());
        if (direction.lengthSquared() > 0.0001) {
            loc.setDirection(direction);
            mob.teleport(loc);
        }
    }

    public int setupAltar(World world) {
        removeGroup(world, GROUP_ALTAR);
        double y = config.getDouble("altar.y", -58.0);
        Location center = new Location(world,
                config.getDouble("altar.center.x", 12.0),
                y,
                config.getDouble("altar.center.z", -5.0));

        List<Location> normals = List.of(
                new Location(world, 10.5, y, -5.5),
                new Location(world, 14.5, y, -5.5),
                new Location(world, 10.5, y, -2.5),
                new Location(world, 14.5, y, -2.5)
        );
        for (Location location : normals) {
            spawnCultist(location, ROLE_NORMAL, GROUP_ALTAR, center);
        }
        spawnCultist(new Location(world, 12.5, y, -6.5), ROLE_PRIEST, GROUP_ALTAR, center);
        return 5;
    }

    public int removeGroup(World world, String group) {
        int count = 0;
        for (Entity entity : new ArrayList<>(world.getEntities())) {
            if (isCultist(entity) && group.equalsIgnoreCase(String.valueOf(getGroup(entity)))) {
                entity.remove();
                count++;
            }
        }
        return count;
    }

    public int calmGroup(World world, String group) {
        int count = 0;
        Location center = new Location(world,
                config.getDouble("altar.center.x", 12.0),
                config.getDouble("altar.y", -58.0),
                config.getDouble("altar.center.z", -5.0));
        for (LivingEntity living : world.getLivingEntities()) {
            if (living instanceof Mob mob && isCultist(mob) && group.equalsIgnoreCase(String.valueOf(getGroup(mob)))) {
                setAlerted(mob, false);
                face(mob, center);
                count++;
            }
        }
        return count;
    }

    public int countGroup(World world, String group) {
        int count = 0;
        for (LivingEntity living : world.getLivingEntities()) {
            if (isCultist(living) && group.equalsIgnoreCase(String.valueOf(getGroup(living)))) {
                count++;
            }
        }
        return count;
    }

    private void applyLoadedCultists() {
        if (Bukkit.getServer() == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (World world : Bukkit.getWorlds()) {
                for (LivingEntity living : world.getLivingEntities()) {
                    if (!(living instanceof Mob mob) || !isCultist(mob)) {
                        continue;
                    }
                    mob.setRemoveWhenFarAway(false);
                    plugin.getEnemyManager().applyBalance(mob);
                    setAlerted(mob, isAlerted(mob));
                }
            }
        });
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        if (!isCultist(event.getEntity())) {
            return;
        }
        // Minecraft標準の斧・エメラルド等のドロップを抑止。シナリオ報酬は別途設定する。
        event.getDrops().clear();
        event.setDroppedExp(0);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはゲーム内で実行してください。");
            return true;
        }
        if (!player.hasPermission("trpg.admin") && !player.hasPermission("trpg.keeper")) {
            player.sendMessage(color("&cKPまたは管理者のみ使用できます。"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "setupaltar" -> {
                int count = setupAltar(player.getWorld());
                player.sendMessage(color("&a祭壇の狂信者を配置しました。 &f" + count + "体 &7(狂信者4＋儀式司祭1)"));
                player.sendMessage(color("&7通常時は祈り状態です。探索者を視認すると5体が一斉に戦闘へ移行します。"));
                return true;
            }
            case "spawn" -> {
                String role = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : ROLE_NORMAL;
                if (!role.equals(ROLE_NORMAL) && !role.equals(ROLE_PRIEST)) {
                    player.sendMessage(color("&c役割は normal または priest を指定してください。"));
                    return true;
                }
                spawnCultist(player.getLocation(), role, GROUP_ALTAR, player.getLocation().clone().add(player.getLocation().getDirection()));
                player.sendMessage(color("&a狂信者を現在位置に配置しました: &f" + role));
                return true;
            }
            case "alert" -> {
                alertGroup(player.getWorld(), GROUP_ALTAR, player, true);
                player.sendMessage(color("&c祭壇の狂信者を戦闘状態にし、警報演出を開始しました。"));
                return true;
            }
            case "alarmtest" -> {
                startAlarmEffects(player.getWorld(), player);
                player.sendMessage(color("&e警報・照明演出をテストしました。"));
                return true;
            }
            case "calm" -> {
                stopAlarmEffects();
                cancelReinforcementTask();
                restoreAlarmDoors(player.getWorld());
                reinforcementTriggered.remove(player.getWorld().getUID());
                int count = calmGroup(player.getWorld(), GROUP_ALTAR);
                player.sendMessage(color("&a祭壇の狂信者を祈り状態へ戻し、警報扉を復旧しました: &f" + count + "体"));
                return true;
            }
            case "clear" -> {
                cancelReinforcementTask();
                restoreAlarmDoors(player.getWorld());
                reinforcementTriggered.remove(player.getWorld().getUID());
                int count = removeGroup(player.getWorld(), GROUP_ALTAR);
                player.sendMessage(color("&a祭壇の狂信者・増援を削除し、警報扉を復旧しました: &f" + count + "体"));
                return true;
            }
            case "status" -> {
                int count = countGroup(player.getWorld(), GROUP_ALTAR);
                player.sendMessage(color("&6[祭壇狂信者] &f" + count + "体 &7/ 視認距離 "
                        + config.getDouble("detection.range", 10.0) + "m &7/ しゃがみ時 "
                        + config.getDouble("detection.sneaking-range", 5.0) + "m"));
                player.sendMessage(color("&6[隠密状態] " + getStealthStatus(player)));
                player.sendMessage(color("&6[警報] &7増援: &f" + countAliveReinforcements(player.getWorld())
                        + "体 &7/ 扉ロック: &f" + (alarmDoorSnapshots.containsKey(player.getWorld().getUID()) ? "作動中" : "待機")));
                return true;
            }
            case "reload" -> {
                reload();
                start();
                player.sendMessage(color("&a cultists.yml を再読み込みしました。"));
                return true;
            }
            default -> {
                sendHelp(player);
                return true;
            }
        }
    }

    private void sendHelp(Player player) {
        player.sendMessage(color("&6=== 祭壇の狂信者 ==="));
        player.sendMessage(color("&e/cultist setupaltar &7- 今回の祭壇座標へ5体を一括配置"));
        player.sendMessage(color("&e/cultist spawn <normal|priest> &7- 現在位置へ個別配置"));
        player.sendMessage(color("&e/cultist alert &7- 強制的に戦闘状態＋警報演出"));
        player.sendMessage(color("&e/cultist alarmtest &7- 警報・照明だけをテスト"));
        player.sendMessage(color("&e/cultist calm &7- 祈り状態へ戻す"));
        player.sendMessage(color("&e/cultist clear &7- 祭壇の狂信者を削除"));
        player.sendMessage(color("&e/cultist status &7- 状態確認"));
        player.sendMessage(color("&e/cultist reload &7- 設定再読込"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return startsWith(args[0], List.of("setupaltar", "spawn", "alert", "alarmtest", "calm", "clear", "status", "reload"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return startsWith(args[1], List.of("normal", "priest"));
        }
        return Collections.emptyList();
    }

    private List<String> startsWith(String input, List<String> values) {
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clampDouble(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }
}
