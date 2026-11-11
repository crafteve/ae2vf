package io.github.crafteve.idlefactory.client.wand;

import java.util.UUID;

import io.github.crafteve.idlefactory.factory.wand.WandDeleteReason;
import io.github.crafteve.idlefactory.factory.wand.WandFailure;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * 绑定杖的全部文案。
 *
 * <p>HUD 与面板只从这里取 {@link Component}，不在调用点拼字符串：失败原因按
 * {@link WandFailure} 查表，枚举新增时 {@code switch} 会编译失败，提醒补文案。</p>
 *
 * <p>不服务终端与仓库：那些仍走 {@code FactoryMessages}。</p>
 */
public final class WandMessages {
    private WandMessages() {
        // 纯文案表
    }

    /**
     * 失败原因。
     *
     * @param failure 失败原因
     * @return 可显示文案
     */
    public static Component failure(WandFailure failure) {
        return Component.translatable(switch (failure) {
            case NONE -> "wand.failure.none";
            case EMPTY_DRAFT -> "wand.failure.empty_draft";
            case NOT_HOLDING_WAND -> "wand.failure.not_holding_wand";
            case NO_FACTORY -> "wand.failure.no_factory";
            case STALE_REQUEST -> "wand.failure.stale_request";
            case INVALID_REQUEST -> "wand.failure.invalid_request";
            case NODE_NOT_FOUND -> "wand.failure.node_not_found";
            case TYPE_MISMATCH -> "wand.failure.type_mismatch";
            case DUPLICATE_BLOCK -> "wand.failure.duplicate_block";
            case BLOCK_CLAIMED -> "wand.failure.block_claimed";
            case BLOCK_UNLOADED -> "wand.failure.block_unloaded";
            case NOT_A_MACHINE -> "wand.failure.not_a_machine";
            case LIMIT_NODES -> "wand.failure.limit_nodes";
            case LIMIT_MACHINES -> "wand.failure.limit_machines";
            case DELETE_BLOCKED -> "wand.failure.delete_blocked";
            case MEMBER_NOT_REMOVABLE -> "wand.failure.member_not_removable";
        });
    }

    /**
     * 删除被拒绝时节点上还留着的配置。
     *
     * @param reason 残留配置
     * @return 可显示文案
     */
    public static Component deleteReason(WandDeleteReason reason) {
        return Component.translatable(switch (reason) {
            case NONE -> "wand.delete.none";
            case HAS_RULES -> "wand.delete.has_rules";
            case HAS_PATTERNS -> "wand.delete.has_patterns";
        });
    }

    /**
     * 常驻草稿条：新建节点。
     *
     * @param machineCount 草稿里的机器台数
     * @return 可显示文案
     */
    public static Component draftNew(int machineCount) {
        return Component.translatable("wand.hud.draft.new", machineCount);
    }

    /**
     * 常驻草稿条：编辑已有节点。
     *
     * @param nodeLabel    节点类型方块名
     * @param added        待加入台数
     * @param removed      待删除台数
     * @return 可显示文案
     */
    public static Component draftEdit(String nodeLabel, int added, int removed) {
        return Component.translatable("wand.hud.draft.edit", nodeLabel, added, removed);
    }

    /** 提交中提示。 */
    public static Component submitting() {
        return Component.translatable("wand.hud.submitting");
    }

    /**
     * 连锁加入结果。
     *
     * @param added   加入台数
     * @param skipped 跳过台数
     * @return 可显示文案
     */
    public static Component chain(int added, int skipped) {
        return Component.translatable("wand.hud.chain", added, skipped);
    }

    /** 连锁没有找到任何可加入的机器。 */
    public static Component chainEmpty() {
        return Component.translatable("wand.hud.chain.none");
    }

    /** 1:1 已连接。 */
    public static Component toggleConnected() {
        return Component.translatable("wand.hud.toggle.on");
    }

    /** 1:1 已断开。 */
    public static Component toggleDisconnected() {
        return Component.translatable("wand.hud.toggle.off");
    }

    /**
     * 提交成功。
     *
     * @param added   新增台数
     * @param removed 删除台数
     * @return 可显示文案
     */
    public static Component saved(int added, int removed) {
        if (added == 0 && removed == 0) {
            return Component.translatable("wand.hud.saved.none");
        }
        return Component.translatable("wand.hud.saved", added, removed);
    }

    /** 取消草稿。 */
    public static Component cancelled() {
        return Component.translatable("wand.hud.cancelled");
    }

    /** 提交超时。 */
    public static Component timeout() {
        return Component.translatable("wand.hud.timeout");
    }

    /** 提交期间重复操作。 */
    public static Component waiting() {
        return Component.translatable("wand.hud.waiting");
    }

    /** 面板标题。 */
    public static Component panelTitle() {
        return Component.translatable("wand.panel.title");
    }

    /** 面板「取消」。 */
    public static Component panelCancel() {
        return Component.translatable("wand.panel.cancel");
    }

    /** 面板「确定」。 */
    public static Component panelConfirm() {
        return Component.translatable("wand.panel.confirm");
    }

    /**
     * 面板摘要：新建。
     *
     * @param machines 台数
     * @return 可显示文案
     */
    public static Component panelSummaryNew(int machines) {
        return Component.translatable("wand.panel.summary.new", machines);
    }

    /**
     * 面板摘要：编辑已有节点。
     *
     * @param nodeLabel 节点类型方块名
     * @param added     待加入台数
     * @param removed   待删除台数
     * @return 可显示文案
     */
    public static Component panelSummaryEdit(String nodeLabel, int added, int removed) {
        return Component.translatable("wand.panel.summary.edit", nodeLabel, added, removed);
    }

    /** 面板空态。 */
    public static Component panelEmpty() {
        return Component.translatable("wand.panel.empty");
    }

    /**
     * 面板待修正提示。
     *
     * @param count 问题条数
     * @return 可显示文案
     */
    public static Component panelIssues(int count) {
        return Component.translatable("wand.panel.issues", count);
    }

    /** 面板内的删除标记。 */
    public static Component panelRemoved() {
        return Component.translatable("wand.panel.removed");
    }

    /** 面板没有问题时底部的操作提示。 */
    public static Component panelHint() {
        return Component.translatable("wand.panel.hint");
    }

    /**
     * 节点类型方块名；读不到时退回类型 id。
     *
     * @param nodeId 节点 id
     * @return 可显示名字
     */
    public static String nodeLabel(UUID nodeId) {
        return WandLinks.node(nodeId)
                .map(node -> blockName(node.typeBlockId()))
                .orElseGet(() -> nodeId.toString().substring(0, 8));
    }

    /**
     * 方块注册名对应的显示名。
     *
     * @param blockId 方块 id
     * @return 可显示名字
     */
    public static String blockName(ResourceLocation blockId) {
        Block block = BuiltInRegistries.BLOCK.get(blockId);
        return block.getName().getString();
    }
}