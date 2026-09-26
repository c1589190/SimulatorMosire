# SDD 台账 —— 经济循环实现（H0–H5 + 关账）

Plan: `docs/superpowers/plans/2026-09-27-economic-cycle-implementation-plan.md`
Goal: `goal-19ff41be-ca9f-40d8-be89-8b9149ea69bf`（用户 2026-09-27：「一口气跑完」）

## 执行方式（用户裁定，优先于任何 Skill）

- ★ **开发期不写新测试、不做变异自证**（`AGENT.md` §三.0）。唯一硬底线 = **编译必须绿**。
- ★ 既有测试仍在跑；既有用例变红 ⇒ 按新口径**重算期望值**，**不许删或放宽断言**（照 S1 先例）。
- ★ **一次只能跑一个 Maven** ⇒ 所有 Maven 经 `tools/mvn-lock.sh`（本轮新建，已自证互斥）。
- ★ 一个文件一个 owner；派单写明文件清单。

## 控制方裁定（执行前已定，见 plan §三）

K1 家户账 = actor 持久真源 + economy 会话工作副本（不进 `EconomyData`）
K2 行键 = `CohortKey`（`(格, 居住类型, 阶层)`）= 家户键；`ClassKey` 删除
K3 产能从行搬到 `Industry`；`ClassRow.meansOfProduction` 删除（代价：真档数值会变）
K4 转移原语只认 `ActorRef`（住 `economy-api`，已依赖 `actor-api`，无循环）
K5 货币与市场**同批**落地
K6 ★ 用户裁定：作坊投入改 `{FIBER, TOOL}`、`IRON` **留作留位但必须有读者**（`allCommodityIds()` + 读口）
K7 既有用例变红优先判"夹具/口径错"，重算不放宽
K8 台账纠错：`ClassKey` 实测 **298 处**（main 174 / test 124），旧台账的 259 把 `AssetClassKey` 算进去了

## 进度

### H0 身份与观测收口

- [x] **契约（控制方亲做）**：`ResidenceKind` 新建 · `CohortKey` 加居住维（三参 + 三段规范串）·
      `PopulationLots` 的前缀改为转发到 `ResidenceKind.lotPrefix()`（**消除第二处拼写点**）
      —— 编译实测：`-pl simos-economy-api,simos-social -am` **exit 0**
- [ ] H0.1/H0.3/H0.4 `simos-economy` 主源（**Agent B 在跑**）
- [ ] H0.1/H0.7 `simos-app` 播种器与读口（**Agent C 在跑**）
- [ ] H0.5 `AssetHolding`/`AssetClassKey`/`ASSET_QUANTITY` 退役（**待 B 落地后再动**：
      `Basis` 与 `ProductionSettlement` 在 B 的文件里，避免互相覆盖）
- [ ] H0.6 产权读口（GUI + MCP）+ 600 天聚合脚本纳入 actor 账
- [ ] H0 批次编译（`-DskipTests package`，**含测试编译**）⇒ 提交

### H0 的判据（阶段边界由控制方跑）

- I1.1：迁移前后逐格人口/劳动/需求**逐值相同**（H0 不应改变经济行为）★ H0.4 例外（K3 已认代价）
- 结构：`ClassKey` 零残留（`grep -v AssetClassKey` = 0）；行键全部 `(格,居住,阶层)` 形状
- 全部 `*Test` 编译通过（不许删文件来"通过"）

---

## H0 跨模块接口裁定（控制方 2026-09-27；Agent C 早期上报后定案）

**背景**：`economy.Seed` 的载荷形状是**跨模块接口**（`EconomySeeder` 在 app 写、`EconomyPayloads` 在 economy 读）
⇒ 两侧不同形会「**编译绿、运行红**」。Agent C 在动手早期就上报，值得记一笔（这正是"早说省一轮"的形态）。

| # | 裁定 | 理由 |
|---|---|---|
| **I-1** | **行从"产业节点内"搬到"格 entry 级"**，每行显式带 `"residence"`（`ResidenceKind.parse`，词表外即抛） | 行 = 家户 `(格, 居住, 阶层)`，不再由产业决定；且"只有农村人口的格"**没有 craft 产业**，城镇四行**没处挂** |
| **I-2** | `Industry.capacity` 走产业节点的 `"capacity"` 键，★**值必须允许 0** | 沙漠格 `LAND=0`、人口 < 20 的格 `TOOL=0`；照 `capacityPerUnit` 的"必须 > 0"写**真档播不出来**。★ `capacityPerUnit` 仍 > 0（"每单位产能需要多少"，性质不同） |
| **I-3** | ★ **class / flow 的地址局部名唯一拼写点 = `CohortKey.toString()` / `CohortKey.parse()`** | 今天 `EconomyResolver.dotted(ClassKey)` 与 **app 侧 4 处内联拼接**（`EconomyOwnershipTimeParticipant.java:109`、`PopulationEconomyTimeParticipant.java:109/110/113`）是**既有的两处拼写点**，借这次收敛掉。★ **读写集能否对上全靠这一个串**，两边不一致 ⇒ 冲突检测**静默失效** |

★ 三条已转告 Agent B（`EconomyPayloads` / `EconomyResolver` 是它的文件）并回执 Agent C。

> 目的：H1 是**唯一改变经济行为**的批，也是最容易失控的一批。先把形状钉死，再派活。

### H1.0 身份映射（**已完成**）

`economy-api/cohort/HouseholdActors`：家户 actor = `ActorRef(HOUSEHOLD, "<hex>:<residence>:<stratum>")`，
`of(CohortKey)` / `cohortOf(ActorRef)` **互逆 + fail-closed**。★ 裁定 K9（不能用 `CohortKey.toString()`，含 `|` 会撞 `GoodsAccountKey` 的接缝）。

### H1.1 家户账 = actor 切片的 `GoodsAccount`（唯一持久真源）

键 `(actor, hex)`；家户的 hex = `CohortKey.hex()`。**不新增账户类型**（复用 S1 已建的 `GoodsAccount`）。

### H1.2 会话工作副本（裁定 K1）

- `EconomyDayStepper` 新增会话态：`LinkedHashMap<CohortKey, Map<CommodityId, Long>> householdGoods`
  —— **与它已经持有的 `flows` 累加器完全同形**（先例，不是新形态）。
- `EconomySettlement.settleOneDay(...)` 新增该参数（**就地更新**），与 `flows` 同一待遇。
- 协调器（`PopulationEconomyTimeParticipant`）：推进前从 actor 侧**载入** → 逐日 step → `finish()` 交出 → **逐日落回 actor**。
- ★ **它不进 `EconomyData`、不进变更集、不跨 revision 存活** ⇒ 不是第二本账（判据：`ClassRow` 无 `goods` 且守恒式无 `ΔΣRowGoods`）。

### H1.3 结算改道（★ 一处净简化）

- `ProductionSettlement.Outcome.cohortIntake` **删除**：受方就是**唯一那个家户** ⇒ 直接产出
  `ActorEntry(HouseholdActors.of(cohort), hex, commodity, +paid)`（与 actor 受方同一支）。
- `EconomySettlement.deliverCohortIntake` **删除**（E17 的 `+unresolved` 兜底随之不再需要：
  受方恒存在，除非该 cohort 没有 actor —— 那种情况要 **fail-closed 抛**，不许静默留账）。
- `ProductionLedger.cohortIntake` 字段**删除**（账里只剩 毛产 / 损耗 / 投入 / 产权条目 / 货币待办）。

### H1.4 消费与投入改读家户账

- `settleHexes`：`grainOf(row)` → 家户账余额；同格借粮的"可贷余粮"`lendableOf` 也读家户账。
- `drawCycleInputs` / `transferIntraHexInputs`：从**供给该产业的 cohort 的家户账**扣料
  （供方集合 = H0.3 已建的"由配额表推 cohort"那条推导）。
- `ClassRow.goods` **删除**（I6.1 与 I7.1 一次达成）。

### H1.5 播种

新增 `app/world/HouseholdSeeder`：对每个 `population > 0` 的行建一个家户 actor，
并把该行的创世库存搬进它的 `GoodsAccount`；`ClassRow` 只留人口 / 劳动 / 参与率 / 需求 / 压力。

### H1.6 守恒式去掉过渡项

`ΔΣRowGoods` 那一项**从式子里删掉**（它是"两套账并存"的产物）。★ **它还在 ⇒ 还有一本账没搬完**。

### H1.7 判据

- 结构：`ClassRow` 无 `goods`/`money`/`debts`/`meansOfProduction`；`ProductionLedger` 无 `cohortIntake`
- 逐值：I4.1（逐 actor 守恒）与 I4.2（全系统守恒，**无过渡项**）成立
- 真档：**改前有饭吃 ⇒ 改后仍有饭吃**（人口不因改造而崩）

### ★ 偏离（如实记）：H1.2 的"货币余额"推迟到 H4

今天**没有任何货币**（`MoneyAuthority` 无实现者、`FIXED_MONEY_*` 只定义不结算），
而 `ClassRow.money` 在 H1 里被删除 ⇒ **全仓没有任何"钱"字段**，
"别用裸 `long`"这条纪律**空转成立**。等 H4 真要有钱时**一次定型**，
比现在造一个恒空的钱包更省，也不会留下"看起来在记、其实没人读"的死字段（本仓明文反对）。

---

## 控制方收口记录（2026-09-27）

### 门禁的真数（**不是 rc**，是 surefire + 真退出码）

| 模块 | 命令 | 真结果 |
|---|---|---|
| `simos-economy-api` | `test` | **exit 0 · 23 条 / 0 失败** |
| `simos-economy` | `test`（不接管道取真退出码） | **exit 0 · 172 条 / 0 失败 / BUILD SUCCESS** |
| `simos-actor-api` + `simos-actor` | `test-compile` + `test`（Agent D 跑） | **exit 0 · 112 条 / 0 失败** |

★ **一处我自己的量具错，记下来**：第一遍我用 `… | tail -25; echo "[exit=$?]"` 读退出码 —— **`$?` 取的是 `tail` 的**，
于是把一次「报告里还有 1 条红」的旧产物读成了绿。**教训**：取 Maven 的退出码**不许接管道**（或显式用 `PIPESTATUS`）。
与 `AGENT.md` §三.1「不信 rc=0」同族，但这是它的**反面**：连 rc 都读错了。

### K10 裁定：`EconomySowingTest.eachClassRowDrawsItsOwnSeed...` 那条红

**判**：属**口径错**（K7 的"多数是后者"），不是护栏错 —— 该夹具的前提是"**0 人**的地主行占 800 亩并下种"，
而 K3 恰恰废除了它（产能在**产业**上；**没有人就没有份额**）。

**处置**：按新口径**最小重算**（不重建夹具 —— H3 的"投入由谁出"会再换一次口径，重建是白做）：
唯一有人口的贫农行拿到全部产业规模 400 亩，而它缸空 ⇒ **一分种也扣不出** ⇒ 整块地荒着 ⇒ 收获 0。
改动的断言：`cycleSeedUsedMilli` 80,000 → **0**；`grainOf(贫农)` `net` → **0**；
`grainOf(地主)` 去掉那一项 80,000。**债务那一段（聚合、本金、id）逐字未动** —— 它不受产出的影响。
★ **判别力仍在**：若取材改成"从全格池子扣"，地主的 5,000,000 会被拿来下种 ⇒ 那两条 `isZero()` 当场红。
★ 旧算式整段**保留在 javadoc 里并标注"H0/K3 之前的口径，留痕不改"**，新口径另起一段"〔H0 / K3 重算〕"。
★★ **H3 落地后本用例要再重算一次**（已写进注释，不静默）。

### `CohortKeyTest`（控制方亲修，economy-api）

我改的契约（`CohortKey` 两参 → 三参）把该测试弄坏（10 处）—— **是我的责任**，已全部修完：
11 处构造补 `ResidenceKind`、规范串改三段、`.residence()` 的语义从"格"变"居住类型"（另加 `.hex()` 断言）、
接缝测试从"第一个接缝"改为"第二个接缝之后整段交给阶层词表"（判别力等价），
并补上**居住词表守卫**与**居住段为空**两条 fail-closed 断言。

---

## ★★ H0 关账（2026-09-27）

### 提交（4 批 + 1 补完）

| 提交 | 内容 |
|---|---|
| `2ac36fc` | `refactor(actor)!` 产权表整块退役（S3 / H0.5 上半） |
| `95d9b03` | `refactor(economy)!` 行键 = 家户键 + 产能搬到产业（K2/K3） |
| `dfcbec3` | `feat(app)!` 播种器按 (格,居住,阶层) 建行 + 产权读口（H0.6/H0.7 的载荷侧） |
| `c161702` | `test(economy-api)+docs` CohortKeyTest 三参修复 + 两处陈旧注释 |
| `8c852ef` | `refactor(economy)!` **补完 H0.5 下半**：`ASSET_QUANTITY` 退役（Basis 六档 → 五档） |

### 判据实测（真数，非 rc）

- 全仓 `test-compile` **exit 0 / BUILD SUCCESS**（13 模块，main + test）
- 全仓 `test` **2482 条 / 0 失败 / 0 错误 / 0 跳过**，12 个模块全绿，前端门禁 **297/297**，
  282 份 surefire 报告 mtime 落在本轮
- 基线 **2518 → 2482（−36）**，账逐条对上：−14（AssetClassKeyTest 8 + AssetHoldingTest 6）
  − 3（ClassKeyTest）− 19（方法主语是产权表）；★ 另有 **2 条方法内断言**被删 ⇒ 用例数不变、覆盖少两处（留到关账补）
- `ClassKey` 残留 = 0（`grep -v AssetClassKey`）

### ★ Agent C 上报的两处口径后果 —— 控制方裁决（**留给 H1/H3，不许静默**）

1. ★★ **"取材取不满"**：`rowSharesOf` 按**人口占比**分摊、逐行向下取整，而纤维入账**集中在少数行** ⇒
   小夹具 6 座作坊只开 **4** 座、50 台织机只开 **48** 台；真档**年末剩 24,001,080 毫纤维**（旧口径"被织机取光"不再成立）。
   **裁决：算 K3 的已知代价，不在 H0 改** —— 理由三条：
   ① 分摊口径的**任意性**正是 H3（C3"投入由谁出"）要消灭的（届时由 `relation` 明说，而不是按人口猜）；
   ② **H1 本来就要再动 `drawCycleInputs`**（供方从"行库存"改成"家户账"）⇒ 现在 churn 期望值是白做；
   ③ C 已按**当前真实行为**重算 3 处字面量（`WEAVE_CLOTH_INTAKE 998,129→977,758`、
   `CRAFT_CLOTH_INTAKE 209,518→139,678`、`fiberCap 619→620` / 布 20,049,900→20,079,000）并注明理由，**没有放宽断言**。
   ★★ **C 的那句"若判为 bug（该用最大余数法）这些字面量要回退"原样带进 H1/H3 的任务书。**
2. `EconomyRealScaleClothTest` 的"第 2 周期不停工"判据原用 `producedBetween > 0`（含 operator **存量**），
   实测不再单调（第 2 周期 −1,665,975）⇒ 改成"本周期**关系入账** > 0"并注明；"布逐周期增长"两条一字未动。
3. C 还修正了子 Agent 的一处误改（3,098/24,784,000 被改成 3,097/24,776,000）—— 实测复算
   `⌊3100×6663/14806⌋+⌊3100×5182/14806⌋+⌊3100×2221/14806⌋+⌊3100×740/14806⌋ = 1395+1084+465+154 = 3,098` ⇒ 已回退。

### ★★ H1 冻结接口（控制方 2026-09-27 定；两个执行 Agent 必须一致，后续照此核）

1. **家户 actor** = `HouseholdActors.of(CohortKey)` ⇒ `ActorRef(HOUSEHOLD, "<hex>:<residence>:<stratum>")`；
   账户键 = `GoodsAccountKey(该 actor, cohort.hex())`。
   ★ economy 切片**看不见 `GoodsAccountKey`**（它在 `simos-actor`）⇒ economy 只产出
   `ActorEntry(actor, location, commodity, delta)`，**落账是 app 的事**（铁律 3）。
2. **会话工作副本** = `Map<CohortKey, Map<CommodityId, Long>>`（缺失键 = 该家户没有该商品）：
   `EconomyDayStepper(EconomyData, Map<…>)` **就地更新** + `householdGoods()` 访问器；
   ★ **绝不进 `EconomyData` / 变更集 / 跨 revision**（裁定 K1）。
   `EconomySettlement.settle(...)`（多日入口）**没有**副本 ⇒ 继续 fail-closed（消息指向 DayStepper）。
3. ★ **fail-closed 的边界**：行 `population > 0` 而副本里**没有**该 cohort ⇒ **抛**（不许静默当库存 0）；
   `population == 0` 的行**跳过消费与投入**；★ 但**分配（规则付款）不按人口过滤** ——
   地租那类规则**仍可能**付给人口 0 的家户。
4. ★ **家户 actor 为"每格两组四行"全部而建（含人口 0 的空账）** ⇒ 分配永不因"没有 actor"失败。
   真档判据 = **799 格 × 8 = 6392**。

### H1 的判据（阶段边界由控制方跑）

- ★ **I4.1** 逐 actor 守恒：`Δ账本 == 净产 − 实付`（逐 actor × 逐商品）
- ★★ **I4.2 无过渡项**：式子里的 `ΔΣRowGoods` **消失** —— 它还在 ⇒ 还有一本账没搬完
- ★ **结构**：`ClassRow` 无 `goods`；`ProductionLedger` 无 `cohortIntake`
- ★ **真档**：改前有饭吃 ⇒ 改后仍有饭吃；`GET /api/economy/ownership` 的 `accounts` 非空、
  且**行侧应为全 0**（一本账）
