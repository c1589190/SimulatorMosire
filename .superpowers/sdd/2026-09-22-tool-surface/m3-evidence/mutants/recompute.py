#!/usr/bin/env python3
"""现场重算门禁读数 —— **只从原始日志算**，不引用任何文档里的现成数字（T8「三份文档一致地错」的教训）。

口径：
  · 模块汇总 = `[INFO] Tests run: N, Failures: F, Errors: E, Skipped: S`，**行尾没有 `-- in <类名>`**
    （带 `-- in` 的是**逐类**行，加进去会把同一个数算两遍）。
  · 正规化：`^\\[INFO\\] Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+), Skipped: (\\d+)\\s*$`
  · 两侧都自证：① 汇总行必须 > 0（读到 0 行要报「拒收」，不许静默给 0）；
                ② 逐类行里 Failures/Errors 非 0 的条数要单独数出来，与 rc 对得上。

用法: recompute.py <clean-verify 日志路径>
"""
import re
import sys

SUMMARY = re.compile(
    r"^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$"
)
PERCLASS = re.compile(r"^\[INFO\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+).*-- in ")
ERRORCLASS = re.compile(r"^\[ERROR\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+).*-- in ")


def main() -> int:
    if len(sys.argv) != 2:
        print("用法: recompute.py <日志路径>")
        return 2
    path = sys.argv[1]
    sums = []
    per_class = 0
    red_class = 0
    per_class_total = 0
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            m = SUMMARY.match(line)
            if m:
                sums.append(tuple(int(g) for g in m.groups()))
                continue
            m = PERCLASS.match(line)
            if m:
                per_class += 1
                per_class_total += int(m.group(1))
                if int(m.group(2)) or int(m.group(3)):
                    red_class += 1
                continue
            m = ERRORCLASS.match(line)
            if m:
                red_class += 1
    if not sums:
        print("拒收：一行模块汇总都没读到 —— 这个日志证明不了任何事（别把「读到空」当「读了 0」）")
        return 2
    total = sum(row[0] for row in sums)
    fails = sum(row[1] for row in sums)
    errors = sum(row[2] for row in sums)
    print("模块汇总行数 = %d （期望 8：父 POM + 7 个模块；无测试的模块不打印汇总）" % len(sums))
    print("逐模块 Tests run = %s" % "/".join(str(row[0]) for row in sums))
    print("逐模块 Failures  = %s" % "/".join(str(row[1]) for row in sums))
    print("逐模块 Errors    = %s" % "/".join(str(row[2]) for row in sums))
    print("总数 = %d, Failures = %d, Errors = %d" % (total, fails, errors))
    print("逐类行数 = %d（逐类合计 %d，与总数差额 = 未逐类打印的）" % (per_class, per_class_total))
    print("逐类行里 红 的条数（[INFO] 或 [ERROR] 形态）= %d" % red_class)
    return 0


if __name__ == "__main__":
    sys.exit(main())
