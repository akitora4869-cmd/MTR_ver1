package Rin.TRPGCharacter;

import org.bukkit.Color;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * TRPG HPに実ダメージが入った時だけ再生する被弾演出。
 * 0ダメージでは何もしない。
 */
public class DamageFeedbackManager {

    private final Plugin plugin;

    public DamageFeedbackManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void play(Player player, int actualDamage) {
        if (player == null || actualDamage <= 0) {
            return;
        }

        if (!plugin.getConfig().getBoolean("damage-feedback.enabled", true)) {
            return;
        }

        float volume;
        float pitch;
        int particles;

        if (actualDamage >= 6) {
            volume = 1.10f;
            pitch = 0.72f;
            particles = 18;
        } else if (actualDamage >= 3) {
            volume = 0.82f;
            pitch = 0.88f;
            particles = 12;
        } else {
            volume = 0.58f;
            pitch = 1.02f;
            particles = 7;
        }

        player.playSound(
                player.getLocation(),
                Sound.ENTITY_PLAYER_HURT,
                volume,
                pitch
        );

        if (actualDamage >= 6) {
            player.playSound(
                    player.getLocation(),
                    Sound.ENTITY_GENERIC_EXPLODE,
                    0.28f,
                    1.35f
            );
        }

        Particle.DustOptions red =
                new Particle.DustOptions(Color.fromRGB(190, 20, 20), 1.15f);

        player.getWorld().spawnParticle(
                Particle.REDSTONE,
                player.getLocation().clone().add(0.0, 1.0, 0.0),
                particles,
                0.38, 0.55, 0.38,
                0.0,
                red
        );
    }
}
