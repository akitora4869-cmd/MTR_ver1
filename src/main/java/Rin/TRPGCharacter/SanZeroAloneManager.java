package Rin.TRPGCharacter;

import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * SANが0以下の探索者が仲間から孤立した際の恐怖演出と死亡処理。
 * 仲間が近づく、SANが回復する、死亡/スペクテイターになるとカウントを解除する。
 */
public class SanZeroAloneManager {

    private final Plugin plugin;
    private final CharacterManager characterManager;
    private final SessionManager sessionManager;
    private final KeeperManager keeperManager;

    private final Map<UUID, State> states = new HashMap<>();
    private BukkitTask task;

    public SanZeroAloneManager(Plugin plugin,
                               CharacterManager characterManager,
                               SessionManager sessionManager,
                               KeeperManager keeperManager) {
        this.plugin = plugin;
        this.characterManager = characterManager;
        this.sessionManager = sessionManager;
        this.keeperManager = keeperManager;
    }

    public void start() {
        shutdown();
        if (!plugin.getConfig().getBoolean("san-zero-alone.enabled", true)) {
            return;
        }

        long interval = Math.max(1L,
                plugin.getConfig().getLong("san-zero-alone.check-interval-ticks", 10L));
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        states.clear();
    }

    public void reload() {
        start();
    }

    private void tick() {
        if (plugin.getTimeStopManager() != null && plugin.getTimeStopManager().isStopped()) {
            return;
        }

        long interval = Math.max(1L,
                plugin.getConfig().getLong("san-zero-alone.check-interval-ticks", 10L));
        int deathDelayTicks = Math.max(20,
                plugin.getConfig().getInt("san-zero-alone.death-delay-seconds", 15) * 20);
        int screamIntervalTicks = Math.max(20,
                plugin.getConfig().getInt("san-zero-alone.scream-interval-seconds", 3) * 20);

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (!shouldTrack(player) || hasNearbyAlly(player)) {
                states.remove(player.getUniqueId());
                continue;
            }

            State state = states.computeIfAbsent(player.getUniqueId(), ignored -> new State());
            state.elapsedTicks += (int) interval;
            state.sinceLastScreamTicks += (int) interval;

            if (!state.warned) {
                state.warned = true;
                state.sinceLastScreamTicks = screamIntervalTicks;
                player.sendMessage("§4[SAN 0] §c周囲に仲間の気配がありません……。");
                player.sendTitle("§4正気が崩れていく", "§7仲間を探してください", 10, 50, 10);
            }

            if (state.sinceLastScreamTicks >= screamIntervalTicks) {
                state.sinceLastScreamTicks = 0;
                playScream(player);
            }

            if (state.elapsedTicks >= deathDelayTicks) {
                states.remove(player.getUniqueId());
                kill(player);
            }
        }

        Iterator<Map.Entry<UUID, State>> iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next().getKey();
            Player player = plugin.getServer().getPlayer(uuid);
            if (player == null || !player.isOnline()) {
                iterator.remove();
            }
        }
    }

    private boolean shouldTrack(Player player) {
        if (!player.isOnline()
                || player.isDead()
                || player.getGameMode() == GameMode.SPECTATOR
                || player.getGameMode() == GameMode.CREATIVE) {
            return false;
        }
        if (keeperManager.isKeeper(player) || player.isOp() || player.hasPermission("trpg.admin")) {
            return false;
        }
        return characterManager.hasConfiguredStats(player)
                && !characterManager.isDeadCharacter(player)
                && characterManager.getCurrentSan(player) <= 0;
    }

    private boolean hasNearbyAlly(Player player) {
        double range = Math.max(1.0,
                plugin.getConfig().getDouble("san-zero-alone.ally-range", 12.0));
        double rangeSquared = range * range;

        boolean sessionParticipant = sessionManager.isActive() && sessionManager.isParticipant(player);

        for (Player other : player.getWorld().getPlayers()) {
            if (other.equals(player)
                    || !other.isOnline()
                    || other.isDead()
                    || other.getGameMode() == GameMode.SPECTATOR
                    || other.getGameMode() == GameMode.CREATIVE) {
                continue;
            }
            if (keeperManager.isKeeper(other) || other.isOp() || other.hasPermission("trpg.admin")) {
                continue;
            }
            if (!characterManager.hasConfiguredStats(other) || characterManager.isDeadCharacter(other)) {
                continue;
            }
            // セッション参加中の探索者なら、同じセッションの探索者だけを「仲間」とする。
            if (sessionParticipant && !sessionManager.isParticipant(other)) {
                continue;
            }
            if (player.getLocation().distanceSquared(other.getLocation()) <= rangeSquared) {
                return true;
            }
        }
        return false;
    }

    private void playScream(Player player) {
        String soundName = plugin.getConfig().getString(
                "san-zero-alone.scream-sound", "ENTITY_GHAST_SCREAM");
        float volume = (float) plugin.getConfig().getDouble(
                "san-zero-alone.scream-volume", 0.8);
        float pitch = (float) plugin.getConfig().getDouble(
                "san-zero-alone.scream-pitch", 0.55);

        Sound sound;
        try {
            sound = Sound.valueOf(soundName == null
                    ? "ENTITY_GHAST_SCREAM"
                    : soundName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            sound = Sound.ENTITY_GHAST_SCREAM;
        }

        // 本人だけに聞こえる恐怖演出。周囲のプレイヤーには鳴らさない。
        player.playSound(player.getLocation(), sound, SoundCategory.AMBIENT, volume, pitch);
    }

    private void kill(Player player) {
        if (!shouldTrack(player) || hasNearbyAlly(player)) {
            return;
        }

        player.sendTitle("§4正気の限界", "§c孤独の中で意識が途切れた", 5, 50, 15);
        player.sendMessage("§4[SAN 0] §c孤独に耐えきれず死亡しました。");
        player.playSound(player.getLocation(), Sound.ENTITY_WITHER_DEATH,
                SoundCategory.AMBIENT, 0.65f, 0.7f);

        // Bukkitの通常死亡処理を通すことで、探索者シート保持・スペクテイター化など
        // 既存DeathManagerの処理をそのまま利用する。
        if (player.getHealth() > 0.0) {
            player.setHealth(0.0);
        }
    }

    private static final class State {
        private int elapsedTicks;
        private int sinceLastScreamTicks;
        private boolean warned;
    }
}
