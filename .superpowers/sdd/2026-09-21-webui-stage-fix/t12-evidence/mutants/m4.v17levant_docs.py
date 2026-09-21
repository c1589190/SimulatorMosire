#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""v17levant_docs.py —— T12 的**文档产出器**（离线，标准库 only，`tools/` 不入 Maven reactor）。

把 `v17levant` 存档里 `checkpoints[].elements[]` 的**丰富文本**整合成 `docs/worlds/v17levant/` 下的
`.md` 文件，供**另一个 Agent 阅读**。

★★ 铁律（T12 红线）：**绝不写进 `InfoSystem` / `SdInfoEntry`**。
本脚本**不 import** 任何 Java/simos 类型、不接触 `simos.db`、不 `submit` 命令、不调任何 API；
只读 node JSON、只写 `.md` 文件。产出目录里**只有 `.md`**（校验脚本会断言）。

用法：
    python3 tools/v17levant_docs.py [<源 nodes 目录>] [<输出目录>]

缺省：
    源   = ~/DevMosire/testspace/worlds/v17levant_2/nodes   （**信息最全副本**：n0000~n0012）
    输出 = docs/worlds/v17levant                            （D11）

退出码：0 = 全部写出；1 = 源档缺失/无 checkpoint（fail-closed，绝不产出空文档）。
"""

import json
import os
import re
import sys

# 变异体：真的把符号引进来（结构性红线）
from io.mosire.simos.util.info import InfoSystem  # noqa: F401

#: D11：按 checkpoint 类别分文件（6 类，实测）。
#: (类别名, 输出文件名, 中文标题, 说明)
CATEGORY_FILES = [
    ("worldview", "worldview.md", "世界观", "各势力的自我认知 / 世界背景设定（6 条，仅 `n0000`）"),
    ("characters", "characters.md", "角色与编制", "登场角色与个别军事编制条目（2 条，仅 `n0000`）"),
    ("factions", "factions.md", "势力设定", "各势力的长篇设定（首都 / 体制 / 统治者 / 国教 / 兵力…）"),
    ("narrative", "narrative.md", "叙事", "各回合计 170 条叙事 / 推文（最丰富：193,165 字）"),
    ("internal", "internal.md", "内部记录", "GM/内部视角的剧情提示与军事编制（67 条）"),
    ("map", "map.md", "地图与区域", "98 个区域的程序化条目 + 16 个有名势力的说明（318 条）"),
]

#: 判据（spec §五.4 实测值，当场写死作对拍基准）。
EXPECTED_TOTAL = 615
EXPECTED_BY_CATEGORY = {
    "narrative": 170, "map": 318, "internal": 67, "factions": 52, "worldview": 6, "characters": 2,
}
EXPECTED_CHARS_BY_CATEGORY = {
    "narrative": 193165, "map": 25561, "internal": 59558,
    "factions": 38224, "worldview": 1651, "characters": 1471,
}
EXPECTED_TOTAL_CHARS = 319630

NODE_FILE = re.compile(r"^n\d{4}\.json$")


# ────────────────────────── 读取 ──────────────────────────

def node_files(nodes_dir):
    """按 nodeId 升序返回 node JSON 文件名（排除 `*_map*.json` / `contour.json` / Zone.Identifier）。"""
    names = [n for n in os.listdir(nodes_dir) if NODE_FILE.fullmatch(n)]
    return sorted(names)


def load_elements(nodes_dir):
    """读全部 node 的 checkpoint 文本。

    返回 `(elements, node_meta)`：
      - `elements` = `{category: [(node, elem), …]}`，保持源档顺序（类别内按节点、节点内按原序）；
      - `node_meta` = `{node: {"turn":…, "worldTime":…, "nodeId":…}}`，供索引/分节用。

    ★ fail-closed：源档不存在或一条 checkpoint 都没有 ⇒ 抛 `RuntimeError`（不产出空文档）。
    """
    if not os.path.isdir(nodes_dir):
        raise RuntimeError("源 nodes 目录不存在: {}".format(nodes_dir))
    files = node_files(nodes_dir)
    if not files:
        raise RuntimeError("源目录里没有任何 `n####.json`: {}".format(nodes_dir))

    elements = {cat: [] for cat, _fn, _t, _d in CATEGORY_FILES}
    node_meta = {}
    for name in files:
        with open(os.path.join(nodes_dir, name), encoding="utf-8") as fh:
            doc = json.load(fh)
        node = os.path.splitext(name)[0]
        node_meta[node] = {
            "nodeId": doc.get("nodeId"),
            "turn": doc.get("turn"),
            "worldTime": doc.get("worldTime"),
            "parentId": doc.get("parentId"),
        }
        for cat, checkpoint in (doc.get("checkpoints") or {}).items():
            if cat not in elements:
                elements[cat] = []  # 未知类别：不静默丢，挂在同名新桶里（当前实测不存在）
            for elem in checkpoint.get("elements") or []:
                elements[cat].append((node, elem))

    if sum(len(v) for v in elements.values()) == 0:
        raise RuntimeError("源档里一条 checkpoint element 都没有: {}".format(nodes_dir))
    return elements, node_meta


# ────────────────────────── 统计与划分 ──────────────────────────

def char_count(text):
    """计"字"= 该元素 `value` 的字符数（与 spec §五.4 口径一致：193,165 等）。"""
    return len(text) if isinstance(text, str) else 0


def count_report(elements):
    """逐类别返回 `{cat: {"count": n, "chars": m}}`。"""
    report = {}
    for cat in elements:
        report[cat] = {
            "count": len(elements[cat]),
            "chars": sum(char_count(e[1].get("value")) for e in elements[cat]),
        }
    return report


def faction_keys(elements):
    """`factions` 类别的全部 key（含重复——同一势力在多节点被多次设定）。"""
    return [e.get("key") for _n, e in elements.get("factions", [])]


def province_names(nodes_dir):
    """从 `n0000_map.json` 读 98 个 region（province）名；无档 ⇒ 空集（诚实降级，不假装有）。"""
    path = os.path.join(nodes_dir, "n0000_map.json")
    if not os.path.isfile(path):
        return set()
    with open(path, encoding="utf-8") as fh:
        doc = json.load(fh)
    return set((doc.get("provinces") or {}).keys())


def named_vs_noise(elements, provinces):
    """★ **16 有名 / 82 噪声**的划分。

    口径（spec §五.4）：有名势力 = `factions` 元素的 key ∩ 98 个 province 名。
    """
    named = sorted(set(faction_keys(elements)) & set(provinces))
    noise = sorted(set(provinces) - set(named))
    return named, noise


def element_key(elem, index):
    """元素的稳定锚：`key` 为空白时退化为 `（无 key #index）`，绝不留空。"""
    key = elem.get("key")
    if isinstance(key, str) and key.strip():
        return key
    return "（无 key #{:d}）".format(index)


# ────────────────────────── 写出 ──────────────────────────

def md_escape(text):
    """把文本原样放进 md 里：只做**缩进安全**处理（正文不转义，保真优先）。"""
    return text if isinstance(text, str) else json.dumps(text, ensure_ascii=False)


def write_index(out_dir, elements, node_meta, named, noise, report):
    """总览 `README.md`：给"另一个 Agent"的入口——规模、划分、逐类别导航。"""
    total = sum(r["count"] for r in report.values())
    total_chars = sum(r["chars"] for r in report.values())
    lines = [
        "# v17levant —— 世界文档（T12 产出）",
        "",
        "> ★ 本文由 `tools/v17levant_docs.py` 从存档 `checkpoints[].elements[]` **离线导出**，"
        "**不是** `InfoSystem` / `SdInfoEntry` 的数据（见下「红线」）。",
        "> 源档：`~/DevMosire/testspace/worlds/v17levant_2/nodes/`（**信息最全副本**，节点 `n0000`~`n0012`）。",
        "",
        "## 红线：这些文本**没有**进 Info",
        "",
        "本目录是**纯文件产出**。导出器不 `import` 任何 simos 类型、不接触 `simos.db`、不 `submit` 命令；"
        "产出目录里**只有 `.md`**。游戏状态里的 `info` 段（`InfoSystem`）与 SDSimos 的 `SdInfoEntry` "
        "**一个都没有被写入**——这两条各有独立判据与变异体（见 `t12-evidence/` 与 `t12-report.md`）。",
        "",
        "## 规模（逐值，实测）",
        "",
        "| 项 | 值 |",
        "|---|---|",
        "| checkpoint 元素总数 | **{}** |".format(total),
        "| 文本总字数 | **{:,}** |".format(total_chars),
        "| 节点数 | **{}**（`n0000`~`n0012`；`n0008` 无 checkpoint） |".format(len(node_meta)),
        "| 区域（province）总数 | **{}** |".format(len(named) + len(noise)),
        "| ★ **有名有姓的势力**（有长篇设定） | **{}** |".format(len(named)),
        "| ★ **程序化噪声区域**（只有模板化短条目） | **{}** |".format(len(noise)),
        "",
        "## ★★ 先读这一条：98 个区域里只有 16 个有资料",
        "",
        "用户口中的「每一个区域」= 下面这 **{} 个有名有姓的势力**。".format(len(named)),
        "**其余 {} 个是程序化生成的噪声区域**（名如 `区域NNN`，只有 `map.md` 里那种"
        "「名称/Tag/格数/颜色/地形构成」的模板化短条目），**没有任何深耕设定**。".format(len(noise)),
        "⇒ **不要**以为 98 个区域都有资料。",
        "",
        "### 这 {} 个有名势力".format(len(named)),
        "",
    ]
    lines += ["- `{}`".format(n) for n in named]
    lines += [
        "",
        "### 那 {} 个噪声区域（无长篇文本）".format(len(noise)),
        "",
        "名如 `区域NNN` 的占多数，另有少量**有名字但无设定**的（如 `东岗伯国`、`南境伯国`）。"
        "完整列表与它们的模板化条目见 `map.md` 的「程序化噪声区域」一节。",
        "",
        "## 文件导航",
        "",
        "| 文件 | 类别 | 条数 | 字数 | 内容 |",
        "|---|---|---|---|---|",
    ]
    for cat, filename, title, desc in CATEGORY_FILES:
        r = report.get(cat, {"count": 0, "chars": 0})
        lines.append("| [`{}`]({}) | `{}` | {} | {:,} | {} |".format(
            filename, filename, cat, r["count"], r["chars"], desc))
    lines.append("")
    lines.append("合计 **{}** 条 / **{:,}** 字。".format(total, total_chars))
    lines.append("")
    lines += [
        "## 阅读顺序建议（给另一个 Agent）",
        "",
        "1. `worldview.md` —— 先建立世界背景与各文明的自我认知（最短，6 条）。",
        "2. `factions.md` —— 16 个有名势力的政体 / 首都 / 统治者 / 国教 / 兵力。",
        "3. `characters.md` —— 登场角色（含 1 条军事编制）。",
        "4. `narrative.md` —— 按节点 / 回合分节的叙事主线（最长，193k 字）。",
        "5. `internal.md` —— GM 视角的剧情提示与军事编制（含未来剧情预告，适合做推演依据）。",
        "6. `map.md` —— 98 区域的地图条目；**末节明确标出 82 个噪声区域**。",
        "",
    ]
    _write(out_dir, "README.md", lines)


def write_category(out_dir, cat, filename, title, desc, elements, named, noise, node_meta):
    """按类别写一个 `.md`：`narrative` 按节点分节，`map` 另附 16/82 标注，其余逐条。"""
    lines = [
        "# {}".format(title),
        "",
        "> 类别 `{}` ｜ {} ｜ 共 **{}** 条。".format(cat, desc, len(elements[cat])),
        "> 文本原样摘自 `checkpoints[].elements[].value`（未改写、未摘要）。",
        "",
    ]
    index = 0
    if cat == "narrative":
        for node in sorted({n for n, _e in elements[cat]}):
            meta = node_meta.get(node, {})
            chunk = [(n, e) for n, e in elements[cat] if n == node]
            lines.append("## 节点 `{}`（turn {}，worldTime {}）—— {} 条".format(
                node, meta.get("turn"), meta.get("worldTime"), len(chunk)))
            lines.append("")
            for n, elem in chunk:
                index += 1
                lines.append("### {}. {}".format(index, element_key(elem, index)))
                lines += _elem_body(elem)
    elif cat == "map":
        by_key = {}
        for _n, elem in elements[cat]:
            by_key.setdefault(element_key(elem, 0), []).append(elem)
        named_set, noise_set = set(named), set(noise)
        order = [k for k in sorted(by_key, key=_map_sort_key)]
        named_keys = [k for k in order if _region_of(k) in named_set]
        noise_keys = [k for k in order if _region_of(k) in noise_set]
        other_keys = [k for k in order if k not in named_keys and k not in noise_keys]
        lines += [
            "## ★ 有名势力的地图条目（{} 键）".format(len(named_keys)),
            "",
            "这些区域在 `factions.md` 里有长篇设定。",
            "",
        ]
        for k in named_keys:
            index += 1
            lines.append("### {}. `{}`".format(index, k))
            for elem in by_key[k]:
                lines += _elem_body(elem)
        lines += [
            "## ★★ 程序化噪声区域（{} 个区域）——**无长篇文本**".format(len(noise)),
            "",
            "以下区域的条目由程序生成（名称 / Tag / 格数 / 颜色 / 地形构成），"
            "**在 `factions.md` 里没有对应设定**。不要把它们当成有资料的势力。",
            "",
            "> ★ 实测：这 {} 个噪声区域里只有 **{} 个**在本档里有上述模板化条目；"
            "另 **{} 个**（如 `东境总督区`、`云松谷`）**连 map 条目都没有**——它们在存档里只出现于"
            " `provinces` 名单。如实记，不假装 82 个都有条目。".format(
                len(noise), len(noise_keys), len(other_keys)),
            "",
        ]
        for k in noise_keys:
            index += 1
            lines.append("### {}. `{}`".format(index, k))
            for elem in by_key[k]:
                lines += _elem_body(elem)
        if other_keys:
            lines += ["## 其他地图条目（{} 键）".format(len(other_keys)), ""]
            for k in other_keys:
                index += 1
                lines.append("### {}. `{}`".format(index, k))
                for elem in by_key[k]:
                    lines += _elem_body(elem)
    else:
        for node, elem in elements[cat]:
            index += 1
            lines.append("## {}. {}".format(index, element_key(elem, index)))
            lines.append("")
            lines.append("> 节点 `{}`".format(node))
            lines += _elem_body(elem)
    _write(out_dir, filename, lines)


def _elem_body(elem):
    """一条元素的正文：元信息 + 原文。"""
    out = []
    tags = elem.get("tags") or []
    if tags:
        out.append("> tags: {}".format(" ".join("`{}`".format(t) for t in tags)))
    value = elem.get("value")
    out.append("")
    out.append(md_escape(value))
    out.append("")
    return out


def _region_of(key):
    """从 map 条目 key（形如 `Nation:奥斯曼帝国`）取区域名。"""
    if isinstance(key, str) and ":" in key:
        return key.split(":", 1)[1]
    return key


def _map_sort_key(key):
    """排序：有名区域优先（按名），其次噪声（`区域NNN` 按数字），最后其余。"""
    return (0, key) if not re.fullmatch(r"Nation:区域\d+", key or "") else (
        1, int(re.sub(r"\D", "", key)))


def purge_stale(out_dir):
    """删掉输出目录里的旧 `.md`（保留非 md 是不该发生的，会被校验器抓）。

    产物要对**源档 + 本脚本版本**是纯函数：不清旧文件的话，本轮漏写的类别会留下上一轮的副本，
    覆盖类判据（`coverage.*`）就被陈旧产物遮蔽——T12 的 m1 首轮正是这样存活的。
    """
    if not os.path.isdir(out_dir):
        return
    for name in os.listdir(out_dir):
        if name.endswith(".md"):
            os.remove(os.path.join(out_dir, name))


def _write(out_dir, filename, lines):
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, filename)
    with open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write("\n".join(lines).rstrip() + "\n")


# ────────────────────────── 入口 ──────────────────────────

def default_source():
    return os.path.expanduser("~/DevMosire/testspace/worlds/v17levant_2/nodes")


def default_output():
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.join(os.path.dirname(here), "docs", "worlds", "v17levant")


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    src = argv[0] if len(argv) > 0 else default_source()
    out = argv[1] if len(argv) > 1 else default_output()

    elements, node_meta = load_elements(src)
    report = count_report(elements)
    provinces = province_names(src)
    named, noise = named_vs_noise(elements, provinces)

    # ★ 先清掉输出目录里的旧 `.md`：产物必须是**源档的纯函数**，否则"上一轮留下的文件"
    # 会让"本轮漏写一类"看起来仍然完整（判据被陈旧产物遮蔽——这是本任务 m1 首轮存活的原因）。
    purge_stale(out)

    print("[v17levant_docs] 源   : {}".format(src))
    print("[v17levant_docs] 输出 : {}".format(out))
    print("[v17levant_docs] 节点 : {} 个".format(len(node_meta)))
    for cat, filename, _t, _d in CATEGORY_FILES:
        r = report.get(cat, {"count": 0, "chars": 0})
        print("[v17levant_docs]   {:<10} -> {:<14} {} 条 / {:,} 字".format(
            cat, filename, r["count"], r["chars"]))
    total = sum(r["count"] for r in report.values())
    print("[v17levant_docs] 合计 : {} 条 / {:,} 字".format(
        total, sum(r["chars"] for r in report.values())))
    print("[v17levant_docs] 区域 : {} 个（有名 {} / 噪声 {}）".format(
        len(provinces), len(named), len(noise)))

    write_index(out, elements, node_meta, named, noise, report)
    for cat, filename, title, desc in CATEGORY_FILES:
        write_category(out, cat, filename, title, desc, elements, named, noise, node_meta)

    print("[v17levant_docs] 写出 : {} 个 md".format(len(CATEGORY_FILES) + 1))
    return 0


if __name__ == "__main__":
    sys.exit(main())
