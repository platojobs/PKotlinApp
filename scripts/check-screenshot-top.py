#!/usr/bin/env python3
"""校验截图停点：视口顶部不能残留上一张卡片的半截内容。

用法：
    adb shell uiautomator dump /sdcard/ui.xml
    adb exec-out cat /sdcard/ui.xml > /tmp/ui.xml
    python3 scripts/check-screenshot-top.py /tmp/ui.xml --expect-top 客厅

断言规则（与 docs/acceptance.md「截图停点」一致）：
1. 视口顶部第一个可见文字必须是 `--expect-top` 指定的完整卡片标题或区块标题，
   用来识别「上一张卡被滚掉上半截、只剩一行尾巴」；
2. 若该文字属于某张 CardView，该卡片顶部不得高于视口顶部（容差 tolerance-dp，
   默认 2dp）；
3. 底部不参与通过/失败判定：滚动列表底部露出下一张卡的半截是正常滚动暗示，
   这里只提示（WARN）。确需严格禁止底部文字跨过视口时用 --strict-bottom。

注意：uiautomator 会把被裁掉的节点 bounds 截到窗口边缘，单看 CardView 的 top
无法区分「卡片刚好贴顶」和「卡片顶部被滚出视口」，因此 --expect-top 的首个文字
校验是识别尾巴的关键。
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS_RE = re.compile(r"\[(\d+),(\d+)]\[(\d+),(\d+)]")


def bounds(node):
    match = BOUNDS_RE.search(node.attrib.get("bounds", ""))
    return tuple(map(int, match.groups())) if match else None


def text_of(node, limit=26):
    return (node.attrib.get("text") or "").replace("\n", " ")[:limit]


def desc_of(node, limit=26):
    return (node.attrib.get("content-desc") or "").replace("\n", " ")[:limit]


def parse_tree(path):
    root = ET.parse(path).getroot()
    parent = {child: node for node in root.iter() for child in node}
    return root, parent


def nearest_card(node, parent):
    cur = parent.get(node)
    while cur is not None:
        if "CardView" in cur.attrib.get("class", ""):
            return cur
        cur = parent.get(cur)
    return None


def is_within(node, ancestor):
    a, b = bounds(node), bounds(ancestor)
    return bool(a and b) and b[0] <= a[0] and b[1] <= a[1] and b[2] >= a[2] and b[3] >= a[3]


def lowest_common_ancestor(nodes, parent):
    ancestors = []
    cur = nodes[0]
    while cur is not None:
        ancestors.append(cur)
        cur = parent.get(cur)
    for candidate in ancestors:
        if all(is_within(node, candidate) for node in nodes):
            return candidate
    return None


def find_viewport(cards, parent):
    # 卡片的最深公共祖先通常就是列表/滚动容器本身（首页为 RecyclerView 的
    # ScrollView 子节点，我的页为 ScrollView 内层 LinearLayout）。
    viewport = lowest_common_ancestor(cards, parent)
    if viewport is None:
        return None
    while "CardView" in viewport.attrib.get("class", "") and parent.get(viewport) is not None:
        viewport = parent.get(viewport)
    return viewport


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dump")
    parser.add_argument("--tolerance-dp", type=float, default=2.0)
    parser.add_argument("--density", type=float, default=2.625)
    parser.add_argument(
        "--expect-top",
        default=None,
        help="视口顶部第一个可见文字（完整卡片标题或区块标题）",
    )
    parser.add_argument(
        "--strict-bottom",
        action="store_true",
        help="底部有文字跨过视口时也判失败（默认只提示，因为滚动列表底部露半张卡是正常的）",
    )
    args = parser.parse_args()

    root, parent = parse_tree(args.dump)
    tolerance = round(args.tolerance_dp * args.density)

    cards = [n for n in root.iter() if "CardView" in n.attrib.get("class", "") and bounds(n)]
    if not cards:
        print("SKIP: 视口内没有 CardView，无法做卡片对齐断言")
        return 2

    viewport = find_viewport(cards, parent)
    if viewport is None or bounds(viewport) is None:
        print("FAIL: 无法定位滚动视口")
        return 1
    _, vtop, _, vbottom = bounds(viewport)

    # 只取真实文字（text），不取 contentDescription：后者常覆盖整张卡，
    # 卡片自己的 content-desc 会排在标题前面，导致误判。
    texts = [
        n for n in root.iter()
        if text_of(n) and bounds(n) and bounds(n)[1] >= vtop
    ]
    texts.sort(key=lambda n: bounds(n)[1])

    problems = []
    if not texts:
        problems.append("视口内没有可见文字，无法确认停点")
    else:
        first = texts[0]
        if args.expect_top and not text_of(first).startswith(args.expect_top):
            problems.append(
                f"顶部残留: 首个可见文字是「{text_of(first)}」"
                f"（期望「{args.expect_top}」），说明上一张卡被滚掉上半截"
            )
        card = nearest_card(first, parent)
        if card is not None:
            card_top = bounds(card)[1]
            if card_top < vtop - tolerance:
                problems.append(
                    f"顶部半截卡片: 首个文字「{text_of(first)}」所属卡片 top={card_top}，"
                    f"视口 top={vtop}（差 {vtop - card_top}px）"
                )
            else:
                print(
                    f"OK 顶部: 首个可见文字「{text_of(first)}」"
                    f"卡片 top={card_top}，视口 top={vtop}"
                    f"（间隙 {card_top - vtop}px）"
                )
        else:
            print(
                f"OK 顶部: 首个可见文字「{text_of(first)}」不属于任何卡片，"
                f"视为完整区块标题（视口 top={vtop}）"
            )

    if args.strict_bottom:
        for node in root.iter():
            b = bounds(node)
            if not text_of(node) or not b:
                continue
            if vtop <= b[1] < vbottom <= b[3]:
                problems.append(
                    f"底部半截文字「{text_of(node)}」bounds={b}，视口 bottom={vbottom}"
                )

    if problems:
        for problem in problems:
            print("FAIL:", problem)
        return 1

    # 底部只提示：滚动列表底部露出下一张卡是正常滚动暗示，切在行间隙不算缺陷。
    for node in root.iter():
        b = bounds(node)
        if not text_of(node) or not b:
            continue
        if vtop <= b[1] < vbottom <= b[3]:
            print(
                f"WARN 底部: 「{text_of(node)}」bounds={b} 跨过视口 bottom={vbottom}"
                f"（列表可继续滚动，未计入失败）"
            )
    print("PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
