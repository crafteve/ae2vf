package io.github.crafteve.idlefactory.command;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.key.BlockKey;

/**
 * 绑定杖草稿：一次提交要落的完整改动。
 *
 * <p>{@code nodeId == null} 表示新建节点，此时 {@code removed} 必须为空；非空表示编辑已有节点，
 * 可以同时加机器与删机器。草稿只描述意图，服务端提交时会全量重验，不信这里的任何结论。</p>
 *
 * <p>不变量：{@code added} 内方块不重复；新建草稿不带删除；删除 id 不重复。规模上限（
 * {@code added + removed} 与配置 / 线协议硬顶的较小者）由解码侧判，本类只管结构。</p>
 *
 * @param nodeId     要编辑的节点 id；{@code null} 表示新建节点
 * @param added      要加入的机器方块，每台一个方块
 * @param removed    要删除的已有机器 id；只有编辑已有节点时才非空
 * @param requestSeq 草稿版本号，提交时用于防重放
 */
public record WandDraft(@Nullable UUID nodeId, List<BlockKey> added, Set<UUID> removed,
        long requestSeq) {

    public WandDraft {
        added = List.copyOf(added);
        removed = Set.copyOf(removed);
        if (nodeId == null && !removed.isEmpty()) {
            throw new IllegalArgumentException("新建草稿不能带删除");
        }
        if (new HashSet<>(added).size() != added.size()) {
            throw new IllegalArgumentException("草稿里的方块重复");
        }
    }

    /**
     * 空草稿。
     *
     * @param requestSeq 草稿版本号
     * @return 没有加也没有删的草稿
     */
    public static WandDraft empty(long requestSeq) {
        return new WandDraft(null, List.of(), Set.of(), requestSeq);
    }

    /** 草稿没有任何改动。 */
    public boolean isEmpty() {
        return this.added.isEmpty() && this.removed.isEmpty();
    }

    /**
     * 方块是否已经在待加入列表里。
     *
     * @param block 方块
     * @return 在列表里时为 {@code true}
     */
    public boolean addsBlock(BlockKey block) {
        return this.added.contains(block);
    }

    /**
     * 机器是否已经在待删除集合里。
     *
     * @param machineId 机器 id
     * @return 在集合里时为 {@code true}
     */
    public boolean removesMachine(UUID machineId) {
        return this.removed.contains(machineId);
    }

    /**
     * 换一个草稿版本号，结构不变。
     *
     * @param seq 新版本号
     * @return 新草稿
     */
    public WandDraft withRequestSeq(long seq) {
        return new WandDraft(this.nodeId, this.added, this.removed, seq);
    }

    /**
     * 把一个方块加入待加入列表；已经在列表里时原样返回。
     *
     * @param block 方块
     * @return 新草稿
     */
    public WandDraft withAddedBlock(BlockKey block) {
        if (this.added.contains(block)) {
            return this;
        }
        List<BlockKey> next = new ArrayList<>(this.added);
        next.add(block);
        return new WandDraft(this.nodeId, next, this.removed, this.requestSeq);
    }

    /**
     * 把一个方块移出待加入列表；不在列表里时原样返回。
     *
     * @param block 方块
     * @return 新草稿
     */
    public WandDraft withoutAddedBlock(BlockKey block) {
        if (!this.added.contains(block)) {
            return this;
        }
        List<BlockKey> next = new ArrayList<>(this.added);
        next.remove(block);
        return new WandDraft(this.nodeId, next, this.removed, this.requestSeq);
    }

    /**
     * 把一台已有机器标记为待删除；已经在集合里时原样返回。
     *
     * @param machineId 机器 id
     * @return 新草稿
     */
    public WandDraft withRemovedMachine(UUID machineId) {
        if (this.removed.contains(machineId)) {
            return this;
        }
        Set<UUID> next = new HashSet<>(this.removed);
        next.add(machineId);
        return new WandDraft(this.nodeId, this.added, next, this.requestSeq);
    }

    /**
     * 撤销一台机器的删除标记；不在集合里时原样返回。
     *
     * @param machineId 机器 id
     * @return 新草稿
     */
    public WandDraft withoutRemovedMachine(UUID machineId) {
        if (!this.removed.contains(machineId)) {
            return this;
        }
        Set<UUID> next = new HashSet<>(this.removed);
        next.remove(machineId);
        return new WandDraft(this.nodeId, this.added, next, this.requestSeq);
    }
}
