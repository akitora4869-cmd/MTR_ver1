package Rin.TRPGCharacter;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Model Engine 4 optional bridge for Paper 1.20.1.
 *
 * This class intentionally uses reflection instead of a compile-time dependency.
 * TRPGCharacter therefore still builds and runs when ModelEngine is absent, and
 * can safely fall back to the existing ItemDisplay renderer.
 */
public class ModelEngineBridgeManager {

    private final Plugin plugin;
    private final MythosManager mythosManager;
    private final Map<UUID, Object> modeledEntities = new HashMap<>();
    private final Map<UUID, Object> activeModels = new HashMap<>();
    private BukkitTask scanTask;
    private org.bukkit.plugin.Plugin modelEnginePlugin;
    private Class<?> apiClass;
    private String lastError = "";

    public ModelEngineBridgeManager(Plugin plugin, MythosManager mythosManager) {
        this.plugin = plugin;
        this.mythosManager = mythosManager;
    }

    public void start() {
        shutdown(false);
        detect();
        if (!isAvailable()) {
            return;
        }

        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (LivingEntity entity : world.getLivingEntities()) {
                    MythosCreatureDefinition def = mythosManager.getDefinition(entity);
                    if (def != null && mythosManager.isModelEngineEnabled(def.id())) {
                        ensureAttached(entity, def.id());
                    }
                }
            }
            activeModels.keySet().removeIf(uuid -> {
                Entity e = Bukkit.getEntity(uuid);
                return e == null || !e.isValid() || e.isDead();
            });
            modeledEntities.keySet().retainAll(activeModels.keySet());
        }, 20L, 20L);
    }

    public void reload() {
        start();
    }

    public void shutdown() {
        shutdown(true);
    }

    private void shutdown(boolean restoreVisibility) {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        if (restoreVisibility) {
            for (UUID uuid : activeModels.keySet()) {
                Entity e = Bukkit.getEntity(uuid);
                if (e instanceof LivingEntity living && living.isValid()) {
                    living.setInvisible(false);
                }
            }
        }
        activeModels.clear();
        modeledEntities.clear();
        apiClass = null;
        modelEnginePlugin = null;
    }

    private void detect() {
        if (!plugin.getConfig().getBoolean("model-engine.enabled", true)) {
            lastError = "config disabled";
            return;
        }

        PluginManager pm = Bukkit.getPluginManager();
        modelEnginePlugin = pm.getPlugin("ModelEngine");
        if (modelEnginePlugin == null || !modelEnginePlugin.isEnabled()) {
            lastError = "ModelEngine not installed/enabled";
            modelEnginePlugin = null;
            return;
        }

        try {
            apiClass = Class.forName(
                    "com.ticxo.modelengine.api.ModelEngineAPI",
                    true,
                    modelEnginePlugin.getClass().getClassLoader()
            );
            lastError = "";
            plugin.getLogger().info("ModelEngine detected: " + modelEnginePlugin.getDescription().getVersion());
        } catch (Throwable t) {
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            apiClass = null;
            plugin.getLogger().warning("ModelEngine APIを読み込めません。ItemDisplayへフォールバックします: " + lastError);
        }
    }

    public boolean isAvailable() {
        return modelEnginePlugin != null && apiClass != null && modelEnginePlugin.isEnabled();
    }

    public boolean isAttached(Entity entity) {
        return entity != null && activeModels.containsKey(entity.getUniqueId());
    }

    public boolean ensureAttached(LivingEntity entity, String mythosId) {
        if (entity == null || mythosId == null || !isAvailable()) {
            return false;
        }
        if (!mythosManager.isModelEngineEnabled(mythosId)) {
            return false;
        }
        if (isAttached(entity)) {
            entity.setInvisible(true);
            return true;
        }

        String modelId = mythosManager.getModelEngineId(mythosId);
        if (modelId == null || modelId.isBlank()) {
            return false;
        }

        try {
            Object modeled = invokeStaticOneArg(
                    apiClass,
                    new String[]{"getOrCreateModeledEntity", "createModeledEntity"},
                    entity
            );
            if (modeled == null) {
                throw new IllegalStateException("ModeledEntity creation returned null");
            }

            Object active = invokeStaticOneArg(
                    apiClass,
                    new String[]{"createActiveModel"},
                    modelId
            );
            if (active == null) {
                throw new IllegalStateException("ActiveModel not found: " + modelId);
            }

            if (!invokeAddModel(modeled, active)) {
                throw new NoSuchMethodException("ModeledEntity#addModel compatible method not found");
            }

            modeledEntities.put(entity.getUniqueId(), modeled);
            activeModels.put(entity.getUniqueId(), active);
            entity.setInvisible(true);
            lastError = "";

            playEvent(entity, "idle");
            return true;
        } catch (Throwable t) {
            lastError = rootMessage(t);
            plugin.getLogger().warning(
                    "ModelEngineモデルの適用に失敗: " + mythosId
                            + " / " + entity.getUniqueId()
                            + " / " + lastError
                            + "。ItemDisplayへフォールバックします。"
            );
            return false;
        }
    }

    public boolean playEvent(Entity entity, String eventName) {
        if (entity == null || eventName == null || !isAttached(entity)) {
            return false;
        }
        MythosCreatureDefinition def = mythosManager.getDefinition(entity);
        if (def == null) {
            return false;
        }
        String animation = mythosManager.getModelAnimation(def.id(), eventName);
        if (animation == null || animation.isBlank()) {
            return false;
        }

        Object active = activeModels.get(entity.getUniqueId());
        try {
            Method getHandler = findMethod(active.getClass(), "getAnimationHandler", 0);
            if (getHandler == null) return false;
            Object handler = getHandler.invoke(active);
            if (handler == null) return false;

            Method play = findCompatiblePlay(handler.getClass());
            if (play == null) return false;
            play.invoke(handler, animation, 0.15d, 0.15d, 1.0d, true);
            return true;
        } catch (Throwable t) {
            // Animation names can be absent while a model is still being authored.
            // Do not tear the model down just because an animation is unavailable.
            lastError = rootMessage(t);
            return false;
        }
    }

    private Object invokeStaticOneArg(Class<?> owner, String[] names, Object arg) throws Exception {
        for (String name : names) {
            for (Method m : owner.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || !m.getName().equals(name) || m.getParameterCount() != 1) {
                    continue;
                }
                Class<?> p = m.getParameterTypes()[0];
                if (p.isAssignableFrom(arg.getClass()) || p == Object.class || p == String.class && arg instanceof String) {
                    return m.invoke(null, arg);
                }
            }
        }
        throw new NoSuchMethodException(String.join("/", names));
    }

    private boolean invokeAddModel(Object modeled, Object active) throws Exception {
        for (Method m : modeled.getClass().getMethods()) {
            if (!m.getName().equals("addModel")) continue;
            if (m.getParameterCount() == 2
                    && m.getParameterTypes()[1] == boolean.class
                    && m.getParameterTypes()[0].isAssignableFrom(active.getClass())) {
                m.invoke(modeled, active, true);
                return true;
            }
            if (m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(active.getClass())) {
                m.invoke(modeled, active);
                return true;
            }
        }
        return false;
    }

    private Method findCompatiblePlay(Class<?> type) {
        for (Method m : type.getMethods()) {
            if (!m.getName().equals("playAnimation") || m.getParameterCount() != 5) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0] == String.class
                    && (p[1] == double.class || p[1] == Double.class)
                    && (p[2] == double.class || p[2] == Double.class)
                    && (p[3] == double.class || p[3] == Double.class)
                    && (p[4] == boolean.class || p[4] == Boolean.class)) {
                return m;
            }
        }
        return null;
    }

    private Method findMethod(Class<?> type, String name, int count) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == count) return m;
        }
        return null;
    }

    private String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) current = current.getCause();
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }

    public String statusLine() {
        if (!plugin.getConfig().getBoolean("model-engine.enabled", true)) {
            return "&eModelEngine連携: 無効(config)";
        }
        if (!isAvailable()) {
            return "&eModelEngine連携: 未接続 &7(" + lastError + ")";
        }
        return "&aModelEngine連携: 接続中 &7v"
                + modelEnginePlugin.getDescription().getVersion()
                + " / モデル適用 " + activeModels.size() + "体";
    }
}
