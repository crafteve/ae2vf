package io.github.crafteve.idlefactory.network.payload;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.network.ClientFactoryTableHandler;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 编辑动作的服务端反馈：成功/失败 + 一句话 + 受影响的产线 id。
 *
 * <p>界面打开时聊天栏不可见，所以编辑结果必须走这条包；客户端把它显示在命名浮层、IO 编辑区
 * 或左表底部状态条。消息在服务端裁到 {@link #MAX_MESSAGE} 字符，解码侧用同样的上限。</p>
 *
 * @param containerId 菜单容器 id
 * @param ok          是否成功
 * @param message     给玩家看的一句话
 * @param lineId      受影响的产线；新建时是新线 id
 */
public record FactoryEditFeedbackS2C(int containerId, boolean ok, String message,
        @Nullable UUID lineId) implements CustomPacketPayload {

    /** 反馈消息最大字符数。 */
    public static final int MAX_MESSAGE = 120;

    /** 包类型：{@code idlefactory:factory_edit_feedback}。 */
    public static final Type<FactoryEditFeedbackS2C> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID,
                    "factory_edit_feedback"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryEditFeedbackS2C> STREAM_CODEC =
            StreamCodec.of(FactoryEditFeedbackS2C::write, FactoryEditFeedbackS2C::read);

    @Override
    public Type<FactoryEditFeedbackS2C> type() {
        return TYPE;
    }

    /**
     * 截断过长的反馈文案；服务端和客户端画布都按这个上限处理。
     *
     * @param message 原文
     * @return 不超过 {@link #MAX_MESSAGE} 的文本
     */
    public static String clip(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MAX_MESSAGE ? message : message.substring(0, MAX_MESSAGE);
    }

    /**
     * 客户端处理：交给当前工作台。
     *
     * @param payload 负载
     * @param context 上下文
     */
    public static void handle(FactoryEditFeedbackS2C payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientFactoryTableHandler.handleFeedback(payload));
    }

    private static void write(RegistryFriendlyByteBuf buf, FactoryEditFeedbackS2C payload) {
        buf.writeVarInt(payload.containerId());
        buf.writeBoolean(payload.ok());
        buf.writeUtf(clip(payload.message()), MAX_MESSAGE);
        buf.writeBoolean(payload.lineId() != null);
        if (payload.lineId() != null) {
            buf.writeUUID(payload.lineId());
        }
    }

    private static FactoryEditFeedbackS2C read(RegistryFriendlyByteBuf buf) {
        int containerId = buf.readVarInt();
        boolean ok = buf.readBoolean();
        String message = buf.readUtf(MAX_MESSAGE);
        UUID lineId = buf.readBoolean() ? buf.readUUID() : null;
        return new FactoryEditFeedbackS2C(containerId, ok, message, lineId);
    }
}
