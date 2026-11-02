package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.menu.FactoryTerminalMenus;

import appeng.menu.AEBaseMenu;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端请求打开样板管理终端。
 *
 * <p>和视图切换同一套校验：服务端只接受「当前打开的确实是工厂终端菜单、容器 id 对得上」的
 * 请求，然后自己按锚点网格重开样板管理菜单；不信任客户端传的任何其他信息。</p>
 *
 * @param containerId 当前菜单容器 id
 */
public record FactoryPatternAccessOpenC2S(int containerId) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:pattern_access_open}。 */
    public static final Type<FactoryPatternAccessOpenC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "pattern_access_open"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryPatternAccessOpenC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.containerId()),
                    buf -> new FactoryPatternAccessOpenC2S(buf.readVarInt()));

    @Override
    public Type<FactoryPatternAccessOpenC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单后打开样板管理终端。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryPatternAccessOpenC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof AEBaseMenu menu)
                    || !FactoryTerminalMenus.isFactoryMenu(menu, payload.containerId())) {
                return;
            }
            FactoryTerminalMenus.openPatternAccess(player);
        });
    }
}
