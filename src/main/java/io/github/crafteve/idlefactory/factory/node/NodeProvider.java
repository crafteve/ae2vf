package io.github.crafteve.idlefactory.factory.node;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.factory.io.BlockingMode;
import io.github.crafteve.idlefactory.factory.io.DispatchMode;
import io.github.crafteve.idlefactory.factory.io.DispatchOrder;
import io.github.crafteve.idlefactory.factory.io.DispatchQueue;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 节点的样板供应器数据：阻挡、倍率、派单、样板槽与待投递队列。
 *
 * <p>provider 归节点：一个节点一个样板容器，槽位与待投递队列跟着节点记录走，堆叠机器共用。
 * 样板槽按容量存成定长列表，空槽用 {@link ItemStack#EMPTY} 占位，槽位下标就是样板访问终端
 * 看到的槽位。</p>
 *
 * <p>队列是 {@link DispatchQueue}：内存写入口只有它，本类只负责把队列序列化成 NBT 再读回来。
 * 旧版 {@code Pending} 格式（一单绑一台机器、只投 0/1 份）不再兼容，读到即丢弃。</p>
 */
public final class NodeProvider {
    /** 默认样板槽数。 */
    public static final int DEFAULT_CAPACITY = 9;
    /** 每次扩容增加的槽数。 */
    public static final int CAPACITY_STEP = 9;
    /** 样板槽硬上限；config 只能调低。 */
    public static final int MAX_CAPACITY = 1024;

    private static final String TAG_BLOCKING = "Blocking";
    private static final String TAG_CAPACITY = "Capacity";
    private static final String TAG_PATTERNS = "Patterns";
    private static final String TAG_ORDERS = "Orders";
    private static final String TAG_ORDER_ID = "OrderId";
    private static final String TAG_LINES = "Lines";
    private static final String TAG_STACK = "Stack";
    private static final String TAG_PER_PORTION = "PerPortion";
    private static final String TAG_PORTIONS = "Portions";
    private static final String TAG_DONE_PORTIONS = "DonePortions";
    private static final String TAG_OPEN_BATCHES = "OpenBatches";
    private static final String TAG_BATCH_MACHINE = "MachineId";
    private static final String TAG_BATCH_MISSING = "Missing";
    private static final String TAG_NEXT_MACHINE = "NextMachine";
    private static final String TAG_RETRY_AT = "RetryAt";
    private static final String TAG_ENQUEUED_AT = "EnqueuedAt";
    private static final String TAG_ORPHANED_AT = "OrphanedAt";
    private static final String TAG_MODE = "Mode";
    private static final String TAG_MODE_PINNED = "ModePinned";
    private static final String TAG_MULTIPLIER_MODE = "MultiplierMode";
    private static final String TAG_MULTIPLIER_FACTOR = "MultiplierFactor";
    private static final String TAG_DISPATCH = "Dispatch";

    /** 阻挡开关。 */
    private BlockingMode blocking = BlockingMode.OFF;
    /** 主动 / 被动模式。 */
    private ProviderMode mode = ProviderMode.PASSIVE;
    /** 玩家是否手动切过模式；手动切过之后不再按样板自动切换。 */
    private boolean modePinned;
    /** 倍率：关 / 固定 / 智能。 */
    private Multiplier multiplier = Multiplier.off();
    /** 派单模式：顺序 / 轮询；新节点默认轮询。 */
    private DispatchMode dispatch = DispatchMode.ROUND_ROBIN;
    /** 样板槽容量；{@code patterns.size()} 永远等于它。 */
    private int capacity = DEFAULT_CAPACITY;
    /** 样板槽，定长、空槽为 {@link ItemStack#EMPTY}。 */
    private final List<ItemStack> patterns = new ArrayList<>();
    /** 待投递队列：内存与存档的唯一写入口是队列自己。 */
    private final DispatchQueue pending = new DispatchQueue();

    /** 新建空供应器：默认 9 槽、不阻挡、队列为空。 */
    public NodeProvider() {
        fill(this.patterns, DEFAULT_CAPACITY);
    }

    /** 阻挡开关。 */
    public BlockingMode blocking() {
        return this.blocking;
    }

    /**
     * 改阻挡开关。
     *
     * @param mode 新开关；{@code null} 按不阻挡处理
     */
    public void setBlocking(@Nullable BlockingMode mode) {
        this.blocking = mode == null ? BlockingMode.OFF : mode;
    }

    /** 主动 / 被动模式。 */
    public ProviderMode mode() {
        return this.mode;
    }

    /**
     * 写入第一张样板时自动转主动；玩家手动切过模式后不再自动改。
     *
     * @return 模式是否因这次写入变化
     */
    public boolean autoActivateOnPattern() {
        if (this.modePinned || this.mode == ProviderMode.ACTIVE) {
            return false;
        }
        this.mode = ProviderMode.ACTIVE;
        return true;
    }

    /** 倍率：关 / 固定 / 智能。 */
    public Multiplier multiplier() {
        return this.multiplier;
    }

    /**
     * 改倍率。
     *
     * @param next 新倍率；{@code null} 按不倍率处理
     */
    public void setMultiplier(@Nullable Multiplier next) {
        this.multiplier = next == null ? Multiplier.off() : next;
    }

    /** 派单模式：顺序 / 轮询。 */
    public DispatchMode dispatch() {
        return this.dispatch;
    }

    /**
     * 改派单模式。
     *
     * @param next 新模式；{@code null} 按轮询处理
     */
    public void setDispatch(@Nullable DispatchMode next) {
        this.dispatch = next == null ? DispatchMode.ROUND_ROBIN : next;
    }

    /** 样板槽容量。 */
    public int capacity() {
        return this.capacity;
    }

    /** 当前已放样板数（非空槽数）。 */
    public int patternCount() {
        int count = 0;
        for (ItemStack stack : this.patterns) {
            if (!stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 读一个槽位的样板；只读，调用方不得修改返回的栈。
     *
     * @param slot 槽位
     * @return 样板；空槽或越界时返回 {@link ItemStack#EMPTY}
     */
    public ItemStack pattern(int slot) {
        if (slot < 0 || slot >= this.patterns.size()) {
            return ItemStack.EMPTY;
        }
        return this.patterns.get(slot);
    }

    /**
     * 写一个槽位的样板，并按「放到容量 - 1 时扩容」的规则顺手加槽。
     *
     * <p>扩容只加一整组 {@link #CAPACITY_STEP}，封顶 {@link #MAX_CAPACITY}；调用方拿写入前后
     * 的 {@link #capacity()} 判断要不要刷新终端分组。</p>
     *
     * @param slot  槽位；越界时忽略
     * @param stack 样板；空栈表示清空该槽
     */
    public void setPattern(int slot, ItemStack stack) {
        if (slot < 0 || slot >= this.patterns.size()) {
            return;
        }
        this.patterns.set(slot, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
        this.resizeByUsage();
    }

    /**
     * 取走全部样板并清空槽位；删除机器时把样板退回玩家。
     *
     * @return 样板副本，按槽位顺序
     */
    public List<ItemStack> takePatterns() {
        List<ItemStack> taken = new ArrayList<>();
        for (int i = 0; i < this.patterns.size(); i++) {
            ItemStack stack = this.patterns.get(i);
            if (!stack.isEmpty()) {
                taken.add(stack.copy());
                this.patterns.set(i, ItemStack.EMPTY);
            }
        }
        return taken;
    }

    /** 待投递队列（内存与存档的唯一写入口）。 */
    public DispatchQueue pending() {
        return this.pending;
    }

    /**
     * 序列化成 NBT。
     *
     * <p>样板槽按定长列表整体落盘：槽位下标必须跨重启保持，所以空槽也写一个空复合标签。
     * 订单按 §6 的新字段整体落盘；旧 {@code Pending} 键不再读取。</p>
     *
     * @param registries 注册表访问，样板与资源 key 的数据组件需要它
     * @return 新标签
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_BLOCKING, this.blocking.name());
        tag.putString(TAG_MODE, this.mode.name());
        tag.putBoolean(TAG_MODE_PINNED, this.modePinned);
        // 倍率拆成两个键，和计划里的存档字段一致；倍数本身由 Multiplier 负责夹在合法区间内。
        tag.putString(TAG_MULTIPLIER_MODE, this.multiplier.mode().name());
        tag.putInt(TAG_MULTIPLIER_FACTOR, this.multiplier.factor());
        tag.putString(TAG_DISPATCH, this.dispatch.name());
        tag.putInt(TAG_CAPACITY, this.capacity);

        ListTag patternList = new ListTag();
        for (ItemStack stack : this.patterns) {
            patternList.add(stack.saveOptional(registries));
        }
        tag.put(TAG_PATTERNS, patternList);

        ListTag orderList = new ListTag();
        for (DispatchOrder order : this.pending.all()) {
            orderList.add(saveOrder(order, registries));
        }
        tag.put(TAG_ORDERS, orderList);
        return tag;
    }

    /**
     * 一单的存档：订单 id、每份用量、份数、进度、半份、退避与时刻。
     */
    private static CompoundTag saveOrder(DispatchOrder order, HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ORDER_ID, order.id());
        ListTag lineList = new ListTag();
        for (DispatchOrder.Line line : order.perPortion()) {
            CompoundTag lineTag = new CompoundTag();
            lineTag.put(TAG_STACK, GenericStack.writeTag(registries,
                    new GenericStack(line.key(), line.amount())));
            lineTag.putLong(TAG_PER_PORTION, line.amount());
            lineList.add(lineTag);
        }
        tag.put(TAG_LINES, lineList);
        tag.putLong(TAG_PORTIONS, order.portions());
        tag.putLong(TAG_DONE_PORTIONS, order.donePortions());
        ListTag batchList = new ListTag();
        for (Map.Entry<UUID, DispatchOrder.OpenBatch> entry : order.openBatches().entrySet()) {
            CompoundTag batchTag = new CompoundTag();
            batchTag.putUUID(TAG_BATCH_MACHINE, entry.getKey());
            long[] missing = new long[entry.getValue().size()];
            for (int index = 0; index < missing.length; index++) {
                missing[index] = entry.getValue().missing(index);
            }
            batchTag.putLongArray(TAG_BATCH_MISSING, missing);
            batchList.add(batchTag);
        }
        tag.put(TAG_OPEN_BATCHES, batchList);
        if (order.nextMachineId() != null) {
            tag.putUUID(TAG_NEXT_MACHINE, order.nextMachineId());
        }
        tag.putLong(TAG_RETRY_AT, order.retryAt());
        tag.putLong(TAG_ENQUEUED_AT, order.enqueuedAt());
        if (order.orphanedAt() >= 0L) {
            tag.putLong(TAG_ORPHANED_AT, order.orphanedAt());
        }
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * <p>旧档没有 {@code Provider} 复合标签时按默认值读：不阻挡、不倍率、轮询派单、9 槽、
     * 空样板、空队列，不写迁移代码。旧 {@code Pending} 队列格式直接丢弃（D26 允许重建）。</p>
     *
     * @param tag        存档标签；可以是空标签
     * @param registries 注册表访问
     * @return 供应器数据
     */
    public static NodeProvider load(CompoundTag tag, HolderLookup.Provider registries) {
        NodeProvider provider = new NodeProvider();
        provider.blocking = BlockingMode.read(tag.getString(TAG_BLOCKING));
        provider.mode = ProviderMode.read(tag.getString(TAG_MODE));
        provider.modePinned = tag.getBoolean(TAG_MODE_PINNED);
        provider.multiplier = new Multiplier(MultiplierMode.read(tag.getString(TAG_MULTIPLIER_MODE)),
                tag.contains(TAG_MULTIPLIER_FACTOR)
                        ? tag.getInt(TAG_MULTIPLIER_FACTOR) : Multiplier.MIN_FACTOR);
        provider.dispatch = DispatchMode.read(tag.getString(TAG_DISPATCH));

        ListTag patternList = tag.getList(TAG_PATTERNS, Tag.TAG_COMPOUND);
        List<ItemStack> loaded = new ArrayList<>(patternList.size());
        for (int i = 0; i < patternList.size(); i++) {
            loaded.add(ItemStack.parseOptional(registries, patternList.getCompound(i)));
        }

        int used = 0;
        for (ItemStack stack : loaded) {
            if (!stack.isEmpty()) {
                used++;
            }
        }
        // 容量必须大于已放数量，否则最后一个槽永远没有空位、终端再也放不进样板。
        int stored = tag.getInt(TAG_CAPACITY);
        int capacity = Math.max(stored, used + 1);
        provider.resize(Math.max(DEFAULT_CAPACITY, Math.min(MAX_CAPACITY, capacity)));

        for (int i = 0; i < loaded.size() && i < provider.patterns.size(); i++) {
            ItemStack stack = loaded.get(i);
            if (!stack.isEmpty()) {
                provider.patterns.set(i, stack.copyWithCount(1));
            }
        }

        ListTag orderList = tag.getList(TAG_ORDERS, Tag.TAG_COMPOUND);
        List<DispatchOrder> orders = new ArrayList<>();
        for (int i = 0; i < orderList.size() && orders.size() < DispatchQueue.MAX_PENDING; i++) {
            DispatchOrder order = loadOrder(orderList.getCompound(i), registries);
            if (order != null) {
                orders.add(order);
            }
        }
        provider.pending.replaceAll(orders);
        return provider;
    }

    /**
     * 读一单；关键字段缺失或非法时整单丢弃，绝不猜数量。
     */
    @Nullable
    private static DispatchOrder loadOrder(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.hasUUID(TAG_ORDER_ID) || !tag.contains(TAG_PORTIONS, Tag.TAG_LONG)) {
            return null;
        }
        ListTag lineList = tag.getList(TAG_LINES, Tag.TAG_COMPOUND);
        List<DispatchOrder.Line> lines = new ArrayList<>(lineList.size());
        for (int index = 0; index < lineList.size(); index++) {
            CompoundTag lineTag = lineList.getCompound(index);
            GenericStack stack = GenericStack.readTag(registries, lineTag.getCompound(TAG_STACK));
            if (stack == null || stack.amount() <= 0L) {
                continue;
            }
            long perPortion = lineTag.contains(TAG_PER_PORTION)
                    ? lineTag.getLong(TAG_PER_PORTION) : stack.amount();
            if (perPortion <= 0L) {
                continue;
            }
            lines.add(new DispatchOrder.Line(stack.what(), perPortion));
        }
        if (lines.isEmpty()) {
            return null;
        }
        long portions = tag.getLong(TAG_PORTIONS);
        long done = tag.getLong(TAG_DONE_PORTIONS);
        if (portions <= 0L || done < 0L || done > portions) {
            return null;
        }
        Map<UUID, DispatchOrder.OpenBatch> batches = new LinkedHashMap<>();
        ListTag batchList = tag.getList(TAG_OPEN_BATCHES, Tag.TAG_COMPOUND);
        for (int index = 0; index < batchList.size(); index++) {
            CompoundTag batchTag = batchList.getCompound(index);
            if (!batchTag.hasUUID(TAG_BATCH_MACHINE)) {
                continue;
            }
            long[] missing = batchTag.getLongArray(TAG_BATCH_MISSING);
            if (missing.length != lines.size()) {
                // 行数对不上：这一条半份已经没法精确补齐，丢掉它比按错位补料安全。
                IdleFactory.LOGGER.warn("Idle Factory: 订单 {} 的半份行数不匹配，已丢弃该半份",
                        tag.getUUID(TAG_ORDER_ID));
                continue;
            }
            batches.put(batchTag.getUUID(TAG_BATCH_MACHINE), new DispatchOrder.OpenBatch(missing));
        }
        UUID nextMachine = tag.hasUUID(TAG_NEXT_MACHINE) ? tag.getUUID(TAG_NEXT_MACHINE) : null;
        long retryAt = tag.getLong(TAG_RETRY_AT);
        long enqueuedAt = tag.contains(TAG_ENQUEUED_AT) ? tag.getLong(TAG_ENQUEUED_AT) : -1L;
        long orphanedAt = tag.contains(TAG_ORPHANED_AT) ? tag.getLong(TAG_ORPHANED_AT) : -1L;
        return new DispatchOrder(tag.getUUID(TAG_ORDER_ID), lines, portions, done, batches,
                nextMachine, retryAt, enqueuedAt, orphanedAt);
    }

    /**
     * 按用量调整容量：全满加一组，空出超过一组才减一组。
     *
     * <p>「刚好空一组」是刚扩容完的正常状态，不能缩，否则会扩→缩→扩死循环。</p>
     *
     * <p>减容量之前先 {@link #compact()} 把样板压到最前面：玩家把样板放在第二排、第一排
     * 空着时，缩完容量样板会原封不动挪到第一排，一个都不会被裁掉。缩后的容量取
     * 「样板数向上取整到 {@link #CAPACITY_STEP} 的倍数」，且不低于 {@link #DEFAULT_CAPACITY}。</p>
     */
    private void resizeByUsage() {
        int used = this.patternCount();
        int free = this.capacity - used;
        if (free == 0 && this.capacity < MAX_CAPACITY) {
            this.resize(Math.min(MAX_CAPACITY, this.capacity + CAPACITY_STEP));
            return;
        }
        if (free > CAPACITY_STEP && this.capacity > DEFAULT_CAPACITY) {
            // 空出超过一组才缩：刚好空 9 是「刚扩容完」的正常状态，再缩会扩缩死循环。
            this.compact();
            int groups = Math.max(1, (used + CAPACITY_STEP - 1) / CAPACITY_STEP);
            int next = Math.max(DEFAULT_CAPACITY, groups * CAPACITY_STEP);
            this.resize(Math.min(this.capacity - CAPACITY_STEP, next));
        }
    }

    /**
     * 把非空样板按原顺序压到最前面的槽位，消除中间空洞。
     *
     * <p>缩容前调用：先压实再截尾部空槽，样板一个都不会丢。</p>
     */
    private void compact() {
        List<ItemStack> kept = new ArrayList<>();
        for (ItemStack stack : this.patterns) {
            if (!stack.isEmpty()) {
                kept.add(stack);
            }
        }
        for (int i = 0; i < this.patterns.size(); i++) {
            this.patterns.set(i, i < kept.size() ? kept.get(i) : ItemStack.EMPTY);
        }
    }

    /**
     * 改容量并同步槽位列表长度。
     *
     * @param next 新容量，调用方负责夹在合法区间内
     */
    private void resize(int next) {
        this.capacity = next;
        while (this.patterns.size() < next) {
            this.patterns.add(ItemStack.EMPTY);
        }
        while (this.patterns.size() > next) {
            this.patterns.remove(this.patterns.size() - 1);
        }
    }

    /**
     * 用空栈补齐定长列表。
     *
     * @param target 目标列表
     * @param size   目标长度
     */
    private static void fill(List<ItemStack> target, int size) {
        for (int i = 0; i < size; i++) {
            target.add(ItemStack.EMPTY);
        }
    }
}
