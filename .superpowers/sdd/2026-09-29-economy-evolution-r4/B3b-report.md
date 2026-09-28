# R4-B.3b 片报告：`economy.TransferAssetShare` 命令 + c1 孤儿债对账

> 切片：B.3b（R4 计划 §2.B.3；R3 决策单 §0.3）。
> 基线：`dfee4bf7`（B.2 + B.3a + B.3a-perf 已提交）；工作树在开工时干净。
> 本片只写生产代码 + 本文档；**未写/改测试、未跑 `test`/`verify`、未 commit/push**。
> 验收设备：新 shaded jar（md5 `2872058a8559e5c212c7d7b4285a556f`），8 线程；`/tmp` 探针与读数为自建装置。

---

## 0. 一句话结论

- `economy.TransferAssetShare` 已注册并端到端可用：整条改 operator / 部分拆分 / 非法 quantity 拒绝三态都实测；
  重放/推进后逐 `(industry, asset)` 份额总量不变、其他 13 个组件不变、零份额 unit 推进不抛。
- c1 孤儿债对账（`DebtReferenceReconciler`）已接入 `EconomyData` 紧凑构造器的守卫前路径：
  合成三案例 + **真实 tick120 旧档的 62 条 c1 孤儿债**（临时探针抽取 components 后构造）全部 `62 → 0`；
  principal 守恒、`defaulted` 未动；debtor/creditor 行缺失时 fail-closed 具名抛。
- B.2 的旧档重放（Probe3/Probe5/Probe2 world 路径）与 B.3a 固定 tick0 世界 0→10 tick 全部通过；
  真实 tick120 旧档**无法经 `Timeline.readChangeSet` 读取**（R3 前 `useRights` 变更集 + 严格模块绑定，已知旧账，
  本片未越界修）。

---

## 1. 改动文件 / 方法

### 1.1 新增 `simos-economy/.../migrate/DebtReferenceReconciler.java`（c1 唯一对账点）

```java
public static Map<HouseholdId, ClassRow> reconcile(
    Map<DebtId, Debt> debts, Map<HouseholdId, ClassRow> classes)
```

- 以 `debts` 表为**唯一权威**：按 `Debt.debtor` 分组，组内 `DebtId::value` **canonical 升序**；
  逐家户行重建 `ClassRow.debts`（表外多余/重复/陈旧引用一律丢弃，债权人侧不写引用）。
- 债务的 `debtor`/`creditor` 任一在 `classes` 里不存在 ⇒ 抛 `IllegalArgumentException`，消息以
  `债务引用对账失败：…（fail-closed：不静默核销、不转第三人、不删债）` 开头（具名）。
- **不动** `debts` 表、`principal`、`defaulted`；只做引用重建 ⇒ principal 守恒是结构性的。
- **幂等**：一致时返回入参 `classes` 的**同一实例**（no-op）；不一致时按原键序返回新 `LinkedHashMap`。
- 唯一实现，唯一调用点见 1.2；`LegacyHouseholdMigration` 里没有第二份。

### 1.2 修改 `simos-economy/.../EconomyData.java`

- 加 import `io.mosire.simos.economy.migrate.DebtReferenceReconciler`。
- 在 `debts`/`classesCopy` 建好之后、v2 §八.2 跨表守卫之前插入：

```java
debts = Collections.unmodifiableMap(debtsCopy);
// ★★ B.3b（R3 决策单 §0.3）：c1 孤儿债对账 …
classesCopy = DebtReferenceReconciler.reconcile(debtsCopy, classesCopy);
classes = Collections.unmodifiableMap(classesCopy);
```

  位置满足“`LegacyHouseholdMigration` 之后、跨表守卫之前”；三个读入路径（Timeline / Codec / 载荷）最终都走这里。
- **放宽守卫**：删除“每个非 EXITED/ABANDONED unit 必须至少有一条同 industry AssetShare”的整段
  （含构造期 `shareOperators` 索引与私有 `industryOperatorKey` helper）。新口径：
  **unit 可以没有任何份额 ⇒ 规模=0**（资产转走/闲置/退出前态是合法状态）；
  `unit↔relations` 的 `operator==unit.operator`、`unit↔operatorConditions` 的 `industry==unit.industry`
  与键存在性守卫**原样保留**。
- 组件形状不变：`EconomyData` 仍 14 个组件；`EconomyChangeSet` / `EconomyCodec` **未改一字**。

### 1.3 新增 `simos-economy/.../spi/EconomyTransferAssetShareHandler.java`

- `type()="economy.TransferAssetShare"`；`handle` 校验后
  `EconomyChangeSet.between(base, base.withAssetShares(newMap))`；错误返回 `HandlerOutcome.Rejected(具名原因)`。
- 载荷解析：`share` 必填；`quantity` 缺省=原 quantity；`toOwner`/`toOperator` 缺省=原值；
  `kind` 缺省=原 kind；`toOwner`/`toOperator`/`kind` 至少一项与现值不同，否则拒。
- 语义：`quantity==原` ⇒ 整条旧 id 删除、新 id 由新 tuple 生成；`0<quantity<原` ⇒ 旧行减 quantity、新行 quantity；
  `quantity<=0` 或 `>原` ⇒ Rejected；一律不动商品/货币/债务/劳动，不要求新主体已有账户。
- **id/sequence 规则**：新 id 只走 `AssetShare.idOf(industry, asset, owner, operator, kind, sequence)`；
  `sequence = 基准状态中同 (industry, asset, owner, operator, kind) 现有份额 id 尾段的最大值 + 1`；
  无同形份额 ⇒ 0。尾段非纯数字/超 long/生成 id 已存在 ⇒ **fail-closed 拒**（不猜序号）。同一基准状态重放/分支得到同一 id。

### 1.4 `simos-app` 注册面

- `Shell.java`：import + handler 列表加 `new EconomyTransferAssetShareHandler()`（运行日志 `handler=54`，原 53）。
- `CatalogTool.PAYLOAD_HINTS`：新条目（否则构造期覆盖断言 fail-closed 启动失败）；`simos.command.catalog`
  实测新类型在列且 hint 正确。
- **未实现 `CommandTargets`**：与既有 `economy.MigrateHousehold` 同款（份额 id 不是现有资源命名空间路径）。
  后果如实记：GM 直接 `simos.command.submit` 可用；若把该命令嵌入决策人 directive，`AdjudicateTick` 会按
  “无资源目标 ⇒ 暂不可裁决” fail-closed 拒——本片范围是 GM/事件用命令。

`git diff --stat`（未含两个新文件）：

```
 simos-app/src/main/java/io/mosire/simos/app/Shell.java                  |  3 +++
 simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java |  7 +++++++
 simos-economy/src/main/java/io/mosire/simos/economy/EconomyData.java    | 44 ++++++++++++--------------------------------
 3 files changed, 22 insertions(+), 32 deletions(-)
```

---

## 2. 编译 / Spotless（原文）

### 2.1 最终 `tools/mvn-lock.sh -DskipTests compile`

```
[INFO] --- checkstyle:3.6.0:check (checkstyle-check) @ simos-economy ---
[INFO] You have 0 Checkstyle violations.
...
[INFO] Recompiling the module because of changed dependency.
[INFO] Compiling 153 source files with javac [debug release 21] to target/classes
...
[INFO] BUILD SUCCESS
[INFO] Total time:  3.925 s
```

### 2.2 最终 `tools/mvn-lock.sh spotless:apply` + `spotless:check`

```
[INFO] Spotless.Java is keeping 255 files clean - 0 were changed to be clean, 0 were already clean,
       255 were skipped because caching determined they were already clean
[INFO] BUILD SUCCESS
[INFO] Total time:  0.440 s
```

`spotless:check` 同样 `BUILD SUCCESS`。`git diff --check` 无 whitespace 报错。

### 2.3 shaded jar

- `tools/mvn-lock.sh -DskipTests package -pl simos-app -am` 会先编测试源码，因 B.2 已改 record 构造器、
  测试源码按片纪律未适配而失败（预存在的测试源码形状问题，非本片引入）；
- 按 B.3a 同款改用 `-Dmaven.test.skip=true package`：

```
[INFO] Attaching shaded artifact.
[INFO] BUILD SUCCESS
[INFO] Total time:  5.541 s
```

- jar：`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 `2872058a8559e5c212c7d7b4285a556f`。
- 探针权威重放（`B3bReplay`，JDBC → `Timeline.readChangeSet` → `EconomyChangeSet.apply`，每个状态都过构造守卫）：

```
HEAD rev=6 tick=121 … shares=8940 units=8940 relations=8940 conditions=8940 allocations=35790
SHARE_SUM 2286937777
DEBT_SUM 0 ORPHAN_REFS 0 MISSING_DEBT_ROWS 0
```

---

## 3. TransferAssetShare 端到端（8 线程，`/tmp/b3a-store2` 副本）

- 世界：`/tmp/b3a-store2` → `/tmp/b3b-store-e2e`；head rev6 tick121；`shares=8940`、
  `Σ quantity=2,286,937,777`、`(industry,asset)` 键 1799 个；`debts=0`。
- 命令经 MCP `simos.command.submit`；每个 committed 用例的回读通过 `B3bReplay`（Timeline+apply 构造守卫）+
  Python 原始 changeset 重放双读。

### 3.1 整条转移（改 operator）

```json
{"share":"share-farm@-56_-65-LAND-HOUSEHOLD-hh--56_-65-rural-landlord-HOUSEHOLD-hh--56_-65-rural-middle_peasant-TENANCY-0",
 "toOperator":{"kind":"ESTATE","id":"farm@-56_-65"},
 "reason":"B.3b E2E: whole row, change operator"}
```

- 返回 `{"result":"committed","ref":{"branch":"main","revision":7}}`。
- 旧 id `…-HOUSEHOLD-hh--56_-65-rural-middle_peasant-TENANCY-0` **不再存在**；
- 新 id `share-farm@-56_-65-LAND-HOUSEHOLD-hh--56_-65-rural-landlord-ESTATE-farm@-56_-65-TENANCY-0`
  存在，`quantity=325501`、owner/operator/kind 与期望逐值相等（sequence=0，目标 tuple 无同形份额）。
- 该旧 unit `unit-farm@-56_-65-HOUSEHOLD-hh--56_-65-rural-middle_peasant` 的**同 industry 份额变为 0**：
  全 8940 个 unit 中恰这一个 `usableAssets={}`（`progressDays=2` 仍保留，推进不抛）。

### 3.2 部分拆分

```json
{"share":"share-farm@-19_-82-LAND-ESTATE-farm@-19_-82-ESTATE-farm@-19_-82-OWNED-0",
 "quantity":1,
 "toOwner":{"kind":"ESTATE","id":"farm@-19_-82"},
 "toOperator":{"kind":"HOUSEHOLD","id":"hh--19_-82-rural-landlord"},
 "kind":"TENANCY","reason":"B.3b E2E: split 1 into existing tuple (seq max+1)"}
```

- 返回 `committed`，`revision 8`。
- 旧行仍在，`quantity 1,445,220 → 1,445,219`，owner/operator/kind 不变；
- 新 id `share-farm@-19_-82-LAND-ESTATE-farm@-19_-82-HOUSEHOLD-hh--19_-82-rural-landlord-TENANCY-1`
  存在，`quantity=1`、`kind=TENANCY`。目标 tuple 已有 sequence=0 的既有份额 ⇒ 新 sequence=1，**max+1 规则实测**。

### 3.3 非法 quantity / 无差异拒绝（head 不动）

| 用例 | 载荷要点 | 返回 |
|---|---|---|
| zero | `quantity=0, kind=TENANCY` | `REJECTED`：`quantity 必须 > 0（不接受零/负拆分；要清空请把剩余量整条转给新主体）: 0` |
| over | `quantity=1445220 > 剩余1445219` | `REJECTED`：`quantity 不得超过原份额 1445219: 1445220` |
| no-diff | 已转移行的 `toOperator` 与现值相同 | `REJECTED`：`toOwner/toOperator/kind 至少一项必须与现值不同（否则不产生任何转移）: share=…` |

三次拒绝后 `timeline head` 保持 8（未产生半截 revision）。

### 3.4 守恒 / 组件不变证据（命令后、推进前）

命令后 rev8 tick121：`shares=8941`、`Σ quantity=2,286,937,777`。与 rev6 逐项比较：

```
PASS head tick unchanged 121
PASS total share quantity per (industry,asset) identical            (1799/1799 keys)
PASS grand share sum identical                                      2286937777 -> 2286937777
PASS all non-assetShare components unchanged                        diff=[]
PASS share_count +1（整条是删+加；拆分 net +1）                     8940 -> 8941
PASS whole: old id absent
PASS whole: new id present with expected tuple/qty/kind
PASS whole: old tenant unit now has 0 share rows (guard relaxation)
PASS split: original reduced by exactly 1                           1445220 -> 1445219
PASS split: new id present qty=1 and seq=1（tuple 既有 max seq 0 +1）
PASS split: original id still exists with same owner/operator/kind
PASS rev7 changeset only assetShares changed                        changed=['assetShares']
PASS rev8 changeset only assetShares changed                        changed=['assetShares']
TOTAL 15 / 15
```

### 3.5 推进 121→122（零份额 unit 在状态里）

- `simos.advance` `expectedRevision=8`，`from=121 to=122`：`committed`，wall **6.223 s**（8 线程），head rev9。
- 推进后 `shares=8941`、`Σ quantity` 仍 `2,286,937,777`、逐键总量与推进前相同；
  `Timeline+apply` 完整重放 rev1..rev9 成功（构造守卫未抛），server log 无 `ERROR`/`Exception`。
- 目标 unit `unit-farm@-56_-65-HOUSEHOLD-hh--56_-65-rural-middle_peasant` 仍在 `units` 里、同 industry 份额 0
  （`usableAssets={}`），有 `operatorConditions` 行；推进第 122 天无异常。

---

## 4. c1 孤儿债对账

### 4.1 合成三案例（`/tmp/B3bDebtProbe`，新形状 `EconomyData` 构造）

```
=== case 1: debts 有 1 条、ClassRow.debts 缺引用（模拟 c1）===
  PASS 孤儿债引用被补上: [debt-1]
  PASS 债权人行不被写 debtor 侧引用
  PASS principal/defaulted 逐值不变: principal=1234 defaulted=true
  PASS 对账幂等：一致时返回入参 classes 同一实例（no-op）
  PASS 再构造一次 classes/debts 逐值不变
=== case 2: debtor/creditor 行缺失 ⇒ fail-closed 具名抛 ===
  PASS debtor 行缺失 ⇒ 构造抛 IllegalArgumentException
  PASS 异常具名（含 债务引用对账失败/debtor id）:
       债务引用对账失败：债务 debt-2 的债务人 hh-debtor 在 classes 里不存在
       （fail-closed：不静默核销、不转第三人、不删债）
  PASS creditor 行缺失 ⇒ 构造抛 IllegalArgumentException
  PASS 异常具名（含 债务引用对账失败/creditor id）: …债权人 hh-creditor…
=== case 3: references 多余/重复 → 以债务表重建 ===
  PASS 多余/重复/悬空引用被重建为 canonical 升序: [debt-1, debt-2]
  PASS 债权人侧的多余引用被清除: []
  PASS 债务表条数/principal 总和不变: count=2 sum=1007
  PASS 重建后再次对账 = no-op（幂等）
=== B3bDebtProbe result: PASS=13 FAIL=0 ===
```

### 4.2 真实 tick120 旧档（存在 62 条 c1）与“旧档不可读”的如实记录

仓库里有真实 c1 的旧 store（`/home/cna/SimosData/simos-dev/fix-t120-210/simos.db`；同批
`verify-t120-210-final`、`r3fix-run-t120-240-20260928` 等 tick120 债务条数亦为 332，但**孤儿条数只在
`fix-t120-210` 上逐条统计**）：

- **经 `Timeline.readChangeSet` 不可读**（本片未修、也不应修的范围外旧账）：

```
Exception in thread "main" java.lang.IllegalArgumentException: 变更集 JSON 非法或缺类型信息（@class）
Caused by: UnrecognizedPropertyException: Unrecognized field "useRights"
  (class io.mosire.simos.economy.change.EconomyChangeSet, not marked as ignorable
   (14 known properties: "debts", "assetShares", "meta", "classes", "units", "allocations",
    "relations", "shipments", "industries", "memberships", "laborSupply", "flows", "markets",
    "operatorConditions"))
```

- 按任务书允许的替代路径：用 `/tmp/B3bRealDebtProbe` **只抽取该旧档 tick120 的 `industries`/`classes`/`debts`
  三个组件的原始 JSON**（旧 `useRights` 不再参与），绑定成当前 record 后构造新形状 `EconomyData`；
  这是本片真实旧数据的证据路径，**没有为了跑通改旧档兼容层**：

```
REAL_RAW industries=1799 classes=6392 debts=332 orphanDebtsBefore=62
         principalBefore=10495732 defaultedBefore=0
  PASS 真实 tick120 组件里确有 c1 孤儿债（>0）
  PASS 构造后孤儿债为 0: 62 -> 0
  PASS principal 总和逐值不变: 10495732 -> 10495732
  PASS defaulted 计数不变（未动违约位）: 0 -> 0
  PASS Σ ClassRow.debts 引用 == 债务表条数: 332
  PASS 每条重建引用按 DebtId canonical 升序
  PASS 再次构造逐值不变（幂等）
=== B3bRealDebtProbe result: PASS=7 FAIL=0 ===
```

- 失败方向对照：旧档 tick120 的 62 条孤儿债是“债在表、行引用缺”；对账后 332 条债务全部在各自 debtor 行里
  恰好引用一次；债务表条数/本金/违约位均未动。

---

## 5. 回归

| 回归 | 结果 |
|---|---|
| B.2 `Probe3 /tmp/b2-tick0-fixture/simos.db`（Timeline 直读旧 rev2/3/4 → apply → 60 项不变量） | `PASS=60 FAIL=0` |
| B.2b `Probe5`（Timeline 直读 vs Codec reshape 收敛 + useRights 已知 gap + 旧 Remove no-op） | `PASS=11 FAIL=0` |
| B.2b `Probe2 /tmp/world-rev4.json /tmp/world-rev2.json`（WorldChangeSet 严格读） | 两档均 `OK modules=[social, economy, actor, map, sd, unit]` |
| B.3a 固定 tick0 世界（`/tmp/b3a-perf-120base-store` 副本，8940 units）`simos.advance` 0→10 | `committed`，wall 6.821 s；`B3bReplay`：HEAD rev5 tick10、`shares=8940`、`Σ=2286937777`、无异常 |
| E2E world（同上）`advance` 121→122 | `committed`，wall 6.223 s；零份额 unit 不抛 |

**`/tmp/economy-rev4.json` 的形状说明（如实记）**：该文件是**世界 changeset 里 economy 模块的节点**（顶层带
`"@class":"io.mosire.simos.economy.change.EconomyChangeSet"`），不是 `WorldChangeSet`。因此 `Probe`/`Probe2`
按各自入参契约都拒绝它；把世界节点的 `@class` 去掉后 `Probe` 三个文件（rev2/3/4）均
`OK class=…EconomyChangeSet`。这与 B.3b 改动无关（未动 Codec/Timeline 一行），B.2 的权威旧档回归以
Probe3/Probe5/Probe2-world 为准。

---

## 6. 与计划不同的地方 / 裁定

1. **`CloseProductionUnit` 未做（留 E1）**：它必须与“退出资产/劳动处置、经营者→家户解析、库存/债务去向”同一片实现；
   本片做会造一个没有退出语义的半成品写口，直接违反 B.3 计划“退出处置留 E1”的分片边界。
2. **`ReclassifyHousehold` 未做（留 B.4）**：B.4 的口径是 `ClassRow.view` **纯派生**；显式改标签会与
   “同一事实重复分类逐值一致”冲突，正确入口只能是 B.4 的 GM 显式校验命令，本片不做。
3. **守卫放宽**：删除“非 EXITED unit 必须有同 industry 份额”。计划文字只写了“unit 可无份额”，未逐字要求删哪条；
   实现上把它与 `industryOperatorKey` helper 一并删除，unit↔relation/condition 的一致性守卫未动。
4. **对账调用频率**：计划写“正常状态每次构造只做 O(1) 一致性抽查”；本片按任务书把唯一对账点放在构造期每次跑
   （O(债务+家户行)）。真实规模 332 债/6392 行，构造成本可忽略；换来“任何读入路径都不可能带孤儿进状态”。
5. **真实 tick120 旧档不可经 Timeline 读取**：R3 前 `useRights` 与严格模块绑定冲突是**已知旧账**（B.2 报告阻断点 1），
   本片未越界修；按任务书改用真实组件的抽取探针 + 合成夹具验证对账。报告 4.2 两段证据分开写，不冒充 Timeline 路径。
6. **`CommandTargets` 未实现**：与既有 `economy.MigrateHousehold` 同款；该命令是 GM/事件入口，不嵌入决策人 directive
   （嵌入会被 AdjudicateTick 的“无资源目标” fail-closed 拒）。
7. **`-DskipTests package` 不可用**：测试源码在 B.2 改 record 构造器后未适配（片纪律不写测试），
   打包只能 `-Dmaven.test.skip=true`；本片未碰测试。
8. **`reason` 字段**：按“仅说明”处理，不校验、不落盘（不新增状态组件）。

---

## 7. 我没做 / 没验证的

- 没写/改任何测试，没跑 `test`/`verify`，没做变异自证（按 AGENTS.md §三.0 留 V）。
- **未跑 SpotBugs**；最终门禁 = `-DskipTests compile`（含 Checkstyle 0 violations）+ `spotless:apply/check`。
- **没有 commit/push**；改动全在工作树（3 改 + 2 新增）。
- **未做真正的 fork/分支重放确定性测试**（同一载荷在两个分支从同一 revision 出发应得同一新 id）；只验证了规则
  是“基准状态的纯函数”并在 E2E 中实测 `sequence=max+1`。分支复现留 V。
- **未对“同 tuple 里有非 `idOf` 数字尾段旧 id”的真实夹具做端到端验证**；“非数字尾段/溢出/撞 id ⇒ 拒”的
  fail-closed 分支只有代码与构造逻辑，未由真档触发。
- 未做 `CloseProductionUnit` / `ReclassifyHousehold`；两者不在本片。
- 未做 1/4/8 线程正式协议、全年 360 tick、峰值内存、JFR；本片 E2E/回归都是 8 线程。
- 未验证资产转移后**市场/账户**层面的长期影响（命令本就不动商品/货币，但未跑多周期看产出归属变化）。
- 未验证真实 tick120 旧档经 Timeline 的读取（已知不可读；未修旧档兼容）。
- 未核对“真实旧档 `ClassRow.debts` 引用顺序”在原档里的顺序语义；对账把顺序统一为 canonical 升序（会改变线格式
  顺序，不改集合/本金）。
- 未对 `economy.TransferAssetShare` 做决策人权限路径测试（未实现 CommandTargets，directive 内会被拒）。
