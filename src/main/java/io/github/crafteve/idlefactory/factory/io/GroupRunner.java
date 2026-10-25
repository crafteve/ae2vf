package io.github.crafteve.idlefactory.factory.io;

import io.github.crafteve.idlefactory.factory.line.IoGroup;
import io.github.crafteve.idlefactory.factory.line.ItemSpec;
import io.github.crafteve.idlefactory.factory.storage.Storage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * 一个 IO 组的执行器：对一台机器按配置补料、抽产物。
 *
 * <p>每条规则按自己的面取 {@code IItemHandler}；面没有物品能力就跳过该规则，不报错。</p>
 */
public final class GroupRunner {
    private GroupRunner() {
        // 纯静态工具类
    }

    /**
     * 跑一台机器的一轮。
     *
     * @param storage 仓库
     * @param level   机器所在维度
     * @param pos     机器坐标
     * @param group   IO 组
     * @return 本轮结果
     */
    public static IoResult run(Storage storage, ServerLevel level, BlockPos pos, IoGroup group) {
        int moved = 0;
        IoResult.Reason lastReason = IoResult.Reason.NO_ITEM;

        for (ItemSpec spec : group.inputs()) {
            ItemStack preview = preview(spec);
            if (preview.isEmpty()) {
                continue;
            }
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, spec.face());
            IoResult result = IoTransfer.feed(storage, handler, preview);
            moved += result.moved();
            if (result.reason() != IoResult.Reason.OK) {
                lastReason = result.reason();
            }
        }

        for (ItemSpec spec : group.outputs()) {
            ItemStack preview = preview(spec);
            if (preview.isEmpty()) {
                continue;
            }
            IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, spec.face());
            IoResult result = IoTransfer.pull(storage, handler, preview);
            moved += result.moved();
            if (result.reason() != IoResult.Reason.OK) {
                lastReason = result.reason();
            }
        }

        return new IoResult(moved, moved > 0 ? IoResult.Reason.OK : lastReason);
    }

    private static ItemStack preview(ItemSpec spec) {
        ResourceLocation id = ResourceLocation.tryParse(spec.itemId());
        if (id == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        if (item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(item);
    }
}
