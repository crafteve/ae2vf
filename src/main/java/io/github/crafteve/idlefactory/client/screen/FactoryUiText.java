package io.github.crafteve.idlefactory.client.screen;

import java.util.ArrayList;
import java.util.List;

import appeng.api.stacks.AEKey;
import io.github.crafteve.idlefactory.factory.capability.CapabilityKind;
import io.github.crafteve.idlefactory.factory.machine.ResourceSpec;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TooltipFlag;
import net.neoforged.fml.ModList;
import io.github.crafteve.idlefactory.factory.storage.AeKeySupport;

/**
 * 界面专用的文本与资源形态辅助。
 *
 * <p>把「一行多色文字」、搜索字段需要的资源名/tooltip/tag，以及 tooltip/JEI 用的物品形态
 * 收在一起。这里全部是只读的注册表/物品栈查询，不探世界，也不改服务端状态。</p>
 */
public final class FactoryUiText {

    private FactoryUiText() {
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
     * 一组多色片段的总宽度。
     *
     * @param font  字体
     * @param parts 片段
     * @return 像素宽度
     */
    static int partsWidth(Font font, List<TextPart> parts) {
        int width = 0;
        for (TextPart part : parts) {
            width += font.width(part.text());
        }
        return width;
    }

    /**
     * 从左到右画一组多色片段。
     *
     * @param graphics 画布
     * @param font     字体
     * @param parts    片段
     * @param x        起始 x
     * @param y        文字基线 y
     * @return 画完后的右边缘 x
     */
    static int drawParts(GuiGraphics graphics, Font font, List<TextPart> parts, int x, int y) {
        int cursor = x;
        for (TextPart part : parts) {
            graphics.drawString(font, part.text(), cursor, y, part.color(), false);
            cursor += font.width(part.text());
        }
        return cursor;
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
     * 资源 tooltip 文本，给搜索的 {@code $} 语法用。
     *
     * <p>FE 没有物品形态，直接返回 {@code FE}；物品走原版 tooltip 行，流体按桶物品
     * 取提示。这里只读注册表/物品栈，不探世界。</p>
     *
     * @param minecraft 客户端实例；可能为 {@code null}
     * @param key       资源
     * @return 多行文本
     */
    static String resourceTooltip(Minecraft minecraft, AEKey key) {
        if (AeKeySupport.isEnergy(key)) {
            return "FE";
        }
        if (minecraft == null) {
            return key.getId().toString();
        }
        ItemStack stack = resourceStack(key);
        if (stack.isEmpty()) {
            return key.getId().toString();
        }
        StringBuilder text = new StringBuilder();
        for (Component line : stack.getTooltipLines(
                Item.TooltipContext.of(minecraft.level), minecraft.player,
                TooltipFlag.Default.NORMAL)) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(line.getString());
        }
        return text.toString();
    }

    /**
     * 资源标签，给搜索的 {@code #} 语法用。
     *
     * <p>只有物品和流体有 tag；能量/化学品没有，返回空表。</p>
     *
     * @param key 资源
     * @return 标签 id 列表
     */
    static List<String> resourceTags(AEKey key) {
        List<String> tags = new ArrayList<>();
        switch (AeKeySupport.kind(key)) {
            case ITEM -> resourceStack(key).getTags()
                    .forEach(tag -> tags.add(tag.location().toString()));
            case FLUID -> {
                var fluid = BuiltInRegistries.FLUID.get(key.getId());
                BuiltInRegistries.FLUID.wrapAsHolder(fluid).tags()
                        .forEach(tag -> tags.add(tag.location().toString()));
            }
            default -> {
                // 能量和化学品没有物品 tag，搜索字段留空即可。
            }
        }
        return tags;
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
        return switch (AeKeySupport.kind(key)) {
            case ITEM -> AeKeySupport.toStack(key);
            case FLUID -> {
                var fluid = BuiltInRegistries.FLUID.get(key.getId());
                Item bucket = fluid.getBucket();
                yield bucket == Items.AIR ? new ItemStack(Items.BUCKET) : new ItemStack(bucket);
            }
            case ENERGY -> new ItemStack(Items.REDSTONE);
            default -> ItemStack.EMPTY;
        };
    }

    /**
     * 资源显示名。
     *
     * @param key 资源
     * @return 名称
     */
    public static String resourceName(AEKey key) {
        return FactoryLabels.name(key);
    }

    /**
     * 规则面的中文单字。
     *
     * @param spec 规则
     * @return 上/下/北/南/东/西/任意
     */
    static String faceName(ResourceSpec spec) {
        return FactoryLabels.face(spec);
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
