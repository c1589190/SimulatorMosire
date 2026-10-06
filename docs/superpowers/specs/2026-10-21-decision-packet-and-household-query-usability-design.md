# 2026-10-21 决策包完整版 + 家户数据查询工具：易用性设计

> 用户 2026-10-21 要求：
> 1. 决策人预执行体系必须做成**可用的完整版**（决策人只填参数，GM 查看/审批，冲突可合并）；
> 2. 决策人必须有**自己看家户信息**的工具；理想是**一个聚合工具**：单 hex、所有可见 hex 的汇总，
>    含年龄段、阶层、出生/死亡率等；不要逼着逐 hex 读；
> 3. GM 工具补齐；工具设计以**易用性**为先：GM 要看“某年龄段 / 某阶层 / 某生产方式”的全量数据，
>    不应逐 hex 单读。
> 本文只做设计，不写实现代码。

## 1. 设计原则

1. **一次调用拿全**：筛选、分组、汇总在一次工具调用里完成；禁止“先在 A 工具拿 hex 列表，再逐 hex 调 B 工具”。
2. **同一维度贯穿**：年龄档、性别、阶层、生产方式、unit、hex、residence 都是可筛选/可分组的**一等维度**，
   不是各工具各拼一套。
3. **人群口径只有一处**：聚合人口从 Social 家户成员份额求和；不做跨户平均、不做 `population × 系数` 反推。
4. **决策人自动裁剪**：同一个工具给决策人时按 scope 自动只含可见家户；GM 拿到 `scope=ALL`。
5. **跨权限情报显式**：中央决策人**只能看自己直辖区**内的 hex/单位家户；下辖 GOV、辖区外单位
   都不自动可见，跨区信息必须走**上报**（`simos.sd.report`/`simos.sd.reports`，D4）。输出里用
   `hex=null` + 具名 reason 说明被 scope 裁剪，不靠删行掩盖。
6. **拒绝具名**：任何“查不了”都写清维度/范围/原因，不填 0 冒充。
7. **读工具不分家**：GM 与决策人用**同一个读工具名**，权限决定 scope；避免“GM 一套、决策人另一套”漂移。

## 2. 家户数据查询工具：`simos.social.households`

### 2.1 为什么一个工具就够

现有 `simos.social.population` 只能按 hex 单查；`unit.get` 只给单个 unit。要回答：

- “全世界/全部可见家户，按年龄段分组的出生率、死亡率”；
- “某阶层/某生产方式的全量汇总”；
- “单个 hex 的年龄段 × 阶层交叉”；
- “所有可见 hex 的总人口/出生/死亡”；
- “自己直辖区内单位家户汇总”；辖区外情报走上报，不直接读；
- “某个 unit 的家户明细”；

需要的不是更多单点工具，而是**一个支持 `scope + filter + groupBy + metrics` 的查询面**。

### 2.2 入参形状

```json
{
  "scope": {
    "kind": "HEX | UNIT | HOUSEHOLD | VISIBLE | ALL",
    "q": 0, "r": 0,            // kind=HEX
    "unitId": "u-1",            // kind=UNIT
    "householdId": "hh-..."     // kind=HOUSEHOLD
  },
  "filters": {
    "ageBracket": ["0-14","15-59","60+"],
    "sex": ["MALE","FEMALE"],
    "stratum": ["poor_peasant","landlord","official","..."],
    "productionMode": ["family_farm","tenancy_fixed_kind","..."],
    "residence": ["RURAL","URBAN"],
    "unitId": ["u-1","u-2"],
    "householdId": ["hh-..."],
    "hex": [{"q":0,"r":0}],
    "hasEconomyRow": true,
    "aliveOnly": true
  },
  "groupBy": ["AGE_BRACKET","SEX","STRATUM","PRODUCTION_MODE","RESIDENCE","UNIT","HEX","HOUSEHOLD"],
  "metrics": [
    "householdCount","population","lotCount",
    "births","deaths","birthRatePerMille","deathRatePerMille",
    "laborMilli","naturalNeeds","participationPerMille",
    "goods","money","debtPrincipal"
  ],
  "window": { "fromTick": 0, "toTick": 120 },   // 观测出生/死亡用；缺省 = 当前周期至今
  "rateMode": "CONFIGURED | OBSERVED | BOTH",  // 配置加权率 vs 窗口观测率
  "include": { "members": false, "economy": false, "units": false },
  "limit": 500,
  "cursor": "..."
}
```

- `scope.kind=VISIBLE`：决策人默认；按 scope 函数（hex 面 ∪ unit 面）过滤。
- `scope.kind=ALL`：只有 GM 桶允许；决策人调用 ⇒ `FORBIDDEN`。
- `groupBy` 可为空数组（总量单行）或多维组合（如 `["AGE_BRACKET","STRATUM"]`）。
- `filters` 与 `groupBy` 是正交的：可以“先筛某生产方式，再按年龄段分组”。

### 2.3 输出形状

```json
{
  "scope": {"kind":"VISIBLE","visibleHexes": 15, "visibleUnits": 3, "source":"decision-scope"},
  "at": {"tick": 360, "date": {...}},
  "window": {"fromTick": 240, "toTick": 360},
  "rows": [
    {
      "key": {"AGE_BRACKET":"15-59","STRATUM":"poor_peasant"},
      "householdCount": 12,
      "population": 480,
      "lotCount": 31,
      "births": 9,
      "deaths": 3,
      "birthRatePerMille": 6.7,      // 配置加权（‰/tick 或按窗口口径，见 2.4）
      "deathRatePerMille": 3.3,
      "laborMilli": 123456,
      "naturalNeeds": {"grain": 123456, "cloth": 789},
      "goods": {"grain": 987654},
      "money": {"silver": 4321},
      "debtPrincipal": {"money:silver": 0},
      "unavailable": []
    }
  ],
  "totals": { ... 同 metrics ... },
  "unavailable": [
    {"reason":"hex-hidden-by-scope","households": 3, "note":"这些家户属于可见 unit 但 hex 不在可见范围"}
  ]
}
```

- 行按 `groupBy` 的维度序 + 稳定 id 排序，可复现。
- `unavailable` 是**整桶**的具名读数，不往 rows 里灌 0。

### 2.4 指标口径

| 指标 | 口径 |
|---|---|
| `population` | Σ 家户成员份额（Social `Household.members`）；**不是** Economy 行 population，后者仅作对账 |
| `householdCount` | 去重 `HouseholdId` |
| `lotCount` | 去重 `PeopleLotId`（跨户共享 lot 只算一次，需显式说明） |
| `births` / `deaths` | window 内 `PopulationEventType.BIRTH/DEATH` 事件计数汇总 |
| `birthRatePerMille` / `deathRatePerMille` | `CONFIGURED` = Σ(有效 ppm 率 × 人数) ÷ 人口（加权，非平均）；`OBSERVED` = 事件数 ÷ 人·tick；`BOTH` 两个都给 |
| `laborMilli` | `SocialData.householdLaborMilli` 求和 |
| `naturalNeeds` | `SocialData.householdNaturalNeeds` 求和（逐商品） |
| `participationPerMille` | Economy 行 `participationPerMille`；无行 ⇒ 该指标在行内 unavailable，不填 0 |
| `goods` / `money` | actor 账户或 Economy 行；口径在实现时唯一选定并写死（推荐 actor 账户，与库存真值同源） |
| `debtPrincipal` | Economy `DebtContract` 按 debtor 或 creditor 方向聚合；缺数据具名 |
| `stratum` | Economy 行 `view.stratum()`；无经济行 ⇒ `unclassified` |
| `productionMode` | `EconomyData.classStandings[hh].currentPositionId` → `classPositions` → `modeId`；缺 ⇒ `none` |
| `unit` | `Unit.households` 反查；一个家户同时属于多个 unit（坏数据）⇒ 具名 |
| `hex` | `HouseholdPositionResolver` 有效格；UNIT 家户按 unit effectivePosition；unit 不可解 ⇒ 空 + reason |

### 2.5 可见性与跨权限情报

- **决策人**：`scope=VISIBLE` 默认。
  - hex 面：`location=HEX(q,r)` 且 `q_r` 在 social scope 内；
  - unit 面：`location=UNIT(u)` 或 `hh ∈ Unit.households(u)` 且 `u` 在 unit scope 内
    （Gov scope = 自己 + 直辖区内单位，不含下辖 GOV/后代；Army/Nation scope 按其各自已定口径）；
  - 只命中 unit 面、hex 不在范围的家户：可以进 unit 分组/单位汇总，但 `HEX` 维度输出 `hex=null` +
    `unavailable: hex-hidden-by-scope`，**不泄露格坐标/格人口**。
- **GM**：`scope=ALL`，不过滤；`scope=VISIBLE/HEX/...` 也允许。
- 读口本身只读不写；错误码：
  - `BAD_REQUEST`：参数/维度不支持；
  - `FORBIDDEN`：决策人用 `ALL`；
  - `NOT_FOUND`：`HEX/HOUSEHOLD/UNIT` 指定对象不存在或不在可见范围（拒因与不存在一致，不做存在性侧信道）。

### 2.6 易用性示例

```json
// GM：全体家户按年龄段
{"scope":{"kind":"ALL"},"groupBy":["AGE_BRACKET"],"metrics":["population","births","deaths","birthRatePerMille","deathRatePerMille"]}

// GM：某阶层 × 某生产方式 全量
{"scope":{"kind":"ALL"},"filters":{"stratum":["poor_peasant"],"productionMode":["tenancy_fixed_kind"]},"groupBy":["RESIDENCE"],"metrics":["population","laborMilli","naturalNeeds","debtPrincipal"]}

// GM/决策人：单个 hex 的年龄 × 阶层交叉
{"scope":{"kind":"HEX","q":0,"r":1},"groupBy":["AGE_BRACKET","STRATUM"],"metrics":["population","householdCount"]}

// 决策人：所有可见 hex 的总量与逐 hex 出生/死亡率
{"scope":{"kind":"VISIBLE"},"groupBy":[],"metrics":["population","births","deaths"]}
{"scope":{"kind":"VISIBLE"},"groupBy":["HEX"],"metrics":["population","birthRatePerMille","deathRatePerMille"]}

// 中央决策人：自己直辖区内单位家户汇总（辖区外无数据；辖区外情报必须先上报）
{"scope":{"kind":"VISIBLE"},"filters":{"unitId":["gov-central-1"]},"groupBy":["UNIT"],"metrics":["population","laborMilli","birthRatePerMille","deathRatePerMille"]}

// 中央决策人读上报情报（不是原始家户数据；详见 D4 simos.sd.reports）
{"scope":{"kind":"VISIBLE"},"filters":{"reportTag":"province-701"}}
```

## 3. 决策包完整版（DecisionPacket）

### 3.1 现状与目标

- 现状：决策人只能 `sd.IssueDirective`（裸 `{type,payloadJson}`），GM 用 `sd.AdjudicateTick` 一次裁决。
- 目标：决策人填 **FormattedCall = 真实工具名 + JSON 参数**；一决策人 × 一 tick = 一个 **DecisionPacket**；
  GM 按包看参数/目标/预览，做 **true/false**；同 tick 多包冲突走 **MERGED** 合并效果集；执行以 GM 身份、
  proposer 留痕。

### 3.2 持久实体（SdData 新组件）

```text
DecisionPacketId(String) / FormattedCall / MergedEffectPlanId(String)

DecisionPacket(
    id, branch, tick, proposerId, status,
    intent?, calls[], createdAtRevision, decidedBy?, decidedAtRevision?, reasonInfoId?)

FormattedCall(
    callIndex, toolName, argsJson, targets[],
    previewJson?, draftChecks[], status: PENDING|APPROVED|REJECTED|MERGED,
    mergedPlanId?)

MergedEffectPlan(
    id, tick, participantIds[], orderedEffects[],
    sources[], reasonInfoId?, outcome?)
```

- `PacketStatus`: `PENDING / APPROVED / REJECTED / MERGED / PARTIALLY_APPROVED`（逐 call 状态为准）。
- `targets` 用新 `CommandTarget(namespace,path)` 或工具级目标；范围校验在拟稿与执行两层都做。
- 旧数据：新组件缺键 ⇒ 空表；旧 `Directive` 保留只读/兼容，不再作为新入口。

### 3.3 决策人侧工具（只挂决策人桶）

| 工具 | 作用 |
|---|---|
| `simos.sd.propose` | `{tool, args, intent?}`：校验工具在 charter 内、参数 schema 合法、目标在 scope 内；调目标工具预览（不落盘），把一个 FormattedCall 写进当前 tick 的 DRAFT packet |
| `simos.sd.packet.intent` | `{text}`：NL 轨道单独留痕（不进 true/false 机械执行） |
| `simos.sd.packet.submit` | `{}`：把当前 tick 的 DRAFT packet 置为 PENDING（幂等：同 tick 同 proposer 只有一个 packet） |
| `simos.sd.packet.my` | `{}` 或 `{tick?}`：决策人读自己的 packet 与状态/预览 |

- `simos.sd.propose` 与目标工具的关系：**决策人调用 propose，不直接调 GM 工具**；propose 内部在拟稿沙箱里
  调目标工具的 preview 路径（`preview=true` / 纯推导），不落 revision。
- `charter` 来源：`DecisionMaker.allowedTools`（本轮先由 GM 直接配置；后续接机制计算）；`allowedTools` 现在是
  **拟稿白名单**，真的强制。
- 不允许的：propose 一个不在 charter/注册表的工具、args 不合 schema、目标不在 scope ⇒ 具名拒，不写 packet。

### 3.4 GM 侧工具

| 工具 | 作用 |
|---|---|
| `simos.gm.packets` | 列表：按 tick/status/proposer/tool 筛选；只发摘要与计数 |
| `simos.gm.packet` | 单个 packet 详情：intent、每个 call 的参数/目标/预览/拟稿拒因/状态 |
| `simos.gm.packet.decide` | `{packetId, decision: APPROVE|DENY|MERGE, note?, callIndexes?}`；true 可附理由；false 不执行并写 INFO；MERGE 转合并流程 |
| `simos.gm.mergedPlan.upsert` | 建/改 merged plan：有序 effects（原始 call 或 GM 编辑后的命令）、sources、reason |
| `simos.gm.mergedPlan.apply` | 把一份 merged plan 落成一条 revision（原子批） |
| `simos.gm.packet.execute` | 把某 tick 已 APPROVED 的 calls 按 packet/call 稳定序落成一条 revision；执行者身份 = GM，proposer 记在 outcome |

- 审批链：`simos.sd.propose` 不弹审批（只写 DRAFT/PENDING）；GM 的 `packet.decide`/`mergedPlan.apply` 是敏感写，
  走现有 `ToolGate.Ask`（GM/MCP 链 `GmAutoApproveGate`，GUI 链人工）。
- GUI「决策 → 审批」页在现有审批面上加 packet 视图：整包 true/false、逐 call 预览、合并入口。

### 3.5 执行语义（可用的完整版）

1. 决策人一轮运行：多次 `simos.sd.propose` + 最后 `simos.sd.packet.submit`；
2. GM 到下一 tick 前审：`packets` → `packet` → `packet.decide`；
3. 无冲突：`packet.execute` 一批执行所有 APPROVED calls；
4. 有冲突：`packet.decide(MERGE)` + `mergedPlan.upsert`（GM 显式排序/改写）+ `mergedPlan.apply`；
5. 执行后 outcome（逐 call applied/rejected + 受影响资源变化）写回 packet / INFO，决策人用 `packet.my` 查看；
6. **执行者身份 = GM**；每条 call 的 `proposerId` 进 outcome/日志；
7. **一个 tick 的所有执行 = 一条 revision**（沿用 `CoreSimos.submitBatch` 的原子语义），冲突/失败退回 GM 重裁；
8. 兼容：现有 `sd.AdjudicateTick` / `IssueDirective` 保留为“旧口”，新体系不依赖它们；后续可只读归档。

### 3.6 拟稿期校验与权限

- 工具必须在 charter + 注册表；
- 工具参数按 `jsonSchema` 校验；
- 工具级目标：为每个可被 propose 的工具实现 `DecisionProposable`（返回目标资源 + preview），
  或复用它的 `CommandTargets` 派生；目标按 3.6 的 `CommandTarget` 规则与 scope 比对；
- 预览必须真调目标工具的 preview 路径（同一份纯推导），不另写一套；
- 跨权限：决策人只能 propose 目标在自己 scope 内的调用；GM 合并/改写不受限。

## 4. GM 工具补齐清单

1. **`simos.gm.households`**：可直接复用 `simos.social.households`（GM 桶允许 `scope=ALL`）；
   如需独立名字，做一个别名工具，视图同源。
2. **`simos.gm.periodicAdjustment`**：P4a 规则表的 GM 窄工具（list/upsert/remove/preview/due 读口）。
3. **`simos.gm.armyPayPolicy`**：P4b `unit.SetArmyPayPolicy` 的 GM 窄工具（preview/apply/read）。
4. **`simos.gm.vitalRates`**：全局/家户出生率、死亡率查看/调整（Social 接口）。
5. **`simos.gm.adjustPopulation`**：指定家户直接加减人口（走 Social 工单 `ADJUST_POPULATION`/`ADD_MEMBERS`）。
6. **决策包 GM 工具**：`packets/packet/packet.decide/mergedPlan.*/packet.execute`。
7. **Catalog/PAYLOAD_HINTS/工具桶/文档**：新工具全部进对应桶与 Catalog，decision 桶只放读 + propose/submit/my。
8. **GUI**：决策→审批页扩展 packet 视图；GM 工具面板与 Catalog 同步。

## 5. 实施批次建议

| 批次 | 内容 | 验收 |
|---|---|---|
| D1 | `HouseholdQueryService` + `simos.social.households` 读工具（同工具 GM/决策人按 scope） | GM 一次调用按年龄/阶层/生产方式聚合；决策人只看到 scope 内；单 hex 与 population 工具对账 |
| D2 | DecisionPacket/FormattedCall 持久组件 + Codec/ChangeSet + `sd.propose/submit/my` + `gm.packets/packet/decide` | 决策人提议→GM 看到预览→true/false；旧数据空表兼容 |
| D3 | MERGED 合并效果集 + `gm.mergedPlan.*` + `gm.packet.execute` + outcome 回写 | 两包冲突→GM 合并→一条 revision；执行者 GM、proposer 留痕 |
| D4 | GM 工具补齐（periodic/armyPay/vitalRates/adjustPopulation）+ Catalog/GUI | 每个 GM 工具 preview/apply/拒绝语义有 smoke |
| D5 | 测试迁移 + clean verify + 文档收口 | `test-compile` 绿、verify 通过 |

## 6. 需要用户确认的开放点

1. **FormattedCall 目标声明**：可 propose 的工具是否统一实现 `DecisionProposable`（推荐，显式目标+预览），
   还是先只支持已有 `preview=true` 的 GM 工具自动拟稿？
2. **charter 初始清单**：第一批允许决策人 propose 哪些工具？建议先：
   `simos.unit.raiseUnit`、`simos.gov.recruit`、`simos.gov.retireStaff`、`simos.unit.setArmyPayPolicy`、
   `simos.social.household.members`（受限 action）、`simos.unit.levy/Select/Dispatch`。
3. **执行粒度**：按 tick 一次批（推荐）还是每个 packet 一条 revision？
4. **`simos.social.households` 是否接受多 groupBy 交叉**（推荐支持，输出组合键）？
5. **出生/死亡率显示**：`CONFIGURED` 加权、`OBSERVED` 窗口、`BOTH` 默认给哪个？
   建议默认 `BOTH`，GM/决策人都能看两个数。
6. **GM 读工具**：直接复用同名 `simos.social.households`（GM 桶 scope=ALL）还是另起 `simos.gm.households` 别名？
   建议复用同名，避免两份视图。
