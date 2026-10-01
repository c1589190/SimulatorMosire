# 后端 MCP：GM 驱动的国家数据初始化 + 城市/省份生成器（本轮开发计划）

> 日期：2026-10-01
> 状态：**计划已记录，调查先行；生产代码未动**
> 分支：`refactor/class-first-economy`
> 来源：用户 2026-10-01 连续裁定（城市/省份生成器可一键落盘；目标区域已有相关元素则不得一键复写；先后端、GUI 暂缓）

---

## 0. 本轮用户裁定（地基，逐条）

1. **先后端，后 GUI。** 本轮只做后端功能 + 对外 MCP 工具面的稳定化；GUI 页面、面板、模式切换全部推迟。
2. **空白国家按 GM 意愿补全数据，而不是“无经济空壳”。** 对没有任何人口、家户、城市、经济、军事数据的国家，GM MCP 工具组应能按需要初始化**所有可以有数据的域**：人口、人口批次、家户 actor、城市、class-first 经济地基、行政中心、决策人、军队。
3. **政府创建统一走 Unit 流程。** 中央/地方政府首先是一个 `Unit`：
   ```text
   unit.CreateUnit
    → unit.SetGovFormation(level=CENTRAL|PROVINCE, superiorGov? …)
    → unit.SetJurisdiction(unitId, regions[…], …)
    → sd.CreateDecisionMaker(Affiliation.Gov(unitId), …)
    → sd.SetDecisionMakerProvider / sd.SetDecisionMakerAccess（可选）
   ```
   不新增“政府实体创建命令”，不建第二套政府语义。现有 `simos.gov.createOffice` 若保留，必须是上述 unit/sd 命令的**薄组合**，不得绕开 Unit。
4. **自动城市生成器/城市划分器**：既能只出计划（建议），也能**一键落盘**。
5. **自动省份划分器**：既能只出建议划分，也能**一键落盘**。建议模式不写任何状态；一键落盘模式不得绕过现有 Region/Unit/DecisionMaker GM 工具，落盘的命令序列与 Agent 自己逐步调用时一致。
6. **一键落盘的硬门（本轮新增）**：
   - 目标区域内存在**任意相关元素** ⇒ **拒绝一键复写**（fail-closed），返回具名拒绝，不做部分覆盖、不做静默合并、不留半截 revision。
   - 只有目标区域“干净”（没有本生成器管辖范围内的既有元素）才允许一键落盘。
   - 已有元素的区域仍可走建议模式；也可以由 Agent 用逐元素 GM 工具在既有状态上增量操作。
7. **省份/城市生成器仍以 Agent 为主导。** 生成器负责算计划与（可选）批量落盘的便利；Agent 始终可以选择只取计划，然后自己逐条调用 GM 工具创建 Region / Unit / GOV / 决策人。
8. **按格建军。** 外部 MCP Agent 需要能指定一个 hex 创建军队；直接建军（GM 特权，不抽人抽粮）与抽取组建（从人口/国库抽）是两条语义，工具名与行为必须分开，不许混成一条“看起来都可以”的工具。

---

## 1. 目标能力（验收 B0–B9）

| # | 验收项 | 可观察判据 |
|---|---|---|
| B0 | 地图加载 | jar 启动后地图 Region/hex 与真档一致；MCP 读口能列出全部 Region |
| B1 | 数据盘点 | 任选一个空白 Region，GM 能读到“缺哪些数据域”的具名清单 |
| B2 | 人口/家户显式指定 | GM 可按 Region/Hex 批量指定人口、人口批次、家户 actor；写入同批一条 revision；守恒/身份不变式由域层判 |
| B3 | 自动城市生成器（建议） | 给 `regionId + totalPopulation + urbanizationRate + seed + capital?`，返回逐格农村人口 + 城市表 + 审计量；**不写状态** |
| B4 | 自动城市生成器（一键落盘） | 同参数 + `preview=false`：目标 Region 无相关元素 ⇒ 一条 revision 落 `social.SetPopulation` + `social.SeedGroups` + `social.CreateCity`（可选 economy/actor）；有相关元素 ⇒ 具名拒绝、零 revision |
| B5 | 自动省份划分器（建议） | 给 `regionId + maxHexPerProvince` 等，返回建议省界/中心/hex 数；**不写状态**；连通性与均衡可核对 |
| B6 | 自动省份划分器（一键落盘） | 目标范围内无相关元素 ⇒ 同一批 / 多批落 `map.CreateRegion` + `unit.CreateUnit` + `unit.SetGovFormation` + `unit.SetJurisdiction` + `sd.CreateDecisionMaker`；命令序列与 Agent 手动调用逐条同源；有元素 ⇒ 具名拒绝 |
| B7 | Unit 政府流程 | 中央/省 GOV 只能经 `unit.CreateUnit + unit.SetGovFormation` 出现；不存在的旁路要么没有，要么在调查报告中具名删除 |
| B8 | 按格建军 | `直接建军` 与 `抽取组建` 两条工具都可经 MCP 调用；失败具名；落真实 revision |
| B9 | 决策人 → 裁决 → 下一回合 | 能查到本 tick 应行动的决策人、批量派出、汇总轨迹/结果/文档，裁决可改地图/单位，`advance` 进入下一回合 |

---

## 2. 现有资产（调查起点，最终以调查报告的 `文件:行` 为准）

### 2.1 可复用的生成/播种资产

| 资产 | 位置 | 可复用性 |
|---|---|---|
| `SettlementGenerator.generate(request, terrain, params)` | `simos-social/.../generate/SettlementGenerator.java:83` | 自动城市生成器可直接复用；纯函数、确定性 |
| `SettlementParams.defaults()` | `.../SettlementParams.java:282` | 全套默认参数在代码里，不依赖冻结 JSON |
| `TerrainView.of(GameMap)` | `.../TerrainView.java:38` | 地形只读口子 |
| `SettlementRequest` / `CapitalAnchor` / `PlannedCity` | `.../SettlementRequest.java` 等 | GM 参数 → 生成请求的现成形状 |
| `PopulationSeeder.groups(plan, tick)` | `simos-app/.../world/PopulationSeeder.java:91` | 把 `SettlementPlan` 翻成 `social.SeedGroups` 批次 |
| `EconomySeeder.plan(...)` / `payload(...)` | `simos-app/.../world/EconomySeeder.java:907/964` | 把同一批人口翻成 `economy.Seed`（class-first） |
| `HouseholdSeeder.payload(...)` | `simos-app/.../world/HouseholdSeeder.java` | 把同一批库存/货币翻成 `actor.Seed` 家户 actor |
| `WorldgenInitializeTool` | `simos-app/.../tools/write/WorldgenInitializeTool.java:402` | 现有最接近的编排参考，但只认 3 国冻结配置；需要抽成“任意 Region + GM 参数” |

### 2.2 现有 GM 命令/工具接缝

| 能力 | 现有命令/工具 | 备注 |
|---|---|---|
| 逐格人口 | `social.SetPopulation` | 命令在；缺窄/批量工具 |
| 人口批次 | `social.SeedGroups` | 命令在；与人口同批的校验要查 |
| 城市 | `social.CreateCity` / `social.UpdateCity` | 命令在；缺城市生成器与批量工具 |
| 经济地基 | `economy.Seed` | 命令在；与人口批次、actor 必须同批 |
| 家户 actor | `actor.Seed` | 命令在；依赖经济计划同一份库存/货币表 |
| Region | `map.CreateRegion` / `map.UpdateRegion` | 已有窄工具 |
| Unit | `unit.CreateUnit` | 已有窄工具 |
| GOV 编制 | `unit.SetGovFormation` / `unit.SetGovPolicy` / `unit.SetGovSuperior` | 已有窄工具；中央/省一律走这里 |
| 管辖 | `unit.SetJurisdiction` / `unit.SetTaxRate` | 已有窄工具 |
| 决策人 | `sd.CreateDecisionMaker` / `sd.SetDecisionMakerProvider` / `sd.SetDecisionMakerAccess` | 已有窄工具 |
| 抽人抽粮建军 | `simos.unit.raiseUnit` | 已有；语义 = 抽取组建 |
| 直接建军 | 无专属组合工具 | `unit.CreateUnit` + `sd.CreateArmy` 可手工批，但缺“按格直接建军”的 GM 工具 |
| 省份划分 | **无** | `Region` 支持任意 hex 集；落盘已有工具 |
| 决策人调度 | `SdQueryService.due`（只读派生） | 缺“显式本 tick 行动名册”与批量派出工具 |
| 裁决/回合 | `sd.AdjudicateTick` / `sd.VoidAdjudication` / `sd.RejectDirective` / `simos.advance` | 可达性、组合脚本与失败态待调查 |

### 2.3 v17levant 数据覆盖

- `config/worldgen/v17levant-nations.json` 只有 **3 国** 的冻结数据（德意志第二帝国、奥斯特马克侯国、霍赫兰伯国）。
- 真档地图有大量 Region；16 个有名势力、82 个程序化噪声区域。
- 本轮不要求一次初始化全部 98 个 Region；调查先给出“任意 Region 可用”的结论与边界，是否批量初始化由后续裁定。

---

## 3. 工具面建议（调查阶段先定边界，不在本轮写代码）

### 3.1 自动城市生成器/城市划分器

建议工具名：
- `simos.city.generate`：默认 `preview=true`，只出计划；
- `preview=false` + 干净门 ⇒ 一键落盘。

输入（草案）：
```text
regionId, totalPopulation?, ruralPopulationByHex?, urbanizationRate?,
agrarianSurplusRate?, commercialIntegration?, politicalCentralization?,
seed?, capital?{hex,name,targetPopulation?}, documentedNames[]?,
params override?, preview?(default true), branch?, expectedRevision?,
reason
```

干净门（城市侧“相关元素”）建议定义，最终由调查报告确认：
- 目标 Region 的 hex 集内已有**农村人口序列非零**；
- 已有任何 **City** 落在目标 hex 集；
- 已有 **PopulationGroup** 指向目标 hex 集；
- 已有 **class-first 经济格/家户 actor** 指向目标 hex 集。
满足任一 ⇒ `preview=false` 具名拒绝，列出命中类型 + 计数 + 示例 id，提示改用逐元素工具；`preview=true` 仍可用。

落盘批次（干净门通过时，同批一条 revision）：
```text
social.SetPopulation
→ social.SeedGroups
→ social.CreateCity × N
→ （GM 需要时）economy.Seed
→ （GM 需要时）actor.Seed
→ sd.PutInfo（行动记录）
```
经济/家户是否默认带上，是调查报告的裁定项；无论默认与否，都必须能由 GM 显式选择。

### 3.2 自动省份划分器

建议工具名：
- `simos.province.divide`：默认 `preview=true`，只出建议；
- `preview=false` + 干净门 ⇒ 一键落盘。

输入（草案）：
```text
regionId, provinceCount? | maxHexPerProvince?(default 50) | minHexPerProvince?(default 20),
capitalHex?, capitalDistrictRadius?,
namingPrefix?, seed?, preview?(default true), branch?, expectedRevision?, reason
```

输出（建议模式）：
```json
{
  "regionId": "…",
  "hexCount": 430,
  "suggestedProvinceCount": 9,
  "provinces": [
    {"suggestedRegionId":"德意志第二帝国__P01","name":"…","center":{"q":…,"r":…},
     "hexes":[{"q":…,"r":…}, …],"hexCount":47,
     "contiguous":true,"notes":[…]}
  ],
  "capitalDistrict": {"center":…, "hexes":[…]}?,
  "warnings": ["…"]
}
```

约束：
- 省界必须在目标 Region 的 hex 集内、六邻接连通；
- 大小尽量落在 `[minHexPerProvince, maxHexPerProvince]`；Region 太小 ⇒ 1 省 + warning；
- 首都圈可单列，是否默认单列由调查报告给选项；
- 确定性：同输入同输出；建议模式不得写任何状态；
- 建议 ID 只作建议，Agent 可改用自己给的 ID。

一键落盘门（省份侧“相关元素”建议定义）：
- 目标 Region 之外，已有任何 **Region** 与目标 Region 的 hex 集相交（即已经被别人切过）；
- 目标范围内已有任何带 `GovFormation` 的 Unit；
- 目标范围内已有任何 `Affiliation.Gov` 决策人；
- 已有任何 Unit 的 `jurisdiction` 覆盖目标 Region。
满足任一 ⇒ 拒绝一键落盘，列出命中元素，指路逐元素 GM 工具。

一键落盘的命令序列（与 Agent 手动调用同源）：
```text
map.CreateRegion × N
→ unit.CreateUnit × (N + 1)      // 每省 + 中央
→ unit.SetGovFormation × (N + 1) // PROVINCE + superiorGov=中央；CENTRAL 无上级
→ unit.SetJurisdiction × N       // 每省纳入自己的 Region
→ sd.CreateDecisionMaker × (N + 1)
→ sd.SetDecisionMakerProvider / sd.SetDecisionMakerAccess（如给）
→ sd.PutInfo（行动记录）
```
中央 GOV 的 jurisdiction 口径（只授首都圈 / 空管辖 / 其他）在调查报告中给出选项。

### 3.3 显式数据播种工具（人口 / 家户等）

调查是否新增：
- `simos.nation.populate`（高层，支持显式与生成混合）；
- 或者多个正交低层工具：`simos.social.setPopulation`、`simos.social.seedGroups`、`simos.actor.seedHouseholds`、`simos.economy.seedClassFirst`。
原则：
- 每个工具必须能被 Agent 单独调用；
- 写路径 = 现有命令，不新增绕过命令；
- 幂等语义要明确：同 Region 二次调用是拒绝、增量还是替换，不许静默合并。

### 3.4 按格建军

两条独立工具：
1. `simos.unit.spawnArmy`（直接建军，GM 特权）：`hex、name、member、equipment、speed、mobilityPerMille、masterGov?、parent?、armyId?、reason`；同批 `unit.CreateUnit` + `sd.CreateArmy`；不抽人口、不抽粮饷；失败具名。
2. `simos.unit.raiseUnit`（现有，抽取组建）：保留原语义，不改成“无前置也能用”。

### 3.5 决策人与回合循环

调查并补齐：
- due 清单读工具（或并入现有 `simos.sd.decision-makers` 的字段）；
- `simos.sd.runDue` 批量派出（逐个 `sd.RunDecision`，汇总每轮轨迹）；
- 汇总读口：令、结果、文档、运行状态；
- 裁决链：`sd.AdjudicateTick` → 真实命令批 → 地图/单位修改 → `simos.advance`；
- 待核实：`DecisionMaker.allowedTools` 是否真的进入运行时权限组；若没有，单列为“N9 静默失效”缺口。

---

## 4. 调查工作包（W1–W7，只读）

| # | 工作包 | 产出 |
|---|---|---|
| W1 | 数据域初始化能力盘点 | `数据项 → 权威状态 → 现有命令/seeder → 缺口`表 |
| W2 | 城市生成器可行性 | 输入/输出 schema、干净门定义、落盘批次、边界样例 |
| W3 | 省份划分器可行性 | 算法候选（递归二分 / 多源 BFS）、确定性样例、连通性/均衡证明、干净门定义 |
| W4 | Unit 政府流程核验 | 现有 `createOffice` 是否走 Unit 流程；中央/省 jurisdiction 口径；staff/policy 默认 |
| W5 | 按格建军 | 直接建军 vs 抽取组建的命令序列、校验、资源声明 |
| W6 | 决策人/回合闭环 | due 口径、批量派出、汇总读口、裁决→改图→advance 脚本 |
| W7 | MCP 稳定化 | 能力矩阵、缺工具清单、preview/apply 判据、覆盖测试设计 |

调查纪律：
- 只读代码；行为探针使用独立临时 store + 独立端口；
- 不抢正在跑的 Maven；不污染生产库；
- 每条结论带 `文件:行` 或可复现命令；
- 不写生产代码、不写测试、不 commit；
- 报告末必须有「未验证 / 做不到 / 需裁定」。

---

## 5. 交付物

`docs/superpowers/reports/2026-10-01-backend-mcp-stabilization-investigation.md`

1. B0–B9 验收矩阵；
2. 数据域初始化能力表；
3. 城市生成器工具设计 + 干净门定义；
4. 省份划分器算法选项 + 确定性样例 + 干净门定义；
5. Unit 政府流程核验；
6. 直接建军/抽取组建对比；
7. 决策人与回合闭环缺口；
8. MCP 工具面覆盖表与稳定化建议；
9. 建议实施批次（只给边界与判据，不写代码）；
10. 未验证 / 做不到 / 需裁定。

---

## 6. 后续实施批次（建议，等调查报告与用户裁定后再开工）

| 批次 | 范围 | 门禁 |
|---|---|---|
| P0 | 调查报告 | 只读；用户审阅 |
| P1 | 城市生成器（建议模式） | 纯函数/只读；确定性样例 |
| P2 | 城市生成器（一键落盘 + 干净门） | 空 Region 成功；有元素 ⇒ 具名拒、零 revision |
| P3 | 省份划分器（建议模式） | 纯只读；连通/均衡/确定性 |
| P4 | 省份划分器（一键落盘 + 干净门） | 命令序列与 Agent 手动路径逐条同源 |
| P5 | 显式数据播种工具 | 人口/批次/家户/经济的批量与失败语义 |
| P6 | Unit 政府创建流程核验与工具补齐 | 无绕过 Unit 的旁路 |
| P7 | 按格建军（直接 + 抽取） | 两条语义分开、审批/资源声明正确 |
| P8 | 决策人调度 + 裁决回合闭环 | due/批量派出/汇总/advance |
| P9 | MCP 工具面稳定化 + 覆盖测试 | 每条 GM 工具可达；preview/apply 判据 |

每个批次仍遵守 AGENTS.md §一.5：**一个阶段一个写代码代理，只编译；测试统一留最后；控制方提交。**

---

## 7. 需用户裁定（调查报告里只列选项）

1. 城市生成器一键落盘时，是否默认带上 `economy.Seed` + `actor.Seed`，还是只做人口/城市、经济另开一条工具？
2. 省份划分器的一键落盘是否允许同时创建决策人（N+1 个），还是只建 Region + Unit + GOV、决策人由 Agent 另外绑定？
3. 中央 GOV 的 jurisdiction：只授首都圈、空管辖，还是把原国家 Region 作为直辖（后者与“中央只读直辖”口径的冲突要显式裁定）。
4. 首都圈默认是否单列；若单列，半径/上限如何定。
5. 直接建军是否允许无人口、无粮饷凭空创建（GM 特权），还是必须至少有国库账户。
6. 干净门遇到“只有零人口批次/空账”这类空壳数据时，算不算“已有元素”（建议按“存在即拒绝”，请确认）。
7. 每个 DM 的 `allowedTools` 是否修成运行时硬白名单；若修，是否属于本轮 P8 范围。
