package io.github.crafteve.idlefactory.client.screen.terminal;

import org.lwjgl.glfw.GLFW;

import appeng.api.stacks.AEItemKey;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.BlockBox;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.Hit;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.Kind;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoLayout.Viewport;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoModel.BlockView;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryPassiveIoModel.RuleRow;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 被动 IO 终端的开发者探针：只在非生产环境生效。
 *
 * <p>CUA 点小 chip 的可靠性不如键盘，所以界面打开时可以用 F6–F9 直接驱动同一套动作：
 * F6 打印模型与排版，F7 给第一台机器加一条输入规则，F8 翻转第一条规则，F9 删除第一条规则。
 * 探针不绕过正式路径——F7 走 JEI 拖入用的 {@code addFromJei}，F8/F9 走 {@code clickHit}，
 * 与服务端、反馈、快照刷新完全一致。</p>
 *
 * <p>正式包 {@code FMLLoader.isProduction()} 为真，{@link FactoryPassiveIoScreen#keyPressed}
 * 不会调用这里。</p>
 */
final class FactoryPassiveIoProbe {
    private FactoryPassiveIoProbe() {
        // 纯静态探针
    }

    /**
     * 处理探针按键。
     *
     * @param screen  当前屏幕
     * @param keyCode GLFW 键码
     * @return 是否消费
     */
    static boolean handleKey(FactoryPassiveIoScreen screen, int keyCode) {
        return switch (keyCode) {
            case GLFW.GLFW_KEY_F6 -> {
                logScreen(screen);
                logBlocks(screen);
                logLayout(screen);
                yield true;
            }
            case GLFW.GLFW_KEY_F7 -> {
                screen.addFromJei(0, AEItemKey.of(new ItemStack(Items.REDSTONE)));
                yield true;
            }
            case GLFW.GLFW_KEY_F8 -> {
                flipTestRule(screen);
                yield true;
            }
            case GLFW.GLFW_KEY_F9 -> {
                deleteTestRule(screen);
                yield true;
            }
            case GLFW.GLFW_KEY_F10 -> {
                clickTestRule(screen, Kind.CHIP_FACE);
                yield true;
            }
            case GLFW.GLFW_KEY_F11 -> {
                clickTestRule(screen, Kind.CHIP_MODE);
                yield true;
            }
            default -> false;
        };
    }

    /**
     * 翻转测试规则：优先找 F7 加进来的红石，找不到就翻最后一条。
     *
     * <p>目标固定为「测试规则」而不是第 0 条，避免探针把原始机器的第一条输入搬走，
     * 测完还得手工恢复。</p>
     */
    private static void flipTestRule(FactoryPassiveIoScreen screen) {
        int index = testRuleIndex(screen);
        if (index >= 0) {
            screen.clickHit(new Hit(Kind.CHIP_DIR, 0, index), 0);
        }
    }

    private static void deleteTestRule(FactoryPassiveIoScreen screen) {
        int index = testRuleIndex(screen);
        if (index >= 0) {
            Hit hit = new Hit(Kind.RULE, 0, index);
            // 右键现在只有「已选中」的卡片才删；探针先补一次左键选中。
            screen.clickHit(hit, 0);
            screen.clickHit(hit, 1);
        }
    }

    private static void clickTestRule(FactoryPassiveIoScreen screen, Kind kind) {
        int index = testRuleIndex(screen);
        if (index >= 0) {
            screen.clickHit(new Hit(kind, 0, index), 0);
        }
    }

    /**
     * 测试规则下标：优先红石，其次最后一条。
     *
     * @param screen 屏幕
     * @return 下标；没有规则时为 -1
     */
    private static int testRuleIndex(FactoryPassiveIoScreen screen) {
        if (screen.blocks().isEmpty()) {
            return -1;
        }
        BlockView block = screen.blocks().get(0);
        if (block.rules().isEmpty()) {
            return -1;
        }
        for (int i = 0; i < block.rules().size(); i++) {
            if ("minecraft:redstone".equals(
                    block.rules().get(i).spec().key().getId().toString())) {
                return i;
            }
        }
        return block.rules().size() - 1;
    }

    /**
     * 打印界面几何。
     *
     * @param screen 屏幕
     */
    static void logScreen(FactoryPassiveIoScreen screen) {
        Viewport viewport = screen.contentViewport();
        FactoryPassiveIoLayout layout = screen.layout();
        IdleFactory.LOGGER.info("[ui-probe] screen view={} rows={} viewport=({},{},{},{}) "
                        + "content={} scroll={} maxScroll={}",
                screen.view(), screen.rows(), viewport.x(), viewport.y(), viewport.width(),
                viewport.height(), layout == null ? 0 : layout.contentHeight(), screen.scroll(),
                layout == null ? 0 : layout.maxScroll(viewport.height()));
    }

    /**
     * 打印机器表摘要。
     *
     * @param screen 屏幕
     */
    static void logTable(FactoryPassiveIoScreen screen) {
        IdleFactory.LOGGER.info("[ui-probe] table blocks={} hasFactory={} view={}",
                screen.blocks().size(), screen.model().hasFactory(), screen.view());
        logBlocks(screen);
    }

    private static void logBlocks(FactoryPassiveIoScreen screen) {
        for (int i = 0; i < screen.blocks().size(); i++) {
            BlockView block = screen.blocks().get(i);
            StringBuilder rules = new StringBuilder();
            for (RuleRow rule : block.rules()) {
                rules.append(' ').append(rule.input() ? "IN" : "OUT").append(':')
                        .append(rule.spec().key().getId()).append(':')
                        .append(rule.spec().face() == null ? "any" : rule.spec().face().getName())
                        .append(':').append(rule.spec().mode()).append(':').append(rule.rate());
            }
            IdleFactory.LOGGER.info("[ui-probe] block[{}] {} status={} missing={} rules={}{}",
                    i, block.name(), block.status().text(), block.machine().machineMissing(),
                    block.rules().size(), rules);
        }
    }

    private static void logLayout(FactoryPassiveIoScreen screen) {
        FactoryPassiveIoLayout layout = screen.layout();
        if (layout == null) {
            return;
        }
        StringBuilder boxes = new StringBuilder();
        for (BlockBox box : layout.boxes()) {
            boxes.append(" #").append(box.index()).append('[').append(box.top()).append('-')
                    .append(box.bottom()).append(']');
        }
        IdleFactory.LOGGER.info("[ui-probe] layout blocks={}{}", layout.boxes().size(), boxes);
    }
}
