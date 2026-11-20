package io.github.crafteve.idlefactory.client.screen;

import io.github.crafteve.idlefactory.client.screen.UiResources;

import io.github.crafteve.idlefactory.client.screen.FactoryLabels;


import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.machine.ResourceSpec;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import io.github.crafteve.idlefactory.resource.AeKeyAdapter;

/**
 * 界面专用的文本与资源形态辅助。
 *
 * <p>把「一行多色文字」、搜索字段需要的资源名/tooltip/tag，以及 tooltip/JEI 用的物品形态
 * 收在一起。这里全部是只读的注册表/物品栈查询，不探世界，也不改服务端状态。</p>
 */
public final class UiResources {

    private UiResources() {
        // 纯静态辅助
    }

    /**
     * 一行多色文字里的一个片段。
     *
     * <p>用于统计、底部汇总这类「标签暗、数字亮」的地方；颜色在构造时定死，
     * 绘制阶段不再查主题。</p>
     *
     * @param text  片段文字
     * @param color 片段颜色
     */
    record TextPart(String text, int color) {
    }

    /**
     * 模组显示名；查不到容器时退回 namespace。
     *
     * @param namespace 模组 id
     * @return 显示名；空 namespace 返回空串
     */
    public static String modName(String namespace) {
        if (namespace == null || namespace.isEmpty()) {
            return "";
        }
        return ModList.get().getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(namespace);
    }

    /**
     * 资源的物品形态，用于 tooltip 和 JEI 的兜底。
     *
     * <p>流体取桶（没有桶就用空桶），能量用红石粉只是视觉兜底；JEI 的 FE 目标
     * 由界面单独处理，不会把红石粉交给 JEI。</p>
     *
     * @param key 资源
     * @return 物品栈；化学品等没有物品形态时为空
     */
    static ItemStack resourceStack(AEKey key) {
        if (AeKeyAdapter.isEnergy(key)) {
            return new ItemStack(Items.REDSTONE);
        }
        if (AeKeyAdapter.isFluid(key)) {
            var fluid = BuiltInRegistries.FLUID.get(key.getId());
            Item bucket = fluid.getBucket();
            return bucket == Items.AIR ? new ItemStack(Items.BUCKET) : new ItemStack(bucket);
        }
        return AeKeyAdapter.toStack(key);
    }

    /**
     * 规则面的中文单字。
     *
     * @param spec 规则
     * @return 上/下/北/南/东/西/任意
     */
    static String faceName(ResourceSpec spec) {
        return spec.face() == null
                ? net.minecraft.network.chat.Component.translatable("idlefactory.face.any").getString()
                : FactoryLabels.face(spec.face());
    }

    /**
     * 资源对应的物品图标。
     *
     * <p>这里给 tooltip 和拖拽准备物品形态，FE 仍会得到红石粉兜底；JEI 的 FE 目标
     * 由界面单独处理。</p>
     *
     * @param key 资源
     * @return 物品栈
     */
    public static ItemStack iconStack(AEKey key) {
        return FactoryLabels.icon(key);
    }
}
