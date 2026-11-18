package io.github.crafteve.idlefactory.observation;

import java.util.HashMap;
import java.util.Map;

import io.github.crafteve.idlefactory.location.BlockKey;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.ICapabilityInvalidationListener;

/**
 * 能力失效监听：机器 / 容器被换能力、升级方块实体时把它标脏，等下一 tick 重探。
 *
 * <p>NeoForge 的能力失效只能按位置注册监听器，没有全局事件，所以这里按观测表的行懒注册：
 * 行第一次被探测时注册，行被对账删掉时丢掉引用。注册回调里只标脏，不查世界——查世界是下一
 * tick 复探的事。</p>
 *
 * <p>监听器必须被强引用持有：NeoForge 的持有者存的是弱引用，丢了就再也收不到失效通知。</p>
 */
public final class CapabilityListener {
    private final MinecraftServer server;
    private final ObservationRunner runner;
    /** 位置 → 已注册的监听器；值必须强引用。 */
    private final Map<BlockKey, ICapabilityInvalidationListener> listeners = new HashMap<>();

    /**
     * @param server 服务端
     * @param runner 观测调度者，回调里只调它标脏
     */
    CapabilityListener(MinecraftServer server, ObservationRunner runner) {
        this.server = server;
        this.runner = runner;
    }

    /**
     * 确保一个位置已注册失效监听；已注册时什么都不做。
     *
     * @param key 位置键
     */
    void track(BlockKey key) {
        if (this.listeners.containsKey(key)) {
            return;
        }
        ServerLevel level = this.server.getLevel(key.dimension());
        if (level == null) {
            return;
        }
        ICapabilityInvalidationListener listener = () -> {
            this.listeners.remove(key);
            this.runner.markDirty(key);
            return false;
        };
        this.listeners.put(key, listener);
        level.registerCapabilityListener(key.pos(), listener);
    }

    /**
     * 丢掉已经不在观测表里的位置。
     *
     * <p>NeoForge 没有注销接口，这里只丢强引用；回调里也会自己移除，不会反复触发。</p>
     *
     * @param table 观测表
     */
    void retain(ObservationTable table) {
        this.listeners.keySet().removeIf(key -> !table.contains(key));
    }

    /** 停服：丢引用。 */
    void clear() {
        this.listeners.clear();
    }
}
