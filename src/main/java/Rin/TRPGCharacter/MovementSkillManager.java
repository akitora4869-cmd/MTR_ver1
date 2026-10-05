package Rin.TRPGCharacter;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Paper/Bukkit の PlayerToggleFlightEvent を利用した二段ジャンプと、
 * 壁に接触中の登攀（蜘蛛の壁登り風）を担当する。
 */
public class MovementSkillManager implements Listener {
    private final Plugin plugin;
    private final CharacterManager characterManager;
    private final SkillManager skillManager;
    private final Random random = new Random();
    private final Map<UUID, Boolean> grantedFlight = new HashMap<>();
    private final Map<UUID, Long> doubleJumpCooldown = new HashMap<>();
    private final Map<UUID, Long> climbCooldown = new HashMap<>();
    private final Map<UUID, Long> climbActiveUntil = new HashMap<>();
    private BukkitTask task;

    public MovementSkillManager(Plugin plugin, CharacterManager characterManager, SkillManager skillManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.skillManager = skillManager;
    }

    public void start() {
        shutdown();
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            revokeFlight(player);
        }
        grantedFlight.clear();
        climbActiveUntil.clear();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!canUse(player)) {
                revokeFlight(player);
                climbActiveUntil.remove(player.getUniqueId());
                continue;
            }

            // Survival/Adventure でのみ一時的に allowFlight を与え、
            // 空中の2回目スペースを PlayerToggleFlightEvent として取得する。
            if (plugin.getConfig().getBoolean("double-jump.enabled", true)) {
                if (player.isOnGround()) {
                    grantFlight(player);
                } else if (grantedFlight.getOrDefault(player.getUniqueId(), false)) {
                    player.setAllowFlight(true);
                }
            } else {
                revokeFlight(player);
            }

            if (plugin.getConfig().getBoolean("climb.enabled", true)) {
                handleClimbMotion(player, now);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!grantedFlight.getOrDefault(player.getUniqueId(), false)) return;
        if (!canUse(player) || !plugin.getConfig().getBoolean("double-jump.enabled", true)) {
            revokeFlight(player);
            return;
        }

        // 本当の飛行は開始させない。イベントを二段ジャンプ入力として消費する。
        event.setCancelled(true);
        player.setFlying(false);
        revokeFlight(player);

        long now = System.currentTimeMillis();
        if (now < doubleJumpCooldown.getOrDefault(player.getUniqueId(), 0L)) return;
        if (player.isOnGround()) return;

        attemptDoubleJump(player);
    }

    private void attemptDoubleJump(Player player) {
        String skillId = plugin.getConfig().getString("double-jump.skill", "jump");
        SkillDefinition def = skillManager.getSkill(skillId);
        String name = def != null ? def.getName() : "跳躍";
        int value = skillManager.getSkillValue(player, skillId);

        plugin.getDiceSoundManager().playRollSequence(player, () -> {
            if (!canUse(player) || player.isOnGround()) return;
            int roll = random.nextInt(100) + 1;
            CheckResult result = CheckResult.evaluate(roll, value);
            plugin.getSkillGrowthManager().tryGrowth(player, skillId, name, result);
            plugin.getDiceSoundManager().playResultSound(player, result);
            player.sendMessage(color("&6[二段ジャンプ] &e" + name + "判定 &7/ 技能値 &b" + value
                    + " &7/ 1d100:&e" + roll + " &7→ " + result.color() + result.label()));

            doubleJumpCooldown.put(player.getUniqueId(), System.currentTimeMillis()
                    + Math.max(0L, plugin.getConfig().getLong("double-jump.cooldown-ms", 700L)));

            if (!result.isSuccess()) {
                if (result == CheckResult.FUMBLE) {
                    Vector v = player.getVelocity();
                    v.setY(Math.min(v.getY(), -Math.max(0.0,
                            plugin.getConfig().getDouble("double-jump.fumble-downward-velocity", 0.25))));
                    player.setVelocity(v);
                }
                return;
            }

            double mult = result == CheckResult.CRITICAL
                    ? plugin.getConfig().getDouble("double-jump.critical-multiplier", 1.25)
                    : result == CheckResult.SPECIAL
                    ? plugin.getConfig().getDouble("double-jump.special-multiplier", 1.12) : 1.0;
            double vertical = Math.max(0.1, plugin.getConfig().getDouble("double-jump.vertical-velocity", 0.68));
            double forward = Math.max(0.0, plugin.getConfig().getDouble("double-jump.forward-velocity", 0.28));
            Vector launch = player.getLocation().getDirection().setY(0).normalize().multiply(forward * mult);
            launch.setY(vertical * mult);
            player.setVelocity(launch);
            player.setFallDistance(0.0f);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!canUse(player) || !plugin.getConfig().getBoolean("climb.enabled", true)) return;
        if (player.isOnGround() || player.isInWater() || wallNearby(player) == null) return;

        // 壁に向かって移動しようとしている時だけ登攀判定を開始する。
        Vector delta = event.getTo() == null ? new Vector() : event.getTo().toVector().subtract(event.getFrom().toVector());
        Vector wall = wallNearby(player);
        if (wall == null || delta.lengthSquared() < 0.0001) return;
        if (delta.clone().setY(0).dot(wall) <= 0.0) return;

        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (now < climbActiveUntil.getOrDefault(id, 0L) || now < climbCooldown.getOrDefault(id, 0L)) return;
        attemptClimb(player);
    }

    private void attemptClimb(Player player) {
        String skillId = plugin.getConfig().getString("climb.skill", "climb");
        SkillDefinition def = skillManager.getSkill(skillId);
        String name = def != null ? def.getName() : "登攀";
        int value = skillManager.getSkillValue(player, skillId);

        climbCooldown.put(player.getUniqueId(), System.currentTimeMillis()
                + Math.max(500L, plugin.getConfig().getLong("climb.retry-cooldown-ms", 2500L)));

        plugin.getDiceSoundManager().playRollSequence(player, () -> {
            if (!canUse(player) || wallNearby(player) == null) return;
            int roll = random.nextInt(100) + 1;
            CheckResult result = CheckResult.evaluate(roll, value);
            plugin.getSkillGrowthManager().tryGrowth(player, skillId, name, result);
            plugin.getDiceSoundManager().playResultSound(player, result);
            player.sendMessage(color("&6[登攀] &e" + name + "判定 &7/ 技能値 &b" + value
                    + " &7/ 1d100:&e" + roll + " &7→ " + result.color() + result.label()));

            if (!result.isSuccess()) return;
            long duration = Math.max(500L, plugin.getConfig().getLong("climb.success-duration-ms", 5000L));
            if (result == CheckResult.SPECIAL) duration = Math.round(duration * 1.25);
            if (result == CheckResult.CRITICAL) duration = Math.round(duration * 1.5);
            climbActiveUntil.put(player.getUniqueId(), System.currentTimeMillis() + duration);
        });
    }

    private void handleClimbMotion(Player player, long now) {
        UUID id = player.getUniqueId();
        Long until = climbActiveUntil.get(id);
        if (until == null) return;
        if (now >= until || player.isOnGround() || player.isInWater()) {
            climbActiveUntil.remove(id);
            return;
        }
        Vector wall = wallNearby(player);
        if (wall == null) {
            climbActiveUntil.remove(id);
            return;
        }

        // 蜘蛛のように壁面へ軽く吸着させながら上昇する。
        double speed = Math.max(0.05, plugin.getConfig().getDouble("climb.vertical-velocity", 0.20));
        double stick = Math.max(0.0, plugin.getConfig().getDouble("climb.wall-stick-velocity", 0.08));
        Vector v = player.getVelocity();
        v.setY(speed);
        v.add(wall.clone().normalize().multiply(stick));
        player.setVelocity(v);
        player.setFallDistance(0.0f);
    }

    // 戻り値は「プレイヤーから壁へ向かう方向」。
    private Vector wallNearby(Player player) {
        double d = Math.max(0.25, plugin.getConfig().getDouble("climb.wall-check-distance", 0.55));
        Vector[] dirs = {new Vector(1,0,0), new Vector(-1,0,0), new Vector(0,0,1), new Vector(0,0,-1)};
        for (Vector dir : dirs) {
            Vector q = player.getLocation().toVector().add(dir.clone().multiply(d));
            Block feet = player.getWorld().getBlockAt(q.getBlockX(), player.getLocation().getBlockY(), q.getBlockZ());
            Block head = feet.getRelative(0, 1, 0);
            if (isClimbableWall(feet) || isClimbableWall(head)) return dir.clone();
        }
        return null;
    }

    private boolean isClimbableWall(Block block) {
        Material type = block.getType();
        return type.isSolid() && type != Material.BARRIER && type != Material.BEDROCK;
    }

    private void grantFlight(Player player) {
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!player.getAllowFlight()) {
            player.setAllowFlight(true);
            grantedFlight.put(player.getUniqueId(), true);
        }
    }

    private void revokeFlight(Player player) {
        UUID id = player.getUniqueId();
        if (grantedFlight.remove(id) != null
                && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { revokeFlight(event.getPlayer()); }
    @EventHandler public void onGameMode(PlayerGameModeChangeEvent event) { revokeFlight(event.getPlayer()); }

    private boolean canUse(Player player) {
        return player.isOnline()
                && player.getGameMode() != GameMode.SPECTATOR
                && player.getGameMode() != GameMode.CREATIVE
                && !player.isInsideVehicle()
                && characterManager.hasConfiguredStats(player)
                && !characterManager.isDeadCharacter(player)
                && (plugin.getTimeStopManager() == null || plugin.getTimeStopManager().canAct(player));
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
}
