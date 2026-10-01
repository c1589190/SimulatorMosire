# 前端地图优先改造计划（F1 / F2）

> 日期：2026-10-01
> 分支：`refactor/class-first-economy`（HEAD `e8f09e19`）
> 依据：
> - `docs/superpowers/reports/2026-10-01-backend-mcp-stabilization-investigation.md`
> - 本会话 2026-10-01 探针实测（v17levant bootstrap + 三国 worldgen + 中央 GOV）
> - 用户 2026-10-01 对 5 个设计问题的裁定
> 状态：**计划已定；F1 代码未动；测试按仓规最后统一补**

---

## 0. 用户裁定（覆盖此前建议）

| 问题 | 用户选择 |
|---|---|
| 改造策略 | **增量改造现有工作台**：复用 Canvas 渲染器 / 六模式 / 编辑与决策页；不重做、不引入 npm |
| 首版范围 | **1 + 2，一个一个做，我自己点网页验收**：先 F1，F1 验收通过再做 F2；F3（GM 操作 UI）暂不做 |
| 后端只读口 | **允许新增紧凑只读视图**：城市列表、区域聚合、经济总览；只读，不改领域写路径 |
| 测试世界 | **仓内引导档 + 现有 store 都备份**；另起专用前端测试库 |
| 经济展示 | **世界总览面板 + 逐格事实，并再做地图热力图**（热力图归 F2） |

---

## 1. F1 目标与验收

### 1.1 一句话目标

世界视图下能**看清图上有什么**：国家/区域、城市、军队、GOV、决策人、交战与路线；点击任一实体能读到**服务端真值**；class-first 经济不再把“世界级/未接线”显示成 0 / 无。

### 1.2 F1 必须交付

1. 城市图层：worldgen 后 201 座 social 城市出现在地图上；首都/等级分层显示；点击出城市详情。
2. 单位图层：军队按现有 formation root 聚合；GOV 单位与 army 单位可区分；名称/人数/位置可见。
3. 区域/国家：国家疆域着色、区域边界与名称；无选中时右栏显示世界总览（三国卡片 + 计数）。
4. 决策人：`affiliation.kind = "gov"` 有中文标签、分组、单位匹配；GOV 决策人可被选中/定位。
5. 检查器：hex / 城市 / 单位（含 GOV）/ 区域详情；单位详情补 `module`、`jurisdiction`、staff、policy。
6. 搜索定位：按 id / 名称搜索城市、单位、区域、GOV、决策人；回车居中并选中。
7. 图层开关：至少可开关国家着色、区域名、城市、军队/GOV、决策人、交战、路线。
8. **经济真相**：`classFirst.available = true` 时，hex 详情和世界总览只读 class-first 权威字段；不再读 `money`/`industries`/`flows` 旧投影并把 0 / 空数组当真相。
9. 两宿主页不回归：`/`（index.html）与 `/map`（map.html）都能加载；新脚本按 `AGENTS.md` §六 的加载顺序与 `BUNDLE_DEPS` 规则。

### 1.3 F1 不做

- 不做人口/粮食热力图（F2）。
- 不做 `region.seed` / `clear` / `province.*` / `spawnArmy` / `createOffice` 的 GUI 操作入口（F3，暂不排）。
- 不新增写命令，不修改 `Command → ChangeSet → Revision` 任何领域路径。
- 不改 `simos-app/src/main/resources/worlds/v17levant.json`。

---

## 2. 后端只读端点（F1）

> 全部只读；装配逻辑放 `ApiViews`，`GuiServer` 只做路由与参数解析；GUI 与未来 MCP 读口共用同一份视图。
> 响应字段按 id / 坐标等稳定序发出；不依赖 `Map` 迭代序。

### 2.1 `GET /api/social/cities`

- 数据源：`SocialData.cities()` + `SocialData.urbanPopulationAt(CityId)`。
- 参数：可选 `?region=<regionId>`（只列 `SocialCity.region == regionId` 的城；未给 = 全量）。
- 响应：
  ```json
  {
    "cities": [
      {
        "id": "…",
        "name": "日耳曼尼亚",
        "at": {"q": -39, "r": -71},
        "region": "德意志第二帝国",
        "tier": "MajorCity",
        "population": 350000,
        "props": {"tier": "MajorCity", "catchmentHexes": 6}
      }
    ]
  }
  ```
- `tier`：优先 `props.tier`；缺失 ⇒ `null`（不猜、不编等级）。
- `population`：`urbanPopulationAt(id)` 现算；城存在但不人口批次 ⇒ `0`（0 是事实）。
- 排序：`id` 字典序。
- `as=`：与 `/api/social/population` 同口径；带 `as=` 时按 `seesHex(actor, target, city.at())` 过滤；不带 `as=` 全量。

### 2.2 `GET /api/map/regions/summary`

- 数据源：`GameMap.regions()` + `SocialData.populations()/cities()` + `UnitState`。
- 响应：
  ```json
  {
    "regions": [
      {
        "id": "德意志第二帝国",
        "name": "德意志第二帝国",
        "hexCount": 430,
        "meta": {"color": "#d5561d", "tag": "nation:德意志第二帝国", "description": "", "annexedBy": ""},
        "population": 5482400,
        "cityCount": 108,
        "cityPopulation": 747600,
        "unitCount": 10,
        "govCount": 0
      }
    ]
  }
  ```
- `population`：该 Region 的 `hexes` 逐格 `SocialData.populationAt(hex)` 求和；重叠 Region 的同一格会在两边都计入 ⇒ 字段旁必须能看出是“区域格集求和”，不是世界守恒量。
- `cityCount` / `cityPopulation`：`SocialCity.region == id`；`region` 为空的城只可能落在该 Region 时再按 `at` 的格归属？**F1 先只认显式 `region`，不按落点猜归属**，并在文档里写明；缺 region 的城不进入任何区域汇总。
- `unitCount`：`UnitState.effectivePosition(unitId, at)` 落在 Region 的 hex 集合内的单位数（含 GOV）。
- `govCount`：其中 `unit.module()` 为 `GovFormation` 的数量。
- 排序：`id` 字典序。
- 不带 `as=` 的 GUI/GM 读口；带 `as=` 时沿用现有读端点的 fail-closed 约定（若本轮不接 redaction，则显式拒绝，不得静默全量）。

### 2.3 `GET /api/economy/overview`

- 数据源：`EconomyData` + `ActorData`；与 `economyHex` 的 class-first / money / conservation 口径同源。
- 响应：
  ```json
  {
    "activated": true,
    "tick": 0,
    "scope": "世界级：…",
    "classFirst": { /* ApiViews.classFirstView 的完整对象 */ },
    "moneyIssuance": { /* 发行/回笼/流通量 */ },
    "moneyByActorKind": { /* actor kind → 币种 → 数量 */ },
    "moneyByHouseholdClass": { /* 家户阶层 → 币种 → 数量 */ },
    "moneyLayers": { /* 三个守恒分栏 */ },
    "currencyDefs": [ … ],
    "moneyInstruments": [ … ]
  }
  ```
- **不逐格**；单格详情仍走既有 `/api/economy/hex`。
- 该端点让前端不必为了世界总览重复拉 84 KB/格的经济响应。

### 2.4 测试世界数据所需的最小 seed

F1 验收库使用 `2026-10-01-frontend-f1`（已存在）：
- 空库 bootstrap：59223 hex / 252 区域 / 782 地形块 / 240 河流边。
- `simos.worldgen.initialize` × 3 国：201 城市 / 26 单位 / 三国经济。
- 已补 1 个中央 GOV + 1 个 GOV 决策人；如代码代理需要更多 GOV 样本，可在测试库补，但不得把 seed 写进生产代码。

---

## 3. 前端改造（F1）

### 3.1 新增纯函数模块 `webui/worldmodel.js`

只放纯函数，便于门禁后续直接断言：

- `cityMarkerPlan(cities, scale, layers)` ⇒ 标记 + 标签可见性。
- `searchIndex(overview, cities, units, decisionMakers)` ⇒ 可搜索项数组。
- `searchMatches(index, query)` ⇒ 匹配项（id / name 精确、前缀、包含的确定性排序）。
- `nationSummaries(regions, cities, units, makers)` ⇒ 三国卡片数据。
- `layerVisibility` / `lodForScale` 一族纯函数；未知值 fail-closed。
- `affiliationKindLabel` 增补 `gov`（仍放 `decisionmodel.js` 更适合；二选一，不重复）。

约束：无 DOM、无 IO、无 fetch；索引与匹配顺序必须确定。

### 3.2 `webui/api.js`

- 新增 `cities(region, target)` / `cachedCities(target)`（URL 带 `region` 时走同一个 target 缓存键）。
- 新增 `regionSummaries(target)` / `cachedRegionSummaries(target)`。
- 新增 `economyOverview(target)` / `cachedEconomyOverview(target)`。
- 现有 `cachedMapOverview` / `cachedUnits` / `cachedCombats` / `cachedDecisionMakers` 不改形状。
- 所有新 URL 必须是同源相对路径；不得出现 `http://` / `https://` / 协议相对 URL。

### 3.3 `webui/renderer.js`

保持现有点选/平移/缩放/编辑行为，不改写命令；新增：

1. `setCities(list)`：把 `/api/social/cities` 的城市存入渲染器；重算 city marker 世界坐标。
2. `setLayerState(layers)`：图层开关；缺省值集中在一处，未知键 fail-closed。
3. `drawCities()` 重写：等级决定半径（MajorCity 星标 / City 圆环 / Town 小圆 / MarketTown 更小点），首都星标优先；按缩放阈值画城市名。
4. `drawUnits()` 增补：根单位 `module.kind === "gov"` 用菱形/GOV 徽标；army 用现有圆标；选中态保持。
5. `pickAt()`：在单位标记之后、hex 之前增加城市标记命中；同一格单位优先于城市（保持现有“点单位”直觉）；返回 `{kind:"city", id, name, q, r, inMap:true}`。
6. `ensureCityVisible(q,r)`：类比现有 `ensureUnitVisible`，供搜索定位。
7. `debug()` 增补 city/layer 计数，供验收截图说明。

LOD 建议（具体阈值以 v17levant full map `fit()` 的实测 scale 校准）：
- 世界级：地形块 + 国家着色/边界 + 区域名（仅国家/大区域）+ 首都 + 军队/GOV 根标记。
- 区域级：加区域名、Town 以上城市、全部军队/GOV、City 以上城市名。
- 近景：全部城市/单位/名称 + 管辖覆盖（选中 GOV 时）。

### 3.4 `webui/map.js`

- `reloadOverview()` 后并行拉 `cachedCities`、`cachedRegionSummaries`、`cachedEconomyOverview`，分别调用渲染器与右栏总览。
- 状态机新增 `selection.kind === "city"` 分支：`workbenchSelect` 收到 city pick ⇒ `app.setSelection({kind:'city', id})`。
- 决策模式 GOV：
  - `decisionScopeLevelLabel` 增补 `gov`（“政府级”），范围高亮仍只信服务端 `visible.hexes`。
  - 选择 GOV 单位时左栏显示其决策人（走 `decisionmodel.decisionMakerForGovUnit`）。
- 搜索：供 `layers.js`/`panels.js` 调用 `locateSearchResult(item)`；城市 ⇒ `ensureCityVisible` + `setSelection`；单位 ⇒ 现有可见性 + `setSelection`。
- 图层开关变化只重绘，不重新拉取全量数据（除非新增图层首次打开）。

### 3.5 `webui/index.html` / `styles.css`

- 顶栏新增「图层」按钮与抽屉（`#layer-panel`，默认 hidden），分组列出 3.3 的开关；开关状态在 `localStorage` 持久化（键名 `simos.layers.v1`，无 localStorage 宿主降级为内存态）。
- 顶栏新增搜索输入 `#search-input` + 结果浮层 `#search-results`；Enter/点击结果定位。
- 右栏在 `view` / `region` 模式下新增「世界总览」容器 `#world-overview-mount`（无选中时显示；有选中时让位给区域/决策详情）。
- `style.css` 沿用现有视觉语言（`--accent`、`panel`、`muted`），不新造主题。
- `map.html` 同步加载新增共享脚本（按依赖顺序），旧页无 `#layer-panel` / `#search-input` 时相关接线不得报错。

### 3.6 `webui/panels.js` / `panel-right.js` / `decisionmodel.js`

- `renderLeft` 增加 `kind === "city"` 分支：城市详情（名称、等级、人口、区域、父国、props、周边单位/GOV）。
- `renderHex`：
  - 城市段：该格社交城市列表（点击可选中城市）。
  - 单位段：单位列表里区分 army / gov。
  - 经济段：`classFirst.available === true` ⇒ 走新的 class-first 投影；旧投影仅在 `classFirst` 缺席时保留。
- `renderUnit`：追加 `module` / `jurisdiction` 分组（GOV：level / superiorGov / staff / policy / jurisdiction；army：masterGov / role）。
- 右栏 `drawWorldOverview`：世界统计 + 三国卡片 + 待批数；数据来自 3.2 的三个缓存端点与 `cachedDecisionMakers`。
- `decisionmodel.js`：`AFFILIATION_LABELS` 增 `gov:"政府"`；`decisionMakerForUnit` 增加 GOV 分支；保留未知 kind 的“其它（kind）”语义。
- `economyReadoutRows` 仍保持**唯一一份实现**（现有 `economy-panel.test.cjs` 的静态断言不动）；函数内部按 `classFirst.available` 分流。class-first 行建议：
  - `世界级阶层池`：4 池人口 / 劳动 / 土地 / 债务合计；hint 写明“世界级、非本格”。
  - `土地市场`：LandForSale / LeaseSupply / 守恒状态。
  - `账户`：debt / claim / net 合计。
  - `本格库存/货币`：继续读 actor 侧 `goods` / `actorMoneyTotal` / `grainStock`。
  - 确实算不出的字段 ⇒ `—（不可得：原因）`，绝不显示 0 冒充。

### 3.7 脚本加载顺序与门禁

浏览器两份宿主：
- `index.html` 顺序：`api.js → modes.js → app.js → blocks.js → readout.js → decisionmodel.js → worldmodel.js → paneldom.js → panel-right.js → panels.js → unitTree.js → timeline.js → hexgeom.js → hexcolor.js → regionShape.js → map.js → map-mapeditor.js → map-regioneditor.js → renderer.js → map-uniteditor.js → map-hostpage.js → notifications.js → gm.js`（新文件在 `panels.js` 前，具体以依赖为准）。
- `map.html` 同步加载 `api.js`、`worldmodel.js`、`app.js` 等它已有的共享资产；不得让新文件先于宿主。
- `simos-app/src/test/js/helpers/webui-loader.cjs` 的 `BUNDLE_DEPS`：若测试以后直接加载 `worldmodel.js`，按“新文件依赖宿主”规则登记；F1 不改测试文件，但代码结构要预留。
- 新 JS 不得含绝对 URL；不得改变 `index.html` 现有六模式按钮的可见形态（`WebuiAssetsTest` 的红线）。

---

## 4. F1 手动验收步骤（用户点网页）

启动命令（控制方在 F1 代码落地后执行）：

```bash
# 服务未跑 Maven 时
./mvnw -DskipTests package -pl simos-app -am
tools/run-shaded.sh simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --store /home/cna/simos-runs/2026-10-01-frontend-f1/store \
  --gui-port 5817 --mcp-port 5715 --approval-port 5713
```

用户按顺序点：

1. 打开 `http://127.0.0.1:5817/`：
   - 世界视图能看见三国色块/国名；legend/状态提示共有 252 区域、201 城市、27 单位。
2. 点「图层」：开关城市 / 军队/GOV / 区域名 / 国家着色，确认改开关后地图即时变化。
3. 搜索 `日耳曼尼亚`：回车后地图居中到首都，城市详情显示 MajorCity / 350,000 人 / 德意志第二帝国。
4. 点首都格：hex 详情出现城市；经济段不再显示“货币 0 / 产业 无”，而是世界级 class-first 读数 + 本格 actor 银 4,382,292。
5. 放大后点任一军队标记：单位详情看到 `module.kind = army`（及 formation 信息）；点 `gov-de`：看到 `module.kind = gov`、level=CENTRAL、jurisdiction=德意志第二帝国。
6. 切「决策」模式：`dm-de-gov` 分组显示「政府」而不是「其它（gov）」；选中它能看到可见范围高亮与详情。
7. 右上角「世界总览」：三国卡片数字与搜索/详情一致（人口、城市、单位、GOV、决策人计数）。
8. 打开 `http://127.0.0.1:5817/map`：旧页地图仍能渲染、缩放、点选，无 JS 报错。

验收判据：以上 8 条全部由用户点过；任一不通过，F1 不关账，不进入 F2。

---

## 5. F2 预告（不在本阶段开工）

- `GET /api/map/population` 紧凑逐格人口（农村/城市/合计）。
- 地图热力层：人口、粮食库存/缺口（逐格事实）、class-first 派生指标；带图例、色阶、口径说明，缺数据格不参与着色。
- 区域/国家详情页补齐：人口结构、城市列表、单位列表、GOV/决策人列表、class-first 世界面板。
- 热力图数据量大时按屏幕范围/缩放分级取数；不许一次把 84 KB/格的经济响应拉全图。
- F2 开工前另写一段设计增补，由用户确认指标口径后再派代码代理。

---

## 6. 测试与门禁（按仓规最后统一补）

- F1 生产代码阶段：写代码代理只写到 `./mvnw -q -DskipTests compile -pl simos-app -am` 通过；不写/改测试，不跑 `test`/`verify`，不 commit。
- F1 关账前由测试代理统一补：
  - `worldmodel.js` 纯函数（城市 LOD、搜索排序、国家汇总、图层 fail-closed）。
  - `api.js` 新端点的缓存键与 URL（不重复请求）。
  - `panels.js` class-first / 旧经济两条分支的逐值投影。
  - `decisionmodel.js` 的 gov 标签/匹配。
  - 后端 `ApiViews` 新视图的确定性排序、空城市、空区域、GOV 计数。
  - `run-gate.cjs` 的 `MIN_TESTS` 与 `gate-contract.test.cjs` 的 `MIN_ASSERTIONS` 同步提高；新 `.test.cjs` 进 `REQUIRED_FILES`。
- 关键项变异自证仍只做四类：守恒式 · 不丢失（身份/operator）· 静默付 0 · 断粮。
- 全仓门禁：`./mvnw clean verify`（前台跑；无服务在跑）。
- 报告必须写“没做/没验证的”。

---

## 7. 备份与测试世界（已完成）

- 备份目录：`/home/cna/simos-runs/2026-10-01-v17levant-backup/`
  - `worlds-v17levant.json`（4,219,786 B，md5 `9e7b0a8d5157a29c1a86c6d6e4de5e53`）
  - `2026-09-30-v17-classfirst/`（现有实际测试世界整目录 2.3 GB；含 `store/simos.db` md5 `6096356dcd22ca3fabf0cfca3a751196`）
  - `v17levant-nations.json`（三国生成器冻结输入）
  - `MANIFEST.md`（来源 / 大小 / md5）
- F1 验收库：`/home/cna/simos-runs/2026-10-01-frontend-f1/store`（一次性库；可随时删除重建）。
- 仓内引导档 `simos-app/src/main/resources/worlds/v17levant.json` 备份后保持原样，不修改。

---

## 8. 非目标

- 不做新前端框架、npm、打包器、CDN。
- 不绕过 `Command → ChangeSet → Revision` 新增写入口。
- 不在 F1 做 GM 工具 UI（region.seed / clear / province.* / spawnArmy / createOffice / run-decision-makers）。
- 不自行发明 class-first 的逐格生产量；经济显示要么是世界级权威读数，要么是真实存在的逐格事实。
