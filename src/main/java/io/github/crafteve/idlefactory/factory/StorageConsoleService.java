package io.github.crafteve.idlefactory.factory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import io.github.crafteve.idlefactory.factory.observation.ObservationService;
import io.github.crafteve.idlefactory.factory.probe.Health;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;
import io.github.crafteve.idlefactory.factory.storage.ContainerAnchor;
import io.github.crafteve.idlefactory.factory.storage.ContainerRef;
import io.github.crafteve.idlefactory.factory.storage.StorageAnchor;
import io.github.crafteve.idlefactory.factory.storage.StorageResolver;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 仓库摘要与存取：仓库内容的一行摘要、{@code /idf storage get|put}。
 *
 * <p>读路径只读观测快照，不逐格探世界；存取按仓库与背包的实际状态重新计算数量，
 * 客户端传来的数量一律不信。</p>
 *
 * <p>本类不持有运行时单例：档案柜、解析器与观测服务都从构造注入。</p>
 */
final class StorageConsoleService {
    /** {@code storage info} 最多列出多少种物品。 */
    private static final int INFO_LIMIT = 30;

    private final FactoryManager manager;
    @Nullable
    private final StorageResolver storageResolver;
    @Nullable
    private final ObservationService observations;

    /**
     * @param manager         工厂档案柜
     * @param storageResolver 仓库解析器；服务端未就绪时为 {@code null}
     * @param observations    观测服务；服务端未就绪时为 {@code null}
     */
    StorageConsoleService(FactoryManager manager, @Nullable StorageResolver storageResolver,
            @Nullable ObservationService observations) {
        this.manager = manager;
        this.storageResolver = storageResolver;
        this.observations = observations;
    }

    // ---- 12. 仓库判定与展示辅助（containerState） ----
    // 负责把观测结论翻译成容器状态；只被仓库摘要使用。
    // ------------------------------------------------------------------

    /**
     * 容器状态的一句话。
     *
     * @param health 观测结论
     * @return 展示文本
     */
    private static String containerState(Health health) {
        return switch (health) {
            case PRESENT -> "可用";
            case UNLOADED -> "未加载";
            case MISSING, NO_DIMENSION -> "已缺失";
            case UNKNOWN -> "未探测";
        };
    }

    // ---- 13. 仓库摘要与存取（storageInfo / storageGet / storagePut） ----
    // 负责仓库内容摘要、取出与存入；由 /idf storage 的 info、get 与 put 调用。
    // ------------------------------------------------------------------

    /**
     * 仓库摘要：类型、容器列表、物品。
     *
     * @param player 发起请求的玩家
     * @return 命令结果
     */
    int storageInfo(ServerPlayer player) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        factory.refreshStorage(this.storageResolver);
        StorageAnchor anchor = factory.config().storageAnchor();
        if (anchor == null) {
            FactoryMessages.err(player, "还没有绑定仓库");
            FactoryMessages.info(player, "容器：准星对准箱子执行 /idf storage bind；ME：用存储链接杖右键 ME 网络方块");
            return 0;
        }
        FactoryMessages.info(player, "仓库：" + anchor.describe() + " · "
                + (factory.live().storageOnline() ? "在线" : "离线"));

        ObservationService observations = this.observations;
        if (anchor instanceof ContainerAnchor containerAnchor) {
            int present = 0;
            int unloaded = 0;
            int missing = 0;
            for (ContainerRef ref : containerAnchor.containers()) {
                Health health = observations == null ? Health.UNKNOWN : observations.observe(ref.blockKey()).health();
                switch (health) {
                    case PRESENT -> present++;
                    case UNLOADED -> unloaded++;
                    case MISSING, NO_DIMENSION -> missing++;
                    default -> {
                        // UNKNOWN 不计
                    }
                }
                Component row = Component.empty()
                        .append(FactoryMessages.text("  ", FactoryMessages.INFO))
                        .append(FactoryMessages.hover(ref.shortId(),
                                ref.dimension().location() + " " + ref.pos().toShortString()))
                        .append(FactoryMessages.text(" " + ref.dimension().location() + " "
                                + ref.pos().toShortString()
                                + " · " + containerState(health)
                                + " · 优先级 " + ref.priority(), FactoryMessages.INFO));
                FactoryMessages.send(player, row);
            }
            FactoryMessages.info(player, "容器 " + containerAnchor.containers().size() + " 个（可用 " + present
                    + (unloaded > 0 ? " · 未加载 " + unloaded : "")
                    + (missing > 0 ? " · 已缺失 " + missing : "") + "）"
                    + (missing > 0 ? " · /idf storage prune 清理已缺失" : ""));
        }

        if (!factory.live().storageOnline()) {
            FactoryMessages.warn(player, "仓库当前离线，机器不会搬运");
            return 1;
        }

        MEStorage storage = factory.live().storage();
        Map<AEKey, Long> snapshot = AeKeySupport.snapshot(storage);
        if (snapshot.isEmpty()) {
            FactoryMessages.info(player, "仓库是空的");
            return 1;
        }
        List<Map.Entry<AEKey, Long>> entries = new ArrayList<>(snapshot.entrySet());
        entries.sort(Map.Entry.<AEKey, Long>comparingByValue().reversed());
        int shown = Math.min(entries.size(), INFO_LIMIT);
        for (int i = 0; i < shown; i++) {
            Map.Entry<AEKey, Long> entry = entries.get(i);
            FactoryMessages.msg(player, "  " + describeResource(entry.getKey()) + " x " + entry.getValue());
        }
        if (entries.size() > shown) {
            FactoryMessages.msg(player, "  ... 还有 " + (entries.size() - shown) + " 种");
        }
        FactoryMessages.info(player, "共 " + entries.size() + " 种资源");
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
    int storageGet(ServerPlayer player, ItemStack sample, int count) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        MEStorage storage = requireOnlineStorage(player, factory);
        if (storage == null) {
            return 0;
        }
        long extracted = AeKeySupport.extract(storage, AeKeySupport.item(sample), count, false);
        if (extracted <= 0) {
            FactoryMessages.err(player, "仓库里没有 " + itemId(sample));
            return 0;
        }
        ItemStack give = sample.copy();
        give.setCount((int) extracted);
        if (!player.getInventory().add(give)) {
            // 背包放不下就掉地上，和 /give 一致
            player.drop(give, false);
        }
        FactoryMessages.ok(player, "取出 " + extracted + " 个 " + itemId(sample));
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
    int storagePut(ServerPlayer player, ItemStack sample, int count) {
        Factory factory = owned(player);
        if (factory == null) {
            return 0;
        }
        MEStorage storage = requireOnlineStorage(player, factory);
        if (storage == null) {
            return 0;
        }
        ItemStack template = sample.copy();
        template.setCount(1);

        if (player.isCreative()) {
            long inserted = AeKeySupport.insert(storage, AeKeySupport.item(template), count, false);
            if (inserted <= 0) {
                FactoryMessages.err(player, "仓库收不下 " + itemId(sample));
                return 0;
            }
            FactoryMessages.ok(player, "存入 " + inserted + " 个 " + itemId(sample) + "（创造模式）");
            return 1;
        }

        int available = countInInventory(player, template);
        if (available <= 0) {
            FactoryMessages.err(player, "背包里没有 " + itemId(sample));
            return 0;
        }
        int want = Math.min(count, available);
        long accepted = AeKeySupport.insert(storage, AeKeySupport.item(template), want, true);
        if (accepted <= 0) {
            FactoryMessages.err(player, "仓库收不下 " + itemId(sample));
            return 0;
        }
        long inserted = AeKeySupport.insert(storage, AeKeySupport.item(template), accepted, false);
        int removed = removeFromInventory(player, template, (int) inserted);
        FactoryMessages.ok(player, "存入 " + removed + " 个 " + itemId(sample));
        return removed > 0 ? 1 : 0;
    }

    /**
     * 取一个当前确实可用的仓库；没绑地址、没电、环离线都给出明确提示。
     *
     * <p>指令是即时的，这里先刷新一次，不等 {@code FactoryRuntime} 的定时刷新。</p>
     *
     * @param player  玩家
     * @param factory 工厂
     * @return 可用仓库；不可用时为 {@code null}
     */
    @Nullable
    private MEStorage requireOnlineStorage(ServerPlayer player, Factory factory) {
        factory.refreshStorage(this.storageResolver);
        if (factory.config().storageAnchor() == null) {
            FactoryMessages.err(player, "你还没有绑定仓库：用存储链接杖右键 ME 网络方块，或对准箱子执行 /idf storage bind");
            return null;
        }
        if (!factory.live().storageOnline()) {
            FactoryMessages.err(player, "仓库未连接（网络没电、频道不足，或 ME 接入点区块未加载）");
            return null;
        }
        return factory.live().storage();
    }

    /**
     * 玩家准星指向的方块坐标。
     *
     * @param player 玩家
     * @return 方块坐标；没对准方块时为 {@code null}
     */

    /** 背包里与样本同物品的总数。 */
    private int countInInventory(ServerPlayer player, ItemStack template) {
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
    private int removeFromInventory(ServerPlayer player, ItemStack template, int amount) {
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

    /** 仓库条目的一行显示，按类型补单位。 */
    private static String describeResource(AEKey key) {
        return switch (AeKeySupport.kind(key)) {
            case FLUID -> key.getDisplayName().getString() + "（mB）";
            case ENERGY -> key.getDisplayName().getString() + "（FE）";
            default -> key.getDisplayName().getString();
        };
    }

    /**
     * 取玩家自己的工厂；没有就提示。
     *
     * @param player 玩家
     * @return 工厂；玩家没有工厂时为 {@code null}
     */
    @Nullable
    private Factory owned(ServerPlayer player) {
        Factory factory = FactoryRefs.owned(this.manager, player);
        if (factory == null) {
            FactoryMessages.err(player, "你还没有工厂，先执行 /idlefactory create");
        }
        return factory;
    }
}
