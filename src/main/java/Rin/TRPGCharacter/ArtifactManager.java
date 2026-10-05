package Rin.TRPGCharacter;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ArtifactManager implements Listener {

    private static final Pattern DICE =
            Pattern.compile("^(\\d+)[dD](\\d+)(?:([+-])(\\d+))?$");

    private final Plugin plugin;
    private final CharacterManager characterManager;
    private final File file;
    private final NamespacedKey artifactIdKey;
    private final NamespacedKey anchorWorldKey;
    private final NamespacedKey anchorXKey;
    private final NamespacedKey anchorYKey;
    private final NamespacedKey anchorZKey;
    private final NamespacedKey anchorYawKey;
    private final NamespacedKey anchorPitchKey;
    private final NamespacedKey useCountKey;
    private final Map<String, ArtifactDefinition> definitions = new HashMap<>();
    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> ygolonacTargetCooldowns = new HashMap<>();
    private final Random random = new Random();

    private YamlConfiguration config;

    public ArtifactManager(Plugin plugin, CharacterManager characterManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.file = new File(plugin.getDataFolder(), "artifacts.yml");
        this.artifactIdKey = new NamespacedKey(plugin, "artifact_id");
        this.anchorWorldKey = new NamespacedKey(plugin, "artifact_anchor_world");
        this.anchorXKey = new NamespacedKey(plugin, "artifact_anchor_x");
        this.anchorYKey = new NamespacedKey(plugin, "artifact_anchor_y");
        this.anchorZKey = new NamespacedKey(plugin, "artifact_anchor_z");
        this.anchorYawKey = new NamespacedKey(plugin, "artifact_anchor_yaw");
        this.anchorPitchKey = new NamespacedKey(plugin, "artifact_anchor_pitch");
        this.useCountKey = new NamespacedKey(plugin, "artifact_use_count");

        if (!file.exists()) plugin.saveResource("artifacts.yml", false);
        reload();
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);

        // 既存サーバーのartifacts.ymlにも、新しく追加された標準アーティファクトを自動補完する。
        try (InputStream in = plugin.getResource("artifacts.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)
                );
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
                config.save(file);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("artifacts.yml の標準設定補完に失敗しました: " + e.getMessage());
        }

        // 旧版の夢のクリスタライザーを、座標ワープからドリームランド連携へ移行。
        String crystalType = config.getString(
                "artifacts.dream_crystalizer.active.effect.type", ""
        );
        if ("DREAM_ANCHOR".equalsIgnoreCase(crystalType)) {
            config.set("artifacts.dream_crystalizer.name", "夢のクリスタルライザー");
            config.set("artifacts.dream_crystalizer.description", java.util.List.of(
                    "夢と現実の境界を越えるための結晶。",
                    "SANとMPを代償にドリームランドへの道を開く。",
                    "KPがドリームランドを無効化している場合は発動しない。"
            ));
            config.set(
                    "artifacts.dream_crystalizer.active.effect.type",
                    "DREAMLAND_CRYSTALIZER"
            );
            try {
                config.save(file);
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "夢のクリスタルライザー設定の移行に失敗しました: " + e.getMessage()
                );
            }
        }

        definitions.clear();
        ConfigurationSection root = config.getConfigurationSection("artifacts");
        if (root == null) return;

        for (String id : root.getKeys(false)) {
            String base = "artifacts." + id;
            definitions.put(id.toLowerCase(), new ArtifactDefinition(
                    id.toLowerCase(),
                    config.getString(base + ".name", id),
                    config.getString(base + ".material", "PAPER"),
                    Math.max(0, config.getInt(base + ".custom-model-data", 0)),
                    config.getStringList(base + ".description"),
                    config.getBoolean(base + ".active.enabled", false),
                    config.getString(base + ".active.effect.type", "NONE"),
                    Math.max(0, config.getInt(base + ".active.cooldown-seconds", 0)),
                    config.getString(base + ".active.san-cost", "0"),
                    Math.max(0, config.getInt(base + ".active.mp-cost", 0)),
                    Math.max(0.0, config.getDouble(base + ".active.effect.range", 0.0)),
                    Math.max(0, config.getInt(base + ".active.effect.duration-seconds", 0)),
                    Math.max(0, config.getInt(base + ".passive.mythos-damage-reduction", 0))
            ));
        }
    }

    public TreeSet<String> getIds() { return new TreeSet<>(definitions.keySet()); }
    public ArtifactDefinition getDefinition(String id) {
        return id == null ? null : definitions.get(id.toLowerCase());
    }

    public ArtifactDefinition getDefinition(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String id = item.getItemMeta().getPersistentDataContainer()
                .get(artifactIdKey, PersistentDataType.STRING);
        return getDefinition(id);
    }

    public ItemStack createItem(String id) {
        ArtifactDefinition d = getDefinition(id);
        if (d == null) return null;
        Material material = Material.matchMaterial(d.material());
        if (material == null) {
            plugin.getLogger().warning("artifacts.yml のmaterialが不正です: " + d.id() + " -> " + d.material());
            return null;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&5" + d.name()));
        if (d.customModelData() > 0) {
            meta.setCustomModelData(d.customModelData());
        }
        List<String> lore = new ArrayList<>();
        for (String line : d.lore()) lore.add(color("&7" + line));
        if (d.activeEnabled()) {
            lore.add("");
            lore.add(color("&d右クリックで使用"));
            if (d.cooldownSeconds() > 0) lore.add(color("&8クールダウン: " + d.cooldownSeconds() + "秒"));
            if (!"0".equals(d.sanCost())) lore.add(color("&8SAN消費: " + d.sanCost()));
            if (d.mpCost() > 0) lore.add(color("&8MP消費: " + d.mpCost()));
        }
        if (d.mythosDamageReduction() > 0) {
            lore.add(color("&8所持中: 神話生物からのダメージ -" + d.mythosDamageReduction()));
        }
        meta.setLore(lore);
        meta.getPersistentDataContainer().set(artifactIdKey, PersistentDataType.STRING, d.id());
        item.setItemMeta(meta);
        return item;
    }

    public int getMythosDamageReduction(Player player) {
        int best = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            ArtifactDefinition d = getDefinition(item);
            if (d != null) best = Math.max(best, d.mythosDamageReduction());
        }
        return best;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        ArtifactDefinition d = getDefinition(item);
        if (d == null || !d.activeEnabled()) return;

        // アーティファクトは他プラグイン/バニラ側でinteractionがcancel扱いでも、
        // このプラグイン自身の使用判定を先に行う。時間停止等はactivateArtifact内で確認する。
        event.setCancelled(true);
        activateArtifact(event.getPlayer(), item, d, null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onUseOnEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        ItemStack item = event.getPlayer().getInventory().getItemInMainHand();
        ArtifactDefinition d = getDefinition(item);
        if (d == null || !d.activeEnabled()) return;

        // イゴーロナクの手は「プレイヤーを右クリック」した場合も確実に対象を取る。
        Player directTarget = event.getRightClicked() instanceof Player p ? p : null;
        event.setCancelled(true);
        activateArtifact(event.getPlayer(), item, d, directTarget);
    }

    private void activateArtifact(Player player, ItemStack item, ArtifactDefinition d, Player directTarget) {
        if (!characterManager.hasConfiguredStats(player) || characterManager.isDeadCharacter(player)) {
            player.sendMessage(color("&cこの状態ではアーティファクトを使用できません。"));
            return;
        }
        if (plugin.getTimeStopManager().isStopped() && !plugin.getTimeStopManager().canAct(player)) {
            player.sendMessage(color("&c時間停止中はアーティファクトを使用できません。"));
            return;
        }

        String type = d.activeEffectType().toUpperCase();
        // 旧artifacts.ymlのDREAM_ANCHORも、新しいドリームランド連携へ自動移行する。
        if ("dream_crystalizer".equals(d.id()) && "DREAM_ANCHOR".equals(type)) {
            type = "DREAMLAND_CRYSTALIZER";
        }

        // 座標記憶型は初回使用を「登録」とし、代償・クールダウンを発生させない。
        if (("DREAM_ANCHOR".equals(type) || "SILVER_KEY".equals(type)) && !hasAnchor(item)) {
            bindAnchor(player, item, d, "SILVER_KEY".equals(type));
            return;
        }

        if ("DREAMLAND_CRYSTALIZER".equals(type)) {
            DreamlandsManager dreamlands = plugin.getDreamlandsManager();
            if (dreamlands == null || !dreamlands.canEnter(player, true)) {
                player.sendMessage(color("&c現在、ドリームランドへの移動はできません。KP設定またはセッション状態を確認してください。"));
                return;
            }
        }

        long remaining = getRemainingCooldown(player, d.id());
        if (remaining > 0) {
            player.sendMessage(color("&c" + d.name() + " はあと " + remaining + " 秒で使用できます。"));
            return;
        }

        int sanCost = roll(d.sanCost());
        if ("TRAPEZOHEDRON_VISION".equals(type) && getEyeLight(player) <= 4) sanCost += 1;
        int currentSan = characterManager.getCurrentSan(player);
        int currentMp = characterManager.getCurrentMp(player);

        if (sanCost > currentSan) {
            player.sendMessage(color("&cSANが足りないため使用できません。"));
            return;
        }
        if (d.mpCost() > currentMp) {
            player.sendMessage(color("&cMPが足りないため使用できません。"));
            return;
        }

        // イゴーロナクの手は対象がいない場合、代償を払わない。
        Player yTarget = directTarget;
        if ("YGOLONAC_HAND".equals(type)) {
            if (yTarget == player) yTarget = null;
            if (yTarget == null) {
                yTarget = findTargetPlayer(player, Math.max(1.0, d.range()));
            }
            if (yTarget == null) {
                player.sendMessage(color("&c10ブロック以内のプレイヤーを見て使用してください。"));
                return;
            }
            long targetRemain = getYgolonacTargetRemaining(player, yTarget);
            if (targetRemain > 0) {
                player.sendMessage(color("&cその対象にはあと " + targetRemain + " 秒使用できません。"));
                return;
            }
        }

        if (sanCost > 0) {
            characterManager.setCurrentSan(player, Math.max(0, currentSan - sanCost));
            player.sendMessage(color("&5[アーティファクト] &fSAN " + currentSan + " → "
                    + Math.max(0, currentSan - sanCost) + " &7(-" + sanCost + ")"));
        }
        if (d.mpCost() > 0) {
            characterManager.setCurrentMp(player, Math.max(0, currentMp - d.mpCost()));
            player.sendMessage(color("&5[アーティファクト] &fMP " + currentMp + " → "
                    + Math.max(0, currentMp - d.mpCost()) + " &7(-" + d.mpCost() + ")"));
        }
        plugin.getSidebarManager().updatePlayer(player);

        boolean activated = activate(player, item, d, yTarget);
        if (!activated) return;
        armCooldown(player, d);
        player.sendMessage(color("&5[アーティファクト] &f" + d.name() + " &dが発動した。"));
    }

    private boolean activate(Player player, ItemStack item, ArtifactDefinition d, Player target) {
        String type = d.activeEffectType().toUpperCase();
        if ("dream_crystalizer".equals(d.id()) && "DREAM_ANCHOR".equals(type)) {
            type = "DREAMLAND_CRYSTALIZER";
        }

        return switch (type) {
            case "REPEL_MYTHOS" -> repelMythos(player, d);
            case "TRAPEZOHEDRON_VISION" -> trapezohedronVision(player, d);
            case "DREAM_ANCHOR" -> teleportToAnchor(player, item, d, false);
            case "DREAMLAND_CRYSTALIZER" -> plugin.getDreamlandsManager().enterByCrystalizer(player);
            case "YGOLONAC_HAND" -> ygolonacHand(player, target, d);
            case "SILVER_KEY" -> teleportToAnchor(player, item, d, true);
            default -> {
                player.sendMessage(color("&cこのアーティファクトの効果タイプは未実装です: " + d.activeEffectType()));
                yield false;
            }
        };
    }

    private boolean repelMythos(Player player, ArtifactDefinition d) {
        int affected = 0;
        for (Entity entity : player.getNearbyEntities(d.range(), d.range(), d.range())) {
            if (!(entity instanceof LivingEntity living) || plugin.getMythosManager().getDefinition(entity) == null) continue;
            Vector away = living.getLocation().toVector().subtract(player.getLocation().toVector());
            if (away.lengthSquared() < 0.0001) away = player.getLocation().getDirection().clone();
            away.normalize().multiply(1.15);
            away.setY(Math.max(0.25, away.getY() + 0.25));
            living.setVelocity(away);
            living.setGlowing(true);
            affected++;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (living.isValid() && !living.isDead()) living.setGlowing(false);
            }, Math.max(1, d.durationSeconds()) * 20L);
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.4f);
        player.sendMessage(color("&d古き印の力が周囲へ広がった。 &7対象: &f" + affected + "体"));
        return true;
    }

    private boolean trapezohedronVision(Player player, ArtifactDefinition d) {
        int affected = 0;
        List<LivingEntity> marked = new ArrayList<>();
        for (Entity entity : player.getNearbyEntities(d.range(), d.range(), d.range())) {
            if (!(entity instanceof LivingEntity living) || plugin.getMythosManager().getDefinition(entity) == null) continue;
            living.setGlowing(true);
            marked.add(living);
            affected++;
        }
        int ticks = Math.max(1, d.durationSeconds()) * 20;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            for (LivingEntity living : marked) if (living.isValid() && !living.isDead()) living.setGlowing(false);
        }, ticks);

        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 80, 0, false, false, false));
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.0f, 0.55f);
        player.sendMessage(color("&5石の内部に、この世界ではない何かを見た。"));
        if (getEyeLight(player) <= 4) {
            player.sendMessage(color("&4暗闇の向こう側から、何かがこちらを見返している。"));
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_STARE, 0.7f, 0.55f);
        }
        player.sendMessage(color("&7異界視で捉えた神話生物: &f" + affected + "体"));
        return true;
    }

    private void bindAnchor(Player player, ItemStack item, ArtifactDefinition d, boolean silverKey) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Location loc = player.getLocation();
        pdc.set(anchorWorldKey, PersistentDataType.STRING, loc.getWorld().getUID().toString());
        pdc.set(anchorXKey, PersistentDataType.DOUBLE, loc.getX());
        pdc.set(anchorYKey, PersistentDataType.DOUBLE, loc.getY());
        pdc.set(anchorZKey, PersistentDataType.DOUBLE, loc.getZ());
        pdc.set(anchorYawKey, PersistentDataType.FLOAT, loc.getYaw());
        pdc.set(anchorPitchKey, PersistentDataType.FLOAT, loc.getPitch());
        item.setItemMeta(meta);
        if (silverKey) {
            player.sendMessage(color("&7[銀の鍵] &fこの場所が鍵に刻まれた。"));
        } else {
            player.sendMessage(color("&b[夢のクリスタライザー] &fこの場所の夢が結晶に記憶された。"));
        }
        player.getWorld().playSound(loc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 0.8f);
    }

    private boolean teleportToAnchor(Player player, ItemStack item, ArtifactDefinition d, boolean crossWorld) {
        Location anchor = readAnchor(item);
        if (anchor == null) {
            player.sendMessage(color("&c記憶された場所を読み取れません。"));
            return false;
        }
        if (!crossWorld && !anchor.getWorld().equals(player.getWorld())) {
            player.sendMessage(color("&c夢のクリスタライザーは別ワールドへ移動できません。"));
            return false;
        }

        player.sendMessage(color(crossWorld
                ? "&7銀の鍵が、存在しないはずの門を開いた……"
                : "&b現実が夢へと溶けていく……"));
        player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.CONFUSION, 100, 0, false, false, false));
        player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0,1,0), 60, .5, .8, .5, .2);
        player.teleport(anchor);
        player.setFallDistance(0.0f);
        player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0,1,0), 60, .5, .8, .5, .2);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.45f, crossWorld ? 0.6f : 1.15f);

        if (crossWorld) {
            int uses = incrementUseCount(item);
            if (uses >= 3 && random.nextDouble() < Math.min(0.50, 0.10 + (uses - 3) * 0.04)) {
                String[] omens = {
                        "門の向こうから、あなたを知っている何かの気配を感じる。",
                        "自分が『ここ』にいるという感覚が、一瞬だけ失われた。",
                        "鍵を回していないのに、どこかで門が開く音がした。"
                };
                player.sendMessage(color("&5" + omens[random.nextInt(omens.length)]));
                player.getWorld().playSound(player.getLocation(), Sound.AMBIENT_CAVE, 0.7f, 0.55f);
            }
        }
        return true;
    }

    private boolean ygolonacHand(Player user, Player target, ArtifactDefinition d) {
        int loss = roll("1d3");
        int before = characterManager.getCurrentSan(target);
        characterManager.setCurrentSan(target, Math.max(0, before - loss));
        plugin.getSidebarManager().updatePlayer(target);
        int ticks = Math.max(1, d.durationSeconds()) * 20;
        target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks, 0, false, false, false));
        target.addPotionEffect(new PotionEffect(PotionEffectType.CONFUSION, ticks, 0, false, false, false));
        target.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, ticks, 0, false, false, false));
        user.sendMessage(color("&4掌の口が開き、名状しがたい言葉を囁いた。"));
        target.sendMessage(color("&4自分のものではない欲望が、心の奥から湧き上がってくる……"));
        target.sendMessage(color("&5[アーティファクト] &fSAN " + before + " → " + Math.max(0, before-loss) + " &7(-" + loss + ")"));
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 0.7f, 0.6f);
        ygolonacTargetCooldowns.computeIfAbsent(user.getUniqueId(), k -> new HashMap<>())
                .put(target.getUniqueId(), System.currentTimeMillis() + 600_000L);
        return true;
    }

    private Player findTargetPlayer(Player player, double range) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(), player.getEyeLocation().getDirection(),
                range, 0.35, e -> e instanceof Player && e != player);
        return result != null && result.getHitEntity() instanceof Player p ? p : null;
    }

    private long getYgolonacTargetRemaining(Player user, Player target) {
        long next = ygolonacTargetCooldowns
                .getOrDefault(user.getUniqueId(), Map.of())
                .getOrDefault(target.getUniqueId(), 0L);
        if (next <= System.currentTimeMillis()) return 0;
        return Math.max(1L, (next - System.currentTimeMillis() + 999L) / 1000L);
    }

    private boolean hasAnchor(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(anchorWorldKey, PersistentDataType.STRING);
    }

    private Location readAnchor(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        PersistentDataContainer pdc = item.getItemMeta().getPersistentDataContainer();
        String worldId = pdc.get(anchorWorldKey, PersistentDataType.STRING);
        Double x = pdc.get(anchorXKey, PersistentDataType.DOUBLE);
        Double y = pdc.get(anchorYKey, PersistentDataType.DOUBLE);
        Double z = pdc.get(anchorZKey, PersistentDataType.DOUBLE);
        if (worldId == null || x == null || y == null || z == null) return null;
        World world;
        try { world = plugin.getServer().getWorld(UUID.fromString(worldId)); }
        catch (IllegalArgumentException e) { return null; }
        if (world == null) return null;
        Float yaw = pdc.get(anchorYawKey, PersistentDataType.FLOAT);
        Float pitch = pdc.get(anchorPitchKey, PersistentDataType.FLOAT);
        return new Location(world, x, y, z, yaw == null ? 0f : yaw, pitch == null ? 0f : pitch);
    }

    private int incrementUseCount(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int uses = pdc.getOrDefault(useCountKey, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(useCountKey, PersistentDataType.INTEGER, uses);
        item.setItemMeta(meta);
        return uses;
    }

    private int getEyeLight(Player player) {
        return player.getEyeLocation().getBlock().getLightLevel();
    }

    private long getRemainingCooldown(Player player, String artifactId) {
        Map<String, Long> map = cooldowns.get(player.getUniqueId());
        if (map == null) return 0;
        long next = map.getOrDefault(artifactId, 0L);
        if (System.currentTimeMillis() >= next) return 0;
        return Math.max(1L, (next - System.currentTimeMillis() + 999L) / 1000L);
    }

    private void armCooldown(Player player, ArtifactDefinition d) {
        if (d.cooldownSeconds() <= 0) return;
        cooldowns.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                .put(d.id(), System.currentTimeMillis() + d.cooldownSeconds() * 1000L);
    }

    private int roll(String expression) {
        if (expression == null || expression.isBlank()) return 0;
        String value = expression.trim();
        try { return Math.max(0, Integer.parseInt(value)); }
        catch (NumberFormatException ignored) {}
        Matcher matcher = DICE.matcher(value);
        if (!matcher.matches()) {
            plugin.getLogger().warning("artifacts.yml のダイス式を解釈できません: " + expression);
            return 0;
        }
        int count = Integer.parseInt(matcher.group(1));
        int sides = Integer.parseInt(matcher.group(2));
        if (count < 1 || count > 100 || sides < 1 || sides > 100000) return 0;
        int modifier = 0;
        if (matcher.group(3) != null && matcher.group(4) != null) {
            int raw = Integer.parseInt(matcher.group(4));
            modifier = "-".equals(matcher.group(3)) ? -raw : raw;
        }
        int total = modifier;
        for (int i = 0; i < count; i++) total += random.nextInt(sides) + 1;
        return Math.max(0, total);
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
