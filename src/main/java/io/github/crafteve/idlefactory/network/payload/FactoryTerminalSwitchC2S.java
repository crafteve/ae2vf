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
 * 客户端请求把终端从合成视图切到样板视图（或反向）。
 *
 * <p>不信任客户端的视图名：服务端只接受「当前打开的确实是工厂终端菜单」的请求，
 * 然后自己翻一次视图表并重开菜单。</p>
 *
 * @param containerId 菜单容器 id
 */
public record FactoryTerminalSwitchC2S(int containerId) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:terminal_switch}。 */
    public static final Type<FactoryTerminalSwitchC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "terminal_switch"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryTerminalSwitchC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeVarInt(payload.containerId()),
                    buf -> new FactoryTerminalSwitchC2S(buf.readVarInt()));

    @Override
    public Type<FactoryTerminalSwitchC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单，切换视图并重开。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryTerminalSwitchC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof AEBaseMenu menu)
                    || !FactoryTerminalMenus.isFactoryMenu(menu, payload.containerId())) {
                return;
            }
            FactoryTerminalMenus.switchView(player, menu);
        });
    }
}
