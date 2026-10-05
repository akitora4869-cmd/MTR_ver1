package Rin.TRPGCharacter;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ドリームランドの常夜と、リソースパック用の巨大な異界月を管理する。
 *
 * PAPER + CustomModelData(93001) を巨大なItemDisplayとして表示する。
 * リソースパック側の青紫月テクスチャを使うことで、TextDisplay版より
 * 自然な円盤・クレーター表現を実現する。
 */
public class DreamlandsMoonManager {

    public static final int MOON_CUSTOM_MODEL_DATA = 93001;

    private final Plugin plugin;
    private final DreamlandsManager dreamlandsManager;
    private final Map<UUID, ItemDisplay> moons = new HashMap<>();
    private BukkitTask task;

    public DreamlandsMoonManager(Plugin plugin, DreamlandsManager dreamlandsManager) {
        this.plugin = plugin;
        this.dreamlandsManager = dreamlandsManager;
    }

    public void start() {
        shutdown();

        long interval = Math.max(
                5L,
                plugin.getConfig().getLong("dreamlands.moon.update-interval-ticks", 10L)
        );

        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tick,
                1L,
                interval
        );
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (ItemDisplay display : moons.values()) {
            removeDisplay(display);
        }
        moons.clear();
    }

    public boolean isEnabled() {
        return plugin.getConfig().getBoolean("dreamlands.moon.enabled", true);
    }

    private void tick() {
        World dream = dreamlandsManager.getDreamlandsWorld();
        if (dream == null) {
            clearAll();
            return;
        }

        if (plugin.getConfig().getBoolean("dreamlands.always-night", true)) {
            long fixedTime = plugin.getConfig().getLong("dreamlands.night-time", 18000L);
            dream.setGameRule(org.bukkit.GameRule.DO_DAYLIGHT_CYCLE, false);
            dream.setTime(Math.floorMod(fixedTime, 24000L));
        }

        dream.setGameRule(org.bukkit.GameRule.DO_WEATHER_CYCLE, false);
        dream.setStorm(false);
        dream.setThundering(false);

        moons.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()
                    || !dreamlandsManager.isDreamlands(player.getWorld())
                    || !isEnabled()) {
                removeDisplay(entry.getValue());
                return true;
            }
            return false;
        });

        if (!isEnabled()) return;

        for (Player player : dream.getPlayers()) {
            if (!player.isOnline() || player.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
                continue;
            }

            ItemDisplay moon = moons.get(player.getUniqueId());
            if (moon == null || moon.isDead() || !moon.isValid()) {
                removeDisplay(moon);
                moon = createMoon(player);
                moons.put(player.getUniqueId(), moon);
            }

            moon.teleport(moonLocation(player));

            // 同じドリームランドに複数人いても月が重複して見えないよう、
            // 自分の月だけ表示し、他人用の追従月は毎回隠す。
            for (Map.Entry<UUID, ItemDisplay> entry : moons.entrySet()) {
                ItemDisplay otherMoon = entry.getValue();
                if (otherMoon == null || !otherMoon.isValid()) continue;
                if (entry.getKey().equals(player.getUniqueId())) {
                    player.showEntity(plugin, otherMoon);
                } else {
                    player.hideEntity(plugin, otherMoon);
                }
            }
        }
    }

    private ItemDisplay createMoon(Player owner) {
        ItemDisplay display = owner.getWorld().spawn(moonLocation(owner), ItemDisplay.class);

        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(MOON_CUSTOM_MODEL_DATA);
        item.setItemMeta(meta);

        display.setItemStack(item);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
        display.setBillboard(Display.Billboard.CENTER);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setViewRange(12.0f);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);

        float scale = moonScale();
        display.setTransformation(new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new Quaternionf(),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        ));

        // 各探索者には自分専用の追従月だけを見せる。
        for (Player other : plugin.getServer().getOnlinePlayers()) {
            if (!other.getUniqueId().equals(owner.getUniqueId())) {
                other.hideEntity(plugin, display);
            }
        }

        return display;
    }

    private Location moonLocation(Player player) {
        double height = plugin.getConfig().getDouble("dreamlands.moon.height-offset", 48.0);
        double offsetX = plugin.getConfig().getDouble("dreamlands.moon.offset-x", 0.0);
        double offsetZ = plugin.getConfig().getDouble("dreamlands.moon.offset-z", 0.0);

        // 真夜中の標準月と重なりやすい天頂付近へ置き、
        // 巨大なカスタム月で標準月を視覚的に覆う。
        return player.getLocation().clone().add(offsetX, height, offsetZ);
    }

    private float moonScale() {
        double configured = plugin.getConfig().getDouble("dreamlands.moon.scale", 26.0);
        return (float) Math.max(4.0, Math.min(48.0, configured));
    }

    private void clearAll() {
        for (ItemDisplay display : moons.values()) {
            removeDisplay(display);
        }
        moons.clear();
    }

    private void removeDisplay(ItemDisplay display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }
}
