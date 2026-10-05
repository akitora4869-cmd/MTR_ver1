package Rin.TRPGCharacter;

import org.bukkit.ChatColor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** PC/NPC/敵で共有する技能クールダウン。デフォルトは全技能共通10秒。 */
public class SkillCooldownManager {
    private final Plugin plugin;
    private final Map<UUID, Long> nextUse = new HashMap<>();

    public SkillCooldownManager(Plugin plugin) { this.plugin = plugin; }

    public long cooldownMillis() {
        return Math.max(0L, plugin.getConfig().getLong("skill-system.global-cooldown-ms", 10000L));
    }

    public boolean tryUse(Entity actor) {
        if (actor == null) return true;
        long now = System.currentTimeMillis();
        long ready = nextUse.getOrDefault(actor.getUniqueId(), 0L);
        if (now < ready) {
            if (actor instanceof Player p) {
                double sec = Math.ceil((ready - now) / 100.0) / 10.0;
                p.sendActionBar(net.kyori.adventure.text.Component.text(
                        "技能クールタイム: " + String.format(java.util.Locale.ROOT, "%.1f", sec) + "秒",
                        net.kyori.adventure.text.format.NamedTextColor.RED));
            }
            return false;
        }
        nextUse.put(actor.getUniqueId(), now + cooldownMillis());
        return true;
    }

    public boolean isReady(Entity actor) {
        return actor == null || System.currentTimeMillis() >= nextUse.getOrDefault(actor.getUniqueId(), 0L);
    }

    public long remainingMillis(Entity actor) {
        if (actor == null) return 0L;
        return Math.max(0L, nextUse.getOrDefault(actor.getUniqueId(), 0L) - System.currentTimeMillis());
    }

    public void reset(Entity actor) { if (actor != null) nextUse.remove(actor.getUniqueId()); }
    public void clear() { nextUse.clear(); }
}
