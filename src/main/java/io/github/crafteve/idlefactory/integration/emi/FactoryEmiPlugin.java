package io.github.crafteve.idlefactory.integration.emi;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;

import dev.emi.emi.api.EmiDragDropHandler;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;

import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoScreen;
import io.github.crafteve.idlefactory.client.screen.terminal.passiveio.PassiveIoJeiIngredients.AddTarget;

import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

/**
 * EMI 侧的被动 IO 终端拖拽接入。
 *
 * <p>EMI 和 JEI 的拖拽是两套 API：JEI 走 {@code IGhostIngredientHandler}，EMI 走
 * {@link EmiDragDropHandler}。本类只做「把 EMI 配料转成 AE2 的 {@link AEKey}，再交给
 * 终端已有的 {@code addFromJei}」，目标区域仍由 {@link PassiveIoScreen#targets()}
 * 从同一份排版算出，不在插件里重算几何。</p>
 *
 * <p>FE 不参与拖拽：FE 由绑定链路决定，和 JEI 侧保持一致。EMI 没有装时本类不会被加载，
 * 所以可以直接引用 EMI 的类。</p>
 */
@EmiEntrypoint
public final class FactoryEmiPlugin implements EmiPlugin {
    @Override
    public void register(EmiRegistry registry) {
        registry.addDragDropHandler(PassiveIoScreen.class, new PassiveIoDragDrop());
    }

    /**
     * 把 EMI 配料转成 AE2 的资源 key。
     *
     * @param ingredient EMI 配料
     * @return 资源 key；物品与流体之外的配料返回 {@code null}
     */
    @Nullable
    private static AEKey toKey(EmiIngredient ingredient) {
        if (ingredient.isEmpty() || ingredient.getEmiStacks().isEmpty()) {
            return null;
        }
        EmiStack stack = ingredient.getEmiStacks().get(0);
        ItemStack item = stack.getItemStack();
        if (!item.isEmpty()) {
            // 同 JEI：AE2 包裹通用堆栈只是包装纸，规则要落在里面的资源上。
            if (GenericStack.isWrapped(item)) {
                GenericStack inner = GenericStack.unwrapItemStack(item);
                if (inner != null) {
                    return inner.what();
                }
            }
            return AEItemKey.of(item);
        }
        if (stack.getKey() instanceof Fluid fluid) {
            return AEFluidKey.of(fluid);
        }
        return null;
    }

    /**
     * 基于矩形区域的拖拽处理器：EMI 负责命中判断与高亮，本类只提供区域和落点动作。
     */
    private static final class PassiveIoDragDrop
            extends EmiDragDropHandler.BoundsBased<PassiveIoScreen> {
        private PassiveIoDragDrop() {
            super(PassiveIoDragDrop::bounds);
        }

        /**
         * 构造当前屏幕上的全部虚线新增区域。
         *
         * @param screen 被动 IO 终端
         * @return 区域 → 落下动作
         */
        private static Map<Bounds, Consumer<EmiIngredient>> bounds(
                PassiveIoScreen screen) {
            Map<Bounds, Consumer<EmiIngredient>> map = new LinkedHashMap<>();
            for (AddTarget target : screen.targets()) {
                Rect2i area = target.area();
                map.put(new Bounds(area.getX(), area.getY(), area.getWidth(),
                                area.getHeight()),
                        ingredient -> drop(screen, target.block(), ingredient));
            }
            return map;
        }

        private static void drop(PassiveIoScreen screen, int block,
                EmiIngredient ingredient) {
            AEKey key = toKey(ingredient);
            if (key != null) {
                screen.addFromJei(block, key);
            }
        }
    }
}
