# unit-ext 交接（控制器，2026-09-21 07:56 停机前）

> 目的：让**下一个会话**不必重新推导就能接着干 T9/T10。所有数字均为**实测**，
> 凡与计划冲突处已标明"计划是立项旧数"。

## 一、当前状态（一句话）

主干 `feat/adr1-core-scope` 在 **`38616da`**，**T1~T8 全部关账**（实现 + 变异 + 模块门禁 + 合并 + 合并门禁 + 台账/CLAUDE.md 回填），工作树**干净**，`origin/feat/adr1-core-scope...HEAD` = **`0 0`**。
**未派 T9**：停机前跑道不足——★ 而且实测把 T9 的估时**进一步推高**：除装配本身外，还压着 **12 条端到端载荷的时序设计**（见 §三 坑 4），**远超** T8 的 28 分钟。**不存在进行中的子代理。**

## 二、已完成的 T1~T8（提交与计数）

| 任务 | 内容 | 计数 | 实现提交 |
|---|---|---|---|
| T1 | 模型地基：`UnitStatus`/`RelativeOffset`/`CommandChainId`/`CommandChain` + `Unit` 四字段 + `UnitState.commandChains` | unit 131→148 | `4e18549` |
| T2 | 三态速度 1000/500/250‰ + `planRoute` 冻结 + `SetStatus` | 148→167 | `8b8c0c0` |
| T3 | 编制命令 A：`attachSubtree` 级联 / `detachUnit` 只节点 / `setOffset` + 三 handler | 167→183 | `5adb574`（合并 `9747448`） |
| T4 | 编制命令 B：`reparentSubtree` 整树迁移 / `splitFormation` / `mergeFormation` + 三 handler | 183→201 | `234f5ce` |
| T5 | 命令链 `createChain`/`updateChain` + ★ **六处链清空修复** | 201→216 | `69d3c7d` |
| T6 | 稀疏路线 `unit.PlanSparseRoute`（A\* 逐段展开；不可达命令期拒；★ 裁定 U3 构造器注入） | 216→233 | `5e32402` |
| T7 | 回归路径 `unit.SetRejoinTarget` + 每 tick 重规划（裁定 U4/U5/U6） | 233→246 | `851e61b` |
| T8 | 战损 `unit.ApplyCasualties`（双轨 delta + 上界 + 未知键拒 + 时间线回退） | 246→257；**core 174→177** | `8cc872d` |

**合并后门禁**：T3 1112 / T4 1130 / T5 1145 / T6 1162 / T7 1175 / **T8 = 1189 = 170/362/45/257/177/62/116**（rc=0、8/8 模块、`BugInstance size is 0` ×7、`[ERROR]` 0、前端 88/88）。每轮 **delta 都干净**（只有应涨的模块涨）。

> ★ **上表的 T8 数字曾被我写成 1191（错），07:59 更正为 1189。** 教训见 §五「算术」条：**跨文档一致 ≠ 验证**。⇒ **引用任何数字前先回原始日志重算一遍。**

## 三、★ 下一步 T9（SPI 装配）——前置与坑

**目标**：12 条新 handler 注册进 `Shell` + `PlanSparseRouteHandler` 的 `MovementCost` 注入 + catalog 含全部新 type + `unit` namespace 恰一个 participant + 端到端冒烟。

**★★ 必须先知道的坑（控制器已核实）**：

1. **★ 计划里的 `simos-app` 计数 `110 + M` 是立项旧数，实测是 `116`。**
   （同理 `simos-core` 的 `169 + K` 也是旧数——T8 实测把它变成了 `177`。）
   ⇒ **一律以实测为口径**；计划的计数只作立项基线，**不改计划**（改了会抹掉偏差记录）。
2. **★ 计划 T9 步骤 1 写的 `new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE)` 是"对的"，别误判成违反 U3。**
   U3 禁止的是**handler 内部写死** `INSTANCE`；在**装配点**注入 `INSTANCE` **正是注入的意义所在**。
   ⇒ 装配点传 `INSTANCE` 合法；`Shell` 里若出现任何"handler 内部自己取 cost"的形态才是错的。
3. **★ T9 的门禁不是"模块门禁"，是"近似全量"。** T9 改 `simos-app`，而 `-pl simos-app -am` 会把 7 个依赖全带上 ⇒ **实测耗时必然压在那条 600 s 线上**（见 §五）。⇒ **T9 的门禁必须按全量门禁的规矩跑**：前台、独占、记"第几次尝试"。
4. ★★★ **最大的一条（实测；锚 = `simos-app/src/test/java/io/mosire/simos/app/McpCoverageTest.java:99-219`，该文件本任务未改动）：`McpCoverageTest` 会把"只注册不补载荷"直接打红，而 T9 的活比"12 条注册"大得多。**
   实件 `simos-app/src/test/java/io/mosire/simos/app/McpCoverageTest.java`：
   - `EXPECTED_COMMAND_TYPES`（`:99-118`）是**硬编码的 18 元 `List`**（★ 注释却写"预期的 **14** 个"——又一例**陈旧注释**，与 T10-f 的"十六个"同族）。
   - `MINIMAL_PAYLOADS`（`:120` 起）是 `LinkedHashMap`，注释明写「**每类的最小合法载荷（对夹具世界；顺序即语义合法序）**」⇒ **插入顺序承重**；且 `unit.DisbandUnit` 会**删掉 `u-1`**（Javadoc `:80` 专门注明"执行序不是 catalog 序"）。
   - `everyCatalogTypeIsReachableThroughMcpAndTakesEffect`（`:209`）判据是：**对 catalog 里每一个 type，经 MCP 真提交一条载荷，并断言分支 head 前进**；`containsExactlyInAnyOrderElementsOf(catalogTypes)`（`:219`）再钉住**载荷表与 catalog 双向一致**——`:218` 的原话是"**漏一个就会在这里红**"。
   ⇒ **推论（T9 的真实工作量）**：注册 12 条 ⇒ catalog 18→30 ⇒ 这个测试**必红**，除非 T9 **同时**①往 `EXPECTED_COMMAND_TYPES` 补 12 条 ②往 `MINIMAL_PAYLOADS` 补 **12 条在夹具世界上真能生效的载荷**。
   ★★ **"真能生效"是硬要求**：判据是 **head 前进**，**载荷被域层拒绝同样红** ⇒ 不能塞 token 占位符。⇒ 其中数条**必须先造前置状态**，例如：
   - `unit.AttachUnit`（spec `:143`「合体 = **同格前提下**重新 attach」）⇒ 得先让两个单位同格；
   - `unit.MergeFormation`（T4 实测要求**同格 + MOVING**）⇒ 得先 `PlaceAt` 同格**再**让它们 MOVING，**两步都排在它前面**；
   - `unit.PlanSparseRoute` ⇒ 载荷要在夹具地形上**真的逐段可达**（T6 的判据是"任一相邻段不可达即命令期拒绝"）；
   - `unit.SetRejoinTarget` ⇒ 依 T7-U5 需 `status == MOVING` 才不早退；
   - `unit.CreateCommandChain`/`UpdateCommandChain` ⇒ 受 `requireNotInAnyChain` 约束，**与 `DisbandUnit` 的相对次序会影响成员可否复用**。
   ⇒ **这条把 T9 从"装配"变成了"装配 + 12 条端到端载荷的时序设计"**，估时**远超** T8 的 28 分钟。★ **别把它当成"顺手补一下"**；也**别**为了变绿去放宽 `McpCoverageTest`（那正是本任务要保的端到端判据）。

**★★ T9 的完整爆炸半径（实测扫过全部相关测试面；锚 = 各文件路径行号，均未被本任务改动）**：

| # | 落点 | 会不会红 | 该怎么办 |
|---|---|---|---|
| 1 | `simos-app/.../McpCoverageTest.java:99-219` | ★★ **必红** | 补 12 条 expected + 12 条真载荷（见上，**T9 的主要工作量**） |
| 2 | `simos-app/src/test/js/gate-contract.test.cjs:9-20,47` | ★ **仅当T9新增 `.cjs` 测试文件时红** | `REQUIRED_FILES` 是**10 个文件的精确集合**（`deepEqual`）⇒ 新增 JS 测试必须同步登记；另有 `assertion-count-is-not-below-the-frozen-floor`（冻结下界，只增不减） |
| 3 | ★★ **静默**：`simos-app/src/main/resources/webui/modes.js`（`isWriteAllowed` `:77-82`、`unit` 模式 `writes` `:37-48`） | **不红，但功能不通** | 白名单是**逐条精确匹配**（`allowedWrites(mode).indexOf(type) >= 0`）、**未知 type 一律拒（fail-closed）**⇒ **后端注册了，GUI 仍拒绝发这 12 条**。⇒ 必须把这 12 条加进 `unit` 模式的 `writes`，并按 #2 的冻结下界补 `modes.test.cjs` 断言 |
| 4 | `simos-app/.../McpServerTest.java:97`（`TWELVE_TOOL_NAMES`） | **不红** | 它钉的是 **12 个 MCP 工具名**（不是命令）⇒ **T9 不得新增工具**，否则此表要改 |
| 5 | `simos-app/src/test/js/write-allowlist.test.cjs` | **不红** | 它管的是 **HTTP 端点**白名单（`/api/command`、`/api/advance`、`/api/fork`），与命令类型无关 |
| 6 | `simos-app/src/test/js/timeline.test.cjs` | **不红** | 只测标签/分组（`shortCommandType`），与命令集合无关 |
| 7 | ★★ **静默**：`simos-app/.../tools/read/CatalogTool.java:28`（`PAYLOAD_HINTS`）+ `:72` | **不红，但 agent 侧引导缺失** | 提示表是**静态 Map**，取值用 `getOrDefault(type, "")` ⇒ **缺项默认空串、不报错**。实测**只有 8 条**，且**恰是已注册的那 8 个 unit handler** ⇒ **今天 `map.*`/`sd.*` 的 10 条就已经没有提示**；T9 后变成 **30 条里 22 条空提示**。⇒ 归 T10 决定"补全还是显式接受"，**别默默放着** |

★ **同时发现一处"既有"缺口（不是 unit-ext 造成的，先记为问题、不记为结论）**：`unit` 模式的 `writes` 只有 **6 条**（`PlanRoute`/`CancelRoute`/`ReparentUnit`/`SetStrength`/`DisbandUnit`/`CreateUnit`），而 `Shell` 已注册 **8 条** unit handler ⇒ **`unit.RenameUnit` 与 `unit.PlaceAt` 不在任何模式的 `writes` 里**（`map-edit`/`region-edit` 也只列 map 命令）⇒ **今天 GUI 就发不出这两条命令**。★ **未定**：这可能是**有意收紧**，也可能是**疏漏**——**无判据可判**。⇒ **归 T9/T10 一并裁**：若判"疏漏"，则 12 条新命令同样该进白名单（与 #3 合并处理）；若判"有意"，则 **#3 的结论要反过来写**（新命令**本就不该**进白名单，但那样 T9 就得说清"这 12 条从哪个模式发"）。**别不做裁决就默默跳过。**

**12 条 handler 清单**（计划 T9 步骤 1，**控制器已逐条实测核过**）：
`AttachUnit` / `DetachUnit` / `ReparentSubtree` / `SetFormationOffset` / `CreateCommandChain` / `UpdateCommandChain` / `SplitFormation` / `MergeFormation` / `PlanSparseRoute(cost)` / `SetRejoinTarget` / `SetStatus` / `ApplyCasualties`

**★ 已实测核过的前置（不是照抄计划，是查过现状）**：

- `simos-unit` 的 `spi/` 下**共 20 个 `*Handler.java`**，上述 12 个**全部存在**，且各自的 `type()` 返回串**逐个对得上**（`unit.AttachUnit` … `unit.ApplyCasualties`）。
- **`Shell` 现在只注册了 8 个 unit handler**：`RenameUnit` / `CreateUnit` / `ReparentUnit` / `SetStrength` / `PlaceAt` / `PlanRoute` / `CancelRoute` / `DisbandUnit`（实测 `Shell.java:203-210`）。⇒ **12 + 8 = 20**，账对得上。
- ★★ **一个容易踩的陷阱**：`SetStatusHandler` 是 **T2** 造的，但**从来没有被注册进 `Shell`**（实测 `Shell.java` 的 import 与 handlers 列表里都没有它）。⇒ **别以为"T2 那批早就注册过了"**；`SetStatus` 确实**要由 T9 首次注册**（计划把它列在 12 条里是**对的**）。
- ★ **计划的 `Shell.java:193-213` 与 `直读现状 :187-207` 是旧行号**：实测 handlers 列表在 **`:195-214`**（同类漂移见 §五「md5 才是判据」条与 `t8-evidence/mutants/LINE-DRIFT-NOTE.md`）。⇒ **以符号为准，别照抄行号。**

**★ 权威 type 清单（08:01 机械抽取，非记忆；锚 = 提交 `1eff18c` 时间戳 08:01:05）**：对 `simos-unit/.../unit/spi/*Handler.java` **逐个取首个 `return "…";`** ⇒ **20 个文件、20 个唯一串、全部 `unit.*`**：

```
unit.ApplyCasualties   unit.AttachUnit        unit.CancelRoute        unit.CreateCommandChain
unit.CreateUnit        unit.DetachUnit        unit.DisbandUnit        unit.MergeFormation
unit.PlaceAt           unit.PlanRoute         unit.PlanSparseRoute    unit.RenameUnit
unit.ReparentSubtree   unit.ReparentUnit      unit.SetFormationOffset unit.SetRejoinTarget
unit.SetStatus         unit.SetStrength       unit.SplitFormation     unit.UpdateCommandChain
```

- **待注册 12**（T9 的靶子）= 上表**去掉已注册的 8**：`ApplyCasualties`/`AttachUnit`/`CreateCommandChain`/`DetachUnit`/`MergeFormation`/`PlanSparseRoute`/`ReparentSubtree`/`SetFormationOffset`/`SetRejoinTarget`/`SetStatus`/`SplitFormation`/`UpdateCommandChain` ⇒ **与 §三 的 12 条清单逐条相同（已实测对齐）**。
- **已注册 8** = `RenameUnit`/`CreateUnit`/`ReparentUnit`/`SetStrength`/`PlaceAt`/`PlanRoute`/`CancelRoute`/`DisbandUnit` ⇒ **12 + 8 = 20 ✓**，无遗漏、无重复。
- ★ **T9 的 catalog 断言请写成"集合相等"，不要用前缀/包含**：本表里有**近名对**（`ReparentUnit` vs `ReparentSubtree`、`PlanRoute` vs `PlanSparseRoute`、`SetStatus` vs `SetStrength` vs `SetFormationOffset`）⇒ 任何 `startswith`/`contains` 型断言都会**掩盖"注册错了一条"**（正是 m1"删一条注册"想抓的东西）。
- ★ **一个机械事实**：这 20 条**全是 `unit` namespace** ⇒ `unit` namespace 下**恰好一个 participant**（§三判据那半）与"20 条 type"是**两件事**，别混。

**★ 装配链已实测走通（08:02，全部只读、未动一行生产代码；锚 = 提交 `8929737` 时间戳 08:02:01）——T9 的形状比计划描述的更简单**：

1. ★★ **不存在"独立的 catalog 类"要改**。`Shell.java:215-219` 用 `Set<String> commandTypes = new LinkedHashSet<>()` **遍历 `handlers` 调 `handler.type()` 现场构建**，再经 `Shell.java:260` 传给 `SimosToolSource`（`:49` 注明"与 `Shell` 注册的 handler 同源"）→ `CatalogTool(commandTypes)`（`:64`）→ 面向 agent 的 `simos.command.catalog`。⇒ **注册即入 catalog，判据由构造保证**；T9 所谓"改 catalog" = **别漏注册**，**不存在第二处要改**。
2. ★ **`MovementCost` 注入已有先例，不必新造通道**：`Shell.java:221` 就是 `new UnitTimeParticipant(TerrainMovementCost.INSTANCE, config.mapId())`。⇒ `new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE)` **照抄同一来源**即可。★★ **且必须写进 `List.of(...)` 之内**（`:195-214`）——若写在 `commandTypes` 构建循环**之后**，handler 注册了但 **catalog 会漏**（这是"注册了却没进 catalog"的唯一真实路径，也正是 §三 陷阱 2 的实操含义）。
3. ★★ **一个比"⊇12"强得多的机械判据**：真源码里 `CommandHandler` 实现**共 30 个**（map 6 + sd 4 + unit 20），而 `Shell` **现注册 18**（`:195-214` 机械计数 = 6 map + 8 unit + 4 sd）⇒ **18 + 12 = 30 = 全仓所有实现，一条不多一条不少**。⇒ T9 判据建议写成 **catalog type 集合 == 30 个实现的 `type()` 集合（集合相等，且 `|catalog| == 30`）**：**比"⊇12"强**，且能同时抓"漏注册"与"注册错/多注册一条"。
   ★ **计数必须限定真源码路径**：不加 `-- '*/src/main/java/*'` 会把 `.superpowers/**` 里的变异体副本算进去（实测 `CommandHandler` 30 → **45**、`TimeParticipant` 1 → **13**）⇒ 一律限定，**别用裸 `-l | wc -l`**。
4. **participant 侧无事可做**：全仓 `TimeParticipant` 实现**只有 `UnitTimeParticipant` 一个**（`namespace()` 硬编码 `return "unit";`，`:91-93`）；`ModuleCodec` **4 个**，与 `Shell:190` 的四条注册相符。⇒ "unit namespace 恰一个 participant" 现成立，**T9 不应新增 participant**。

**判据**：catalog type 集合 ⊇ 上述 12 个（**m1 靶子 = 删一条注册**）；每个 `type()` 形状 `<namespace>.<Command>`；`unit` namespace 恰一个 participant（装配 + 一次 `advance` 不抛）；端到端经 `Shell` 发一条新命令 ⇒ `committed`。**变异 ≥2 轮**；★ 十道门禁 + ★ **裁定 42**（新增/改动护栏必须自带变异轮）+ ★ 第 ⑩ 道**逐片段**自证。

**★ 装配改动 ⇒ 必须连带复核**：`Shell` 是既有装配点，改它 = **改动既有护栏** ⇒ 按裁定 42 需自带重跑轮；同时确认 `simos-app` 既有测试（实测 **116**）只增不减地绿。

## 四、T10（端到端判据 + 关账）——**已挂账的待裁项**（不许丢）

| 编号 | 挂账项 | 出处 |
|---|---|---|
| **T10-a** | ★ **T3-L1**：`dq`/`dr` 无上界 ⇒ `RelativeOffset.appliedTo` 裸 int 加法**静默溢出**（`1+MAX_VALUE=-2147483648`）；修点应落 T1 的 `RelativeOffset`（只堵 handler 层则 codec 路径仍开着），且 **P2 明文"无范围约束"** ⇒ **与 P2 一起裁** | T3 |
| **T10-b** | **同族判据清扫**：`everyHandlerRejectsMalformedPayload` 里 **13 处**只断言字段 token 的载荷断言（`UnitCommandHandlersTest:620`~`:631`）——凡"载荷层与域层都会提到同一字段名"的命令，token 断言**判不出是哪一层拒的** | T4 / T5-L6 |
| **T10-c** | **T5-L4 的 1 参兼容构造器 `new UnitState(units)` 是否删除**（它会**静默清空 `commandChains`**；T5/L4 立为通则、三次被变异体撞上既有护栏） | T5-L4 / T7-G4 |
| **T10-d** | **G1**：`disband` 之后可能留**悬空 `rejoinTarget`**——运行期口径安全（`effectivePosition` 对不存在 id 返空 ⇒ 不回归、不写任何东西）但**无判据**；**未在 T7 补**（会动 T3/T4 已关账 op 族） | T7-G1 |
| **T10-e** | **G5**：回归与**在途普通路线**同时存在时的交互**无 spec 依据**（现状：回归行程**替换**在途行程）；**凭空定策略就是发明需求** ⇒ 届时仍无上游依据就**记为"未定策略"、不记为"已实现"** | T7-G5 |
| **T10-f** | **G2**：`UnitPayloads` 类注仍写"unit **十六个** handler"——**T7 立此项时实为 18**，**T8 后实为 20**（08:02 机械抽取复核实测；T9 注册完 type 总数**仍 20**，变的只是 **Shell 注册数 8→20**）⇒ 纯注释、零行为 | T7-G2 |
| **T10-g** | **spec §八 20 条逐条实测值**（不是"通过/不通过"，要**数字**）+ 变异轮汇总 + **"我未能核实的"清单** + 关账报告 | 计划 T10 |
| **T10-h** | ★ **新增（08:02 控制器实测，锚 = `8929737`）**：`Shell.java:308` 日志字面量**硬编码** `"… participant=1"`——当前**无测试断言**（`*/src/test/*` 搜不到），故**非现行陷阱**；但**一旦将来注册第二个 participant，这条日志会静默说谎**（既不编译错、也不测试红）⇒ 归 T10 清扫，**修法是数出来**而不是写死 | 控制器 08:02 |
| **T10-i** | ★★ **静默面之一：GUI 写白名单**。`webui/modes.js` 的 `unit` 模式 `writes` 只列 **6 条**，而 `Shell` 已注册 **8 条** unit handler ⇒ `unit.RenameUnit`/`unit.PlaceAt` **今天 GUI 就发不出**；T9 的 12 条同样会被 **fail-closed 拒**且**无测试会红**。⇒ **要裁的是"白名单该不该跟注册面走"**（若"该"，则 T9 一并处理；若"不该"，则须说清这 12 条**从哪个模式发**） | 控制器（`modes.js:37-48`/`:77-82`） |
| **T10-j** | ★★ **静默面之二：agent 侧载荷提示**。`CatalogTool.PAYLOAD_HINTS`（`:28`、取用 `:72` `getOrDefault(type, "")`）**只有 8 条**，恰是已注册的 8 个 unit handler ⇒ `map.*`/`sd.*` 的 **10 条今天已无提示**，T9 后 **22/30 为空**。⇒ 裁"补全 or 显式接受" | 控制器（`CatalogTool.java:28/72`） |
| **T10-k** | ★ **同族抽象（建议 T10 一并裁）**：h/i/j 三项 + `McpCoverageTest` 的 expected 表 + `gate-contract` 的 `REQUIRED_FILES`，**都是"声明式清单不随注册面自动延伸"** ⇒ 建议裁定一条通则：**凡"随命令/文件集合增长而该增长的清单"，要么改成从源头派生（像 `Shell` 那样现场构建）、要么配一条"清单 == 源头集合"的断言**，否则**每加一条命令都会静默留洞** | 控制器归纳 |

## 五、★ 运行纪律与环境实测（**下一个会话必须原样继承**）

### 安全 / 纪律
- **密钥纪律**：配置**值**绝不进日志 / 异常 / 事件 / argv / env / stdio；读配置只打印**路径 + 长度**。
- **绝不 `git add -A`**；提交前先扫 `git diff --cached`；**不加 `Co-Authored-By`**、不加任何生成器 trailer；提交信息用中文。
- **`~/.m2` 里那份 simos 构件是探针 `install` 写进去的** ⇒ **`-am` 必带、禁 `install` simos、禁改任何 `pom.xml`**。
- **一次只跑一个 Maven**（本机 `nproc=2`）；**不许在 agent 活着时跑全量 verify**。
- **不许跳过门禁**；**单个模块的评审不超过 3 轮**，评审体量不得压过代码本身。
- 证据放 `.superpowers/sdd/<date>-<name>/tN-evidence/`，**不许放仓根**。
- 本机 `grep` 是 ugrep 7.8.4 ⇒ 全仓搜索一律 `git grep`；含未入库文件用 `git grep --untracked`。
- worktree 下**绝不裸用 `git stash` / `git stash pop`**。
- ★★ **子代理一律用默认类型**（用户明确要求）；**不许用 V4 Flash**。
- `CLAUDE.md` 两边各有一份同名文件（主检出与 worktree）⇒ **改哪份要认路径**。

### 环境实测（这台机器，1.8 GiB）
- ★★ **全量 `clean verify` 耗时压在 Bash 工具 600 s 上限附近**：一旦越过就被 harness **摘到后台**；**摘后台窗口内的内存压力**会招来内存守卫**杀进程**。
  **实测序列**：T5 1 杀 1 成 / T6 0 杀 1 成（587 s，恰在线下）/ **T7 3 杀 1 成** / **T8 0 杀 1 成（被摘后台、跑完 rc=0）**。
  ⇒ **"前台"是必要条件、不是充分条件**；**可操作的只有两条：①走前台 ②独占（门禁期间一条命令都别跑）**。
  ⇒ **"被杀"既不是红也不是绿**；被杀轮**留档不删**；记录门禁结果**必须一并记"第几次尝试"**。
- ★ **被杀轮的死点可反推，且 T7 三轮都死在末段**（08:00 实测，前缀和**精确吻合**）：被杀轮无汇总（`SUCCESS [` 计数 0），但**累计用例数**能定位死点——`t5 attempt1` 累计 **532** = `util+map`（第 3 个模块中，46%）；`t7 attempt1/2` **997** = 到 `core` 为止（跑完 5 模块，85%）；`attempt3` **1059** = 再过 `sd`（跑完 6 模块，死在 `app`，90%）。
  ⇒ ①**"被杀"不是"早早失败"**，T7 三轮都在跑完 **85%~90%** 用例后才倒下 ⇒ **重跑一轮的时间代价≈整轮全价**；②**被杀的轮次不许读作"没参考价值"**——它客观证明"耗时确实压在线上"。
  ★ **假说（未验、不得当结论）**：死点偏末段**可能**因 `simos-app` 处 JVM 与 npm 前端门禁同驻、内存压力峰值在那里；但 **T5 的被杀轮死在第 3 个模块** ⇒ **反例在案**，故只记「**压力驱动、不是位置驱动**」。
- ★ **md5 才是"字节变了"的判据，mtime 不是**；**同一文件被改动 ⇒ 旧证据对应旧字节**。
- ★ **文档里的时刻要挂可核验的锚，不写"我读的钟"**（08:02 立）：本会话**第三次**把时刻写到实际钟点**之前**（这次写了 `08:06`，实际 `08:02`）。⇒ 不是"看错表"，而是**自报型记录天然不可核验**——与上面的 md5/mtime 是**同族病**：**凡是"记录者自己说"的字段（时刻、计数、哈希），都得挂一个外部可验的锚**。时刻的锚 = **提交时间戳**（`git log --format='%ad' --date=format:'%H:%M:%S'`）⇒ 凡文档里出现钟点，**先取提交时间戳再回填**，读者可自行复核。**这条与"门禁总数一律现场重算"是同一条纪律的第二次兑现。**
  ★★ **加固（写完上面这条的下一段就又犯了一次——第四次）**：我在描述 `McpCoverageTest` 陷阱时写了"`08:04` 实测"，而承载该发现的提交 `4e25e97` 时间戳是 **`08:03:21`**。**根因不是粗心，是上面那条规矩本身有缺陷**：它要求"**回填**提交时间戳"，可我**在提交之前**就把钟点写进了正文 ⇒ **只能靠猜**。
  ⇒ **改成更硬的做法：新写的正文里干脆不写钟点。** 要给别人可定位的点，就写**可核验的锚**——**文件路径 + 行号**（如 `…/McpCoverageTest.java:99-219`）或**短哈希**；钟点由 `git log` 从哈希解析。**哈希与行号是可核验的，钟点不是。**
  ⇒ **教训本身值得单独记**：**"立了规矩"≠"执行了程序"**——我把规矩写进文档的同一分钟就违反了它，因为**规矩要求的动作发生在写作之后、而错误发生在写作之中**。凡此类"事后回填"型规矩，**都要改成"写作时就不写那个字段"**，否则等于没立。
- ★★ **算术：跨文档一致 ≠ 验证**（T8 收口时实测踩到）：我把 T8 门禁总数写成 **1191**（真值 **1189**），错因是**两处连乘的自算错**（`+11 +3` 写成 +16；`1175+16` 算成 1191——两个加数本身是从日志**正确提取**的）。错被抄进 3 份文档 + 2 条已推提交信息。
  **停机前的跨文档一致性检查全部通过**——因为**三份文档一致地错** ⇒ **一致性只证明"抄得整齐"，证明不了数对**。
  ⇒ **通则：门禁总数一律现场重算**（`grep -E '^\[INFO\] Tests run:' <log> | grep -oE '[0-9]+' | paste -sd+ | bc`），**不得引用任何文档里的现成数字，包括本文件与台账**；文档里的数只能用来**发现分歧**、**不能当结论**。★ 同族病：计划里的 `169+K`（core）与 `110+M`（app）都是**立项旧数、无人回源头算**。
- ★ **"派单"与"提交树外文档"不能同序**：T6/T7/T8 **连续三次**因为"派单后又提交了收口文档"导致分支分叉、需要 `rebase` 再 ff。⇒ 要么**先提交完再派单**，要么**接受一次 rebase**（并记得报告里引用的哈希会漂）。

## 六、T9/T10 之后

**sd-simos C1~C6 → D1~D7 → E1**，其中 **C 的硬前置 = unit-ext 全部完成**。

## 七、停机时的一件待决（**需用户一句话**）

`origin/main` 落后 **20** 个提交（`origin/main...HEAD` = `0 20`）。记录在案的口径是「默认分支 **deliberately unpushed**」，本会话**未推**，也**没有**在停机时擅自推。若要把 main 也推上去（用户此前有过一次「推 main」的先例），说一声即可。
