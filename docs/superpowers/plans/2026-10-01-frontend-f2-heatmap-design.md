# F2 设计增补：人口 / 粮食 / 经济热力图 + 区域国家汇总

> 日期：2026-10-01
> 前置：F1 已由用户点网页验收通过（入口 `/`，世界视图 + 图层抽屉 + GOV 辖区）。
> 依据：`docs/superpowers/plans/2026-10-01-frontend-map-first-plan.md` §5；用户裁定「世界总览面板 + 逐格事实，并再做地图热力图」。
> 状态：**用户 2026-10-01 已点网页验收通过 F1、F2（含用户实测后追加的两项修正：政府图层默认关、GovDaily 粮耗默认 120× 修正为每日 83 毫粮）；测试仍留到最后统一补。**

---

## 0. 原则

1. **只读**：F2 不新增写命令、不改 `Command → ChangeSet → Revision`、不动领域持久状态。
2. **一条事实一个来源**：热力图的值必须来自服务端装配（`ApiViews` / `GuiServer` 只读视图），前端不重算、不补 0。
3. **口径写在地图上**：每层带单位、统计窗口与 caveat；算不出的指标明确不可用，不填 0 冒充。
4. **class-first 是世界级**：4 个阶层池没有 hex/国家维；地图热力图只画**真实存在的逐格事实**（人口、actor 货物/货币、逐格投影等）。世界级池/账户/土地市场/守恒继续走「世界总览」面板。
5. 一次只叠加一个热力指标，避免颜色/量纲互相污染；不同时画多个数据层。

---

## 1. F2 热力图指标（推荐首批）

### 1.1 人口层（social）

| 指标 id | 显示名 | 数据来源 | 单位 |
|---|---|---|---|
| `populationTotal` | 人口·总 | `SocialData` 该格 `populationAt`（批次口径） | 人 |
| `populationRural` | 人口·农村 | `SocialData.urbanRuralAt` 的 rural | 人 |
| `populationUrban` | 人口·城市 | `SocialData.urbanRuralAt` 的 urban | 人 |

- 有记录但值为 0 ⇒ 作为 0 画最低档（0 是事实）；完全没有记录的格不入 cells、不着色。
- 统计窗口：时点快照（当前 tick 的出生/死亡已经写回 social 切片）。

### 1.2 粮食层（economy + actor）

| 指标 id | 显示名 | 数据来源 | 单位/窗口 |
|---|---|---|---|
| `grainStock` | 粮食·库存 | 该格全部 actor `GoodsAccount` 的 `grain` 余额之和 | 毫粮（时点） |
| `grainDailyNeed` | 粮食·日耗 | 该格 `ClassRow.naturalNeeds[grain]` 合计（与 `economyHex.grainDailyConsumption` 同源） | 毫粮/日（最近一次结算日） |
| `grainCoverageDays` | 粮食·覆盖天数 | `grainStock / grainDailyNeed`（分母 0 ⇒ `null`，不可用，不画） | 天（派生） |
| `grainCycleUnmet` | 粮食·周期缺口 | `grainDiagnosis.importDemand`（仅进程内报告在场时有值；否则具名 unavailable、不入图层） | 毫粮（本周期累计） |

- 覆盖天数只在 `grainDailyNeed > 0` 且 `grainStock` 可读时给；否则 `null` + 原因。
- 周期缺口只在报告可读时发；窗口是「本周期累计」，图例必须写明只有关账日读才是整周期量。

### 1.3 财富层（actor）

| 指标 id | 显示名 | 数据来源 | 单位/窗口 |
|---|---|---|---|
| `moneySilver` | 货币·银 | 该格全部 actor `GoodsAccount.money` 的 silver 合计 | 最小币值（时点） |
| `goodsGrain` | 货物·粮 | 与 `grainStock` 同一份 actor 余额（首版可不单列，保留 id 供后续） | 毫粮（时点） |

- 其它币种按 `currencyDefs` 词表逐币种：首版默认只做 silver；UI 可后续加币种下拉。
- 不提供「跨币种求和」的 money 总量（不同币种不可加，与 economy 读口同口径）。

### 1.4 阶级 / 生产投影（可选，第二批）

| 指标 id | 显示名 | 数据来源 | 说明 |
|---|---|---|---|
| `classPopulation` | 阶级人口·投影 | `economyHex.classes[]` 该格合计 | 逐格投影（日末回写），不是生产权威 |
| `classLabor` | 阶级劳动·投影 | `classes[].laborMilli` | 同上 |
| `classLand` | 阶级土地·投影 | `classes[].landMilliMu` | 同上 |

- 这些是 class-first 引擎写回的**逐格投影**；世界级 4 池/账户/土地市场才是权威。图层必须带 `scope: 逐格投影` 说明，避免被读成生产真值。
- 默认**不做**；用户确认要才进 F2。

---

## 2. 后端只读端点（F2）

### 2.1 `GET /api/map/heatmap?metric=<id>&branch=&revision=`

响应：

```json
{
  "metric": "grainStock",
  "label": "粮食·库存",
  "unit": "毫粮",
  "scope": "逐格 actor 账本（时点）",
  "tick": 0,
  "cells": [{"q": -39, "r": -71, "value": 1978110833}],
  "stats": {"count": 799, "min": 0, "median": 123, "max": 1978110833},
  "unavailable": null
}
```

- 未知 metric / 该指标结构性不可得（如 `grainCycleUnmet` 没有进程内报告）⇒ 整层 `unavailable` 具名，`cells: []`，不填 0。
- `cells` 按 `(q,r)` 自然序（确定性），调用方不重排。
- 服务端按需现算：人口层走 social；粮食/财富层走 economy + actor；不把 `economyHex` 的 84 KB 逐格视图整份发给前端。
- 不新增写路径；端点实现放 `ApiViews`（GUI 与未来 MCP 读口共用）。

### 2.2 区域汇总扩展 `GET /api/map/regions/summary`

在现有字段上新增：

```json
{
  "id": "德意志第二帝国",
  "population": 6230000,
  "ruralPopulation": 5482400,
  "urbanPopulation": 747600,
  "cityCount": 108,
  "cityPopulation": 747600,
  "unitCount": 10,
  "govCount": 1,
  "grainStock": 9876543210,
  "silverMoney": 1234567,
  "goodsTotal": {"grain": 100, "fiber": 200, "iron": 300},
  "window": "人口 = 区域 hex 集合逐格求和（区域重叠会重复计入）；粮食/货币 = 区域所含格 actor 账本位合计（时点）"
}
```

- 区域重叠口径与 F1 一致；国家卡片只在 `meta.tag = nation:<id>` 的区域上求和（三国区域目前互不重叠，仍写 hint）。
- 世界级 class-first 不放这里，继续由 `/api/economy/overview` 的「世界总览」面板承载。

### 2.3 复用现有端点

- `/api/economy/overview`：世界级池/账户/土地市场/守恒（F1 已有）。
- `/api/social/population`：单格详情（F1 已有）。
- `/api/map/region/{id}`：区域 hex 集合（F1 已有）。

---

## 3. 前端渲染与交互

### 3.1 图层抽屉「数据」组

- 新增一个 `<select id="heatmap-metric">`，选项：
  - 不显示（默认）
  - 人口·总 / 人口·农村 / 人口·城市
  - 粮食·库存 / 粮食·日耗 / 粮食·覆盖天数 / 粮食·周期缺口（不可用时置灰）
  - 货币·银
  - （可选第二批）阶级人口·投影 / 阶级劳动·投影 / 阶级土地·投影
- 新增不透明度滑块（0.15–0.85，默认 0.55）。
- 热力图与 F1 图层正交：可叠加在城市/军队/GOV 图层之下；不改变 F1 已验收的开关语义。

### 3.2 渲染

- `map.js` 在 metric 变化时调 `GET /api/map/heatmap`，带当前 `target()`；缓存按 `(metric,target)`。
- `renderer.setHeatmap(payload)`：
  - `cells` 逐格画满格 hex 填充（`addHexPath`，radius=cellSize），颜色由 `worldmodel.heatmapColorScale(cells, {method})` 计划给出；
  - 绘制顺序：地形 → 国家着色 → GOV 辖区 → **热力层** → 区域高亮/边界 → 城市/单位/标签；
  - 图层顺序保证热力图不被地形盖住，也不盖住单位/标签。
- 缺失数据格**不着色**（露出地形）；0 值若来自服务端 cells，则作为最低档画。
- 图例：地图左下/右下 HUD 增加 `#heatmap-legend`，显示指标名、单位、min/median/max、无数据格说明、世界级/逐格/窗口 caveat。
- 大图性能：F2 首版只需覆盖有数据的格子（当前 799 格）；若未来扩展到 59223 格，走「视口裁剪 + 离屏位图缓存」，不逐帧重建全图。

### 3.3 色标

- 默认**分位数**（将本指标 cells 按值排序均分为 7 档；值相同可合并，图例显示每档实际值边界），适合人口/粮食/货币的长尾。
- 可选**对数**（对 `value > 0` 取 log10 分档；0 单独一档）以在 UI 下拉里切换；为 F2 可选，不阻塞首批。
- 纯函数 `heatmapColorScale` / `heatmapLegend` 放 `worldmodel.js`，门禁可直接断言：
  - 空输入 ⇒ 空计划；
  - 相同值 ⇒ 确定性分档；
  - 颜色数量 ≤ 调色板长度；
  - 无穷/NaN 值不入档并具名。

### 3.4 区域 / 国家汇总

- 右栏「世界总览」三国卡片新增：农村/城市人口、粮食库存、银货币。
- 区域详情（点区域/区域模式）增加：population/rural/urban、city/cityPopulation、grainStock/silverMoney、goods 合计、单位/GOV 计数。
- 继续保留 F1 的 `world overview` 世界级 class-first 摘要，并与逐格热力层明确分区（世界级 vs 逐格）。

---

## 4. 手动验收（F2 通过标准）

1. 图层抽屉「数据」选择「人口·总」⇒ 地图按人口着色，首都最亮；选「不显示」⇒ 配色消失，地形恢复。
2. 选择「粮食·库存」「粮食·覆盖天数」「货币·银」⇒ 颜色变化；点同格左栏读数与图例/服务端一致。
3. 选择「粮食·周期缺口」在无进程内报告时 ⇒ 图例明确写“不可用 + 原因”，地图不填 0。
4. 世界总览三国卡片新增粮食/货币数字；与对应区域汇总/actor 账相符。
5. 切换 F1 的军事/政府/城市预设 ⇒ 热力层不被误删，图层之间仍正交。
6. 旧 `/map` 页可打开；不选热力指标时与 F1 渲染一致。
7. 用户点网页验收通过后，F2 才关账。

---

## 5. 不做什么

- 不做跨 Region 市场/运输、逐格市场重建、生产公式调整。
- 不做 class-first 逐格生产量；阶级层默认是投影并带 caveat。
- 不做时间维热力动画（只画当前 target 快照）。
- 不新增 npm/打包器/CDN；继续纯 `<script>` + Canvas。
- 不新增任何写命令或绕过 `Command → ChangeSet → Revision` 的入口。
