"""物品贴图生成：科幻立体风格的终端 / 机器绑定杖 / 存储绑定杖。

输出 64×64 贴图到 assets/idlefactory/textures/item/，并生成 .tmp/item-icons-preview.png
样张（64/32/16）。64 基准坐标，512 画布绘制后缩放。
"""

import math
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, ".tmp", "item-icons-preview.png")

S = 8
BASE = 64
W = BASE * S

# 科幻金属 + 发光色
DARK_BG = (0x0E, 0x14, 0x1C)
CARD_BG = (0x16, 0x1E, 0x2A)
CARD_LINE = (0x2A, 0x3A, 0x4E)
TEXT = (0xE6, 0xF7, 0xFF)
DIM = (0x8F, 0xA6, 0xC0)

METAL_TOP = (0x6E, 0x82, 0xA0)
METAL_BOTTOM = (0x24, 0x2D, 0x3B)
METAL_EDGE = (0x0F, 0x15, 0x1E)
METAL_LIGHT = (0x9A, 0xB0, 0xCC)
METAL_DARK = (0x18, 0x20, 0x2C)
CYAN = (0x38, 0xE1, 0xF0)
CYAN_DIM = (0x1B, 0x8A, 0x9C)
AMBER = (0xFF, 0x9E, 0x3D)
GREEN = (0x4F, 0xE0, 0xA0)
GREEN_DIM = (0x1F, 0x7A, 0x55)
GLASS_TOP = (0x1A, 0x2A, 0x3A)
GLASS_BOTTOM = (0x07, 0x0D, 0x14)
PANEL = (0xD8, 0xD9, 0xDF)
INK = (0x33, 0x44, 0x5C)


def sc(value):
    return int(round(value * S))


def pts(points):
    return [(sc(x), sc(y)) for x, y in points]


def poly(draw, points, fill=None, outline=None, width=0):
    draw.polygon(pts(points), fill=fill, outline=outline, width=sc(width))


def line(draw, points, fill, width=1):
    draw.line(pts(points), fill=fill, width=sc(width))


def ellipse(draw, x1, y1, x2, y2, fill, outline=None, width=0):
    draw.ellipse([sc(x1), sc(y1), sc(x2), sc(y2)], fill=fill, outline=outline, width=sc(width))


def lerp(a, b, t):
    return tuple(int(round(a[i] + (b[i] - a[i]) * t)) for i in range(3))


def gradient_polygon(img, points, top, bottom):
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).polygon(pts(points), fill=255)
    grad = Image.new("RGBA", (W, W))
    gd = ImageDraw.Draw(grad)
    for y in range(W):
        color = lerp(top, bottom, y / (W - 1))
        gd.line([(0, y), (W, y)], fill=(*color, 255))
    img.paste(grad, (0, 0), mask)


def gradient_round_rect(img, box, radius, top, bottom):
    x1, y1, x2, y2 = box
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        [sc(x1), sc(y1), sc(x2), sc(y2)], radius=sc(radius), fill=255)
    grad = Image.new("RGBA", (W, W))
    gd = ImageDraw.Draw(grad)
    for y in range(W):
        color = lerp(top, bottom, y / (W - 1))
        gd.line([(0, y), (W, y)], fill=(*color, 255))
    img.paste(grad, (0, 0), mask)


def add_glow(img, draw_fn, blur=10, alpha=150):
    layer = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer, "RGBA"))
    layer = layer.filter(ImageFilter.GaussianBlur(sc(blur / 8)))
    if alpha < 255:
        r, g, b, a = layer.split()
        a = a.point(lambda value: value * alpha // 255)
        layer = Image.merge("RGBA", (r, g, b, a))
    img.alpha_composite(layer)


def terminal():
    """厚板全息终端：金属倒角外壳 + 玻璃屏 + 升起的小工厂投影。"""
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img, "RGBA")

    # 底部厚度
    gradient_polygon(img, [(9, 45), (57, 45), (57, 51), (9, 51)], METAL_BOTTOM, METAL_EDGE)
    gradient_polygon(img, [(57, 18), (62, 23), (62, 51), (57, 51)], METAL_BOTTOM, METAL_EDGE)
    poly(draw, [(9, 51), (57, 51), (62, 46), (14, 46)], (0x0B, 0x10, 0x16))

    # 正面外壳
    gradient_round_rect(img, (7, 15, 57, 47), 5, METAL_TOP, METAL_BOTTOM)
    draw.rounded_rectangle([sc(7), sc(15), sc(57), sc(47)], radius=sc(5),
                           outline=METAL_EDGE, width=sc(2))
    # 倒角高光/暗边
    line(draw, [(11, 17), (53, 17)], METAL_LIGHT, 2)
    line(draw, [(9, 21), (9, 43)], (0x8A, 0x9E, 0xB8), 1)
    line(draw, [(11, 45), (53, 45)], (0x12, 0x18, 0x22), 2)
    line(draw, [(55, 21), (55, 43)], (0x12, 0x18, 0x22), 2)
    # 四角螺丝
    for x, y in ((11, 19), (53, 19), (11, 43), (53, 43)):
        ellipse(draw, x - 1.4, y - 1.4, x + 1.4, y + 1.4, (0x0C, 0x11, 0x18))
        ellipse(draw, x - 0.8, y - 0.8, x + 0.8, y + 0.8, (0x7C, 0x90, 0xAC))
        line(draw, [(x - 0.6, y), (x + 0.6, y)], (0x0C, 0x11, 0x18), 1)

    # 屏幕：深色玻璃
    gradient_round_rect(img, (13, 21, 51, 41), 2, GLASS_TOP, GLASS_BOTTOM)
    draw.rounded_rectangle([sc(13), sc(21), sc(51), sc(41)], radius=sc(2),
                           outline=(0x05, 0x09, 0x0E), width=sc(2))
    # 玻璃斜反光
    poly(draw, [(15, 23), (25, 23), (17, 38), (15, 38)], (0x9A, 0xD8, 0xE8, 30))
    line(draw, [(15, 23), (49, 23)], (0x9A, 0xD8, 0xE8, 70), 1)

    # 屏幕网格
    for x in (23, 32, 41):
        line(draw, [(x, 23), (x, 39)], (0x1E, 0x3A, 0x4A), 1)
    for y in (28, 34):
        line(draw, [(15, y), (49, y)], (0x1E, 0x3A, 0x4A), 1)

    # 全息工厂：线框厂房 + 光柱
    add_glow(img, lambda d: (
        d.polygon(pts([(24, 36), (31, 30), (38, 36)]), fill=(0x38, 0xE1, 0xF0, 70)),
        d.rectangle([sc(27), sc(30), sc(35), sc(36)], fill=(0x38, 0xE1, 0xF0, 55)),
    ), blur=14, alpha=180)
    line(draw, [(18, 36), (46, 36)], CYAN, 2)
    line(draw, [(21, 36), (21, 31), (25, 31), (25, 36)], CYAN, 1)
    line(draw, [(28, 36), (28, 27), (34, 27), (34, 36)], CYAN, 1)
    line(draw, [(38, 36), (38, 32), (43, 32), (43, 36)], CYAN, 1)
    line(draw, [(30, 27), (30, 24), (33, 24)], CYAN, 1)
    # 屏幕底部数据条
    for i in range(5):
        line(draw, [(16 + i * 7, 39), (20 + i * 7, 39)], CYAN_DIM, 1)

    # 天线
    line(draw, [(48, 15), (53, 4)], (0x0C, 0x11, 0x18), 3)
    line(draw, [(49, 15), (54, 4)], (0x7C, 0x90, 0xAC), 1)
    add_glow(img, lambda d: d.ellipse(
        [sc(51.5), sc(2), sc(56.5), sc(7)], fill=(0x38, 0xE1, 0xF0, 220)), blur=10, alpha=255)
    ellipse(draw, 52, 2.5, 56, 6.5, CYAN_DIM)
    ellipse(draw, 53.2, 3.7, 54.8, 5.3, (0xEA, 0xFF, 0xFF))

    # 侧面接口灯
    for y, color in ((24, CYAN), (31, AMBER), (38, (0xE6, 0xF7, 0xFF))):
        line(draw, [(58.5, y), (60.5, y)], color, 2)

    # 底部按键
    draw.rounded_rectangle([sc(18), sc(44), sc(26), sc(46)], radius=sc(1), fill=CYAN)
    draw.rounded_rectangle([sc(30), sc(44), sc(35), sc(46)], radius=sc(1), fill=AMBER)
    line(draw, [(40, 45), (48, 45)], (0x4E, 0x5F, 0x78), 1)

    # 全息投影：屏幕上方的小立方体 + 粒子
    add_glow(img, lambda d: d.polygon(
        pts([(31, 6), (38, 3), (45, 7), (38, 10)]), fill=(0x38, 0xE1, 0xF0, 90)),
        blur=10, alpha=200)
    poly(draw, [(31, 6), (38, 3), (45, 7), (38, 10)], None, (0x8A, 0xF4, 0xFF, 120), 1)
    line(draw, [(38, 10), (38, 15)], (0x38, 0xE1, 0xF0, 120), 1)
    for x, y in ((28, 9), (47, 12), (35, 1), (44, 3)):
        ellipse(draw, x - 0.5, y - 0.5, x + 0.5, y + 0.5, (0x9A, 0xF6, 0xFF, 180))
    return img


def rod(img, draw, ring):
    """杖杆：金属渐变 + 能量导管 + 握把。"""
    gradient_polygon(img, [(28, 26), (38, 26), (36, 61), (30, 61)], METAL_TOP, METAL_BOTTOM)
    line(draw, [(30, 27), (32, 59)], METAL_LIGHT, 2)
    line(draw, [(37, 27), (35, 59)], (0x0F, 0x15, 0x1E), 2)
    # 能量导管
    add_glow(img, lambda d: d.line([(sc(33), sc(28)), (sc(34), sc(48))],
                                    fill=(0x38, 0xE1, 0xF0, 150), width=sc(1.2)), blur=8)
    line(draw, [(33, 28), (34, 48)], CYAN, 1)
    for y in (32, 38, 44):
        line(draw, [(32.5, y), (34.5, y)], (0xEA, 0xFF, 0xFF), 1)
    # 握把
    gradient_round_rect(img, (26, 48, 39, 61), 2, (0x35, 0x3E, 0x50), (0x11, 0x16, 0x1E))
    draw.rounded_rectangle([sc(26), sc(48), sc(39), sc(61)], radius=sc(2),
                           outline=(0x08, 0x0C, 0x12), width=sc(2))
    for y in (51, 54, 57):
        line(draw, [(27, y), (38, y)], (0x4A, 0x55, 0x68), 1)
    # 能量环
    add_glow(img, lambda d: d.rounded_rectangle(
        [sc(26.5), sc(49.5), sc(38.5), sc(52.5)], radius=sc(1),
        fill=(*ring, 220)), blur=10, alpha=220)
    draw.rounded_rectangle([sc(26.5), sc(49.5), sc(38.5), sc(52.5)], radius=sc(1),
                           fill=ring, outline=(0x0C, 0x11, 0x18), width=sc(1))


def machine_wand():
    """机器绑定杖：六边形机械头 + 悬浮青色齿轮 + 能量环。"""
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img, "RGBA")
    rod(img, draw, AMBER)

    # 头部厚度
    poly(draw, [(32, 4), (50, 11), (50, 27), (32, 34), (14, 27), (14, 11)],
         (0x0B, 0x10, 0x16))
    gradient_polygon(img, [(32, 2), (48, 9), (48, 25), (32, 32), (16, 25), (16, 9)],
                     METAL_TOP, METAL_BOTTOM)
    draw.polygon(pts([(32, 2), (48, 9), (48, 25), (32, 32), (16, 25), (16, 9)]),
                 outline=METAL_EDGE, width=sc(2))
    # 内六边形
    gradient_polygon(img, [(32, 5), (45, 10.5), (45, 23.5), (32, 29), (19, 23.5), (19, 10.5)],
                     (0x4A, 0x5A, 0x72), (0x1B, 0x23, 0x30))
    draw.polygon(pts([(32, 5), (45, 10.5), (45, 23.5), (32, 29), (19, 23.5), (19, 10.5)]),
                 outline=(0x0B, 0x10, 0x16), width=sc(1))
    # 六角铆钉
    for x, y in ((32, 4), (46, 10), (46, 24), (32, 30), (18, 24), (18, 10)):
        ellipse(draw, x - 1.1, y - 1.1, x + 1.1, y + 1.1, (0x0B, 0x10, 0x16))
        ellipse(draw, x - 0.6, y - 0.6, x + 0.6, y + 0.6, (0xA8, 0xBE, 0xD8))

    # 外圈能量环（六边形）
    add_glow(img, lambda d: d.polygon(
        pts([(32, 7), (43, 12), (43, 22), (32, 27), (21, 22), (21, 12)]),
        outline=(0x38, 0xE1, 0xF0, 220), width=sc(1.2)), blur=12, alpha=220)
    draw.polygon(pts([(32, 7), (43, 12), (43, 22), (32, 27), (21, 22), (21, 12)]),
                 outline=(0x9A, 0xF6, 0xFF, 150), width=sc(1))

    # 悬浮齿轮 glow
    add_glow(img, lambda d: d.ellipse([sc(23), sc(7), sc(41), sc(25)],
                                       fill=(0x38, 0xE1, 0xF0, 160)), blur=16, alpha=230)
    # 齿轮齿
    for i in range(8):
        angle = math.tau * i / 8 - math.pi / 2
        x = 32 + math.cos(angle) * 8.5
        y = 16 + math.sin(angle) * 8.5
        draw.regular_polygon((sc(x), sc(y), sc(2.8)), 4,
                             rotation=angle * 180 / math.pi,
                             fill=CYAN, outline=CYAN_DIM)
    # 齿轮盘
    ellipse(draw, 25, 9, 39, 23, CYAN, (0x9A, 0xF6, 0xFF), 1)
    ellipse(draw, 27, 11, 37, 21, (0x0B, 0x22, 0x2A))
    ellipse(draw, 30, 14, 34, 18, CYAN)
    ellipse(draw, 31.2, 15.2, 32.8, 16.8, (0x0B, 0x22, 0x2A))
    # 齿轮高光
    line(draw, [(28, 11), (31, 10)], (0xEA, 0xFF, 0xFF), 1)

    # 橙色警示条
    for x in (18, 44):
        draw.rounded_rectangle([sc(x), sc(15), sc(x + 2), sc(21)],
                               radius=sc(1), fill=AMBER)
    # 顶部能量针
    line(draw, [(32, 2), (32, 0.5)], CYAN, 2)
    add_glow(img, lambda d: d.ellipse([sc(30.5), sc(-1), sc(33.5), sc(2)],
                                       fill=(0x38, 0xE1, 0xF0, 200)), blur=8)
    return img


def storage_wand():
    """存储绑定杖：悬浮等距储物方块 + 绿色能量环 + 抽屉光缝。"""
    img = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img, "RGBA")
    rod(img, draw, GREEN)

    # 悬浮投影光柱
    add_glow(img, lambda d: d.polygon(
        pts([(28, 30), (36, 30), (33, 38), (31, 38)]),
        fill=(0x4F, 0xE0, 0xA0, 90)), blur=12, alpha=160)
    line(draw, [(32, 30), (32, 38)], (0x4F, 0xE0, 0xA0, 140), 1)

    # 后半圈：先画，随后被立方体挡住
    add_glow(img, lambda d: d.arc(
        [sc(12), sc(24), sc(52), sc(36)], 180, 360,
        fill=(0x4F, 0xE0, 0xA0, 180), width=sc(1)), blur=12, alpha=180)
    draw.arc([sc(12), sc(24), sc(52), sc(36)], 180, 360,
             fill=(0x4F, 0xE0, 0xA0, 200), width=sc(1))

    # 等距立方体：顶面 / 左面 / 右面
    gradient_polygon(img, [(32, 2), (50, 10), (32, 18), (14, 10)],
                     (0xE6, 0xEC, 0xF4), (0x9A, 0xA8, 0xBC))
    gradient_polygon(img, [(14, 10), (32, 18), (32, 30), (14, 22)],
                     (0x7C, 0x8A, 0x9E), (0x3A, 0x44, 0x54))
    gradient_polygon(img, [(32, 18), (50, 10), (50, 22), (32, 30)],
                     (0x5A, 0x68, 0x7C), (0x28, 0x31, 0x3E))
    # 立方体棱边
    line(draw, [(32, 2), (50, 10), (50, 22), (32, 30), (14, 22), (14, 10), (32, 2)],
         (0x0B, 0x10, 0x16), 2)
    line(draw, [(14, 10), (32, 18), (50, 10)], (0xE6, 0xEC, 0xF4), 1)
    line(draw, [(32, 18), (32, 30)], (0x12, 0x18, 0x22), 1)
    # 顶面绿色圆环
    add_glow(img, lambda d: d.ellipse([sc(26), sc(6), sc(38), sc(14)],
                                       outline=(0x4F, 0xE0, 0xA0, 220), width=sc(1.2)),
             blur=10, alpha=200)
    ellipse(draw, 26, 6, 38, 14, None, GREEN, 1)
    ellipse(draw, 30, 8.5, 34, 11.5, GREEN)
    # 左面抽屉光缝
    for y in (13, 18):
        line(draw, [(18, y), (29, y + 5)], GREEN, 1)
    # 右面能量格栅
    for i in range(3):
        line(draw, [(36, 16 + i * 4), (46, 12 + i * 4)], (0x4F, 0xE0, 0xA0, 160), 1)
    # 前半圈：画在立方体前面，和后半圈形成遮挡
    add_glow(img, lambda d: d.arc(
        [sc(12), sc(24), sc(52), sc(36)], 0, 180,
        fill=(0x4F, 0xE0, 0xA0, 180), width=sc(1)), blur=12, alpha=180)
    draw.arc([sc(12), sc(24), sc(52), sc(36)], 0, 180,
             fill=(0x4F, 0xE0, 0xA0, 200), width=sc(1))
    return img


def font(size, bold=False):
    path = "C:/Windows/Fonts/msyhbd.ttc" if bold else "C:/Windows/Fonts/msyh.ttc"
    return ImageFont.truetype(path, size)


def scaled(icon, size):
    return icon.resize((size, size), Image.LANCZOS)


def preview():
    items = [
        ("工厂终端", terminal()),
        ("机器绑定杖", machine_wand()),
        ("存储绑定杖", storage_wand()),
    ]

    cell_w = 320
    cell_h = 540
    width = cell_w * len(items) + 40
    height = cell_h + 40
    canvas = Image.new("RGBA", (width, height), (*DARK_BG, 255))
    draw = ImageDraw.Draw(canvas)
    title_font = font(24, True)
    label_font = font(15)

    draw.text((20, 10), "物品贴图样张：科幻立体风 64 / 32 / 16", font=title_font, fill=TEXT)

    for index, (name, icon) in enumerate(items):
        x0 = 20 + index * cell_w
        y0 = 50
        draw.rounded_rectangle([x0, y0, x0 + cell_w - 20, y0 + cell_h - 20],
                               radius=12, fill=(*CARD_BG, 255),
                               outline=(*CARD_LINE, 255), width=2)
        draw.text((x0 + 16, y0 + 10), name, font=title_font, fill=TEXT)

        # 64px 放大 3 倍
        img64 = scaled(icon, 64 * 3)
        canvas.alpha_composite(img64, (x0 + (cell_w - 20 - img64.width) // 2, y0 + 40))
        draw.text((x0 + 16, y0 + 238), "64px（放大 3×）", font=label_font, fill=DIM)

        # 浅色物品栏条
        draw.rounded_rectangle([x0 + 12, y0 + 266, x0 + cell_w - 32, y0 + 382],
                               radius=10, fill=(0xCB, 0xCC, 0xD4, 255),
                               outline=(0x87, 0x8F, 0xA5, 255), width=2)
        img32 = scaled(icon, 32).resize((32 * 3, 32 * 3), Image.NEAREST)
        img16 = scaled(icon, 16).resize((16 * 4, 16 * 4), Image.NEAREST)
        canvas.alpha_composite(img32, (x0 + 32, y0 + 274))
        canvas.alpha_composite(img16, (x0 + 180, y0 + 290))
        draw.text((x0 + 32, y0 + 366), "32px", font=label_font, fill=INK)
        draw.text((x0 + 180, y0 + 366), "16px（物品栏）", font=label_font, fill=INK)

        # 小字说明
        draw.text((x0 + 16, y0 + 400), "金属倒角 · 发光能量 · 悬浮核心", font=label_font, fill=DIM)

    canvas.save(OUT)
    print("saved", OUT, canvas.width, "x", canvas.height)


def export():
    """导出 64×64 物品贴图到资源目录。"""
    out = os.path.join(
        ROOT, "src", "main", "resources", "assets", "idlefactory", "textures", "item")
    os.makedirs(out, exist_ok=True)
    for name, icon in (
            ("factory_terminal", terminal()),
            ("machine_binding_wand", machine_wand()),
            ("storage_binding_wand", storage_wand())):
        scaled(icon, 64).save(os.path.join(out, name + ".png"))
        print("exported", name, "64x64")


if __name__ == "__main__":
    preview()
    export()
