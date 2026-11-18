package io.github.crafteve.idlefactory.client.wand;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.wand.screen.WandDraftScreen;
import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.command.WandDraft;
import io.github.crafteve.idlefactory.factory.wand.WandFailure;
import io.github.crafteve.idlefactory.factory.wand.WandIssue;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * 机器绑定杖的客户端草稿状态机：IDLE / EDITING / SUBMITTING。
 *
 * <p>草稿只活在这份静态缓存里，不写物品组件、不跨登录；草稿的 authority 在客户端，但服务端
 * 提交时会全量重验，所以这里的所有结论都只是「先给玩家即时反馈」。每次结构变更取
 * {@link WandCoordinator#nextSeq()} 当版本号，提交与回执靠它配对。</p>
 *
 * <p>生命周期：登录 / 登出 / 换维度 / 换手 / 把杖放进容器 / 打开别的界面都丢草稿回 IDLE；
 * 只有草稿面板本身打开时不丢。</p>
 */
public final class WandSession {
    /** 绑定杖状态；没有 mode 字段。 */
    public enum State {
        /** 手持杖、草稿为空。 */
        IDLE,
        /** 草稿开放；{@code nodeId == null} 是新建，非空是编辑已有节点。 */
        EDITING,
        /** 等提交回执；所有手势忽略。 */
        SUBMITTING
    }

    private static State state = State.IDLE;
    private static WandDraft draft = WandDraft.empty(0L);
    private static List<WandIssue> issues = List.of();
    private static Set<BlockKey> conflictBlocks = Set.of();
    private static Set<BlockKey> removedBlocks = Set.of();
    /** 草稿所属玩家；换人就清空。 */
    @Nullable
    private static UUID owner;
    /** 草稿所属主手槽位；换手就清空。 */
    private static int handSlot = -1;
    /** 草稿所属维度；换维度就清空。 */
    @Nullable
    private static ResourceKey<Level> dimension;

    private WandSession() {
        // 纯静态状态机
    }

    /** 当前状态。 */
    public static State state() {
        return state;
    }

    /** 当前草稿。 */
    public static WandDraft draft() {
        return draft;
    }

    /** 最近一次客户端预检的问题，供面板与 HUD 使用。 */
    public static List<WandIssue> issues() {
        return issues;
    }

    /** 客户端预检判为冲突的方块，红框用。 */
    public static Set<BlockKey> conflictBlocks() {
        return conflictBlocks;
    }

    /** 草稿里标为删除的机器方块，红框用。 */
    public static Set<BlockKey> removedBlocks() {
        return removedBlocks;
    }

    /** 是否在编辑态（含提交中）。 */
    public static boolean isEditing() {
        return state != State.IDLE;
    }

    /**
     * 草稿是否正在编辑某个节点；终端用它点亮「+」。
     *
     * @param nodeId 节点 id
     * @return 正在编辑该节点时为 {@code true}
     */
    public static boolean isEditingNode(UUID nodeId) {
        return state != State.IDLE && nodeId.equals(draft.nodeId());
    }

    /**
     * 进入编辑态并新建草稿。
     *
     * @param minecraft 客户端实例
     */
    public static void startNew(Minecraft minecraft) {
        if (!holdingWand(minecraft)) {
            WandHud.failure(WandMessages.failure(WandFailure.NOT_HOLDING_WAND));
            return;
        }
        draft = WandDraft.empty(WandCoordinator.nextSeq());
        state = State.EDITING;
        issues = List.of();
        conflictBlocks = Set.of();
        removedBlocks = Set.of();
        WandHud.click();
    }

    /**
     * 进入编辑态并编辑已有节点；终端「+」入口。
     *
     * @param minecraft 客户端实例
     * @param nodeId    节点 id
     */
    public static void startEdit(Minecraft minecraft, UUID nodeId) {
        if (!holdingWand(minecraft)) {
            WandHud.failure(WandMessages.failure(WandFailure.NOT_HOLDING_WAND));
            return;
        }
        if (WandLinks.node(nodeId).isEmpty()) {
            WandHud.failure(WandMessages.failure(WandFailure.NODE_NOT_FOUND));
            return;
        }
        // 终端「+」进来时终端界面还开着；tick 的「打开别的界面就丢草稿」会在同一 tick
        // 把刚建的草稿清掉，玩家关掉终端后已经是 IDLE，右键机器只能落成 1:1。先关界面再进编辑态。
        closeOtherScreen(minecraft);
        draft = new WandDraft(nodeId, List.of(), Set.of(), WandCoordinator.nextSeq());
        state = State.EDITING;
        recheck(minecraft);
        WandHud.click();
    }

    /**
     * 编辑态右键方块：按三态切换加入 / 移除 / 标记删除。
     *
     * @param minecraft 客户端实例
     * @param block     目标方块
     */
    public static void toggle(Minecraft minecraft, BlockKey block) {
        if (state != State.EDITING) {
            return;
        }
        if (draft.addsBlock(block)) {
            applyDraft(minecraft, draft.withoutAddedBlock(block));
            return;
        }
        Optional<WandLinks.Owner> owner = WandLinks.owner(block);
        if (owner.isPresent()) {
            if (owner.get().nodeId().equals(draft.nodeId())) {
                UUID machineId = owner.get().machineId();
                applyDraft(minecraft, draft.removesMachine(machineId)
                        ? draft.withoutRemovedMachine(machineId)
                        : draft.withRemovedMachine(machineId));
                return;
            }
            WandHud.failure(WandMessages.failure(WandFailure.BLOCK_CLAIMED));
            return;
        }
        ResourceLocation typeId = WandPrecheck.baseType(minecraft, draft);
        WandIssue issue = WandPrecheck.checkBlock(minecraft, block, typeId, true);
        if (issue != null) {
            WandHud.failure(WandMessages.failure(issue.failure()));
            return;
        }
        applyDraft(minecraft, draft.withAddedBlock(block));
    }

    /**
     * 把服务端连锁结果批量加入草稿；已在草稿、被占用、类型不一致都跳过。
     *
     * @param machines 服务端过滤后的机器方块
     */
    public static void applyChain(List<BlockKey> machines) {
        if (state != State.EDITING) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ResourceLocation typeId = WandPrecheck.baseType(minecraft, draft);
        int added = 0;
        int skipped = 0;
        for (BlockKey block : machines) {
            if (draft.addsBlock(block) || WandLinks.owner(block).isPresent()
                    || WandPrecheck.checkBlock(minecraft, block, typeId, true) != null) {
                skipped++;
                continue;
            }
            draft = draft.withAddedBlock(block);
            added++;
        }
        if (added > 0) {
            draft = draft.withRequestSeq(WandCoordinator.nextSeq());
            recheck(minecraft);
        }
        if (added == 0 && skipped == 0) {
            WandHud.neutral(WandMessages.chainEmpty());
        } else {
            WandHud.success(WandMessages.chain(added, skipped));
        }
    }

    /**
     * 提交前换一个新版本号：超时重试也算一次新提交，不会被服务端当成重放。
     *
     * @return 带新版本号的草稿
     */
    public static WandDraft renewRequestSeq() {
        if (state == State.EDITING && !draft.isEmpty()) {
            draft = draft.withRequestSeq(WandCoordinator.nextSeq());
        }
        return draft;
    }

    /** 进入提交态；由协调器在发包前调。 */
    public static void beginSubmit() {
        if (state == State.EDITING) {
            state = State.SUBMITTING;
        }
    }

    /** 提交失败或超时：回到编辑态，草稿保留。 */
    public static void submitFailed() {
        if (state == State.SUBMITTING) {
            state = State.EDITING;
        }
    }

    /** 提交成功：回到 IDLE 并清空草稿。 */
    public static void submitSucceeded() {
        clear();
    }

    /** 清空草稿并回 IDLE，保留所属玩家 / 维度。 */
    public static void clear() {
        WandCoordinator.abandonSubmission();
        state = State.IDLE;
        draft = WandDraft.empty(0L);
        issues = List.of();
        conflictBlocks = Set.of();
        removedBlocks = Set.of();
        handSlot = -1;
    }

    /** 登录 / 登出时连所属玩家与维度一起清。 */
    public static void clearAll() {
        clear();
        owner = null;
        dimension = null;
    }

    /**
     * 每 tick 维护生命周期：登录、换维度、换手、放下杖、打开别的界面都丢草稿。
     *
     * @param minecraft 客户端实例
     */
    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null) {
            clearAll();
            return;
        }
        UUID currentOwner = minecraft.player.getUUID();
        if (owner == null) {
            owner = currentOwner;
        } else if (!owner.equals(currentOwner)) {
            clearAll();
            owner = currentOwner;
            return;
        }
        ResourceKey<Level> level = minecraft.player.level().dimension();
        if (dimension == null) {
            dimension = level;
        } else if (!dimension.equals(level)) {
            clear();
            dimension = level;
            return;
        }
        if (!holdingWand(minecraft)) {
            if (state != State.IDLE || !draft.isEmpty()) {
                clear();
            }
            handSlot = -1;
            return;
        }
        int slot = minecraft.player.getInventory().selected;
        if (handSlot != -1 && handSlot != slot) {
            clear();
        }
        handSlot = slot;
        // 开别的界面就丢草稿；草稿面板本身是例外，关面板回编辑态还要接着用
        if (minecraft.screen != null && !(minecraft.screen instanceof WandDraftScreen)) {
            clear();
        }
    }

    /**
     * 草稿变更后重新取版本号并重算预检。
     *
     * @param minecraft 客户端实例
     * @param next      新草稿
     */
    private static void applyDraft(Minecraft minecraft, WandDraft next) {
        draft = next.withRequestSeq(WandCoordinator.nextSeq());
        recheck(minecraft);
        WandHud.click();
    }

    /**
     * 重算客户端预检与红框集合。
     *
     * @param minecraft 客户端实例
     */
    private static void recheck(Minecraft minecraft) {
        issues = WandPrecheck.check(minecraft, draft);
        Set<BlockKey> conflicts = new HashSet<>();
        for (WandIssue issue : issues) {
            if (issue.block() != null) {
                conflicts.add(issue.block());
            }
        }
        conflictBlocks = Set.copyOf(conflicts);
        Set<BlockKey> removed = new HashSet<>();
        for (UUID machineId : draft.removed()) {
            WandLinks.blockOfMachine(machineId).ifPresent(removed::add);
        }
        removedBlocks = Set.copyOf(removed);
    }

    /**
     * 关掉当前界面：进入编辑态前必须没有别的界面，否则下一个 tick 会把草稿清掉。
     *
     * @param minecraft 客户端实例
     */
    private static void closeOtherScreen(Minecraft minecraft) {
        if (minecraft.screen == null) {
            return;
        }
        if (minecraft.player != null) {
            minecraft.player.closeContainer();
        }
        minecraft.setScreen(null);
    }

    /**
     * 主手是不是机器绑定杖。
     *
     * @param minecraft 客户端实例
     * @return 是时为 {@code true}
     */
    private static boolean holdingWand(Minecraft minecraft) {
        return minecraft.player != null
                && minecraft.player.getMainHandItem().getItem()
                instanceof MachineBindingWandItem;
    }
}