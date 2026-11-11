"""离线生成 GUI 贴图。

约定
----
- 色值一律从 `GuiTheme`（Java 侧）抄过来，两边必须一致；改色先改这里再同步 Java。
- 所有九宫格贴图都用「border」表示不可拉伸的边框宽度；Java 侧 `NineSlice` 按同样的
  数字切九块。border 变了要同时改 Java 常量。
- 生成物落在 `src/main/resources/assets/idlefactory/textures/gui/`，可重复执行覆盖。
- 只依赖 Pillow，不联网。

用法
----
    python tools/gui/gen_textures.py
"""

from __future__ import annotations

import os
import sys

from PIL import Image

# ---------------------------------------------------------------------------
# 调色板（与 GuiTheme.AE2 一一对应）
# ---------------------------------------------------------------------------

# 面板
PANEL_BG = (0xCB, 0xCC, 0xD4)          # 面板底
PANEL_BORDER = (0x41, 0x3F, 0x54)      # 最外圈深色
PANEL_BORDER_LIGHT = (0xF2, 0xF2, 0xF2)  # 深色内圈的亮边
PANEL_BEVEL_DARK = (0xBF, 0xC0, 0xC9)  # 内凹阴影（上/左）
PANEL_BEVEL_LIGHT = (0xD8, 0xD9, 0xDF)  # 内凹高光（下/右）

# 格子
SLOT_BG = (0xAD, 0xB0, 0xC4)
SLOT_DARK = (0x87, 0x8F, 0xA5)
SLOT_LIGHT = (0xF2, 0xF2, 0xF2)
SLOT_HOVER_BG = (0xA6, 0xC4, 0xE6)

# 终端工作区（对齐 AE2 样板管理终端：深色工作区 + 浅色槽位）
WORK_BG = (0x9A, 0x9F, 0xB4)
WORK_SHADOW = (0x69, 0x6D, 0x88)

# 按钮
BUTTON_BG = (0xD8, 0xD9, 0xDF)
BUTTON_DARK = (0x87, 0x8F, 0xA5)
BUTTON_LIGHT = (0xF2, 0xF2, 0xF2)

# 匹配按钮（黄色）
MATCH_BG = (0xF2, 0xD2, 0x7A)
MATCH_DARK = (0xA8, 0x7E, 0x22)
MATCH_LIGHT = (0xFF, 0xEF, 0xB8)

# 输入框
FIELD_BG = (0x9A, 0x9F, 0xB4)
FIELD_BORDER = (0x4D, 0x4D, 0x67)

# 设备列表
TABLE_FRAME_DARK = (0x41, 0x3F, 0x54)
TABLE_FRAME_LIGHT = (0xF2, 0xF2, 0xF2)
TABLE_FRAME_SHADOW = (0x87, 0x8F, 0xA5)
TABLE_HEADER_BG = (0xBF, 0xC0, 0xC9)
ROW_CARD_BG = (0xC6, 0xC8, 0xD3)
ROW_CARD_TOP = (0xE4, 0xE5, 0xEA)
ROW_CARD_BOTTOM = (0x9A, 0x9E, 0xB2)
ROW_CARD_BORDER = (0x87, 0x8F, 0xA5)
MACHINE_CARD_BG = (0x3B, 0x40, 0x4E)
MACHINE_CARD_TOP = (0x67, 0x71, 0x88)
MACHINE_CARD_BOTTOM = (0x23, 0x27, 0x32)
MACHINE_CARD_BORDER = (0x8A, 0x96, 0xB0)
SPEED_BG = (0x87, 0x8F, 0xA5)
SPEED_FILL = (0x3F, 0x7F, 0x8C)
SPEED_FILL_WARN = (0xB0, 0x78, 0x22)
SPEED_FILL_ERROR = (0xCE, 0x24, 0x01)
STATUS_RUN = (0x2E, 0x9E, 0x4F)
STATUS_WARN = (0xD1, 0x8A, 0x00)
STATUS_ERROR = (0xD6, 0x3A, 0x3A)
STATUS_IDLE = (0x8A, 0x93, 0xA6)

# ---------------------------------------------------------------------------
# 搜索框与图标
# ---------------------------------------------------------------------------
SEARCH_BG = (0xB4, 0xB8, 0xC9)
SEARCH_BORDER = (0x4D, 0x4D, 0x67)
SEARCH_SHADOW = (0xA3, 0xA7, 0xB9)
SEARCH_HIGHLIGHT = (0xCA, 0xCD, 0xD9)
SEARCH_ICON = (0x4D, 0x4D, 0x67)
SEARCH_CLEAR = (0x4D, 0x4D, 0x67)

# 排序按钮：蓝灰底 + 白色单色图标（AE2 深色按钮风格）
SORT_BUTTON_BG = (0x87, 0x8F, 0xA5)
SORT_BUTTON_LIGHT = (0xA6, 0xAE, 0xC2)
SORT_BUTTON_DARK = (0x5F, 0x68, 0x7D)
SORT_ICON = (0xF2, 0xF2, 0xF2)
# 排序图标阴影：与 AE2 states.png 的图标阴影同色，保证和 AE2 原生按钮图标一致
SORT_ICON_SHADOW = (0x4D, 0x4D, 0x67)

OUT_DIR = os.path.join(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
    "src", "main", "resources", "assets", "idlefactory", "textures", "gui",
)


# ---------------------------------------------------------------------------
# 工具
# ---------------------------------------------------------------------------

def rect(img: Image.Image, x: int, y: int, w: int, h: int, color) -> None:
    """把 [x, x+w) × [y, y+h) 涂成 color。"""
    px = img.load()
    for yy in range(y, y + h):
        for xx in range(x, x + w):
            px[xx, yy] = color


def panel(width: int = 10, height: int = 10, border: int = 3) -> Image.Image:
    """面板：深色外圈 + 亮色内圈 + 一层内凹斜边。"""
    img = Image.new("RGBA", (width, height), PANEL_BG)
    assert border >= 3, "panel 至少需要 3px 边框：外深、内亮、斜边"
    rect(img, 0, 0, width, height, PANEL_BG)
    # 外圈深色
    rect(img, 0, 0, width, 1, PANEL_BORDER)
    rect(img, 0, height - 1, width, 1, PANEL_BORDER)
    rect(img, 0, 0, 1, height, PANEL_BORDER)
    rect(img, width - 1, 0, 1, height, PANEL_BORDER)
    # 内圈亮色
    rect(img, 1, 1, width - 2, 1, PANEL_BORDER_LIGHT)
    rect(img, 1, height - 2, width - 2, 1, PANEL_BORDER_LIGHT)
    rect(img, 1, 1, 1, height - 2, PANEL_BORDER_LIGHT)
    rect(img, width - 2, 1, 1, height - 2, PANEL_BORDER_LIGHT)
    # 斜边：上/左压暗，下/右提亮
    rect(img, 2, 2, width - 4, 1, PANEL_BEVEL_DARK)
    rect(img, 2, 2, 1, height - 4, PANEL_BEVEL_DARK)
    rect(img, 2, height - 3, width - 4, 1, PANEL_BEVEL_LIGHT)
    rect(img, width - 3, 2, 1, height - 4, PANEL_BEVEL_LIGHT)
    return img


def slot(width: int = 4, height: int = 4, border: int = 1) -> Image.Image:
    """凹槽：上/左暗边，下/右亮边。border=1，可任意拉伸。"""
    img = Image.new("RGBA", (width, height), SLOT_BG)
    rect(img, 0, 0, width, 1, SLOT_DARK)
    rect(img, 0, 0, 1, height, SLOT_DARK)
    rect(img, 0, height - 1, width, 1, SLOT_LIGHT)
    rect(img, width - 1, 0, 1, height, SLOT_LIGHT)
    # 左下角跟随左/上暗边，右上角跟随下/右亮边
    img.load()[0, height - 1] = SLOT_DARK
    return img


def button(width: int = 6, height: int = 6, border: int = 1) -> Image.Image:
    """凸起按钮：上/左亮边，下/右暗边。"""
    img = Image.new("RGBA", (width, height), BUTTON_BG)
    rect(img, 0, 0, width, 1, BUTTON_LIGHT)
    rect(img, 0, 0, 1, height, BUTTON_LIGHT)
    rect(img, 0, height - 1, width, 1, BUTTON_DARK)
    rect(img, width - 1, 0, 1, height, BUTTON_DARK)
    img.load()[0, height - 1] = BUTTON_DARK
    return img


def status_lamp(color) -> Image.Image:
    """4×4 状态灯：左上提亮，右下压暗。"""
    img = Image.new("RGBA", (4, 4), color)
    light = tuple(min(255, c + 45) for c in color)
    dark = tuple(max(0, c - 45) for c in color)
    rect(img, 0, 0, 4, 1, light)
    rect(img, 0, 0, 1, 4, light)
    rect(img, 0, 3, 4, 1, dark)
    rect(img, 3, 0, 1, 4, dark)
    return img


def solid(color) -> Image.Image:
    """1×1 纯色，Java 侧当「可拉伸的填充」用（避免 fill 画几何图元）。"""
    return Image.new("RGBA", (1, 1), color)


def button_hover(width: int = 6, height: int = 6, border: int = 1) -> Image.Image:
    """凸起按钮悬停：浅蓝底，保留上/左亮、下/右暗。"""
    img = Image.new("RGBA", (width, height), SLOT_HOVER_BG)
    rect(img, 0, 0, width, 1, BUTTON_LIGHT)
    rect(img, 0, 0, 1, height, BUTTON_LIGHT)
    rect(img, 0, height - 1, width, 1, BUTTON_DARK)
    rect(img, width - 1, 0, 1, height, BUTTON_DARK)
    img.load()[0, height - 1] = BUTTON_DARK
    return img


def _hline(img: Image.Image, x1: int, x2: int, y: int, color) -> None:
    """画一行像素。"""
    for x in range(x1, x2 + 1):
        img.putpixel((x, y), color)


def _vline(img: Image.Image, x: int, y1: int, y2: int, color) -> None:
    """画一列像素。"""
    for y in range(y1, y2 + 1):
        img.putpixel((x, y), color)


# ---------------------------------------------------------------------------
# 排序图标（16×16，与 AE2 states.png 的工具栏图标同规格）
# ---------------------------------------------------------------------------

def _icon_canvas() -> Image.Image:
    """16×16 透明底的排序图标画布。"""
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def _icon_shadow(img: Image.Image) -> Image.Image:
    """把白色像素整体复制一层到右下 1px 当阴影，被白色压住的位置不画阴影。

    与 AE2 原生图标同款：主色白、右下 1px 深色阴影，因此放进左侧工具栏不会跳。
    """
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    src = img.load()
    dst = out.load()
    for y in range(img.height):
        for x in range(img.width):
            if src[x, y][3] > 0:
                dst[x, y] = SORT_ICON
                if x + 1 < img.width and y + 1 < img.height and src[x + 1, y + 1][3] == 0:
                    dst[x + 1, y + 1] = SORT_ICON_SHADOW
    return out


def sort_status() -> Image.Image:
    """状态档图标：三根阶梯条，表示状态等级（好 → 坏）。"""
    img = _icon_canvas()
    rect(img, 1, 9, 3, 6, SORT_ICON)
    rect(img, 6, 6, 3, 9, SORT_ICON)
    rect(img, 11, 3, 3, 12, SORT_ICON)
    return _icon_shadow(img)


def sort_rule_count() -> Image.Image:
    """IO 规则数档图标：三条规则行（行首小方块 + 长条）。"""
    img = _icon_canvas()
    for y in (2, 7, 12):
        rect(img, 2, y, 2, 2, SORT_ICON)
        rect(img, 5, y, 8, 2, SORT_ICON)
    return _icon_shadow(img)


def sort_distance() -> Image.Image:
    """距离档图标：两侧立柱 + 中间双向箭头，读作「间距」。"""
    img = _icon_canvas()
    rect(img, 2, 3, 2, 10, SORT_ICON)
    rect(img, 13, 3, 2, 10, SORT_ICON)
    rect(img, 7, 7, 3, 2, SORT_ICON)
    for x, y in ((5, 7), (5, 8), (6, 6), (6, 7), (6, 8), (6, 9),
                 (10, 6), (10, 7), (10, 8), (10, 9), (11, 7), (11, 8)):
        rect(img, x, y, 1, 1, SORT_ICON)
    return _icon_shadow(img)


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

SPECS = {
    # 文件名 -> (生成函数, 说明)；第一版工作台删除后只剩终端与共享组件用到的贴图
    "panel": (panel, "面板底：九宫格 border=3"),
    "slot": (slot, "凹槽格子：九宫格 border=1"),
    "button": (button, "凸起按钮：九宫格 border=1"),
    "button_hover": (button_hover, "按钮悬停：6×6 九宫格 border=1"),
    "divider_dark": (lambda: solid(PANEL_BORDER), "粗分隔线：1×1 拉伸"),
    "status_strip_run": (lambda: solid(STATUS_RUN), "状态条：运行"),
    "status_strip_warn": (lambda: solid(STATUS_WARN), "状态条：警告"),
    "status_strip_error": (lambda: solid(STATUS_ERROR), "状态条：错误"),
    "status_strip_idle": (lambda: solid(STATUS_IDLE), "状态条：空闲"),
    "status_lamp_run": (lambda: status_lamp(STATUS_RUN), "状态灯：运行"),
    "status_lamp_warn": (lambda: status_lamp(STATUS_WARN), "状态灯：警告"),
    "status_lamp_error": (lambda: status_lamp(STATUS_ERROR), "状态灯：错误"),
    "status_lamp_idle": (lambda: status_lamp(STATUS_IDLE), "状态灯：空闲"),
    "sort_status": (sort_status, "排序图标：状态（三根阶梯条）"),
    "sort_rule_count": (sort_rule_count, "排序图标：IO 规则数（三条规则行）"),
    "sort_distance": (sort_distance, "排序图标：距离（立柱 + 双向箭头）"),
}


def main() -> int:
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, (factory, note) in SPECS.items():
        img = factory()
        path = os.path.join(OUT_DIR, name + ".png")
        img.save(path)
        print(f"  {name + '.png':<12} {img.width}x{img.height}  {note}")
    print(f"输出目录：{OUT_DIR}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
