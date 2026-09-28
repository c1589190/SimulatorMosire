# R4-B.4 阶层纯派生收口 —— 实现与验收报告

> 切片：B.4（R3B.4）。基线 HEAD：`ecdfe04f`。日期：2026-09-29。
> 工作树只改 2 个生产文件；未 commit / 未 push / 未写测试 / 未跑 `test` / 未跑 `verify`。

---

## 0. 一句话结论

`HouseholdClassRule` 已没有任何槽位上限回退：分类直接返回纯派生 `stratum`；`EconomyData` 的
“`ClassRow.view` 必须命中 `Industry.slots` 且 `participationPerMille ≤ slot.laborParticipationPerMille`”守卫已删除。
同一 tick0 世界（B.3a rev4，6,392 家户）在 B.4 前后各跑 0→120（8 线程）做全领域状态对比：
**唯一差异是 `economy.classes` 的 799 行 `view`**，其余 13 个经济组件、actor 账户、map/social/unit/sd
全部逐值相同；`ClassRow` 的非 view 字段（population/laborMilli/participationPerMille/money/debts/…）零差异。
活跃富农（有 `ownLand > 0`、净雇工、无租权）读数 **0**；总数 598 的“富农”全部是
`retainedCurrentView:noObservableEvidence` 的旧标签保留（B.4 前行为，不是新造标签）。

---

## 1. 改动文件 / 方法

`git status --porcelain`：

```
 M simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java
 M simos-economy/src/main/java/io/mosire/simos/economy/time/HouseholdClassRule.java
```

`git diff --stat`：2 files changed，22 insertions(+)，211 deletions(-)。

### 1.1 `time/HouseholdClassRule.java`

删掉（全部为分类侧的槽位回退机制）：

| 删除物 | 说明 |
|---|---|
| `Index.slotCapsByHex` | 逐格“旧四档 → min 参与率上限”索引 |
| `Index.universalSlotCaps` | 无格键产业的最紧槽位上限 |
| `Index` 构造器里的 `industries → slots` 建表循环 | 该循环是 `slotCapsByHex` 的唯一来源 |
| `LEGACY_TIER_STRATA` | 分类侧“哪些档受槽位约束”的词表 |
| `feasibleStratum(ClassRow, SocialClassId)` | 派生档不满足“该行参与率 ≤ 新档槽位上限”时退化的入口 |
| `fallbackOrder(SocialClassId)` | 富→中→贫 / 地主→中→贫 退化顺序 |
| `slotCapOf(ClassRow, SocialClassId)` | 按 `row.view().hex()` 查 `slotCapsByHex` |
| `slotCapText(ClassRow, SocialClassId)` | 仅给 reason 拼字 |
| classify 末尾的 `feasibleStratum` 分支 | 含 `;slotCapFallback(...)` reason 拼接；现在直接 `return new Classification(stratum, reason, …)` |
| import `model.ClassSlot`、`model.IndustryHexKeys` | 删除后无用途 |

保留/不变：

- `classify` / `classifyDetailed` 的公开签名、`Index.of(...)` 的两个公开重载、`laborSourceOf` 读口；
- `Index.of(...)` 的 `industries` 形参**保留但不再读取**（避免动结算/读口调用点的签名；见 §6.1）；
- `Classification` 的字段与理由字符串格式（只是不再追加槽位回退段）；
- “无任何可观察证据 ⇒ 保留当前 `ClassRow.view`”的 `retainedCurrentView:noObservableEvidence` 路径（既有语义，B.4 未动）。

### 1.2 `EconomyData.java`

删除：

- `classes` 构造循环里的 `requireStratumAllowed(industriesCopy, view, "classes")` 调用；
- 随之的 `row.participationPerMille() > slot.laborParticipationPerMille()` 抛错分支（v2 spec §八.1 的
  “参与率 ≤ 槽位上限”那条跨对象守卫）；
- `CREATION_SLOT_STRATA` 常量、`requireStratumAllowed` 私有 helper（旧的 view↔slots 判据整体退场）；
- import `model.ClassSlot`、`api.id.SocialClassId`（删除后无用途）。

替换：

- `flows` 侧原来的 `requireStratumAllowed(...)` 改为新的 `requireIndustryRegistered(...)`：
  **只判“该 flow 行的格上登记过至少一个产业”**（保留“引用了不存在的产业”的 fail-closed），
  不再判 view 是否命中 slots、不再返回任何参与率上限。
  `classes` 侧不再调用它，因此 classes 行的“该格存在产业”校验随该调用一起删除（见 §6.1）。

未动（遵守“不改结算/市场/播种/索引；不改组件形状”）：

- `EconomySettlement` / `MarketSettlement` / `EconomySeeder` / `SettlementIndex` / `EconomyChangeSet` /
  `EconomyCodec` / `Industry` / `ClassSlot` / `ApiViews` 全部零改动；
- `Industry.slots` 的线格式、Codec 形状、14 组件数量全部不变。

### 1.3 删掉的守卫 vs 保留的守卫

**删掉的守卫（分类侧槽位耦合）**：

1. `ClassRow.view.stratum ∉ 该格任一产业 slots` 的 classes fail-closed；
2. `ClassRow.participationPerMille ≤ min(命中 slot 的 laborParticipationPerMille)`；
3. 分类结果为满足 (2) 而做的 `feasibleStratum` 回退与 `slotCapFallback` reason 段；
4. flows 侧的 slot 命中/上限判据（保留“该格有产业”这一条）。

**保留的守卫（逐条仍在）**：

- `ClassRow` 构造期：`population ≥ 0`、`laborMilli ≥ 0`、`money ≥ 0`、`cycleNaturalNeedMilli ≥ 0`、
  `participationPerMille ∈ [0,1000]`、`naturalNeeds`/`effectiveDemand` 键值非 null 且值 ≥ 0、`debts` 非 null 且不含 null、
  两张需求表保序不可变；
- `EconomyData.classes`：`classes` 键 == `ClassRow.id`（稳定身份）、键/值非 null；
- `EconomyData.debts`：键/值非 null、被引用债必须存在、每条 `ClassRow.debts` 引用的债必须存在（c1 对账后的兜底）；
- `EconomyData.flows`：键 == `FlowRow.id`、键必须是已存在家户、flow 行的格必须登记过产业；
- 劳动供给/配额：键 == 行内 group/id、Σ allocation ≤ available、配额必须有同期 supply；
- 产业/关系/unit/condition 的跨表 operator/industry 一致性、unit↔relation 等既有守卫未动；
- `HouseholdClassRule` 内的债务压力、租权、净劳动、直接份额等派生判据未动。

---

## 2. 编译 / spotless 原文

命令与退出码：

```
$ pgrep -af "surefirebooter|classworlds.launcher" || true      # 无命中
$ tools/mvn-lock.sh -q spotless:apply                           # rc=0
$ tools/mvn-lock.sh -q spotless:check                           # rc=0
$ tools/mvn-lock.sh -DskipTests compile                         # rc=0
$ tools/mvn-lock.sh -Dmaven.test.skip=true package              # rc=0（为 E2E 起 shaded jar，未跑 test）
```

最终 compile 原文（`/tmp/b4-final-compile.log` 尾）：

```
[INFO] EconomySimos ....................................... SUCCESS [  0.629 s]
[INFO] SimosApp ........................................... SUCCESS [  0.658 s]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  3.893 s
[INFO] Finished at: 2026-09-29T04:57:36+08:00
```

spotless 非 `-q` 原文（`/tmp/b4-final-spotless-apply-verbose.log` / `…check-verbose.log` 第 128 行）：

```
[INFO] BUILD SUCCESS
[INFO] Total time:  0.410 s   （apply）
[INFO] BUILD SUCCESS
[INFO] Total time:  0.419 s   （check）
```

package（`/tmp/b4-package.log`）：

```
[INFO] BUILD SUCCESS
[INFO] Total time:  5.737 s
[INFO] Finished at: 2026-09-29T04:46:38+08:00
```

shaded jar：`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`
md5 `3000a539c240300408996568bc61eafd`（B.4 后）。B.4 前的对照 jar：`2cf86215762fb90807074133249bf281`
（B.3a-perf 关账值，见 progress.md）。

---

## 3. 分类纯函数性证据（同状态两次逐行相等）

探针：`/tmp/ProbeB4.java`；固定世界：`/tmp/b3a-store2/simos.db` 的 revision 2/3/4
（B.3a 自租修复后的 tick0，6,392 家户）；证据：

| 证据文件 | 状态 | 行数 | 每行调用 | `Classification` 不一致 |
|---|---|---|---|---|
| `/tmp/b4-probe-final-tick0.txt` | tick0 rev4 | 6,392 | 同一 `Index` 连续 2 次 + 另一独立 `Index` 第 3 次 | **0** |
| `/tmp/b4-probe-final-tick120.txt` | tick120 rev5 | 6,392 | 同上 | **0** |
| `/tmp/b4-probe-final-tick120-1t.txt` | 1 线程 tick120 | 6,392 | 同上 | **0** |

- 探针还额外用公开静态入口 `HouseholdClassRule.classifyDetailed(data, h, empty)` 逐字跑了 4 行 ×2 次
  （每次都重建 `Index`）：0 不一致。之所以只抽样 4 行：每行静态入口会重建一次全量 `Index`，
  6,392 行全跑约 1.75 s/行；逐行“同状态两次”的判据已由“同一 Index 两次 + 独立 Index 第三次”覆盖。
- **事实指纹不变**：探针在分类前/后对全部 14 个组件做 SHA-256 指纹：
  - tick0：`3ef8e36f8d5a5f6f19003b39a46777a20331402ff0e311716f2ebed78d73dd26`（前 = 后）；
  - tick120：`caf23a4f7f7f4dd9eac195ca5636b6599f545447f7a34f01420d369388c3fbe8`（前 = 后）；
  - 结论：分类路径不修改输入 `EconomyData`。
- **模拟 5c 写回**：对每个“派生档 ≠ 当前 view”的行调用与 `EconomySettlement` 相同的
  `ClassRow.withView(...)`，用反射逐字段比较：
  - tick0：2,402 行会写回，**非 view 字段差异 0**；
  - tick120：0 行需写回（已收敛），非 view 差异 0。

B.4 之前的旧行为（旧代码 + 同一 tick0，证据 `/tmp/b4-probe-pre-tick0.txt`；该文件里
`staticApiSampleRows` 一行有探针自身的计数初值 bug，实际循环行数 = 命令行参数 64）：

- `slotCapFallbackRows=799`，全部为 `derived=middle_peasant, slotCap=900, participationPerMille=950 -> poor_peasant`；
- 典型行：`hh--56_-65-rural-poor_peasant`，理由以
  `middle_peasant:ownLandMilliMu=837002;netLaborSoldMilli=2893100;…` 开头，末尾被追加
  `slotCapFallback(…)`；这 799 行就是“事实派生成 middle、被槽位上限错误降回 poor”的样本。

---

## 4. 0→120：写回只改 view + 前后阶层分布 + 零活跃富农证据

### 4.1 运行与证据文件

- 同一个 tick0 初态：`/tmp/b3a-store2` 的 rev4（复制后删 `revision > 4`）。
- B.4 前对照：`/tmp/b3a-perf-final120-store`（B.3a-perf 的 8 线程 0→120 关账 store，
  allocations=33,793，与本次新 run 同口径；`/tmp/b3a-store2` 自带的 rev5 是另一次 B.3a 旧 run，
  allocations=35,790、debts=0，不可比，故未用——见 §6.2）。
- B.4 后：`/tmp/b4-8-store`，8 线程 0→120，wall **35.27 s**（API 内 `wall=34.758 s`），
  `result=committed`，head=5，tick=120。
- 差异证据：`/tmp/b4-diff-proof.py` → `/tmp/b4-diff-proof.txt`。

### 4.2 “只改 view”的状态/变更集证明

> 口径说明：rev5（tick120）是 **0→120 的聚合 changeset**，其中其他 economy 组件的变化来自生产/市场/结算，
> 不是分类写回。要证明的是“**分类写回不改变任何事实**”。为此用两条互补证据：
> ① 同初态、B.4 前 vs B.4 后各跑完整 0→120，把 B.4 的全部影响隔离出来；
> ② 在 tick0 状态上模拟 5c 写回（逐行 `withView`），对 2,402 个会写回的行做“除 view 外逐字段比较”。

`/tmp/b4-diff-proof.txt` 原文要点：

```
changed_components=economy.classes
classes_keys_equal=True rows=6392
changed_class_rows=799 field_diffs={'view': 799} nonview_diffs=0
```

即：

1. B.4 前 vs B.4 后，**六个模块只有 `economy.classes` 一个组件不同**；
2. 6,392 个 `HouseholdId` 键完全相同；
3. 799 行有差异，差异字段只有 `view`；
4. 非 view 字段（`population` / `laborMilli` / `participationPerMille` / `money` / `debts` /
   `naturalNeeds` / `effectiveDemand` / `cycleNaturalNeedMilli`）**逐值零差异**；
5. 其余 `LaborAllocation` / `AssetShare` / `Debt` / actor 账户（`accounts`）以及 map/social/unit/sd
   组件**逐值相同**。

样例（同样 5 个字段全部相等，只有 view 变）：

```
hh--56_-65-rural-poor_peasant: baseline_view=-56_-65|rural|poor_peasant
                              new_view=-56_-65|rural|middle_peasant
participation=950 population=6063 laborMilli=3487624 money=0 debts=[]
```

写回点源码证明（`EconomySettlement` 5c）：唯一写入是
`rows.put(key, row.withView(new CohortKey(row.view().hex(), row.view().residence(), derived)))`；
`ClassRow.withView` 只把 `view` 换成新值，其余 9 个字段原样传入。完整源码片段见 `/tmp/b4-audit.txt`。

### 4.3 前后阶层分布

**tick0（rev4，原始 view 每档 1,598）**

- 新分类派生总数：`artisan=749, landless_laborer=55, landlord=1397, middle_peasant=2995, poor_peasant=598, rich_peasant=598`
  （其中 retainedCurrentView 每档 598，即无证据行保留旧 view；活跃派生
  `activeDerivedCounts={artisan=749, landless_laborer=55, landlord=799, middle_peasant=2397}`）。
- 旧行为会把 799 个 `poor_peasant participation=950 → 派生 middle` 的行回落成 poor；
  B.4 后这 799 行保持 `middle_peasant`。

**tick120（stored `ClassRow.view`）**

| 档位 | B.4 前（`/tmp/b3a-perf-final120-store`） | B.4 后（`/tmp/b4-8-store`） | 差 |
|---|---:|---:|---:|
| middle_peasant | 2,196 | 2,995 | **+799** |
| poor_peasant | 1,452 | 653 | **−799** |
| landlord | 1,397 | 1,397 | 0 |
| artisan | 749 | 749 | 0 |
| rich_peasant | 598 | 598 | 0 |
| landless_laborer | 0 | 0 | 0 |

差异恰好是那 799 行：它们在旧代码里被 `slotCapFallback` 从 middle 降回 poor，B.4 后保留派生 middle。

**tick120 重新分类读数（`/tmp/b4-probe-final-tick120.txt`）**

- `derivedCounts={artisan=749, landlord=1397, middle_peasant=2995, poor_peasant=653, rich_peasant=598}`
- `activeDerivedCounts={artisan=749, landlord=799, middle_peasant=2397, poor_peasant=55}`
- `retainedCurrentViewCounts={landlord=598, middle_peasant=598, poor_peasant=598, rich_peasant=598}`
- `slotCapFallbackRows=0`（新代码里该分支已不存在）

同表里 `landless_laborer` 在 tick120 为 **0（stored 0 / active 0；`activeDerivedCounts` 里没有该键即 0）**；
这是分类判据读数的直接结果，没有“为了四档齐全”补标签。

### 4.4 零活跃富农的证据（不是因为“要零”才这么写）

tick120 `rich_peasant` 总数 598，但**活跃富农 = 0**；598 全部是
`retainedCurrentView:noObservableEvidence`（城市富农家户无任何资产份额/关系/劳动/租权可观察证据时，
既有语义就是保留当前 view；B.4 没有改这条语义）。

判据读数（`/tmp/b4-probe-final-tick120.txt`）：

```
derivedRichPeasantTotal=598
derivedRichPeasantActive=0
derivedRichPeasantRetainedCurrentView=598
rowsWithOwnLandPositive=3196
rowsWithNetLaborHired=799
rowsWithOwnLandAndNetLaborHired=799
rowsWithOwnLandNetHiredNoRent=0        <-- 富农分支 ownLand>0 & netLaborSold<0 & 无租权 = 0
richBranchExamples: (空)
```

即：本世界里**没有任何家户**同时满足富农派生分支的证据；因此活跃富农如实为 0。
799 个 `ownLand>0 & netLaborSold<0` 的行全部 `rentEntitled=true`，按既有优先级先落 `LANDLORD`
（这是 B.4 之前的算法顺序，本片未动）。没有为了“四档齐全”造任何标签。

tick0 的同口径读数为：`active rich=0`、`rowsWithOwnLandNetHiredNoRent=0`（证据 `/tmp/b4-probe-final-tick0.txt`）。

---

## 5. 1 vs 8 线程

- 1 线程：`/tmp/b4-1-store`，同一 tick0，0→120，wall **38.62 s**（API 内 `wall=38.102 s`），committed。
- 8 线程：`/tmp/b4-8-store`，wall **35.27 s**（API 内 34.758 s）。
- 领域对比（`b3a_perf_state` canonical bundle，rev5 全状态）：

```
1-vs-8 component diffs: NONE
8t stratum {middle_peasant:2995, landlord:1397, artisan:749, poor_peasant:653, rich_peasant:598}
1t stratum {middle_peasant:2995, landlord:1397, artisan:749, poor_peasant:653, rich_peasant:598}
```

`ProbeB4` 对 1t/8t 的 tick120 再分类读数也逐项相同（derived、active、retained、fallback=0、
classificationMismatches=0）。本任务书允许 1 线程 >3 分钟时退 0→30；实测 1 线程 0→120 仅 38.6 s，
故直接跑了完整 0→120。

说明：本次 1t 与 8t 只差 1.10×，与 B.3a-perf 的串行面被索引化一致；这不等于 4 线程/更长区间已测（见 §7）。

---

## 6. 与计划不同处 / 剩余阻断

### 6.1 与计划不同处

1. **`requireStratumAllowed` 有第二个调用点（flows）**：任务书写“删除该调用与只服务它的 helper/import”。
   实际删掉 classes 调用后，helper 仍被 flows 调用，因此没有整个删除，而是退化为
   `requireIndustryRegistered`（只保留“flow 行的格必须有产业”）。flows 侧的 slot 命中/上限判据一并删除，
   所以“view 被 slots 约束”在 main 里已无代码路径。
   副作用：`classes` 行原来自带的“该格必须有产业”校验随调用删除；分类器不用 hex（新代码也不读 slots），
   故不影响分类，但这是一个应当记录的结构校验放宽。
2. **`Index.of(...)` 的 `industries` 形参保留但不再使用**：为了让结算/读口的公开重载签名不变
   （§4 的“不改索引”），只在工厂里做 `requireNonNull`。V 阶段可连同测试一起删该形参。
3. **对照组改用 `/tmp/b3a-perf-final120-store`**：`/tmp/b3a-store2` 自带的 rev5 是 B.3a 的另一次 run
   （allocations=35,790、debts=0），与本片 0→120 的 B.3a-perf 口径不同；B.3a-perf 的关账 store 才是
   同口径对照（33,793 allocations）。差异证据因此是“B.4 前后各跑一次 0→120”，不是“读一个旧 rev5”。
4. **活跃富农 vs retained 富农**：新世界 598 的 `rich_peasant` 数字**全部**是
   `retainedCurrentView` 的旧标签；报告把它拆成 total/active/retained，避免把“保留旧 view”误读成“活跃富农”。

### 6.2 剩余阻断 / 文档债（本片未处理）

1. **既有测试未适配**：`EconomyInvariantsTest` 仍有“参与率超槽位上限必须拒”“未允许的阶层槽位”等
   旧槽位断言（test scope，大约 :128 / :317-322），V 阶段必须按新判据成批改写；本片按纪律未动测试、未跑 test。
2. **陈旧注释**：`ClassRow.java` 的类注（约 :39）与 `@param participationPerMille`（约 :52）、
   `EconomyMigrateHouseholdHandler.java`（约 :77）仍写着“≤ 槽位上限”的旧口径。它们不在本片文件所有权内，
   且无功能作用；V 清理时一并改。
3. **`Industry.slots` 现在只读/只写不再约束**：
   - 写入：`EconomySeeder` 仍给每个产业登记四档 `laborParticipationPerMille`（950/900/750/100）；
   - 读/显示/解析：`ApiViews`、`CatalogTool`、`EconomyPayloads`、`ClassSlot` 的 `[0,1000]` 自校验；
   - **没有任何 main 代码再拿它算上限**（audit 见下）。传统槽位配置现在是“生产方式内部角色/劳动配置”
     的一份只读配置，不影响阶层派生；E2 若要用它，需要重新声明消费点。
4. **classes 的“格必须有产业”校验已删**（见 §6.1.1），flows 侧仍保留。
5. 未做 4 线程、全年、峰值内存/JFR、正式 1/4/8 协议；未重跑 B.3b 的 `TransferAssetShare` 命令 E2E
   （本片未改其 handler；0→120 结算路径本身覆盖了 assetShares/账户/债务的正常流转）。

### 6.3 禁止性路径审计

`/tmp/b4-audit.txt` 原文（`*/src/main`，排除 target）：

```
$ grep -rn "slotCapFallback|feasibleStratum" --include=*.java */src/main        -> ZERO HITS
$ grep -rn "slotCapsByHex|universalSlotCaps|fallbackOrder|slotCapOf|slotCapText|LEGACY_TIER_STRATA" -> ZERO HITS
$ grep -rn "requireStratumAllowed|CREATION_SLOT_STRATA"                        -> ZERO HITS
$ grep -rn "withParticipation|setParticipation"                                -> ZERO HITS
$ grep -rn "ReclassifyHousehold"                                               -> ZERO HITS
$ grep -n  "data\..*\(put\|merge\|remove\)" HouseholdClassRule.java            -> ZERO HITS
```

`laborParticipationPerMille` 的全部 main 命中只剩：`ClassSlot` 模型、`EconomyPayloads` 载荷解析、
`EconomySeeder` 播种、`ApiViews`/`CatalogTool` 读口显示、`SocialClassId` 的一句说明——**没有分类/上限计算**。

写回路径唯一写点：`EconomySettlement:1090` 的 `row.withView(...)`；`withView` 只替换 `view`，
其余 9 字段原样传入（源码片段见 `/tmp/b4-audit.txt`）。分类侧 `EconomyData` 的指纹在分类前后不变
（§3），跨 run 全组件 diff 只有 `economy.classes`（§4.2）。

---

## 7. 回归

| 回归项 | 命令/文件 | 结果 |
|---|---|---|
| B.2b `Probe2`（WorldChangeSet 严格读） | `/tmp/Probe2.java /tmp/world-rev4.json /tmp/world-rev2.json` | 两条 `OK modules=[social, economy, actor, map, sd, unit]` |
| B.2 `Probe3`（旧 rev2/3/4 → `EconomyData.empty()` 60 项） | `/tmp/Probe3.java /tmp/b2-tick0-fixture/simos.db` | `PASS=60 FAIL=0` |
| B.2b `Probe5`（Timeline vs Codec reshape 收敛） | `/tmp/Probe5.java /tmp/b2-tick0-fixture/simos.db` | `PASS=11 FAIL=0` |

关于 `/tmp/economy-rev4.json`：该文件是**世界 changeset 里的 economy 模块节点**（顶层 `@class` =
`EconomyChangeSet`），不是 `WorldChangeSet`；`Probe2`/`Timeline.readChangeSet` 对它在 B.3b 报告里
已如实记为“形状不匹配、会失败”，**不是 B.4 回归**。B.2 旧档路径以 Probe3/Probe5/Probe2-world 三条为准
（同上表，全绿）。

B.3b `TransferAssetShare`：本片未改 handler，也未重跑命令 E2E；0→120 正常提交且资产份额/账户逐值
与 B.4 前一致（§4.2）可作旁证，但不等于命令级回归。

---

## 8. 我没做 / 没验证的

- **没写/没改任何测试**；**没跑 `test` / `verify` / `clean verify` / surefire**；没有全仓回归网结果。
- **没 commit / 没 push / 没建分支**；工作树保持 2 个 modified。
- 没跑 Checkstyle / SpotBugs / Enforcer / 变异自证；只跑了任务书要求的 `spotless:apply/check` 与
  `-DskipTests compile`（外加为 E2E 必需的 `-Dmaven.test.skip=true package`）。
- 没跑 4 线程；没跑全年（360 tick）；没测峰值 RSS/JFR/正式性能协议；8 线程 35.27 s、1 线程 38.62 s
  只是本机同初态各一次。
- 没重跑 B.3b `TransferAssetShare` 的 committed/REJECTED E2E，也没做 c1 孤儿债专项探针（B.3b 已关账）。
- 没做 `economy.ReclassifyHousehold` GM 命令（计划记录 B.4 后只允许 GM 显式校验；main 里无该命令，
  本片未新增）。
- 没验证/清理 `ClassRow` 与 `EconomyMigrateHouseholdHandler` 里的陈旧注释（§6.2.2）。
- 没验证多 unit 世界在 B.4 后的长程行为（只到 120 tick；B.3a 的 8,940 unit 世界）。
- 旧档兼容只覆盖 Probe2/3/5 的三档 fixtures，未做更多历史 store。

---

## 9. 证据文件索引

| 文件 | 内容 |
|---|---|
| `/tmp/b4-final-compile.log` | 最终 `compile` 原文 |
| `/tmp/b4-final-spotless-apply-verbose.log` / `…check-verbose.log` | spotless 原文 |
| `/tmp/b4-package.log` | shaded jar package 原文 |
| `/tmp/b4-probe-pre-tick0.txt` | B.4 前旧规则 tick0：fallback=799 |
| `/tmp/b4-probe-final-tick0.txt` | B.4 后 tick0：纯函数 0 差异、fallback=0、模拟写回 2,402 行 0 非 view 差异 |
| `/tmp/b4-probe-final-tick120.txt` | B.4 后 8t tick120：同口径 |
| `/tmp/b4-probe-final-tick120-1t.txt` | B.4 后 1t tick120：同口径 |
| `/tmp/b4-diff-proof.txt` | 新旧 0→120 全状态 diff：仅 `economy.classes` / 仅 view |
| `/tmp/b4-audit.txt` | 禁止性路径 grep + 写回点 + withView 源码原文 |
| `/tmp/b4-8-run/advance-0-120.json` / `.log` | 8 线程提交结果与 wall |
| `/tmp/b4-1-run/advance-0-120.json` / `.log` | 1 线程提交结果与 wall |
| `/tmp/b4-probe2-world.out` / `b4-probe3.out` / `b4-probe5.out` | 回归原文 |
