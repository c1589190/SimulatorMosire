# 批 S —— 「写之后抛异常 ⇒ 报错但已落盘」（真机真跑挖出，2026-10-15）

> 来源：**批 R 的 ③ 真 LLM 真机跑**（两个门控 e2e 第一次真正跑起来）⇒ ★ **挖出一个真生产缺陷**。
> 控制方**已回代码逐行核实**。本仓自定义：**真机跑出来的缺陷最值钱**（它走的是模型真实行为，不是人猜的）。

---

## 1. 缺陷现场（`ProposeCallTool.java`）

```java
// :265 注释原文：「两者都没有 ⇒ finalIntentText = null = "不动包里已有的 intent"」← ★ null 是【有意设计】的值
String finalIntentText = topLevelIntent.orElseGet(() -> composed.isBlank() ? null : composed);
DecisionPacket packet = newPacket(..., finalIntentText, calls, ...);
// :279 ★ 包已经落盘（+1 revision）
ToolWritePort.Receipt receipt =
    writes.submitWithReceipt(branch, ..., UpsertDecisionPacketHandler.TYPE, ...);
Map<String, Object> view = new LinkedHashMap<>();
... view.put(...) 若干行 ...
if (!finalIntentText.isBlank()) {   // :294 ★ 对【有意允许的 null】不判空 ⇒ NPE
```
⇒ **NPE 发生在写之后** ⇒ 工具返回 `TOOL_ERROR`，而**包已经落盘**
⇒ ★★ **违反它自己类注写的「整次调用要么全成、要么全不成 … 世界零变化」**。

## 2. ★★ 真机后果链（批 R 真 LLM 跑，两次独立复现）

```
模型 propose 不带 intent ⇒ TOOL_ERROR（但包已落盘）
  → 模型重试同一条 ⇒ 包里出现两条 byte-identical 的 call（落点 id 由 tool+args 派生 ⇒ 同 id）
  → simos.gm.packet.execute 以【一条 revision 整批原子】执行
  → 第二条撞「家户已存在 / 单位 id 已存在」
  → ★★ 整批（六个决策人【全部】决策）被拒 ⇒ 本 tick 世界零效果
```
★ 模型自己写在包 intent 里的原话（逐字）：「**前一次提议虽报错但已落包，重试又追加了一条**」
—— ★ **模型把根因诊断得比人还准**（这正是"真机跑"不可替代的价值）。

★ **免 LLM 的确定性证明**（批 R 的仓库外探针 `ProposeNoIntentNpeProbeTest`，与 `DecisionPacketEndToEndTest`
唯一差别 = propose 不带 intent）：
```
success=false code=TOOL_ERROR message=... NullPointerException: Cannot invoke "String.isBlank()" because "finalIntentText" is null
headBefore=1 headAfter=2 packetStatus=DRAFT headDelta=1     ← ★ 报错，但 head 涨了 1
```

## 3. ★★★ 抽象出的**通用形状**（本批真正的产出）

> **`writes.submit*(...)` 之后到 `return` 之间的任何一行都可能抛；一旦抛，就把"原子"变成了"部分成功"。**

- ★ **铁律 2**（`Command → ChangeSet → Revision`）保证了**世界写**的原子性；
- ★★ 但**「写 + 组装返回视图」不在同一原子里** —— 视图组装（解引用、查表、解析、索引）**在写之后**，
  它的任何异常都会让"世界已变、调用方以为失败"。
- ⇒ ★★ **纪律**：**写之后的代码必须"不会抛"**（null-safe · 不查表取不存在的键 · 不做可能失败的解析 · 不越界），
  或者**把一切可能失败的事移到写之前**。★ 若确实要在写之后做可能失败的事 ⇒ **必须显式设计"写已发生但后续失败"的语义**
  （例如把失败折进"已提交但视图降级"），**不许**让它变成 `TOOL_ERROR`。

## 4. ★ 同一个家族：今晚的第 3 例

| # | 缺陷 | "报错但已经落了什么" |
|---|---|---|
| 1 | 4 条 catalog 工具忽略 `planOnly` | 规划阶段**真落盘**，报 `PLANNING_FAILED`，GM 重试**再落一次** |
| 2 | `gov.createOffice` / `province.apply` | 整批被拒（**0 落盘**），但视图报 `submitted: true` |
| 3 | **本批：`propose` 写后 NPE** | 报 `TOOL_ERROR`，但**包已落盘** ⇒ 重试 ⇒ 重复 call ⇒ **整 tick 决策全废** |

⇒ ★ 三例的共同根因都是：**"调用方看到的结局" 与 "世界真实发生了什么" 不一致**。
★ 这一族值得**单独扫一遍**（本批的 ② 就是干这个：扫 `submit*` 之后到 `return` 之间的可能抛点）。

## 5. 本批范围与判据

| # | 判据 |
|---|---|
| **V1** | ★ **确定性负向**：`propose` 不带 intent ⇒ **要么成功、要么干净地拒**；★ **绝不许**"`TOOL_ERROR` 而 head 涨了" |
| **V2** | 修后同一探针：`headDelta=0`；或（若修法允许 null 继续成功）`headDelta=1` 但 `code != TOOL_ERROR` |
| **V3** | 既有决策包用例保持绿 |
| **V4** | ★ **同类扫描清单**（真风险 / 假风险分开，逐条 `file:line` + 理由） |
| **V5** | `test-compile` rc=0 · spotless 干净 · 三格计数不变 |

★ **不写测试**（pin 用例归测试代理）；**不许** `try/catch` 吞 NPE（那是掩盖）。
