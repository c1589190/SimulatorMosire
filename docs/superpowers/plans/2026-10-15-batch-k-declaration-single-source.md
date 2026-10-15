# 批 K 约束设计书 —— 「让声明成为唯一真相」（① 根治版 + ⑤ 合并）

> 用户 2026-10-15 裁定：「**肯定选根治啊，1和5并一起我觉得问题不大，准备一下开始实现吧**」
> 执行顺序（用户 2026-10-15）：**⑤(含①) → ② 旧招牌 → ④ 零件 → ③ 真 LLM（最后）**
> 相关：`docs/superpowers/plans/2026-10-15-tool-three-categories.md`（三分类 + 3a/3b）·
> `docs/superpowers/status/2026-10-15-gm-dm-tool-classes-progress.md`（存档与未完成清单）

---

## 0. 用户原话附录（逐字）

> 「1 不是，这玩意不是在程序代码上已经写死了吗，就是有DM标签的工具，其中DM/GM混合就是要交给GM审核，DM完全可用就是DM随便用的意思，为啥额外要张表？」
> 「5 我认为应该最先改，没有好的设计哪来好的代码」
> 「肯定选根治啊，1和5并一起我觉得问题不大，准备一下开始实现吧」

★ 口径（用户原话的机制化）：
| 用户说的 | 代码里的对应 |
|---|---|
| 「有 DM 标签的工具」 | `roles()` 含 `ToolRole.DM` |
| 「DM/GM 混合就是要交给 GM 审核」 | **类2 `DM_APPROVAL`** = `roles()` 含 DM **且** `gate()` = `Ask` |
| 「DM 完全可用就是 DM 随便用的意思」 | **类3 `DM_FREE`** = `roles()` 含 DM **且** `gate()` = `ALLOW` |
| 「为啥额外要张表」 | ★ **`ProposalCatalog` 的手写名单是冗余的第二处声明** |

## 1. 病根（①⑤ 是同一个病）

**「第二处声明会漂移」** —— 本段实测四例：
1. `ProposalCatalog` 的手写名单：**原始 7 条全是类1 GM-only**（`RaiseUnit` / `LevyRegion` / `GovRecruit` /
   `GovRetireStaff` / `SocialHouseholdMembers` / `GovSelectExaminees` / `GovDispatchTeam`）⇒ **按用户口径它们不该在"DM 可提议"名单里**，而没有任何东西拦它。
2. `description()` 撒谎：`ga.CreateDecisionMaker` 声称 `affiliation` 必填 ⇒ **`createOffice` 与 `province.apply` 两个工具整体 0 落盘**。
3. `AGENTS.md` 边界表 **漏列** `simos-gov` / `simos-unit` / `simos-social` 的 `simos-ga-spi`（实测三个 pom 都声明了）。
4. 真 LLM 门控 e2e 引用 10 处改名前的工具名（死角第 4 次）。

## 2. 现状（回代码核过，给 `file:line`）

| 事实 | 位置 |
|---|---|
| `SimosTool` / `ToolRole` 住 **`simos-ga-spi`** | `simos-ga-spi/src/main/java/io/mosire/simos/ga/spi/` |
| ★ **`simos-gov` / `simos-unit` / `simos-social` 的 pom 都声明了 `simos-ga-spi`** | 三个 `pom.xml`（逐个 grep 核过）⇒ **域模块本来就能实现 ga-spi 里的接口** |
| `DecisionProposable` 住 **`simos-ga`**，**全仓只有 `ProposalCatalog` 自己实现它**（内部 `Entry` 适配器） | `simos-ga/.../ga/decision/DecisionProposable.java` · `ProposalCatalog.java:471` |
| 它带：`toolName()` / `tool()` / `skipKeys()` / `createsUnit()` | 同上（接口类注：`skipKeys`/`createsUnit` 是**每条描述自己的配置**） |
| catalog 手写名单 **16 条**（7 条类1 + 9 条类2），`entry(tool, skipKeys, createsUnit)` | `ProposalCatalog.java:167-214` |
| 类2/类3 的**权威分类**已由 `ToolClassAudit` 在装配期从 `(roles(), gate())` 纯派生 | `simos-ga/.../ga/tools/ToolClassAudit.java`（批 3a） |
| ★ ⑤-15：「需 GM 在审批面点头」**9 处** | `simos-app` 1 · `simos-ga` 2 · `simos-gov` 6 |
| ★ ⑤-16：错误正文写 `govUnitId`，而两条 policy 工具的实际键不同 | `simos-gov/.../tools/GovToolSupport.java`（"govUnitId 必填（GM 调用必须显式点名…）"） |
| ★ ⑤-17：假断言 | `simos-app/.../tools/SimosToolSource.java`（★ **行号已漂，按内容找**：声称某命令打 `DmUsableCommand`，实为仅 GM） |

## 3. 设计（根治版的确切形态）

### 3.1 `DecisionProposable` 搬到 `simos-ga-spi`，改成**工具自己实现**的接口

```java
// simos-ga-spi/.../ga/spi/DecisionProposable.java  ★ 新家
public interface DecisionProposable {
  /** 目标提取时整键跳过的字段（创建型工具的新 id 等）。 */
  default Set<String> skipKeys() { return Set.of(); }
  /** 是否创建型：为真时目标提取还会跳过与预览里 newUnitId 逐字相等的单位 id。 */
  default boolean createsUnit() { return false; }
}
```

★ **删掉 `toolName()` 与 `tool()`**：工具自己实现 ⇒ `toolName()` 就是它的 `name()`、`tool()` 就是 `this`，两者都是**冗余**。
★ **`preview` / `targets` 的逻辑留在 `ProposalCatalog`**：那是**通用**的（跑真 `preview=true`、从 args+preview 递归提目标），
不是每条工具的知识 ⇒ `Entry` 内部适配器**保留**，改成"持有工具实例 + 从工具读那两个开关"。

⇒ **一句话**：**"我能不能被提议"由工具自己声明（实现这个接口）；"怎么提议、怎么提目标"由 catalog 一处实现。**

### 3.2 `ProposalCatalog` 的名单**从工具面派生**（手写 `List.of(...)` 删除）

装配期：遍历**决策人工具面**，收集**实现了 `DecisionProposable`** 的工具实例 ⇒ 逐个包成 `Entry`。
★ 工具面由 `SimosToolSource` 装配期构建（catalog 本就由它构造）⇒ 实例现成的。

### 3.3 ★★ 装配期 fail-closed 审计（照 `ToolClassAudit` 同址同制，放 `simos-app`）

| # | 判据 | 违反时 |
|---|---|---|
| **C1** | 每个 `DecisionProposable` 实现者的 `roles()` **必须含 `ToolRole.DM`** | **装配期抛** `IllegalStateException`（含工具名 + 实际 `roles()`） |
| **C2** | 实现者必须**在工具面上**（否则取不到实例） | 装配期抛 |
| **C3** | 源码扫描：`ProposalCatalog` 里**没有第二处手写工具名清单** | 用例红（照 `ProposalCatalogPlanOnlyScanTest` 形制） |

★★ **C1 就是用户口径的落地**：「没有 DM 标签的工具**不该出现在提议名单里**」——以前没人拦，现在**装配期当场拦**。

### 3.4 ⑤ 的文案修正（与 ① 同一个病，一批做）

| # | 改什么 |
|---|---|
| ⑤-15 | 「需 GM 在审批面点头」**9 处**：★ 只对 **DM 直写**成立（走 `propose` 是 success、`asks` 不增，批 3b §11.2 B3 实测）⇒ 逐处改成**逐路径写清** |
| ⑤-16 | `GovToolSupport` 的「`govUnitId` 必填」：对两条 policy 工具调用者实际要写的键不同 ⇒ **按工具分叉**报文（或写明两个键名） |
| ⑤-17 | `SimosToolSource` 那处**假断言**（声称打 `DmUsableCommand`，实为仅 GM）⇒ 按事实改。★ **行号会漂，按内容找** |
| ★ 新增 | `AGENTS.md` §三 边界表 + `docs/agents/01-〇` 同名行：**补 `simos-ga-spi`** 给 `simos-gov` / `simos-unit` / `simos-social`（实测三个 pom 都声明了）—— ★ 由**控制方**改（docs 不占实现责任区） |

## 4. ★ 已裁定（用户 2026-10-15）：**甲 —— 漏打 DM 标签，给这 7 条补上**

**裁定**：`RaiseUnit` · `LevyRegion` · `GovRecruit` · `GovRetireStaff` · `SocialHouseholdMembers` ·
`GovSelectExaminees` · `GovDispatchTeam` —— **它们本来就该是 DM 能提议的（决策人的核心动作），当年只是漏了标签**。

**决定性事实（控制方回代码核过，它决定了为什么不能选乙）**：
| 动作 | 命令面里有对应命令吗 | 若移出名单，DM 还能做吗 |
|---|---|---|
| 招吏 / 辞退 | ✅ `unit.RecruitStaff` · `unit.DismissStaff` | 能（走决策令） |
| 征税 | `unit.SetTaxRate` 有，但 **`levyRegion` 是工具独有** | ★ 不能 |
| **造兵** | ❌ **没有 `unit.RaiseUnit`**（`RaiseUnitTool` 是**工具独有**） | ★ **不能** |

**落地**：给这 7 条补 `roles={DM,GM}`。它们**本来就是 `Ask`** ⇒ **自动成为类2**（`DM_APPROVAL`）——
与用户口径「**DM/GM 混合就是要交给 GM 审核**」**完全一致**。

★★ **口径变更（已获用户点头）**：
```
类1 GM_ONLY     140 → 133
类2 DM_APPROVAL  17 →  24
类3 DM_FREE      37 →  37（不变）
```
⇒ ★ **必须同步改 `ToolClassAuditAssemblyTest` 的三格锚点**，并在提交信息里写明这是**用户裁定后的新口径**。

**未选的乙/丙，及其代价（留痕）**：
- **乙**（全移出）：DM **彻底失去造兵与征税**（命令面无对应命令 ⇒ 决策令路径也走不了）。
- **丙**（逐条定）：最精确但工作量最大；用户裁定**不必**（口径统一为甲）。

## 5. 不变量（C 系列）

| # | 不变量 |
|---|---|
| **K1** | `ProposalCatalog` 的名单**唯一来源** = 工具面里实现了 `DecisionProposable` 的工具；**不得有第二处手写清单** |
| **K2** | `DecisionProposable` 的实现者 `roles()` **必须含 DM**（装配期 fail-closed） |
| **K3** | `preview` / `targets` 的**实现只有一处**（`ProposalCatalog` 内部） |
| **K4** | **零口径变更**（除 §4 裁定后明确的变更）：`roles()`/`gate()` 的改动**只允许**发生在 §4 裁定的那 7 条上；其余工具**一个字不动** |
| **K5** | 三格计数与 `ToolClassAudit` 的锚点**自洽**：改后 `140/17/37` 或 §4 裁定后的新值，两者必须一起改并说明 |

## 6. 验收判据（★ 含负向）

| # | 判据 |
|---|---|
| **V1** | catalog 成员 = 工具面里 `DecisionProposable` 的实现者（**逐条比对**，不重不漏） |
| **V2** | ★ **负向**：造一条 `roles()={GM}` 且实现 `DecisionProposable` 的假工具 ⇒ **装配期抛**（含工具名 + roles 原文） |
| **V3** | ★ **负向**：实现者不在工具面上 ⇒ 装配期抛 |
| **V4** | `ProposalCatalog` 源码扫描：**0 处工具名字面量** |
| **V5** | 既是 `propose` 又走 `gm.packet.execute` 的端到端链**仍然恰好一条 revision**（既有 `DecisionPacketEndToEndTest` 保持绿） |
| **V6** | ⑤-15 的 9 处描述**逐处**读回（从生产工具实例 dump），且**与两条路径的实测行为一致**（GM 面直调 ⇒ `APPROVAL_DENIED asks=1`；`propose` ⇒ success `asks` 不增） |
| **V7** | ⑤-16 的报文对**两条 policy 工具**给出**正确键名**（负向：给错键 ⇒ 报文能指路） |
| **V8** | `AGENTS.md` 边界表与三个 pom **逐条对账**（含 `simos-ga-spi`） |

## 7. 文件所有权（一文件一 owner）

| 范围 | 归属 |
|---|---|
| `simos-ga-spi/.../ga/spi/DecisionProposable.java`（新） | **批 K 实现代理** |
| `simos-ga/.../ga/decision/DecisionProposable.java`（删） | 同上 |
| `simos-app/.../decision/ProposalCatalog.java` | 同上 |
| 实现接口的**工具类**（`simos-unit` / `simos-social` / `simos-gov` 里那些） | 同上 |
| 新增审计类 + 接线（`Shell.java` 一行） | 同上 |
| ⑤-15 / ⑤-16 / ⑤-17 的文案 | 同上（**与 ① 同批**） |
| `AGENTS.md` + `docs/agents/01-〇` 边界表 | ★ **控制方**（docs 不占实现责任区） |
| `src/test/**` | ★ **测试代理**（最后单独一批） |

## 8. 明确不做

- **不改**任何 `gate()`；**不改**审批链；**不动** `APPROVAL_TIMEOUT`。
- **不改** `ToolClassAudit` 的判据（只在其计数随 §4 裁定变化时同步锚点）。
- **不动** `src/test/**`。
- **不动** ② 旧招牌（`simos.sd.*` 9 条 / `Sd*` 18 类 / `/api/sd/` 72 处）—— 那是**下一批**。
