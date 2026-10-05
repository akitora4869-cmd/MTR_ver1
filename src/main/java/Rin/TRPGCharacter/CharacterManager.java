package Rin.TRPGCharacter;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class CharacterManager {

    private final Plugin plugin;
    private final File file;
    private YamlConfiguration data;

    private static final String[] STATS = {
            "STR", "CON", "POW", "DEX", "APP", "SIZ", "INT", "EDU"
    };

    public CharacterManager(Plugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public String[] getStats() {
        return STATS.clone();
    }

    public String getCharacterName(Player player) {
        return data.getString(
                path(player.getUniqueId(), "character-name"),
                player.getName()
        );
    }

    public void setCharacterName(Player player, String name) {
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(path(player.getUniqueId(), "character-name"), name);
        save();
    }

    public int getStat(Player player, String stat) {
        String normalized = stat.toUpperCase(Locale.ROOT);
        int fallback = plugin.getConfig().getInt("default-stat-value", 0);
        return data.getInt(path(player.getUniqueId(), "stats." + normalized), fallback);
    }

    public void setStat(Player player, String stat, int value) {
        String normalized = stat.toUpperCase(Locale.ROOT);
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(path(player.getUniqueId(), "stats." + normalized), value);
        save();
    }

    public void setStatsBulk(Player player, java.util.Map<String, Integer> stats) {
        data.set(path(player.getUniqueId(), "name"), player.getName());
        for (java.util.Map.Entry<String, Integer> entry : stats.entrySet()) {
            data.set(path(player.getUniqueId(), "stats." + entry.getKey()), entry.getValue());
        }
        save();
    }

    public void resetCurrentVitalsToMaximum(Player player) {
        setCurrentHp(player, getHp(player));
        setCurrentMp(player, getMp(player));
        setCurrentSan(player, getSan(player));
    }

    public boolean hasConfiguredStats(Player player) {
        for (String stat : STATS) {
            String p = path(player.getUniqueId(), "stats." + stat);
            if (!data.contains(p)) {
                return false;
            }
        }
        return true;
    }

    public boolean isValidStat(String stat) {
        String normalized = stat.toUpperCase(Locale.ROOT);
        for (String s : STATS) {
            if (s.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    public Integer getStoredSkill(Player player, String skillId) {
        String p = path(player.getUniqueId(), "skills." + skillId);
        if (!data.contains(p)) {
            return null;
        }
        return data.getInt(p);
    }

    public void setSkill(Player player, String skillId, int value) {
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(path(player.getUniqueId(), "skills." + skillId), value);
        save();
    }

    /**
     * 趣味技能ポイント。CoC第6版標準の INT×10。
     */
    public int getHobbyPointTotal(Player player) {
        return Math.max(0, getStat(player, "INT") * 10);
    }

    public int getHobbyAllocation(Player player, String skillId) {
        return Math.max(0, data.getInt(
                path(player.getUniqueId(), "skill-points.hobby.allocations." + skillId),
                0
        ));
    }

    public int getHobbyPointUsed(Player player) {
        String base = path(player.getUniqueId(), "skill-points.hobby.allocations");
        org.bukkit.configuration.ConfigurationSection section =
                data.getConfigurationSection(base);

        if (section == null) {
            return 0;
        }

        int total = 0;
        for (String skillId : section.getKeys(false)) {
            total += Math.max(0, section.getInt(skillId, 0));
        }
        return total;
    }

    public int getHobbyPointRemaining(Player player) {
        return Math.max(0, getHobbyPointTotal(player) - getHobbyPointUsed(player));
    }

    public boolean addHobbyAllocation(Player player, String skillId, int amount) {
        if (amount <= 0 || amount > getHobbyPointRemaining(player)) {
            return false;
        }

        int current = getHobbyAllocation(player, skillId);
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(
                path(player.getUniqueId(), "skill-points.hobby.allocations." + skillId),
                current + amount
        );
        save();
        return true;
    }

    public void resetHobbyAllocations(Player player) {
        data.set(
                path(player.getUniqueId(), "skill-points.hobby.allocations"),
                null
        );
        save();
    }

    public String getOccupationName(Player player) {
        return data.getString(path(player.getUniqueId(), "occupation.name"), "未設定");
    }

    public String getOccupationId(Player player) {
        return data.getString(path(player.getUniqueId(), "occupation.id"), "");
    }

    public boolean hasOccupation(Player player) {
        return !getOccupationId(player).isBlank();
    }

    public boolean isCustomOccupation(Player player) {
        return data.getBoolean(path(player.getUniqueId(), "occupation.custom"), false);
    }

    public String getOccupationPointFormula(Player player) {
        return data.getString(path(player.getUniqueId(), "occupation.point-formula"), "EDU*20");
    }

    public java.util.List<String> getOccupationSkills(Player player) {
        return new java.util.ArrayList<>(data.getStringList(path(player.getUniqueId(), "occupation.skills")));
    }

    public boolean isOccupationSkill(Player player, String skillId) {
        return getOccupationSkills(player).contains(skillId);
    }

    public void setOccupation(Player player, String id, String name, boolean custom,
                              String pointFormula, java.util.List<String> skills) {
        String base = path(player.getUniqueId(), "occupation");
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(base + ".id", id);
        data.set(base + ".name", name);
        data.set(base + ".custom", custom);
        data.set(base + ".point-formula", pointFormula);
        data.set(base + ".skills", new java.util.ArrayList<>(skills));
        // 職業変更時は、以前の職業に割り振ったポイントを持ち越さない。
        data.set(path(player.getUniqueId(), "skill-points.occupation.allocations"), null);
        save();
    }

    public void addOccupationSkill(Player player, String skillId) {
        java.util.List<String> skills = getOccupationSkills(player);
        if (!skills.contains(skillId)) {
            skills.add(skillId);
            data.set(path(player.getUniqueId(), "occupation.skills"), skills);
            save();
        }
    }

    public void removeOccupationSkill(Player player, String skillId) {
        java.util.List<String> skills = getOccupationSkills(player);
        if (skills.remove(skillId)) {
            data.set(path(player.getUniqueId(), "occupation.skills"), skills);
            data.set(path(player.getUniqueId(), "skill-points.occupation.allocations." + skillId), null);
            save();
        }
    }

    public int getOccupationPointTotal(Player player) {
        String raw = getOccupationPointFormula(player).trim().toUpperCase(Locale.ROOT);
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("([A-Z]{3})\\*(\\d+)").matcher(raw);
        if (matcher.matches() && isValidStat(matcher.group(1))) {
            return Math.max(0, getStat(player, matcher.group(1)) * Integer.parseInt(matcher.group(2)));
        }
        return Math.max(0, getStat(player, "EDU") * 20);
    }

    public int getOccupationAllocation(Player player, String skillId) {
        return Math.max(0, data.getInt(
                path(player.getUniqueId(), "skill-points.occupation.allocations." + skillId),
                0
        ));
    }

    public int getOccupationPointUsed(Player player) {
        String base = path(player.getUniqueId(), "skill-points.occupation.allocations");
        org.bukkit.configuration.ConfigurationSection section = data.getConfigurationSection(base);
        if (section == null) return 0;
        int total = 0;
        for (String skillId : section.getKeys(false)) {
            total += Math.max(0, section.getInt(skillId, 0));
        }
        return total;
    }

    public int getOccupationPointRemaining(Player player) {
        return Math.max(0, getOccupationPointTotal(player) - getOccupationPointUsed(player));
    }

    public boolean addOccupationAllocation(Player player, String skillId, int amount) {
        if (!isOccupationSkill(player, skillId) || amount <= 0 || amount > getOccupationPointRemaining(player)) {
            return false;
        }
        int current = getOccupationAllocation(player, skillId);
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(path(player.getUniqueId(), "skill-points.occupation.allocations." + skillId), current + amount);
        save();
        return true;
    }

    public void resetOccupationAllocations(Player player) {
        data.set(path(player.getUniqueId(), "skill-points.occupation.allocations"), null);
        save();
    }

    public int getSkillGrowth(Player player, String skillId) {
        return Math.max(0, data.getInt(
                path(player.getUniqueId(), "skill-growth.allocations." + skillId),
                0
        ));
    }

    public void addSkillGrowth(Player player, String skillId, int amount) {
        if (amount <= 0) {
            return;
        }

        int current = getSkillGrowth(player, skillId);
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(
                path(player.getUniqueId(), "skill-growth.allocations." + skillId),
                current + amount
        );
        save();
    }

    public String getUnarmedAttack(Player player) {
        String value = data.getString(
                path(player.getUniqueId(), "combat.unarmed-attack"),
                "fist"
        );

        if ("kick".equalsIgnoreCase(value)) {
            return "kick";
        }

        return "fist";
    }


    /**
     * よく使う技能ショートカット。
     * players.yml の shortcuts.skills に技能IDを保存する。
     */
    public List<String> getSkillShortcuts(Player player) {
        List<String> stored = data.getStringList(
                path(player.getUniqueId(), "shortcuts.skills")
        );
        return new ArrayList<>(stored);
    }

    public boolean isSkillShortcut(Player player, String skillId) {
        return getSkillShortcuts(player).contains(skillId);
    }

    public boolean addSkillShortcut(Player player, String skillId, int max) {
        List<String> shortcuts = getSkillShortcuts(player);
        if (shortcuts.contains(skillId)) {
            return true;
        }
        if (shortcuts.size() >= Math.max(1, max)) {
            return false;
        }

        shortcuts.add(skillId);
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(path(player.getUniqueId(), "shortcuts.skills"), shortcuts);
        save();
        return true;
    }

    public boolean removeSkillShortcut(Player player, String skillId) {
        List<String> shortcuts = getSkillShortcuts(player);
        boolean removed = shortcuts.remove(skillId);
        if (removed) {
            data.set(path(player.getUniqueId(), "shortcuts.skills"), shortcuts);
            save();
        }
        return removed;
    }

    public void resetSkillShortcuts(Player player) {
        data.set(path(player.getUniqueId(), "shortcuts.skills"), null);
        save();
    }

    public String getUnarmedAttackName(Player player) {
        return "kick".equals(getUnarmedAttack(player)) ? "キック" : "こぶし";
    }

    public void setUnarmedAttack(Player player, String type) {
        String normalized = "kick".equalsIgnoreCase(type) ? "kick" : "fist";
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(path(player.getUniqueId(), "combat.unarmed-attack"), normalized);
        save();
    }

    public int getHp(Player player) {
        int con = getStat(player, "CON");
        int siz = getStat(player, "SIZ");
        return (con + siz + 1) / 2;
    }

    public int getMp(Player player) {
        return getStat(player, "POW");
    }

    public int getCurrentHp(Player player) {
        String p = path(player.getUniqueId(), "current-hp");
        if (!data.contains(p)) {
            return getHp(player);
        }
        return data.getInt(p);
    }

    public boolean isDeadCharacter(Player player) {
        return hasConfiguredStats(player) && getCurrentHp(player) <= 0;
    }

    public void setCurrentHp(Player player, int value) {
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(path(player.getUniqueId(), "current-hp"), value);
        save();
    }

    public int getCurrentMp(Player player) {
        String p = path(player.getUniqueId(), "current-mp");
        if (!data.contains(p)) {
            return getMp(player);
        }
        return data.getInt(p);
    }

    public void setCurrentMp(Player player, int value) {
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(path(player.getUniqueId(), "current-mp"), value);
        save();
    }

    public int getSan(Player player) {
        return getStat(player, "POW") * 5;
    }

    public int getCurrentSan(Player player) {
        String p = path(player.getUniqueId(), "current-san");
        if (!data.contains(p)) {
            return getSan(player);
        }
        return data.getInt(p);
    }

    public void setCurrentSan(Player player, int value) {
        data.set(path(player.getUniqueId(), "name"), player.getName());
        data.set(path(player.getUniqueId(), "current-san"), value);
        save();
    }

    public int getIdea(Player player) {
        return getStat(player, "INT") * 5;
    }

    public int getLuck(Player player) {
        return getStat(player, "POW") * 5;
    }

    public int getKnowledge(Player player) {
        return getStat(player, "EDU") * 5;
    }

    public int getDerived(Player player, String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "idea" -> getIdea(player);
            case "luck" -> getLuck(player);
            case "knowledge" -> getKnowledge(player);
            default -> 0;
        };
    }

    public String getDerivedName(String id) {
        return switch (id.toLowerCase(Locale.ROOT)) {
            case "idea" -> "アイデア";
            case "luck" -> "幸運";
            case "knowledge" -> "知識";
            default -> id;
        };
    }

    private String path(UUID uuid, String suffix) {
        return "players." + uuid + "." + suffix;
    }

    public void resetPlayer(Player player) {
        data.set("players." + player.getUniqueId(), null);
        save();
    }

    public void resetAllPlayers() {
        data.set("players", null);
        save();
    }

    public void reload() {
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("players.yml の保存に失敗しました: " + e.getMessage());
        }
    }
}
