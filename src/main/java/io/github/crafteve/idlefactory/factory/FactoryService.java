package io.github.crafteve.idlefactory.factory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.factory.storage.Storage;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.integration.ae2.Ae2Binder;

import appeng.api.ids.AEComponents;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 工厂行政窗口：创建 / 删除 / 绑定仓库 / 仓库调试。
 *
 * <p>终端、指令、以后的 WebUI 都走这里。它负责权限（第一版只看 owner）和档案写入，
 * 不负责界面，也不负责搬运。</p>
 */
public final class FactoryService {
    /** {@code storage info} 最多列出多少种物品。 */
    private static final int INFO_LIMIT = 30;

    private FactoryService() {
        // 纯静态工具类
    }

    // ------------------------------------------------------------------
    // 工厂生命周期
    // ------------------------------------------------------------------

    /**
     * 给玩家创建一个工厂。一人一个，已有工厂时不动。
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 未创建）
     */
    public static int create(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            msg(player, "Idle Factory 尚未启动（服务端未就绪）");
            return 0;
        }
        Optional<Factory> existing = runtime.manager().byOwner(player.getUUID());
        if (existing.isPresent()) {
            msg(player, "你已经有工厂了：" + existing.get().id());
            return 0;
        }

        UUID id = nextId(runtime, player.getUUID());
        Factory factory = new Factory(id, player.getUUID());
        if (!runtime.manager().add(factory)) {
            msg(player, "工厂创建失败：id 或拥有者冲突");
            return 0;
        }
        runtime.markDirty();
        msg(player, "工厂已创建：" + id);
        return 1;
    }

    /**
     * 删除玩家自己的工厂。机器方块本身不动，只是不再属于任何工厂。
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 未删除）
     */
    public static int delete(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            msg(player, "Idle Factory 尚未启动（服务端未就绪）");
            return 0;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            msg(player, "你还没有工厂，先执行 /idlefactory create");
            return 0;
        }
        runtime.manager().remove(factory.get().id());
        runtime.markDirty();
        msg(player, "工厂已删除：" + factory.get().id());
        return 1;
    }

    /**
     * 打印玩家自己的工厂摘要。
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 有工厂，{@code 0} 没有）
     */
    public static int info(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        msg(player, FactorySnapshotBuilder.describe(factory));
        return 1;
    }

    // ------------------------------------------------------------------
    // 绑定仓库
    // ------------------------------------------------------------------

    /**
     * 把主手上的量子纠缠奇点绑到玩家自己的工厂。
     *
     * <p>成功后奇点消失。这块是工厂与 AE2 的组装点：{@link Ae2Binder} 负责「频率 → 量子环」，
     * 这里只负责「删物品、写档案、发提示」。</p>
     *
     * @param player 发起请求的玩家
     * @return 命令结果（{@code 1} 成功，{@code 0} 失败）
     */
    public static int connect(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        ItemStack held = player.getMainHandItem();
        if (!Ae2Binder.isSingularity(held)) {
            msg(player, "主手请拿量子纠缠奇点");
            return 0;
        }
        long frequency = held.getOrDefault(AEComponents.ENTANGLED_SINGULARITY_ID, 0L);
        if (frequency == 0L) {
            msg(player, "奇点没有有效频率");
            return 0;
        }
        StorageAnchor anchor = Ae2Binder.anchor(player.serverLevel(), frequency);
        if (anchor == null) {
            msg(player, "找不到对应的量子环（环的区块加载了吗？）");
            return 0;
        }
        factory.bindAnchor(anchor);
        held.shrink(1);
        player.getInventory().setChanged();
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime != null) {
            runtime.markDirty();
        }
        msg(player, "已绑定仓库：freq=" + frequency + " @ " + anchor.dimension().location()
                + " " + anchor.ringPos().toShortString());
        return 1;
    }

    // ------------------------------------------------------------------
    // 仓库调试
    // ------------------------------------------------------------------

    /**
     * 列出工厂仓库里的物品和数量。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    public static int storageInfo(ServerPlayer player) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        Map<String, Long> snapshot = factory.storage().snapshot();
        if (snapshot.isEmpty()) {
            msg(player, "仓库是空的（或仓库未连接）");
            return 1;
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(snapshot.entrySet());
        entries.sort(Map.Entry.<String, Long>comparingByValue().reversed());
        int shown = Math.min(entries.size(), INFO_LIMIT);
        for (int i = 0; i < shown; i++) {
            Map.Entry<String, Long> entry = entries.get(i);
            msg(player, "  " + entry.getKey() + " x " + entry.getValue());
        }
        if (entries.size() > shown) {
            msg(player, "  ... 还有 " + (entries.size() - shown) + " 种");
        }
        msg(player, "共 " + entries.size() + " 种物品");
        return 1;
    }

    /**
     * 从工厂仓库取物品给玩家，类似 {@code /give}。
     *
     * @param player 目标玩家
     * @param sample 物品样本（数量会被忽略）
     * @param count  想取几个
     * @return 命令结果（{@code 1} 取到，{@code 0} 没取到）
     */
    public static int storageGet(ServerPlayer player, ItemStack sample, int count) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        long extracted = factory.storage().extract(sample, count, false);
        if (extracted <= 0) {
            msg(player, "仓库里没有 " + itemId(sample));
            return 0;
        }
        ItemStack give = sample.copy();
        give.setCount((int) extracted);
        if (!player.getInventory().add(give)) {
            // 背包放不下就掉地上，和 /give 一致
            player.drop(give, false);
        }
        msg(player, "取出 " + extracted + " 个 " + itemId(sample));
        return 1;
    }

    /**
     * 把玩家背包里的物品放进工厂仓库。
     *
     * <p>生存模式先统计背包、再按仓库点头量扣背包；创造模式不统计背包（默认背包无限），
     * 只按仓库能收多少来放。</p>
     *
     * @param player 目标玩家
     * @param sample 物品样本（数量会被忽略）
     * @param count  想放几个
     * @return 命令结果（{@code 1} 存了，{@code 0} 没存）
     */
    public static int storagePut(ServerPlayer player, ItemStack sample, int count) {
        Factory factory = ownedFactory(player);
        if (factory == null) {
            return 0;
        }
        Storage storage = factory.storage();
        ItemStack template = sample.copy();
        template.setCount(1);

        if (player.isCreative()) {
            long inserted = storage.insert(template, count, false);
            if (inserted <= 0) {
                msg(player, "仓库收不下 " + itemId(sample));
                return 0;
            }
            msg(player, "存入 " + inserted + " 个 " + itemId(sample) + "（创造模式）");
            return 1;
        }

        int available = countInInventory(player, template);
        if (available <= 0) {
            msg(player, "背包里没有 " + itemId(sample));
            return 0;
        }
        int want = Math.min(count, available);
        long accepted = storage.insert(template, want, true);
        if (accepted <= 0) {
            msg(player, "仓库收不下 " + itemId(sample));
            return 0;
        }
        long inserted = storage.insert(template, accepted, false);
        int removed = removeFromInventory(player, template, (int) inserted);
        msg(player, "存入 " + removed + " 个 " + itemId(sample));
        return removed > 0 ? 1 : 0;
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /**
     * 取玩家自己的工厂；没有就提示。
     *
     * @param player 玩家
     * @return 工厂；运行时未就绪或玩家没有工厂时为 {@code null}
     */
    @Nullable
    private static Factory ownedFactory(ServerPlayer player) {
        FactoryRuntime runtime = FactoryRuntime.get();
        if (runtime == null) {
            msg(player, "Idle Factory 尚未启动（服务端未就绪）");
            return null;
        }
        Optional<Factory> factory = runtime.manager().byOwner(player.getUUID());
        if (factory.isEmpty()) {
            msg(player, "你还没有工厂，先执行 /idlefactory create");
            return null;
        }
        return factory.get();
    }

    /**
     * 生成一个工厂 id。
     *
     * <p>用「拥有者 + 存档自增序号」做确定性 UUID：同一玩家第 N 个工厂的 id 永远一样，
     * 序号单调递增保证不冲突；外面再套一层查重，防御存档被手改过。</p>
     *
     * @param runtime 运行时
     * @param owner   拥有者 uuid
     * @return 可用的工厂 id
     */
    private static UUID nextId(FactoryRuntime runtime, UUID owner) {
        UUID id;
        do {
            long seq = runtime.takeNextSeq();
            id = UUID.nameUUIDFromBytes((owner + ":" + seq).getBytes(StandardCharsets.UTF_8));
        } while (runtime.manager().byId(id).isPresent());
        return id;
    }

    /** 背包里与样本同物品的总数。 */
    private static int countInInventory(ServerPlayer player, ItemStack template) {
        int total = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, template)) {
                total += slot.getCount();
            }
        }
        return total;
    }

    /** 从背包扣掉指定数量，返回实际扣掉的数量。 */
    private static int removeFromInventory(ServerPlayer player, ItemStack template, int amount) {
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, template)) {
                continue;
            }
            int take = Math.min(slot.getCount(), remaining);
            slot.shrink(take);
            remaining -= take;
        }
        player.getInventory().setChanged();
        return amount - remaining;
    }

    /** 物品注册名。 */
    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** 给玩家发一条系统消息。 */
    private static void msg(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
    }
}
