# 调查：节点版本、世界文档系统、2+1 模式、经济卸载与 PNG 底图（只读）

> **状态**：调查事实（2026-11-13 补查）· 不是设计、不是计划
> **补裁设计**：`docs/superpowers/specs/2026-11-13-node-version-world-docs-and-asset-pipeline-design.md`（D-035）
> **前一报告**：`docs/superpowers/reports/2026-11-13-timeline-node-editor-current-state.md`
> **基线**：`main` @ `4f2552ce`；工作树仅原有 `.opencode/` 未跟踪
> **边界**：只读调查；未改业务代码、未执行清理、未跑 Maven。

---

## 0. 结论摘要

1. **“tick + rev”节点身份现在没有现成字段**：`revisions` 的 `revision` 是**分支内全局序号**，不是“每个 tick 内的版本号”；同一 tick 多版本的“rev1/rev2”需要新字段或新表。前端目前只是按 tick 分组，并取 `lastRevision`。
2. **“中间 revision 直接打死”不能只 DELETE**：`revisions` 有自引用外键，且 `CombatRecord.atRevision`、`DecisionInfoEntry.at`、`Verdict.atRevision` 等持久记录引用 revision；需要“节点表 + 节点增量”或“压缩后重指父链 + 重映射引用”，并处理旧档。
3. **任意节点分叉是低风险改动**：`Timeline.fork` 当前只查 head；`Replay` 已支持任意父节点，读任意 revision 也已支持。把 fork 的 head 检查改成“父行存在”即可，不涉及存储重写。
4. **世界文档系统已有一块很接近的底座**：`docs-mosire` 提供 LOG（地址 + tick + ordinal 多版本 + 可见性 + 查询）和 FILE（md 原子创建/编辑）；但它**没有删除、没有回收站、没有部分地址查询、没有名称别名索引**，地址也只是不透明字符串，不解析节点/模块语义。
5. **2+1 模式需要模块前端机制**：当前所有 webui 资源都在 `simos-app/src/main/resources/webui`，`StaticHandler` 只从 `/webui/` 单根取；模块没有自带前端资源的注册/加载机制。区域编辑/战役前端“下放模块”目前没有承载结构。
6. **经济卸载是代码手术，不是开关**：app 里 economy 引用 95 个文件、production 35、culture 4、social 82、actor 56、ga 70；unit/army 的复合工具也直接 import `SocialData`；gov 的工具也依赖 social。只删 EconomyCodec 会编译不过/运行时空转。
7. **PNG 底图的前端几何已具备**：`hexgeom.js` 有 `hexToPixel` / `pixelToHex`，renderer 有 canvas `drawImage`/`getImageData`，`TerrainCatalog` 有 7 种地形颜色。缺的是图片载入/对齐 UI、颜色→地形分类、批量应用与持久化。
8. **老文件清理有明确的安全分层**：`.superpowers` 9.6GB 中约 8.4GB 是 ignored 日志（可 `git clean -X` 定向删）；worktrees 40GB 里有 9 条**本地未推送**经济分支和 7 个脏树，不能无脑删；`test-world`/`target`/旧 store 可重建。详见 §6。

---

## 1. 节点版本（tick + rev）现状

### 1.1 现有 revision 编号是“分支内全局序号”

`revisions` 主键 `(branch, revision)`，`revision` 由每个分支从 1 开始递增（`SqliteStore.java:42-56`、`Timeline.java:61-63`）。它**不是**“每个 tick 内的版本号”。

实测 `main` 在 tick0 可以有多条 revision：

```text
main rev1 tick0 core.Bootstrap
main rev2 tick0 map.SetTerrain
main rev3 tick0 map.SetTerrain
```

如果按用户写法 `0tick-rev1` / `0tick-rev2` 指“tick0 的第 1/2 个版本”，当前没有这个 `rev` 字段；只能由前端按 tick 分组后算“组内序号”。前端已有分组：

- `timeline.js:142-175` 的 `groupByTick` 记录 `firstRevision` / `lastRevision`；
- 节点显示与游标落在该 tick 的最后一个 revision（`timeline.js:13`）。

**可选实现路径（仅调查事实，不是计划）**：

- A. 新增 `node` 表：`(branch, tick, version, revision)`，version 是 tick 内序号；地址 `0tick-rev1` 查表。
- B. 复用 revision：把 `rev` 解释为分支内全局 revision（则 `0tick-rev1` 只在 tick0 成立，tick360 的版本号是 361+），需要在地址协议里说明。
- C. 前端/读口派生：`(branch, tick)` 分组，组内按 revision 升序编号；无持久化 node 表，压缩/删除后编号可能漂移。

### 1.2 中间 revision 删除的硬约束

- `revisions` 自引用外键：`FOREIGN KEY (parent_branch, parent_revision) REFERENCES revisions (branch, revision)`，没有 `ON DELETE`（`SqliteStore.java:42-56`）。删除中间行会违反外键，除非先重指子行父链。
- 没有删除/压缩 API：`Timeline`/`SqliteStore` 只有 `appendRevision`，没有 `delete/remove/compact/prune`（grep 0 命中）。
- **持久记录引用 revision**：
  - `CombatRecord.atRevision`（`CombatRecord.java:75`）——战斗记录“这一份内容落在哪次 revision”；
  - `DecisionInfoEntry.at`（`DecisionInfoEntry.java:63`）——ga INFO/决策文档；
  - `Verdict.atRevision`（`simos-ga/.../Verdict.java:19`）——判决；
  - 可能还有旧记录/工具层引用（需要逐项核）。
  删除中间 revision 后，这些引用会指向不存在的 revision；若保留“节点 revision”，至少要把引用重映射到节点，或改为节点地址。
- `Replay` 从最近 checkpoint 沿父链施加 changeset（`Replay.java:31`、`:139`）；如果压缩后父链只保留节点 revision，则每个节点 revision 的 `changeset_json` 必须是**相对上一节点的增量**（不是逐日增量）。

**结论**：要满足 D-035“中间 revision 打死”，需要先定义节点表/节点增量/引用重映射；单纯 DELETE 会破外键和持久引用。

---

## 2. 任意节点分叉现状

- `Timeline.fork` 当前先查 `head(source)`，要求 `expectedRevision == head`（`Timeline.java:219-223`）。
- 但 `Replay` 支持任意父节点：分叉行只需要 `parent = (source, expected)`；`hasCheckpoint` 对分叉父强制 checkpoint（`Timeline.java:41-44`、`:356-364`）。
- 读任意 revision 已支持：`QueryService.stateAt` 接受 `QueryTarget(branch, revision)`（`QueryService.java:96-125`）。
- 实测：从 head 分叉 `tree2` 并独立推进成功；从历史 revision 分叉 409（见前一报告 §2.2）。

**结论**：把 `Timeline.fork` 的“必须 head”改成“父行必须存在”即可支持任意节点分叉；风险主要在 UI（时间轴只有 head 分叉按钮）与权限/校验，而不在存储层。

---

## 3. 世界文档系统现状

### 3.1 已经存在的 `docs-mosire` 库

本地依赖：`docs-mosire 0.2.3-SNAPSHOT`（`.m2-repo/io/mosire/docs-mosire/...-sources.jar`）。核心能力：

| 能力 | 现状 | 与用户要求的差距 |
|---|---|---|
| 地址 | `DocAddress.require`：非空、无空白、无 `#`、无 `//`、≤200 字符；**不解析语义** | 需要接 `tree/0tick-rev1/...` 节点地址与模块地址 |
| LOG 纪律 | 每地址一个 NDJSON 日志；条目不可变；`ordinal` 版本序号；`append` / `void`；查询按 `tick`/区间/limit；可见性三轴 | **没有删除/回收站**；查询只按 tick，不按地址/部分地址/名称 |
| FILE 纪律 | md 文件原子 `create` / `edit`（行寻址/锚点）；**没有删除**（类注明写） | 需要删除→回收站、编辑→备份旧档、版本链 |
| 多版本 | LOG 的 `ordinal` 天然多版本；`void` 只作废不删除 | md 文件没有“多版本”目录/备份；一个地址多版本/多名称无协议 |
| 名称/别名 | `DocEntry.key` / `fields` 可放自定义字段，但没有索引/查询 | 需要模块名称/别名索引与“按名称查地址” |
| 可见性 | `owner/tags/affiliations` 与 `DocVisibility` | 世界文档是否需要按决策人/玩家可见需另定 |
| 挂载 | `DocsEngine` 依赖 `workspace-mosire` 的 `Mount`（Kind.FILE/LOG），物理根由 workspace registry 解析 | 当前 app 的 docs 根在 `<store>/workspaces`（`Shell.java:318-324`、`:1206-1210`），不是仓库 `docs/` |
| 工具 | `doc_list/read/query/append/void/create/edit/guide`（`SimosToolSource.java:591-600`） | 缺“删除/回收/备份/按名称查/按部分地址查”工具 |

**可直接复用的部分**：地址校验、LOG 多版本/可见性/按 tick 查询、FILE 原子读写、workspace 权限挂载、`DocEntry.fields` 自定义元数据。

**必须新增的部分**：

1. 删除 → 回收站目录 + 删除日期；
2. 编辑 → 旧档备份（版本目录或 LOG 条目）+ 新档；
3. 部分地址查询（前缀/段匹配）；
4. 名称/别名索引（模块提供“名称 → 地址”）；
5. 一个地址多版本/多名称的统一协议；
6. 世界文档根（仓库 `docs/` vs store）与地址→路径映射；
7. 与节点版本（tick+rev）的关联（文档属于稳定实体地址还是某节点版本？）。

### 3.2 现有另外两套“文档/信息”机制

- **决策文档**：`DecisionDoc` 用 `ga:doc.<docId>` 地址，正文存 `GaState.info`（`DecisionDoc.java:35`、`PutInfoHandler`）；读口 `/api/ga/decision-docs`。这是 ga 层的决策文档，不是世界文档系统。
- **InfoSystem**：`SimulationState.info()` 是“地址 → 时态 InfoEntry”的通用外挂层（`InfoSystem.java`、`InMemoryInfoSystem`），但 main 侧几乎没有写路径（`RichWorld` 只读资源里的 info），不是现成文档系统。

### 3.3 “模块名称 → 地址”的现状

- Resolver 基本按 ID 解析：`map:<mapId>:region.<id>`、`unit:<id>`、`army:army.<id>`、`army:combat.<id>`、`ga:decision-maker.<id>`（`MapResolver.java:133-167`、`UnitResolver.java:29-30`、`ArmyResolver.java:111-147`）。
- 只有 `UnitResolver` 支持链式名称定位（`unit:"链"`），且 canonical 回 ID（`UnitResolver.java:29-30`）。
- 区域名、国家名、城市名**没有**统一的“名称 → 地址”解析；GUI 搜索是前端对 overview/cities/units 做名称匹配（`worldmodel.js:364-540`），不是后端协议。
- 用户例子“gov 里一个 hex 可以有首都/首府属性，查 xxx 政府的首都链接到这个 hex 原始地址”：当前 `Nation` 只有 `homeRegion`（`Nation.java`），首都由 `homeRegion` + 中央 GOV 位置/城市等派生（`MoveCapitalPlan`、`ProvinceApplyTool`），**没有**“模块给 hex 挂名称/属性”的索引。需要新增模块级名称/属性索引。

---

## 4. 2+1 模式与模块前端下放

### 4.1 当前前端结构

- 所有 webui 资源都在 `simos-app/src/main/resources/webui`（20,646 行 JS/CSS/HTML），其他模块 `src/main/resources` 下没有 webui 资产（`find simos-* ...` 实测只有 simos-app 有）。
- `StaticHandler` 只从 classpath `/webui/` 单根取资源（`StaticHandler.java:22`、`:44`），页面别名硬编码（`/`、`/map`、`/unit`、`/social`，`:25`）。
- `modes.js:21-89` 硬编码 6 模式与写白名单；`index.html` 硬编码脚本加载顺序（`index.html` 末尾）。
- 前端门禁 `webui-loader.cjs` 的 `BUNDLE_DEPS` 也硬编码依赖顺序。

**结论**：模块“自带前端/下放区域编辑、战役前端”目前没有承载结构。需要至少：

1. 模块资源目录约定（如 `simos-army/src/main/resources/webui-modules/army/...`）；
2. 服务端多 classpath 资源解析（`ClassLoader.getResources` 聚合，或 app 显式清单）；
3. 前端模块注册/加载清单（脚本、样式、模式/面板、权限）；
4. 前端门禁对模块资产的依赖检查。

### 4.2 “2+1”的读法

用户原话：“改成2+1大模式，后续不排除多加，其中对于战役系统的维护前端、区域编辑前端可以下放到相关模块”。

按上下文最可能的读法：

- **2 个核心大模式**：地图编辑、单位编辑；
- **+1**：战役编辑（前端由 `simos-army` 模块提供）；
- **区域编辑**并入地图编辑（前端可由 `simos-map` 模块提供）；
- 后续可加更多模块前端。

这与 D-034 的三模式实质接近，只是把“战役”作为模块扩展位，而不是 app 内固定模式。**需要用户确认**（见 §8）。

---

## 5. 经济及相关模块卸载的代码面

### 5.1 引用规模（app main）

| 模块 | app 中引用文件数 |
|---|---:|
| map | 103 |
| economy | 95 |
| social | 82 |
| ga | 70 |
| unit | 64 |
| actor | 56 |
| gov | 35 |
| production | 35 |
| army | 18 |
| calendar | 14 |
| culture | 4 |

### 5.2 必须拆开的强耦合

1. **时间参与者合并**：`PopulationUnitTimeParticipant` 把 `PopulationEconomyTimeParticipant`（2266 行，构造 `EconomyDayStepper`/`ProductionRuntimeEngine`）与 `UnitTimeParticipant` 合并（类注 `:39-42`）。经济卸载必须换成 unit-only 参与者（或加关闭分支），否则 unit 移动仍会驱动经济。
2. **Shell 装配**：10 codec（`Shell.java:593-619`）、149 handler（其中 `Economy*` 38）、7 resolver（`:1223-1236`）、2 facet（`:1239-1240`）、3 participant（`:1158-1167`）。
3. **工具面**：`SimosToolSource` 单类 2145 行，MCP 182 条；economy 14、social 9、gov 23、ga/gm 42。需要按模块拆分/过滤，而不是只删 import。
4. **GUI**：4 条 economy 只读端点（`GuiServer.java:286-316`）、economy/heatmap 面板（`panels.js` 41 处 economy 命中）、决策面板。
5. **世界生成**：`RichWorld`/`SmallWorld`/`CorridorWorld`/`ThreePowersWorld` 都补 economy/actor/gov/ga/production/culture 空切片并 seed 经济（`RichWorld.java:99-140`、`SmallWorld.java:391-405`、`CorridorWorld.java:100-114`）。
6. **unit/army/gov 的工具依赖**：
   - `simos-unit` 有 7 个 tool 文件直接 import `io.mosire.simos.social.*`（RaiseUnit/Levy/SpawnArmy/UnitToolReads 等）；
   - `simos-army` 有 3 个 tool 文件直接 import social（ResolveCombatPlan/ArmyManpowerPort/ArmyToolReads）；
   - `simos-gov` 有十余个 tool 文件 import social（GovRecruit/GovAbsorb/GovAssignPosts/ProvinceAssignCities 等）。
   卸载 social 必须先删/改这些组合工具，或把它们留在“暂停模块”里不装配。

### 5.3 最小保留候选（按用户范围）

用户点名保留：地图、区域、单位、军队、政府、app、各模块 GUI、统一 GUI、时间线推进。

按依赖静态核对，最小 reactor 候选为：

```text
simos-util
simos-map
simos-social-api（契约：HouseholdId 等）
simos-actor-api（契约：ActorRef 等）
simos-economy-api（契约：CommodityId/CurrencyId 等，gov 用）
simos-ga-spi（工具契约）
simos-unit
simos-army
simos-gov（若只保留政府单位，可进一步裁掉 offices/外交/工具）
simos-calendar
simos-core
simos-app（瘦身）
```

**待确认**：

- `simos-gov` 是否保留完整（nation/外交/offices）还是只保留政府单位（`GovernmentFormation` 在 unit 模块，gov 模块可整个下线）；
- `social`/`actor`/`ga` 是否随经济一起下线（用户范围未点名它们）；
- `calendar` 是否保留（时间线日期需要）；
- `docs-mosire`/`workspace-mosire` 是否保留（世界文档系统要复用）。

---

## 6. 老文件清理清单（未执行）

### 6.1 现状

- 仓库总 **53GB**；`worktree` **40GB**、`.superpowers` **9.6GB**、`test-world` **1.7GB**、`.git` **966MB**、`.m2-repo` **779MB**。
- `.superpowers` 9.6GB 中：
  - **约 1.3GB 是 tracked**（`git ls-files .superpowers` 5,451 个文件）；
  - **约 8.4GB 是 ignored**（`git clean -ndX .superpowers` 251 条，主要是大日志/证据）。
- `worktree` 40GB：每个树都有独立 `.m2-repo`（~0.78GB）与 `.superpowers`/`test-world`；最大 `parallel-side-module` 约 14GB，`economy-integration` 约 8.3GB。
- `test-world` 1.7GB：`multitick` 1.5GB（可重建）、`runtime` 141MB（旧 jar）、`store` 22MB（HEAD 打不开，旧 jar 可读）。
- 当前工作树有 **16 个**（旧 STATUS 只列 8 个）；**9 条经济分支全是本地分支、无远端对应**；7 个 worktree 有未提交改动（economy-test1/2/3、d031-p1-baseline、ga-opt、agentlib-dock、parallel-side-module）。

### 6.2 分层清理候选

| 层 | 内容 | 体积 | 风险 | 建议 |
|---|---|---:|---|---|
| A. 可重建 | 各 `target/` | ~1.4GB | 无（重编即可） | 可删 |
| A. 可重建 | `test-world/multitick/` | ~1.5GB | 无（README 明说可 `rm -rf` 回收） | 可删 |
| A. 可重建 | `test-world/runtime/` 旧 jar（保留最新一件） | ~141MB | 无 | 可删旧件 |
| A. 可重建 | `run/small-world-db/`（HEAD 打不开） | 7MB | 丢失旧小世界演示库 | 可删（或先导出） |
| B. ignored 证据 | `.superpowers` 中 ignored 的日志/证据 | ~8.4GB | 丢失本地证据；docs 可能引用路径 | 可删，建议先保留 `.md` 报告 |
| C. worktree | 15 个 worktree 的 `.m2-repo`/`.superpowers`/`test-world`/`target` | 约 30GB+ | 删除整树会丢未提交改动 | 先归档 dirty patch/关键 md，再 `git worktree remove` |
| D. tracked 证据 | `.superpowers` 中 1.3GB tracked 文件 | 1.3GB 工作树 | 删了要提交；`.git` 历史仍保留 | 可移除 + 加 ignore + 提交，但不缩小 `.git` |
| E. 本地分支 | 9 条经济分支 + 3 个脏 test 树 | 小（对象已在 .git） | **未推送，删除不可恢复** | 不建议删；可归档 patch 后删除 |
| F. 历史 | `.git` 966MB（pack 887MB） | 966MB | 需要 history rewrite 才能缩小 | 暂不动 |

**安全结论**：

- 可立即安全释放的只有 A 层（约 3GB）与 B 层（约 8.4GB，若接受证据丢失）。
- C 层（worktree 30GB+）是最大头，但必须先处理 9 条本地分支和 7 个脏树；**不能直接 `rm -rf worktree`**。
- `.superpowers` tracked 1.3GB 删除不会缩小 `.git`；要缩小 `.git` 只能历史重写（高风险，需用户明确授权）。

**本轮未删除任何文件**；等待用户对 A/B/C/D/E 层给出“删/留”口令。

---

## 7. PNG 底图与地形提取现状

### 7.1 已具备

- **六角投影**：`hexgeom.js:31-38` `hexToPixel` / `hexCorners`，`:65-70` `pixelToHex`；服务端 `MapProjection.java` 有同型公式。
- **画布**：`renderer.js:110` 创建 2D context；`:1987` 已有 `drawImage`（地形缓存 blit）；`:3029` 有 `getImageData` 取色钩子。
- **地形颜色**：`TerrainCatalog.java:45-71` 7 种地形带 `#RRGGBB`；前端也能从 `/api/map/overview` 的 `terrainTypes[].color` 拿到。
- **批量写地形**：`map.SetTerrain` 载荷 `{hexes:[{q,r}…], terrain}`，一条命令可写多格（`SetTerrainHandler.java:24-66`）。
- **涂地形写高度**：`TerrainHeights.paintHeight` 按地形带中点写 height（沙漠特殊 +0.005）。

### 7.2 缺什么

1. **图片载入/对齐 UI**：没有 PNG 选择、底图显示、参考 hex 对齐、offset/scale 控件。
2. **颜色→地形分类**：`TerrainClassifier` 是**高度+湿度**分类，不是颜色分类；没有“最近色/容差/自定义调色板”实现。
3. **采样策略**：没有“hex 中心取色/多点平均/排除描边”的实现。
4. **应用与预览**：没有“提取结果预览 → 确认 → 按地形分组批量 `map.SetTerrain`”的流程。
5. **持久化**：底图是否随节点/地址保存、是否只作编辑期临时视图，未定。
6. **服务端反投影**：`MapProjection` 只有正向；前端 `pixelToHex` 已有，若服务端也要采样需补 inverse。

**结论**：PNG 底图主要是**前端功能**（文件读取 + canvas + `pixelToHex` + 颜色分类），后端只需复用 `map.SetTerrain`；但持久化与权限/大图上传需要设计。

---

## 8. 需要用户确认的点

1. **2+1 的精确拆分**：是否按“地图 + 单位”两个核心模式、“战役”为 +1（区域编辑并入地图编辑、战役前端下放 army）？
2. **暂停范围**：social / actor / ga 是否随 economy/production/culture 一起下线？`gov` 保留完整还是只保留政府单位？
3. **`rev` 的定义**：`0tick-rev1` 的 `rev` 是 tick 内版本序号（需要新字段/表），还是分支内全局 revision 号？
4. **中间 revision 打死后的引用**：`CombatRecord.atRevision`、`DecisionInfoEntry.at`、`Verdict.atRevision` 是重映射到节点、还是改为节点地址/时间戳？
5. **世界文档根**：仓库 `docs/` 还是运行时 store 的 docs 根？回收站目录名/保留期限？
6. **文档协议**：版本/名称/删除日期放 frontmatter、sidecar、索引文件还是 LOG 条目？是否复用 `docs-mosire` 的 LOG+FILE 双纪律？
7. **清理授权**：A/B/C/D/E 哪几层可以删？C 层 9 条本地分支是否允许先归档 patch 再删？
8. **PNG 底图**：对齐方式（参考点数量/手填 offset）、颜色分类（最近色/容差/调色板）、底图是否持久化。

---

## 9. 未验证 / 未做

1. 没有修改代码、没有执行清理、没有跑 Maven。
2. 没有验证“删除中间 revision 后 Replay 仍可回放”的具体 schema；只做了静态约束分析。
3. 没有验证 `docs-mosire` 挂载到仓库 `docs/` 的权限/可见性行为；只读了库与 app 现有接线。
4. 没有验证模块前端资源在 shaded jar 中的 classpath 聚合方式；只确认当前 `StaticHandler` 单根。
5. 没有验证 PNG 颜色分类在真实图片上的效果；没有实现采样/预览。
6. 没有盘点全部 `RevisionId` 持久引用（只列了主要三处）；实现前需逐项 grep 并制定重映射表。

---

## 10. 关键代码位置

| 主题 | 位置 |
|---|---|
| revisions 主键/外键 | `simos-core/src/main/java/io/mosire/simos/core/store/SqliteStore.java:42-56` |
| 无删除/压缩 API | `simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java`（grep delete/remove/compact 0） |
| fork 只从 head | `simos-core/src/main/java/io/mosire/simos/core/timeline/Timeline.java:219-223` |
| 任意 revision 读 | `simos-app/src/main/java/io/mosire/simos/app/query/QueryService.java:96-125` |
| tick 分组/版本 | `simos-app/src/main/resources/webui/timeline.js:142-175` |
| CombatRecord.atRevision | `simos-army/src/main/java/io/mosire/simos/army/CombatRecord.java:75` |
| DecisionInfoEntry.at | `simos-ga/src/main/java/io/mosire/simos/ga/model/DecisionInfoEntry.java:63` |
| Verdict.atRevision | `simos-ga/src/main/java/io/mosire/simos/ga/model/Verdict.java:19` |
| docs-mosire LOG/FILE | `.m2-repo/io/mosire/docs-mosire/0.2.3-SNAPSHOT/docs-mosire-0.2.3-SNAPSHOT-sources.jar` 中 `DocStore.java` / `DocFileStore.java` / `DocEntry.java` / `QuerySpec.java` / `DocAddress.java` |
| docs 工具注册 | `simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java:591-600` |
| docs 工作区根 | `simos-app/src/main/java/io/mosire/simos/app/Shell.java:318-324`、`:1206-1210` |
| 决策文档地址 | `simos-app/src/main/java/io/mosire/simos/app/docs/DecisionDoc.java:35` |
| StaticHandler 单根 | `simos-app/src/main/java/io/mosire/simos/app/gui/StaticHandler.java:22`、`:44` |
| 六模式 | `simos-app/src/main/resources/webui/modes.js:21-89` |
| 经济/unit 合并参与者 | `simos-app/src/main/java/io/mosire/simos/app/time/PopulationUnitTimeParticipant.java:39-42` |
| Shell 装配 | `simos-app/src/main/java/io/mosire/simos/app/Shell.java:593-619`、`:1158-1167`、`:1223-1240` |
| 地图投影 | `simos-app/src/main/resources/webui/hexgeom.js:31-70`、`simos-app/src/main/java/io/mosire/simos/app/render/MapProjection.java` |
| 地形颜色 | `simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainCatalog.java:45-71` |
| 批量写地形 | `simos-map/src/main/java/io/mosire/simos/map/spi/SetTerrainHandler.java:24-66` |
| 画布/取色 | `simos-app/src/main/resources/webui/renderer.js:110`、`:1987`、`:3029` |
| 磁盘占用 | 本文 §6 的 `du` 实测（2026-11-13） |
