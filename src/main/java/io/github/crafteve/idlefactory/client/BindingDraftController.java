package io.github.crafteve.idlefactory.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.client.network.ClientBindingCommitHandler;
import io.github.crafteve.idlefactory.factory.BindingDraft;
import io.github.crafteve.idlefactory.factory.BindingMode;
import io.github.crafteve.idlefactory.factory.location.BlockKey;
import io.github.crafteve.idlefactory.item.MachineBindingWandItem;
import io.github.crafteve.idlefactory.network.payload.FactoryBindingCommitC2S;
import io.github.crafteve.idlefactory.network.payload.FactoryWandActionC2S;
import io.github.crafteve.idlefactory.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 绑定杖草稿的客户端权威副本：模式、机器 / 方块、手势与提交。
 *
 * <p>草稿不跨登录保留：登录 / 登出 / 换维度 / 换手 / 把杖放进容器时一律清空。静态缓存是权威
 * 副本，同时镜像到绑定杖的非 persistent、非 network-synchronized 组件，供 tooltip 与重开
 * 界面恢复。客户端只做简单核验（主手、重复块、堆叠同 id），服务端仍会全量重验。</p>
 *
 * <p>手势：SINGLE 普通左右键仍走 1:1 toggle；STACK / MULTIBLOCK / STACKED_MULTIBLOCK 用
 * 蹲下右键开草稿，蹲下右键空气提交，蹲下左键空气取消。</p>
 */
public final class BindingDraftController {
    private static BindingDraft draft = empty(BindingMode.SINGLE);
    /** 当前是否有一台「还开着」的多方块机器；STACK 模式不用。 */
    private static boolean machineOpen;
    /** 草稿所属玩家；换人就清空。 */
    @Nullable
    private static UUID owner;
    /** 草稿所属主手槽位；换手就清空。 */
    private static int handSlot = -1;
    /** 草稿所属维度；换维度就清空。 */
    @Nullable
    private static ResourceKey<Level> dimension;
    /** 最近一次机器表里见过的「方块 → 节点」映射；世界编辑已有节点时用。 */
    private static final Map<BlockKey, UUID> blockToNode = new HashMap<>();

    private BindingDraftController() {
        // 纯静态控制器
    }

    /** 当前草稿。 */
    public static BindingDraft draft() {
        return draft;
    }

    /** 草稿是否非空。 */
    public static boolean hasDraft() {
        return !draft.isEmpty();
    }

    /**
     * 滚轮切换模式；草稿非空时拒绝。
     *
     * @param backwards 是否反向
     */
    public static void cycleMode(boolean backwards) {
        if (!draft.isEmpty()) {
            actionBar("草稿非空，先完成或取消（蹲下 + 左键空气）", ChatFormatting.RED);
            return;
        }
        BindingMode[] modes = BindingMode.values();
        int index = Math.floorMod(draft.mode().ordinal() + (backwards ? -1 : 1), modes.length);
        draft = empty(modes[index]);
        syncComponent();
        actionBar("绑定模式：" + modeName(draft.mode()), ChatFormatting.AQUA);
    }

    /**
     * 右键方块。
     *
     * @param context 使用上下文
     * @return 交互结果
     */
    public static InteractionResult onUseBlock(UseOnContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return InteractionResult.PASS;
        }
        boolean sneaking = minecraft.player.isShiftKeyDown();
        BlockKey block = BlockKey.of(context.getLevel().dimension(), context.getClickedPos());
        if (draft.mode() == BindingMode.SINGLE && !sneaking) {
            // 1:1 语义保持原样：普通右键仍然 toggle。
            PacketDistributor.sendToServer(new FactoryWandActionC2S(
                    FactoryWandActionC2S.Action.MACHINE_TOGGLE, context.getClickedPos(), false));
            return InteractionResult.SUCCESS;
        }
        if (!sneaking) {
            if (draft.isEmpty()) {
                PacketDistributor.sendToServer(new FactoryWandActionC2S(
                        FactoryWandActionC2S.Action.MACHINE_TOGGLE, context.getClickedPos(), false));
                return InteractionResult.SUCCESS;
            }
            return addByNormalRightClick(block);
        }
        // 蹲下右键：开草稿 / 开机器 / 加部件
        if (draft.isEmpty()) {
            UUID existing = blockToNode.get(block);
            draft = new BindingDraft(draft.mode(), existing, List.of());
            machineOpen = false;
        }
        if (draft.mode() == BindingMode.SINGLE) {
            actionBar("先蹲下滚轮切到堆叠 / 多方块模式", ChatFormatting.YELLOW);
            return InteractionResult.SUCCESS;
        }
        if (draft.mode() == BindingMode.STACK) {
            draft = draft.withMachine(List.of(block));
            syncComponent();
            actionBar("已加机器 #" + draft.machines().size() + "，蹲下右键空气提交", ChatFormatting.AQUA);
            return InteractionResult.SUCCESS;
        }
        if (!machineOpen) {
            draft = draft.withMachine(List.of(block));
            machineOpen = true;
            syncComponent();
            actionBar("开始机器 #" + draft.machines().size() + "，右键加部件", ChatFormatting.AQUA);
        } else {
            addPart(block);
        }
        return InteractionResult.SUCCESS;
    }

    /** 普通右键：STACK 加一台单方块机器，多方块加部件。 */
    private static InteractionResult addByNormalRightClick(BlockKey block) {
        if (draft.mode() == BindingMode.STACK) {
            draft = draft.withMachine(List.of(block));
            syncComponent();
            actionBar("已加机器 #" + draft.machines().size(), ChatFormatting.AQUA);
            return InteractionResult.SUCCESS;
        }
        addPart(block);
        return InteractionResult.SUCCESS;
    }

    /** 给当前开着的机器加一个部件；重复方块直接拒绝。 */
    private static void addPart(BlockKey block) {
        if (!machineOpen || draft.machines().isEmpty()) {
            actionBar("先蹲下右键定第一块", ChatFormatting.YELLOW);
            return;
        }
        int index = draft.machines().size() - 1;
        List<BlockKey> blocks = new ArrayList<>(draft.machines().get(index).blocks());
        if (blocks.contains(block)) {
            actionBar("这个方块已经在草稿里", ChatFormatting.RED);
            return;
        }
        blocks.add(block);
        List<BindingDraft.MachineDraft> machines = new ArrayList<>(draft.machines());
        machines.set(index, new BindingDraft.MachineDraft(blocks));
        draft = new BindingDraft(draft.mode(), draft.nodeId(), machines);
        syncComponent();
        actionBar("部件 +1（共 " + blocks.size() + " 块）", ChatFormatting.AQUA);
    }

    /**
     * 左键方块：草稿非空时从草稿移除该块，否则普通 toggle。
     *
     * @param pos 方块坐标
     */
    public static void onLeftClickBlock(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (draft.isEmpty()) {
            PacketDistributor.sendToServer(new FactoryWandActionC2S(
                    FactoryWandActionC2S.Action.MACHINE_TOGGLE, pos, false));
            return;
        }
        BlockKey block = BlockKey.of(minecraft.player.level().dimension(), pos);
        List<BindingDraft.MachineDraft> machines = new ArrayList<>(draft.machines().size());
        boolean removed = false;
        for (BindingDraft.MachineDraft machine : draft.machines()) {
            if (machine.blocks().contains(block)) {
                List<BlockKey> blocks = new ArrayList<>(machine.blocks());
                blocks.remove(block);
                if (!blocks.isEmpty()) {
                    machines.add(new BindingDraft.MachineDraft(blocks));
                }
                removed = true;
            } else {
                machines.add(machine);
            }
        }
        if (!removed) {
            return;
        }
        draft = new BindingDraft(draft.mode(), draft.nodeId(), machines);
        machineOpen = !machines.isEmpty() && draft.mode() != BindingMode.STACK;
        syncComponent();
        actionBar("已从草稿移除", ChatFormatting.GRAY);
    }

    /** 左键空气：蹲下取消草稿。 */
    public static void onLeftClickAir() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.isShiftKeyDown() || draft.isEmpty()) {
            return;
        }
        clear();
        actionBar("已取消绑定草稿", ChatFormatting.GRAY);
    }

    /** 右键空气：蹲下右键提交 / 结束当前机器。 */
    public static void onRightClickAir() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.player.isShiftKeyDown() || draft.isEmpty()) {
            return;
        }
        switch (draft.mode()) {
            case SINGLE -> {
                // SINGLE 不产生草稿
            }
            case STACK -> submit();
            case MULTIBLOCK -> {
                machineOpen = false;
                submit();
            }
            case STACKED_MULTIBLOCK -> {
                if (machineOpen) {
                    machineOpen = false;
                    actionBar("机器 #" + draft.machines().size()
                            + " 已完成；蹲下右键空气提交整份草稿", ChatFormatting.AQUA);
                } else {
                    submit();
                }
            }
        }
    }

    /** 提交草稿；客户端只做简单核验，结果以服务端回执为准。 */
    public static void submit() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        if (draft.isEmpty()) {
            actionBar("草稿为空", ChatFormatting.YELLOW);
            return;
        }
        String error = verify();
        if (error != null) {
            actionBar(error, ChatFormatting.RED);
            return;
        }
        PacketDistributor.sendToServer(new FactoryBindingCommitC2S(0, draft));
    }

    /** 简单核验：主手、重复块、堆叠机器类型一致。 */
    @Nullable
    private static String verify() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return "客户端未就绪";
        }
        if (!(minecraft.player.getMainHandItem().getItem() instanceof MachineBindingWandItem)) {
            return "主手不是绑定杖";
        }
        Map<BlockKey, Boolean> seen = new HashMap<>();
        for (BindingDraft.MachineDraft machine : draft.machines()) {
            for (BlockKey block : machine.blocks()) {
                if (seen.put(block, Boolean.TRUE) != null) {
                    return "草稿里有重复方块";
                }
            }
        }
        return null;
    }

    /**
     * 服务端提交结果：成功清草稿，失败保留。
     *
     * @param payload 结果
     */
    public static void handleResult(io.github.crafteve.idlefactory.network.payload.FactoryBindingCommitS2C payload) {
        if (payload.ok()) {
            clear();
        }
    }

    /**
     * 记录一次「方块 → 节点」映射；世界蹲下右键已有成员时用它填 nodeId。
     *
     * @param block  方块
     * @param nodeId 节点 id
     */
    public static void rememberNode(BlockKey block, UUID nodeId) {
        blockToNode.put(block, nodeId);
    }

    /** 清空映射；登出时调。 */
    public static void clearKnownNodes() {
        blockToNode.clear();
    }

    /**
     * 每 tick 维护草稿生命周期：登录 / 换维度 / 换手 / 把杖放进容器都清空。
     *
     * @param minecraft 客户端实例
     */
    public static void tick(Minecraft minecraft) {
        if (minecraft.player == null) {
            clear();
            return;
        }
        if (owner == null) {
            owner = minecraft.player.getUUID();
        } else if (!owner.equals(minecraft.player.getUUID())) {
            clear();
            owner = minecraft.player.getUUID();
            return;
        }
        ResourceKey<Level> level = minecraft.player.level().dimension();
        int slot = minecraft.player.getInventory().selected;
        boolean holding = minecraft.player.getMainHandItem().getItem()
                instanceof MachineBindingWandItem;
        if (dimension == null) {
            dimension = level;
        } else if (!dimension.equals(level)) {
            clear();
            dimension = level;
            return;
        }
        if (!holding) {
            // 换手 / 把杖放进容器：草稿不跨手保留。
            if (!draft.isEmpty()) {
                clear();
            }
            handSlot = -1;
            return;
        }
        if (handSlot != -1 && handSlot != slot) {
            clear();
        }
        handSlot = slot;
    }

    /** 清空草稿与组件镜像。 */
    public static void clear() {
        if (!draft.isEmpty() || draft.nodeId() != null) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                removeComponent(minecraft.player.getMainHandItem());
            }
        }
        draft = empty(draft.mode());
        machineOpen = false;
        handSlot = -1;
    }

    /** 登出 / 换人时连已知节点映射一起清。 */
    public static void clearAll() {
        clear();
        clearKnownNodes();
        owner = null;
        dimension = null;
    }

    /** 把草稿镜像到主手物品组件；非持久化、非同步。 */
    private static void syncComponent() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        ItemStack stack = minecraft.player.getMainHandItem();
        if (!(stack.getItem() instanceof MachineBindingWandItem)) {
            return;
        }
        stack.set(ModDataComponents.BINDING_DRAFT.get(), draft);
    }

    private static void removeComponent(ItemStack stack) {
        if (stack.getItem() instanceof MachineBindingWandItem) {
            stack.remove(ModDataComponents.BINDING_DRAFT.get());
        }
    }

    private static BindingDraft empty(BindingMode mode) {
        return new BindingDraft(mode, null, List.of());
    }

    private static String modeName(BindingMode mode) {
        return switch (mode) {
            case SINGLE -> "单方块";
            case STACK -> "堆叠";
            case MULTIBLOCK -> "多方块";
            case STACKED_MULTIBLOCK -> "堆叠多方块";
        };
    }

    private static void actionBar(String text, ChatFormatting color) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal(text).withStyle(color), true);
        }
    }
}
