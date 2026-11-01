package io.github.crafteve.idlefactory.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.menu.FactoryTerminalTargets;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetS2C;
import io.github.crafteve.idlefactory.network.payload.FactoryTerminalTargetsS2C;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 主动样板目标表的发送出口。
 *
 * <p>列表与详情的构建都走 {@link FactoryTerminalTargets}，不在网络层另算一份；样板内容
 * 不在这里发，右半边 AE2 终端自己读。</p>
 */
public final class FactoryTerminalSnapshots {
    private FactoryTerminalSnapshots() {
        // 纯静态工具类
    }

    /**
     * 立即发一份目标列表；工厂不存在时退回空表。
     *
     * @param player      目标玩家
     * @param containerId 菜单容器 id
     */
    public static void sendTargets(ServerPlayer player, int containerId) {
        FactoryRuntime runtime = FactoryRuntime.get();
        Optional<Factory> factory = runtime == null
                ? Optional.empty() : runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            PacketDistributor.sendToPlayer(player,
                    new FactoryTerminalTargetsS2C(containerId, List.of()));
            return;
        }
        sendTargets(player, containerId, factory.get());
    }

    /**
     * 立即发一份目标列表。
     *
     * @param player      目标玩家
     * @param containerId 菜单容器 id；过期界面自行忽略
     * @param factory     拥有者工厂
     */
    public static void sendTargets(ServerPlayer player, int containerId, Factory factory) {
        List<FactoryTerminalTargetsS2C.Entry> entries = new ArrayList<>();
        for (FactoryTerminalTargets.Entry entry
                : new FactoryTerminalTargets(factory, player).entries()) {
            entries.add(new FactoryTerminalTargetsS2C.Entry(entry.kind(), entry.key(),
                    entry.name(), entry.icon(), entry.capacity(), entry.used()));
        }
        PacketDistributor.sendToPlayer(player,
                new FactoryTerminalTargetsS2C(containerId, entries));
    }

    /**
     * 发一个目标的详情；键不存在时什么都不发。
     *
     * @param player      目标玩家
     * @param containerId 菜单容器 id
     * @param key         目标键
     */
    public static void sendDetail(ServerPlayer player, int containerId, String key) {
        FactoryRuntime runtime = FactoryRuntime.get();
        Optional<Factory> factory = runtime == null
                ? Optional.empty() : runtime.manager().byOwner(player.getUUID());
        if (factory.isPresent()) {
            sendDetail(player, containerId, key, factory.get());
        }
    }

    /**
     * 发一个目标的详情；键不存在时什么都不发。
     *
     * @param player      目标玩家
     * @param containerId 菜单容器 id
     * @param key         目标键
     * @param factory     拥有者工厂
     */
    public static void sendDetail(ServerPlayer player, int containerId, String key, Factory factory) {
        FactoryTerminalTargets.Detail detail =
                new FactoryTerminalTargets(factory, player).detail(key);
        if (detail == null) {
            return;
        }
        int mode = detail.mode() == null ? -1 : detail.mode().ordinal();
        PacketDistributor.sendToPlayer(player, new FactoryTerminalTargetS2C(containerId,
                detail.kind(), detail.key(), detail.name(), mode, detail.online(),
                detail.capacity(), detail.used()));
    }

    /**
     * 机器目标的键。
     *
     * @param machineId 机器 id
     * @return 目标键
     */
    public static String machineKey(UUID machineId) {
        return FactoryTerminalTargets.keyOf(machineId);
    }
}
