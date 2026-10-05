package Rin.TRPGCharacter;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.io.File;
import java.util.Map;

public class SkillEffectManager {

    private final Plugin plugin;
    private final CharacterManager characterManager;
    private final Random random = new Random();
    private final File skillsFile;
    private YamlConfiguration skillsConfig;

    public SkillEffectManager(Plugin plugin, CharacterManager characterManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.skillsFile = new File(plugin.getDataFolder(), "skills.yml");
        reload();
    }

    public void reload() {
        this.skillsConfig = YamlConfiguration.loadConfiguration(skillsFile);
    }

    public void applyOnSuccess(Player player, String skillId) {
        applyOnSuccess(player, skillId, CheckResult.SUCCESS);
    }

    public void applyOnSuccess(Player player, String skillId, CheckResult result) {
        switch (skillId) {
            case "first_aid" -> healHp(player, roll(1, 3), "応急手当");
            case "medicine" -> healHp(player, roll(1, 3) + 1, "医学");
            case "psychoanalysis" -> psychoanalysisOther(player, roll(1, 3));
            case "jump" -> applyPotion(player, PotionEffectType.JUMP, 20 * 15, 1, "跳躍力上昇");
            case "swim" -> applyPotion(player, PotionEffectType.WATER_BREATHING, 20 * 30, 0, "水中呼吸");
            case "climb" -> applyClimbEffect(player);
            case "hide" -> applyPotion(player, PotionEffectType.INVISIBILITY, 20 * 10, 0, "透明化");
            case "sneak" -> applyPotion(player, PotionEffectType.SPEED, 20 * 10, 0, "忍び歩き補助");
            case "spot_hidden" -> discoverClues(player, result == CheckResult.CRITICAL);
            case "listen" -> listenNearby(player);
            default -> {
                // この10技能以外は判定のみ。
            }
        }
    }

    private void healHp(Player player, int amount, String source) {
        int before = characterManager.getCurrentHp(player);
        int max = characterManager.getHp(player);
        int after = Math.min(max, before + amount);
        characterManager.setCurrentHp(player, after);

        player.sendMessage(color("&6[TRPG] &a" + source + "成功: HP "
                + before + " → " + after + " (+" + (after - before) + ")"));
        plugin.getSidebarManager().updatePlayer(player);
        plugin.getHealthSyncManager().sync(player);
    }

    private void psychoanalysisOther(Player analyst, int amount) {
        Player target = null;
        double nearest = Double.MAX_VALUE;

        for (Player candidate : analyst.getWorld().getPlayers()) {
            if (candidate.equals(analyst)) {
                continue;
            }

            double distance = candidate.getLocation().distanceSquared(analyst.getLocation());
            if (distance <= 25.0 && distance < nearest) {
                target = candidate;
                nearest = distance;
            }
        }

        if (target == null) {
            analyst.sendMessage(color("&6[TRPG] &e精神分析成功: 5ブロック以内に対象となる他のプレイヤーがいません。"));
            return;
        }

        int before = characterManager.getCurrentSan(target);
        int max = characterManager.getSan(target);
        int after = Math.min(max, before + amount);
        characterManager.setCurrentSan(target, after);

        analyst.sendMessage(color("&6[TRPG] &d精神分析成功: &f" + target.getName()
                + " &dのSAN " + before + " → " + after
                + " (+" + (after - before) + ")"));

        target.sendMessage(color("&6[TRPG] &d" + analyst.getName()
                + " の精神分析を受けました: SAN "
                + before + " → " + after
                + " (+" + (after - before) + ")"));

        plugin.getSidebarManager().updatePlayer(target);
    }

    private void healSan(Player player, int amount) {
        int before = characterManager.getCurrentSan(player);
        int max = characterManager.getSan(player);
        int after = Math.min(max, before + amount);
        characterManager.setCurrentSan(player, after);

        player.sendMessage(color("&6[TRPG] &d精神分析成功: SAN "
                + before + " → " + after + " (+" + (after - before) + ")"));
        plugin.getSidebarManager().updatePlayer(player);
    }

    private void applyPotion(Player player,
                             PotionEffectType type,
                             int durationTicks,
                             int amplifier,
                             String label) {
        player.addPotionEffect(new PotionEffect(type, durationTicks, amplifier, true, true, true));
        player.sendMessage(color("&6[TRPG] &a" + label + "の効果を得ました。"));
    }

    private void applyClimbEffect(Player player) {
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, 20 * 15, 0, true, true, true));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 15, 0, true, true, true));
        player.sendMessage(color("&6[TRPG] &a登攀成功: 15秒間、登攀補助を得ました。"));
    }

    private void discoverClues(Player player, boolean critical) {
        int found = plugin.getClueManager().discoverNearby(player, critical);

        if (found <= 0) {
            player.sendMessage(color(critical
                    ? "&6[TRPG] &6目星クリティカル: &e周囲に追加で読み取れる情報はありませんでした。"
                    : "&6[TRPG] &e目星成功: 特に気になる情報は見つかりませんでした。"));
            return;
        }

        if (critical) {
            player.sendMessage(color("&6[TRPG] &6★ 目星クリティカル ★ &f" + found
                    + "件 &6の情報ポイントを発見し、通常より深い情報を読み取れます。"));
        } else {
            player.sendMessage(color("&6[TRPG] &a目星成功: &f" + found + "件 &aの情報ポイントを発見しました。"));
        }
        player.sendMessage(color("&7白いパーティクルが出ている場所を右クリックしてください。"));
    }

    /**
     * すべての技能に共通するクリティカル追加効果入口。
     * skills.yml の critical-fumble.defaults と skills.<id>.critical を合成して実行する。
     * 新しい技能IDを追加しても RollManager 側の変更は不要。
     */
    public void applyOnCritical(Player player, String skillId) {
        applyConfiguredEffects(player, skillId, "critical", CheckResult.CRITICAL);
    }

    /**
     * すべての技能に共通するファンブル効果入口。
     * skills.yml の設定だけで技能ごとの事故を差し替えられる。
     */
    public void applyOnFumble(Player player, String skillId) {
        applyConfiguredEffects(player, skillId, "fumble", CheckResult.FUMBLE);
    }

    private void applyConfiguredEffects(Player player, String skillId, String phase, CheckResult result) {
        if (skillsConfig == null) reload();

        String defaultBase = "critical-fumble.defaults." + phase;
        String skillBase = "skills." + skillId + "." + phase;

        boolean inherit = skillsConfig.getBoolean(skillBase + ".inherit-defaults", true);
        if (inherit && skillsConfig.getBoolean(defaultBase + ".enabled", true)) {
            runEffectList(player, skillId, result, defaultBase + ".effects");
        }

        if (skillsConfig.contains(skillBase) && skillsConfig.getBoolean(skillBase + ".enabled", true)) {
            runEffectList(player, skillId, result, skillBase + ".effects");
        }
    }

    private void runEffectList(Player player, String skillId, CheckResult result, String path) {
        List<Map<?, ?>> effects = skillsConfig.getMapList(path);
        for (Map<?, ?> raw : effects) {
            Object typeObj = raw.get("type");
            if (typeObj == null) continue;
            String type = String.valueOf(typeObj).trim().toLowerCase();

            try {
                switch (type) {
                    case "message" -> {
                        Object text = raw.get("text");
                        if (text instanceof List<?> list) {
                            for (Object line : list) player.sendMessage(color(replace(String.valueOf(line), player, skillId, result)));
                        } else if (text != null) {
                            player.sendMessage(color(replace(String.valueOf(text), player, skillId, result)));
                        }
                    }
                    case "damage" -> changeHp(player, -rollExpression(value(raw, "amount", "1")), "クリファン効果");
                    case "heal-hp" -> changeHp(player, rollExpression(value(raw, "amount", "1")), "クリティカル効果");
                    case "heal-san" -> changeSan(player, rollExpression(value(raw, "amount", "1")));
                    case "lose-san" -> changeSan(player, -rollExpression(value(raw, "amount", "1")));
                    case "blindness" -> applyPotion(player, PotionEffectType.BLINDNESS,
                            secondsToTicks(raw, 5), intValue(raw, "amplifier", 0), "視界阻害");
                    case "potion" -> applyConfiguredPotion(player, raw);
                    case "knockback" -> applyKnockback(player, raw);
                    case "command" -> runCommands(player, skillId, result, raw);
                    case "clue-fumble" -> {
                        boolean applied = plugin.getClueManager().applyFumbleEffect(player, skillId);
                        if (!applied) player.sendMessage(color("&4[ファンブル] &c周囲に事故が起こる対象はありませんでした。"));
                    }
                    case "clue-discover" -> discoverClues(player, result == CheckResult.CRITICAL);
                    case "repeat-success-effect" -> {
                        int times = Math.max(1, Math.min(5, intValue(raw, "times", 1)));
                        for (int i = 0; i < times; i++) applyOnSuccess(player, skillId, result);
                    }
                    default -> plugin.getLogger().warning("skills.yml: 未対応のクリファン効果 type: " + type
                            + " (skill=" + skillId + ")");
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("skills.yml: クリファン効果の実行に失敗しました: skill=" + skillId
                        + ", type=" + type + ", " + ex.getMessage());
            }
        }
    }

    private void applyConfiguredPotion(Player player, Map<?, ?> raw) {
        String effectName = value(raw, "effect", "").toUpperCase();
        PotionEffectType type = PotionEffectType.getByName(effectName);
        if (type == null) {
            plugin.getLogger().warning("skills.yml: 不明なPotionEffect: " + effectName);
            return;
        }
        applyPotion(player, type, secondsToTicks(raw, 5), intValue(raw, "amplifier", 0), effectName);
    }

    private void applyKnockback(Player player, Map<?, ?> raw) {
        double power = doubleValue(raw, "power", 1.2);
        double upward = doubleValue(raw, "upward", 0.35);
        Vector direction = player.getLocation().getDirection().multiply(-power);
        direction.setY(upward);
        player.setVelocity(direction);
    }

    private void runCommands(Player player, String skillId, CheckResult result, Map<?, ?> raw) {
        Object commands = raw.get("commands");
        if (commands instanceof List<?> list) {
            for (Object command : list) dispatch(player, skillId, result, String.valueOf(command));
        } else {
            Object command = raw.get("command");
            if (command != null) dispatch(player, skillId, result, String.valueOf(command));
        }
    }

    private void dispatch(Player player, String skillId, CheckResult result, String command) {
        String parsed = replace(command, player, skillId, result);
        if (parsed.startsWith("/")) parsed = parsed.substring(1);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), parsed);
    }

    private void changeHp(Player player, int delta, String source) {
        int before = characterManager.getCurrentHp(player);
        int max = characterManager.getHp(player);
        int after = Math.max(0, Math.min(max, before + delta));
        characterManager.setCurrentHp(player, after);
        player.sendMessage(color("&6[TRPG] &f" + source + ": HP " + before + " → " + after));
        plugin.getSidebarManager().updatePlayer(player);
        plugin.getHealthSyncManager().sync(player);
    }

    private void changeSan(Player player, int delta) {
        int before = characterManager.getCurrentSan(player);
        int max = characterManager.getSan(player);
        int after = Math.max(0, Math.min(max, before + delta));
        characterManager.setCurrentSan(player, after);
        player.sendMessage(color("&6[TRPG] &fSAN " + before + " → " + after));
        plugin.getSidebarManager().updatePlayer(player);
    }

    private int rollExpression(String expression) {
        String s = expression == null ? "0" : expression.trim().toLowerCase().replace(" ", "");
        if (s.matches("\\d+")) return Integer.parseInt(s);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d*)d(\\d+)([+-]\\d+)?").matcher(s);
        if (!m.matches()) return 0;
        int count = m.group(1).isEmpty() ? 1 : Integer.parseInt(m.group(1));
        int sides = Integer.parseInt(m.group(2));
        int bonus = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        count = Math.max(1, Math.min(100, count));
        sides = Math.max(1, Math.min(100000, sides));
        int total = bonus;
        for (int i = 0; i < count; i++) total += random.nextInt(sides) + 1;
        return Math.max(0, total);
    }

    private int secondsToTicks(Map<?, ?> raw, int def) {
        return Math.max(1, intValue(raw, "seconds", def)) * 20;
    }

    private int intValue(Map<?, ?> raw, String key, int def) {
        Object v = raw.get(key);
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private double doubleValue(Map<?, ?> raw, String key, double def) {
        Object v = raw.get(key);
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return def; }
    }

    private String value(Map<?, ?> raw, String key, String def) {
        Object v = raw.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private String replace(String text, Player player, String skillId, CheckResult result) {
        SkillDefinition def = plugin.getSkillManager().getSkill(skillId);
        String skillName = def == null ? skillId : def.getName();
        return text.replace("{player}", player.getName())
                .replace("{skill}", skillName)
                .replace("{skill_id}", skillId)
                .replace("{result}", result.label());
    }

    private void glowNearby(Player player) {
        List<LivingEntity> targets = new ArrayList<>();

        for (Entity entity : player.getNearbyEntities(16, 16, 16)) {
            if (entity instanceof LivingEntity living && entity != player) {
                targets.add(living);
            }
        }

        if (targets.isEmpty()) {
            player.sendMessage(color("&6[TRPG] &e目星成功: 周囲に目立つ対象はありません。"));
            return;
        }

        for (LivingEntity target : targets) {
            target.setGlowing(true);
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (LivingEntity target : targets) {
                if (target.isValid()) {
                    target.setGlowing(false);
                }
            }
        }, 20L * 10L);

        player.sendMessage(color("&6[TRPG] &a目星成功: 周囲の対象を10秒間強調表示しました。"));
    }

    private void listenNearby(Player player) {
        List<LivingEntity> targets = new ArrayList<>();

        for (Entity entity : player.getNearbyEntities(24, 12, 24)) {
            if (entity instanceof LivingEntity living && entity != player) {
                targets.add(living);
            }
        }

        if (targets.isEmpty()) {
            player.sendMessage(color("&6[TRPG] &e聞き耳成功: 近くに生物の気配はありません。"));
            return;
        }

        Location origin = player.getLocation();
        targets.sort(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(origin)));
        LivingEntity nearest = targets.get(0);
        double distance = Math.sqrt(nearest.getLocation().distanceSquared(origin));

        player.sendMessage(color("&6[TRPG] &a聞き耳成功: "
                + targets.size() + "体の気配。最も近い気配は約"
                + Math.max(1, (int) Math.round(distance)) + "ブロック先です。"));
    }

    private int roll(int dice, int sides) {
        int total = 0;
        for (int i = 0; i < dice; i++) {
            total += random.nextInt(sides) + 1;
        }
        return total;
    }

    private String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }
}
