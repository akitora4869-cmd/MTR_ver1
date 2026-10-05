package Rin.TRPGCharacter;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 深きもの専用モデル。
 * 元のDROWNEDは透明化し、AI・移動・当たり判定・戦闘処理を維持する。
 * 見た目のみリソースパックのItemDisplayへ置換する。
 */
public class DeepOneVisualManager {

    public static final int MODEL_DATA = 93210;
    private static final String ID = "deep_one";

    private final Plugin plugin;
    private final MythosManager mythosManager;
    private final NamespacedKey visualKey;
    private final Map<UUID, ItemDisplay> displays = new HashMap<>();
    private BukkitTask task;

    public DeepOneVisualManager(Plugin plugin, MythosManager mythosManager) {
        this.plugin = plugin;
        this.mythosManager = mythosManager;
        this.visualKey = new NamespacedKey(plugin, "deep_one_visual");
    }

    public void start() {
        shutdown();
        cleanupOrphans();

        task = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::tick,
                1L,
                2L
        );
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }

        for (Map.Entry<UUID, ItemDisplay> entry : displays.entrySet()) {
            org.bukkit.entity.Entity host = plugin.getServer().getEntity(entry.getKey());
            if (host instanceof LivingEntity living && living.isValid()) {
                living.setInvisible(false);
            }
            if (entry.getValue() != null && entry.getValue().isValid()) {
                entry.getValue().remove();
            }
        }
        displays.clear();
    }

    public void onMythosSpawn(LivingEntity entity, String id) {
        if (entity == null || !ID.equalsIgnoreCase(id)) {
            return;
        }
        if (plugin.getModelEngineBridgeManager() != null
                && plugin.getModelEngineBridgeManager().ensureAttached(entity, id)) {
            removeFallback(entity.getUniqueId());
            return;
        }
        ensure(entity);
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("mythos-visuals.enabled", true)) {
            restoreAndClear();
            return;
        }

        // 既存個体や/reload後の個体も拾う。
        for (World world : plugin.getServer().getWorlds()) {
            for (LivingEntity entity : world.getLivingEntities()) {
                MythosCreatureDefinition def = mythosManager.getDefinition(entity);
                if (def != null && ID.equalsIgnoreCase(def.id())) {
                    if (plugin.getModelEngineBridgeManager() != null
                            && plugin.getModelEngineBridgeManager().ensureAttached(entity, def.id())) {
                        removeFallback(entity.getUniqueId());
                    } else {
                        ensure(entity);
                    }
                }
            }
        }

        Iterator<Map.Entry<UUID, ItemDisplay>> it = displays.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, ItemDisplay> entry = it.next();
            org.bukkit.entity.Entity raw = plugin.getServer().getEntity(entry.getKey());
            ItemDisplay display = entry.getValue();

            if (!(raw instanceof LivingEntity host)
                    || !host.isValid()
                    || host.isDead()
                    || display == null
                    || !display.isValid()) {
                if (display != null && display.isValid()) {
                    display.remove();
                }
                it.remove();
                continue;
            }

            MythosCreatureDefinition def = mythosManager.getDefinition(host);
            if (def == null || !ID.equalsIgnoreCase(def.id())) {
                host.setInvisible(false);
                display.remove();
                it.remove();
                continue;
            }

            host.setInvisible(true);
            update(host, display, def.visualScale());
        }
    }

    private void ensure(LivingEntity host) {
        if (!plugin.getConfig().getBoolean("mythos-visuals.enabled", true)) {
            return;
        }
        if (plugin.getModelEngineBridgeManager() != null
                && plugin.getModelEngineBridgeManager().isAttached(host)) {
            removeFallback(host.getUniqueId());
            return;
        }
        if (!mythosManager.useItemDisplayFallback(ID)) {
            host.setInvisible(false);
            removeFallback(host.getUniqueId());
            return;
        }
        if (displays.containsKey(host.getUniqueId())) {
            return;
        }

        MythosCreatureDefinition def = mythosManager.getDefinition(host);
        if (def == null || !ID.equalsIgnoreCase(def.id())) {
            return;
        }

        host.setInvisible(true);

        ItemDisplay display = host.getWorld().spawn(host.getLocation(), ItemDisplay.class);
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setCustomModelData(MODEL_DATA);
        item.setItemMeta(meta);

        display.setItemStack(item);
        display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        display.setBillboard(Display.Billboard.FIXED);
        display.setBrightness(new Display.Brightness(15, 15));
        display.setViewRange(8.0f);
        display.setPersistent(false);
        display.setInvulnerable(true);
        display.setGravity(false);
        display.getPersistentDataContainer().set(
                visualKey,
                PersistentDataType.BYTE,
                (byte) 1
        );

        displays.put(host.getUniqueId(), display);
        update(host, display, def.visualScale());
    }

    private void update(LivingEntity host, ItemDisplay display, double configuredScale) {
        float scale = (float) Math.max(0.5, Math.min(3.0, configuredScale));

        Location loc = host.getLocation().clone();
        display.teleport(loc);

        float yaw = (float) Math.toRadians(-loc.getYaw() + 180.0f);

        display.setTransformation(new Transformation(
                new Vector3f(0.0f, 0.0f, 0.0f),
                new Quaternionf().rotateY(yaw),
                new Vector3f(scale, scale, scale),
                new Quaternionf()
        ));
    }

    private void restoreAndClear() {
        for (Map.Entry<UUID, ItemDisplay> entry : displays.entrySet()) {
            org.bukkit.entity.Entity raw = plugin.getServer().getEntity(entry.getKey());
            if (raw instanceof LivingEntity host && host.isValid()) {
                host.setInvisible(false);
            }
            ItemDisplay display = entry.getValue();
            if (display != null && display.isValid()) {
                display.remove();
            }
        }
        displays.clear();
    }


    private void removeFallback(UUID uuid) {
        ItemDisplay display = displays.remove(uuid);
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    private void cleanupOrphans() {
        for (World world : plugin.getServer().getWorlds()) {
            for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
                if (display.getPersistentDataContainer().has(
                        visualKey,
                        PersistentDataType.BYTE
                )) {
                    display.remove();
                }
            }
        }
    }
}
