package io.github.crafteve.ae2vf.view;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.ae2vf.io.IoReason;
import io.github.crafteve.ae2vf.io.IoSide;
import io.github.crafteve.ae2vf.machine.MatchMode;

import net.minecraft.core.Direction;

import appeng.api.stacks.AEKey;

/**
 * 视图表里的一行 IO 规则：规则定义（配置）与聚合结果（运行）合在一起。
 *
 * <p>规则归节点、执行归机器，所以一个节点只有 {@code ruleCount} 行规则，不是
 * {@code 机器数 × 规则数} 行。{@code rate} 是该规则在全部成员机器上的速率之和，
 * {@code reason} 是这些成员里最需要注意的那一个原因（按 {@link IoReason#severity()} 取最大），
 * 两者都在服务端一次算好；客户端不再逐台机器求和或比较严重度。</p>
 *
 * @param side   方向：输入还是输出
 * @param key    资源
 * @param face   访问面；{@code null} 表示任意面
 * @param mode   匹配模式：精确 / 忽略组件
 * @param order  同一方向内的配置顺序，从 0 开始
 * @param rate   聚合后的速率，单位：数量/秒
 * @param reason 聚合后的原因
 */
public record ViewIoRule(IoSide side, AEKey key, @Nullable Direction face, MatchMode mode,
        int order, long rate, IoReason reason) {

    /**
     * 规则的删除身份：方向 + 资源。
     *
     * <p>规则没有编号（草稿 23 取消 slot），差量里删除一行规则只能用这两个字段定位；
     * 面与匹配模式属于可变内容，改了是 upsert，不是删加。</p>
     *
     * @param side 方向
     * @param key  资源
     */
    public record Key(IoSide side, AEKey key) {
    }

    /** 本行的删除身份。 */
    public Key identity() {
        return new Key(this.side, this.key);
    }
}
