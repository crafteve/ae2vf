#!/usr/bin/env python3
"""按 AE2 无线终端的像素布局重画四件物品。

外壳、天线、屏幕、底座的位置直接对齐 AE2 的 wireless_terminal；
屏幕内容分两种：节点终端用横条纹，被动 IO 终端用 3×3 格子。
绑定杖沿用 AE2 network_tool 的「木柄 + 金属杆 + 紫色头 + 青色光」配色。
"""

from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "ae2vf", "textures", "item")
PREVIEW = os.path.join(ROOT, ".tmp", "item-preview.png")

# AE2 无线终端取色（略调为本模组的紫）
OUTLINE = (0x1E, 0x1B, 0x24, 255)
SHELL_TOP = (0x4D, 0x4D, 0x67, 255)
SHELL_SIDE = (0x41, 0x3F, 0x54, 255)
SCREEN_HI = (0xF2, 0xF2, 0xF2, 255)
SCREEN_MID = (0xDE, 0xDF, 0xE3, 255)
SCREEN_LO = (0xCB, 0xCC, 0xD4, 255)
PURPLE_EDGE = (0x91, 0x5D, 0xCD, 255)
STRIPE_LIGHT = (0xE2, 0xA3, 0xE3, 255)
STRIPE_DARK = (0x5A, 0x47, 0x9E, 255)
ANTENNA = (0xB0, 0x6F, 0xDD, 255)
BASE_METAL = (0x69, 0x6D, 0x88, 255)
SHADOW = (0xAD, 0xB0, 0xC4, 255)
# 节点终端专用：深紫屏幕，避免与 AE2 无线终端的浅灰屏幕混同
NODE_SCREEN_HI = (0x4A, 0x36, 0x68, 255)
NODE_SCREEN = (0x3E, 0x2E, 0x56, 255)
NODE_SCREEN_LO = (0x32, 0x24, 0x46, 255)

# 绑定杖：AE2 network_tool 配色
WOOD_DARK = (0x5A, 0x44, 0x24, 255)
WOOD = (0x80, 0x5E, 0x36, 255)
WOOD_LIGHT = (0x99, 0x71, 0x40, 255)
METAL = (0x9A, 0x9F, 0xB4, 255)
METAL_LIGHT = (0xDE, 0xDF, 0xE3, 255)
METAL_DARK = (0x4D, 0x4D, 0x67, 255)
GLOW = (0xAC, 0xE9, 0xFF, 255)
GLOW_DIM = (0x9C, 0xD3, 0xFF, 255)


def canvas():
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def px(d, x, y, color):
    if 0 <= x < 16 and 0 <= y < 16:
        d.point((x, y), fill=color)


def rect(d, x0, y0, x1, y1, color):
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            px(d, x, y, color)


def frame(d, x0, y0, x1, y1, color):
    """画 1px 边框。"""
    d.rectangle([x0, y0, x1, y1], outline=color, width=1)


def terminal_shell(d, screen_hi=SCREEN_HI, screen=SCREEN_MID, screen_lo=SCREEN_LO):
    """AE2 无线终端外壳：1px 天线、方角金属框、屏幕、底部金属条。"""
    # 天线
    px(d, 4, 1, ANTENNA)
    px(d, 4, 2, SHELL_SIDE)
    px(d, 4, 3, SHELL_SIDE)
    # 外壳顶
    for x in range(3, 13):
        px(d, x, 4, SHELL_TOP)
    px(d, 4, 4, SHELL_SIDE)
    # 左右边框
    for y in range(5, 13):
        px(d, 2, y, SHELL_TOP if y < 10 else SHELL_SIDE)
        px(d, 13, y, SHELL_TOP if y < 10 else SHELL_SIDE)
    # 屏幕底：顶部高光、中间主体、底部阴影
    rect(d, 3, 5, 12, 5, screen_hi)
    rect(d, 3, 6, 12, 8, screen)
    rect(d, 3, 9, 12, 11, screen)
    rect(d, 3, 12, 12, 12, screen_lo)
    # 底部金属条
    rect(d, 3, 13, 12, 13, BASE_METAL)
    rect(d, 3, 14, 12, 14, SHELL_SIDE)
    px(d, 2, 13, SHELL_SIDE)
    px(d, 2, 14, SHELL_SIDE)
    px(d, 13, 13, SHELL_SIDE)
    px(d, 13, 14, SHELL_SIDE)


def screen_frame(d):
    """屏幕内的紫色内框，坐标对齐 AE2 的 4..11 / 6..11。"""
    rect(d, 4, 6, 11, 6, PURPLE_EDGE)
    rect(d, 4, 11, 11, 11, PURPLE_EDGE)
    rect(d, 4, 7, 4, 10, PURPLE_EDGE)
    rect(d, 11, 7, 11, 10, PURPLE_EDGE)


# AE2 无线终端底图：节点终端只替换屏幕中间的内容。
BASE_TERMINAL = Image.open(os.path.join(ROOT, "tools", "item", "ae2_wireless_terminal_base.png")).convert("RGBA")


def node_terminal():
    """节点管理终端：横线左右各延 1px、上移 1px，竖线左移 1px，右下带阴影。"""
    img = BASE_TERMINAL.copy()
    d = ImageDraw.Draw(img)
    # 内容区：中紫底
    rect(d, 5, 6, 10, 11, PURPLE_EDGE)
    # 阴影：横线下方一行、竖线右侧一列
    rect(d, 4, 8, 11, 8, STRIPE_DARK)
    rect(d, 7, 6, 7, 11, STRIPE_DARK)
    # 亮线：横线 y=7、左右各延 1px；竖线 x=6
    rect(d, 4, 7, 11, 7, STRIPE_LIGHT)
    rect(d, 6, 6, 6, 11, STRIPE_LIGHT)
    return img


def passive_io_terminal():
    """被动 IO 终端：屏幕里 3×3 格子（对齐 AE2 合成终端）。"""
    img = canvas()
    d = ImageDraw.Draw(img)
    terminal_shell(d)
    screen_frame(d)
    for gy in range(3):
        for gx in range(3):
            x0 = 5 + gx * 2
            y0 = 6 + gy * 2
            rect(d, x0, y0, x0 + 1, y0 + 1, STRIPE_LIGHT)
    for x in (7, 9):
        rect(d, x, 6, x, 11, STRIPE_DARK)
    for y in (8, 10):
        rect(d, 5, y, 10, y, STRIPE_DARK)
    return img


def _recolor(base, mapping):
    """按精确 RGB 换色，保留 alpha；底图不动。"""
    img = base.copy()
    pixels = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = pixels[x, y]
            if (r, g, b) in mapping:
                nr, ng, nb = mapping[(r, g, b)]
                pixels[x, y] = (nr, ng, nb, a)
    return img


# 底图来自 AE2 的 network_tool：只换发光与线圈颜色，保持同一套造型。
BASE_TOOL = Image.open(os.path.join(ROOT, "tools", "item", "ae2_network_tool_base.png")).convert("RGBA")

# 机器杖：青色发光改成绿色，表示机器 IO。
MACHINE_MAP = {
    (0x8A, 0xBB, 0xEF): (0x6F, 0xD9, 0x8A),
    (0xAC, 0xE9, 0xFF): (0xB8, 0xF5, 0xC8),
    (0x9C, 0xD3, 0xFF): (0x9C, 0xE8, 0xB0),
    (0x7D, 0xA9, 0xD2): (0x86, 0xC4, 0x9A),
}

# ME 杖：青色发光改成亮紫，与机器杖的绿色区分。
ME_MAP = {
    (0x8A, 0xBB, 0xEF): (0xC0, 0x8A, 0xF0),
    (0xAC, 0xE9, 0xFF): (0xE0, 0xC0, 0xFF),
    (0x9C, 0xD3, 0xFF): (0xD0, 0xA8, 0xF0),
    (0x7D, 0xA9, 0xD2): (0xA8, 0x86, 0xC4),
    (0xB0, 0x6F, 0xDD): (0x7A, 0x4A, 0xB0),
    (0x79, 0x54, 0xBA): (0x4E, 0x2F, 0x78),
}


def machine_wand():
    """机器绑定杖：在 AE2 网络工具贴图上把青色发光换成绿色。"""
    return _recolor(BASE_TOOL, MACHINE_MAP)


def me_wand():
    """ME 绑定杖：在 AE2 网络工具贴图上把紫色线圈压深。"""
    return _recolor(BASE_TOOL, ME_MAP)


ITEMS = [
    ("node_terminal", "节点管理终端", node_terminal),
    ("passive_io_terminal", "被动 IO 终端", passive_io_terminal),
    ("machine_binding_wand", "机器绑定杖", machine_wand),
    ("me_binding_wand", "ME 绑定杖", me_wand),
]


def export():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, _, build in ITEMS:
        build().save(os.path.join(OUT_DIR, name + ".png"))
        print("exported", name)


def preview():
    scale = 8
    cell = 16 * scale
    gap = 24
    label_h = 28
    width = gap + len(ITEMS) * (cell + gap)
    height = gap + label_h + cell + gap
    out = Image.new("RGBA", (width, height), (0x1B, 0x18, 0x22, 255))
    d = ImageDraw.Draw(out)
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 18)
    except OSError:
        font = ImageFont.load_default()
    for index, (_, label, build) in enumerate(ITEMS):
        x0 = gap + index * (cell + gap)
        y0 = gap + label_h
        d.rectangle([x0 - 1, y0 - 1, x0 + cell, y0 + cell],
                    outline=(0x4A, 0x45, 0x52, 255), width=1)
        out.alpha_composite(build().resize((cell, cell), Image.NEAREST), (x0, y0))
        d.text((x0, gap), label, fill=(0xE8, 0xE8, 0xF0, 255), font=font)
    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    out.save(PREVIEW)
    print("saved", PREVIEW)


if __name__ == "__main__":
    export()
    preview()