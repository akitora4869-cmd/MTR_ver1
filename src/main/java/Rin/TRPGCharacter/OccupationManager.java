package Rin.TRPGCharacter;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class OccupationManager {

    private final Plugin plugin;
    private final CharacterManager characterManager;
    private final SkillManager skillManager;
    private final File file;
    private final Map<String, OccupationDefinition> definitions = new LinkedHashMap<>();

    public OccupationManager(Plugin plugin, CharacterManager characterManager, SkillManager skillManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.skillManager = skillManager;
        this.file = new File(plugin.getDataFolder(), "occupations.yml");
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource("occupations.yml", false);
        }

        definitions.clear();

        // バンドル済みの基本職業を先に読み込み、既存サーバーの occupations.yml で
        // 同じIDが定義されていればそちらを優先する。
        try (var in = plugin.getResource("occupations.yml")) {
            if (in != null) {
                YamlConfiguration bundled = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)
                );
                loadDefinitions(bundled, false);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("内蔵 occupations.yml の読み込みに失敗しました: " + ex.getMessage());
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        loadDefinitions(config, true);
    }

    private void loadDefinitions(YamlConfiguration config, boolean overwrite) {
        ConfigurationSection root = config.getConfigurationSection("occupations");
        if (root == null) {
            return;
        }

        for (String id : root.getKeys(false)) {
            if (!overwrite && definitions.containsKey(id)) {
                continue;
            }

            String base = "occupations." + id;
            String name = config.getString(base + ".name", id);
            List<String> aliases = new ArrayList<>(config.getStringList(base + ".aliases"));
            String formula = config.getString(base + ".point-formula", "EDU*20");

            List<String> fixedSkills = validateSkills(
                    name, config.getStringList(base + ".fixed-skills")
            );

            // 旧形式 skills: も互換のため固定技能として扱う。
            if (fixedSkills.isEmpty() && config.isList(base + ".skills")) {
                fixedSkills = validateSkills(name, config.getStringList(base + ".skills"));
            }

            int restrictedCount = Math.max(
                    0,
                    config.getInt(base + ".restricted-choice.count", 0)
            );
            List<String> restrictedSkills = validateSkills(
                    name,
                    config.getStringList(base + ".restricted-choice.skills")
            );
            int freeCount = Math.max(0, config.getInt(base + ".free-choice-count", 0));

            definitions.put(id, new OccupationDefinition(
                    id,
                    name,
                    List.copyOf(aliases),
                    formula,
                    List.copyOf(fixedSkills),
                    restrictedCount,
                    List.copyOf(restrictedSkills),
                    freeCount
            ));
        }
    }

    private List<String> validateSkills(String occupationName, List<String> skillIds) {
        List<String> valid = new ArrayList<>();
        for (String skillId : skillIds) {
            if (skillManager.hasSkill(skillId)) {
                if (!valid.contains(skillId)) {
                    valid.add(skillId);
                }
            } else {
                plugin.getLogger().warning(
                        "occupations.yml: " + occupationName + " の技能IDが見つかりません: " + skillId
                );
            }
        }
        return valid;
    }

    public OccupationDefinition findByInput(String input) {
        String needle = normalize(input);
        if (needle.isEmpty()) {
            return null;
        }
        for (OccupationDefinition def : definitions.values()) {
            if (normalize(def.id()).equals(needle) || normalize(def.name()).equals(needle)) {
                return def;
            }
            for (String alias : def.aliases()) {
                if (normalize(alias).equals(needle)) {
                    return def;
                }
            }
        }
        return null;
    }

    public OccupationDefinition getDefinition(Player player) {
        if (characterManager.isCustomOccupation(player)) {
            return null;
        }
        return definitions.get(characterManager.getOccupationId(player));
    }

    public void setOccupationFromInput(Player player, String input) {
        OccupationDefinition matched = findByInput(input);
        if (matched != null) {
            characterManager.setOccupation(
                    player,
                    matched.id(),
                    matched.name(),
                    false,
                    matched.pointFormula(),
                    matched.fixedSkills()
            );
            return;
        }

        characterManager.setOccupation(
                player,
                "custom",
                input.trim(),
                true,
                plugin.getConfig().getString("occupation.default-point-formula", "EDU*20"),
                Collections.emptyList()
        );
    }

    public int getSelectionLimit() {
        return Math.max(
                1,
                plugin.getConfig().getInt("occupation.custom.max-selected-skills", 8)
        );
    }

    public int getOptionalSelectedCount(Player player) {
        OccupationDefinition def = getDefinition(player);
        if (def == null) {
            return characterManager.getOccupationSkills(player).size();
        }
        int count = 0;
        for (String skillId : characterManager.getOccupationSkills(player)) {
            if (!def.fixedSkills().contains(skillId)) {
                count++;
            }
        }
        return count;
    }

    public int getOptionalTotalCount(Player player) {
        if (characterManager.isCustomOccupation(player)) {
            return getSelectionLimit();
        }
        OccupationDefinition def = getDefinition(player);
        if (def == null) {
            return 0;
        }
        return def.restrictedChoiceCount() + def.freeChoiceCount();
    }

    public boolean isFixedSkill(Player player, String skillId) {
        OccupationDefinition def = getDefinition(player);
        return def != null && def.fixedSkills().contains(skillId);
    }

    public boolean isSkillSelectable(Player player, String skillId) {
        if (!skillManager.hasSkill(skillId) || !characterManager.hasOccupation(player)) {
            return false;
        }

        if (characterManager.isCustomOccupation(player)) {
            return true;
        }

        OccupationDefinition def = getDefinition(player);
        if (def == null || def.fixedSkills().contains(skillId)) {
            return false;
        }

        // すでに追加選択済みなら解除できる。
        if (characterManager.isOccupationSkill(player, skillId)) {
            return true;
        }

        ChoiceUsage usage = getChoiceUsage(player, def);
        boolean restrictedAvailable =
                def.restrictedChoiceSkills().contains(skillId)
                        && usage.restrictedUsed < def.restrictedChoiceCount();
        boolean freeAvailable = usage.freeUsed < def.freeChoiceCount();

        return restrictedAvailable || freeAvailable;
    }

    public boolean toggleOccupationSkill(Player player, String skillId) {
        if (!isSkillSelectable(player, skillId)) {
            return false;
        }

        if (characterManager.isOccupationSkill(player, skillId)) {
            if (isFixedSkill(player, skillId)) {
                return false;
            }
            characterManager.removeOccupationSkill(player, skillId);
            return true;
        }

        if (characterManager.isCustomOccupation(player)) {
            if (characterManager.getOccupationSkills(player).size() >= getSelectionLimit()) {
                return false;
            }
            characterManager.addOccupationSkill(player, skillId);
            return true;
        }

        OccupationDefinition def = getDefinition(player);
        if (def == null) {
            return false;
        }

        ChoiceUsage usage = getChoiceUsage(player, def);
        boolean canUseRestricted =
                def.restrictedChoiceSkills().contains(skillId)
                        && usage.restrictedUsed < def.restrictedChoiceCount();
        boolean canUseFree = usage.freeUsed < def.freeChoiceCount();

        if (!canUseRestricted && !canUseFree) {
            return false;
        }

        characterManager.addOccupationSkill(player, skillId);
        return true;
    }

    private ChoiceUsage getChoiceUsage(Player player, OccupationDefinition def) {
        List<String> extras = new ArrayList<>();
        for (String skillId : characterManager.getOccupationSkills(player)) {
            if (!def.fixedSkills().contains(skillId)) {
                extras.add(skillId);
            }
        }

        int restrictedUsed = 0;
        int freeUsed = 0;
        for (String skillId : extras) {
            if (def.restrictedChoiceSkills().contains(skillId)
                    && restrictedUsed < def.restrictedChoiceCount()) {
                restrictedUsed++;
            } else {
                freeUsed++;
            }
        }
        return new ChoiceUsage(restrictedUsed, freeUsed);
    }

    public String getSelectionDescription(Player player) {
        if (characterManager.isCustomOccupation(player)) {
            return "自由選択 " + characterManager.getOccupationSkills(player).size()
                    + "/" + getSelectionLimit();
        }

        OccupationDefinition def = getDefinition(player);
        if (def == null) {
            return "";
        }

        ChoiceUsage usage = getChoiceUsage(player, def);
        List<String> parts = new ArrayList<>();
        if (def.restrictedChoiceCount() > 0) {
            parts.add("候補選択 " + usage.restrictedUsed + "/" + def.restrictedChoiceCount());
        }
        if (def.freeChoiceCount() > 0) {
            parts.add("自由選択 " + usage.freeUsed + "/" + def.freeChoiceCount());
        }
        return String.join(" / ", parts);
    }

    private String normalize(String s) {
        if (s == null) return "";
        return s.trim()
                .replace("　", "")
                .replace(" ", "")
                .replace("／", "/")
                .toLowerCase(Locale.ROOT);
    }

    private record ChoiceUsage(int restrictedUsed, int freeUsed) {
    }
}
