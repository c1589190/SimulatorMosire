# 全面任务规划（第二版）：弃用 sd / ga 持标准决策人 / hex 双属性 / 权限反默认

> **这是什么**：用户 2026-10-12 第四、五轮裁定后的**可派单规划**（两组只读调查的综合结论）。
> **基线**：`main` @ `9db14239`。**权威**：与代码冲突以代码为准（§四）。
> **用户原话**：见 `docs/superpowers/reports/2026-10-12-hex-ratio-city-gov-semantics-feasibility.md` §0/§8.1/§10.1 与本文 §0。
> **前版**：`2026-10-12-permission-inversion-city-ratio-gov-semantics-plan.md`（本版取代其乙/丙/丁部分）。

---

## 0. 用户裁定（逐字，§一.8.1）

> 「那肯定b啊」「城市化率用面积比来显示」
> 「另外，给单个hex加一个可以编辑的可用土地数值，根据地形初始化，可以不填（给一个各个地形的默认值，反正大体大差不差，如果没有额外的赋值，就用地形默认值，编辑原则和城市化同理）」
> 「map层级的命令、凭空造钱造人建军肯定不该给DM用啊，你的理解是对的」
> 「目前的战役细节不都是GM推演的，单个Army决策人只有权提交自然文本战术和移动到哪，其他工具都是GM专有，我建议把1、2、3、5给废除掉，4和6都是很有用的工具，给GM开放」
> 「ga为什么依赖sd？？？？ga应当维护标准的一般决策人，因此事实上是要提供决策人Agent上下文状态描述，和决策人的归属等的，储存可以交给Core；单个决策人的具体归属，交给调用ga的具体模块不行吗？同样搞工单；和Unit一样，不但允许创建没有更多附加状态的单个决策人，也可以创建/为已有决策人赋予、修改状态！」
> 「GOV模块」（NationId 落点）「sd整个弃用！」「直接扔」（地址族）

### 0.1 已定（无需再裁）
| # | 裁定 |
|---|---|
| 1 | hex 落点 = **(b) 塞 `HexCell`** |
| 2 | 城市化率 = **面积比**（`perMille` 口径，仓内比例一律千分比整数）；**逐 hex 独立事实**，不再是人口派生量 |
| 3 | 逐 hex **可用土地数值**：按地形初始化、**可不填**（缺省取地形默认）、编辑原则同城市化率 |
| 4 | 权限反默认方向确认；**map 命令 / 造钱 / 造人 / 建军 不给 DM** |
| 5 | 战斗：废 1（触发器状态机）2（改表）3（`LossClass`）5（交战级 `finalOutcome`）；**4（`atRevision`）6（三段地址）保留给 GM** |
| 6 | **`simos-sd` 整个弃用**；`NationId` 落 **GOV**；`sd:*` 地址族**直接扔** |
| 7 | `ga` = **标准的一般决策人**：身份 + Agent 上下文状态描述；**存储交 Core**；**具体归属交调用 ga 的模块**（走既有命令路径）；**像 Unit 一样"裸创建 + 再赋状态"** |
| 8 | 随仓 `worlds/v17levant.json` **暂不动**（用测试世界验代码可行性） |

---

## 1. ★ 新增待裁（8 条，本轮调查新发现）

| # | 待裁 | 为什么必须你裁（实测依据） |
|---|---|---|
| **A1** | **`ga` 是否持状态** | 接状态后 `ga` 会是**全仓第一个"既持状态切片、又依赖 `core`"的模块** —— 今天 **8 个状态模块全部把 `simos-core` 列进禁列**（`map/pom.xml:66`、`gov/pom.xml:89`、`army/pom.xml:84` …），这正是铁律 3/4 的结构化。技术上零 pom 改动、Maven 无环（`core` 禁 ga、ga 允许 core 两边已就位），**但破例本身要你点头** |
| **A2** | **`Affiliation` 落点**（唯一的"身份×归属"耦合） | 三条路代价差约 3 倍：(a) **留 ga、降为不透明字符串**（零 pom、4 类权限分派点一字不改）/ (b) 下沉新契约模块 `dm-api`（要回填 8+ 模块禁列 + `ArchitectureGuardsTest`）/ (c) **完全交调用模块**（4 类分派点全重写 + `SdInfoEntry.affiliations` 也改）。★ 三条都可行，但**"读不到归属 ⇒ deny-all"必须保持**（`DecisionScopeFunctions:62-76`） |
| **A3** | **`NationId` 在 GOV 带来的依赖方向** | 你已裁 `NationId` 归 GOV；若 `Affiliation.Nation` 想持**强类型** `NationId`，则 **`ga` 必须读 `gov`**（今天 `ga/pom.xml:96` 禁 gov）⇒ 只能选 A2 的 (a) 或 (b) |
| **A4** | **改名后 25 条 `sd.*` 的逐域权限标签** | ★ **本轮最大的静默风险**：`sd.` 前缀规则今天挡着 25 条非 GmOnly 的 sd 命令；改名后前缀按不住 ⇒ `AdjudicateTickToolTest:549` 的 **66 → 91（+25）**，**全部自动进"可嵌决策令"白名单与 Effect 入队集合**。⇒ 必须逐域给标签：**ga 17 条** / **gov 4 条** / **army 7 条**（按你丁组口径：army 的必须"不可 DM"） |
| **B1** | ★ **可用土地的权威归谁**（**最容易被漏的一条**） | 「可用土地」**今天已经在 economy 侧被持久化**：`EconomySeeder:3546` 逐格 `landMilliMuOf(terrain)` → `:4650` 写进 `Industry.capacity["LAND"]`（毫亩），落盘见 `EconomyCodec:1484`。⇒ 在 map 里再放一个就是**同一个数的第二拼写点**。二选一：**economy 改读 map**（map 为权威）／明写"map 的是**初始/上限**、economy 的是**当前产能**"并给判据 |
| **B2** | **地形默认表落哪一层** | (a) `TerrainType` 加字段（**23 处 `new TerrainType(` 全改** + 它是变更集/落盘组件）/ (b) **`TerrainCatalog` 旁表**（零签名改动、零 `new TerrainType(`、不进快照 —— **推荐**）/ (c) map 新常量表。★ **硬约束**：`RegressionGuardsTest:371-383`（L9③）禁止 `low_hills`/`mountains`/`plateau`/`plateau_mountains` 这 4 个 key 出现在 map 除 `TerrainCatalog` 外**任何文件（连注释都算）**⇒ (c) 只能按 `TerrainType.food` 派生。★ 且 `map` 只依赖 util+ga-spi ⇒ 不能引 `EconomySeeder.arablePerMilleOf`；**若 map 自己写一份 `food*1000/3`，就把 app 的那份变成第二拼写点** ⇒ 建议**连同 `FOOD_AT_FULL_ARABLE` 一起下沉 map，app 改引用** |
| **B3** | **城市化率是否真的没有地形默认值** | 你原话"同理"修饰的是**编辑原则**（同一条命令路径），不是"城市化率也按地形取默认"。控制方判定：**城市化率无地形默认**（地形与它有语义关系吗），其"不填"落到 `0`；**只有可用土地**需要区分"未赋值"与"显式 0"（因为沙漠/山地/海洋的地形默认**真的是 0**，用 primitive 0 会把语义撞死） |
| **B4** | **甲组遗留**：`unit.PlaceAt` 与 `SetStatus`/`SetStateDescription`/`SetVisionRadius` 是否收紧 | 你已裁"Army DM 只能提交**自然文本战术 + 移动到哪**"⇒ `PlanRoute`/`PlanSparseRoute`/`CancelRoute` **保留给 DM**；`PlaceAt`（瞬移）判**收紧**；那三条"状态描述类"我倾向收紧（战术文本走的是自然语言上报那条路），**待你确认** |

---

## 2. 批次表（按依赖排序）

### 批次 0 · 零裁定零风险（**现在就能做**）
| # | 交付 | 验收判据（含负向） |
|---|---|---|
| **0a** | `simos-gov` 补**铁律 5 往返护栏** `GovRoundTripTest`（全仓唯一缺失） | 正向绿；**负向：往 `GovState` 加组件不改 `GovChangeSet` ⇒ 当场红（今天必绿）** |
| **0b** | `army → gov` pom 放行（1 exclude + 1 dependency + message；**gov 侧那条 `simos-army` 禁列保留**） | `validate` rc=0 + 计数不变；负向：给 army 加回 `simos-sd` ⇒ 红 |
| **0c** | `army.RecordCombat` 的 **revision 级判据**（今天只有域级） | 负向：改坏写口 ⇒ 必红；删"阶段 id 重复具名拒" ⇒ 必红 |
| **0d** | **文档更正 5 处**（`freeze.md:69` army 行漏 gov/sd/ga · `freeze.md:68`+`AGENTS.md:62` sd 的"推演框架层"已被 B2a/B3 推翻 · `2026-10-13-unit-integration-p2.md:40` 的 `unit.ResolveCombatStage` 笔误 · `freeze.md:78`/`AGENTS.md:72` 的"map 现用了 Nation"实测为假 · **本报告 §1.3 的 `HexCell`"P1 用户裁定"出处不明**） | 逐处 `grep` 复核；**追加更正、不改历史留痕行**（§五.4） |

### 批次 1 · 权限反默认（等价迁移，零行为变化）
- **依赖**：甲组 4 条（标签命名 / `GmOnlyCommand` 退役 / `sd.` 前缀 / 切片 A·B 分批）。
- **交付**：`simos-util/spi/DmUsableCommand`（纯标记，落 util —— 9 个域要实现）+ `Shell.java` **三处**改判（`:863` / `:879` / **`:903-914` 那 5 条无条件 add**）+ 机械派生的 **91 条** `implements` + 装配期**互斥断言**（照 `ToolRoleAudit` 形制）。
- **验收**：66 集对称差 = ∅；`AdjudicateTickToolTest:549`(66) + `:931`(97) + `McpCoverageTest`(142) + `SimosToolsTest`(142) + `CatalogVisibilityTest` 语义全绿。
- **负向（今天必红）**：新加命令不打标签 ⇒ 不进 directive/drainable，**必须**进 catalog（142→143）。

### 批次 2 · hex 双属性（**成本远低于初判**）
- **依赖**：B1 / B2 / B3 / B4（+ A4 若同批）。
- **实测低成本**：`HexCell` 加两组件 + **一个便捷构造器** ⇒ **240 处 `new HexCell(` 零编译改动**（仓内 20+ 条先例）；组件钉子只有 **2 处**会红（`HexCellTest:50-52`、`RegressionGuardsTest:169-172`）；`RoundTripComponentsTest` 的"9 vs 8 组件"**全不红**（塞值类型不改组件数）；`MapChangeSet` 无需新 `parse`（新字段是**值**不是键）。
- **★ 唯一必修的语义点**：`TerrainOperations.java:115` 涂地形时 `new HexCell(paintHeight)` 会**静默抹平**用户编辑值 ⇒ 改 `existing.withHeight(paintHeight)`。**负向判据 N4：先设城市化率 250，再 `map.SetTerrain` ⇒ 仍为 250（改前必红）**。
- **字段形状（建议）**：`record HexCell(double height, int urbanAreaPerMille, Optional<Long> landMilliMuOverride)`；城市化率**必须另名**（`urbanizationRate` 已被"人口比例"占用：`SettlementRequest:44`/`RegionSeedPlan:79`/`WorldgenConfig:137`）；可用土地用**毫亩**（与 `EconomySeeder.MU_PER_HEX`/`MILLI_MU_PER_MU` 同源）。
- **`migrate` 是坑**：`MapCodec:348-350` 只留 `height` ⇒ 随仓世界的新字段**永远缺省**（你已裁"暂不动"⇒ 合规）。
- **写入口**：两条命令 `map.SetHexUrbanization` / `map.SetHexLand`（各 ~100 行 handler + `GmOnlyCommand` + `CommandTargets`）+ 窄工具；**8~13 处表项/计数**连带（`PAYLOAD_HINTS` 缺项 ⇒ **Shell 构造期抛**）。
- **关键负向判据**：N2 hex 不在图 ⇒ 具名拒 + **零 revision**；**N3 未赋值 ⇒ 读回地形默认（plains 3,100,000 毫亩；desert 0）**；**N5 权限不放大**（不标 `GmOnlyCommand` 时必红）；N6 老 JSON `{"height":0.37}` 解码成功且新字段为默认。
- **顺带**：`CityLand`/`CityLandBook`/`PopulationMigrationPlanner`/`CityMigrationReading`/`MoveCapitalPlan` **整链死码确认，删除零行为变化**。

### 批次 3 · sd 弃用（**四步，按不可逆点排序**）
| 步 | 内容 | 正向 | 负向 |
|---|---|---|---|
| **3a** | **最小切片**：`decisionPackets` + `mergedEffectPlans` **两组件** + 4 条命令 → `ga.*`（含 `DecisionMakerId` 搬入）；建 `GaState`/`GaSnapshot`/`GaChangeSet`/`GaCodec`/`GaRoundTripTest` | `GaRoundTripTest` `hasSize(2)` 绿；`ArchitectureGuardsTest` 名单 9 → **10** | ① 提交 `sd.UpsertDecisionPacket` ⇒ **`Rejected`（未注册）+ head 不变**；② 加 `GaState` 组件不改 `GaChangeSet` ⇒ 往返**必红**；③ `ga.*` 未注册 codec ⇒ **装配期 `IllegalStateException`（不许静默）** |
| **3b** | **决策族其余 5 组件 + 13 handler → ga**；`Affiliation` 按 A2 处置 | ga 组件 7；`Directive` 的构造期外键降级为**命令期**（`:483`） | 令里嵌 `ga.UpsertDecisionPacket` **必须仍被挡**（靠批次 1 的标记，**不能靠前缀**）← **"切片不得静默放权"的守门判据** |
| **3c** | **外交 3 组件 + 4 handler → gov**（连 `NationId`）；`nations` 20 处读者全切 | gov 往返护栏（0a）覆盖新组件 | 造一个 gov 表里有、sd 不再有的 Nation ⇒ `SdResolver` 必须**查不到**（今天查得到） |
| **3d** | **战斗 4 组件 + 7 handler**：先重挂判决面 `sd:combat.*` → `army:combat.*`（**不可逆点①**），再退役；保留能力 4/6 给 GM | `grep 'sd\.CreateCombat' simos-*/src/main` = **0**（今天 9 处） | `sd:combat.<id>` + D1 判决 ⇒ **具名拒**（今天接受） |
| **3e** | **sd 空壳退役**：删模块（根 pom 17 → 16）、`ArchitectureGuardsTest.MODULES` −1 | `SdCodec`/`SdState`/`SdResolver` 全仓 0 引用 | ★ **仍恰 9 个 ChangeSet 实现者**（ga 进 / sd 出**恰好抵消**）—— 变 8 或 10 ⇒ 有人漏改 |

- **★ 改名不可避免**（实测）：`CommandBus.namespaceOf(type)` = 类型前缀 ⇒ **handler 搬 gov 而 type 仍 `sd.xxx` ⇒ 拿 gov 变更集去 sd codec 上 apply ⇒ 炸**。
- **连带面（实测）**：`"sd.` 字面量 —— app main **43** / ga main **27** / gov **5** / army **3**；**测试 358 处 / 52 文件**；MCP 工具名 `simos.sd.*` **87 处 / 19 名**；webui **24 处**；`CatalogTool.PAYLOAD_HINTS` **28 条**；`McpCoverageTest` **28 条**。
- **★ 两处抵消/不变**：`SimosToolsTest` 计数 **142 不变**（改名不改数）；`ArchitectureGuardsTest` 名单**恰好抵消**。
- **不可逆点 3 处**：① `Verdict.subject`（持久字段，`Breakpoints:88` 硬编码 `"sd".equals(subject.namespace())`）② 地址族（你已裁"直接扔"，但**扔之前必须切完 20 处 `nations` 读者**，否则静默失明）③ 命令类型串（**失败静默** —— `CommandTarget:12` 自陈"写歪不会抛、只会静默判错"）。

### 批次 4 · 权限收紧（35 条 → 按 B4 细化后）
- **不得与批次 1 混提交**（否则红点无法归因）。含 `actor.Seed`/`economy.Seed` **凭空造钱**（既有报告记"本次调查最重的一条"）。

### 批次 5 · gov 命令面 / 其余
- gov 第一条命令的 9 处同步 + `GovRoundTripTest`（0a 是前置）。

---

## 3. 执行次序

```
批次 0（0a/0b/0c 可并行；0d 文档）                    ← 零裁定，现在可开
   ↓
批次 1（权限反默认等价迁移）  ← 甲组 4 条
   ↓
批次 2（hex 双属性）          ← B1/B2/B3/B4
批次 3（sd 弃用 3a→3e）       ← A1/A2/A3/A4；3a 最小切片
批次 4（收紧 35 条）          ← 另批
批次 5（gov 命令面）
```
- **一次只允许一个写代码 Agent**（§一.10）；`Shell`/`SimosToolSource`/测试计数表是共用装配面 ⇒ 批次间**串行**。
- **★ 批次 1 必须先于批次 3**：否则改名后 25 条 `sd.*` 静默进令白名单（**66 → 91**）。

---

## 4. 风险与不确定（如实）
1. **未跑 Maven**：所有"会红/会炸"均为**断言源码 + `codecFor` 路径**级证据，非实测。
2. **`ga` 持状态破例铁律 3/4 的"域模块不认识 Core"这一半** ⇒ A1 未裁不能开工。
3. **前端未逐文件核**：`webui/**` 的 `/api/sd/*`、城市图层、region tag 消费点只数了量（24 处 `sd.` + 10 类端点）。
4. **Jackson「record creator 属性缺失 ⇒ null 而非抛」是反推**（从 `DiplomaticRelation`/`Army` 的注释与代码 + jackson 2.22.2 默认），**未跑探针** ⇒ 批次 2 建议加 3 行解码探针。
5. **`EconomySeeder`（5474 行）只读类注与入口** ⇒ B1 的"权威归一"成本可能偏乐观。
6. **`.claude/worktrees/**` 下 30+ 平行工作树未核**。

---

## 5. 用户第五轮裁定（归属归模块 + ga 不依赖 core + hex 属性表）

### 5.1 原话（逐字，§一.8.1）
> 「那让ga不依赖core就行，按你说的做；剩下的A2、A3、B2、B3、B4还需要我决策吗？看看你的新想法」
> 「归属肯定归模块啊，后面新加模块ga还得显式地重新添加？不可能的，因此是乙」
> 「形状按A / 工具两者都要，走工单，Map额外实现工具 / 自定义字段必须是一个String，应当明确标注为"自定义字段"，已有的城市/乡村走enum标签声明 / 这个可用地面积如果没有，那么读的时候就走默认，也就是单个地形的默认可用面积，以乡村类型显示」

### 5.2 八条待裁的最终处置

| # | 处置 | 依据 |
|---|---|---|
| **A1** | ✅ **用户批准**：`ga` **不依赖 core** | 实测 `ga` 用 core 只有 **9 个文件（全是 `ga/tools/write/*`）**、只用 4 个类型（`CommandEnvelope`/`CoreSimos`/`BatchResult`/`CommandResult`）；**运行流零 core import**。而 `ga-spi.ToolWritePort` **早就够用**（`BatchOutcome` 已有 `Committed/Rejected/Conflict` **且带逐条 `outcomes`**；`BatchReceipt` 回 `commandId` 列表；`head`/`replay` 都有）⇒ **9 个工具改吃端口 → ga 的 pom 删 core + 把 core 加进 ga 的禁列** ⇒ 与其余 8 个持状态模块同待遇，**铁律 3/4 无破例、新不变量不用立** |
| **A2** | ✅ **用户裁定 = 乙：归属归模块** | 原话理由：「后面新加模块ga还得显式地重新添加？不可能的」⇒ **ga 不得枚举归属种类**，否则加模块要改 ga |
| **A3** | ✅ **消失** | A2 选乙后 `ga` 不需要 `NationId`（它不再持归属）⇒ `ga → gov` 永不出现 |
| **B2** | ✅ **控制方技术裁定**：地形默认表落 **`TerrainCatalog` 旁表** | 零签名改动、不进快照；并满足硬约束（`RegressionGuardsTest:371-383` 禁那 4 个地形 key 出现在 map 除 `TerrainCatalog` 外任何文件） |
| **B3** | ✅ **消失** | 用户已**取消城市化率**整个概念 |
| **B4** | ✅ **消失** | 用户总口径「**其他工具都是 GM 专有**」⇒ `PlaceAt`/`SetStatus`/`SetStateDescription`/`SetVisionRadius` 一律收紧；只有"自然文本战术"与"移动到哪"（`PlanRoute`/`PlanSparseRoute`/`CancelRoute`）留给 DM |
| 甲组 4 条 | ✅ **已结**（前一轮）：`DmUsableCommand` 命名 · `GmOnlyCommand` 退役 · `sd.` 前缀删除 · 切片 A/B 分批 | ★ 用户追加裁定：**权限由各模块自主声明 + 模块自交 handler 清单 + ga 统一默认规则与装配期审计 + app 只做注册** |
| hex 4 条 | ✅ **已结**（见 5.1） | |

### 5.3 A2 = 乙 的波及面（实测）与形状

**波及面**：`Affiliation` 在 main 的分布 = **sd 41 / ga 18 / app 81 / gov 10**（约 150 处），集中在 12 个文件：
`SdQueryService` 23 · `DecisionScopeFunctions` 12 · `SdPayloads` 11 · `RedactingQueryService` 9 · `SimosSdReportsTool` 8 · `Affiliation` 本身 7 · `CreateDecisionMakerHandler` 6 · `SdInfoEntry` 5 · `DecisionAgentRunner` 5 · `DecisionAdjudicationService` 4 · `AppGaToolQueries` 3 · `NationOpeningSnapshot` 3。
★ `DecisionScopeFunctions` 的注册表是**按 `Class<? extends Affiliation>` 键**的（`:26/:34`）⇒ 乙要求**改键**。

**形状（六步）**：
1. **`ga` 的 `DecisionMaker` 去掉 `affiliation`** ⇒ 只剩「裸身份 + 通用上下文」= `{id, allowedTools, accessLimit, decisionCadenceTicks, providerId, conversationGeneration}`（正是用户「允许创建没有更多附加状态的单个决策人」）；
2. **各模块持自己的归属表 + 自己的赋予命令**（走既有命令/工单路径）：
   - `gov` 持 **nation DMs**（`Map<DecisionMakerId, NationId>`）与 **gov DMs**（`Map<DecisionMakerId, UnitId>`，gov 已依赖 unit ✓）
   - `army` 持 **army DMs**（`Map<DecisionMakerId, ArmyId>`）
   - 三条 `*.Assign*DecisionMaker` 命令各自在自己模块内校验 id 存在性（今天 `CreateDecisionMakerHandler` 的那段校验随之删除）
3. **端口放 `ga-spi`**（**不是 util**，也不是 ga 本体）：一个"归属解析"契约（`kind()` + 按 DM 查归属）；**各模块实现、app 装配注册**；
4. **`DecisionScopeFunctions` 的键从 `Class<? extends Affiliation>` 改成 kind 字符串**（由各模块自报）⇒ **加模块 = 加一个实现 + app 注册一行，ga 一字不改** ✓（这正是用户要的）；
5. **`SdInfoEntry.affiliations` → 不透明的"受众标签集"**（`Set<String>`，写入方自报），**可见性判定搬 app**（app 认识各模块）⇒ ga 只存标签、不解释；
6. **删 `Affiliation` 类型 + `DecisionMaker.affiliation` + `SdPayloads.requireAffiliation`**；`DecisionAgentRunner`（会话/提示语的归属）与 `DiplomaticWriteSignature`（外交写权限 = 必须是 nation DM）**改走第 3 步的端口**。

**必须保持的不可协商项**：**读不到归属 ⇒ deny-all**（今天 `DecisionScopeFunctions:62-76` 的未注册类型路径 + `NationScope` 的"类型不符就抛"口径，一字不能松）。

### 5.4 hex 属性表的最终形状

```java
sealed interface HexAreaKey {
  enum Builtin implements HexAreaKey { URBAN, RURAL }   // 内置：enum 标签声明
  record Custom(String name) implements HexAreaKey {}    // 自定义：String，类型上标注"自定义字段"
}
record HexCell(double height, Map<HexAreaKey, Long> areas)   // (A)：塞 HexCell；加便捷构造器 ⇒ 240 处零编译改动
```
- **初始化**：创世按**地形**给 `RURAL` 一个固定值（表落 `TerrainCatalog`，按 `TerrainType.food` 派生）；
- **缺省读法**：**没有值 ⇒ 读回该地形默认可用面积，以 RURAL 类型显示**；
- **操作**：**增量（±）与全量赋值都要**，走既有命令路径（工单）；**Map 模块自己实现工具**；
- **暂不与任何模块联动**（economy 的 `Industry.capacity["LAND"]` 原样不动）；
  ★ 记一条将来会撞的口子：两者名义上是同一个物理量，**将来要联动时必须定谁权威**；
- 唯一必修的语义点仍是 `TerrainOperations:115`（涂地形会**静默抹平**编辑值 ⇒ 改 `existing.withHeight(...)`）。
