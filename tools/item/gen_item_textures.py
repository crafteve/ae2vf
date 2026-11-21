#!/usr/bin/env python3
"""生成虚空工厂四件物品的 16×16 像素贴图。

风格对齐 AE2：终端是「天线 + 深紫灰外壳 + 浅灰屏幕」，绑定杖是粗杆工具。
两个终端共用外壳，屏幕内容区分；杖只用平面贴图，不做 3D 模型。

输出：
  assets/ae2vf/textures/item/{node_terminal,passive_io_terminal,
      machine_binding_wand,me_binding_wand}.png
  .tmp/item-preview.png（4 张放大 8 倍的预览）
"""

from PIL import Image, ImageDraw, ImageFont
import os

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT_DIR = os.path.join(ROOT, "src", "main", "resources", "assets", "ae2vf", "textures", "item")
PREVIEW = os.path.join(ROOT, ".tmp", "item-preview.png")

# AE2 配色
OUTLINE = (0x1E, 0x1B, 0x24, 255)
SHELL = (0x3E, 0x3A, 0x46, 255)
SHELL_LIGHT = (0x4A, 0x45, 0x52, 255)
SHELL_DARK = (0x2E, 0x2A, 0x36, 255)
SCREEN = (0xC9, 0xC9, 0xD2, 255)
SCREEN_DARK = (0x9A, 0x99, 0xA8, 255)
PURPLE = (0x9B, 0x5F, 0xD0, 255)
PURPLE_LIGHT = (0xC0, 0x92, 0xE8, 255)
PURPLE_DARK = (0x5C, 0x3D, 0x86, 255)
HILIGHT = (0xE8, 0xE8, 0xF0, 255)
CYAN = (0x4F, 0xE0, 0xE0, 255)
METAL = (0xA8, 0xA6, 0xB4, 255)
METAL_DARK = (0x6E, 0x6B, 0x7A, 255)


def canvas():
    """新建 16×16 透明画布。"""
    return Image.new("RGBA", (16, 16), (0, 0, 0, 0))


def px(draw, x, y, color):
    """画一个像素。"""
    draw.point((x, y), fill=color)


def rect(draw, x0, y0, x1, y1, color):
    """填充矩形，坐标含端点。"""
    draw.rectangle([x0, y0, x1, y1], fill=color)


def frame(draw, x0, y0, x1, y1, color):
    """画 1px 边框。"""
    draw.rectangle([x0, y0, x1, y1], outline=color, width=1)


def terminal_shell(draw):
    """AE2 无线终端外壳：左上天线、深色圆角框、浅灰屏幕。"""
    # 天线（连到外壳顶）
    rect(draw, 3, 0, 5, 2, PURPLE)
    px(draw, 3, 0, PURPLE_LIGHT)
    frame(draw, 3, 0, 5, 2, OUTLINE)
    px(draw, 6, 2, OUTLINE)

    # 外壳
    rect(draw, 1, 2, 14, 14, OUTLINE)
    rect(draw, 2, 3, 13, 13, SHELL)
    rect(draw, 2, 3, 13, 4, SHELL_LIGHT)
    rect(draw, 2, 13, 13, 13, SHELL_DARK)
    px(draw, 13, 13, HILIGHT)
    # 圆角：四角各擦 1px
    for x, y in ((1, 2), (14, 2), (1, 14), (14, 14)):
        px(draw, x, y, (0, 0, 0, 0))

    # 屏幕：深紫外框 + 浅灰底 + 顶部阴影
    rect(draw, 3, 5, 12, 12, PURPLE_DARK)
    rect(draw, 4, 6, 11, 11, SCREEN)
    rect(draw, 4, 6, 11, 6, SCREEN_DARK)


def node_terminal():
    """节点管理终端：屏幕里一个节点方块，四向各有 2px 连线。"""
    img = canvas()
    d = ImageDraw.Draw(img)
    terminal_shell(d)
    # 中心 4×4 节点
    rect(d, 6, 7, 9, 10, PURPLE)
    frame(d, 6, 7, 9, 10, PURPLE_DARK)
    px(d, 6, 7, PURPLE_LIGHT)
    # 四向连线，长度 2px
    rect(d, 4, 8, 5, 8, PURPLE_LIGHT)
    rect(d, 10, 8, 11, 8, PURPLE_LIGHT)
    rect(d, 7, 6, 8, 6, PURPLE_LIGHT)
    rect(d, 7, 11, 8, 11, PURPLE_LIGHT)
    return img


def passive_io_terminal():
    """被动 IO 终端：屏幕里左箭头 → 箱子 → 右箭头。"""
    img = canvas()
    d = ImageDraw.Draw(img)
    terminal_shell(d)
    # 箱子 4×4
    frame(d, 6, 7, 9, 10, PURPLE)
    px(d, 7, 8, PURPLE_DARK)
    px(d, 8, 8, PURPLE_DARK)
    # 左箭头
    px(d, 5, 8, PURPLE)
    px(d, 4, 7, PURPLE)
    px(d, 4, 9, PURPLE)
    # 右箭头
    px(d, 10, 8, CYAN)
    px(d, 11, 7, CYAN)
    px(d, 11, 9, CYAN)
    return img


def wand_head(d, x0, y0, core):
    """4×4 的发光工具头：深紫外框、核心色内芯、左上 1px 高光。"""
    rect(d, x0, y0, x0 + 3, y0 + 3, PURPLE_DARK)
    rect(d, x0 + 1, y0 + 1, x0 + 2, y0 + 2, core)
    px(d, x0 + 1, y0 + 1, HILIGHT)
    px(d, x0 + 2, y0 + 2, PURPLE_DARK)


def machine_wand():
    """机器绑定杖：斜置金属探针，顶端紫色发光头。"""
    img = canvas()
    d = ImageDraw.Draw(img)
    # 杆：2px 宽，从右下到左上，顶端插进头部
    for i in range(9):
        x = 4 + i
        y = 13 - i
        rect(d, x, y, x + 1, y + 1, METAL)
        px(d, x + 1, y + 1, METAL_DARK)
    # 头部盖在杆顶
    wand_head(d, 10, 2, PURPLE)
    # 底部护手
    rect(d, 3, 13, 4, 14, SHELL)
    px(d, 3, 14, SHELL_DARK)
    return img


def me_wand():
    """ME 绑定杖：斜置线缆插头，顶端紫色端子与青色横条。"""
    img = canvas()
    d = ImageDraw.Draw(img)
    # 杆：2px 宽，深灰线缆
    for i in range(9):
        x = 4 + i
        y = 13 - i
        rect(d, x, y, x + 1, y + 1, SHELL_LIGHT)
        px(d, x + 1, y + 1, SHELL_DARK)
    # 头部
    wand_head(d, 10, 2, PURPLE)
    # 青色端子横条
    px(d, 11, 4, CYAN)
    px(d, 12, 4, CYAN)
    # 底部插头
    rect(d, 3, 13, 4, 14, METAL_DARK)
    px(d, 3, 13, METAL)
    return img


ITEMS = [
    ("node_terminal", "节点管理终端", node_terminal),
    ("passive_io_terminal", "被动 IO 终端", passive_io_terminal),
    ("machine_binding_wand", "机器绑定杖", machine_wand),
    ("me_binding_wand", "ME 绑定杖", me_wand),
]


def export():
    """导出 16×16 贴图到资源目录。"""
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, _, build in ITEMS:
        path = os.path.join(OUT_DIR, name + ".png")
        build().save(path)
        print("exported", path, "16x16")


def preview():
    """生成 4 张 8 倍放大的预览图。"""
    scale = 8
    cell = 16 * scale
    gap = 24
    label_h = 28
    width = gap + len(ITEMS) * (cell + gap)
    height = gap + label_h + cell + gap
    canvas_img = Image.new("RGBA", (width, height), (0x1B, 0x18, 0x22, 255))
    draw = ImageDraw.Draw(canvas_img)
    try:
        font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 18)
    except OSError:
        font = ImageFont.load_default()

    for index, (_, label, build) in enumerate(ITEMS):
        x0 = gap + index * (cell + gap)
        y0 = gap + label_h
        draw.rectangle([x0 - 1, y0 - 1, x0 + cell, y0 + cell],
                       outline=(0x4A, 0x45, 0x52, 255), width=1)
        big = build().resize((cell, cell), Image.NEAREST)
        canvas_img.alpha_composite(big, (x0, y0))
        draw.text((x0, gap), label, fill=(0xE8, 0xE8, 0xF0, 255), font=font)

    os.makedirs(os.path.dirname(PREVIEW), exist_ok=True)
    canvas_img.save(PREVIEW)
    print("saved", PREVIEW, canvas_img.width, "x", canvas_img.height)


if __name__ == "__main__":
    export()
    preview()