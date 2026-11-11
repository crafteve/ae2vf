package io.github.crafteve.idlefactory.client.screen.terminal;

import org.lwjgl.glfw.GLFW;

import io.github.crafteve.idlefactory.IdleFactory;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeLayout.Kind;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeLayout.Viewport;
import io.github.crafteve.idlefactory.client.screen.terminal.FactoryNodeModel.NodeRow;

/**
 * 节点终端的开发者探针：只在非生产环境生效。
 *
 * <p>CUA 点小键帽的可靠性不如键盘：F6 打印模型与排版，F7 切第一台节点的阻挡，F8 把第一台节点
 * 改名为「测试节点」，F9 展开 / 收起第一台节点。探针不绕过正式路径，点击与包发送都走屏幕自己的
 * 分发。</p>
 */
final class FactoryNodeProbe {
    private FactoryNodeProbe() {
        // 纯静态探针
    }

    /**
     * 处理探针按键。
     *
     * @param screen  当前屏幕
     * @param keyCode GLFW 键码
     * @return 是否消费
     */
    static boolean handleKey(FactoryNodeScreen screen, int keyCode) {
        if (screen.model().rows().isEmpty()) {
            return false;
        }
        NodeRow first = screen.model().rows().get(0);
        return switch (keyCode) {
            case GLFW.GLFW_KEY_F6 -> {
                logScreen(screen);
                logTable(screen);
                yield true;
            }
            case GLFW.GLFW_KEY_F7 -> {
                boolean next = !first.view().provider().blocking().isOn();
                FactoryNodeActions.setBlocking(screen.containerId(), first, next);
                yield true;
            }
            case GLFW.GLFW_KEY_F8 -> {
                FactoryNodeActions.rename(screen.containerId(), first, "测试节点");
                yield true;
            }
            case GLFW.GLFW_KEY_F9 -> {
                Viewport viewport = screen.layout() == null ? null : screen.layout().viewport();
                if (viewport != null) {
                    screen.mouseClicked(FactoryNodeLayout.cardX(viewport) + 6, firstTop(screen), 0);
                }
                yield true;
            }
            default -> false;
        };
    }

    private static double firstTop(FactoryNodeScreen screen) {
        FactoryNodeLayout layout = screen.layout();
        if (layout == null || layout.boxes().isEmpty()) {
            return 0;
        }
        return FactoryNodeLayout.screenY(layout.viewport(), layout.boxes().get(0).top(),
                screen.scroll()) + 10;
    }

    /**
     * 打印模型与排版。
     *
     * @param screen 屏幕
     */
    static void logScreen(FactoryNodeScreen screen) {
        Viewport viewport = screen.contentViewport();
        IdleFactory.LOGGER.info("[ui-probe] node screen rows={} viewport=({},{},{},{}) "
                        + "scroll={} maxScroll={}", screen.rows(), viewport.x(), viewport.y(),
                viewport.width(), viewport.height(), screen.scroll(),
                screen.layout() == null ? 0
                        : screen.layout().maxScroll(viewport.height()));
    }

    /**
     * 打印节点表摘要。
     *
     * @param screen 屏幕
     */
    static void logTable(FactoryNodeScreen screen) {
        IdleFactory.LOGGER.info("[ui-probe] node table rows={} hasFactory={} meStorage={}",
                screen.model().rows().size(), screen.model().hasFactory(),
                screen.model().meStorage());
        for (int i = 0; i < screen.model().rows().size(); i++) {
            NodeRow row = screen.model().rows().get(i);
            IdleFactory.LOGGER.info("[ui-probe] node[{}] {} status={} members={} rules={} "
                            + "patterns={} blocking={} available={}", i, row.name(),
                    row.status().text(), row.view().machineCount(), row.view().ruleCount(),
                    row.view().patternCount(), row.view().provider().blocking(),
                    row.view().provider().available());
        }
    }

    /**
     * 调试点击某个键帽。
     *
     * @param screen 屏幕
     * @param kind   目标
     */
    static void click(FactoryNodeScreen screen, Kind kind) {
        if (screen.layout() == null || screen.layout().boxes().isEmpty()) {
            return;
        }
        FactoryNodeLayout layout = screen.layout();
        int top = FactoryNodeLayout.screenY(layout.viewport(), layout.boxes().get(0).top(),
                screen.scroll());
        Viewport viewport = layout.viewport();
        int x = switch (kind) {
            case BLOCKING -> FactoryNodeLayout.keyX(viewport, 0) + 6;
            case MULTIPLIER -> FactoryNodeLayout.keyX(viewport, 1) + 6;
            case DISPATCH -> FactoryNodeLayout.keyX(viewport, 2) + 6;
            case EDIT -> FactoryNodeLayout.actionX(viewport) + 6;
            case DELETE -> FactoryNodeLayout.deleteX(viewport) + 6;
            default -> FactoryNodeLayout.nameX(viewport) + 2;
        };
        int y = top + FactoryNodeLayout.KEY_INSET_Y + 6;
        screen.mouseClicked(x, y, 0);
    }
}