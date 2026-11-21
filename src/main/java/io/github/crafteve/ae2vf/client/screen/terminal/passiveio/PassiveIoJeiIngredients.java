package io.github.crafteve.ae2vf.client.screen.terminal.passiveio;

import java.util.ArrayList;
import java.util.List;

import io.github.crafteve.ae2vf.client.screen.terminal.passiveio.PassiveIoLayout.BlockBox;
import io.github.crafteve.ae2vf.client.screen.terminal.passiveio.PassiveIoLayout.Viewport;

import net.minecraft.client.renderer.Rect2i;

/**
 * 被动 IO 内容区的 JEI 拖拽目标。
 *
 * <p>卡片不提供 R/U：机器行与规则行不是原版槽位，查询只覆盖玩家背包与机器容器，
 * 由 JEI 自己的槽位扫描负责。这里只把整张机器大卡片算成拖入区。</p>
 */
public final class PassiveIoJeiIngredients {
    private PassiveIoJeiIngredients() {
        // 纯静态目标构造
    }

    /**
     * 一条 JEI 拖拽目标。
     *
     * @param block 机器块下标
     * @param area  机器大卡片的屏幕区域（已按视口裁剪）
     */
    public record AddTarget(int block, Rect2i area) {
    }

    /**
     * 构造全部拖拽目标。
     *
     * <p>每台机器一条，区域就是它的机器大卡片：整卡可拖入，不再依赖虚线新增行。
     * 区域按视口裁剪，滚出视口的部分不参与命中，否则拖拽高亮会画到滚动条或背包槽上。</p>
     *
     * @param layout 当前排版
     * @param scroll 当前滚动
     * @return 每台机器一条
     */
    static List<AddTarget> addTargets(PassiveIoLayout layout, int scroll) {
        List<AddTarget> targets = new ArrayList<>();
        Viewport viewport = layout.viewport();
        int left = PassiveIoLayout.bodyX(viewport);
        int width = viewport.width() - 1;
        int viewTop = viewport.y();
        int viewBottom = viewport.y() + viewport.height();
        for (BlockBox box : layout.boxes()) {
            int top = Math.max(viewTop, PassiveIoLayout.screenY(viewport, box.top(), scroll));
            int bottom = Math.min(viewBottom,
                    PassiveIoLayout.screenY(viewport, box.bottom(), scroll));
            if (bottom <= top) {
                continue;
            }
            targets.add(new AddTarget(box.index(), new Rect2i(left, top, width, bottom - top)));
        }
        return targets;
    }
}
