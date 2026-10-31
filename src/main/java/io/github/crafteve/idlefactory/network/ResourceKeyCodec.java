package io.github.crafteve.idlefactory.network;

import appeng.api.stacks.AEKey;

import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * {@link AEKey} 的唯一网络编解码入口。
 *
 * <p>直接委托 AE2 的 {@link AEKey#STREAM_CODEC}，不再自己拼「类型 + 注册名 + 组件快照」。
 * 资源类型已经统一成 AE 的 key（物品 / 流体 / 装了 Applied Flux 后的能量都由 AE2 自己
 * 处理），所以这里不需要也不应该知道有几种类型。</p>
 *
 * <p>保留这个薄包装是为了把编解码收在一处：改协议时只动这里，调用方（规则负载、仓库快照）
 * 不受影响。</p>
 */
public final class ResourceKeyCodec {
    private ResourceKeyCodec() {
        // 纯静态工具类
    }

    /**
     * 写一个 key。
     *
     * @param buf 目标缓冲
     * @param key 资源
     */
    public static void write(RegistryFriendlyByteBuf buf, AEKey key) {
        AEKey.STREAM_CODEC.encode(buf, key);
    }

    /**
     * 读一个 key。
     *
     * @param buf 来源缓冲
     * @return 资源
     */
    public static AEKey read(RegistryFriendlyByteBuf buf) {
        return AEKey.STREAM_CODEC.decode(buf);
    }
}
