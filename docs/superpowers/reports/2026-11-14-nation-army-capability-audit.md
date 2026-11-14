# 国家、领土与军队编制工具能力调查

> **状态**：调查完成，2026-11-14。只读代码/实际工具清单与隔离内存探针；未修改生产代码、测试或现有演示存档，未创建四国局势。
> **当前状态入口**：[STATUS](../../agents/STATUS.md)。本报告记录事实，不替代设计。[探针证据](2026-11-14-nation-army-capability-evidence.json)。

## 1. 用户目标运行序列

现有地图 → 建立也门、埃塞俄比亚、厄立特里亚、索马里四国 → 指定/修改领土并显示国家色、边界、名称 → 根据选定年代公开资料创建军队 → 师/旅/营编制可修改 → 营默认随旅、旅默认随师共同移动 → 网页与LLM图片显示正确位置和层级。

用户列举四国，调查按四国处理；本轮只确认现有能力与缺口，不填未经考据的军队名册，不更改经济系统。

## 2. 能力判断

| 用户需要 | 当前能力 | 关键边界 |
|---|---|---|
| 建国家 | 基本可用 | `map.CreateRegion` 创建带 `nation:<id>` tag/颜色的领土区域，再用 `simos.gov.createNation` 建 Nation；实际 MCP 工具清单存在 |
| 改领土 | 有底层工具 | `map.UpdateRegion` 替换格集；另有 MergeRegions/SplitRegion/ReassignHexes 命令，需通用提交；国家区域允许重叠，不能自动保证互斥国土 |
| 国家资料编辑 | 不完整 | Nation 只有 id/name/homeRegion/adminBudget；无国家更新/改名/改homeRegion命令。Region 可单独改名/色，与 Nation 不自动同步 |
| 国家显示 | 网页具备、LLM图片缺口 | 网页依赖 Region 的 tag/颜色/区名；MCP render 只有区域边线，缺国家填色和国名图层 |
| 创建师旅营 | 树结构已支持 | Unit 可任意深度 parent；Army 是挂到 rootUnit 的军队身份，ArmyFormation.role 是自由文本，未有规范的师/旅/营级别目录 |
| 改编/装备 | 多项可用 | CreateUnit、SetArmyFormation、Attach/Detach、Reparent/Subtree、Split/Merge、AdjustComposition；装备整体修改工具真实名称是 `simos.unit.set-composition`（命令 `unit.SetComposition`） |
| 人员创建/修改 | editor 下组合链不完整 | 人数权威在 Social 家户，不能把 manpower 当 Unit 字段；spawnArmy/raiseUnit 等仍含暂停的 economy 命令 |
| 默认整支同移 | 已实现、隔离探针确认 | parent + attached=true 链共同移动、整支按最慢有效速度；下级真实位置逐项写入，并非查询继承 |
| 军队/编制显示 | 有基础 | 网页单位树可多层，地图按根分组军队标记；同格堆叠、图标计数是单位数而非兵员数，缺国家/军级/营级统一图层语义；MCP 图是通用单位标记 |

## 3. 关键证据与缺口

### 3.1 国家和领土并非一个完整编辑对象

- `simos-gov/.../nation/Nation.java:18`：国家记录没有颜色字段，颜色在 `RegionMeta`。`CreateNationHandler.java:61-83` 要求 homeRegion 存在且有 nation 前缀 tag；创建并不保证 tag 后缀等于 nationId，正确绑定仍依赖调用方。
- `simos-map/.../spi/UpdateRegionHandler.java:23-30`：hexes 和 meta 至少给一个；允许区域重叠，边界由格集重建。meta 是整体值，不能把只给颜色误当成保留其他 meta 字段的局部补丁。
- `simos-app/.../access/NationScope.java:70` 用完整 `nation:<id>` tag 查领土；网页国家填色由 `webui/map.js:446-457` 从带 tag 的区域派生。
- `webui/worldmodel.js:317-337` 的国名来自 **region.name**；`renderer.js:1543-1564` 为国家区域填色。显示不直接取 Nation.name；同国多个区域的名称和汇总可能重复，领土重叠也会重复计数。
- 有 `map.ReassignHexes`、`map.MergeRegions`、`map.SplitRegion` 命令（各自 handler/RegionOperations），不能称这些能力不存在；但不是完善的国家割让/转移窄工具，涉及 GOV jurisdiction 时还要同批协调。
- 默认 editor 仍保留 `simos.gov.createOffice` / worldgen 等组合工具，但其批含 `economy.RegisterGovernment` / `economy.Seed`，会被暂停领域命令缺席阻断；这项此前已有隔离运行证据，本轮未再写世界复测。

### 3.2 同移机制已存在，创建与改编接口仍有坑

- `simos-unit/.../spi/CreateUnitHandler.java:94-110`：给 parent 默认 attached=true；显式位置必须与父同格。**只给 parent、省 position 仍被接受**，但不会初始化成父的位置。
- `UnitState.java:98-101` 的 effectivePosition 只读自身位置，已经取消位置继承；`:116-174` 的 formationRoot/formationMembers 才定义整支成员，`:185-195` 取整支最慢速度。
- `UnitTimeParticipant.java:204-220,263-291`：只有编制顶层自行推进，随后将所有 attached 后代写到同一格并清掉成员旧路线。
- `UnitOperations.java:452-518`：仍 attached 在师下面的旅不能直接单独下路线；需先分遣/Detach，再给旅下令，其营随行。这是已存在的“保留编制归属、独立行动”机制。
- `UnitOperations.java:136-152` 的 Reparent 只改 parent；不能等同于重新合编。Attach/Merge 需要同格，Detach/Split 可保留 parent 但切换 attached。
- `UnitOperations.java:419-434` 的 PlaceAt 只改一个单位，未随同搬动下级；与整支移动语义不同。建局势时不能把移动师部图标等同于搬动全师。
- `UnitCreateTool.java:48-52` / Catalog 的部分描述仍说“省位置就继承父”；`SetFormationOffsetHandler.java:68-76` 对已退役偏移功能具名拒绝，工具却仍在面上。

### 3.3 真正有人的军队和国家归属需要额外装配

- `SpawnArmyPlan.java:364-375` 的组合批含 Social 工单、CreateUnit、可选 SetArmyFormation、CreateArmy、**economy.RegisterHousehold**、actor 账户和 ga 记录；`EditorProfile.java:34-42` 移除 economy handler，所以高层一键建军与当前 editor 不兼容。raiseUnit 同类问题。
- `UnitSetCompositionTool.java:48-52` 真实说明只改装备，非空 manpower 拒绝；兵员属于 Social 家户。不能用新增 Unit.manpower 或把人员当装备的方式绕过该权威。
- `CreateArmyHandler.java:105-160` 允许 masterGov 缺省；Army 认 GOV，不直接认 Nation。Army.masterGovUnitId 和 Unit.ArmyFormation.masterGov 两个关系需由 `simos.army.assignGov` 编排同步；命名为某国军队并不等于已完成所属关系。
- 创建 Army 不要求 root 已有 ArmyFormation，也不要求 root 已是整个 parent 树的顶层；可建出身份记录但不呈现预期军队标记。需要一致的建军入口和有效性检查。
- `RenderLayer.java:14-29` / `RenderModelBuilder.java:80-136,182-214`：LLM图目前支持 terrain/regions/cities/units/population，只有区域边线和统一单位标记，没有国家色块/国名、师旅营图标层级。

## 4. 本轮实际验证

1. **现有服务只读 tools/list**：连接 `http://127.0.0.1:18515/mcp`，确认建国、建军、区域CRUD、单位创建/附属/分遣/改编/路线等工具已装配。装备工具使用真实名称 `simos.unit.set-composition`，不误认为 `unit.SetComposition` 窄工具缺失。
2. **隔离内存探针**：使用已验收的 `run/map-reference-demo/simos.jar` 编译 `/tmp/SimosFormationAudit.java`，不启动服务、不写 store、不跑 Maven/JUnit。真实 CreateUnit/PlanRoute handler + UnitTimeParticipant，用受控边成本 24000 毫MP；师速10、旅速5、营速1。
3. 显式给三者同格位置后，师路线日1：师/旅/营都实际写为 `1_0`，整支装载速度1。
4. 省略旅营初始 position：创建成功但两者初始 effectivePosition 都为空；师第一次移动后才全部物化到 `1_0`。
5. 分遣营后：营仍 parent=brigade、attached=false，留在 `0_0`；师旅装载速度5，移动到 `2_0`。证明“有隶属但不同行”与“默认整支同行”两种状态可明确控制。

该探针证明编制位置同步，不验证真实地形/装备下的行军速度、完整 Core 时间编排或大规模营级性能。源码/已有测试核查不等于本轮实际建成四国局势。

## 5. 建议的后续最小工作

先补不依赖经济的完整国家/军队创建与编辑入口，保持 Social 人数权威；修正创建/改编时的位置和 attached 规则及失效描述；让 GUI/MCP 图片对齐国家与师旅营显示。复用已有同移机制，不重写一套跟随系统。之后再核对底图覆盖/地理配准，定年份和国际承认国界/实际控制区口径，逐条登记公开编制资料和缺失项。

## 6. 未做 / 未验证

未修改生产实现或测试、未提交推送、未改演示世界；未跑 full gate（上一轮3835/482为历史功能基线）；未实建四国、未考据营级名册、未验证国家色块的完整浏览器实例或现代营级大规模性能。公开资料的完整程度和当前控制区尚未调查，不把未知编制补成事实。
