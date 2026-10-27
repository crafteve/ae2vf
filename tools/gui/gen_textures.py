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

# 按钮
BUTTON_BG = (0xD8, 0xD9, 0xDF)
BUTTON_DARK = (0x87, 0x8F, 0xA5)
BUTTON_LIGHT = (0xF2, 0xF2, 0xF2)

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
STATUS_RUN = (0x3F, 0x7F, 0x8C)
STATUS_WARN = (0xB0, 0x78, 0x22)
STATUS_ERROR = (0xCE, 0x24, 0x01)
STATUS_IDLE = (0x87, 0x8F, 0xA5)

# 搜索框与图标
SEARCH_BG = (0xB4, 0xB8, 0xC9)
SEARCH_BORDER = (0x4D, 0x4D, 0x67)
SEARCH_SHADOW = (0xA3, 0xA7, 0xB9)
SEARCH_HIGHLIGHT = (0xCA, 0xCD, 0xD9)
SEARCH_ICON = (0x4D, 0x4D, 0x67)
SEARCH_CLEAR = (0x4D, 0x4D, 0x67)

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


def slot_hover(size: int = 18) -> Image.Image:
    """仓库悬停格：浅蓝底，沿用普通格子的上/左暗边与下/右亮边。"""
    img = Image.new("RGBA", (size, size), SLOT_HOVER_BG)
    rect(img, 0, 0, size, 1, SLOT_DARK)
    rect(img, 0, 0, 1, size, SLOT_DARK)
    rect(img, 0, size - 1, size, 1, SLOT_LIGHT)
    rect(img, size - 1, 0, 1, size, SLOT_LIGHT)
    img.load()[0, size - 1] = SLOT_DARK
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


def field(width: int = 4, height: int = 4, border: int = 1) -> Image.Image:
    """输入框：深色描边 + 更暗的底。"""
    img = Image.new("RGBA", (width, height), FIELD_BG)
    rect(img, 0, 0, width, 1, FIELD_BORDER)
    rect(img, 0, height - 1, width, 1, FIELD_BORDER)
    rect(img, 0, 0, 1, height, FIELD_BORDER)
    rect(img, width - 1, 0, 1, height, FIELD_BORDER)
    return img


def table_frame(width: int = 6, height: int = 6) -> Image.Image:
    """设备列表外框：1px 深色外圈 + 内侧亮/暗边，中间透明。"""
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    rect(img, 0, 0, width, 1, TABLE_FRAME_DARK)
    rect(img, 0, height - 1, width, 1, TABLE_FRAME_DARK)
    rect(img, 0, 0, 1, height, TABLE_FRAME_DARK)
    rect(img, width - 1, 0, 1, height, TABLE_FRAME_DARK)
    rect(img, 1, 1, width - 2, 1, TABLE_FRAME_LIGHT)
    rect(img, 1, 1, 1, height - 2, TABLE_FRAME_LIGHT)
    rect(img, 1, height - 2, width - 2, 1, TABLE_FRAME_SHADOW)
    rect(img, width - 2, 1, 1, height - 2, TABLE_FRAME_SHADOW)
    return img


def table_header(width: int = 6, height: int = 6) -> Image.Image:
    """凸起表头：亮顶、暗底，左右有细边。"""
    img = Image.new("RGBA", (width, height), TABLE_HEADER_BG)
    rect(img, 0, 0, width, 1, TABLE_FRAME_LIGHT)
    rect(img, 0, height - 1, width, 1, TABLE_FRAME_SHADOW)
    rect(img, 0, 0, 1, height, TABLE_FRAME_SHADOW)
    rect(img, width - 1, 0, 1, height, TABLE_FRAME_SHADOW)
    return img


def row_card(width: int = 6, height: int = 6) -> Image.Image:
    """行卡片：浅钢灰底，亮顶、暗底、1px 边。"""
    img = Image.new("RGBA", (width, height), ROW_CARD_BG)
    rect(img, 0, 0, width, 1, ROW_CARD_TOP)
    rect(img, 0, height - 1, width, 1, ROW_CARD_BOTTOM)
    rect(img, 0, 0, 1, height, ROW_CARD_BORDER)
    rect(img, width - 1, 0, 1, height, ROW_CARD_BORDER)
    return img


def machine_card(width: int = 6, height: int = 6) -> Image.Image:
    """机器库卡片：深钢灰底、亮顶、暗底、1px 冷色边。"""
    img = Image.new("RGBA", (width, height), MACHINE_CARD_BG)
    rect(img, 0, 0, width, 1, MACHINE_CARD_TOP)
    rect(img, 0, height - 1, width, 1, MACHINE_CARD_BOTTOM)
    rect(img, 0, 0, 1, height, MACHINE_CARD_BORDER)
    rect(img, width - 1, 0, 1, height, MACHINE_CARD_BORDER)
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


def search_icon(size: int = 9) -> Image.Image:
    """搜索放大镜：1px 环 + 斜柄，9×9。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ring = [
        (3, 1), (4, 1), (5, 2), (6, 3), (6, 4), (5, 5),
        (4, 6), (3, 6), (2, 5), (1, 4), (1, 3), (2, 2),
    ]
    for x, y in ring:
        img.putpixel((x, y), SEARCH_ICON)
    # 斜柄，从环的右下角伸出
    for x, y in ((6, 6), (7, 7), (8, 8)):
        img.putpixel((x, y), SEARCH_ICON)
    return img


def search_clear_icon(size: int = 7) -> Image.Image:
    """搜索框清空按钮：7×7 的 X。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for i in range(1, 6):
        img.putpixel((i, i), SEARCH_CLEAR)
        img.putpixel((6 - i, i), SEARCH_CLEAR)
    return img


def solid(color) -> Image.Image:
    """1×1 纯色，Java 侧当「可拉伸的填充」用（避免 fill 画几何图元）。"""
    return Image.new("RGBA", (1, 1), color)


def io_panel(width: int = 10, height: int = 10, border: int = 3) -> Image.Image:
    """IO 展开详情底板：深钢灰板材，亮顶、暗底、冷色外框，顶部一条青色科技线。"""
    img = Image.new("RGBA", (width, height), MACHINE_CARD_BG)
    rect(img, 0, 0, width, 1, MACHINE_CARD_BORDER)
    rect(img, 0, height - 1, width, 1, MACHINE_CARD_BORDER)
    rect(img, 0, 0, 1, height, MACHINE_CARD_BORDER)
    rect(img, width - 1, 0, 1, height, MACHINE_CARD_BORDER)
    rect(img, 1, 1, width - 2, 1, MACHINE_CARD_TOP)
    rect(img, 1, 1, 1, height - 2, MACHINE_CARD_TOP)
    rect(img, 1, height - 2, width - 2, 1, MACHINE_CARD_BOTTOM)
    rect(img, width - 2, 1, 1, height - 2, MACHINE_CARD_BOTTOM)
    # 内凹：顶部再压暗、底部再提亮，让板材低于行卡片一层。
    rect(img, 2, 2, width - 4, 1, MACHINE_CARD_BOTTOM)
    rect(img, 2, 2, 1, height - 4, MACHINE_CARD_BOTTOM)
    # 顶部一条青色科技线。
    rect(img, 2, 2, width - 5, 1, STATUS_RUN)
    return img


def io_cell(width: int = 6, height: int = 6, border: int = 1) -> Image.Image:
    """规则单元格：浅钢灰凸起芯片，亮顶/亮左、暗底/暗右。"""
    img = Image.new("RGBA", (width, height), ROW_CARD_BG)
    rect(img, 0, 0, width, 1, ROW_CARD_TOP)
    rect(img, 0, 0, 1, height, ROW_CARD_TOP)
    rect(img, 0, height - 1, width, 1, ROW_CARD_BOTTOM)
    rect(img, width - 1, 0, 1, height, ROW_CARD_BOTTOM)
    img.load()[0, height - 1] = ROW_CARD_BOTTOM
    return img


def io_cell_hover(width: int = 6, height: int = 6, border: int = 1) -> Image.Image:
    """规则单元格悬停：浅蓝底，沿用凸起芯片的亮/暗边。"""
    img = Image.new("RGBA", (width, height), SLOT_HOVER_BG)
    rect(img, 0, 0, width, 1, ROW_CARD_TOP)
    rect(img, 0, 0, 1, height, ROW_CARD_TOP)
    rect(img, 0, height - 1, width, 1, ROW_CARD_BOTTOM)
    rect(img, width - 1, 0, 1, height, ROW_CARD_BOTTOM)
    img.load()[0, height - 1] = ROW_CARD_BOTTOM
    return img


def io_header(width: int = 6, height: int = 6, border: int = 1) -> Image.Image:
    """IO 段头：深色凹槽条，顶部冷色高光、底部青色科技线。"""
    img = Image.new("RGBA", (width, height), MACHINE_CARD_BOTTOM)
    rect(img, 0, 0, width, 1, MACHINE_CARD_TOP)
    rect(img, 0, height - 1, width, 1, STATUS_RUN)
    return img


def button_hover(width: int = 6, height: int = 6, border: int = 1) -> Image.Image:
    """凸起按钮悬停：浅蓝底，保留上/左亮、下/右暗。"""
    img = Image.new("RGBA", (width, height), SLOT_HOVER_BG)
    rect(img, 0, 0, width, 1, BUTTON_LIGHT)
    rect(img, 0, 0, 1, height, BUTTON_LIGHT)
    rect(img, 0, height - 1, width, 1, BUTTON_DARK)
    rect(img, width - 1, 0, 1, height, BUTTON_DARK)
    img.load()[0, height - 1] = BUTTON_DARK
    return img


def chevron_right(size: int = 7) -> Image.Image:
    """收起箭头：7×7 透明底，指向右。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for y, xs in ((1, (2,)), (2, (2, 3)), (3, (2, 3, 4)), (4, (2, 3)), (5, (2,))):
        for x in xs:
            img.putpixel((x, y), PANEL_BORDER)
    return img


def chevron_down(size: int = 7) -> Image.Image:
    """展开箭头：7×7 透明底，指向下。"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    for x, ys in ((1, (2,)), (2, (2, 3)), (3, (2, 3, 4)), (4, (2, 3)), (5, (2,))):
        for y in ys:
            img.putpixel((x, y), PANEL_BORDER)
    return img


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

SPECS = {
    # 文件名 -> (生成函数, 说明)
    "panel": (panel, "面板底：九宫格 border=3"),
    "slot": (slot, "凹槽格子：九宫格 border=1"),
    "slot_hover": (slot_hover, "仓库悬停格：18×18 浅蓝底"),
    "button": (button, "凸起按钮：九宫格 border=1"),
    "field": (field, "输入框：九宫格 border=1"),
    "search_bg": (lambda: solid(SEARCH_BG), "搜索框纯色底：1×1"),
    "search_border": (lambda: solid(SEARCH_BORDER), "搜索框边框：1×1"),
    "search_shadow": (lambda: solid(SEARCH_SHADOW), "搜索框内阴影：1×1"),
    "search_highlight": (lambda: solid(SEARCH_HIGHLIGHT), "搜索框内高光：1×1"),
    "table_header": (table_header, "凸起表头：6×6 border=1"),
    "row_card": (row_card, "行卡片：6×6 border=1"),
    "machine_card": (machine_card, "机器库卡片：6×6 border=1"),
    "table_light": (lambda: solid(TABLE_FRAME_LIGHT), "表格内圈亮边：1×1"),
    "status_strip_run": (lambda: solid(STATUS_RUN), "状态条：运行"),
    "status_strip_warn": (lambda: solid(STATUS_WARN), "状态条：警告"),
    "status_strip_error": (lambda: solid(STATUS_ERROR), "状态条：错误"),
    "status_strip_idle": (lambda: solid(STATUS_IDLE), "状态条：空闲"),
    "status_lamp_run": (lambda: status_lamp(STATUS_RUN), "状态灯：运行"),
    "status_lamp_warn": (lambda: status_lamp(STATUS_WARN), "状态灯：警告"),
    "status_lamp_error": (lambda: status_lamp(STATUS_ERROR), "状态灯：错误"),
    "status_lamp_idle": (lambda: status_lamp(STATUS_IDLE), "状态灯：空闲"),
    "search": (search_icon, "搜索放大镜：9×9"),
    "search_clear": (search_clear_icon, "搜索清空：7×7"),
    "divider": (lambda: solid(SLOT_DARK), "细分隔线：1×1 拉伸"),
    "divider_dark": (lambda: solid(PANEL_BORDER), "粗分隔线：1×1 拉伸"),
    "header_bg": (lambda: solid(PANEL_BEVEL_LIGHT), "表头底色：1×1 拉伸"),
    "row_alt": (lambda: solid(PANEL_BEVEL_LIGHT), "隔行底色：1×1 拉伸"),
    "io_panel": (io_panel, "IO 详情底板：10×10 九宫格 border=3"),
    "io_cell": (io_cell, "规则单元格：6×6 九宫格 border=1"),
    "io_cell_hover": (io_cell_hover, "规则单元格悬停：6×6 九宫格 border=1"),
    "io_header": (io_header, "IO 段头：6×6 九宫格 border=1"),
    "button_hover": (button_hover, "按钮悬停：6×6 九宫格 border=1"),
    "chevron_right": (chevron_right, "收起箭头：7×7"),
    "chevron_down": (chevron_down, "展开箭头：7×7"),
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
