package io.github.crafteve.idlefactory.network.payload;

import java.util.UUID;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.Factory;
import io.github.crafteve.idlefactory.factory.FactoryRuntime;
import io.github.crafteve.idlefactory.factory.machine.Machine;
import io.github.crafteve.idlefactory.factory.machine.MachineProvider;
import io.github.crafteve.idlefactory.factory.node.MachineNode;
import io.github.crafteve.idlefactory.menu.FactoryTerminalMenus;
import io.github.crafteve.idlefactory.network.FactoryTerminalSnapshots;

import appeng.api.inventories.InternalInventory;
import appeng.core.definitions.AEItems;
import appeng.menu.AEBaseMenu;

import io.netty.handler.codec.DecoderException;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 客户端对自绘样板槽的动作：拾取 / 放置 / 交换 / 快速移到背包。
 *
 * <p>样板槽不在菜单里，所以不能用原版的点击包；服务端收到后按「机器 + 槽位」重校验，
 * 只认工厂终端菜单、存在的机器、合法槽位，然后读写 {@link MachineProvider} 并把结果
 * 通过 {@code setCarried} 同步回客户端。客户端传来的机器 id、槽位、动作一律不信内容，
 * 只当意图。</p>
 *
 * <p><b>写入必须经 {@link MachineNode#getTerminalPatternInventory()}，不能直接调
 * {@code provider.setPattern}</b>：后者只是把样板塞进数组，不触发「按第一张样板转主动」
 * 「样板脏标记」「容量刷新」三件事，结果就是界面放完样板后 CPU 看不到、容量不更新。
 * 本条包是界面第二条写路径，与 AE2 样板访问终端走同一个入口。</p>
 *
 * @param containerId 菜单容器 id
 * @param machineId   机器 id
 * @param slot        样板槽下标
 * @param action      动作
 */
public record FactoryPatternSlotActionC2S(int containerId, UUID machineId, int slot,
        Action action) implements CustomPacketPayload {
    /** 包类型：{@code idlefactory:pattern_slot_action}。 */
    public static final Type<FactoryPatternSlotActionC2S> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(IdleFactory.MODID, "pattern_slot_action"));

    /** 编解码器。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, FactoryPatternSlotActionC2S> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> {
                        buf.writeVarInt(payload.containerId());
                        buf.writeUUID(payload.machineId());
                        buf.writeVarInt(payload.slot());
                        buf.writeByte(payload.action().ordinal());
                    },
                    buf -> {
                        int containerId = buf.readVarInt();
                        UUID machineId = buf.readUUID();
                        int slot = buf.readVarInt();
                        int ordinal = buf.readUnsignedByte();
                        if (ordinal >= Action.values().length) {
                            throw new DecoderException("invalid pattern slot action: " + ordinal);
                        }
                        return new FactoryPatternSlotActionC2S(containerId, machineId, slot,
                                Action.values()[ordinal]);
                    });

    @Override
    public Type<FactoryPatternSlotActionC2S> type() {
        return TYPE;
    }

    /**
     * 服务端处理：校验菜单、机器、槽位后执行动作。
     *
     * @param payload 请求
     * @param context 上下文
     */
    public static void handle(FactoryPatternSlotActionC2S payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof AEBaseMenu menu)
                    || !FactoryTerminalMenus.isFactoryMenu(menu, payload.containerId())) {
                return;
            }
            FactoryRuntime runtime = FactoryRuntime.get();
            if (runtime == null) {
                return;
            }
            Factory factory = runtime.manager().byOwner(player.getUUID()).orElse(null);
            if (factory == null) {
                return;
            }
            Machine machine = factory.config().machines().byId(payload.machineId()).orElse(null);
            if (machine == null) {
                return;
            }
            MachineProvider provider = machine.provider();
            if (payload.slot() < 0 || payload.slot() >= provider.capacity()) {
                return;
            }
            MachineNode node = runtime.nodeManager().node(machine.id());
            if (node == null) {
                // 节点对账还没跑到这台机器：本轮不写。写入必须经节点，否则会绕开脏标记。
                FactoryTerminalSnapshots.sendTargets(player, payload.containerId(), factory);
                return;
            }
            apply(node, menu, player, provider, payload.slot(), payload.action());
            // 样板池变了，重发列表，客户端自绘槽位才会跟着更新。
            FactoryTerminalSnapshots.sendTargets(player, payload.containerId(), factory);
        });
    }

    /**
     * 执行一个动作；不合法组合直接忽略，不写任何东西。
     *
     * <p>写库存一律走 {@link MachineNode#getTerminalPatternInventory()}，由
     * {@code MachinePatternInventory} 负责在写完后刷样板脏标记与容量；先写库存再动光标，
     * 写失败时光标不动，不会出现「光标拿了、槽里没清」的两份。</p>
     *
     * @param node     机器节点，提供样板库存的写入口
     * @param menu     当前菜单，光标在这里
     * @param player   玩家，快速移动进背包时用
     * @param provider 机器样板池，只用来读当前槽内容
     * @param slot     槽位
     * @param action   动作
     */
    private static void apply(MachineNode node, AEBaseMenu menu, ServerPlayer player,
            MachineProvider provider, int slot, Action action) {
        InternalInventory inventory = node.getTerminalPatternInventory();
        ItemStack carried = menu.getCarried();
        ItemStack slotStack = provider.pattern(slot);
        switch (action) {
            case PICKUP -> {
                if (!carried.isEmpty() || slotStack.isEmpty()) {
                    return;
                }
                inventory.setItemDirect(slot, ItemStack.EMPTY);
                menu.setCarried(slotStack.copy());
            }
            case PLACE -> {
                if (carried.isEmpty() || !slotStack.isEmpty()
                        || !AEItems.PROCESSING_PATTERN.is(carried)) {
                    return;
                }
                inventory.setItemDirect(slot, carried);
                menu.setCarried(ItemStack.EMPTY);
            }
            case SWAP -> {
                if (carried.isEmpty() || !AEItems.PROCESSING_PATTERN.is(carried)
                        || slotStack.isEmpty()) {
                    return;
                }
                inventory.setItemDirect(slot, carried);
                menu.setCarried(slotStack.copy());
            }
            case QUICK_MOVE -> {
                if (slotStack.isEmpty()) {
                    return;
                }
                if (!player.getInventory().add(slotStack.copy())) {
                    return;
                }
                inventory.setItemDirect(slot, ItemStack.EMPTY);
            }
        }
    }

    /** 动作类型。 */
    public enum Action {
        /** 光标空，从槽位拾取到光标。 */
        PICKUP,
        /** 光标有样板，放进空槽。 */
        PLACE,
        /** 光标与槽位都有样板，交换。 */
        SWAP,
        /** Shift 点击：槽位样板进玩家背包。 */
        QUICK_MOVE
    }
}
