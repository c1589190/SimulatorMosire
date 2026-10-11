#!/usr/bin/env python3
"""W8 铁律 5 往返自证（只读）：把落盘 checkpoint 读回来，逐值核债务表的不变式。

本批**没有改任何状态形状**（无新组件 / 无 Codec / 无 ChangeSet 改动），故往返不变式按构造未受影响；
本脚本给出"确实读得回来且自洽"的**真读数**：
  A. JSON 全量信封可解码（铁律 5 的"读回"前提）
  B. 每条合同的 id 三个可读段 == 行内 (debtor, creditor, unit) —— 销账只换值不换身份
  C. status ∈ 合法档；FORGIVEN/SETTLED ⇒ principal == 0；principal >= 0
  D. 债务人/债权人两侧引用的家户都在 classes 表里（跨表守卫）
  E. 与逐日读数对拍：checkpoint 时点的本金合计 == 当日 debt.jsonl 行的合计
"""
import gzip
import json
import re
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from w8lib import load, mod, unit_str  # noqa: E402

HEX = re.compile(r"^debtc-([0-9a-f]+)-([0-9a-f]+)-([0-9a-f]+)-([0-9a-f]+)$")
STATUSES = {"NORMAL", "DELINQUENT", "DEFAULTED", "SETTLED", "FORGIVEN"}


def decode(part):
    try:
        return bytes.fromhex(part).decode()
    except Exception:
        return None


def check(cp_path, day, debt_jsonl=None):
    day = int(day)
    env = load(cp_path)
    ed = mod(env, "economy")
    debts = ed.get("debtContracts") or {}
    classes = ed.get("classes") or {}
    print("== %s (day=%s, revisions 可解码 OK) ==" % (cp_path, day))
    print("   合同行数 = %d ; 家户行数 = %d" % (len(debts), len(classes)))

    bad = []
    id_mismatch = 0
    granted = 0
    principal_sum = 0
    for cid, c in debts.items():
        m = HEX.match(cid)
        if not m:
            bad.append(("id-shape", cid))
            continue
        d, cr, unit = decode(m.group(1)), decode(m.group(2)), decode(m.group(3))
        if d != c.get("debtor") or cr != c.get("creditor") or unit != unit_str(c.get("unit")):
            id_mismatch += 1
            if id_mismatch <= 3:
                bad.append(("id-mismatch", cid, d, c.get("debtor"), cr, c.get("creditor"), unit, unit_str(c.get("unit"))))
        p = c.get("principal")
        if p is None or p < 0:
            bad.append(("principal-negative-or-missing", cid, p))
        st = c.get("status")
        if st not in STATUSES:
            bad.append(("bad-status", cid, st))
        if st in ("FORGIVEN", "SETTLED") and p != 0:
            bad.append(("settled-with-principal", cid, st, p))
        if st == "FORGIVEN":
            granted += 1
        if p > 0:
            principal_sum += p
        if c.get("debtor") not in classes:
            bad.append(("debtor-not-in-classes", cid, c.get("debtor")))
        if c.get("creditor") not in classes:
            bad.append(("creditor-not-in-classes", cid, c.get("creditor")))
    print("   B id 三段 == 行内 (debtor, creditor, unit)：不一致 %d 条" % id_mismatch)
    print("   C FORGIVEN 合同数 = %d ; 全部 principal > 0 合计 = %d" % (granted, principal_sum))
    print("   D 跨表引用缺失 = %d" % sum(1 for b in bad if "classes" in b[0]))
    if debt_jsonl and os.path.exists(debt_jsonl):
        total = 0
        with gzip.open(debt_jsonl, "rt") as f:
            for line in f:
                r = json.loads(line)
                if r["d"] == day:
                    total += r["p"]
        print("   E 与逐日读数对拍 day=%s：checkpoint 合计=%d，debt.jsonl 合计=%d ⇒ %s"
              % (day, principal_sum, total, "逐值相同" if total == principal_sum else "不符"))
    print("   异常项 %d 条%s" % (len(bad), ("" if not bad else "：" + repr(bad[:5]))))
    return len(bad)


if __name__ == "__main__":
    rc = 0
    for arg in sys.argv[1:]:
        parts = arg.split(",")
        rc += check(parts[0], parts[1], parts[2] if len(parts) > 2 and parts[2] else None)
    print("[check-rc=%d]" % rc)
    sys.exit(0 if rc == 0 else 1)
