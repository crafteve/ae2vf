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

# 按钮
BUTTON_BG = (0xD8, 0xD9, 0xDF)
BUTTON_DARK = (0x87, 0x8F, 0xA5)
BUTTON_LIGHT = (0xF2, 0xF2, 0xF2)

# 输入框
FIELD_BG = (0x9A, 0x9F, 0xB4)
FIELD_BORDER = (0x4D, 0x4D, 0x67)

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


def field(width: int = 4, height: int = 4, border: int = 1) -> Image.Image:
    """输入框：深色描边 + 更暗的底。"""
    img = Image.new("RGBA", (width, height), FIELD_BG)
    rect(img, 0, 0, width, 1, FIELD_BORDER)
    rect(img, 0, height - 1, width, 1, FIELD_BORDER)
    rect(img, 0, 0, 1, height, FIELD_BORDER)
    rect(img, width - 1, 0, 1, height, FIELD_BORDER)
    return img


def solid(color) -> Image.Image:
    """1×1 纯色，Java 侧当「可拉伸的填充」用（避免 fill 画几何图元）。"""
    return Image.new("RGBA", (1, 1), color)


# ---------------------------------------------------------------------------
# 入口
# ---------------------------------------------------------------------------

SPECS = {
    # 文件名 -> (生成函数, 说明)
    "panel": (panel, "面板底：九宫格 border=3"),
    "slot": (slot, "凹槽格子：九宫格 border=1"),
    "button": (button, "凸起按钮：九宫格 border=1"),
    "field": (field, "输入框：九宫格 border=1"),
    "divider": (lambda: solid(SLOT_DARK), "细分隔线：1×1 拉伸"),
    "divider_dark": (lambda: solid(PANEL_BORDER), "粗分隔线：1×1 拉伸"),
    "header_bg": (lambda: solid(PANEL_BEVEL_LIGHT), "表头底色：1×1 拉伸"),
    "row_alt": (lambda: solid(PANEL_BEVEL_LIGHT), "隔行底色：1×1 拉伸"),
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
