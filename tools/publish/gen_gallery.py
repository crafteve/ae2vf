"""把整屏截图切成聚焦小块，用于项目页 Gallery。

思路
----
- 整屏截图里 UI 只占中间一块，缩小展示时细节全糊；改成「一块 UI + 一句图注」，
  读者一眼看懂这张图要说明什么。
- 区域用**相对坐标**（0..1）写在 MANIFEST 里，所以换分辨率不用重算。
- 坐标沿 UI 卡片边界量取：不要切进半个格子，也不要让文字贴边。
- 输出 1:1 不放大：2560 宽的截图里 UI 已经是 2 倍像素，直接切就够清楚。
- 每块同时输出中英图注到 captions.md，填平台 Gallery 的说明栏时直接抄。

用法
----
    python tools/publish/gen_gallery.py docs/publish/screenshots/raw docs/publish/screenshots/fragments
"""

from __future__ import annotations

import os
import sys

from PIL import Image

# ---------------------------------------------------------------------------
# 切块清单：shot -> (源图文件名, [(块名, 区域(左,上,右,下) 相对坐标, 中文图注, 英文图注)])
# 坐标按 2560×1334 的截图量取，面板范围约 (0.319,0.087)–(0.620,0.992)。
# ---------------------------------------------------------------------------

MANIFEST = {
    "passive-io": (
        "02-passive-io.png",
        [
            ("header", (0.348, 0.093, 0.620, 0.132),
             "一个终端管所有机器：标题、搜索框，左边一排是排序与视图切换",
             "One terminal for every machine: title, search box, and the sorting / view toolbar"),
            ("machine-card", (0.353, 0.139, 0.616, 0.432),
             "每台机器一张卡：状态灯、机器名、仓库里有哪类资源；卡片里是它的 IO 规则",
             "One card per machine: status lamp, name, resource types held, and its IO rules"),
            ("rule-pair", (0.358, 0.213, 0.616, 0.327),
             "每条规则一行：方向（入 / 出）、匹配（任 / 精）、当前速率",
             "One line per rule: direction, match mode, and current rate"),
            ("second-machine", (0.353, 0.433, 0.616, 0.621),
             "同一终端里的第二台机器，速率实时刷新（396k/s、18/s）",
             "A second machine in the same terminal, with live rates"),
        ],
    ),
    "nodes": (
        "03-nodes.png",
        [
            ("rows", (0.348, 0.132, 0.620, 0.349),
             "节点终端：每个节点一行，带成员台数与开关（阻挡 / 倍率 / 轮询 / 编辑 / 删除）",
             "Node terminal: one row per node with member count and its toggles"),
        ],
    ),
    "wand": (
        "01-wand.png",
        [
            ("hud", (0.3848, 0.8546, 0.6152, 0.9108),
             "绑定杖的 HUD：正在编辑哪台机器、按什么键加 / 删、蹲下确认",
             "The wand HUD: which machine is being edited, which keys add or remove, sneak to confirm"),
        ],
    ),
}


def rel_box(size, box):
    """把相对坐标换算成像素框。"""
    w, h = size
    return (int(box[0] * w), int(box[1] * h), int(box[2] * w), int(box[3] * h))


def main(argv) -> int:
    if len(argv) < 3:
        print(__doc__)
        return 2
    src_dir, out_dir = argv[1], argv[2]
    os.makedirs(out_dir, exist_ok=True)

    captions = ["# 切块图注", "", "| 文件名 | 中文图注 | 英文图注 |", "|---|---|---|"]
    for shot, (filename, pieces) in MANIFEST.items():
        path = os.path.join(src_dir, filename)
        if not os.path.exists(path):
            print(f"  跳过 {shot}：缺少源图 {path}")
            continue
        image = Image.open(path).convert("RGB")
        for name, box, zh, en in pieces:
            crop = image.crop(rel_box(image.size, box))
            out_name = f"{shot}-{name}.png"
            crop.save(os.path.join(out_dir, out_name))
            captions.append(f"| `{out_name}` | {zh} | {en} |")
            print(f"  {out_name:<28} {crop.width}x{crop.height}")

    with open(os.path.join(out_dir, "captions.md"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(captions) + "\n")
    print(f"图注写入 {os.path.join(out_dir, 'captions.md')}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
