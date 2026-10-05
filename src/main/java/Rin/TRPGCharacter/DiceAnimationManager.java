package Rin.TRPGCharacter;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** リソースパック不要のダイス演出。SECRETは本人だけに見え、技能名は常に「？？？」。 */
public class DiceAnimationManager {
    private final Plugin plugin;
    public DiceAnimationManager(Plugin plugin) { this.plugin = plugin; }

    public void play(Player roller, RollVisibility visibility, Runnable finish) {
        if (visibility == RollVisibility.HIDDEN) {
            plugin.getServer().getScheduler().runTaskLater(plugin, finish, 20L);
            return;
        }
        boolean secret = visibility == RollVisibility.SECRET;
        Component head = secret
                ? Component.text("🔒 SECRET ROLL 〈？？？〉", NamedTextColor.DARK_PURPLE)
                : Component.text("DICE ROLL", NamedTextColor.GOLD);
        sendAction(roller, visibility, head);
        tick(roller, visibility, 0);
        plugin.getServer().getScheduler().runTaskLater(plugin, finish, 20L);
    }

    private void tick(Player roller, RollVisibility visibility, int step) {
        if (step >= 5 || !roller.isOnline()) return;
        Location base = roller.getEyeLocation().clone().add(roller.getEyeLocation().getDirection().normalize().multiply(1.35));
        double angle = step * 1.7;
        Location p = base.clone().add(Math.cos(angle) * .28, -0.15 + Math.sin(angle * 1.3) * .16, Math.sin(angle) * .28);
        spawn(roller, visibility, p);
        sound(roller, visibility, step);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> tick(roller, visibility, step + 1), 4L);
    }

    private void spawn(Player roller, RollVisibility visibility, Location loc) {
        if (visibility == RollVisibility.SECRET) {
            roller.spawnParticle(Particle.END_ROD, loc, 5, .08, .08, .08, .01);
            roller.spawnParticle(Particle.CRIT, loc, 3, .06, .06, .06, .01);
        } else {
            roller.getWorld().spawnParticle(Particle.END_ROD, loc, 5, .08, .08, .08, .01);
            roller.getWorld().spawnParticle(Particle.CRIT, loc, 3, .06, .06, .06, .01);
        }
    }

    private void sound(Player roller, RollVisibility visibility, int step) {
        float pitch = .78f + step * .09f;
        if (visibility == RollVisibility.SECRET) {
            roller.playSound(roller.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, .55f, pitch);
        } else {
            double r2 = 12 * 12;
            for (Player p : roller.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(roller.getLocation()) <= r2)
                    p.playSound(roller.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, .55f, pitch);
            }
        }
    }

    private void sendAction(Player roller, RollVisibility visibility, Component component) {
        if (visibility == RollVisibility.SECRET) roller.sendActionBar(component);
        else for (Player p : roller.getWorld().getPlayers()) if (p.getLocation().distanceSquared(roller.getLocation()) <= 144) p.sendActionBar(component);
    }
}
