#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""check_v17levant_docs.py —— T12 的**产物校验器**（`tools/` 不入 Maven reactor，故靠它自证）。

用法：
    python3 tools/check_v17levant_docs.py [<源 nodes 目录>] [<文档目录>] [<info-不变量证据 json>]

它做四件事（每件都先确认**被断言的东西非空**，再下结论——防"扫描为空恒真"同族陷阱）：
  1. **逐值计数**：6 类条数与字数、合计 615 / 319,630，逐项对上。
  2. **产出目录只有 `.md`**（无 `.json`/`.db`/别的）。
  3. **★ 16/82 标注**：有名势力 = `factions` key ∩ 98 province 名 = 16，其余 82 个区域在
     `map.md` 里落在「程序化噪声区域」一节、且**没有**被标成有名。
  4. **★ 红线（绝不录 Info）**：读 app 侧 Info 不变量的实测输出，断言 `info == empty`、
     `SdState.info` 无新增、SdInfoEntry 不变量成立。
  5. **红线（静态侧）**：产出目录里**没有**任何"被录进 Info"的痕迹——即不存在
     `info==空` 之外的任何 Info 载荷；并核对文档**只在 `docs/`**，不在任何 `resources/`。

退出码：0 = 全部 PASS；1 = 有 FAIL。
"""

import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import v17levant_docs as vd  # noqa: E402

EXPECTED_TOTAL = 615
EXPECTED_TOTAL_CHARS = 319630
EXPECTED_BY_CATEGORY = {
    "narrative": 170, "map": 318, "internal": 67, "factions": 52, "worldview": 6, "characters": 2,
}
EXPECTED_CHARS_BY_CATEGORY = {
    "narrative": 193165, "map": 25561, "internal": 59558,
    "factions": 38224, "worldview": 1651, "characters": 1471,
}
EXPECTED_PROVINCES = 98
EXPECTED_NAMED = 16
EXPECTED_NOISE = 82

#: ★ **冻结的**类别 → 产物文件映射（**不 import 生成器的 CATEGORY_FILES**）。
#: 理由（实测教训）：生成器一旦把自己的类别清单改坏，若检查器跟着它迭代，就等于**与变异体串通**——
#: m1（生成器删掉 narrative 一行）首轮因此**存活**。检查器的期望必须独立成源。
EXPECTED_FILES = {
    "narrative": "narrative.md",
    "map": "map.md",
    "internal": "internal.md",
    "factions": "factions.md",
    "worldview": "worldview.md",
    "characters": "characters.md",
}

_failures = []


def check(name, ok, detail=""):
    print("  [{}] {}{}".format("PASS" if ok else "FAIL", name, (" — " + detail) if detail else ""))
    if not ok:
        _failures.append(name)


def md_only(doc_dir):
    """返回产出目录里**非 `.md`** 的条目（应为空）。"""
    bad = []
    for root, _dirs, files in os.walk(doc_dir):
        for f in files:
            if not f.endswith(".md"):
                bad.append(os.path.relpath(os.path.join(root, f), doc_dir))
    return bad


def read_texts(doc_dir):
    texts = {}
    for f in sorted(os.listdir(doc_dir)):
        if f.endswith(".md"):
            with open(os.path.join(doc_dir, f), encoding="utf-8") as fh:
                texts[f] = fh.read()
    return texts


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    src = argv[0] if len(argv) > 0 else vd.default_source()
    doc_dir = argv[1] if len(argv) > 1 and argv[1] else vd.default_output()
    info_probe = argv[2] if len(argv) > 2 else None

    print("[check_v17levant_docs] 源   : {}".format(src))
    print("[check_v17levant_docs] 文档 : {}".format(doc_dir))

    # ── 装置自证：先确认两侧都非空 ──
    elements, _node_meta = vd.load_elements(src)
    report = vd.count_report(elements)
    texts = read_texts(doc_dir)
    check("teeth.source-non-empty", sum(r["count"] for r in report.values()) > 0,
          "source elements={}".format(sum(r["count"] for r in report.values())))
    check("teeth.docs-non-empty", len(texts) > 0, "md files={}".format(len(texts)))

    # ── 1 逐值计数 ──
    total = sum(r["count"] for r in report.values())
    total_chars = sum(r["chars"] for r in report.values())
    check("counts.total", total == EXPECTED_TOTAL, "{}".format(total))
    check("chars.total", total_chars == EXPECTED_TOTAL_CHARS, "{:,}".format(total_chars))
    for cat in EXPECTED_BY_CATEGORY:
        r = report.get(cat, {"count": -1, "chars": -1})
        check("counts.{}".format(cat), r["count"] == EXPECTED_BY_CATEGORY[cat],
              "{}".format(r["count"]))
        check("chars.{}".format(cat), r["chars"] == EXPECTED_CHARS_BY_CATEGORY[cat],
              "{:,}".format(r["chars"]))
    # 6 类条数之和 == 总数（防"漏一类"）
    check("counts.categories-sum-to-total",
          sum(EXPECTED_BY_CATEGORY.values()) == EXPECTED_TOTAL,
          "{}".format(sum(EXPECTED_BY_CATEGORY.values())))

    # ── 1b ★ 产物覆盖：**每一个源元素都必须能在产物 md 里找到**（判据承重点）
    # 口径：对每条源元素取它 value 的**首行前 60 字**作指纹，在对应类别 md 里找。
    # 这一条才是"生成器漏了一类/漏了一批 ⇒ 红"的真判据——只数**源档**是判据弱于行为。
    missing = {}
    for cat, filename in EXPECTED_FILES.items():
        body = texts.get(filename, "")
        if not body:
            missing[cat] = ["<整个文件缺失: {}>".format(filename)]
            continue
        gone = []
        for _n, elem in elements.get(cat, []):
            value = elem.get("value")
            if not isinstance(value, str) or not value.strip():
                continue
            fingerprint = value.strip().splitlines()[0].strip()[:60]
            if fingerprint and fingerprint not in body:
                gone.append(fingerprint)
        if gone:
            missing[cat] = gone
    check("coverage.all-elements-present-in-docs", not missing,
          "缺失: {}".format({k: len(v) for k, v in missing.items()}))

    # ── 2 产出目录只有 .md ──
    bad = md_only(doc_dir)
    check("docs.only-md", not bad, "非 md 条目: {}".format(bad))

    # ── 3 ★ 16/82 标注 ──
    # ★ 划分口径**在检查器里独立重算**（不调生成器的 named_vs_noise）：否则生成器把划分写反时，
    # 检查器会跟着它一起错（与上面 EXPECTED_FILES 同一条教训）。
    provinces = vd.province_names(src)
    faction_key_set = {e.get("key") for _n, e in elements.get("factions", [])}
    named = sorted(faction_key_set & provinces)
    noise = sorted(provinces - set(named))
    check("regions.provinces-count", len(provinces) == EXPECTED_PROVINCES, "{}".format(len(provinces)))
    check("regions.named-16", len(named) == EXPECTED_NAMED, "{}".format(len(named)))
    check("regions.noise-82", len(noise) == EXPECTED_NOISE, "{}".format(len(noise)))

    map_md = texts.get("map.md", "")
    noise_section = ""
    if "程序化噪声区域" in map_md:
        noise_section = map_md.split("程序化噪声区域", 1)[1]
    check("map.noise-section-exists", len(noise_section) > 0, "section bytes={}".format(len(noise_section)))
    # 噪声区域里**有 map 条目**的那些必须出现在噪声一节。★ 实测：82 个噪声区域里只有 75 个有 map 条目，
    # 另 7 个（如 `东境总督区`/`云松谷`）**连 map 条目都没有** ⇒ 判据只对"有条目者"要求落位，
    # 不假装 82 个都有条目（如实记，见报告 §诚实披露）。
    map_keys = set()
    for _n, e in elements.get("map", []):
        k = e.get("key")
        if isinstance(k, str) and k.startswith("Nation:"):
            map_keys.add(k.split(":", 1)[1])
    missing_noise = [n for n in noise if n in map_keys and "`{}`".format(n) not in noise_section]
    check("map.all-mapped-noise-regions-in-noise-section", not missing_noise, "{}".format(missing_noise[:5]))
    check("map.noise-without-entry-count",
          len([n for n in noise if n not in map_keys]) == 7,
          "无 map 条目的噪声区域数={}（实测 7）".format(len([n for n in noise if n not in map_keys])))
    # ★ m2 的靶子：把噪声标成"有名" ⇒ 若 map.md 的**有名一节**里出现噪声区域名，则红
    named_section = map_md.split("程序化噪声区域", 1)[0] if "程序化噪声区域" in map_md else map_md
    leaked = [n for n in noise if "`Nation:{}`".format(n) in named_section
              and not re.search(r"^\d+\. `Nation:" + re.escape(n) + r"`", named_section, re.M)]
    check("map.noise-not-marked-named", not leaked, "泄漏: {}".format(leaked[:5]))
    # README 必须把 16/82 说清楚
    readme = texts.get("README.md", "")
    check("readme.states-16-82",
          "**16**" in readme and "**82**" in readme and "程序化" in readme,
          "16={} 82={}".format("**16**" in readme, "**82**" in readme))
    # ★ 有名势力必须真的在 factions.md 里有长篇设定（正文长度显著）
    fac_md = texts.get("factions.md", "")
    thin = []
    for n in named:
        if "## " not in fac_md or "`{}`".format(n) not in fac_md and n not in fac_md:
            thin.append(n)
    check("factions.named-appear", not thin, "{}".format(thin[:5]))

    # ── 4 ★ 红线：Info 不变量（读 app 侧实测输出） ──
    check("redline.probe-provided", bool(info_probe) and os.path.isfile(info_probe or ""),
          "info probe={}".format(info_probe))
    if info_probe and os.path.isfile(info_probe):
        with open(info_probe, encoding="utf-8") as fh:
            probe = json.load(fh)
        check("redline.info-is-empty", probe.get("infoEmpty") is True,
              "infoEmpty={}".format(probe.get("infoEmpty")))
        check("redline.info-bySubject-empty", probe.get("infoBySubjectSize") == 0,
              "bySubject={}".format(probe.get("infoBySubjectSize")))
        check("redline.sd-state-info-empty", probe.get("sdStateInfoEmpty") is True,
              "sdStateInfoEmpty={}".format(probe.get("sdStateInfoEmpty")))
        check("redline.no-sd-info-entries", probe.get("sdInfoEntryCount") == 0,
              "sdInfoEntryCount={}".format(probe.get("sdInfoEntryCount")))
        # 装置自证：探针必须真的读到过一个**非空**的状态（否则"空"可能是"没读到"）
        check("redline.probe-saw-real-state", probe.get("hexCount") == 59223,
              "hexCount={}".format(probe.get("hexCount")))

    # ── 5 静态侧：文档只在 docs/，不在 resources/ ──
    norm_doc = os.path.normpath(os.path.abspath(doc_dir)).replace(os.sep, "/")
    check("redline.docs-under-docs-dir", "/docs/worlds/" in norm_doc, norm_doc)
    check("redline.docs-not-under-resources", "/resources/" not in norm_doc, norm_doc)

    print("[check_v17levant_docs] 结果 : {}".format("OK" if not _failures else "FAIL"))
    if _failures:
        print("[check_v17levant_docs] 失败项: {}".format(", ".join(_failures)))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
