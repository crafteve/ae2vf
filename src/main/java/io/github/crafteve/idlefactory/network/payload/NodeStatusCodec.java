package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.factory.FactorySnapshotBuilder;
import io.github.crafteve.idlefactory.factory.NodeStatus;
import io.github.crafteve.idlefactory.observation.NodeHealth;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * {@link NodeStatus} 的线协议编解码。
 *
 * <p>节点状态在服务端只算一次，随节点表 / 机器表下发；客户端只读。枚举用字节写 ordinal，
 * 读侧越界抛 {@link DecoderException}，和现有快照包的写法一致。</p>
 */
public final class NodeStatusCodec {
    private NodeStatusCodec() {
        // 纯静态编解码
    }

    /**
     * 写一个节点状态。
     *
     * @param buf    缓冲
     * @param status 节点状态
     */
    public static void write(RegistryFriendlyByteBuf buf, NodeStatus status) {
        buf.writeBoolean(status.probed());
        buf.writeBoolean(status.stale());
        buf.writeByte(status.health().ordinal());
        buf.writeByte(status.totalState().ordinal());
    }

    /**
     * 读一个节点状态。
     *
     * @param buf 缓冲
     * @return 节点状态
     */
    public static NodeStatus read(RegistryFriendlyByteBuf buf) {
        boolean probed = buf.readBoolean();
        boolean stale = buf.readBoolean();
        NodeHealth health = readEnum(buf, NodeHealth.values(), "node health");
        FactorySnapshotBuilder.TotalState totalState =
                readEnum(buf, FactorySnapshotBuilder.TotalState.values(), "total state");
        return new NodeStatus(probed, stale, health, totalState);
    }

    private static <E extends Enum<E>> E readEnum(RegistryFriendlyByteBuf buf, E[] values,
            String name) {
        int ordinal = buf.readUnsignedByte();
        if (ordinal >= values.length) {
            throw new DecoderException("invalid " + name + " ordinal: " + ordinal);
        }
        return values[ordinal];
    }
}
