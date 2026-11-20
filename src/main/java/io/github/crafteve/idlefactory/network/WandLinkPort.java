package io.github.crafteve.idlefactory.network;

import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.network.payload.WandLinksS2C;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 绑定杖链接快照的网络出口。
 *
 * <p>工厂只属于一个玩家：这里有在线连接就推最新快照，不在线就只留服务端状态，等下次登录或
 * 打开终端时由客户端主动请求。发包集中在 network 包，工厂层不直接碰负载。</p>
 */
public final class WandLinkPort {
    private WandLinkPort() {
        // 纯静态端口
    }

    /**
     * 把一个工厂的最新链接快照推给它的在线拥有者。
     *
     * @param server  服务端
     * @param factory 发生结构变化的工厂
     */
    public static void push(MinecraftServer server, Factory factory) {
        ServerPlayer owner = server.getPlayerList().getPlayer(factory.owner());
        if (owner != null) {
            PacketDistributor.sendToPlayer(owner, WandLinksS2C.of(factory));
        }
    }
}
