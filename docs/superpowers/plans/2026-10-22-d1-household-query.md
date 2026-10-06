# D1 施工契约：`HouseholdQueryService` + `simos.social.households`

> 批次：D1（规划入口 `docs/superpowers/plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md`）
> 基线：D0 已推送（`573cc2e9`）。
> 用户裁定：GM 与决策人共用工具；GM `scope=ALL`；中央只看直辖区，跨区走上报；
> 人口权威在 Social，经济行只是投影；缺数据具名 unavailable，不填 0。

## 1. 文件范围

**新增**
- `simos-app/src/main/java/io/mosire/simos/app/query/HouseholdQueryService.java`
- `simos-app/src/main/java/io/mosire/simos/app/tools/read/SocialHouseholdsTool.java`
- `docs/superpowers/plans/2026-10-22-d1-household-query.md`（本文件）

**修改**
- `simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java`：`readTools(...)` 加
  `new SocialHouseholdsTool(query, calendarService, mapId)`；不标 `GmOnlyRead`（两桶共享）。
- `simos-app/src/main/java/io/mosire/simos/app/access/DecisionCallerFactory.java`：`WHITELIST` 加
  `SocialHouseholdsTool.NAME`。
- 必要的 javadoc / 工具描述。

**禁止**
- 改 Social/Economy/Unit/Actor 持久组件与 Codec/ChangeSet；
- 改既有 handler 写语义；
- 改测试（D5 统一迁移）；
- commit/push（控制方做）。

## 2. 服务 API

```java
public final class HouseholdQueryService {
  public HouseholdQueryService(CalendarService calendarService);

  public Map<String, Object> query(
      SimulationState state, String mapId, Spec spec, Visibility visibility);

  public interface Visibility {
    boolean householdAllowed(
        HouseholdId id, HouseholdLocation location,
        Optional<HexCoord> effectiveHex, Set<UnitId> containingUnits);
    boolean hexAllowed(HexCoord hex);

    static Visibility all();
    static Visibility none();
  }

  public record Spec(
      Scope scope, Filters filters, List<Dimension> groupBy,
      Set<Metric> metrics, Window window, RateMode rateMode, Include include) {}

  public enum ScopeKind { HEX, UNIT, HOUSEHOLD, VISIBLE, ALL }
  public record Scope(ScopeKind kind, Integer q, Integer r, String unitId, String householdId) {}

  public record Filters(
      Set<AgeBracket> ageBrackets, Set<Sex> sexes, Set<String> strata,
      Set<String> productionModes, Set<ResidenceKind> residences,
      Set<UnitId> unitIds, Set<HouseholdId> householdIds, Set<HexCoord> hexes,
      Boolean hasEconomyRow, boolean aliveOnly) {}

  public enum Dimension {
    AGE_BRACKET, SEX, STRATUM, PRODUCTION_MODE, RESIDENCE, UNIT, HEX, HOUSEHOLD
  }
  public enum Metric {
    POPULATION, HOUSEHOLD_COUNT, LOT_COUNT, BIRTHS, DEATHS,
    BIRTH_RATE_PER_MILLE, DEATH_RATE_PER_MILLE, LABOR_MILLI, NATURAL_NEEDS,
    PARTICIPATION_PER_MILLE, GOODS, MONEY, DEBT_PRINCIPAL, CREDIT_PRINCIPAL
  }
  public record Window(long fromTick, long toTick) {}
  public enum RateMode { CONFIGURED, OBSERVED, BOTH }
  public record Include(boolean members, boolean economy, boolean units) {}
}
```

- `query` 返回 **JSON 友好 Map**（键/值都可被 `ToolSupport.ok` 直接序列化）。
- `Spec` 与 `Visibility` 都可以被未来 GUI/工具复用；`Visibility.all()` 仅 GM/测试用。

## 3. 输入形状（工具层解析成 Spec）

```json
{
  "scope": {"kind":"VISIBLE","q":0,"r":0,"unitId":"u","householdId":"hh"},
  "filters": {
    "ageBracket": ["0-14"], "sex": ["FEMALE"], "stratum": ["poor_peasant"],
    "productionMode": ["self_farm"], "residence": ["RURAL"],
    "unitId": ["u"], "householdId": ["hh"], "hex": [{"q":0,"r":0}],
    "hasEconomyRow": true, "aliveOnly": true
  },
  "groupBy": ["AGE_BRACKET","SEX"],
  "metrics": ["population","births","deaths"],
  "window": {"fromTick": 0, "toTick": 120},
  "rateMode": "BOTH",
  "include": {"members": false, "economy": false, "units": false}
}
```

- `scope` 缺省 `VISIBLE`；`groupBy` 缺省空（总量单行）；`metrics` 缺省全部；
  `rateMode` 缺省 `BOTH`；`include` 缺省全 false。
- `filters.aliveOnly` 缺省 true（只含 population > 0 的家户）；显式 false 才含 0 人口家户。
- `filters` 中 `ageBracket`/`sex`/`residence` 是成员级筛选：家户只要有一个成员命中就纳入；
  人口指标只累加命中的成员。
- `window` 缺省 = 当前历法年第一天（clamp 到 tick 0）到 `now + 1`（半开 `[from, to)`，
  `to` 含当前 tick）。
- `metrics` 中出现未知值、`groupBy` 重复、`scope` 不合法 ⇒ 工具 `BAD_REQUEST`。

## 4. 可见性

- 工具 `resources()` 声明：`map`/`social`/`unit`/`economy`/`actor` 全部 `READ_ONLY`
  （`actor` 对决策人由 scope 函数置 `none()`，对 GM 是 `unlimited()`）。
- `Visibility.hexAllowed(HexCoord)` = `ToolSupport.populationVisible(context, hex)`。
- `Visibility.householdAllowed(...)`：
  - `location=HEX(h)` 且 `hexAllowed(h)` ⇒ true；
  - `location=UNIT(u)` 且 `ToolSupport.unitVisible(context, u)` ⇒ true；
  - 任一 `containingUnits` 可见 ⇒ true；
  - `effectiveHex` 可见 ⇒ true（同一物理落点，hex 面已授权）。
- 工具前置检查：
  - `scope.kind=ALL`：仅当 `context.permissions().resourceScopes().byNamespace().get("social")`
    存在且 `unrestricted()` ⇒ 允许；否则 `FORBIDDEN`。**不靠工具名判断 GM**。
  - `scope.kind=HEX`：**按 `HouseholdLocation.Hex` 收口**（与 `simos.social.population` 对账）；物理落在该 hex 的
  `UNIT` 家户不因有效格相同而进入 `scope=HEX`（它们走 `scope=UNIT/VISIBLE`，HEX 维度按有效格输出/隐藏）；
  格不存在或 `!populationVisible` ⇒ `NOT_FOUND`（拒因与不存在一致）。
  - `scope.kind=UNIT`：unit 不存在或 `!unitVisible` ⇒ `NOT_FOUND`。
  - `scope.kind=HOUSEHOLD`：家户不存在或不可见 ⇒ `NOT_FOUND`。
- `scope.kind=VISIBLE`：服务用 `visibility.householdAllowed` 逐户过滤。
- **hex-hidden-by-scope**：家户只通过 unit 面可见、但其 `effectiveHex` 不满足 `hexAllowed` 时，
  可进 unit/其他维度与 totals，但 `HEX` 维度值输出 `null`，行 `unavailable` 记
  `hex-hidden-by-scope`；`scope` 块同时记 `hiddenHexHouseholds` 计数。

## 5. 指标口径（必须逐条实现）

| 指标 | 口径 |
|---|---|
| `population` | Σ 命中成员份额（Social `Household.members`）；不用 Economy `population` 替代 |
| `householdCount` | 去重 HouseholdId（命中成员所在桶；无成员级维度时用纳选家户） |
| `lotCount` | 去重 PeopleLotId（桶内去重；跨户共享 lot 只在同桶算一次，说明写进输出注释字段） |
| `births`/`deaths` | window 内 `PopulationEventType.BIRTH/DEATH` 的 `count` 求和（人数，不是事件条数）；事件同样受成员级 filters 约束（年龄档/性别/居住地对不上即不落桶） |
| `birthRatePerMille` | `CONFIGURED` 优先：Σ(份额 × 有效 ppm 率) ÷ population ÷ 1000（per-mille/tick，加权不是平均）。出生率只对 **FEMALE 且 15≤ageYears<45** 的成员取 `findVitalRate(hh, ADULT, FEMALE)`，其余计 0；死亡率对所有成员取 `findVitalRate(hh, bracket, sex)`。`OBSERVED`：`births × 1000 ÷ (population × windowTicks)`；`BOTH` 时另写 `birthRatePerMilleObserved`。population=0 或 windowTicks=0 ⇒ 该指标 unavailable |
| `laborMilli` | `SocialData.householdLaborMilli(hh, nowTick, clock)` 求和 |
| `naturalNeeds` | `SocialData.householdNaturalNeeds(hh, nowTick, clock)` 逐商品求和 |
| `participationPerMille` | Economy 行 `participationPerMille` 按 `HouseholdEconomy.population` 加权平均；任一纳入家户无经济行 ⇒ 该桶该指标 unavailable |
| `goods`/`money` | actor `HouseholdInventory` 逐商品/币种求和（`ActorData.accounts().get(new HouseholdAccountKey(hh))`）；actor 面不可见或任一纳入家户无账户 ⇒ unavailable；有账户且 0 余额照发 0 |
| `debtPrincipal` | `DebtContract.debtor == hh` 的本金按 `unit.key()` 求和；家户级指标，桶内多户相加 |
| `creditPrincipal` | 同上，方向为 `creditor == hh` |
| `stratum` 维度 | `HouseholdEconomy.view.stratum().value()`；无经济行 ⇒ `unclassified` |
| `productionMode` 维度 | `EconomyData.classStandings[hh].currentPositionId → classPositions → modeId().value()`；缺 ⇒ `none` |
| `unit` 维度 | `Unit.households` 反查（UnitState 保证一个家户至多属一个 unit）；无 ⇒ null |
| `hex` 维度 | `HouseholdPositionResolver.effectiveHex`；unit 不可解 ⇒ null + `unit-unresolved` |
| `residence` 维度 | 成员级：`PopulationLots.isUrban(group) ? URBAN : RURAL` |

### 5.1 家户级指标 × 成员级 groupBy

`AGE_BRACKET/SEX/RESIDENCE` 是成员级维度。若一个家户在这些维度上跨多个值：

- `population`/`births`/`deaths`/`lotCount` 照常按成员/事件分桶；
- `householdCount` 按"该桶内有成员的 distinct 家户"计（跨桶可重复，这是桶计数）；
- `laborMilli`/`naturalNeeds`/`participationPerMille`/`goods`/`money`/`debtPrincipal`/`creditPrincipal`
  是家户级指标，不能按成员拆：若家户在成员级维度上不唯一，则对应的每个桶该指标记
  `household-level-metric-split` unavailable，**不重复相加**；若家户在成员级维度上唯一，则整户指标落到该桶。
- **无成员级维度**时：家户级指标正常按家户落桶（每户只落一次）。
- **成员级 filters 的部分命中**：若成员级 filters（ageBracket/sex/residence）把某家户的**一部分成员**挡在门外，
  则该家户的**家户级指标**（laborMilli/naturalNeeds/participationPerMille/goods/money/debtPrincipal/
  creditPrincipal）一律记 `member-filter-partial` unavailable——不能拿全家户的整本账冒充"命中成员那一部分"。
  population/births/deaths/lotCount 仍只按命中成员/事件累加。

## 6. 输出形状

```json
{
  "scope": {"kind":"VISIBLE","source":"decision-scope","households":12,"hiddenHexHouseholds":3},
  "at": {"tick":360},
  "window": {"fromTick":0,"toTick":361},
  "rateMode": "BOTH",
  "rows": [
    {"key":{"AGE_BRACKET":"15-59"},
     "population":480,"householdCount":12,"lotCount":31,
     "births":9,"deaths":3,
     "birthRatePerMille":0.156,"birthRatePerMilleObserved":0.052,
     "unavailable":["goods:actor-denied"]}
  ],
  "totals": { "...同 rows[].指标，key 为空对象..." },
  "unavailable": [
    {"reason":"actor-denied","metrics":["goods","money"],"households":12}
  ],
  "notes": ["lotCount 按桶内 PeopleLotId 去重；跨户共享 lot 可能出现在多个桶"]
}
```

- `rows` 按 `groupBy` 维度顺序 + 稳定值字典序排序；`key` 保留请求维度顺序。
- 指标值缺数据时**不写键**（或写 `null` 不冒充 0），同时在行 `unavailable` 和顶层
  `unavailable` 具名；能算的 0 正常写 0。
- `totals` 是同一套指标对全部纳选家户的汇总。
- `include.members/economy/units` 在 D1 **只接受 false**；给出 true ⇒ `BAD_REQUEST`
  并提示留 D1.1（分页与明细列）。

## 7. 门禁 smoke（控制方执行）

- `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` rc=0。
- 用真实 small-world genesis 起 shell：
  1. GM `scope=ALL` 按 `STRATUM`、`PRODUCTION_MODE`、`AGE_BRACKET` 聚合；
     逐户手算 `population`/`householdCount` 对账；单 hex 与 `simos.social.population` 对账；
  2. GM `scope=HEX` 单格人口 == 该格所有家户成员份额和；
  3. 决策人（GOV，直辖区）`scope=VISIBLE` 只含辖区内家户；辖区外 hex 的
     `social`/`unit` 家户不出现；
  4. 配置加权率与窗口事件数对账；`rateMode=BOTH` 两个键都在；
  5. `scope=ALL` 在决策人上下文 ⇒ `FORBIDDEN`。
- 结果日志落 `/tmp/d1-smoke.log`；不跑 test/test-compile/verify。

## 8. 实际 smoke 证据（2026-10-22）

- `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests clean compile` → rc=0。
- `/tmp/D1Smoke2.java`（临时 harness，不进仓库）跑真实 small-world `Shell`：
  - GM `scope=ALL`：STRATUM 逐桶 population 合计 == 逐户手算 4000；AGE_BRACKET 配置率/观测率两键齐全；
  - `scope=HEX` 单格人口 == `simos.social.population`（200 == 200）；
  - `scope=HOUSEHOLD` == `SocialData.householdPopulation`；
  - 推进 400 tick 后 window `[0,361)`：births=257、deaths=157，`birthRatePerMilleObserved`
    与公式 `births×1000÷(population×windowTicks)` 一致；
  - 真实 GOV 决策人（直辖区 = 新建 `d1-region` 单 hex）：`scope=ALL` ⇒ `FORBIDDEN`；
    `scope=VISIBLE` 家户集合/人口与 `GovScope` 手算完全一致（5 户 / 205 人），辖区外不出现；
  - GM `scope=ALL` 按 PRODUCTION_MODE 汇总 == 全量人口；`scope=UNIT` 只返回目标单位户；
  - 服务层 hex-hidden：仅 unit 面可见但有效格不在 hex 面时，`HEX` 维度为 `null`、
    行记 `hex-hidden-by-scope`、totals 仍含该户。
  - 结论：`[smoke] PASS2`，日志 `/tmp/d1-smoke2d.log`。
