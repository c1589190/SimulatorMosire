# R4-B.3a 新世界多 unit 播种报告（庄园自营 + 佃耕 + 家户自用）

> 切片：B.3a（只做**新世界播种**；不含 B.3b 的资产转移/显式停业命令与 c1 孤儿债对账）。
> 代码基线：`915d328b`（R4-B.2）之上，工作树只改一个文件；未 commit。
> 证据目录：本文引用的全部探针/读数/日志都在 `/tmp/b3a-*`（不进仓库）。

---

## 1. 改动文件 / 方法

### 1.1 改动面

| 文件 | 改动 |
|---|---|
| `simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java` | **唯一改动的生产文件**（+457/−23）：新增拆分常量/中间体/载荷构造；重排每格"先发配额、再拆分"的顺序 |
| `simos-economy/.../spi/EconomyPayloads.java` | **未改**：既有解析器已支持"unit 节点可选 `relation`"与 `assetShares[].owner != operator`（B.2 就绪），本片只是第一次真正写这两种载荷 |
| 结算/市场/StressPolicy/价格/人口常量 | **未改**（硬约束） |

方法清单（同一文件）：

- **改**：每格主循环（把 `units/assetShares` 的发出移到"配额发完之后"）、`industry(...)`（多收 `kind`、
  把 `capacityPerUnit` 留一份定点整数进 plan）、`IndustryPlan` record（加 `kind` / `capacityPerUnit`）、
  `unitOf(plan)`（改走共同构造）、`assetSharesOf(plan)`（改收"要发的量"）。
- **加**：`splitIndustry`、`secondaryAllocation`、`householdUnit`、`farmTenantUnit`、`outputShareRule`、
  `unitOf(plan, HouseholdUnit)` / `unitOf(plan, ActorRef, ProductionRelation)`、`actorNode`、`recipientNode`、
  `relationNode`、`ruleNode`、`assetShareNode`；中间体 `IndustrySplit` / `HouseholdUnit` / `MovedRow` / `LaborMove`；
  常量 `SECONDARY_PER_MILLE` / `TENANT_RENT_PER_MILLE` / `LANDLORD_SLOT_INDEX`。

### 1.2 新增常量（都在 `EconomySeeder` 一处）

```java
public static final int SECONDARY_PER_MILLE = 300;   // 副 unit 合计拿 300‰；主 unit 拿 700‰
public static final int TENANT_RENT_PER_MILLE = 300; // 佃租/匠户分成率（‰）
private static final int LANDLORD_SLOT_INDEX = 3;    // CLASS_IDS 里 landlord 的下标
```

### 1.3 拆分算法（确定、守恒、可复现）

在每格的既有配额（`appendAllocation` 产出、逐值不变）与整份 capacity 之上，`splitIndustry(plan, allocations, hex, landlordPopulation)` 做四步：

1. **劳动**：只取 `activity == 主 unit id` 的行，`moved = ⌊laborMilli × 300 ÷ 1000⌋`；`moved ≤ 0` 的行原样留下。按
   `HouseholdId` 去重累加 moved（`Math.addExact`）。主行减 moved；副 unit 新发一条
   `id = LaborAllocation.idOf(副 unit, group, household)`、`activity = 副 unit id`、`actor = 家户 actor`，`group/household/period` 照旧。
   ⇒ **逐 (batch, household) 的 Σ 配额逐值不变**，`Σ allocated ≤ available` 由构造保证。
2. **候选排序**：候选家户按 `HouseholdId.value()` **升序**（canonical id 序）。
3. **资产**：逐 asset `secondaryTotal = ⌊capacity × 300 ÷ 1000⌋`，用**最大余数法**（`splitProportional` →
   `ProportionalSplit.byDenominator`，分母 = Σ权重，余数按"余数降序、同余数下标升序"分派）按各户 moved 劳动切分。
   **并列规则因此 = canonical id 升序**（下标序就是已排序的 id 序）。每户每个 asset 都 ≥ `capacityPerUnit` 才建副 unit；
   否则该户**整体跳过**，其份额与劳动都留在主 unit。
4. **主 unit 剩余** = `capacity − Σ(已建成副 unit 的份额)`（逐 asset 减，`Math.addExact`）。副 unit 的
   `AssetShare.idOf(...)` sequence 由现有载荷解析器 `EconomyPayloads.addAssetShare` 的状态内确定性计数给出
   （同一 `(industry, asset, owner, operator, kind)` 从 0 递增），seeder 不落 id 字段。

**退化处理（实测逼出来的）**：farm tenant 的 owner/受方本来是"该格农村 landlord 家户"；但当 **operator 就是 landlord 家户本人**
（799 个农业格各一个）时，租规则会铸出"两端相等"的转移，被 `Transfer` 的 fail-closed 守卫当场拒。现在这种**自租退化**与
"地主不存在/人口 0"同档退回 `ESTATE:farm@hex`（`ToActor(ESTATE)`），规则仍是 `OUTPUT_SHARE × GROSS_OUTPUT × grain × 300‰ ×
priority=10`，只是受方换成可解析的 ESTATE actor。实测：修复前 799 条自环规则（0→120 推进在 `hh--24_-69-rural-landlord`
处失败），修复后 0 条。

### 1.4 unit / relation / assetShare 载荷

- **主 unit**：与 B.2 逐字段同形（`id/industry/operator/modeKey/progressDays=0/cycleLaborMilli=0/cycleInputUsedMilli={}`），不带
  `relation`（走制度默认关系）。
- **副 unit**：同形 + **显式 `relation`**：
  - farm tenant：`operator=家户`、`inputSupplier=ToHousehold(家户)`、`residualOwner=家户`、`laborSource=TENANT`、
    `rules=[OUTPUT_SHARE × GROSS_OUTPUT × grain × 300‰ × 受方=ToHousehold(landlord)（自租/无地主时为 ToActor(ESTATE)）× priority=10]`；
  - weave：`owner=operator=家户`、`kind=OWNED`、`inputSupplier=ToHousehold(家户)`、`residualOwner=家户`、`laborSource=FAMILY`、
    `rules=[]`（全自留）；
  - craft artisan：`operator=家户`、`owner=主 unit 的 WORKSHOP actor`、`kind=TENANCY`、`inputSupplier=ToActor(WORKSHOP)`、
    `residualOwner=家户`、`laborSource=TENANT`、
    `rules=[OUTPUT_SHARE × GROSS_OUTPUT × cloth × 300‰ × 受方=ToActor(WORKSHOP) × priority=10]`。
- **assetShares**：逐条显式发 `{industry, owner, operator, asset, quantity, kind}`；farm tenant/craft artisan 是
  `TENANCY`（owner≠operator），weave 与主 unit 是 `OWNED`。
- **operators**：主 unit 的经营者开缸账保持现有规则（ESTATE/WORKSHOP/HOUSEHOLD 三种，开缸商品与钱包逐值不变）；
  **没有**给家户副 unit 重复发经营者开缸账（家户账已由 `HouseholdSeeder` 建好，避免双份货币/商品）。

---

## 2. 编译 / package 原文

最终修订（含自租退化修复）的编译与打包输出：

```text
$ tools/mvn-lock.sh -q spotless:apply                       # exit 0
$ tools/mvn-lock.sh -DskipTests compile
[INFO] UnitSimos .......................................... SUCCESS [  0.188 s]
[INFO] CoreSimos .......................................... SUCCESS [  0.260 s]
[INFO] SDSimos ............................................ SUCCESS [  0.258 s]
[INFO] ActorSimos ......................................... SUCCESS [  0.071 s]
[INFO] EconomySimos ....................................... SUCCESS [  0.639 s]
[INFO] SimosApp ........................................... SUCCESS [  1.150 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  4.282 s

$ tools/mvn-lock.sh -Dmaven.test.skip=true package
[INFO] MapSimos ........................................... SUCCESS [  0.662 s]
[INFO] ActorApiSimos ...................................... SUCCESS [  0.021 s]
[INFO] EconomyApiSimos .................................... SUCCESS [  0.176 s]
[INFO] SocialSimos ........................................ SUCCESS [  0.216 s]
[INFO] UnitSimos .......................................... SUCCESS [  0.211 s]
[INFO] CoreSimos .......................................... SUCCESS [  0.243 s]
[INFO] SDSimos ............................................ SUCCESS [  0.246 s]
[INFO] ActorSimos ......................................... SUCCESS [  0.105 s]
[INFO] EconomySimos ....................................... SUCCESS [  0.551 s]
[INFO] SimosApp ........................................... SUCCESS [  2.411 s]
[INFO] BUILD SUCCESS
[INFO] Total time:  5.624 s
```

shaded jar（跑期间用 `tools/run-shaded.sh` 快照，避免就地重写）：
`simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar`，md5 `b2133a5a54b49558234bcc4ea2020437`，23,430,969 B。

---

## 3. 抽格读数证据

### 3.1 worldgen（空 store、8 线程）

`tools/run-shaded.sh ... --store /tmp/b3a-store2 --gui-port 5811 --mcp-port 5825 --economy-threads 8`，三国
`simos.worldgen.initialize`（`army=true, dryRun=false`）：

```text
德意志第二帝国 430 格  农村 5,482,400  城镇 747,600  总 6,230,000  108 城  rev2/cmd126
奥斯特马克侯国 138 格  农村 2,824,400  城镇 245,600  总 3,070,000   33 城  rev3/cmd49
霍赫兰伯国     231 格  农村 2,201,100  城镇 328,900  总 2,530,000   60 城  rev4/cmd76
合计 799 个经济格、201 座城、11,830,000 人；构造期没有抛（EconomyData 守卫全过）
```

### 3.2 单元数与资产守恒（tick0）

从 `/tmp/b3a-store2/simos.db` 重放 tick0 全量状态：

```text
industries = 1799（farm 799 / weave 799 / craft 201）
units      = 8940（farm 3995 / weave 3995 / craft 950）
unit-per-industry 直方图 = {5 units: 1744, 4 units: 55}
```

即 1744 个产业 = 主 unit + 4 个家户副 unit；55 个 craft 产业因小容量只建出 3 个匠户副 unit。**没有任何产业只有主 unit，
最小 = 4 条 unit**（主 + ≥3 副）。craft 的 55 个小格即计划里"小容量分不出一份 ⇒ 跳过该户"的落点。

抽格资产守恒（MCP `simos.economy.hex`，`/tmp/b3a-final-tick0-hexes.json`）：

| hex | industry | units | capacity | Σ unit.assets | 守恒 |
|---|---|---:|---:|---:|---:|
| `-20_-81` | craft | 4 | `{WORKSHOP:24}` | `{WORKSHOP:24}` | ✅ |
| `-20_-81` | farm | 5 | `{LAND:2064600}` | `{LAND:2064600}` | ✅ |
| `-20_-81` | weave | 5 | `{TOOL:565}` | `{TOOL:565}` | ✅ |
| `-34_-65` | craft | 5 | `{WORKSHOP:235}` | `{WORKSHOP:235}` | ✅ |
| `-34_-65` | farm | 5 | `{LAND:3100000}` | `{LAND:3100000}` | ✅ |
| `-34_-65` | weave | 5 | `{TOOL:643}` | `{TOOL:643}` | ✅ |
| `-56_-65` | farm / weave | 5 / 5 | `LAND:3100000` / `TOOL:685` | 同值 | ✅ |
| `-30_-60` | farm / weave | 5 / 5 | `LAND:3100000` / `TOOL:558` | 同值 | ✅ |
| `-40_-70` | farm / weave | 5 / 5 | `LAND:3100000` / `TOOL:596` | 同值 | ✅ |
| `-25_-75` | farm / weave | 5 / 5 | `LAND:2064600` / `TOOL:737` | 同值 | ✅ |

全量守恒（对 B.2 tick0 夹具逐 (industry, asset) 对账，见 §4.1）：1799 个键 **0 差异**、总额
`2,286,937,777` 逐值相等。抽格只是例证。

### 3.3 relation / owner / operator（tick0）

MCP 读口 `units[].relation` 不含 rules 明细（读口口径，见 `ApiViews.relationView`），故 relation 的**规则逐条**从
changeset 重放核对（修复前 store1 的读数在 `/tmp/b3a-relations-tick0.txt`；最终代码 store2 的全量扫描在
`/tmp/b3a-store2-relations-scan.txt`，下面列的是它）：

- `relations = 8940`，`unit.operator == relation.operator` **0 处不一致**；
- `laborSource`：`TENANT` 3945（farm tenant 3196 + craft artisan 749）、`FAMILY` 3995（weave 全部）、`SERF` 799（farm 主）、
  `WAGE` 201（craft 主）；
- `OUTPUT_SHARE × GROSS_OUTPUT`：grain 规则 3995 条（farm 主 799 的封建地租 + farm tenant 3196 的佃租，均 300‰）、
  cloth 规则 749 条（craft artisan，300‰）；其余默认规则：给养 3196、纤维 1000‰ 3196、布 600‰/700‰ 804/3196、
  货币工资 804；
- 修复后 **自环规则 = 0**；799 条 farm tenant（operator = 该格 rural landlord 家户）按自租退化把
  owner/受方 = `ESTATE:farm@hex`（`TENANCY` owner=ESTATE 的 AssetShare 799 条）；
- **所有 `TENANCY` AssetShare 都 owner≠operator**：`asset_share_owner_ne_operator = 3945`（= TENANCY 全部），
  `OWNED` 4995 全部 owner=operator；
- 受方解析：`ToHousehold(landlord)`（其余 farm tenant）/ `ToActor(ESTATE)`（自租退化）/ `ToActor(WORKSHOP)`（craft artisan）
  指到的家户行或经营者开缸账都真实存在；0 人口行合法（`ruralCohort` 恒建四行）。

完整扫描（`/tmp/b3a-store2-relations-scan.txt`）：`units=8940, relations=8940, assetShares=8940`，
`relation.operator != unit.operator = 0`，`self-recipient rules = 0`，逐规则计数见上。

代表性读数（`-20_-81`，修复后）：

```text
farm@-20_-81: unit-farm@-20_-81-ESTATE-farm@-20_-81   op=ESTATE:farm@-20_-81   assets LAND=1445220
             unit-farm@-20_-81-HOUSEHOLD-hh-...-rural-poor_peasant   op=HOUSEHOLD:hh-...   TENANT
               relation: input=ToHousehold(该户) residual=该户 source=TENANT
                         rule=OUTPUT_SHARE GROSS_OUTPUT grain 300‰ → ToHousehold(landlord) p=10
             ... 四个 rural 家户各一条；landlord 自己那条 owner/受方 = ESTATE（自租退化）
             Σ assets = 1445220+30969+216783+278721+92907 = 2064600 = cap ✅
weave@-20_-81: 主 HOUSEHOLD:weave@-20_-81=396 + 四户 9/59/76/25 = 565 ✅（rules=[]，FAMILY）
craft@-20_-81: 主 WORKSHOP=17 + 三户 3/3/1 = 24 ✅（第四户 share=0 < 1 ⇒ 未建 unit）
```

### 3.4 0→120 的 progress / 产出（读口可见）

★★ **取样边界（如实记）**：推进到 tick120 时**恰好是周期末**——结算在关账日把 `progressDays`/`cycleLaborMilli`/
`cycleInputUsedMilli` 清零、`operatorConditions` 记下上一周期读数。因此我又推进了 **1 tick（120→121，9.478 s）**，
在同一批 8 个格上读 `simos.economy.hex` + `simos.social.population` + `simos.economy.ownership`
（`/tmp/b3a-final-tick121-hexes.json`、`/tmp/b3a-final-tick121-ownership.json`）——这是让新周期第一天的
progress/投入在**读口**里可见的最小代价；0→120 的主推进仍是一次 `AdvanceTime`（§4.2）。

读数（全部抽格）：

| hex | industry | units | Σassets==capacity | main/副 progressDays | 副 unit 有 `cycleInputUsedMilli` | main/副产出账（读口） |
|---|---|---:|---|---|---|---|
| `-20_-81` | farm | 5 | ✅ | 1 / 1×4 | 4/4 | main rev=180；副 rev=8594/614/…；副 unsold 19.7M/31.1M/… |
| `-20_-81` | weave | 5 | ✅ | 1 / 1×4 | 4/4 | main rev=57；副 rev=3/52/…；副 unsold 122k/2.86M/… |
| `-20_-81` | craft | 4 | ✅ | 1 / 1×3 | 3/3（tool） | main unsold=602k、selfUsable=1.05M；副 tool 投入 6000/6000/2000 |
| `-34_-65` | farm / weave | 5 / 5 | ✅ / ✅ | 1 / 1×4 | 4/4 / 4/4 | farm 副 rev=11323/1220/…；weave main rev=418、副 rev=11/162/… |
| `-56_-65` | farm / weave | 5 / 5 | ✅ / ✅ | 1 / 1×4 | 4/4 / 4/4 | farm 副 rev=52450/40782/…；weave main rev=416、副 rev=24/510/… |
| `-30_-60` | farm / weave | 5 / 5 | ✅ / ✅ | 1 / 1×4 | 4/4 / 4/4 | farm 副 rev=10482/1286/…；weave main rev=207、副 rev=12/257/… |
| `-40_-70` | farm / weave | 5 / 5 | ✅ / ✅ | 1 / 1×4 | 4/4 / 4/4 | farm 副 rev=66567/37050/…；weave main rev=11519、副 rev=713/14206/… |
| `-25_-75` | farm / weave | 5 / 5 | ✅ / ✅ | 1 / 1×4 | 4/4 / 4/4 | farm 副 rev=12143/3075/…；weave main rev=192、副 rev=12/241/… |

farm 的 `cycleInputUsedMilli` 是**种子**（如 `-20_-81` 主 unit 11,560,000 毫粮；佃户 240,000 / 1,728,000 / …），
weave 是**纤维**（主 8,256,160 毫纤维；户 189,072 / 1,239,475 / …），craft 是**工具/纤维**（主 34,000 工具 + 1,020,000 纤维；
副户 6,000/6,000/2,000 工具）——**主 unit 与副 unit 都真的有投入、有 progress**。

产出归属变化（同批抽格的 actor 账，`/tmp/b3a-final-tick121-ownership.json`）：

- `-20_-81`：tick0 货账 `{fiber 13.824M, grain 67.827M, iron 0.24M, tool 0.048M}` → tick121
  `{cloth 12.177M, fiber 8.230M, grain 127.433M, iron 0.24M}`；tick121 的账落在 **ESTATE 持 fiber 8.230M**、
  **四个 rural 家户持 cloth（0.175M/3.226M/4.312M/…）+ grain（12.456M/41.863M/56.351M/…）**。
- `-34_-65`：tick0 `{fiber 32.7M, grain 133.641M, iron 2.35M, tool 0.47M}` → tick121
  `{cloth 18.986M, fiber 12.304M, grain 233.037M, iron 2.35M, tool 0.172M}`；**WORKSHOP 持 tool 172,000**、
  urban 家户持 iron、rural 家户持 cloth+grain、`HOUSEHOLD:weave@-34_-65` 持 cloth 8.041M ——
  主 unit 与副 unit 的产出都通过各自关系/产量账落到不同主体上。
- `operatorConditions`：tick120 从 0 条变为 **8940 条**（每个 unit 一条），每条都带 `lastCycleRevenueMilli` /
  `unsoldStockMilli` / `consecutiveInputShortfallCycles` 等**逐 unit 的产出账**；即"同一 hex 至少两条 unit 各有
  progress/产出账"在真实推进后成立，而不是只有播种时形状对。
- **`simos.social.population` 逐格对账**：抽的 6 个有人口的格里，social 人口 = economy 人口（tick121：
  12,330 / 24,216 / 13,477 / 10,953 / 11,738 / 14,516），没有"经济侧人口 ≠ 社会侧人口"的漂移（全量人口见 §4.2 的
  changeset 重放）。

---

## 4. 宏观守恒读数与推进墙钟

### 4.1 tick0：与 B.2 基线（同一 worldgen 三国）逐项对账

方法：Python 重放 `/tmp/b3a-store2/simos.db` 与 `/tmp/b2-tick0-fixture/simos.db` 的 changeset 流（economy + actor
组件；脚本 `/tmp/b3a_state.py`；对照 `/tmp/b3a-compare-b2.txt`）。两边的世界生成参数完全相同（三国、`army=true`）。

| 指标 | B.2 基线 tick0 | B.3a tick0 | 结论 |
|---|---:|---:|---|
| 人口（Σ ClassRow.population） | 11,830,000 | 11,830,000 | 逐值相等 |
| alloc 行 / Σ laborMilli | 22,392 / 5,586,267,122 | 44,564 / 5,586,267,122 | 行数 ×2（拆半），**总量逐值相等** |
| 逐 (group, household) 配额键 | 16,000 | 16,000 | **0 差异** |
| AssetShare 行数 | 1,799 | 8,940 | 拆成主+副，行的**逐 (industry, asset) 总量 0 差异** |
| AssetShare 总量 | 2,286,937,777 | 2,286,937,777 | 逐值相等 |
| 货币（actor 账 Σ） | silver 142,924,800 | silver 142,924,800 | 逐值相等（没有重复发家户开缸账） |
| 商品（actor 账 Σ） | grain 64,078,302,152 / fiber 15,298,116,000 / iron 263,410,000 / tool 52,682,000 | 完全相同 | 逐值相等 |
| 债务笔数 / 出生+死亡 | 0 / 0+0 | 0 / 0+0 | tick0 无债务、未结算 |
| `max(Σalloc − available)` | −1260 | −1260 | 无 >0 违反 |
| assetShare / account 负值 | 0 | 0 | 无负余额 |

⇒ B.3a 只改**结构**（unit/份额/关系挂在哪个主体上），旧口径的产能、人口、商品、货币、配额**一个数都没变**。

### 4.2 0→120 推进：墙钟、异常、tick120 宏观

**推进**：`simos.advance {branch:main, expectedRevision:4, from:0, to:120}`（8 线程，一次）。

```text
wall = 752.723 s（12 分 32.7 秒），error = None
result = {"result":"committed","ref":{"branch":"main","revision":5}}
timeline head 4 (tick0) → head 5 (tick 120)，之后 120→121 的 1 tick 另计 9.478 s
```

★ **这超过任务书"≤3 分钟"的预算**；原因是多 unit 世界把结算里本就存在的两个 O(n²) 面放大约 10×
（`householdKeysOf` 每 unit 扫全量配额：8940 × 44,564；`ProductionUnitBook.usableAssets` 每 unit 扫全量份额：
8940 × 8940），而这段是**串行**的，8 线程帮不上。本片不改结算（硬约束），把它作为性能切片输入；推进中抓到的
jstack 栈摘录在 `/tmp/b3a-jstack.txt`（`MarketSettlement.clearOncePerCycle → inputShortfallNear → usableAssets`）。

**tick120 宏观**（重放 economy + actor changeset 流；`/tmp/b3a-state2-tick120.json`，对照 tick0：
`/tmp/b3a-state2-tick0.json`）：

| 指标 | tick0 | tick120 | 结论 |
|---|---:|---:|---|
| 人口（Σ ClassRow.population） | 11,830,000 | 11,622,725 | `11,830,000 + 出生 99,472 − 死亡 306,747 = 11,622,725` **逐值闭合** |
| alloc 行 / Σ laborMilli | 44,564 / 5,586,267,122 | 33,793 / 5,559,098,523 | 行数与总量由**运行期劳动再分配**（H5）改写；**无 Σalloc > available**（最大差仍 −1260） |
| AssetShare 总量 / 键 | 2,286,937,777 / 1799 | 2,286,937,777 / 1799 | **逐值守恒**（无资产转移路径被走到） |
| AssetShare 行 / OWNED / TENANCY | 8940 / 4995 / 3945 | 8940 / 4995 / 3945 | 形状与数量都不变 |
| 货币（actor 账 Σ silver） | 142,924,800 | 142,924,800 | **逐值守恒**（无铸造/销毁路径） |
| 债务笔数 / principal | 0 / 0 | 0 / 0 | 本轮不产生债务 |
| 商品（actor 账 Σ） | grain 64,078,302,152 / fiber 15,298,116,000 / iron 263,410,000 / tool 52,682,000 | grain 164,011,560,198 / fiber 23,134,775,234 / cloth 12,745,741,800 / iron 263,410,000 / tool 52,682,000 | 净 Δ：+99,933,258,046 / +7,836,659,234 / +12,745,741,800 / 0 / 0 —— 产出/消费真实发生，**不是守恒量**，但逐商品**无负余额** |
| `Σalloc − available` 最大 | −1260 | −1260 | 无 >0 违反 |
| 负余额（goods/money/population/shares） | 0 | 0 | 无负余额 |
| `operatorConditions` | 0 | 8940 | 每个 unit 都有关账账本 |
| units / relations / industries | 8940 / 8940 / 1799 | 8940 / 8940 / 1799 | 结构稳定 |

⇒ 两个**真守恒量**（AssetShare、货币）逐值不动；人口变化被出生/死亡逐值解释；无负余额、无配额超发、无异常。
商品的净增量是"生产 − 投入 − 消费"的结果（本片没有独立总账对账器，见 §6）。

---

## 5. 与计划不同的地方、剩余阻断点

1. **自租退化**：计划只写了"owner = landlord 家户（若不存在/人口 0 退回 ESTATE）"。实测发现 **operator 恰是 landlord 家户**
   的 799 条 farm tenant 会让租规则变成自环转移（`Transfer` 两端相等守卫当场拒），故把"自租"并入同一退回档
   （owner/受方 = ESTATE）。规则类型/费率/priority 不变，只换受方。
2. **0 人口行**：受方用 `ToHousehold(landlord)` 时**不要求人口 > 0**（行存在即可，`ruralCohort` 恒建四行）；只有
   `landlordPopulation == 0` 或自租才退 ESTATE。这与"0 人口行合法"一致。
3. **landlord 家户也会拿到 tenant unit**（它是 farm 配额里出现过的农村家户之一），于是出现"自租" unit；本片按字面
   "每个出现过的家户一个 tenant unit" 保留它（owner/受方退 ESTATE）。若要把它排除，是一处候选集过滤的后续裁定。
4. **小容量跳过**：55 个 craft 产业只建出 3 个匠户副 unit（第二户 share=0 < capacityPerUnit=1）；其余户的份额与劳动
   留在主 unit。没有小容量 farm/weave 被跳过（LAND/TOOL 量级足够）。
5. **性能（硬约束"≤3 分钟"未达成，见 §4.2 墙钟）**：0→120 一次推进实测 **752.723 s（12 分 32.7 秒）**，
   多 unit 把结算里本就存在的 O(units × allocations) 面放大约 10×
   （`householdKeysOf` 每 unit 扫全量配额、`ProductionUnitBook.usableAssets` 每 unit 扫全量份额）；8 线程救不了这段
   串行面。本片不改结算（硬约束），如实记为推进墙钟超标与后续性能切片输入。
6. **关系读口**：`simos.economy.hex` 的 `units[].relation` 不吐 rules 明细（`ApiViews.relationView` 的既有口径），
   故 rules 证据来自 changeset 重放，不是 MCP 读口；B.3b/E 若要读口可见，需要加 rule 明细栏（不在本片）。
7. **运行期配额再分配**：tick0 的"Σ 配额逐值等于旧口径"由本片构造保证；tick120 的 `alloc_total` 比 tick0 少
   27,168,599 是结算的 `reallocateLaborPartitioned`（H5 既有行为）按当时人口/劳动重发的结果，不是播种器丢值；
   守卫读数仍是 `max(Σalloc − available) = −1260`（无超发）。

---

## 6. 我没做 / 没验证的

- 未写/改任何测试；未跑 `test` / `verify`（按任务纪律）；未做变异自证。
- 未 commit/push。
- 未做 B.3b（资产转移/停业命令）、c1 孤儿债对账、B.4/E 切片；未改结算算法、市场、StressPolicy、价格、人口常量。
- **旧档重放做了两条**：① Python 重放 `/tmp/b2-tick0-fixture` 全 changeset 流成功（§4.1 的对照就是它）；
  ② 新 jar 下跑既有 Java 探针 `Probe3`（`Timeline.readChangeSet` → `EconomyData.empty()` 逐 rev2/3/4）
  **PASS=60 FAIL=0**（含"Σ AssetShare == 旧 capacity"、"relations/conditions 键全是真实 unit"、严格往返、幂等）。
  **未验证**更早的历史档（如 M2 `store-m2` 的 `useRights` 兼容——B.2 已如实记为在 HEAD 上同样不可读）。
- 未做 1/4/8 线程逐值一致、未做一年期（≥360 tick）与峰值内存测试；0→120 只跑了一组 8 线程。
- 宏观"商品守恒"只按 actor 账总量与无负余额检查；**没有**独立的"产出 − 投入 − 消费 ± 转移"逐笔对账器，故商品的
  tick0→tick120 变化只报告净额，不声称逐笔闭合。债务本轮为 0，没有做"旧债/新债"路径。
- 未逐笔核对 craft artisan 的 300‰ 布租是否落到 WORKSHOP 的 actor 账（读口能看到主/副 unit 的
  `cycleInputUsedMilli`/`lastCycleRevenueMilli`/`unsoldStockMilli` 与 hex 总账变化，但没有对租规则做 ledger 级对账）。
- tick120 恰好是周期末（progress/投入清零），所以 progress 证据取自**额外推进的 1 tick（121）**；未做 0→120 途中的
  多 tick 采样（若要"推进中读数曲线"，需要在 60/90/119 等 tick 另取）。
- 未跑 B.2 的 0→30 A/B（任务书明说"不强制"）；本片用 tick0 全量对账 + 0→120 一次推进替代。
- 抽格只覆盖 8 个格（含任务点名的 `-20_-81`、`-34_-65`）；全量守恒来自 changeset 重放，不是逐格 MCP 读数。
