# T4 报告 —— 编制命令 B（子树迁移 + 拆合）

- 分支：`ue/t4`（worktree `.claude/worktrees/uet4`）
- 基线：`9747448616445e22b86966360e77bb53549d5138`（`rev-parse HEAD` 实测**逐字相同**，非陈旧基线）
- 证据目录：`.superpowers/sdd/2026-09-20-unit-extension/t4-evidence/`（29 个文件）
- 本报告的口径：**「我验过了」= 当场跑过、有留痕可指**；**「我记得是这样」/「我推出来的」= 单独标注**。
  两者不混写。文中每个数字都能在 `logs/` 或 `mutants/` 里找到对应的字节。

---

## 一、交付物与字节

| 文件 | 行数 | md5 | 状态 |
|---|---|---|---|
| `simos-unit/.../unit/ops/UnitOperations.java` | 528 | `09840d49510efa4b17c36e06aa23d9c6` | 修改（+138） |
| `simos-unit/.../unit/spi/ReparentSubtreeHandler.java` | 48 | `f4b82888ab81cf39b6ad27e0d4e361d9` | 新增 |
| `simos-unit/.../unit/spi/SplitFormationHandler.java` | 52 | `83feefb4ebbd6fadf2a18a08213f9577` | 新增 |
| `simos-unit/.../unit/spi/MergeFormationHandler.java` | 46 | `bde0f8fef62488fc2a7cb2ee89b65e81` | 新增 |
| `simos-unit/.../unit/spi/UnitPayloads.java` | — | `9cfd978ada1cdafc7b046f6bbf76730c` | 修改（+16，`requireTextArray`） |
| `simos-unit/.../unit/ops/UnitOperationsTest.java` | — | `4011c2ac55e7b50d24065152052487ba` | 修改（+259） |
| `simos-unit/.../unit/spi/UnitCommandHandlersTest.java` | — | `e5d901d2c822b1390f9018fa47ce8f6f` | 修改（+241） |

`git diff --stat`：4 文件 `+648 / -6`；三个新 handler 共 146 行。
**五个 main 文件的 md5 与 `mutants/baseline-md5.txt` 逐字节相同**（收口 `md5sum -c` 全 OK，见 `logs/rounds-summary.txt:18-23`）。

新 handler 一律照 T3 形制：取 `state.meta().timestamp()`、经 op 产 `UnitChangeSet.between(...)`、把
`IllegalArgumentException` 折成 `HandlerOutcome.Rejected`；载荷解析复用 `UnitPayloads`。

★ **未注册进 `Shell`**：12 个 handler 的装配与 catalog 归 **T9**，本任务**不做**。

---

## 二、门禁实测值（模块级，前台跑）

命令：`./mvnw -pl simos-unit -am verify`（★ 未跑全仓 `clean verify`；★ 前台——本机后台跑会被内存守卫杀）。

### 2.1 变异前的干净轮

| 项 | 实测值 | 出处 |
|---|---|---|
| rc | **0** | `logs/verify-rc.txt` |
| reactor | 4 模块 SUCCESS（parent / UtilSimos / MapSimos / UnitSimos） | `logs/clean-verify-round1.log` |
| surefire 合计 | **UtilSimos 170 / MapSimos 362 / UnitSimos 201**，Failures 0 / Errors 0 / Skipped 0 | 同上 |
| unit 增量 | **183 → 201（+18）**：`UnitOperationsTest` 26→35、`UnitCommandHandlersTest` 40→49 | 同上（逐类汇总行） |
| `BugInstance size is 0` | **3** 次 | 同上 |
| `^[ERROR]` 行 | **0** | 同上 |
| `COMPILATION ERROR` | **0** | 同上 |
| Checkstyle 0 违规行 | **4**（每模块一条） | 同上 |
| Spotless "is keeping" | **3** | 同上 |
| 定向轮（两条用例类） | `Tests run: 84, Failures: 0` | `logs/m9_UnitPayloads.log` 的干净对照 |

### 2.2 变异全部收口后的复跑

| 项 | 实测值 | 出处 |
|---|---|---|
| rc | **0** | `logs/verify-rc-after-mutants.txt` |
| reactor | 4/4 SUCCESS，BUILD SUCCESS，Total time 03:25 min | `logs/clean-verify-after-mutants.log` |
| surefire 合计 | **170 / 362 / 201**，三者 Failures 0 / Errors 0 / Skipped 0 | 同上 |
| `BugInstance size is 0` | **3** | 同上 |
| `^[ERROR]` 行 | **0** | 同上 |
| Checkstyle 0 违规行 | **4**；Spotless "is keeping" **3** | 同上 |

### 2.3 前端 88/88

**实测**（留痕：`logs/frontend-gate.log`，抬头自记 cwd/node/驱动路径，尾行 `frontend_gate_rc=0`）：
`node simos-app/src/test/js/run-gate.cjs` ⇒ `[frontend-gate] OK tests=88 pass=88 fail=0`，
`# pass 88 / # fail 0 / # skipped 0`，rc=0。
**★ 诚实说明**：本任务的 `verify` 是 `-pl simos-unit -am` 的形状，**不含** `simos-app` ⇒ 前端门禁
**不在这条命令里**，是**单独跑**的。前端断言由 `exec-maven-plugin` 独立执行、**不并入** surefire 合计，
故 88 这个数与 `simos-unit` 的 201 互不影响；本任务**零前端改动**，88/88 与 T3 关账同值。

---

## 三、★ §3 的内部矛盾：就地裁定

### 3.1 矛盾原文（均已核对字节，不是凭记忆）

- 计划 `docs/superpowers/plans/2026-09-20-unit-extension-plan.md:292`（T4 步骤 1）：
  「对 `rootId` **及其全部后代**在同 `at` 追加 `parent` 段（`newParent` 对 root；后代父不变——只有 root 换父）」
- 计划 `:311`（T4 变异思路 m1）：「`reparentSubtree` 只改 root、不改后代 ⇒ **后代父不变** ⇒ 红」
- spec `docs/superpowers/specs/2026-09-20-unit-extension-design.md:137`（§一.5 机制列）：
  「给 `rootId` **及其全部后代**在同 `at` 追加 `parent` 段」
- 计划 `:304`（判据）：「子树迁移后**每个后代**的 `parent.valueAt(at)` 都对（逐 id 断言；只改 root 的变异会红）」

**两条不能同真**：若「后代父不变」是设计（Reading A），则「只改 root」的变异体**与参照实现逐字节相同**，
`:304` 的判据恒真、`:311` 的 m1 不可杀（等于没有护栏）；若要用上 m1，就必须存在一个**后代被改动**的
可观测差异（Reading B′）。

### 3.2 裁定：**Reading B′（级联重挂，值保持不变）**，实现按此落地

语义：`rootId` 在 `at` 落到 `newParent`；**每一个后代也在同一 `at` 追加一段 `parent`，值 = 它当前的父**
（值保持不变的**再断言**）。`attached`/`offset` 一律不动（P3：迁移不是 attach）。

证据（逐条核过原文）：
1. spec `:137` 明写「及其**全部后代**在同 `at` 追加 `parent` 段」——是**动作**，不是"值会变"。
2. spec §一.5 判据表与计划 `:304` 都把「**每个后代**的父都对」写成**可测的**，且计划自陈「只改 root 的变异会红」。
3. `:138` 把 `attachSubtree / detachUnit` 与 `reparentSubtree` **并列为两条不同的操作**——级联语义在那里已定
   （attach 级联、detach 只节点），reparent 自成一类。
4. T3 已落地的 `attachSubtree` javadoc 与之对照：attach 改 `attached`，reparent 改 `parent`。
5. 计划 `:277` 依赖行「T3（attach/detach 复用）」：复用的是**遍历/复制 helper 与纪律**，不是"reparent 只碰 root"。
6. **判决性**：Reading A 下计划的 m1 会推出一个**与参照实现字节相同**的"变异体"——正是形态 1 的坑
   （变异体不成立 ⇒ 三向全绿），说明计划的措辞**自相矛盾**而非"有意为之"。

### 3.3 ★ 我**实现并跑掉**了计划的 m1，结论与计划的说法不同——如实报

原 m1 我原样实现（`mutants/m1_UnitOperations.java`：只有 root 换父、后代 `next.put(member, current)` 原样带过），
九道门禁全跑，**RED（被杀），rc=1，Failures 3**。但红点**不是**计划说的"后代父不变"：

```
[ERROR] UnitOperationsTest.reparentSubtreeRemountsEveryDescendantAtTheSameInstant:510 [★ 后代也在同一刻落段（整树迁移；只改 root ⇒ 这里是 1）]
[ERROR] UnitOperationsTest.reparentSubtreeWithoutAParentPromotesTheSubtreeToRoot:562 [后代也落段]
[ERROR] UnitCommandHandlersTest.reparentSubtreeRemountsTheWholeSubtree:662 [★ 后代在同一刻也落段；只改 root ⇒ 这里是 1]
```

三处全是 **`SegmentedSeries` 段数**断言（`Expected size: 2 but was: 1`），**没有一处是 `parent.valueAt(at)` 的值断言**。
理由（**我验过了**）：值不变是 Reading B′ 的**设计**——B′ 下后代的新段落**值仍是它本来的父**，
所以"值"这个维度**结构性**地杀不掉"只改 root"的变异体；能杀的只有"**有没有落这一段**"。
⇒ **计划的 m1 判据（`:311`）写错了理由**，正确的杀点在**段数**。这是我**替换 m1** 的依据，而不是"m1 没用"。

**替换后的 m1**：保留原语义（只改 root），但**断言改成段数**——m1 的变异体字节与计划的 m1 **性质相同**
（都是"只有 root 换父"），差别在于**守护它的判据**。所以：
- 我**没有**发明新的变异体来凑数；
- 我**改的是判据的措辞与断言**（`reparentSubtreeRemountsEveryDescendantAtTheSameInstant` 里的段数断言、
  以及"后代也落段"的失败消息），使 §3 的裁定**可被证伪**。
- 计划 m2/m3/m4 原样保留（见第六节）。

---

## 四、★ §4 语义决定：`mergeFormation` **复用 `attachSubtree`**（不只手挂 child）

**结论**：`mergeFormation` 通过两条前置后 `return attachSubtree(state, childId, parentId, at);`。

证据：
1. spec `:369`（§五.2 命令表）：`unit.MergeFormation` 的 **op 列写的就是「attach（**同格 + MOVING**）」**——
   op 不是"改 parent 字段"，是 attach。
2. spec `:143`：「**合体** = **同格**前提下重新 `attach`（P9：v1 不销毁节点）」。
3. spec `:37`（P9）：合体 = attach 回父；保留节点。
4. spec `:31`（P3）：attach **级联**（`attachSubtree` 把节点连其子树一起 attach）——
   `mergeFormation` 复用 attach 就**自然继承级联**，与 P3 一致。
5. 计划 `:296` 的措辞「`child.parent=parentId` + `child.attached=true`」只描述了**结果**，
   没有排除"经 attach 得到该结果"；`:277` 的依赖行「T3（attach/detach 复用）」正指向复用。

**两条前置互为独立**（派单明确要求"两个独立前置"）：
- ① **同格**：两者 `effectivePosition(at)` **都 present 且相等**——"不可确定"也拒（消息含 `同格`）。
- ② `child.status() == MOVING`（消息含 `MOVING`）。
顺序：先同格、后 MOVING。m3（删①的相等那一半）与 m4（删②）**各自独立被杀**，见第六节——这就是"独立"的实测证明。

★ **未加「已是父」守卫**（T3 裁定，派单点名）：`detachUnit` 只翻 `attached`、不动 `parent` ⇒
`SplitFormation` 后 child 的 `parent` 没变 ⇒ `MergeFormation` 必须能以**同一个父**重新 attach。
**拆→合往返的用例**（`UnitOperationsTest` 与 `UnitCommandHandlersTest` 各一条）钉住这一条；
m7（把可选 parent 当必填）杀的是**另一条**降根路径，不是这条。

---

## 五、§5 验收判据逐条（每条都有实测）

| 判据 | 落在哪 | 实测 |
|---|---|---|
| 迁移后**每个后代**的 `parent.valueAt(at)` 正确 | `reparentSubtreeRemountsEveryDescendantAtTheSameInstant`、SPI `reparentSubtreeRemountsTheWholeSubtree` | 绿；m1 证明**段数**维度可杀 |
| 新父落在子树内 ⇒ 拒、**状态逐字节不变** | `reparentSubtreeRejectsANewParentInsideTheSubtree`（`hasMessageContaining("子树")`）、SPI `reparentSubtreeRejectsCyclesAndUnknownUnits` | 绿；m2 杀在**理由文案**（见节六） |
| `SplitFormation` 目标不在 root 子树 ⇒ 拒 | `splitFormationRejectsTargetsOutsideTheSubtreeAndEmptyLists`、SPI 同名 | 绿；m5 杀 |
| 在子树内 ⇒ `attached=false` 且**只有该节点** | `splitFormationDetachesTheNamedNodeOnly` | 绿 |
| `MergeFormation` 不同格 ⇒ 拒 | `mergeFormationRequiresTheSameHexAndTheMovingStatus` | 绿；m3 杀 |
| 同格但非 MOVING ⇒ 拒 | 同上 | 绿；m4 杀 |
| 同格 + MOVING ⇒ 过（`parent==parentId`、`attached==true`） | 同上 | 绿 |
| ★ **拆→合往返成功** | 两条往返用例 | 绿（**未加**「已是父」守卫正是为此） |
| 三条命令都经 `between` 产变更集、往返绿 | SPI 各用例（`applied(...)` / `Rejected`） | 绿；m8 的失败清单里可见 `UnitChangeSet[units=Unchanged[], ...]` 的实形 |
| `simos-unit` 计数累加 | 183 → **201** | 实测（节二） |
| 前端 **88/88** 不变 | 单独跑 | 实测（节 2.3） |

---

## 六、★ 变异：9 轮，九道门禁，逐轮实测

装置：`mutants/mut-round.sh`（九道门禁）+ `make-mutants.py`（变异体生成）+ `run-all-rounds.sh`（manifest 驱动 + 收口核对）。
**执行形态（诚实披露）**：本机单条 Bash 命令上限 10 分钟，9 轮 ×≈55 s 无法一次跑完 ⇒
分三批**前台**下发（m1 / m2–m5 / m6–m9），一轮一个 Maven、同一时刻只有一个。
`run-all-rounds.sh` 是**等价的一键驱动**，本轮**未直接用它**；汇总表由各轮日志**导出**（不手抄），
逐轮 SELF 行同时进 stdout 与 `logs/rounds-summary.txt`。

| 轮 | 目标类 | pushed md5 | rc | COMPILATION ERROR | 聚合 | 结论 |
|---|---|---|---|---|---|---|
| m1 | UnitOperations | `b4fd41a7…` | 1 | 0 | 84 / 3 | **RED(被杀)** — 段数：ops `:510`、ops `:562`、SPI `:662` |
| m2 | UnitOperations | `062555a2…` | 1 | 0 | 84 / 2 | **RED** — ops `:526`、SPI `:689`（**理由文案**） |
| m3 | UnitOperations | `980ddd3d…` | 1 | 0 | 84 / 2 | **RED** — ops `:649`、SPI `:764`（Applied 而非 Rejected） |
| m4 | UnitOperations | `edef7b35…` | 1 | 0 | 84 / 2 | **RED** — ops `:638`、SPI `:781`（status=RESTING 放行） |
| m5 | UnitOperations | `fe8d4ce7…` | 1 | 0 | 84 / 2 | **RED** — ops `:601`、SPI `:734` |
| m6 | UnitOperations | `49269a0d…` | 1 | 0 | 84 / 2 | **RED** — ops `:668`（级联 `true but was false`）、ops `:680` |
| m7 | ReparentSubtreeHandler | `c99d2fa0…` | 1 | 0 | 84 / 1 | **RED** — SPI `:673` `期望 Applied 而不是 Rejected[reason=字段 parent 必填]` |
| m8 | UnitOperations | `6e239752…` | 1 | 0 | 84 / 2 | **RED** — ops `:615`、SPI `:742`（`Unchanged[]` 被 Applied） |
| m9 | UnitPayloads | `beb75fff…` | **0** | 0 | 84 / 0 | **GREEN(存活)** — 预期内，见下 |

每轮 `verdict=OK`（无 VOID：干净世界、字节不同、白名单、无陈旧 `.class`、CE=0、surefire mtime 落在本轮内、
逐字节 `cp` 还原、日志自指四个 md5）。以上全部可在 `logs/m?_*.log` 的 `SELF-REFERENTIAL RECORD` 段逐条复核。

### 6.1 逐轮「为什么红 / 为什么没红」

- **m1（RED）** — 红的是**段数**（`Expected size: 2 but was: 1`），不是值。理由见节 3.3：B′ 下后代的
  新段**值不变**，值维度结构性杀不掉它。这三处失败消息本身就是判据的原文（含「只改 root ⇒ 这里是 1」）。
- **m2（RED）** — 删掉 `reparentSubtree` 的**显式**成环拒。红点原文：ops `:526`、SPI `:689`。
  ★ **为什么红的是理由文案**：`UnitState` 构造期**还有一道兜底**（`UnitState.java` 的成环检查，
  消息是 `编制树在 … 成环，环上含 …`，含「成环」**不含**「子树」）⇒ 删了 op 内的守卫后
  **行为仍然拒绝**，但**理由变了**，于是 `hasMessageContaining("子树")` 红。
  ⇒ 这是**防御纵深**：op 的守卫给可读理由、构造期是真拦。**杀掉的是可读理由这一层**，如实记。
- **m3（RED）** — 删「同格」判据的**相等**那一半（"不可确定"那一半还在）。SPI 失败清单里可见**完整实形**：
  `期望 Rejected 而不是 Applied[changeSet=… units=Upsert[entries={u-2=Unit[… parent=…]] …]` ⇒ 不同格**真的过了**。
- **m4（RED）** — 删 `MOVING` 前置。SPI 清单里 `status=RESTING` 的 child **被放行**（`Applied`）。
  ★ 与 m3 **各自独立**：m3 删①、m4 删②，两轮的失败点分别是 `:649`/`:764` 与 `:638`/`:781`，不重叠。
- **m5（RED）** — 删拆分的「目标在 root 子树内」守卫 ⇒ 外部单位被 detach。ops `:601`、SPI `:734`。
- **m6（RED）** — 合体只挂 child 节点、不级联（即**否掉节四的复用决定**）。ops `:668` 的失败消息是
  `下属一起归队（复用 attach 的级联）`，实测失败原文 `Expecting value to be true but was false` ⇒ **级联确实是可观测的**；
  另一处 `:680` 是"合体的成环路径"。⇒ **节四「复用 `attachSubtree`」这个决定自身有护栏**。
- **m7（RED）** — 把 `ReparentSubtreeHandler` 的可选 `parent` 当必填（照抄 `AttachUnitHandler` 的 `orElseThrow`）。
  SPI `:673`：`期望 Applied 而不是 Rejected[reason=字段 parent 必填]` ⇒ **降根路径（P4）被堵死是可观测的**。
- **m8（RED）** — 删 `splitFormation` 的**空名单**守卫（它是我就地裁定的产物，见节七）。SPI `:742` 的实形是
  `Applied[changeSet=UnitChangeSet[units=Unchanged[], commandChains=Unchanged[]]]` ⇒ **空命令静默成功**，被判据抓住。
- **m9（GREEN，存活）** — 删 `UnitPayloads.requireTextArray` 的 `!value.isArray()` 形状校验。
  **为什么没红（我验过了）**：传 `subUnitIds:{}`（对象）时，SPI 那条断言只查**消息里含 token `subUnitIds`**，
  而**域层兜底消息**（"目标不存在/不在子树内" 之类的路径上 `requireTextArray` 已不再抛）同样含 `subUnitIds`
  ⇒ **判据弱于行为**，变异体穿过。★ 这是**如实存档的存活项**，**不伪造红点**、**不改判据去凑杀**
  （改判据属于"为了让变异体死而改测试"，会把判别力问题掩盖成绿）。
  ⇒ 结论要**两个一起报**：「m9 存活」**且**「该处判据弱于行为」。修法应落在 SPI 断言改成
  精确匹配形状错误（如 `hasMessageContaining("必须是 [字符串…] 数组")`），归 **T9/T10 收口或由控制器裁**。

### 6.2 装置自证（拒收侧，`logs/apparatus-negative-gates.log`）

四道故意违规，**全部 rc=3 且源树未被污染**：
- (a) TARGET 不在白名单（`pom.xml`）⇒ `ABORT: TARGET 不在白名单内`；
- (b) 变异体与原件字节相同 ⇒ `ABORT: 变异体与原件字节相同 —— 该轮作废`；
- (c) 伪造基线（`0000…0`）⇒ `ABORT: 本轮起点不是干净世界 —— 当前 09840d49… != 基线 0000…`；
- (d) 源树里放一个 `m1_X.java` ⇒ `ABORT: 源树内有 1 个规范名之外的 .java`。
自证后复测五个目标 `md5sum -c` **全 OK**。

★ **生成器自证也抓到过一次真事故（形态 1 实例）**：第一版 `make-mutants.py` 的**核验路径**用裸 `replace`
去重算 m4，而 m4 的两段锚点在文件里**不连续** ⇒ 核验读到**原件与原件相等**，装置当场以
`ABORT: m4_UnitOperations.java 与原件字节相同 —— 该变异体作废` 退出（**若它静默通过，那一轮跑的就是原件**）。
修法：把「构建」与「核验」合成**同一次折叠**（`PLAN` 字典，每处 `splice()` 断言恰命中 1 次）。

---

## 七、就地裁定清单（含依据与被取代的原文）

派单要求"有分歧就地裁定 + 写报告，控制器回填"。逐条列：

| # | 裁定 | 依据 | 被取代的原文 |
|---|---|---|---|
| A1 | **Reading B′**：后代也在同 `at` 追加 `parent` 段（值不变） | spec `:137`「及其全部后代在同 `at` 追加 `parent` 段」+ 计划 `:304` 判据 + `:138` 并列两条 op | 计划 `:292`「后代父不变——只有 root 换父」；计划 `:311` 的 m1 理由 |
| A2 | `splitFormation` **空 `subUnitIds` ⇒ 拒**（消息"不得为空：拆分命令至少要指名一个目标"） | 空名单是**坏命令**（无目标可拆），静默成功会产 `Unchanged[]` 变更集（m8 实测实形） | 计划 `:294` 未提该情形（**规格缺口**，补上） |
| A3 | `subUnitIds` **去重**（`LinkedHashSet`，保留首次出现序） | 重复 id 重复 detach 无意义且会让"节点级"语义含糊 | 计划 `:294` 未提（**规格缺口**） |
| A4 | `subUnitIds` **含 `rootId` 自身合法**，交给 `detachUnit` 判（"已是根"） | P3 只节点；root 被 detach 是合法语义 | 计划未提（**规格缺口**） |
| A5 | `reparentSubtree(…, null, …)` 抛 **NPE**（编程错误）；而 **JSON 载荷里** `parent` 缺失/null ⇒ **降根**（P4） | spec `:364` 载荷写 `parent?`；P4（spec `:32`）孤儿策略 = 提升为根；`Optional` 形参**显式传 null** 是调用方 bug | — |
| A6 | 计划的 **m1 的杀点在段数**，不在值 | 见节 3.3 实测 | 计划 `:311` 的「后代父不变 ⇒ 红」 |
| A7 | `mergeFormation` **复用 `attachSubtree`** | spec `:369` op 列「attach」+ `:143` + P9(`:37`) + P3(`:31`) | 计划 `:296` 只写结果字段，未点明复用（**措辞不完整**，非冲突） |
| A8 | 空/坏载荷的形状校验**留在 `UnitPayloads`**（而非各 handler 各写一份） | 计划 `:298` 明写「新增 `requireTextArray` 到 `UnitPayloads`」 | — |

★ **未裁定、原样转下**：m9 的「判据弱于行为」（节 6.1）——修它要动 SPI 断言，属于**判据改造**，
不在本任务的验收范围（本任务的判据已绿），归 **T9/T10 或控制器裁**。

---

## 八、我未能核实的（★ 必读）

1. **端到端未做**（派单明文要求如实写）。三个 handler **未注册进 `Shell`**（装配与 catalog 归 **T9**）⇒
   **没有**任何一条"经真实 `CommandBus`/`CoreSimos`/`Shell` 的端到端"证据。本任务的全部证据止于
   **op 层 + SPI 层**（`HandlerOutcome`）。派单明令"本任务不做端到端冒烟"，故这不是缺口而是**范围**。
2. **未跑全仓 `clean verify`**（派单明令只跑模块级）。因此**没有**"整仓 n 模块全绿"的实测值；
   前端 88/88 是**单独跑**的（节 2.3）。`simos-app` 在 `-pl simos-unit -am` 的 reactor **之外**。
3. **未做真档/真世界的验证**：全部用例都在构造出来的 `UnitState` 夹具上，**没有**一份从 `simos.db`
   重放出来的编制树。⇒ "迁移一棵真实的十几层编制树"**没测过**。
4. **Android/跨机未测**：只在本机（`nproc=2`）跑过。`~/ProjectMosire`、`~/.m2` 不跨机同步，
   换机器前先做 CLAUDE.md 的"换设备自检清单"。
5. **性能未测**：`reparentSubtree` 的复杂度是 O(子树)（每个成员一次 `append`，`SegmentedSeries` 追加是拷贝），
   **没有**任何规模化的时延/内存测量。**这是推导，不是实测**。
6. **m9 的修法未实现**（节 6.1）：我知道该往哪改（`hasMessageContaining` 精确到形状文案），
   **但没改**（改判据会让"存活项"变成"被杀"，属于事后凑绿）。⇒ 「m9 存活 + 判据弱于行为」**原样留在案上**。
7. **`SegmentedSeries` 在 `at` 已存在段时的行为**：我只知道「严格递增的 `from`」这条约束存在
   （T1/T3 的交接），**本任务没有单独测**"在同 `at` 追加一个已存在的段"会发生什么——
   三个 op 都只在 `state.meta().timestamp()`（当前时刻）追加，若该时刻**已有**段，
   行为**未验**。**这是推导出来的风险，不是实测事实。**
8. **`reparentSubtree` 对**已被 detach 的子树**（`attached=false`）的语义未单独钉**：实现里
   `attached` 一律不动，故"迁移一个 detached 子树"会保留 `attached=false`——**符合我的读法**，
   但**没有**一条用例专门钉这个组合（值断言只覆盖 `parent`）。
9. **未核 `reparentSubtree` 与 `disband` 的交互**（disband 要求先改编下属）——T5 范围，
   **本任务没碰**，**也没验**。

---

## 九、T9/T10 的交接（硬接缝）

- **三个 handler 未注册**：`unit.ReparentSubtree` / `unit.SplitFormation` / `unit.MergeFormation`
  的 `type()` 字符串已在 handler 内固定，但**没有任何 catalog/registry 认识它们** ⇒ T9 装配时
  必须把三条都注册，并在 `Shell` 侧提供载荷形状（`parent?` **可为缺省**，`subUnitIds[]` 为**数组**——
  这两条是 m7/m8/m9 三条变异所钉的形状约束）。
- **m9 的判据弱于行为**（节 6.1/7）需要收口。
- **A5 的 NPE 边界**：`ReparentSubtreeHandler` 走 `UnitPayloads.optionalId`（**不** `orElseThrow`），
  域层拿 `Optional`；**别**在装配期给这条参数补 `orElseThrow`（m7 已证明那是可观测的行为改变）。
