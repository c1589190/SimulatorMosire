# 区域可见性修复 — 实现台账

> **状态**：生产 Agent 已交回，编译通过；保存前前端门禁493/497、4项失败，尚未验收。按用户要求暂停并保存，详情见[保存检查](2026-11-15-progress-save-checks.md)。
> **父设计**：[国家与区域显示接线修复](../specs/2026-11-15-region-visibility-repair.md) §1 阶段1–6、§3、§4。
> **责任区**：app 前端接线（map/renderer/worldmodel/panel-right/index）；唯一生产写者。
> **日期**：2026-11-15。

## 1. 父阶段与责任区

| 父设计阶段 | 本责任区落点 | 实际结果 |
|---|---|---|
| §1 阶段1 读冻结 target、只读区域/国家 | map.js 既有 `targetKey` / `loadOverview` / gov `/editor/nation` 读桥；无新写命令 | 保留；未改世界/Revision/存档 |
| §1 阶段2 常规/地图编辑可见区域列表；国家疆域边界；普通区域边界 | index.html 可见性；renderer 国家描边 + 独立基底边界 | 完成 |
| §1 阶段3 按 target 缓存、限并发、token；只读 region 接口；不逐帧 fetch | map.js `reloadRegionBaseline` / `fetchRegionCached` | 完成 |
| §1 阶段4 名称四公开模式可读；大范围国名/近景区域名；去重与最小字号 | map.js `REGION_NAME_MODES`；renderer `paintRegionNames` / `paintNationNames`；worldmodel `nationNamesVisible` | 完成 |
| §1 阶段5 列表点击定位+高亮稳定 regionId；不进入隐藏 region；清高亮不清基底 | panel-right 点击；map.js `locateRegionById`；renderer 双 overlay | 完成 |
| §1 阶段6 图层/预设显式开关；旧 /map 接线可用 | worldmodel 10 键；index 抽屉；map.js 旧页守卫 | 完成；旧 /map 行为保持 |
| §1 阶段7 宿主与旧页共享纯函数 | 名称/图层/定位均走既有跨页实现 | 未复制写状态机 |

## 2. 实际行为

1. **区域列表**：`index.html` 的 `#right-panel` 增加 `map-edit`；`#region-panel-mount` 祖先改为
   `data-modes="view map-edit region region-edit"`。常规与地图编辑都能看到既有区域分组/列表，未新增模式。
2. **国家疆域**：仍由 `reloadNationFills` 调 `GET /api/editor/nation`（gov 国家并集视图），
   每个国家一项，名称取 `Nation.name`；`renderer.paintNationFills` 保留 0.32 填充，
   新增 `paintNationOutlines` 按 `regionBoundaryRings` 精确环描“深色外描边 + 国家色内描边”。
   受 `layerState.nation` 控制。
3. **普通区域基底边界**：新增 `regions` 图层键（默认开），map.js 从既有 overview 的 `regions[]`
   逐个走现有 `api.mapRegion` 读几何（只读），renderer 新增独立 `regionBaselineOutlines` overlay，
   精确环描边；与选中高亮的 `regionOutlines` 分开存储/绘制。清高亮只清后者，基底边界不消失。
4. **名称**：`REGION_NAME_MODES` 改为 `view/map-edit/unit/battle/region/region-edit`；
   `nationNamesVisible` 从“仅 world LOD”改为“所有 ≥ 0.03 的可读缩放”；
   `paintNationNames` 去掉 `mode === "view"` 限制，国名在四个公开模式都按图层显示；
   区域名仍在近景 `scale >= 0.25` 显示。国名最小字号仍由 `nationLabelPlan` 的
   `max(8, min(40, √hexCount×1.8)) / scale` 保证屏幕字号 ≥ 8px。
5. **重名/同点**：区域名计划会剔除 `meta.tag=nation:*` 且名称与国名相同的区域名；
   国家名与普通区域名同点但不同名时，区域名纵向下移一行。既有视口裁剪/同点错位逻辑保留。
6. **列表点击**：`panel-right` 点击区域项后，既有 `setRegionTag` + `setHighlightRegions(...,"single")`
   照旧；新增 `SimosMap.locateRegionById(id, region.label)` 复用 `ensureCityVisible` 居中/抬缩放。
   不调用 `setMode("region")`；搜索定位的区域分支也不再切入隐藏 `region` 模式。
7. **手势**：未改 renderer 指针/右键分派；unit 移动、地形绘制、套索/边界点逻辑未触碰。
8. **旧 /map**：`refreshRegionHighlight`/名称/定位函数仍在共享资产中；基底边界取数受
   `host.isWorkbench` 守卫，旧页不会新增批量区域请求，也不会崩。

## 3. 新增/变更 API（前端模块内）

| 模块 | API | 说明 |
|---|---|---|
| `SimosWorldModel` | `LAYER_KEYS` + `regions`；`DEFAULT_LAYERS.regions=true` | 旧 9 键缺 `regions` 时由 `layerVisibility` 补默认 true |
| `SimosWorldModel` | `layerPreset("all"/"terrain"/"military"/"government"/"cities")` | all/terrain 开 regions；military/government 显式 `regions:false` |
| `SimosWorldModel` | `nationNamesVisible(scale)` | 语义改为所有 ≥ `NATION_LABEL_MIN_SCALE` 的可读缩放 |
| `SimosMap` | `locateRegionById(id, fallbackLabel)` | 稳定 regionId 定位；不切模式 |
| `SimosMap` | `reloadRegionBaseline()` | 基底边界重取（图层重开/调试用） |
| `renderer` | `setRegionBaselineOutlines(list)` | 独立基底边界 overlay；精确环只算一次 |
| `renderer.debug()` | `nationOutlineDraws` / `nationOutlineVertexCount` / `regionBaselineOutlineCount` / `regionBaselineVertexCount` / `regionBaselineDraws` | 真实绘制/几何计数，未硬填 |
| `SimosMap.worldViewDebug()` | 同名转出 + `layerState` | 供验收/门禁断言 |

## 4. 缓存与失效

| 对象 | 键/机制 | 失效 |
|---|---|---|
| overview / 区域列表 | `api.cachedMapOverview` 按完整 URL（含 branch/revision/writeEpoch） | target 变即换键 |
| 区域详情几何 | `host.regionCache[id + "@" + targetKey]` | 切 target：`host.regionCache = {}` |
| 同区在途请求 | `host.regionInflight` 同键合流 | 请求 finally 删除；切 target 重置 |
| 基底边界轮次 | `host.regionBaselineToken` + `targetKey()` | 切目标立即 `clearRegionBaseline`；关图层立即清；迟到结果双校验丢弃 |
| 基底 concurrency | `REGION_BASELINE_CONCURRENCY = 4` | 固定上限，不随区域数增长 |
| 图层偏好 | `localStorage simos.layers.v2` | 旧 9 键保留；新 `regions` 缺省 true；删除/坏 JSON 走内存态 |

## 5. 文件

- `simos-app/src/main/resources/webui/index.html`
- `simos-app/src/main/resources/webui/worldmodel.js`
- `simos-app/src/main/resources/webui/renderer.js`
- `simos-app/src/main/resources/webui/map.js`
- `simos-app/src/main/resources/webui/panel-right.js`

未改 Java/后端/schema/工具/测试/存档；未新增写命令或世界数据。工作树中其它既有 dirty 全部保留。

## 6. 编译

| 命令 | rc | 备注 |
|---|---|---|
| `node --check worldmodel.js` | 0 | 语法 |
| `node --check renderer.js` | 0 | 语法 |
| `node --check map.js` | 0 | 语法 |
| `node --check panel-right.js` | 0 | 语法 |
| `tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am` | 0 | 编译通过 |

未执行 `test` / `verify` / `package` / JS gate / 浏览器验收。

## 7. 对父序列影响表

| 维度 | 影响 |
|---|---|
| 顺序 | 基底边界在 overview 载入后启动；与单位/世界层取数并行，不阻塞主渲染链 |
| 预算/冻结 | 只有读请求；每 target 最多 `4` 个区域详情并发；无每帧请求 |
| 身份/持久化 | 国家仍用 gov 并集 + `Nation.name`；区域用既有稳定 `regionId`；仅前端 localStorage 增 `regions` 键 |
| 失败/回滚 | 单区域失败折空 hex 并跳过该边界；国家读桥失败清国家着色；迟到结果按 token/target 丢弃 |
| 日志 | 无新增日志通道；沿用既有 `console.warn` 失败提示 |
| 测试 | 语义变化需测试 Owner 更新旧断言（区域名模式、国家名 LOD、9→10 键）并补新正负用例；本轮未跑 |

## 8. 未验证 / 风险

- **NOT_RUN**：真实 Chromium 截图、可见 DOM、请求数、世界 head 不变证据。
- **NOT_RUN**：新增 JS 正负用例与现有精确图层/资产门禁；旧 `webui-fix2`/`f1-map-first` 中
  “区域名只限两模式”“国名仅 world LOD”“9 键”等断言与新设计冲突，预期由测试 Owner 统一更新。
- **NOT_RUN**：旧 9 键 localStorage 的真实浏览器迁移路径（静态推理：缺键默认 true，其余键保留）。
- **风险**：大世界首次打开 `regions` 层会按区域数逐个读几何；已限 4 并发并按 target 缓存，
  但真实大图请求数/耗时未实测。
- **风险**：国名改为近景仍显示，可能与区域名/城市名在密集区重叠；已做同点错位与国名/所属区域重名去重，
  未做全量标签碰撞消解（沿用既有区域名视口裁剪，未扩大范围）。
- **未做**：区域套索编辑手势迁移、整体 UI 重设计、真实四国资料、经济/AI、提交推送；均非本责任区。

## 9. 控制方保存检查（2026-11-15）

上述 NOT_RUN 为生产 Agent 交回时的记录。控制方随后运行既有前端门禁：497 tests / 493 pass / 4 fail / 0 skipped；尚未更新测试，也未完成统一验收或真实浏览器检查。文档/Skill 检查通过。用户要求停止推进并提交/推送当前版本，源码与本台账作为待验收进度保存；不代表区域修复交付。[保存检查](2026-11-15-progress-save-checks.md)。
