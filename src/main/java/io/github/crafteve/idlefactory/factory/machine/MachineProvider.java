package io.github.crafteve.idlefactory.factory.machine;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.GenericStack;

import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 机器的样板供应器数据：阻挡开关、样板槽、待发送余量。
 *
 * <p>一个逻辑机器就是一个样板容器，槽位跟着机器记录走，所以这些字段的宿主是
 * {@link Machine} 而不是运行时节点。样本槽按容量存成定长列表，空槽用
 * {@link ItemStack#EMPTY} 占位，槽位下标就是样板访问终端看到的槽位。</p>
 *
 * <p>对外只给不可变视图或副本；写入口只有本类显式的 set / add / take 方法，调用方拿不到
 * 内部可变列表。</p>
 */
public final class MachineProvider {
    /** 默认样板槽数。 */
    public static final int DEFAULT_CAPACITY = 9;
    /** 每次扩容增加的槽数。 */
    public static final int CAPACITY_STEP = 9;
    /** 样板槽上限。 */
    public static final int MAX_CAPACITY = 1024;
    /** 余量队列最多保留几条；正常一份样板最多几条输入，超了说明状态异常。 */
    public static final int MAX_PENDING = 64;

    private static final String TAG_BLOCKING = "Blocking";
    private static final String TAG_CAPACITY = "Capacity";
    private static final String TAG_PATTERNS = "Patterns";
    private static final String TAG_PENDING = "Pending";
    private static final String TAG_STACK = "Stack";
    private static final String TAG_FACE = "Face";
    /** 余量条目的面：-1 表示还没定面。 */
    private static final byte FACE_UNSET = -1;

    /** 阻挡开关。 */
    private BlockingMode blocking = BlockingMode.OFF;
    /** 样板槽容量；{@code patterns.size()} 永远等于它。 */
    private int capacity = DEFAULT_CAPACITY;
    /** 样板槽，定长、空槽为 {@link ItemStack#EMPTY}。 */
    private final List<ItemStack> patterns = new ArrayList<>();
    /** 待发送余量：上一份已经接受、但机器一时收不下的零头。 */
    private final List<Pending> pending = new ArrayList<>();

    /** 新建空供应器：默认 9 槽、不阻挡、没有余量。 */
    public MachineProvider() {
        fill(this.patterns, DEFAULT_CAPACITY);
    }

    /**
     * 一份还没送进机器的余量。
     *
     * @param stack 资源与数量；数量恒大于 0
     * @param face  上次送进去的面；{@code null} 表示面未定
     */
    public record Pending(GenericStack stack, @Nullable Direction face) {
        /**
         * 换一个面，数量不变。
         *
         * @param next 新面；{@code null} 表示面未定
         * @return 新条目
         */
        public Pending withFace(@Nullable Direction next) {
            return new Pending(this.stack, next);
        }

        /**
         * 换数量，面不变。
         *
         * @param amount 新数量
         * @return 新条目
         */
        public Pending withAmount(long amount) {
            return new Pending(new GenericStack(this.stack.what(), amount), this.face);
        }
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
        this.growIfNearlyFull();
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

    /** 待发送余量（只读副本）。 */
    public List<Pending> pending() {
        return List.copyOf(this.pending);
    }

    /** 余量总条数。 */
    public int pendingCount() {
        return this.pending.size();
    }

    /** 余量总数量。 */
    public long pendingAmount() {
        long total = 0L;
        for (Pending entry : this.pending) {
            total += entry.stack().amount();
        }
        return total;
    }

    /**
     * 追加一条余量。
     *
     * @param stack 资源与数量；数量不为正时忽略
     * @param face  塞不进去的面；{@code null} 表示面未定
     * @return 是否入队；队列已满时返回 {@code false}
     */
    public boolean addPending(GenericStack stack, @Nullable Direction face) {
        if (stack.amount() <= 0L || this.pending.size() >= MAX_PENDING) {
            return false;
        }
        this.pending.add(new Pending(stack, face));
        return true;
    }

    /**
     * 整体替换余量队列；读档与冲刷后回写用。
     *
     * @param entries 新队列；超过 {@link #MAX_PENDING} 的部分丢弃
     */
    public void setPending(List<Pending> entries) {
        this.pending.clear();
        for (Pending entry : entries) {
            if (this.pending.size() >= MAX_PENDING) {
                break;
            }
            if (entry.stack().amount() > 0L) {
                this.pending.add(entry);
            }
        }
    }

    /** 清空余量。 */
    public void clearPending() {
        this.pending.clear();
    }

    /**
     * 序列化成 NBT。
     *
     * <p>样板槽按定长列表整体落盘：槽位下标必须跨重启保持，所以空槽也写一个空复合标签。
     * 余量条目带面，面缺失表示还没定面。</p>
     *
     * @param registries 注册表访问，样板的数据组件需要它
     * @return 新标签
     */
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_BLOCKING, this.blocking.name());
        tag.putInt(TAG_CAPACITY, this.capacity);

        ListTag patternList = new ListTag();
        for (ItemStack stack : this.patterns) {
            patternList.add(stack.saveOptional(registries));
        }
        tag.put(TAG_PATTERNS, patternList);

        ListTag pendingList = new ListTag();
        for (Pending entry : this.pending) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.put(TAG_STACK, GenericStack.writeTag(registries, entry.stack()));
            entryTag.putByte(TAG_FACE, entry.face() == null
                    ? FACE_UNSET : (byte) entry.face().get3DDataValue());
            pendingList.add(entryTag);
        }
        tag.put(TAG_PENDING, pendingList);
        return tag;
    }

    /**
     * 从 NBT 读回来。
     *
     * <p>旧档没有 {@code Provider} 复合标签时按默认值读：不阻挡、9 槽、空样板、空余量，
     * 不写迁移代码。</p>
     *
     * @param tag        存档标签；可以是空标签
     * @param registries 注册表访问
     * @return 供应器数据
     */
    public static MachineProvider load(CompoundTag tag, HolderLookup.Provider registries) {
        MachineProvider provider = new MachineProvider();
        provider.blocking = BlockingMode.read(tag.getString(TAG_BLOCKING));

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
        // 容量必须大于已放数量，否则最后一个槽永远没有空位、终端再也放不进样板
        int stored = tag.getInt(TAG_CAPACITY);
        int capacity = Math.max(stored, used + 1);
        provider.resize(Math.max(DEFAULT_CAPACITY, Math.min(MAX_CAPACITY, capacity)));

        for (int i = 0; i < loaded.size() && i < provider.patterns.size(); i++) {
            ItemStack stack = loaded.get(i);
            if (!stack.isEmpty()) {
                provider.patterns.set(i, stack.copyWithCount(1));
            }
        }

        ListTag pendingList = tag.getList(TAG_PENDING, Tag.TAG_COMPOUND);
        for (int i = 0; i < pendingList.size() && provider.pending.size() < MAX_PENDING; i++) {
            CompoundTag entryTag = pendingList.getCompound(i);
            GenericStack stack = GenericStack.readTag(registries, entryTag.getCompound(TAG_STACK));
            if (stack == null || stack.amount() <= 0L) {
                continue;
            }
            byte rawFace = entryTag.getByte(TAG_FACE);
            Direction face = rawFace >= 0 && rawFace < 6
                    ? Direction.from3DDataValue(rawFace) : null;
            provider.pending.add(new Pending(stack, face));
        }
        return provider;
    }

    /**
     * 已放数量逼近容量时加一组槽。
     *
     * <p>判据是「已放 >= 容量 - 1」：留一个空位，终端永远放得下下一条，扩容本身也就永远
     * 有触发点。</p>
     */
    private void growIfNearlyFull() {
        if (this.capacity >= MAX_CAPACITY || this.patternCount() < this.capacity - 1) {
            return;
        }
        this.resize(Math.min(MAX_CAPACITY, this.capacity + CAPACITY_STEP));
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
