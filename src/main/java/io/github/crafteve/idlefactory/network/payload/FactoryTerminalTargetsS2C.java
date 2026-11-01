package io.github.crafteve.idlefactory.network.payload;

import java.util.ArrayList;
import java.util.List;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientFactoryTerminalHandler;
import io.github.crafteve.idlefactory.menu.FactoryTerminalTargetKind;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端的主动合成目标列表。
 *
 * <p>只发界面需要的摘要：类型、稳定键、显示名、图标、槽数与已用槽数；样板内容本身由
 * 右半边的 AE2 样板访问逻辑负责，不塞进这个包。键是字符串，机器用
 * {@code machine:<uuid>}，AE2 设备用它在网格里的稳定序号。</p>
 *
 * @param containerId 菜单容器 id
 * @param targets     目标列表
 */
public record FactoryTerminalTargetsS2C(int containerId,
        List<Entry> targets) implements CustomPacketPayload {
    /** 目标数量上限与机器库一致，防止坏包撑爆界面。 */
    public static final int MAX_TARGETS = 512;
    /** 名字 / 键的长度上限。 */
    public static final int MAX_TEXT = 128;

    /** 包类型：{@code idlefactory:terminal_targets}。 */
    public static final Type<FactoryTerminalTargetsS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "terminal_targets"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryTerminalTargetsS2C> STREAM_CODEC =
            StreamCodec.of(FactoryTerminalTargetsS2C::write, FactoryTerminalTargetsS2C::read);

    public FactoryTerminalTargetsS2C {
        targets = List.copyOf(targets);
    }

    @Override
    public Type<FactoryTerminalTargetsS2C> type() {
        return TYPE;
    }

    /**
     * 客户端处理。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(FactoryTerminalTargetsS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientFactoryTerminalHandler.handleTargets(payload));
    }

    /**
     * 目标摘要。
     *
     * @param kind     来源类型
     * @param key      稳定键；选中与详情都按它找目标
     * @param name     显示名
     * @param icon     图标；未知时为空栈
     * @param capacity 样板槽数
     * @param used     已放样板数
     */
    public record Entry(FactoryTerminalTargetKind kind, String key, String name, ItemStack icon,
            int capacity, int used) {
        public Entry {
            icon = icon.copy();
        }
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryTerminalTargetsS2C payload) {
        buf.writeVarInt(payload.containerId());
        int count = Math.min(payload.targets().size(), MAX_TARGETS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            Entry entry = payload.targets().get(i);
            buf.writeByte(entry.kind().ordinal());
            writeText(buf, entry.key());
            writeText(buf, entry.name());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, entry.icon());
            buf.writeVarInt(entry.capacity());
            buf.writeVarInt(entry.used());
        }
    }

    private static FactoryTerminalTargetsS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_TARGETS) {
            throw new DecoderException("invalid target count: " + count);
        }
        List<Entry> targets = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            FactoryTerminalTargetKind kind = FactoryTerminalTargetKind.read(buf.readUnsignedByte());
            String key = readText(buf, "target key");
            String name = readText(buf, "target name");
            ItemStack icon = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            int capacity = buf.readVarInt();
            int used = buf.readVarInt();
            targets.add(new Entry(kind, key, name, icon, capacity, used));
        }
        return new FactoryTerminalTargetsS2C(containerId, targets);
    }

    private static void writeText(RegistryFriendlyByteBuf buf, String text) {
        String clipped = text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text;
        buf.writeUtf(clipped, MAX_TEXT);
    }

    private static String readText(RegistryFriendlyByteBuf buf, String what) {
        String text = buf.readUtf(MAX_TEXT);
        if (text.length() > MAX_TEXT) {
            throw new DecoderException("invalid " + what + " length");
        }
        return text;
    }
}
