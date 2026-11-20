package io.github.crafteve.idlefactory.network.payload;

import appeng.api.config.SortDir;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.menu.node.NodeTerminalMenu;
import io.github.crafteve.idlefactory.menu.passiveio.PassiveIoTerminalMenu;
import io.github.crafteve.idlefactory.menu.TerminalSortMode;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端请求写回终端排序偏好（档位 + 方向）。
 *
 * <p>排序偏好存在物品的 {@code ae2:exported_settings} 组件里；客户端直接改自己的物品栈不会
 * 同步到服务端，所以由服务端写组件，再回一份确认。服务端只接受当前打开的两种终端菜单与匹配的
 * 容器 id，避免拿着过期的 containerId 写到别的界面。</p>
 *
 * @param containerId 菜单容器 id
 * @param mode        排序档位
 * @param direction   排序方向
 */
public record FactoryTerminalSortC2S(int containerId, TerminalSortMode mode, SortDir direction)
        implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:terminal_sort}。 */
    public static final Type<FactoryTerminalSortC2S> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "terminal_sort"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryTerminalSortC2S> STREAM_CODEC =
            StreamCodec.of(FactoryTerminalSortC2S::write, FactoryTerminalSortC2S::read);

    @Override
    public Type<FactoryTerminalSortC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单后写物品组件，并回 S2C 确认。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryTerminalSortC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            AbstractContainerMenu menu = player.containerMenu;
            if (menu.containerId != payload.containerId()) {
                return;
            }
            if (menu instanceof PassiveIoTerminalMenu passive) {
                passive.host().setSort(payload.mode(), payload.direction());
            } else if (menu instanceof NodeTerminalMenu node) {
                node.host().setSort(payload.mode(), payload.direction());
            } else {
                return;
            }
            player.getInventory().setChanged();
            PacketDistributor.sendToPlayer(player,
                    new FactoryTerminalSortS2C(payload.containerId(), payload.mode(),
                            payload.direction()));
        });
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryTerminalSortC2S payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeByte(payload.mode().ordinal());
        buf.writeByte(payload.direction().ordinal());
    }

    private static FactoryTerminalSortC2S read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        TerminalSortMode[] modes = TerminalSortMode.values();
        int modeOrdinal = buf.readUnsignedByte();
        if (modeOrdinal >= modes.length) {
            throw new DecoderException("invalid terminal sort mode ordinal: " + modeOrdinal);
        }
        SortDir[] directions = SortDir.values();
        int directionOrdinal = buf.readUnsignedByte();
        if (directionOrdinal >= directions.length) {
            throw new DecoderException("invalid terminal sort direction ordinal: "
                    + directionOrdinal);
        }
        return new FactoryTerminalSortC2S(containerId, modes[modeOrdinal],
                directions[directionOrdinal]);
    }
}
