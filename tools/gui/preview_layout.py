"""离线预览界面布局，按三种 GUI 缩放各出一张到 `.tmp/mockup/`。

用**真实贴图** + 和 `FactoryLayout.java` 一致的规则合成，用来在开游戏之前检查：
列会不会被画到右区上面、右区内容会不会互相压。

用法
----
    python tools/gui/preview_layout.py [gutter]
    # 输出 .tmp/mockup/p0-s2.png / p0-s3.png / p0-s4.png

注意
----
- **这个脚本抄了一份布局常量。** 改 `FactoryLayout.java` 时必须同步这里的常量，
  否则图会骗人。图只是辅助，真正的判据是游戏日志里那行
  `Idle Factory UI: screen=... guiScale=... columns=[...]`。
- 字号用系统雅黑近似，**不代表** MC 真实字体宽度；字宽够不够必须进游戏看。
- 合成区/背包的占位格子画成空槽，实际界面里是物品图标。
"""

from __future__ import annotations

import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
TEX = os.path.join(ROOT, "src", "main", "resources", "assets", "idlefactory", "textures", "gui")
OUT_DIR = os.path.join(ROOT, ".tmp", "mockup")

# ---- 与 FactoryLayout.java 对齐 ----
PAD, HEADER_H, RIGHT_W, MIN_RIGHT_W, GAP = 4, 22, 170, 170, 4
TABLE_HEADER_H, ROW_H, FOOTER_H, CELL, GRID_COLS = 18, 26, 14, 18, 9
TAB_H, TAB_COUNT, SEARCH_H, CRAFT = 16, 2, 14, 3
SORT_BTN_W, SORT_BTN_GAP = 22, 4
CRAFT_CELL, CRAFT_RESULT, CRAFT_ARROW, LABEL_GAP = 20, 22, 24, 12
DEFAULT_GUTTER, MAX_PANEL_W, MAX_PANEL_H, MIN_PANEL_W, MIN_PANEL_H = 96, 900, 540, 480, 320

MIN_ALL = [80, 88, 76, 76, 64]
PREF_ALL = [263, 104, 112, 112, 93]
MIN_NO_RATE = [80, 88, 76, 76]
MIN_NO_IO = [80, 88, 64]
MIN_BASIC = [80, 88]
PREF_NO_IO = [200, 104, 93]
PREF_BASIC = [300, 120]

TEXT, DIM = (0x41, 0x3F, 0x54), (0x87, 0x8F, 0xA5)
RUN, WARN, ERR = (0x3F, 0x7F, 0x8C), (0xB0, 0x78, 0x22), (0xCE, 0x24, 0x01)

SCALE_VIEW = 2


def tex(name: str) -> Image.Image:
    return Image.open(os.path.join(TEX, name + ".png")).convert("RGBA")


def nine(dst, img, border, x, y, w, h):
    if w <= 0 or h <= 0:
        return
    s = img.width
    si = s - border * 2
    if border <= 0 or w < border * 2 or h < border * 2:
        dst.alpha_composite(img.resize((w, h), Image.NEAREST), (x, y))
        return

    def piece(px, py, pw, ph, u, v, sw, sh):
        if pw <= 0 or ph <= 0:
            return
        src = img.crop((u, v, u + sw, v + sh))
        if (pw, ph) != (sw, sh):
            src = src.resize((pw, ph), Image.NEAREST)
        dst.alpha_composite(src, (px, py))

    far = s - border
    iw, ih = w - border * 2, h - border * 2
    piece(x, y, border, border, 0, 0, border, border)
    piece(x + w - border, y, border, border, far, 0, border, border)
    piece(x, y + h - border, border, border, 0, far, border, border)
    piece(x + w - border, y + h - border, border, border, far, far, border, border)
    piece(x + border, y, iw, border, border, 0, si, border)
    piece(x + border, y + h - border, iw, border, border, far, si, border)
    piece(x, y + border, border, ih, 0, border, border, si)
    piece(x + w - border, y + border, border, ih, far, border, border, si)
    piece(x + border, y + border, iw, ih, border, border, si, si)


def solid(dst, img, x, y, w, h):
    if w > 0 and h > 0:
        dst.alpha_composite(img.resize((w, h), Image.NEAREST), (x, y))


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def gutter(screen_w: int, wanted: int) -> int:
    allowed = max(0, (screen_w - MIN_PANEL_W) // 2)
    return min(max(0, wanted), allowed)


def distribute(total: int, mn: list[int], pref: list[int]) -> list[int]:
    n = len(mn)
    min_sum = sum(mn)
    if total <= min_sum:
        return list(mn)
    pref = [max(pref[i], mn[i]) for i in range(n)]
    pref_sum = sum(pref)
    if total >= pref_sum:
        result = list(pref)
        result[0] += total - pref_sum
        return result
    extra = total - min_sum
    weight_sum = pref_sum - min_sum
    used = 0
    result = [0] * n
    for i in range(n):
        weight = pref[i] - mn[i]
        result[i] = mn[i] + (extra * weight // weight_sum if weight_sum > 0 else 0)
        used += result[i]
    result[0] += total - used
    return result


def layout(screen_w: int, screen_h: int, wanted_gutter: int) -> dict:
    g = gutter(screen_w, wanted_gutter)
    panel_w = clamp(screen_w - g * 2, MIN_PANEL_W, MAX_PANEL_W)
    panel_h = clamp(screen_h, MIN_PANEL_H, MAX_PANEL_H)
    px, py = (screen_w - panel_w) // 2, (screen_h - panel_h) // 2

    header_x, header_y, header_w = px + PAD, py + PAD, panel_w - PAD * 2
    body_y = header_y + HEADER_H + 3
    body_h = py + panel_h - PAD - body_y

    available = panel_w - PAD * 2
    right_wanted = min(RIGHT_W, available - sum(MIN_ALL) - GAP)
    right_w = max(MIN_RIGHT_W, min(RIGHT_W, right_wanted))
    right_x = px + panel_w - PAD - right_w
    left_x = px + PAD
    left_w = right_x - GAP - left_x

    table_x, table_w = left_x + 4, left_w - 8
    table_header_y = body_y
    row_top = body_y + TABLE_HEADER_H
    footer_y = body_y + body_h - FOOTER_H
    rows_visible = max(1, (footer_y - row_top) // ROW_H)

    if table_w >= sum(MIN_ALL):
        ids = [0, 1, 2, 3, 4]
        widths = distribute(table_w, MIN_ALL, PREF_ALL)
    elif table_w >= sum(MIN_NO_RATE):
        ids = [0, 1, 2, 3]
        widths = distribute(table_w, MIN_NO_RATE, PREF_ALL[:4])
    elif table_w >= sum(MIN_NO_IO):
        ids = [0, 1, 4]
        widths = distribute(table_w, MIN_NO_IO, PREF_NO_IO)
    else:
        ids = [0, 1]
        widths = distribute(table_w, MIN_BASIC, PREF_BASIC)
    xs = [0] * 5
    cursor = table_x
    for k, i in enumerate(ids):
        xs[i] = cursor
        cursor += widths[k]

    tab_y = header_y
    search_y = body_y
    grid_x = right_x + (right_w - GRID_COLS * CELL) // 2
    grid_y = search_y + SEARCH_H + 4
    hotbar_y = body_y + body_h - 6 - CELL
    inv_y = hotbar_y - 3 - 3 * CELL
    inv_label_y = inv_y - LABEL_GAP
    craft_w = CRAFT * CRAFT_CELL
    group_w = craft_w + CRAFT_ARROW + CRAFT_RESULT
    craft_x = grid_x + (GRID_COLS * CELL - group_w) // 2
    craft_y = inv_label_y - 4 - craft_w
    craft_label_y = craft_y - LABEL_GAP
    result_x = craft_x + craft_w + CRAFT_ARROW
    result_y = craft_y + (craft_w - CRAFT_RESULT) // 2
    grid_rows = clamp((craft_label_y - 6 - grid_y) // CELL, 1, 14)

    return dict(
        gutter=g, panel=(px, py, panel_w, panel_h), header=(header_x, header_y, header_w),
        body_y=body_y, body_h=body_h, right=(right_x, right_w), left=(left_x, left_w),
        table_x=table_x, table_w=table_w, table_header_y=table_header_y, row_top=row_top,
        footer_y=footer_y, rows_visible=rows_visible, xs=xs, widths=widths, ids=ids,
        tab_y=tab_y, search_y=search_y, grid=(grid_x, grid_y), grid_rows=grid_rows,
        search_w=right_w - 8 - SORT_BTN_W - SORT_BTN_GAP,
        sort_x=right_x + right_w - 4 - SORT_BTN_W,
        craft=(craft_x, craft_y), craft_label_y=craft_label_y,
        result=(result_x, result_y), inv=(grid_x, inv_y), inv_label_y=inv_label_y,
        hotbar_y=hotbar_y,
    )


DEMO = [("高炉", WARN, "缺料 12s", "↑2.0 ↓2.0"), ("合金炉", RUN, "就绪", "↑2.0 ↓1.0"),
        ("压板机", DIM, "未配", "—"), ("电解槽", DIM, "未载", "—"),
        ("离心机", ERR, "失联", "—"), ("粉碎机", RUN, "就绪", "↑1.0 ↓2.0"),
        ("熔炼炉", RUN, "就绪", "↑1.0 ↓1.0"), ("压缩机", WARN, "缺料", "—"),
        ("结晶器", RUN, "就绪", "↑0.4 ↓0.4"), ("装配机", RUN, "就绪", "↑0.8 ↓0.8"),
        ("熔炉", RUN, "就绪", "↑1.2 ↓1.2"), ("酿造台", WARN, "缺料", "—"),
        ("蒸馏塔", RUN, "就绪", "↑0.6 ↓0.6"), ("压铸机", WARN, "缺料", "—"),
        ("离心分离机", RUN, "就绪", "↑0.3 ↓0.2")]


def render(screen_w: int, screen_h: int, wanted_gutter: int, out: str) -> None:
    panel_tex, slot_tex, btn_tex, field_tex = tex("panel"), tex("slot"), tex("button"), tex("field")
    div, divd, hdr, alt = tex("divider"), tex("divider_dark"), tex("header_bg"), tex("row_alt")

    L = layout(screen_w, screen_h, wanted_gutter)
    img = Image.new("RGBA", (screen_w, screen_h), (40, 42, 48, 255))
    draw = ImageDraw.Draw(img)
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 11)
    small = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 9)

    px, py, panel_w, panel_h = L["panel"]
    hx, hy, hw = L["header"]
    right_x, right_w = L["right"]
    left_x, left_w = L["left"]
    xs, widths = L["xs"], L["widths"]

    nine(img, panel_tex, 3, px, py, panel_w, panel_h)
    solid(img, divd, hx, hy + HEADER_H, right_x - hx, 1)
    solid(img, div, right_x - GAP // 2, L["body_y"], 1, L["body_h"])
    solid(img, hdr, left_x + 1, L["table_header_y"], left_w - 2, TABLE_HEADER_H)

    for i in range(min(L["rows_visible"], len(DEMO))):
        y = L["row_top"] + i * ROW_H
        if i % 2 == 1:
            solid(img, alt, left_x + 1, y, left_w - 2, ROW_H - 1)
        solid(img, div, L["table_x"], y + ROW_H - 1, L["table_w"], 1)
    solid(img, div, L["table_x"], L["footer_y"], L["table_w"], 1)

    tab_w = right_w // TAB_COUNT
    for i in range(TAB_COUNT):
        x = right_x + i * tab_w
        w = right_w - i * tab_w if i == TAB_COUNT - 1 else tab_w
        nine(img, btn_tex, 1, x, L["tab_y"], w, HEADER_H)
    solid(img, divd, right_x, L["tab_y"], tab_w, 2)
    nine(img, field_tex, 1, right_x + 4, L["search_y"], L["search_w"], SEARCH_H)
    nine(img, btn_tex, 1, L["sort_x"], L["search_y"], SORT_BTN_W, SEARCH_H)

    gx, gy = L["grid"]
    for r in range(L["grid_rows"]):
        for c in range(GRID_COLS):
            nine(img, slot_tex, 1, gx + c * CELL, gy + r * CELL, CELL, CELL)

    cx, cy = L["craft"]
    for r in range(CRAFT):
        for c in range(CRAFT):
            nine(img, slot_tex, 1, cx + c * CRAFT_CELL, cy + r * CRAFT_CELL, CRAFT_CELL, CRAFT_CELL)
    rx, ry = L["result"]
    solid(img, divd, cx + CRAFT * CRAFT_CELL + 4, ry + CRAFT_RESULT // 2, CRAFT_ARROW - 8, 1)
    nine(img, slot_tex, 1, rx, ry, CRAFT_RESULT, CRAFT_RESULT)

    ix, iy = L["inv"]
    for r in range(3):
        for c in range(GRID_COLS):
            nine(img, slot_tex, 1, ix + c * CELL, iy + r * CELL, CELL, CELL)
    for c in range(GRID_COLS):
        nine(img, slot_tex, 1, ix + c * CELL, L["hotbar_y"], CELL, CELL)

    # ---- 文字 ----
    center = hy + (HEADER_H - 9) // 2
    x = hx + 6
    solid(img, divd, x, center + 2, 6, 6)
    x += 12
    draw.text((x, center), "放置工厂", font=font, fill=TEXT)
    x += int(draw.textlength("放置工厂", font=font)) + 10
    bw = int(draw.textlength("铁线", font=font)) + 24
    nine(img, btn_tex, 1, x, hy + 3, bw, TAB_H)
    draw.text((x + 8, center), "铁线", font=font, fill=TEXT)
    x += bw + 8
    solid(img, divd, x, center + 2, 6, 6)
    draw.text((x + 10, center), "运行", font=font, fill=RUN)
    stats = "12 台 · 3 线 · 仓库在线"
    close_x = right_x - 6 - 14
    status_end = x + 10 + int(draw.textlength("运行", font=font))
    stats_x = close_x - 8 - int(draw.textlength(stats, font=font))
    if stats_x > status_end + 8:
        draw.text((stats_x, center), stats, font=font, fill=DIM)
    nine(img, btn_tex, 1, close_x, hy + 4, 14, 14)
    draw.text((close_x + 5, center), "x", font=font, fill=TEXT)

    thy = L["table_header_y"] + (TABLE_HEADER_H - 9) // 2
    headers = ["机器", "状态", "输入", "输出", "速率"]
    for head_id in L["ids"]:
        draw.text((xs[head_id] + 4, thy), headers[head_id], font=font, fill=DIM)

    for i, (name, color, detail, rate) in enumerate(DEMO[:min(L["rows_visible"], len(DEMO))]):
        y = L["row_top"] + i * ROW_H
        ty = y + (ROW_H - 9) // 2
        solid(img, divd, left_x + 1, y, 2, ROW_H - 2)
        nine(img, slot_tex, 1, xs[0] + 4, y + 5, CELL, CELL)
        draw.text((xs[0] + 4 + CELL + 2, ty), name, font=font, fill=TEXT)
        solid(img, divd, xs[1] + 4, ty + 2, 6, 6)
        draw.text((xs[1] + 14, ty), detail, font=font, fill=color)
        if 2 in L["ids"]:
            draw.text((xs[2] + 2, ty), "—", font=font, fill=DIM)
        if 3 in L["ids"]:
            draw.text((xs[3] + 2, ty), "—", font=font, fill=DIM)
        if 4 in L["ids"]:
            draw.text((xs[4] + 4, ty), rate, font=small,
                      fill=TEXT if rate != "—" else DIM)

    draw.text((L["table_x"], L["footer_y"] + 3),
              "共 15 台 · 就绪 8 · 缺料 4 · 未配 2 · 失联 1", font=small, fill=DIM)

    for i, t in enumerate(["仓库", "机器"]):
        tw = int(draw.textlength(t, font=font))
        draw.text((right_x + i * tab_w + (tab_w - tw) // 2, L["tab_y"] + (HEADER_H - 9) // 2), t,
                  font=font, fill=TEXT if i == 0 else DIM)
    draw.text((right_x + 8, L["search_y"] + 2), "搜索…", font=small, fill=DIM)
    sort_label = "默"
    sort_w = int(draw.textlength(sort_label, font=small))
    draw.text((L["sort_x"] + (SORT_BTN_W - sort_w) // 2, L["search_y"] + 2),
              sort_label, font=small, fill=DIM)
    draw.text((cx, L["craft_label_y"]), "合成", font=small, fill=DIM)
    draw.text((ix, L["inv_label_y"]), "背包", font=small, fill=DIM)

    big = img.resize((screen_w * SCALE_VIEW, screen_h * SCALE_VIEW), Image.NEAREST)
    big.save(out)
    print(f"  {screen_w}x{screen_h} g{L['gutter']}: panel={panel_w}x{panel_h} "
          f"left={left_w} right={right_w} cols={widths} rows={L['rows_visible']} "
          f"grid={L['grid_rows']} -> {os.path.basename(out)}")


def main() -> int:
    os.makedirs(OUT_DIR, exist_ok=True)
    wanted = int(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_GUTTER
    print(f"gutter requested = {wanted}")
    for name, (w, h) in {
        "s2": (1280, 684),
        "s3": (853, 456),
        "s4": (640, 342),
    }.items():
        render(w, h, wanted, os.path.join(OUT_DIR, f"p0-{name}.png"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
