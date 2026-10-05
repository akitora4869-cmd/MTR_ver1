package Rin.TRPGCharacter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * リソースパック内包型NPC。
 *
 * Interaction = 右クリック判定
 * ItemDisplay = NPC外見
 * TextDisplay = 名前表示
 *
 * NPC設定/位置は plugins/TRPGCharacter/npcs.yml に保存される。
 */
public class NpcManager implements Listener, TabExecutor {

    private static final int DEFAULT_MODEL_DATA = 93200;

    private final Plugin plugin;
    private final KeeperManager keeperManager;
    private final File file;
    private final NamespacedKey npcIdKey;
    private final NamespacedKey npcPartKey;

    private YamlConfiguration config;
    private final Map<String, SpawnedNpc> spawned = new HashMap<>();

    public NpcManager(Plugin plugin, KeeperManager keeperManager) {
        this.plugin = plugin;
        this.keeperManager = keeperManager;
        this.file = new File(plugin.getDataFolder(), "npcs.yml");
        this.npcIdKey = new NamespacedKey(plugin, "npc_id");
        this.npcPartKey = new NamespacedKey(plugin, "npc_part");
        reload();
    }

    public void reload() {
        if (!file.exists()) {
            plugin.saveResource("npcs.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        respawnAll();
    }

    public void shutdown() {
        removeSpawnedEntities();
    }

    private boolean canManage(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return sender.hasPermission("trpg.admin");
        }
        return player.isOp()
                || player.hasPermission("trpg.admin")
                || keeperManager.isKeeper(player);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!canManage(sender)) {
            sender.sendMessage(color("&cKPまたは管理者のみ使用できます。"));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("list")) {
            sender.sendMessage(color("&6[NPC] &f登録一覧"));
            ConfigurationSection sec = config.getConfigurationSection("npcs");
            if (sec == null || sec.getKeys(false).isEmpty()) {
                sender.sendMessage(color("&7登録NPCはありません。"));
                return true;
            }
            for (String id : sec.getKeys(false)) {
                sender.sendMessage(color("&7- &e" + id + " &f: "
                        + config.getString("npcs." + id + ".name", id)));
            }
            return true;
        }

        if (sub.equals("reload")) {
            reload();
            sender.sendMessage(color("&aNPC設定を再読み込みしました。"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("この操作はゲーム内プレイヤーから実行してください。");
            return true;
        }

        if (sub.equals("create") && args.length >= 3) {
            String id = safeId(args[1]);
            if (id == null) {
                player.sendMessage(color("&cIDは英数字・_・- のみ使用できます。"));
                return true;
            }
            if (config.contains("npcs." + id)) {
                player.sendMessage(color("&cそのNPC IDは既に存在します。"));
                return true;
            }

            String name = join(args, 2);
            Location loc = player.getLocation();

            String base = "npcs." + id;
            config.set(base + ".name", name);
            config.set(base + ".model-data", DEFAULT_MODEL_DATA);
            config.set(base + ".scale", 1.0);
            config.set(base + ".location.world", loc.getWorld().getName());
            config.set(base + ".location.x", loc.getX());
            config.set(base + ".location.y", loc.getY());
            config.set(base + ".location.z", loc.getZ());
            config.set(base + ".location.yaw", loc.getYaw());
            config.set(base + ".location.pitch", 0.0);
            config.set(base + ".dialogue", List.of(
                    "こんにちは。私は " + name + " です。",
                    "npcs.yml の dialogue を編集すると会話内容を変更できます。"
            ));
            save();

            spawnOne(id);
            player.sendMessage(color("&aNPCを作成しました: &e" + id + " &f(" + name + ")"));
            return true;
        }

        if (sub.equals("delete") && args.length == 2) {
            String id = args[1].toLowerCase(Locale.ROOT);
            if (!config.contains("npcs." + id)) {
                player.sendMessage(color("&cNPCが見つかりません。"));
                return true;
            }
            removeOne(id);
            config.set("npcs." + id, null);
            save();
            player.sendMessage(color("&aNPCを削除しました: &e" + id));
            return true;
        }

        if (sub.equals("move") && args.length == 2) {
            String id = args[1].toLowerCase(Locale.ROOT);
            if (!config.contains("npcs." + id)) {
                player.sendMessage(color("&cNPCが見つかりません。"));
                return true;
            }
            Location loc = player.getLocation();
            String base = "npcs." + id + ".location";
            config.set(base + ".world", loc.getWorld().getName());
            config.set(base + ".x", loc.getX());
            config.set(base + ".y", loc.getY());
            config.set(base + ".z", loc.getZ());
            config.set(base + ".yaw", loc.getYaw());
            config.set(base + ".pitch", 0.0);
            save();
            removeOne(id);
            spawnOne(id);
            player.sendMessage(color("&aNPCを現在位置へ移動しました。"));
            return true;
        }

        if (sub.equals("say") && args.length >= 3) {
            String id = args[1].toLowerCase(Locale.ROOT);
            if (!config.contains("npcs." + id)) {
                player.sendMessage(color("&cNPCが見つかりません。"));
                return true;
            }
            String text = join(args, 2);
            config.set("npcs." + id + ".dialogue", List.of(text));
            save();
            player.sendMessage(color("&aNPCの会話を設定しました。"));
            return true;
        }

        if (sub.equals("addline") && args.length >= 3) {
            String id = args[1].toLowerCase(Locale.ROOT);
            if (!config.contains("npcs." + id)) {
                player.sendMessage(color("&cNPCが見つかりません。"));
                return true;
            }
            String path = "npcs." + id + ".dialogue";
            List<String> lines = new ArrayList<>(config.getStringList(path));
            lines.add(join(args, 2));
            config.set(path, lines);
            save();
            player.sendMessage(color("&a会話文を追加しました。"));
            return true;
        }

        if (sub.equals("clearlines") && args.length == 2) {
            String id = args[1].toLowerCase(Locale.ROOT);
            if (!config.contains("npcs." + id)) {
                player.sendMessage(color("&cNPCが見つかりません。"));
                return true;
            }
            config.set("npcs." + id + ".dialogue", new ArrayList<String>());
            save();
            player.sendMessage(color("&a会話文をすべて削除しました。"));
            return true;
        }

        sendHelp(player);
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&6--- NPC コマンド ---"));
        sender.sendMessage(color("&e/npc create <id> <表示名> &7- 現在位置に作成"));
        sender.sendMessage(color("&e/npc delete <id> &7- 削除"));
        sender.sendMessage(color("&e/npc move <id> &7- 現在位置へ移動"));
        sender.sendMessage(color("&e/npc say <id> <文章> &7- 会話を1行に置換"));
        sender.sendMessage(color("&e/npc addline <id> <文章> &7- 会話行を追加"));
        sender.sendMessage(color("&e/npc clearlines <id> &7- 会話を空にする"));
        sender.sendMessage(color("&e/npc list"));
        sender.sendMessage(color("&e/npc reload"));
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        String id = event.getRightClicked().getPersistentDataContainer().get(
                npcIdKey,
                PersistentDataType.STRING
        );
        if (id == null) {
            return;
        }

        String part = event.getRightClicked().getPersistentDataContainer().get(
                npcPartKey,
                PersistentDataType.STRING
        );
        if (!"interaction".equals(part)) {
            return;
        }

        event.setCancelled(true);
        talk(event.getPlayer(), id);
    }

    private void talk(Player player, String id) {
        String base = "npcs." + id;
        if (!config.contains(base)) {
            return;
        }

        String name = config.getString(base + ".name", id);
        List<String> lines = config.getStringList(base + ".dialogue");

        player.sendMessage(color("&6[" + name + "]"));
        if (lines.isEmpty()) {
            player.sendMessage(color("&7……"));
            return;
        }
        for (String line : lines) {
            player.sendMessage(color("&f" + line));
        }
    }

    private void respawnAll() {
        if (!plugin.isEnabled()) {
            return;
        }

        removeSpawnedEntities();
        cleanupOrphans();

        ConfigurationSection sec = config.getConfigurationSection("npcs");
        if (sec == null) {
            return;
        }
        for (String id : sec.getKeys(false)) {
            spawnOne(id);
        }
    }

    private void spawnOne(String id) {
        String base = "npcs." + id;
        String worldName = config.getString(base + ".location.world");
        if (worldName == null) {
            return;
        }
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("NPC '" + id + "' のワールドが見つかりません: " + worldName);
            return;
        }

        Location loc = new Location(
                world,
                config.getDouble(base + ".location.x"),
                config.getDouble(base + ".location.y"),
                config.getDouble(base + ".location.z"),
                (float) config.getDouble(base + ".location.yaw"),
                0.0f
        );

        int modelData = config.getInt(base + ".model-data", DEFAULT_MODEL_DATA);
        double rawScale = config.getDouble(base + ".scale", 1.0);
        float scale = (float) Math.max(0.5, Math.min(3.0, rawScale));
        String name = config.getString(base + ".name", id);

        Interaction interaction = world.spawn(loc, Interaction.class);
        interaction.setInteractionWidth(0.8f * scale);
        interaction.setInteractionHeight(1.9f * scale);
        interaction.setResponsive(true);
        tag(interaction, id, "interaction");

        ItemDisplay visual = world.spawn(loc.clone().add(0.0, 0.95 * scale, 0.0), ItemDisplay.class);
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(modelData);
        item.setItemMeta(meta);

        visual.setItemStack(item);
        visual.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        visual.setBillboard(Display.Billboard.FIXED);
        visual.setViewRange(8.0f);
        visual.setPersistent(false);
        visual.setInvulnerable(true);
        visual.setGravity(false);
        tag(visual, id, "visual");

        float yaw = (float) Math.toRadians(-loc.getYaw() + 180.0f);
        visual.setTransformation(new Transformation(
                new Vector3f(0.0f, -0.95f * scale, 0.0f),
                new Quaternionf().rotateY(yaw),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        ));

        TextDisplay nameTag = world.spawn(loc.clone().add(0.0, 2.15 * scale, 0.0), TextDisplay.class);
        nameTag.text(Component.text(name, NamedTextColor.GOLD));
        nameTag.setBillboard(Display.Billboard.CENTER);
        nameTag.setSeeThrough(true);
        nameTag.setShadowed(true);
        nameTag.setViewRange(8.0f);
        nameTag.setPersistent(false);
        nameTag.setInvulnerable(true);
        nameTag.setGravity(false);
        tag(nameTag, id, "name");

        spawned.put(id, new SpawnedNpc(interaction, visual, nameTag));
    }

    private void removeOne(String id) {
        SpawnedNpc npc = spawned.remove(id);
        if (npc != null) {
            npc.remove();
        }
    }

    private void removeSpawnedEntities() {
        for (SpawnedNpc npc : spawned.values()) {
            npc.remove();
        }
        spawned.clear();
    }

    private void cleanupOrphans() {
        for (World world : plugin.getServer().getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
                if (entity.getPersistentDataContainer().has(npcPartKey, PersistentDataType.STRING)) {
                    entity.remove();
                }
            }
        }
    }

    private void tag(org.bukkit.entity.Entity entity, String id, String part) {
        entity.getPersistentDataContainer().set(npcIdKey, PersistentDataType.STRING, id);
        entity.getPersistentDataContainer().set(npcPartKey, PersistentDataType.STRING, part);
    }

    private void save() {
        try {
            config.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("npcs.yml の保存に失敗しました: " + ex.getMessage());
        }
    }

    private String safeId(String raw) {
        String id = raw.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]+")) {
            return null;
        }
        return id;
    }

    private String join(String[] args, int start) {
        StringBuilder b = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) {
                b.append(' ');
            }
            b.append(args[i]);
        }
        return b.toString();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!canManage(sender)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return filter(List.of(
                    "create", "delete", "move", "say", "addline",
                    "clearlines", "list", "reload"
            ), args[0]);
        }

        if (args.length == 2 && !args[0].equalsIgnoreCase("create")) {
            ConfigurationSection sec = config.getConfigurationSection("npcs");
            if (sec == null) {
                return Collections.emptyList();
            }
            return filter(new ArrayList<>(sec.getKeys(false)), args[1]);
        }

        return Collections.emptyList();
    }

    private List<String> filter(List<String> source, String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : source) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                out.add(value);
            }
        }
        return out;
    }

    private record SpawnedNpc(Interaction interaction, ItemDisplay visual, TextDisplay nameTag) {
        private void remove() {
            if (interaction != null && interaction.isValid()) interaction.remove();
            if (visual != null && visual.isValid()) visual.remove();
            if (nameTag != null && nameTag.isValid()) nameTag.remove();
        }
    }
}
