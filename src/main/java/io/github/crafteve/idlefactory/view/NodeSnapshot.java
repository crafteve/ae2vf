package io.github.crafteve.idlefactory.view;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import net.minecraft.resources.ResourceLocation;

public record NodeSnapshot(java.util.UUID nodeId, @Nullable String name,
        ResourceLocation typeBlockId, NodeStatus status, int ruleCount, int patternCount,
        boolean autoEnergyOnly, int pendingCount, long pendingOldestAt, ProviderSnapshot provider,
        List<MemberSnapshot> members) {

    public NodeSnapshot {
        members = List.copyOf(members);
    }

    /** 节点下的机器台数。 */
    public int machineCount() {
        return this.members.size();
    }

    /**
     * 删除前要不要二次确认。
     *
     * <p>只有「接入时自动补的 FE 输入规则」不算玩家配置：这种节点点删除直接删，
     * 规则反正会在下次接机器时再补回来。</p>
     *
     * @return 有样板、或有玩家自己配的规则
     */
    public boolean configured() {
        return this.patternCount > 0 || (this.ruleCount > 0 && !this.autoEnergyOnly);
    }
}
