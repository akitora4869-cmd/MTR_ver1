package Rin.TRPGCharacter;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.Door;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class DoorLockManager implements Listener, CommandExecutor, TabCompleter {

    private enum LockState {
        LOCKED, HARDLOCK, UNLOCKED
    }

    private static final String DEFAULT_KEY_NAME = "扉の鍵";
    private static final String DEFAULT_HAMMER_NAME = "大型ハンマー";

    private final Plugin plugin;
    private final SkillManager skillManager;
    private final KeeperManager keeperManager;
    private final File file;
    private final File breachWallFile;
    private final NamespacedKey keyIdTag;
    private final NamespacedKey breachHammerTag;
    private final Map<String, LockState> states = new HashMap<>();
    private final Map<String, String> requiredKeys = new HashMap<>();
    private final Map<UUID, Long> rollCooldown = new HashMap<>();
    private final Map<UUID, Map<String, Long>> failedAttempts = new HashMap<>();
    private final Set<String> breachWalls = new HashSet<>();

    public DoorLockManager(Plugin plugin, SkillManager skillManager, KeeperManager keeperManager) {
        this.plugin = plugin;
        this.skillManager = skillManager;
        this.keeperManager = keeperManager;
        this.file = new File(plugin.getDataFolder(), "doors.yml");
        this.breachWallFile = new File(plugin.getDataFolder(), "breach-walls.yml");
        this.keyIdTag = new NamespacedKey(plugin, "door_key_id");
        this.breachHammerTag = new NamespacedKey(plugin, "breach_hammer");
        load();
        loadBreachWalls();
    }

    public void load() {
        states.clear();
        requiredKeys.clear();
        if (!file.exists()) {
            save();
            return;
        }

        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = config.getConfigurationSection("doors");
        if (section == null) return;

        for (String doorKey : section.getKeys(false)) {
            Object rawValue = section.get(doorKey);

            // 旧形式: doors.<座標>: LOCKED
            if (rawValue instanceof String rawState) {
                putState(doorKey, rawState);
                continue;
            }

            // 新形式: doors.<座標>.state / key-id
            ConfigurationSection doorSection = section.getConfigurationSection(doorKey);
            if (doorSection == null) continue;
            putState(doorKey, doorSection.getString("state"));
            String requiredKey = normalizeKeyId(doorSection.getString("key-id"));
            if (requiredKey != null) {
                requiredKeys.put(doorKey, requiredKey);
            }
        }
    }

    private void putState(String doorKey, String rawState) {
        if (rawState == null) return;
        try {
            states.put(doorKey, LockState.valueOf(rawState.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
        }
    }

    private void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<String, LockState> entry : states.entrySet()) {
            String base = "doors." + entry.getKey();
            config.set(base + ".state", entry.getValue().name());
            String requiredKey = requiredKeys.get(entry.getKey());
            if (requiredKey != null) {
                config.set(base + ".key-id", requiredKey);
            }
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("doors.yml の保存に失敗しました: " + e.getMessage());
        }
    }

    private void loadBreachWalls() {
        breachWalls.clear();
        if (!breachWallFile.exists()) {
            saveBreachWalls();
            return;
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(breachWallFile);
        breachWalls.addAll(config.getStringList("walls"));
    }

    private void saveBreachWalls() {
        YamlConfiguration config = new YamlConfiguration();
        List<String> sorted = new ArrayList<>(breachWalls);
        Collections.sort(sorted);
        config.set("walls", sorted);
        try {
            config.save(breachWallFile);
        } catch (IOException e) {
            plugin.getLogger().warning("breach-walls.yml の保存に失敗しました: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreachInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null
                || (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }

        Block clicked = event.getClickedBlock();
        if (!breachWalls.contains(blockKey(clicked))) return;

        // 他機能（時間停止など）が既に操作を禁止している場合は破壊しない。
        if (event.isCancelled()) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();

        if (!isBreachHammer(item)) {
            player.sendMessage(color("&8[黒壁] &7強い衝撃を与えられる道具が必要そうだ。"));
            player.playSound(player.getLocation(), Sound.BLOCK_DEEPSLATE_HIT, 0.7f, 0.65f);
            return;
        }

        Set<Block> connected = collectConnectedRegisteredWall(clicked, 64);
        if (connected.isEmpty()) return;

        for (Block block : connected) {
            breachWalls.remove(blockKey(block));
            block.getWorld().playSound(block.getLocation(), Sound.BLOCK_DEEPSLATE_BREAK, 0.9f, 0.72f);
            block.setType(Material.AIR, false);
        }
        saveBreachWalls();

        player.sendMessage(color("&8[黒壁] &a大型ハンマーで脆い壁を破壊した。"));
        player.playSound(player.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 0.55f, 0.8f);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }

        Block door = canonicalIronDoor(event.getClickedBlock());
        if (door == null) return;

        String doorKey = key(door);
        LockState state = states.get(doorKey);
        if (state == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();

        if (state == LockState.UNLOCKED) {
            toggleDoor(door);
            player.getWorld().playSound(door.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 0.7f, 1.0f);
            return;
        }

        String requiredKey = requiredKeys.get(doorKey);
        if (requiredKey != null && hasMatchingKey(player, requiredKey)) {
            unlockWithKey(player, door, doorKey, requiredKey);
            return;
        }

        if (state == LockState.HARDLOCK) {
            forceClosed(door);
            if (requiredKey != null) {
                player.sendMessage(color("&8[扉] &c鍵がかかっている。対応する鍵が必要だ。"));
            } else {
                player.sendMessage(color("&8[扉] &cこの扉は通常の方法では開けられそうにない。"));
            }
            player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 0.65f, 0.72f);
            return;
        }

        // LOCK: 指定鍵を持っていない場合は従来どおり鍵開け技能判定。
        long now = System.currentTimeMillis();
        long retryUntil = failedAttempts
                .getOrDefault(player.getUniqueId(), Collections.emptyMap())
                .getOrDefault(doorKey, 0L);

        if (now < retryUntil) {
            long remainingSeconds = Math.max(1L, (retryUntil - now + 999L) / 1000L);
            player.sendMessage(color("&8[扉] &c一度鍵開けに失敗しているため、まだ再挑戦できません。"
                    + " &7(残り " + remainingSeconds + " 秒)"));
            return;
        }

        long until = rollCooldown.getOrDefault(player.getUniqueId(), 0L);
        if (now < until) return;
        rollCooldown.put(player.getUniqueId(), now + 1600L);

        int target = skillManager.getSkillValue(player, "locksmith");
        player.sendMessage(color(requiredKey == null
                ? "&8[扉] &e鍵がかかっている。鍵開けを試みます。"
                : "&8[扉] &e対応する鍵を持っていない。鍵開けを試みます。"));

        plugin.getDiceSoundManager().playRollSequence(player, () -> {
            int roll = ThreadLocalRandom.current().nextInt(1, 101);
            CheckResult result = CheckResult.evaluate(roll, target);

            plugin.getSkillGrowthManager().tryGrowth(player, "locksmith", "鍵開け", result);

            String message = color(
                    "&6[CoC判定] &f" + player.getName()
                            + " &7- &b鍵開け"
                            + " &7目標値:&e" + target
                            + " &7/ 1d100:&e" + roll
                            + " &7→ " + result.color() + result.label()
            );

            if (plugin.getConfig().getBoolean("broadcast-rolls", true)) {
                Bukkit.broadcastMessage(message);
            } else {
                player.sendMessage(message);
            }
            plugin.getDiceSoundManager().playResultSound(player, result);

            if (result.isSuccess()) {
                clearFailedAttempt(player.getUniqueId(), doorKey);
                clearFailedAttemptsForDoor(doorKey);
                states.put(doorKey, LockState.UNLOCKED);
                save();
                player.sendMessage(color("&8[扉] &a鍵が開いた。以後、この鉄の扉は普通のドアのように開閉できます。"));
                player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 0.9f, 1.2f);
                toggleDoor(door);
            } else {
                int retrySeconds = Math.max(0,
                        plugin.getConfig().getInt("door-lock.retry-after-failure-seconds", 300));

                if (retrySeconds > 0) {
                    failedAttempts.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                            .put(doorKey, System.currentTimeMillis() + retrySeconds * 1000L);
                    player.sendMessage(color("&8[扉] &c鍵を開けることができなかった。"
                            + " &7この扉への再挑戦は " + retrySeconds + " 秒後に可能です。"));
                } else {
                    player.sendMessage(color("&8[扉] &c鍵を開けることができなかった。"));
                }
                forceClosed(door);
            }
        });
    }

    private boolean hasMatchingKey(Player player, String requiredKey) {
        // 鍵を手に持って右クリックする方式。通常の名前変更だけでは偽造できない。
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        String itemKeyId = meta.getPersistentDataContainer().get(keyIdTag, PersistentDataType.STRING);
        return requiredKey.equals(normalizeKeyId(itemKeyId));
    }

    private void unlockWithKey(Player player, Block door, String doorKey, String requiredKey) {
        clearFailedAttempt(player.getUniqueId(), doorKey);
        clearFailedAttemptsForDoor(doorKey);
        states.put(doorKey, LockState.UNLOCKED);
        save();

        player.sendMessage(color("&8[扉] &a鍵を使って解錠した。 &7[鍵ID: " + requiredKey + "]"));
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 0.9f, 1.15f);
        toggleDoor(door);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPhysics(BlockPhysicsEvent event) {
        Block door = canonicalIronDoor(event.getBlock());
        if (door == null) return;

        LockState state = states.get(key(door));
        if (state == LockState.LOCKED || state == LockState.HARDLOCK) {
            forceClosed(door);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) {
        if (breachWalls.contains(blockKey(event.getBlock()))) {
            if (!canManage(event.getPlayer())) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(color("&8[黒壁] &c普通には壊せない。専用の道具が必要だ。"));
                return;
            }
            breachWalls.remove(blockKey(event.getBlock()));
            saveBreachWalls();
        }

        Block door = canonicalIronDoor(event.getBlock());
        if (door == null) return;
        String doorKey = key(door);
        if (states.remove(doorKey) != null) {
            requiredKeys.remove(doorKey);
            save();
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("このコマンドはゲーム内から使用してください。");
            return true;
        }

        if (!canManage(player)) {
            player.sendMessage(color("&cこのコマンドを使用する権限がありません。"));
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        String action = args[0].toLowerCase(Locale.ROOT);

        if (action.equals("keygive")) {
            return handleKeyGive(player, args);
        }
        if (action.equals("hammergive")) {
            return handleHammerGive(player, args);
        }
        if (action.equals("wallmark") || action.equals("wallclear") || action.equals("wallinfo")) {
            return handleBreachWallCommand(player, action, args);
        }

        if (!(action.equals("lock") || action.equals("hardlock")
                || action.equals("unlock") || action.equals("clear") || action.equals("keyinfo"))) {
            sendUsage(player);
            return true;
        }

        if ((action.equals("lock") || action.equals("hardlock")) && args.length > 2) {
            sendUsage(player);
            return true;
        }
        if (!(action.equals("lock") || action.equals("hardlock")) && args.length != 1) {
            sendUsage(player);
            return true;
        }

        Block door = getLookedAtIronDoor(player);
        if (door == null) {
            player.sendMessage(color("&c5ブロック以内の鉄のドアを見ながら実行してください。"));
            return true;
        }

        String doorKey = key(door);
        String suppliedKeyId = args.length >= 2 ? normalizeKeyId(args[1]) : null;
        if (args.length >= 2 && suppliedKeyId == null) {
            player.sendMessage(color("&c鍵IDには英数字、_、- のみ使用できます。"));
            return true;
        }

        switch (action) {
            case "lock" -> {
                clearFailedAttemptsForDoor(doorKey);
                states.put(doorKey, LockState.LOCKED);
                setRequiredKey(doorKey, suppliedKeyId);
                forceClosed(door);
                save();
                if (suppliedKeyId == null) {
                    player.sendMessage(color("&aこの鉄の扉を &eLOCK &aに設定しました。鍵開け成功で解錠できます。"));
                } else {
                    player.sendMessage(color("&aこの鉄の扉を &eLOCK &aに設定しました。 &f鍵ID: &b" + suppliedKeyId
                            + " &7(正しい鍵または鍵開け技能で解錠)"));
                }
            }
            case "hardlock" -> {
                clearFailedAttemptsForDoor(doorKey);
                states.put(doorKey, LockState.HARDLOCK);
                setRequiredKey(doorKey, suppliedKeyId);
                forceClosed(door);
                save();
                if (suppliedKeyId == null) {
                    player.sendMessage(color("&aこの鉄の扉を &cHARDLOCK &aに設定しました。鍵開けでは開きません。"));
                } else {
                    player.sendMessage(color("&aこの鉄の扉を &cHARDLOCK &aに設定しました。 &f鍵ID: &b" + suppliedKeyId
                            + " &7(この鍵でのみ解錠)"));
                }
            }
            case "unlock" -> {
                states.put(doorKey, LockState.UNLOCKED);
                save();
                player.sendMessage(color("&aこの鉄の扉を解錠しました。木のドアのように右クリックで開閉できます。"));
            }
            case "clear" -> {
                clearFailedAttemptsForDoor(doorKey);
                states.remove(doorKey);
                requiredKeys.remove(doorKey);
                save();
                player.sendMessage(color("&aこの鉄の扉のTRPGロック設定を解除しました。Minecraft本来の鉄扉に戻ります。"));
            }
            case "keyinfo" -> {
                LockState state = states.get(doorKey);
                String required = requiredKeys.get(doorKey);
                player.sendMessage(color("&8[扉情報] &7状態: &f" + (state == null ? "未登録" : state.name())));
                player.sendMessage(color("&8[扉情報] &7鍵ID: &f" + (required == null ? "なし" : required)));
            }
            default -> sendUsage(player);
        }
        return true;
    }

    private boolean handleKeyGive(Player sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(color("&e/lock keygive <player> <key-id> [表示名]"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(color("&c指定したプレイヤーはオンラインではありません。"));
            return true;
        }

        String keyId = normalizeKeyId(args[2]);
        if (keyId == null) {
            sender.sendMessage(color("&c鍵IDには英数字、_、- のみ使用できます。"));
            return true;
        }

        String displayName = DEFAULT_KEY_NAME;
        if (args.length >= 4) {
            displayName = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        }

        ItemStack keyItem = createKeyItem(keyId, displayName);
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(keyItem);
        for (ItemStack item : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
        }

        sender.sendMessage(color("&a" + target.getName() + " に鍵を渡しました。 &7[鍵ID: " + keyId + "]"));
        target.sendMessage(color("&6[鍵] &f" + displayName + " &aを受け取りました。"));
        target.playSound(target.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
        return true;
    }

    private boolean handleHammerGive(Player sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(color("&e/lock hammergive <player> [表示名]"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(color("&c指定したプレイヤーはオンラインではありません。"));
            return true;
        }

        String displayName = args.length >= 3
                ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : DEFAULT_HAMMER_NAME;

        ItemStack hammer = createBreachHammer(displayName);
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(hammer);
        for (ItemStack item : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
        }

        sender.sendMessage(color("&a" + target.getName() + " に &6" + displayName + " &aを渡しました。"));
        target.sendMessage(color("&6[道具] &f" + displayName + " &aを受け取りました。"));
        target.playSound(target.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.85f);
        return true;
    }


    public void giveKeyReward(Player target, String keyId, String displayName) {
        String normalized = normalizeKeyId(keyId);
        if (normalized == null) {
            plugin.getLogger().warning("手掛かり報酬の鍵IDが不正です: " + keyId);
            return;
        }
        String name = (displayName == null || displayName.isBlank()) ? DEFAULT_KEY_NAME : displayName;
        ItemStack keyItem = createKeyItem(normalized, name);
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(keyItem);
        for (ItemStack item : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
        }
        target.sendMessage(color("&6[手掛かり報酬] &f" + name + " &aを入手しました。"));
        target.playSound(target.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.2f);
    }

    public void giveHammerReward(Player target, String displayName) {
        String name = (displayName == null || displayName.isBlank()) ? DEFAULT_HAMMER_NAME : displayName;
        ItemStack hammer = createBreachHammer(name);
        Map<Integer, ItemStack> overflow = target.getInventory().addItem(hammer);
        for (ItemStack item : overflow.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
        }
        target.sendMessage(color("&6[手掛かり報酬] &f" + name + " &aを入手しました。"));
        target.playSound(target.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 0.85f);
    }

    private boolean handleBreachWallCommand(Player player, String action, String[] args) {
        if (args.length != 1) {
            sendUsage(player);
            return true;
        }

        RayTraceResult result = player.rayTraceBlocks(6.0);
        if (result == null || result.getHitBlock() == null) {
            player.sendMessage(color("&c6ブロック以内の黒色コンクリートパウダーを見ながら実行してください。"));
            return true;
        }

        Block target = result.getHitBlock();
        if (target.getType() != Material.BLACK_CONCRETE_POWDER) {
            player.sendMessage(color("&c対象は黒色コンクリートパウダーではありません。"));
            return true;
        }

        Set<Block> connected = collectConnectedBlackPowder(target, 64);
        if (action.equals("wallmark")) {
            for (Block block : connected) breachWalls.add(blockKey(block));
            saveBreachWalls();
            player.sendMessage(color("&a接続している黒壁を破壊可能壁として登録しました。 &7[" + connected.size() + "ブロック]"));
            return true;
        }

        if (action.equals("wallclear")) {
            int removed = 0;
            for (Block block : connected) {
                if (breachWalls.remove(blockKey(block))) removed++;
            }
            saveBreachWalls();
            player.sendMessage(color("&a破壊可能壁の登録を解除しました。 &7[" + removed + "ブロック]"));
            return true;
        }

        int registered = 0;
        for (Block block : connected) {
            if (breachWalls.contains(blockKey(block))) registered++;
        }
        player.sendMessage(color("&8[黒壁情報] &7接続ブロック: &f" + connected.size()
                + " &7/ 登録済み: &f" + registered));
        return true;
    }

    private ItemStack createBreachHammer(String displayName) {
        ItemStack item = new ItemStack(Material.IRON_PICKAXE, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&6" + displayName));
        meta.setLore(List.of(
                color("&7補修・解体用の重量工具。"),
                color("&7登録された脆い黒壁を破壊できる。"),
                color("&8シナリオ用アイテム / 消費されない")
        ));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(breachHammerTag, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isBreachHammer(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        Byte tag = item.getItemMeta().getPersistentDataContainer()
                .get(breachHammerTag, PersistentDataType.BYTE);
        return tag != null && tag == (byte) 1;
    }

    private Set<Block> collectConnectedBlackPowder(Block start, int maxBlocks) {
        Set<Block> result = new LinkedHashSet<>();
        if (start == null || start.getType() != Material.BLACK_CONCRETE_POWDER) return result;
        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);

        while (!queue.isEmpty() && result.size() < maxBlocks) {
            Block current = queue.removeFirst();
            String currentKey = blockKey(current);
            if (!visited.add(currentKey)) continue;
            if (current.getType() != Material.BLACK_CONCRETE_POWDER) continue;
            result.add(current);

            for (BlockFace face : List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                Block next = current.getRelative(face);
                if (!visited.contains(blockKey(next))) queue.addLast(next);
            }
        }
        return result;
    }

    private Set<Block> collectConnectedRegisteredWall(Block start, int maxBlocks) {
        Set<Block> result = new LinkedHashSet<>();
        if (start == null || !breachWalls.contains(blockKey(start))) return result;
        ArrayDeque<Block> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);

        while (!queue.isEmpty() && result.size() < maxBlocks) {
            Block current = queue.removeFirst();
            String currentKey = blockKey(current);
            if (!visited.add(currentKey)) continue;
            if (!breachWalls.contains(currentKey)) continue;
            result.add(current);

            for (BlockFace face : List.of(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)) {
                Block next = current.getRelative(face);
                if (!visited.contains(blockKey(next))) queue.addLast(next);
            }
        }
        return result;
    }

    private String blockKey(Block block) {
        Location l = block.getLocation();
        return block.getWorld().getUID() + "_" + l.getBlockX() + "_" + l.getBlockY() + "_" + l.getBlockZ();
    }

    private ItemStack createKeyItem(String keyId, String displayName) {
        ItemStack item = new ItemStack(Material.TRIPWIRE_HOOK, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color("&6" + displayName));
        meta.setLore(List.of(
                color("&7扉を開けるための鍵。"),
                color("&8鍵ID: " + keyId)
        ));
        meta.getPersistentDataContainer().set(keyIdTag, PersistentDataType.STRING, keyId);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * シナリオイベントから座標指定で鉄扉をロックする。
     * @return 後で元へ戻すためのスナップショット。対象が鉄扉でなければ null。
     */
    public DoorLockSnapshot setScenarioLock(World world, int x, int y, int z, boolean hardlock, String keyId) {
        if (world == null) return null;
        Block door = canonicalIronDoor(world.getBlockAt(x, y, z));
        if (door == null) return null;
        String doorKey = key(door);
        DoorLockSnapshot snapshot = new DoorLockSnapshot(
                door.getX(), door.getY(), door.getZ(),
                states.containsKey(doorKey),
                states.get(doorKey) == null ? null : states.get(doorKey).name(),
                requiredKeys.get(doorKey)
        );
        clearFailedAttemptsForDoor(doorKey);
        states.put(doorKey, hardlock ? LockState.HARDLOCK : LockState.LOCKED);
        setRequiredKey(doorKey, normalizeKeyId(keyId));
        forceClosed(door);
        save();
        return snapshot;
    }

    /** 警報前の扉状態へ戻す。 */
    public boolean restoreScenarioLock(World world, DoorLockSnapshot snapshot) {
        if (world == null || snapshot == null) return false;
        Block door = canonicalIronDoor(world.getBlockAt(snapshot.x(), snapshot.y(), snapshot.z()));
        if (door == null) return false;
        String doorKey = key(door);
        clearFailedAttemptsForDoor(doorKey);
        if (!snapshot.registered()) {
            states.remove(doorKey);
            requiredKeys.remove(doorKey);
        } else {
            try {
                states.put(doorKey, LockState.valueOf(snapshot.state()));
            } catch (Exception e) {
                states.remove(doorKey);
            }
            setRequiredKey(doorKey, normalizeKeyId(snapshot.keyId()));
        }
        if (states.get(doorKey) == LockState.LOCKED || states.get(doorKey) == LockState.HARDLOCK) {
            forceClosed(door);
        }
        save();
        return true;
    }

    public record DoorLockSnapshot(int x, int y, int z, boolean registered, String state, String keyId) {}

    private void setRequiredKey(String doorKey, String keyId) {
        if (keyId == null) requiredKeys.remove(doorKey);
        else requiredKeys.put(doorKey, keyId);
    }

    private String normalizeKeyId(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty() || !id.matches("[a-z0-9_-]+")) return null;
        return id;
    }

    private boolean canManage(Player player) {
        return player.isOp()
                || player.hasPermission("trpg.admin")
                || keeperManager.isKeeper(player);
    }

    private Block getLookedAtIronDoor(Player player) {
        RayTraceResult result = player.rayTraceBlocks(5.0);
        if (result == null || result.getHitBlock() == null) return null;
        return canonicalIronDoor(result.getHitBlock());
    }

    private Block canonicalIronDoor(Block block) {
        if (block == null || block.getType() != Material.IRON_DOOR) return null;
        BlockData data = block.getBlockData();
        if (!(data instanceof Door door)) return null;
        if (door.getHalf() == Bisected.Half.TOP) {
            Block bottom = block.getRelative(BlockFace.DOWN);
            return bottom.getType() == Material.IRON_DOOR ? bottom : null;
        }
        return block;
    }

    private String key(Block bottom) {
        Location l = bottom.getLocation();
        return bottom.getWorld().getUID() + "_" + l.getBlockX() + "_" + l.getBlockY() + "_" + l.getBlockZ();
    }

    private void toggleDoor(Block bottom) {
        BlockData data = bottom.getBlockData();
        if (!(data instanceof Openable openable)) return;
        openable.setOpen(!openable.isOpen());
        bottom.setBlockData(data, true);

        Block top = bottom.getRelative(BlockFace.UP);
        if (top.getType() == Material.IRON_DOOR) {
            BlockData topData = top.getBlockData();
            if (topData instanceof Openable topOpenable) {
                topOpenable.setOpen(openable.isOpen());
                top.setBlockData(topData, true);
            }
        }
    }

    private void forceClosed(Block bottom) {
        if (bottom == null || bottom.getType() != Material.IRON_DOOR) return;
        BlockData data = bottom.getBlockData();
        if (data instanceof Openable openable && openable.isOpen()) {
            openable.setOpen(false);
            bottom.setBlockData(data, true);
        }

        Block top = bottom.getRelative(BlockFace.UP);
        if (top.getType() == Material.IRON_DOOR) {
            BlockData topData = top.getBlockData();
            if (topData instanceof Openable topOpenable && topOpenable.isOpen()) {
                topOpenable.setOpen(false);
                top.setBlockData(topData, true);
            }
        }
    }

    private void clearFailedAttempt(UUID playerId, String doorKey) {
        Map<String, Long> map = failedAttempts.get(playerId);
        if (map == null) return;
        map.remove(doorKey);
        if (map.isEmpty()) failedAttempts.remove(playerId);
    }

    private void clearFailedAttemptsForDoor(String doorKey) {
        Iterator<Map.Entry<UUID, Map<String, Long>>> iterator = failedAttempts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Map<String, Long>> entry = iterator.next();
            entry.getValue().remove(doorKey);
            if (entry.getValue().isEmpty()) iterator.remove();
        }
    }

    private void sendUsage(Player player) {
        player.sendMessage(color("&e/lock lock [key-id] &7- 鍵開け可能。鍵ID指定時は鍵でも解錠可能"));
        player.sendMessage(color("&e/lock hardlock [key-id] &7- 鍵開け不可。鍵ID指定時はその鍵だけで解錠"));
        player.sendMessage(color("&e/lock keygive <player> <key-id> [表示名] &7- 専用鍵を渡す（使用しても消費しない）"));
        player.sendMessage(color("&e/lock hammergive <player> [表示名] &7- 黒壁破壊用の大型ハンマーを渡す"));
        player.sendMessage(color("&e/lock wallmark &7- 見ている黒色コンクリートパウダーと接続部分を破壊可能壁に登録"));
        player.sendMessage(color("&e/lock wallclear &7- 接続している黒壁の登録を解除"));
        player.sendMessage(color("&e/lock wallinfo &7- 接続している黒壁の登録状態を確認"));
        player.sendMessage(color("&e/lock keyinfo &7- 見ている扉の状態と鍵IDを確認"));
        player.sendMessage(color("&e/lock unlock &7- 木のドアのように開閉可能"));
        player.sendMessage(color("&e/lock clear &7- TRPGロック設定を削除"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filterPrefix(args[0], List.of("lock", "hardlock", "keygive", "hammergive", "wallmark", "wallclear", "wallinfo", "keyinfo", "unlock", "clear"));
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("keygive") || args[0].equalsIgnoreCase("hammergive"))) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) names.add(player.getName());
            return filterPrefix(args[1], names);
        }
        return Collections.emptyList();
    }

    private List<String> filterPrefix(String rawPrefix, Collection<String> values) {
        String prefix = rawPrefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(prefix)) result.add(value);
        }
        return result;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
}
