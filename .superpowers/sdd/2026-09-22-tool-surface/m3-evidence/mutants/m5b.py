#!/usr/bin/env python3
"""m5b（配 m5 的**第二半**）：只回退**一处** —— `roleBucketsNeverCarryGenericWrite` 里 `externalWithGm` 块的
`.hasSize(55);` ⇒ `.hasSize(43);`（即 brief §6「第 1 处必红」那一处），**其余一律保持 M3 终态**。

为什么需要它：m5（"常量全不回退"）那一轮里，同一个测试方法**在更靠前的 GM `.hasSize(52)` 上先红**
⇒ AssertJ 在该方法里**第一次失败即抛出** ⇒ externalWithGm 那条断言**这一轮根本没执行**（"没跑到"）。
m5b 把 GM 留在 52（绿），于是唯一能红的就是 externalWithGm 那一处 ⇒ **把"被掩掉"与"没有独立牙齿"分开**。

期望：`SimosToolsTest.roleBucketsNeverCarryGenericWrite` 红，且红点**恰是 externalWithGm 那条断言**。
"""
import hashlib
import os
import sys

WT = sys.argv[1]
SIMOS = "simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java"

p = os.path.join(WT, SIMOS)
with open(p, encoding="utf-8") as handle:
    before = handle.read()
if not before.strip():
    raise SystemExit("VOID: 读到空文件 %s" % SIMOS)

OLD = ".hasSize(55);"
NEW = ".hasSize(43);"
n = before.count(OLD)
if n != 1:
    raise SystemExit("VOID: `%s` 命中 %d 次（期望恰 1 次）—— 不敢改" % (OLD, n))
after = before.replace(OLD, NEW)

# 自证：GM 那一处必须**原样留在 52**（否则又变成 m5 的掩蔽形态，白跑）
if "        .hasSize(52);" not in after:
    raise SystemExit("VOID: GM 桶 hasSize(52) 不在 —— 世界不干净")
if after == before:
    raise SystemExit("VOID: 逐字节没变 —— 这一处等于没变异")

print("  %s" % SIMOS)
print("       before md5=%s (%d B)" % (hashlib.md5(before.encode()).hexdigest(), len(before.encode())))
print("       after  md5=%s (%d B)" % (hashlib.md5(after.encode()).hexdigest(), len(after.encode())))
print("  [自证] GM 桶 .hasSize(52) 原样保留（本轮的靶子只有 externalWithGm 那一处）")
with open(p, "w", encoding="utf-8") as handle:
    handle.write(after)
print("  m5b 推送完成：只有 externalWithGm 的 .hasSize(55) ⇒ 43 这一处")
