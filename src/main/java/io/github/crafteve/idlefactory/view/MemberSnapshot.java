package io.github.crafteve.idlefactory.view;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import io.github.crafteve.idlefactory.location.BlockKey;
import io.github.crafteve.idlefactory.view.MemberSnapshot;
import io.github.crafteve.idlefactory.machine.MachineHealth;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

public record MemberSnapshot(java.util.UUID machineId, BlockKey block,
        @Nullable ResourceLocation blockId, MachineHealth health, boolean probed,
        boolean stale) {

    /** 机器方块。 */
    public BlockKey primaryBlock() {
        return this.block;
    }

    /** 方块坐标。 */
    public BlockPos primaryPos() {
        return this.block.pos();
    }

    /** 方块所在维度。 */
    public ResourceLocation dimensionId() {
        return this.block.dimension().location();
    }
}
