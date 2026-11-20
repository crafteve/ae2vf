package io.github.crafteve.idlefactory.view;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.key.BlockKey;
import io.github.crafteve.idlefactory.machine.MachineHealth;

import net.minecraft.resources.ResourceLocation;

/**
 * 视图表里的一台机器：身份与健康，不含运行状态。
 *
 * <p>每台机器的"在干什么"只在 {@code ViewTableBuilder} 内部用作聚合临时值，聚合进
 * {@link ViewNodeCore#activity()} 后就不再上线：界面不展示单台机器的速率或原因，多传一层
 * 只会让报文变大、让客户端出现第二套聚合口径。</p>
 *
 * @param machineId 机器 id
 * @param block     机器占用的方块（维度 + 坐标），定位用
 * @param blockId   观测到的方块 id；未知时 {@code null}
 * @param health    机器健康
 * @param probed    是否探测过
 * @param stale     健康是否过期
 * @param order     在节点内的加入顺序，从 0 开始
 */
public record ViewMachine(UUID machineId, BlockKey block, @Nullable ResourceLocation blockId,
        MachineHealth health, boolean probed, boolean stale, int order) {
}
