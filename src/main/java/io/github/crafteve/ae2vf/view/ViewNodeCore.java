package io.github.crafteve.ae2vf.view;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.io.BlockingMode;
import io.github.crafteve.ae2vf.io.DispatchMode;
import io.github.crafteve.ae2vf.node.Multiplier;
import io.github.crafteve.ae2vf.observation.NodeHealth;
import io.github.crafteve.ae2vf.view.ViewNode.Activity;

import net.minecraft.resources.ResourceLocation;

/**
 * 视图表里节点自己的一行字段：身份、排序、四个结论字段、计数与样板供应器配置。
 *
 * <p>四个结论字段（{@code probed} / {@code stale} / {@code health} / {@code activity}）
 * 由 {@code ViewTableBuilder} 在构造节点行时一次算好；原来独立的 {@code NodeStatus} 记录与
 * {@code NodeStatusBuilder} 已按草稿 23 合并进来，不再有第二份节点状态表示。</p>
 *
 * <p>{@code available} 是"这个节点现在能不能干活"的合成结论：仓库在线且节点不是全员离线。
 * 它不等于 {@code health}，也不等于 {@code activity}，所以单独一个字段，客户端不自己拼。</p>
 *
 * @param nodeId        节点 id
 * @param name          玩家起的名字；{@code null} 表示用方块名
 * @param typeBlockId   类型方块 id，节点图标与分组用
 * @param order         在节点库里的顺序，从 0 开始
 * @param probed        是否全部成员都探测过
 * @param stale         是否有成员健康过期
 * @param health        节点健康
 * @param activity      节点在干什么
 * @param patternCount  已放样板数
 * @param pendingCount  待投递订单数
 * @param autoEnergyOnly 规则里只有自动补的 FE 输入规则（删除节点不需要二次确认）
 * @param pendingOldestAt 最早一单的入队时刻；{@code -1} 表示没有待投递订单
 * @param available     仓库在线且节点有机器在线
 * @param blocking      被动阻挡开关
 * @param multiplier    倍率
 * @param dispatch      派单模式
 */
public record ViewNodeCore(UUID nodeId, @Nullable String name, ResourceLocation typeBlockId,
        int order, boolean probed, boolean stale, NodeHealth health, Activity activity,
        int patternCount, int pendingCount, boolean autoEnergyOnly, long pendingOldestAt,
        boolean available, BlockingMode blocking, Multiplier multiplier, DispatchMode dispatch) {
}
