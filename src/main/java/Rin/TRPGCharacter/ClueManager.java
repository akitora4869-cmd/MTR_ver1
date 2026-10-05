package Rin.TRPGCharacter;

import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ClueManager {

    private final Plugin plugin;
    private final File file;
    private final File sanHistoryFile;
    private final File fumbleHistoryFile;
    private final File rewardHistoryFile;
    private final CharacterManager characterManager;
    private YamlConfiguration config;
    private final NamespacedKey clueKey;
    private final NamespacedKey hiddenKey;
    private final NamespacedKey protectedKey;

    private final Map<UUID, Map<UUID, Long>> discovered = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> criticalDiscoveries = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> destroyedForPlayer = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> sanChecked = new ConcurrentHashMap<>();
    private final Map<UUID, Set<String>> rewardClaimed = new ConcurrentHashMap<>();

    public ClueManager(Plugin plugin, CharacterManager characterManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.file = new File(plugin.getDataFolder(), "clues.yml");
        this.sanHistoryFile = new File(plugin.getDataFolder(), "clue-san-history.yml");
        this.fumbleHistoryFile = new File(plugin.getDataFolder(), "clue-fumble-history.yml");
        this.rewardHistoryFile = new File(plugin.getDataFolder(), "clue-reward-history.yml");
        this.clueKey = new NamespacedKey(plugin, "clue_id");
        this.hiddenKey = new NamespacedKey(plugin, "clue_hidden");
        this.protectedKey = new NamespacedKey(plugin, "clue_protected");

        if (!file.exists()) {
            plugin.saveResource("clues.yml", false);
        }

        reload();
        loadSanHistory();
        loadFumbleHistory();
        loadRewardHistory();
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickParticles, 10L, 10L);
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public boolean clueExists(String id) {
        return config.contains(id);
    }

    public void markArmorStand(ArmorStand stand, String clueId) {
        stand.getPersistentDataContainer().set(clueKey, PersistentDataType.STRING, clueId);
    }

    public int hideNearby(Player player, double radius) {
        int count = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof ArmorStand stand)) continue;

            stand.setInvisible(true);
            stand.getPersistentDataContainer().set(hiddenKey, PersistentDataType.BYTE, (byte) 1);
            count++;
        }
        return count;
    }

    public int showNearby(Player player, double radius) {
        int count = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof ArmorStand stand)) continue;

            stand.setInvisible(false);
            stand.getPersistentDataContainer().remove(hiddenKey);
            count++;
        }
        return count;
    }

    public int protectNearby(Player player, double radius) {
        int count = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof ArmorStand stand)) continue;

            stand.getPersistentDataContainer().set(protectedKey, PersistentDataType.BYTE, (byte) 1);
            stand.setInvulnerable(true);
            count++;
        }
        return count;
    }

    public int unprotectNearby(Player player, double radius) {
        int count = 0;
        for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof ArmorStand stand)) continue;

            stand.getPersistentDataContainer().remove(protectedKey);
            stand.setInvulnerable(false);
            count++;
        }
        return count;
    }

    public int setupNearby(Player player, double radius) {
        int hidden = hideNearby(player, radius);
        protectNearby(player, radius);
        return hidden;
    }

    public boolean isProtected(Entity entity) {
        Byte value = entity.getPersistentDataContainer().get(protectedKey, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    public void restorePersistentState(ArmorStand stand) {
        Byte hidden = stand.getPersistentDataContainer().get(hiddenKey, PersistentDataType.BYTE);
        Byte protect = stand.getPersistentDataContainer().get(protectedKey, PersistentDataType.BYTE);

        if (hidden != null && hidden == (byte) 1) {
            stand.setInvisible(true);
        }

        if (protect != null && protect == (byte) 1) {
            stand.setInvulnerable(true);
        }
    }

    public String getClueId(Entity entity) {
        return entity.getPersistentDataContainer().get(clueKey, PersistentDataType.STRING);
    }

    public boolean hasClue(Entity entity) {
        return getClueId(entity) != null;
    }

    public int discoverNearby(Player player) {
        return discoverNearby(player, false);
    }

    public int discoverNearby(Player player, boolean critical) {
        int found = 0;
        long now = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();

        for (Entity entity : player.getNearbyEntities(16, 16, 16)) {
            if (!(entity instanceof ArmorStand stand)) continue;

            String id = getClueId(stand);
            if (id == null || !config.contains(id)) continue;
            if (destroyedForPlayer.getOrDefault(playerId, Collections.emptySet()).contains(stand.getUniqueId())) continue;

            double range = config.getDouble(id + ".range", 10.0);
            if (stand.getLocation().distanceSquared(player.getLocation()) > range * range) continue;

            long duration = config.getLong(id + ".duration", 30L) * 1000L;
            discovered.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>())
                    .put(stand.getUniqueId(), now + duration);
            if (critical) {
                criticalDiscoveries.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                        .add(stand.getUniqueId());
            }
            grantConfiguredRewardIfNeeded(player, id);
            found++;
        }
        return found;
    }

    public boolean canInspect(Player player, Entity entity) {
        Map<UUID, Long> map = discovered.get(player.getUniqueId());
        if (map == null) return false;
        long until = map.getOrDefault(entity.getUniqueId(), 0L);
        return until > System.currentTimeMillis();
    }

    public void showInfo(Player player, Entity entity) {
        if (!canInspect(player, entity)) return;

        String id = getClueId(entity);
        if (id == null || !config.contains(id)) return;

        player.sendMessage(color("&f------------------------------"));
        player.sendMessage(color("&b[情報] &f" + config.getString(id + ".display-name", id)));
        for (String line : config.getStringList(id + ".text")) {
            player.sendMessage(color("&7" + line));
        }

        Set<UUID> critical = criticalDiscoveries.get(player.getUniqueId());
        if (critical != null && critical.contains(entity.getUniqueId())) {
            List<String> bonus = config.getStringList(id + ".critical.text");
            if (!bonus.isEmpty()) {
                player.sendMessage(color("&6★ クリティカル追加情報 ★"));
                for (String line : bonus) {
                    player.sendMessage(color("&e" + line));
                }
            }
        }

        player.sendMessage(color("&f------------------------------"));
        performSanCheckIfNeeded(player, id);
    }


    public boolean applyFumbleEffect(Player player, String skillId) {
        ArmorStand target = findNearestFumbleClue(player, skillId);
        if (target == null) return false;

        String id = getClueId(target);
        String base = id + ".fumble";
        String message = config.getString(base + ".message", "調査中に事故が起こった！");
        player.sendMessage(color("&4☠ ファンブル ☠ &c" + message));

        if (config.getBoolean(base + ".collapse-sound", true)) {
            player.getWorld().playSound(target.getLocation(), Sound.BLOCK_WOOD_BREAK, 1.0f, 0.65f);
            player.getWorld().playSound(target.getLocation(), Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.65f, 0.85f);
        }

        String damageExpr = config.getString(base + ".damage", "0");
        int damage = rollDiceExpression(damageExpr);
        if (damage > 0) {
            applyDirectHpDamage(player, damage, damageExpr);
        }

        int blindnessSeconds = Math.max(0, config.getInt(base + ".blindness-seconds", 0));
        if (blindnessSeconds > 0) {
            int amplifier = Math.max(0, config.getInt(base + ".blindness-amplifier", 0));
            player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, blindnessSeconds * 20, amplifier, true, true, true));
            player.sendMessage(color("&8[状態異常] &7" + blindnessSeconds + "秒間、視界を奪われました。"));
        }

        boolean destroy = config.getBoolean(base + ".destroy-information", false);
        boolean required = config.getBoolean(id + ".required", false);
        if (destroy) {
            destroyedForPlayer.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet())
                    .add(target.getUniqueId());
            saveFumbleHistory();
            Map<UUID, Long> map = discovered.get(player.getUniqueId());
            if (map != null) map.remove(target.getUniqueId());
            Set<UUID> critical = criticalDiscoveries.get(player.getUniqueId());
            if (critical != null) critical.remove(target.getUniqueId());
            player.sendMessage(color("&4[情報喪失] &cこの情報ポイントは、この探索者には通常の方法では調べられなくなりました。"));
        }

        if (required) {
            List<String> rescue = config.getStringList(base + ".rescue.text");
            if (rescue.isEmpty()) {
                plugin.getLogger().warning("必須情報 " + id + " にファンブル救済文がありません。clues.yml の "
                        + base + ".rescue.text を設定してください。");
                player.sendMessage(color("&6[救済] &e重要な手掛かりのため、KPが別経路で情報を提示してください。"));
            } else {
                player.sendMessage(color("&6[救済情報] &f完全には失われなかった断片から、最低限の情報を得ました。"));
                for (String line : rescue) {
                    player.sendMessage(color("&e" + line));
                }
            }
        }

        return true;
    }

    private ArmorStand findNearestFumbleClue(Player player, String skillId) {
        ArmorStand nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity entity : player.getNearbyEntities(16, 16, 16)) {
            if (!(entity instanceof ArmorStand stand)) continue;
            String id = getClueId(stand);
            if (id == null || !config.contains(id)) continue;
            if (!config.getBoolean(id + ".fumble.enabled", false)) continue;

            List<String> skills = config.getStringList(id + ".fumble.skills");
            if (!skills.isEmpty() && skills.stream().noneMatch(s -> s.equalsIgnoreCase(skillId))) continue;

            double range = config.getDouble(id + ".fumble.range", config.getDouble(id + ".range", 10.0));
            double distance = stand.getLocation().distanceSquared(player.getLocation());
            if (distance > range * range || distance >= nearestDistance) continue;

            nearest = stand;
            nearestDistance = distance;
        }
        return nearest;
    }

    private void applyDirectHpDamage(Player player, int damage, String expression) {
        int before = Math.max(0, characterManager.getCurrentHp(player));
        int after = Math.max(0, before - damage);
        characterManager.setCurrentHp(player, after);
        player.sendMessage(color("&4[ファンブルダメージ] &f" + expression + " = &c" + damage
                + " &7(HP " + before + " → " + after + ")"));
        if (plugin.getDamageFeedbackManager() != null) {
            plugin.getDamageFeedbackManager().play(player, Math.max(0, before - after));
        }
        if (plugin.getSidebarManager() != null) plugin.getSidebarManager().updatePlayer(player);
        if (plugin.getHealthSyncManager() != null) plugin.getHealthSyncManager().sync(player);
    }

    private void performSanCheckIfNeeded(Player player, String clueId) {
        if (!config.getBoolean(clueId + ".san-check.enabled", false)) return;

        Set<String> checked = sanChecked.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        if (!checked.add(clueId)) return;
        saveSanHistory();

        int currentSan = Math.max(0, characterManager.getCurrentSan(player));
        int roll = 1 + new Random().nextInt(100);
        boolean success = roll <= currentSan;
        String lossExpression = config.getString(clueId + (success ? ".san-check.success" : ".san-check.failure"), "0");
        int loss = rollDiceExpression(lossExpression);
        int after = Math.max(0, currentSan - loss);
        characterManager.setCurrentSan(player, after);

        player.sendMessage(color("&5[SANチェック] &f1d100 = &e" + roll + " &7/ &f" + currentSan
                + (success ? " &a成功" : " &c失敗")));
        player.sendMessage(color("&5[SAN減少] &f" + lossExpression + " = &d" + loss
                + " &7(SAN " + currentSan + " → " + after + ")"));
    }

    private int rollDiceExpression(String expression) {
        if (expression == null) return 0;
        String s = expression.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (s.matches("\\d+")) return Math.max(0, Integer.parseInt(s));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d*)d(\\d+)([+-]\\d+)?").matcher(s);
        if (!m.matches()) {
            plugin.getLogger().warning("clues.yml のSAN減少式が不正です: " + expression);
            return 0;
        }
        int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        int modifier = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        if (count < 0 || count > 100 || sides <= 0 || sides > 100000) return 0;
        Random random = new Random();
        int total = modifier;
        for (int i = 0; i < count; i++) total += 1 + random.nextInt(sides);
        return Math.max(0, total);
    }

    private void loadFumbleHistory() {
        destroyedForPlayer.clear();
        if (!fumbleHistoryFile.exists()) return;
        YamlConfiguration history = YamlConfiguration.loadConfiguration(fumbleHistoryFile);
        org.bukkit.configuration.ConfigurationSection players = history.getConfigurationSection("players");
        if (players == null) return;

        for (String playerText : players.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(playerText);
                Set<UUID> destroyed = ConcurrentHashMap.newKeySet();
                for (String entityText : history.getStringList("players." + playerText + ".destroyed")) {
                    try {
                        destroyed.add(UUID.fromString(entityText));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (!destroyed.isEmpty()) destroyedForPlayer.put(playerId, destroyed);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private synchronized void saveFumbleHistory() {
        YamlConfiguration history = new YamlConfiguration();
        for (Map.Entry<UUID, Set<UUID>> entry : destroyedForPlayer.entrySet()) {
            List<String> values = new ArrayList<>();
            for (UUID entityId : entry.getValue()) values.add(entityId.toString());
            history.set("players." + entry.getKey() + ".destroyed", values);
        }
        try {
            history.save(fumbleHistoryFile);
        } catch (java.io.IOException e) {
            plugin.getLogger().severe("clue-fumble-history.yml の保存に失敗しました: " + e.getMessage());
        }
    }

    private void grantConfiguredRewardIfNeeded(Player player, String clueId) {
        String base = clueId + ".reward";
        if (!config.getBoolean(base + ".enabled", false)) return;

        Set<String> claimed = rewardClaimed.computeIfAbsent(player.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        if (claimed.contains(clueId)) return;

        String type = config.getString(base + ".type", "").trim().toLowerCase(Locale.ROOT);
        DoorLockManager doorLock = plugin.getDoorLockManager();
        if (doorLock == null) return;

        boolean granted = false;
        if (type.equals("key")) {
            String keyId = config.getString(base + ".key-id", "");
            String displayName = config.getString(base + ".display-name", "扉の鍵");
            if (keyId == null || keyId.isBlank()) {
                plugin.getLogger().warning("clues.yml: " + clueId + ".reward.key-id が設定されていません。");
                return;
            }
            doorLock.giveKeyReward(player, keyId, displayName);
            granted = true;
        } else if (type.equals("hammer")) {
            String displayName = config.getString(base + ".display-name", "大型ハンマー");
            doorLock.giveHammerReward(player, displayName);
            granted = true;
        } else {
            plugin.getLogger().warning("clues.yml: " + clueId + ".reward.type が不正です: " + type);
        }

        if (granted) {
            claimed.add(clueId);
            saveRewardHistory();
        }
    }

    private void loadRewardHistory() {
        rewardClaimed.clear();
        if (!rewardHistoryFile.exists()) return;
        YamlConfiguration history = YamlConfiguration.loadConfiguration(rewardHistoryFile);
        org.bukkit.configuration.ConfigurationSection players = history.getConfigurationSection("players");
        if (players == null) return;
        for (String uuidText : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidText);
                Set<String> ids = ConcurrentHashMap.newKeySet();
                ids.addAll(history.getStringList("players." + uuidText + ".claimed"));
                if (!ids.isEmpty()) rewardClaimed.put(uuid, ids);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private synchronized void saveRewardHistory() {
        YamlConfiguration history = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> entry : rewardClaimed.entrySet()) {
            history.set("players." + entry.getKey() + ".claimed", new ArrayList<>(entry.getValue()));
        }
        try {
            history.save(rewardHistoryFile);
        } catch (java.io.IOException e) {
            plugin.getLogger().severe("clue-reward-history.yml の保存に失敗しました: " + e.getMessage());
        }
    }

    public int resetRewardHistory(UUID playerId) {
        Set<String> removed = rewardClaimed.remove(playerId);
        saveRewardHistory();
        return removed == null ? 0 : removed.size();
    }

    public boolean resetRewardHistory(UUID playerId, String clueId) {
        Set<String> set = rewardClaimed.get(playerId);
        if (set == null || !set.remove(clueId)) return false;
        if (set.isEmpty()) rewardClaimed.remove(playerId);
        saveRewardHistory();
        return true;
    }

    private void loadSanHistory() {
        sanChecked.clear();
        if (!sanHistoryFile.exists()) return;
        YamlConfiguration history = YamlConfiguration.loadConfiguration(sanHistoryFile);
        org.bukkit.configuration.ConfigurationSection players = history.getConfigurationSection("players");
        if (players == null) return;
        for (String uuidText : players.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidText);
                Set<String> ids = ConcurrentHashMap.newKeySet();
                ids.addAll(history.getStringList("players." + uuidText + ".checked"));
                sanChecked.put(uuid, ids);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private synchronized void saveSanHistory() {
        YamlConfiguration history = new YamlConfiguration();
        for (Map.Entry<UUID, Set<String>> entry : sanChecked.entrySet()) {
            history.set("players." + entry.getKey() + ".checked", new ArrayList<>(entry.getValue()));
        }
        try {
            history.save(sanHistoryFile);
        } catch (java.io.IOException e) {
            plugin.getLogger().severe("clue-san-history.yml の保存に失敗しました: " + e.getMessage());
        }
    }

    public int resetFumbleHistory(UUID playerId) {
        Set<UUID> removed = destroyedForPlayer.remove(playerId);
        saveFumbleHistory();
        return removed == null ? 0 : removed.size();
    }

    public int resetSanHistory(UUID playerId) {
        Set<String> removed = sanChecked.remove(playerId);
        saveSanHistory();
        return removed == null ? 0 : removed.size();
    }

    public boolean resetSanHistory(UUID playerId, String clueId) {
        Set<String> set = sanChecked.get(playerId);
        if (set == null || !set.remove(clueId)) return false;
        if (set.isEmpty()) sanChecked.remove(playerId);
        saveSanHistory();
        return true;
    }

    private void tickParticles() {
        long now = System.currentTimeMillis();
        Particle.DustOptions white = new Particle.DustOptions(Color.WHITE, 1.0f);

        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            for (ArmorStand stand : world.getEntitiesByClass(ArmorStand.class)) {
                restorePersistentState(stand);
            }
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            Map<UUID, Long> map = discovered.get(player.getUniqueId());
            if (map == null || map.isEmpty()) continue;

            Iterator<Map.Entry<UUID, Long>> it = map.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> e = it.next();
                if (e.getValue() <= now) {
                    it.remove();
                    continue;
                }

                Entity target = null;
                for (Entity entity : player.getWorld().getEntities()) {
                    if (entity.getUniqueId().equals(e.getKey())) {
                        target = entity;
                        break;
                    }
                }

                if (target == null || !target.isValid()) {
                    it.remove();
                    continue;
                }

                Location loc = target.getLocation().clone().add(0, 1.4, 0);
                player.spawnParticle(Particle.REDSTONE, loc, 8, 0.35, 0.45, 0.35, 0.0, white);
            }
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
