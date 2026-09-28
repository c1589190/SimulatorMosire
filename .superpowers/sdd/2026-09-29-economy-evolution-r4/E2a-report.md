# R4-E2a 报告 —— 运行期需求账本 + 候选预设状态 + GM 命令 + 订单路径接线（进入/采用留 E2b）

> 切片：`E2a`。起点：HEAD `e2a48c49`（R4-E1），工作树干净。本片只改 `src/main/java`，不写/改测试，不跑 `test`/`verify`/SpotBugs，不 commit/push。
> 任务书：`docs/superpowers/plans/2026-09-29-economy-evolution-r4-plan.md` §2.E2 的**状态 + 命令 + 订单**部分；`EconomyEntrySettlement`/TRIALING 进入算法不在本片。
> 最终 shaded jar：`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 `8cacdd8b049cb2a4fcde782c40aec925`。

---

## 0. 结论

- 新增第 15/16 个经济状态组件：`demands: Map<DemandId, DemandEntry>`、`candidates: Map<CandidateId, ProductionCandidate>`；record / `empty()` / `withX` / 缺键 null ⇒ 空表 / 跨表守卫 / `EconomyChangeSet` / `EconomyCodec` 键反序列化器全部同步。
- 新增四条 GM 命令：`economy.SetMarketPrice`、`economy.AddDemand`、`economy.CancelDemand`、`economy.RegisterCandidate`；`Shell` 注册 + `CatalogTool.PAYLOAD_HINTS` 构造期 fail-closed 覆盖。
- 订单路径：`MarketRound`/`HexPlan` 只读需求账本 → `DemandTargets`（HOUSEHOLD 直归 / HEX 按人口最大余数摊分；TOTAL/PER_CAPITA；created/expires 过滤；priority 序）→ `ordersFor` 把「粮/布 35 天 lifeReserve 基线 + 有效需求目标」合成买/不卖目标；预算沿 `spendableMoneyOf` 与既有钱包冻结上限，基线先拿、需求按 priority 升序拿剩余；缺价不生成订单。
- `demands` 空表时订单量逐值等于 B.4/E1 基线：固定 tick0 世界 0→10 完整领域状态 A/B（HEAD 工作树 jar vs 本片 jar）**42 组件 0 差异**；0→120 与 HEAD 已关账 store 对比 **42 组件 changed=NONE**。
- GM 需求→订单→取消全链路在真实世界跑通（wool 需求下 `effectiveDemandMilli=10000`；取消后下一轮为 0；粮/布/纤维订单量与无需求基线逐值相等）；无价商品拒绝消息指名 `economy.SetMarketPrice`，定价后同一载荷成功；候选 `(id,version)` 重复被拒、旧 unit 的 modeKey 逐字节不变。
- 性能：0→120 8 线程最终 jar **34.218 s**（HEAD 基线同世界 34.313 s；B.3a-perf/B.4/E1 记录 34–36 s），峰值 `VmHWM=1,650,520 kB`（基线 1,783,784 kB）——无明显回退。

---

## 1. 新类型 / 状态组件 / 命令 / 订单路径改动

### 1.1 新 ID 与模型类型

| 文件 | 内容 |
|---|---|
| `simos-economy-api/.../id/DemandId.java`（新增） | opaque 值对象；`toString()`/`parse` 三件套。 |
| `simos-economy-api/.../id/CandidateId.java`（新增） | opaque 值对象；version **不编进 id**（版本是候选记录上的字段）。 |
| `simos-economy/model/DemandEntry.java`（新增） | `DemandEntry(DemandId, DemandScope, Optional<HouseholdId>, Optional<HexCoord>, CommodityId, DemandKind, DemandUnit, long quantityPerCycle, long createdDay, long expiresDay, int priority, String source)`；嵌套枚举 `DemandScope{HOUSEHOLD,HEX}` / `DemandKind{RECURRING,ONE_OFF}` / `DemandUnit{TOTAL,PER_CAPITA}`。 |
| `simos-economy/model/ProductionCandidate.java`（新增） | `ProductionCandidate(CandidateId, int version, CommodityId output, Map<CommodityId,Long> outputPerUnit, Map<CommodityId,Long> inputPerUnit, Map<AssetKind,Long> requiredAssets, long laborPerUnit, long buildDays, long cycleDays, RegimeId, LaborSource, Set<AssetShare.RightKind>, Optional<ActorRef> assetSource, String name)`；`static String modeKeyOf(CandidateId,int)` + 实例 `modeKey()` = `id.value()+"@"+version` 的**唯一拼写点**。 |

构造期不变量（不静默归一）：

- `DemandEntry`：scope=HOUSEHOLD ⇒ household 必须有、hex 必须空；scope=HEX ⇒ 相反；`quantityPerCycle > 0`；`createdDay ≥ 0`；`priority ≥ 0`；所有引用/枚举不得 null（`Optional` 本身不得 null）。`effectiveOn(day)`：`createdDay > day` 不生效；`expiresDay >= 0 && expiresDay < day` 不生效；`expiresDay < 0` = 永久（即 `expiresDay == day` 仍生效，与任务书口径一致）。
- `ProductionCandidate`：`version ≥ 1`；`buildDays ≥ 0`；`cycleDays ≥ 1`；`laborPerUnit ≥ 0`；`outputPerUnit` 非空且逐值 > 0、必须包含 `output`；`inputPerUnit`/`requiredAssets` 逐值 ≥ 0；三张表保序不可变（`LinkedHashMap` + `unmodifiable*`）；`acceptedRightKinds` 不得含 null（**允许空集**，E2b 判不可行）；`name` 非空白。

### 1.2 `EconomyData` 第 15/16 组件同步清单

- record 尾部追加 `Map<DemandId, DemandEntry> demands, Map<CandidateId, ProductionCandidate> candidates`；`empty()` 16 张空表。
- 缺键（null）⇒ 空表（旧档不失败）；`withDemands` / `withCandidates` 各一个唯一写入口，其余组件原样带过。
- 跨表守卫（compact 构造器内、全部冻结后）：
  - `demands`：键 == 行内 id；HOUSEHOLD 的家户必须存在于 `classes`；HEX 的格必须在 economy 侧可定位（该格有产业 / 有家户行 / 有市场行三者任一，`hexRegistered`）。
  - `candidates`：键 == 行内 id；`regime` 必须是 `RegimeOperators.registered()` 里的已登记档（feudal/household/handicraft/tenant）；`output` 必须出现在本行 `outputPerUnit` 里。资产 key 由 `AssetKind` 类型本身限定为合法枚举。
- 旧档缺键 ⇒ 空表：`EconomyData.empty()` / codec / 载荷 / Timeline 直读四条路径都经同一个 compact 构造器。

### 1.3 `EconomyChangeSet` / `EconomyCodec` / 播种与结算带过

- `EconomyChangeSet` 追加 `FieldDelta<DemandEntry> demands`、`FieldDelta<ProductionCandidate> candidates`；null ⇒ `Unchanged`（旧 changeset 没提 = 没动）；`between`/`apply`/`isEmpty` 同步；rebuild 键解析器 = `DemandId::parse` / `CandidateId::parse`。
- `EconomyCodec.keyModule()` 注册两个 Map 键反序列化器；值侧走 Jackson record 绑定，不需要兼容层（新类型是本片首次落盘）。
- `EconomyPayloads.toData`：创世载荷不声明 demands/candidates（两个空表）。
- `EconomySeedHandler`：按格追加播种时 `base.demands()` / `base.candidates()` **原样带过**（否则再播一国会把 GM 注入的需求/预设静默抹掉）。
- `EconomyStateBuilder.build`：日结算原样带过 `base.demands()` / `base.candidates()`（需求/候选不参与结算写回）。

### 1.4 四条 GM 命令

全部 `HandlerOutcome.Applied(EconomyChangeSet.between(base, target))` / `Rejected(具名)`；不实现 `CommandTargets`（GM `simos.command.submit` 路径可用；directive 内会被既有权限层 fail-closed 拒，见 §6）。`Shell` 注册 4 个 handler；`CatalogTool.PAYLOAD_HINTS` 同批补 4 条（Shell 启动成功即证明构造期覆盖断言通过）。

| 命令 | 载荷 | 语义与边界 |
|---|---|---|
| `economy.SetMarketPrice` | `q, r, commodity, price` | `price > 0`（0/负 ⇒ Rejected）；该格无 market 行 ⇒ 以 `MoneyVocabulary.SILVER_CURRENCY` 计价创建空市场（只放该商品价，不造商品/货币/账户）；已有市场 ⇒ 保留原 numeraire，仅 upsert 该商品价；**只写 `markets`**。 |
| `economy.AddDemand` | `scope, household?, hex{q,r}?, commodity, kind, unit, quantityPerCycle, createdDay?, expiresDay?, priority?, source?, id?` | HOUSEHOLD：`household` 必须存在，用其**居住格**价表校验；HEX：`hex` 必须给，用该格价表校验。缺市场/缺该商品价 ⇒ Rejected 且消息指名 `economy.SetMarketPrice`。`quantityPerCycle > 0`、`createdDay ≥ 0`、`priority ≥ 0`；scope↔household/hex 互斥与必填。`id` 缺省 ⇒ 确定性生成 `demand-<scope>-<主体>-<商品>-<kind>-<unit>-<序号>`（同前缀现有 id 尾段最大值 + 1；首条为 `-0`；尾段不可解析/溢出 ⇒ 拒，不猜）；显式 id 已存在 ⇒ 拒。PER_CAPITA 在命令期对家户/该格人口做 `multiplyExact` 溢出预检。**只写 `demands`**。 |
| `economy.CancelDemand` | `demand` | 不存在 ⇒ Rejected；存在 ⇒ 从表里移除；**只写 `demands`**。 |
| `economy.RegisterCandidate` | `id, version?(1), name?, output, outputPerUnit, inputPerUnit?, requiredAssets?, laborPerUnit?, buildDays?, cycleDays, regime, laborSource?, acceptedRightKinds?, assetSource?` | `(id,version)` 已存在 ⇒ Rejected（修订必须新 version）；同一 id 已有版本 ⇒ 新 version 必须**严格大于**当前版本；通过后替换该 id 的当前版本。`modeKey=id@version` 由 `ProductionCandidate.modeKeyOf` 唯一给出；**既有 unit 的 `modeKey` 一字不动**。**只写 `candidates`**，不建 unit/不改关系/资产/劳动/市场。 |

默认值（命令层，可省略）：AddDemand `createdDay=0`、`expiresDay=-1`、`priority=0`、`source="gm"`；RegisterCandidate `version=1`、`name=id`、`laborPerUnit=0`、`buildDays=0`、`laborSource=SELF`、`inputPerUnit/requiredAssets=空表`、`acceptedRightKinds=空集`、`assetSource=空`。

### 1.5 订单路径（GM 需求 → 真实买单）

- **只读传入**：`MarketSettlement.MarketRound` 增 `Map<DemandId,DemandEntry> demands`；`EconomySettlement.settleOneDay` 从 `base.demands()` 传入，`MarketReadout.deriveInternal` 从 `data.demands()` 传入；R2 并行 worker 的 `readOnlyPlanningRound` 与分区克隆 `localRound` 原样传 `ctx.round.demands`。
- **逐格预计算**：`HexPlan` 增 `Map<ActorRef, Map<CommodityId, List<Long>>> demandParts`（list 序 = 预算优先级序）。`DemandTargets.partsForHex`（新增包内类）：
  - 现算，不落第二份状态；先按 `effectiveOn(day)` 过滤；
  - HOUSEHOLD 范围直接归该户；`PER_CAPITA` = 每人量 × 该户人口，`TOTAL` = 每周期总量；
  - HEX 范围按本格家户人口摊到户：家户按 `HouseholdId.value()` 升序（并列序），`ProportionalSplit.byDenominator` 最大余数法；`PER_CAPITA` 先乘本格总人口，`TOTAL` 直接摊；
  - 多条需求按 `(priority 升序, DemandId 值升序)` 追加进 list；同一（户,商品）多段，调用方逐段扣预算。
- **`ordersFor`**：
  - 家户目标 = 基线 lifeReserve（粮/布 35 天）+ Σ 有效需求分段；经营者仍只补 necessary（需求不归经营者）。
  - **不卖一侧**：`sellable = max(0, stock − frozen − necessary − (life + demandTarget))` —— 想买的东西不再被同轮当余量卖掉；非粮/布商品也进这个目标（不再被 `selfNeedOf` 静默忽略）。
  - **预算**：沿用 `spendableMoneyOf` 与 `Budget`/钱包冻结上限；`allocateQuantity` 先给基线缺口，再按 demandParts 的 priority 序逐段拿剩余买得起量；`gap`/`price` 口径与旧实现逐值一致（`affordable = budget×1000/reference`）。
  - **缺价不生成订单**：市场价表没有该商品 ⇒ 外层 `market.prices()` 循环不会进入，`planOrders` 对 `reference<=0` 也直接返回空。
- **读口**：`collectBuyerOutcomes` 的 `desired` 改为「基线 + 有效需求总量」（与订单生成走同一段 `DemandTargets`），所以 `MarketReport`/`MarketReadout` 的 `gap`/`effectiveDemandMilli` 与订单同源；`marketReadout` 不新增状态字段。

### 1.6 读口 `ApiViews.economyHex`

- 新增 `demands`：本格相关需求（HEX 命中本格 + HOUSEHOLD 住在本格），逐条 `{id,scope,household,hex,commodity,kind,unit,quantityPerCycle,createdDay,expiresDay,priority,source,effective}`；`effective` 由当前 tick 现判（tick<0 ⇒ null）。按 id 值排序。
- 新增 `candidates`：全量候选（世界级、与格无关），逐条 `{id,version,modeKey,name,output,outputPerUnit,inputPerUnit,requiredAssets,laborPerUnit,buildDays,cycleDays,regime,laborSource,acceptedRightKinds,assetSource}`；按 id 值排序，map 键字典序。
- 不新增 `EconomyResolver` 地址 kind，不改 `MarketReport`/`MarketReadout` 的字段形状。

---

## 2. 编译 / spotless 原文

```text
$ tools/mvn-lock.sh spotless:apply
[INFO] BUILD SUCCESS
（exit 0，/tmp/e2a-final-spotless-apply-verbose.log）

$ tools/mvn-lock.sh spotless:check
[INFO] BUILD SUCCESS
（exit 0，/tmp/e2a-final-spotless-verbose.log）

$ tools/mvn-lock.sh -DskipTests compile
[INFO] EconomySimos ....................................... SUCCESS [  0.677 s]
[INFO] SimosApp ........................................... SUCCESS [  0.653 s]
[INFO] BUILD SUCCESS
（exit 0，/tmp/e2a-final-compile-verbose.log；validate 期 checkstyle 已随 compile 跑，无违规）

$ tools/mvn-lock.sh -Dmaven.test.skip=true package
[INFO] EconomySimos ....................................... SUCCESS [  0.586 s]
[INFO] SimosApp ........................................... SUCCESS [  2.407 s]
[INFO] BUILD SUCCESS
（tests 未编译/未运行；md5=8cacdd8b049cb2a4fcde782c40aec925）
```

---

## 3. 旧基线 A/B

### 3.1 固定 tick0 世界 0→10（同会话、同 store 副本、8 线程）

- 世界：`/tmp/b3a-perf-120base-store`（= `/tmp/b3a-store2` 的 rev1–4 裁出；rev1–4 changeset SHA-256 与 B.3a-perf/B.4/E1 固定夹具逐字节相同）。
- 基线：`git worktree add --detach /tmp/e2a-base e2a48c49` + 在该 worktree `tools/mvn-lock.sh -Dmaven.test.skip=true package`；jar md5 `5575fa38a9944115a0ab57ca3fa03369`（`/tmp/e2a-base-worktree.jar`）。
- 本片：最终 jar md5 `8cacdd8b…`。
- 比较方法：Python 重放两份 `simos.db` 的全部 revision changeset（`/tmp/b3a_perf_state.py` 的 replay），对 actor/economy/map/sd/social/unit 的每个组件 final map 做规范 JSON 比较；新建组件在旧侧不存在时按**空表**处理（缺键 = 空表的语义）。

| | 基线 5575fa38 | 本片 8cacdd8b |
|---|---:|---:|
| 0→10 墙钟 | 5.743 s | 5.915 s |
| 组件数 | 42 | 42 |
| 差异组件 | — | **0 / 42** |

- 新侧 rev1–4 无 demands/candidates 键；rev5（advance 后）两者都是 `unchanged` ⇒ 新增空组件不产生任何状态变化。
- 证据：`/tmp/e2a-worktree-base-store`、`/tmp/e2a-final2-ab-store`、`/tmp/e2a-worktree-base-state.json`、`/tmp/e2a-final2-ab-state.json`、`/tmp/e2a-final2-ab-diff.txt`。

### 3.2 0→120 完整领域状态（HEAD 已关账 store vs 本片）

- 基线 store：`/tmp/e1-store-final2`（HEAD `e2a48c49` 0→120 8 线程，E1 报告 34.183 s，且已被 E1 验证与 B.4 0→120 store `changed_components=NONE`）。
- 本片 store：`/tmp/e2a-final2-perf-store`（本片最终 jar 0→120 8 线程，34.218 s）。

```
components=42
changed_components=NONE
population = 11,622,725
alloc rows/ΣlaborMilli = 33,793 / 5,559,098,523；max(Σalloc−available) = −1,260
asset_share_total = 2,286,937,777（8,940 行）
money(silver) = 142,924,800
goods = {grain:164,011,560,198, fiber:23,134,775,234, cloth:12,745,741,800, iron:263,410,000, tool:52,682,000}
units/relations/operatorConditions = 8,940 / 8,940 / 8,940
demands/candidates 最终 = 0 / 0
```

证据：`/tmp/e2a-final2-full-state-diff.txt`、`/tmp/e2a-final2-perf-advance.json`。

> 说明：0→120 的基线墙钟 34.313 s 用同 HEAD 的现有 shaded jar（`/tmp/e2a-base.jar`，md5 `44e0811aa9319fecbec1a4f14096776b`，与 E1 报告 md5 一致）实跑；0→10 的基线用 worktree 现场 package 的 jar（md5 `5575fa38…`）。两者同源同行为。

---

## 4. GM 需求 → 订单 → 取消 / 命令边界（真实世界，MCP）

场景世界：固定 tick0 副本；HEX `(-55,-65)`（该区 anchor，4+4 家户、有市场、人口 21,741）。命令：
`economy.SetMarketPrice {q:-55,r:-65,commodity:"wool",price:10}` → `economy.AddDemand {id:"e2a-wool-hex",scope:"HEX",hex:{-55,-65},commodity:"wool",kind:"RECURRING",unit:"TOTAL",quantityPerCycle:10000,createdDay:0,expiresDay:-1,priority:0,source:"gm"}` → `advance 0→5` → `CancelDemand {demand:"e2a-wool-hex"}` → `advance 5→10`。

`simos.economy.hex q=-55 r=-65` 的 `marketReadout` 逐商品 `effectiveDemandMilli`（= 同一 `planOrders` 生成的买单总量）：

| 时点 | 世界 A（无 wool）grain / cloth / fiber / wool | 世界 B（wool 需求）grain / cloth / fiber / wool |
|---|---|---|
| tick0（B 刚注入需求、未推进） | 6,647,499 / 3,399,597 / 4,800,000 / 无行 | **6,647,499 / 3,399,597 / 4,800,000 / 10,000** |
| tick5（0→5 后） | 0 / 3,399,597 / 4,800,000 / 无行 | **0 / 3,399,597 / 4,800,000 / 10,000** |
| tick10（B 取消后 5→10） | 0 / 3,399,597 / 4,800,000 / 无行 | **0 / 3,399,597 / 4,800,000 / 0** |

- wool 订单/有效需求在 D+1 开市日可见（10000 > 0）；取消后下一轮归 0；B 的 `demands` 表在 tick10 为空数组。
- 粮/布/纤维的订单量与无需求世界 A 在三个时点逐值相等 ⇒ 基线订单量不受影响。
- wool 的 `supplyMilli=0`（这个世界还没有牧羊 unit/卖家 ⇒ 买单可见但不会成交）；进入生产/成交留 E2b，本片如实记边界。
- 证据：`/tmp/e2a-final2-baseline-run.json`、`/tmp/e2a-final2-wool-run.json`、`/tmp/e2a-final2-gm-evidence.txt`；每轮卖单/买单由 `MarketSettlement.planOrders`（MCP 读口内部同一条实现）给出。

### 4.1 `SetMarketPrice` 边界

- `price<=0` ⇒ Rejected（合成 handler 探针：`E2aHandlerProbe PASS=12/0`）。
- **该格无市场行**：合成探针证明新建市场 numeraire=silver、只含该商品价、变更集只有 `markets` 组件变化（`E2aHandlerProbe`）。
- **已有市场**：真实世界给 `(-55,-65)` 的既有 silver 市场 upsert `wool` 价；`market.prices` 保留 grain/cloth/fiber/tool/iron，只多 wool。

### 4.2 `RegisterCandidate` 边界

- v1 committed（rev7）；重复 `(id,version)` Rejected，head 不变：
  `"候选 (id,version) 已存在，拒绝覆盖: cand-wool@1（修订必须用新 version）"`。
- v2 committed（rev8），读口 `modeKey="cand-wool@2"`；v2 之后再用 v1 ⇒ Rejected：
  `"候选修订必须使用严格更大的 version（当前 2，收到 1）: cand-wool"`。
- 候选命令前后（rev6→rev8）除 `candidates` 外全部组件差异 **0**；`economy.units` 8,940 行逐字节相同 ⇒ 既有 unit 的 modeKey 不受修订影响。
- 证据：`/tmp/e2a-final2-c-run.json`；比对见 `/tmp/e2a-candidate-run.json`（前一版同类运行）。

### 4.3 新商品无价 → 定价后成功

- `economy.AddDemand`（`wood`, HEX `(-55,-65)`, ONE_OFF TOTAL 5000）⇒ Rejected，head 4→4：
  `"该格 -55_-65 没有商品 wood 的价（请先用 economy.SetMarketPrice 给该格定价）"`。
- `economy.SetMarketPrice {wood,7}` committed（rev5）；**同一载荷** AddDemand committed（rev6）；读口 `demands` 出现该条（`effective=true`），市场 wood 价=7。
- 证据：`/tmp/e2a-final2-c-run.json`。

### 4.4 命令副作用面 / 半截 revision

- 所有成功命令只改目标组件：SetMarketPrice→`markets`；AddDemand/CancelDemand→`demands`；RegisterCandidate→`candidates`（合成探针与真实 store 重放均验证）。
- 所有 Rejected 的 head 不变（MCP 证据：4→4、7→7、8→8）；成功/失败都经 `HandlerOutcome`，不产生半截 revision。
- `AddDemand` 的确定性自动 id 也验证通过：首条 `demand-HEX-0_0-wool-RECURRING-TOTAL-0`（合成探针 `E2aHandlerProbe PASS=12/0`）。

---

## 5. 性能 / 回归读数

| 项目 | 读数 |
|---|---|
| 0→120 8 线程（固定 tick0，最终 jar 8cacdd8b） | **34.218 s**（`/tmp/e2a-final2-perf-advance.json`） |
| 0→120 基线同世界（HEAD jar 44e0811a） | 34.313 s（E1 报告 34.183 s；B.3a-perf 36.023 s；B.4 35.27 s） |
| 峰值内存（最终 jar） | `VmHWM=1,650,520 kB`、结束 `VmRSS=1,580,160 kB`（≈1.57 GiB） |
| 峰值内存（基线 jar） | `VmHWM=1,783,784 kB`、`VmRSS=1,744,088 kB` |
| 0→10 A/B（8 线程） | 基线 5.743 s / 本片 5.915 s；42 组件 0 差异 |
| 旧档回归 | `Probe3 PASS=60 FAIL=0`；`Probe5 PASS=11 FAIL=0`；`Probe2 /tmp/world-rev4.json /tmp/world-rev2.json` 两条 `OK modules=[social, economy, actor, map, sd, unit]` |
| 旧档缺新键 | `/tmp/economy-rev4.json`（economy 模块节点，剥顶部包装 `@class` 后经 `EconomyCodec.decodeChangeSet`）⇒ `demands=0/Unchanged、candidates=0/Unchanged`，industries 522/classes 1848/units 522 正常；旧快照缺两键经 `decodeSnapshot` 同样空表（`OldSnapshotProbe`） |
| 新组件 codec 往返 | `E2aCodecProbe PASS`：changeset/snapshot 两条 encode→decode→apply，demands=2/candidates=1，`modeKey=cand-wool@2` |
| 需求摊分/订单单测式探针 | `E2aProbe PASS=29 FAIL=0`（TOTAL/PER_CAPITA、最大余数并列序、created/expires 过滤、priority 预算切分、不卖保留、ONE_OFF 每轮同量） |

`demands` 空表时的新增开销：`DemandTargets`/`totalsByHex` 在空表直接返回 `Map.of()`；`ordersFor` 只多一次 `addExact(0)`。因此 0→120 墙钟与基线同带。

---

## 6. 与计划 / 任务书不同处（如实记）

1. **ONE_OFF 不做跨轮剩余递减**：一个有效窗口内每轮都按整份目标量处理；取消/到期是唯一的终止手段。任务书明确允许此边界。
2. **不做进入/采用算法**：无 `EconomyEntrySettlement`、无 TRIALING unit、无 buildDays/cycleDays 实际生产、无候选自动采用；`candidates` 只是登记 + 读口。
3. **`naturalNeeds` / `effectiveDemand` 不落新值**：计划 E2 全量版的「naturalNeeds 写当日人均+demand 份额、effectiveDemand 写实际买量」未做；本片的需求可见性走 `economyHex.demands[]` + `marketReadout.effectiveDemandMilli`（由同一 `planOrders` 现算），`ClassRow.naturalNeeds/effectiveDemand` 形状与写入点未动。
4. **candidates 表每个 `CandidateId` 只留当前版本**：任务书要求「修订必须新 version，旧 unit 保持旧 modeKey」；在 `Map<CandidateId, ProductionCandidate>` 的键形状下，新 version 会**替换**该 id 的旧行。「旧 unit 的 modeKey 原样保留」已逐字节验证；但旧版本的候选行在修订后不再可查。计划原文「修订 = 新 version 新行」与 `Map<CandidateId,…>` 的键唯一性冲突，本片按任务书给出的 Map 形状实现并记此边界。
5. **修订版本必须严格递增**：同一 `(id,version)` 拒；小于当前版本也拒（不只「不同」）。避免用旧版本回滚当前表。
6. **regime 合法性收窄为 `RegimeOperators.registered()` 四档**：未登记的制度在 E2b 建 relation 时必抛，故登记期 fail-closed；这是对「regime 合法」的保守解释。
7. **`acceptedRightKinds` 允许空集**：登记合法，E2b 判不可行；不发明「默认全收」。
8. **`AddDemand` 的缺省值**：`createdDay=0`、`expiresDay=-1`、`priority=0`、`source="gm"`；`id` 可省略并用确定性序号生成（计划只给了字段，没有规定缺省/生成规则）。首条自动 id 序号从 0 起。
9. **`SetMarketPrice` 不校验格在图上**：只要求 hex 坐标合法 + price>0（任务书未要求 map 校验；HEX 需求的存在性守卫反而接受「先有市场、后有人口」的合法顺序）。
10. **需求目标量不新增状态字段**：按任务书「不新增状态」，摊分每次现算；逐 hex 扫 demand 表（表通常很小），不引入 unit×allocation 全表扫描。
11. **`CommandTargets`/directive 未实现**：四条命令都是 `CommandHandler`，走 GM `simos.command.submit`；与 `economy.MigrateHousehold` 同款，directive 内会被既有权限层 fail-closed 拒。
12. **`EconomyResolver` 未加 `demand`/`candidate` 地址 kind**；读口走 `economyHex`。
13. **新商品买卖的现实边界**：本片世界的 wool 没有卖家，所以验证到「买单/有效需求 >0」与「不卖保留」为止，不验证成交；这是 E2b 的进入算法接通后才能覆盖的链路。

---

## 7. 我没做 / 没验证的

- **测试源码未动、未编译**：既有测试仍按 14 参 `EconomyData`/`EconomyChangeSet` 构造；本片只保证 `src/main/java` 编译过，`-DskipTests compile` 不编译测试，`package` 用 `-Dmaven.test.skip=true`。测试适配按纪律留给 V 阶段。
- 未跑 `test`/`verify`/SpotBugs；未做变异自证（不是本片职责）。
- 未实现 E2b（进入/采用/TRIALING/buildDays）、E3（经验）、E4（梯度消费）；未改价格、粮布保留、播种、`SECONDARY_PER_MILLE`、租率、`StressPolicy`、退出/生产逻辑。
- 未做**非空 demands 的 1/4/8 线程确定性**对比：本片 8 线程只跑空 demands 的真实 0→120；需求摊分/预算优先级是单线程合成探针验证的。
- 未做**带真实卖家的需求成交守恒**：wool 无卖方，验证的是订单生成与不卖保留，不是成交/账户守恒。
- 未做 HOUSEHOLD scope PER_CAPITA 的真实世界 GM 端到端（合成 `E2aProbe` 覆盖 HOUSEHOLD TOTAL/HEX PER_CAPITA；`AddDemand` HOUSEHOLD 价格校验用合成 handler 探针覆盖）。
- 未做更老历史 store 的兼容（M2 `useRights` 旧档在 HEAD 基线上本就因 `useRights` 严格绑定不可读，是 B.2 起记录的既存阻断，不是 E2a 回归）；旧档验证只覆盖 Probe2/Probe3/Probe5 三档 fixtures、`/tmp/economy-rev4.json` 与旧快照缺键。
- 未做全年/360 tick、1/4/8 全矩阵、JFR/峰值内存正式协议；峰值 RSS 是 `/proc/<pid>/status` 过程读数。
- 未改/未验证 `ProductionCandidate` 的旧 version 行在修订后仍可查（见 §6.4 的边界）。
- 未验证 `SetMarketPrice` 在「格不在图上」时的行为（命令不校验地图存在性）。
- 未 commit / 未 push（按纪律，等控制方审后提交）。

---

## 8. 数值行为变化 / 硬编码字面量清单（给测试代理）

**会改变数值行为的改动（仅当 `demands` 非空时）**

- `MarketSettlement.ordersFor`：家户目标 = 基线 lifeReserve + Σ有效需求；`sellable` 一侧同时扣除需求目标。`demands` 空表时逐值等于旧路径（0→10/0→120 A/B 0 差异已验）。
- 需求摊分：HEX 范围按本格家户人口 `ProportionalSplit` 最大余数法；HOUSEHOLD 直归；`PER_CAPITA` 乘人口；`TOTAL` 直接摊。并列序 = `HouseholdId.value()` 升序。
- 预算优先级：基线先拿，需求按 `(priority, DemandId)` 升序拿剩余买得起量（`allocateQuantity`）。
- 订单/报告的 `desired`：`collectBuyerOutcomes` 在需求存在时加需求目标（只影响 `MarketReport.BuyerOutcome.desiredQty`/`gapQty` 与读数，不影响状态）。
- `demands` 空时：新增代码全部走早退/加 0，不改变任何既有数值；既有价格、粮布 35 天保留、`SECONDARY_PER_MILLE`、租率、`StressPolicy`、播种、退出/生产逻辑均未动。

**新引入的字面量 / 默认值（测试代理需要知道的）**

- `DemandEntry`：`quantityPerCycle>0`、`createdDay≥0`、`priority≥0`、`expiresDay=-1` 表示永久。
- `AddDemand` 默认：`createdDay=0`、`expiresDay=-1`、`priority=0`、`source="gm"`；自动 id 前缀 `demand-<scope>-<主体>-<商品>-<kind>-<unit>-`，序号 = 同前缀现有尾段最大值 + 1，**首条为 0**。
- `SetMarketPrice` 新市场 numeraire = `MoneyVocabulary.SILVER_CURRENCY`（`"silver"`）；`price>0`。
- `RegisterCandidate` 默认：`version=1`、`name=id`、`laborPerUnit=0`、`buildDays=0`、`laborSource=SELF`、`inputPerUnit/requiredAssets` 空表、`acceptedRightKinds` 空集；`modeKey=id@version`（`ProductionCandidate.modeKeyOf` 唯一拼写点）。
- 已登记 regime 集合来自 `RegimeOperators.registered()`：`feudal/household/handicraft/tenant`（候选登记 fail-closed 收窄）。
- 变更的既有硬编码：无（未改任何既有常量值；`MARKET_LIFE_RESERVE_DAYS` 仍 5+30=35、`MARKET_BID/ASK_PER_MILLE`、`SECONDARY_PER_MILLE=300`、`TENANT_RENT_PER_MILLE=300` 等一字未动）。

**测试适配提示（V 阶段）**

- `EconomyData`/`EconomyChangeSet` 现在是 16 个组件；既有测试源码按 14 参构造，`test-compile` 会红（本片按纪律未动）。夹具若直接构造 record，需在尾部补 `Map.of(), Map.of()`（或改用 `withDemands/withCandidates`）。
- 旧档重放探针（`Probe3`/`Probe5` 的 `onlyIndustries`/`relationsRemove` 助手）也需要在 `new EconomyChangeSet(...)` 尾部补两个 `FieldDelta.Unchanged<>`；本片在 `/tmp/e2a-probes-final/` 已用补丁版验证 `PASS=60/0`、`PASS=11/0`。

**改动文件清单**

- 新增：`CandidateId.java`、`DemandId.java`、`DemandEntry.java`、`ProductionCandidate.java`、`EconomySetMarketPriceHandler.java`、`EconomyAddDemandHandler.java`、`EconomyCancelDemandHandler.java`、`EconomyRegisterCandidateHandler.java`、`EconomyCommandPayloads.java`、`DemandTargets.java`。
- 修改：`EconomyData.java`、`EconomyChangeSet.java`、`EconomyCodec.java`、`EconomyPayloads.java`、`EconomySeedHandler.java`、`EconomyStateBuilder.java`、`EconomySettlement.java`、`MarketSettlement.java`、`MarketReadout.java`、`Shell.java`、`CatalogTool.java`、`ApiViews.java`。
- 报告：`.superpowers/sdd/2026-09-29-economy-evolution-r4/E2a-report.md`（不 commit）。
