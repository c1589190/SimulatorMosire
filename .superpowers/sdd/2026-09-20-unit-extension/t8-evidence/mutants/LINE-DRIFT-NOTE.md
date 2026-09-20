# 行号漂移说明（T8，控制器追加，2026-09-21 07:54）

## 事实
1. 变异轮（t8m1~t8m7）跑在 **pre-spotless** 的 `UnitOperations.java` 上（锚点 md5 `eadbe492…`，见 `baseline-md5.txt`）。
2. 之后 `spotless:apply` 重排了该文件里**新增 Javadoc 的折行**，产出现状字节 md5 `4788d99f…`（见 `spotless-code-identity.txt`）。
3. ⇒ **`UnitOperations.java` 的行号整体漂移**：`applyCasualties` **178 → 140**、`copy` **816 → 792**；全文件 884 → 883 行。

## 为什么这不使变异证据作废
- **代码面逐字节相同，且被独立复核过两次**：
  - 实现者：`strip-compare.py` ⇒ `IDENTICAL`。
  - 控制器：用**另一份自己写的**去注释规范化脚本（处理 `//`、`/* */`、字符串/字符字面量内的转义）重算 ⇒ `UnitOperations.java` 去注释后 md5 `05159296…`、`ApplyCasualtiesHandler.java` `407a244c…`，**两侧各自相等**。
- **锚点没有被抹掉**：pre-spotless 的**原始字节仍在档**（`pre-spotless_UnitOperations.java`、`pre-spotless_ApplyCasualtiesHandler.java`）⇒ 任何读者都能重放。
- **承重的红点不在漂移的文件里**：t8m1/t8m2 的红点落在 `UnitOperationsTest.java:1285 / 1259 / 1344 / 1300` 等**测试文件**上，而测试文件**未被 spotless 改动**；漂移的只是 JVM **栈帧**引用（`at …UnitOperations.copy(UnitOperations.java:816)`）。
- **⑩ 道自证的锚是"片段"不是"行号"**：被改的代码片段在原件/变异体中的出现次数才是指标，行号从来不是。

## 不受影响 / 受影响
- **不受影响**：8 个变异体 9 轮的**杀/存活判定**；`restored == orig` 的逐字节还原；`CommandBus.java` 未被 spotless 动过（md5 `23a07b24…` 在 `baseline-md5.txt` 与现状**相同**，且 `git diff 851e61b HEAD` 里**没有**该生产文件）。
- **受影响（仅引用可读性）**：t8m1.log / t8m2.log 里 `UnitOperations.java:816` 应对读到 `:792`、`:178` 应对读到 `:140`。
- **另注**：`ApplyCasualtiesHandler.java` 的 baseline md5 与现状**本就相同**（`1d6b30a0…`）⇒ spotless 实际只改了 `UnitOperations.java` 一个文件；报告里「两份文件均 IDENTICAL」对 handler 而言是**恒真**（同一份字节），不是"改过之后仍相同"。

## 裁定（控制器）
**不重跑变异轮**。理由：锚点规则要护的是「**可复现**」，而此处锚点字节在档 + 代码面同一性被双重独立证明 + 承重红点在未改动文件里；重跑成本（约 15 分钟跑道）换不到新的判别力，且同族漂移在 T9/T10 还会再出现。⇒ **记档不重跑**，并把行号映射写在上文供读者对照。
