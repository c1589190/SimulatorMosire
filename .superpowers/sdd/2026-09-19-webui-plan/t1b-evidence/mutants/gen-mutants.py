#!/usr/bin/env python3
# M7b T1 变异体生成器：从**源** timeline.js 精确替换生成 m1/m2，并断言替换真的发生且字节不同。
# 用法: gen-mutants.py <源 timeline.js> <输出目录>
import hashlib
import pathlib
import sys

src_path = pathlib.Path(sys.argv[1])
out_dir = pathlib.Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)

src = src_path.read_text(encoding="utf-8")
src_md5 = hashlib.md5(src.encode("utf-8")).hexdigest()

M1_OLD = """          knob.hidden = false;
          knob.style.left = rect.left + rect.width / 2 - origin.left + origin.scrollLeft + "px";
          knob.style.top = rect.top + rect.height / 2 - origin.top + origin.scrollTop + "px";
"""
M1_NEW = """          knob.hidden = false;
"""

M2_OLD = """    var list = model.nodes[branch] || [];
    var first = list.length > 0 ? list[0] : null;
    if (!first || !first.parent || first.parent.branch === undefined || first.parent.branch === null) {
      return rev;
    }
    return (
      columnOf(first.parent.branch, Number(first.parent.revision), seen) + (rev - 1)
    );
"""
M2_NEW = """    return rev;
"""


def mutate(name, old, new):
    if src.count(old) != 1:
        raise SystemExit("[%s] 锚点匹配 %d 次（应为 1）：锚点漂了，本轮作废" % (name, src.count(old)))
    mutated = src.replace(old, new)
    if mutated == src:
        raise SystemExit("[%s] 替换后与原件逐字节相同：变异体无效" % name)
    md5 = hashlib.md5(mutated.encode("utf-8")).hexdigest()
    (out_dir / (name + ".timeline.js")).write_text(mutated, encoding="utf-8")
    print("%s orig_md5=%s mutant_md5=%s" % (name, src_md5, md5))


mutate("m1", M1_OLD, M1_NEW)
mutate("m2", M2_OLD, M2_NEW)
print("src_md5=%s" % src_md5)
