package io.github.crafteve.idlefactory.network.payload;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.menu.FactoryPatternTerminalMenu;
import io.github.crafteve.idlefactory.menu.FactoryTerminalMenus;
import io.github.crafteve.idlefactory.network.FactoryTerminalSnapshots;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端在主动样板视图里选中一个目标。
 *
 * <p>只传目标键；服务端在当前菜单的目标表里重新解析，找不到就忽略，不信任客户端传的
 * 槽数、模式等状态。</p>
 *
 * @param containerId 菜单容器 id
 * @param key         目标稳定键
 * @param page        样板页
 */
public record FactoryTerminalSelectC2S(int containerId, String key,
        int page) implements CustomPacketPayload {
    /** 键长度上限，与列表包一致。 */
    public static final int MAX_KEY = 128;

    /** 包类型：{@code idlefactory:terminal_select}。 */
    public static final Type<FactoryTerminalSelectC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "terminal_select"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryTerminalSelectC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.containerId());
                        buf.writeUtf(payload.key(), MAX_KEY);
                        buf.writeVarInt(payload.page());
                    },
                    buf -> new FactoryTerminalSelectC2S(buf.readVarInt(), buf.readUtf(MAX_KEY),
                            buf.readVarInt()));

    @Override
    public Type<FactoryTerminalSelectC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单，把选中键交给样板菜单。
     *
     * @param payload 选择
     * @param context 上下文
     */
    public static void handle(FactoryTerminalSelectC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof FactoryPatternTerminalMenu menu)
                    || !FactoryTerminalMenus.isFactoryMenu(menu, payload.containerId())) {
                return;
            }
            if (menu.selectTarget(payload.key(), payload.page())) {
                FactoryTerminalSnapshots.sendDetail(player, payload.containerId(), payload.key(),
                    menu.page());
            }
        });
    }
}
