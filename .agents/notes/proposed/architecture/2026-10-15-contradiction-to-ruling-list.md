# 矛盾 → 裁定项 清单（D-031 文化 / 生产方式 / 经济「接口先行」）

> 用途：把本侧线（`parallel/side-module`）已整理的冲突一次性摊开，每条给「谁和谁矛盾 + 逐字证据 + 裁定项 + 我的建议 + 状态」。
> **真实地址规则**：项目文档一律给**绝对路径**，落在**主检出** `/home/cna/SimulatorMosire/`；本侧线自己新写的笔记落在**工作树** `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module/`。
> 状态图例：✅ 已裁定（不必再问）· ⏳ 待你拍板 · 🔧 技术自决（我按仓内先例定，你随时可否）
>
> ★★ **2026-10-15 23:11 复核已落定**：控制方复核决定全文 = `/home/cna/SimulatorMosire/docs/superpowers/reports/2026-10-15-d031-side-line-review-decisions.md`。
> **下文 M4 / M15 / M16 / M2 / M6 / M12 / M13 / M5 一律以该文件为准，不再是"待拍板"**：
> **M4** 最终归前置模块已定；本轮接口先行、旧定义暂留经济但**须标"尚未迁移"**，后续 production 是定义与注册的**唯一权威写者**（迁移定义 ≠ 搬运行实例/劳动/账户/债务）。
> **M15/M16** 两个模块 `simos-culture` / `simos-production`，**暂不拆 api+impl**；生产对外契约放生产模块公开契约包；现有底层 ID 复用 `economy-api`；**经济允许只读依赖生产模块**（"经济永不依赖前置生产"是侧线自加约束，**作废**）；禁止 `production → economy` 实现与任何领域 → `app`。
> **M2** 生产加成与信用/利息**不互斥**：本轮聚焦生产熟练度，复杂宗教信用延期、旧需求保留；不预建万能修正框架。
> **M6** **保留**零成员 Social 家户与零人口经济壳行；残留财债校验维持，**不得为删户而清账**；D-022 的"删除"出自代理整理条款（逐字原话只要求不得原地改生产方式）。
> **M12/M13** 接口完成与效果接通**分开验收**：本轮可只有中性默认路径，但**不得以恒 1000‰ 声称效果已生效**；派生倍率**不另存第二张权威表**。
> **M5** 按市场结算周期执行流动；spec 分开写"逐 tick 评估 / 周期执行 / 追加轮"，且**不得称旧双机制已被替换**。
> 机械清单纠正（复核 §3）：`TimeParticipant` **按需**、不为计数造空推进器；模块清单/enforcer/往返守卫/扫描根按**实际能力**同步；**修改数量不决定架构**。

## 0. 汇总

| # | 一句话矛盾 | 状态 | 谁定 |
|---|---|---|---|
| M1 | 文化状态塞进 `EconomyData` / actor / 组织实体 —— 还是新建「文化与基层治理」模块 | ✅ | 用户（D-031） |
| M2 | 文化效果＝**信用/利息（riba）**（10-04 稿）vs 文化效果＝**生产熟练度加成表**（D-031） | ⏳ | 用户 |
| M3 | 文化状态归 `EconomyData` vs 归文化模块自己 | ✅ | 用户（D-031 补充） |
| M4 | 生产方式**定义**留 `simos-economy` vs 包给统一前置模块 | ⏳ | 用户（= R1 甲/乙/丙） |
| M5 | 流动表「逐 tick 算」vs「与市场同周期执行」 | ⏳ | 用户已定一半；余下进设计书 |
| M6 | 空户「清零后从家户表删除」（D-022）vs 实现「始终保留 0 人口壳行」（P0 2026-10-10） | ⏳ | 用户（权威顺序冲突） |
| M7 | 家户成员 `List<PeopleLotId> memberLots`（10-09 稿）vs 实现 `Map<PeopleLotId,Long> members`（份额语义） | ✅ | 🔧 以代码为准 |
| M8 | `metadata` 当文化挂点 vs「metadata 只存文本，不能算文化已实现」 | ✅ | 🔧 以调查报告为准 |
| M9 | 「各模块只通过 `social-api` 引用 Social」vs「后端业务模块都允许大方依赖 `simos-social`」 | ✅ | 同日两份，以后者 + pom 为准 |
| M10 | 术语撞车：「方案 3 延后」指的是 SPI 贡献者，不是宿主 A/B/C | ✅ | 🔧 引用时改写措辞 |
| M11 | 文件名日期与提交日期/正文日期互不吻合（两处） | ✅ | 🔧 两存标注（`docs/AGENTS.md` §五.3） |
| M12 | 预期利润「看不到修正参数」（批内接受）vs「文化/技术必须进同一预期口径」 | ⏳ | 用户（长期方向；本轮只立接口） |
| M13 | 效率注入契约已存在但**无任何生产者** | ✅ | 🔧 文化成为首个生产者 |
| M14 | 调查报告 §3.3「市场轮后的借粮」vs §9.1「借粮腿已退役（**自更正**）」 | ✅ | 🔧 以 §9.1 为准 |
| M15 | 「只立接口」vs 本仓「加模块 ⇒ 必然回改 15 个 pom + 5 处测试清单（2 处静默）」 | ⏳ | 用户（= R2 模块数：2 还是 4） |
| M16 | 新模块的 Maven 名称 / 契约包 / 允许依赖图「尚未裁定」 | ⏳ | 用户认可（我给默认） |

---

## M1 · 文化的宿主

- **A 说**（旧稿给了三个候选，未定）：`/home/cna/SimulatorMosire/docs/superpowers/plans/2026-10-04-household-culture-and-community-effects-plan.md:48-51` ——「方案 A：`EconomyData` 新增一张 `Map<HouseholdId, HouseholdTraits>`」「方案 B：actor 侧 `Actor` 增加文化/组织成员属性」「方案 C：基层组织做成 `ActorKind.ORGANIZATION` 实体」。
- **B 说**（新裁定）：`/home/cna/SimulatorMosire/docs/superpowers/specs/2026-10-02-architecture-design-source.md:504` 用户逐字「引入……家户的文化模块，我希望命名为**"文化与基层治理"**」；`/home/cna/SimulatorMosire/docs/superpowers/reports/2026-10-15-household-mobility-culture-production-investigation.md:127` 逐字「**旧候选宿主 A/B/C 不应重新当成已定边界**」。
- **裁定项**：A/B/C 作废，新模块承载。**已定，不再问**。
- **状态**：✅ 用户已裁定。

## M2 · 文化效果的维度（唯一"未撤回但已非主目标"的需求）

- **A 说**：`…/2026-10-04-household-culture-and-community-effects-plan.md:7-8` 用户 2026-10-04 原话举例「如果这个家户是穆斯林，那么放贷额度 −90%」；同文件 `:59-67` 列出的集成点全是**信用/利息**（`MarketSettlement.CreditPools.build`、`EconomySettlement.lendableOf`、`DebtContract.terms`、`chargeInterest`）；`:82` 逐字「任何涉及宗教合规性的验收都不能算通过」。
- **B 说**：`…/2026-10-02-architecture-design-source.md:505` 用户 2026-10-15 逐字「这个模块首先和经济相互作用的地方很简单，那就是这个家户的**生产熟练度**……维护一张给特定生产方式加加成的表」；`docs/superpowers/specs/2026-10-23-production-efficiency-framework.md:286` 把「文化/宗教/基层组织字段与 SPI 贡献者」列为**延后**。
- **事实**：riba/信用维度在 D-031 三段原话里**一次未出现，也从未被明文废除**，仍挂在未完成清单。
- **裁定项**：① 本轮不实现、**接口不挡死**（我的默认）；② 明确废除并记档；③ 本轮就要（不建议）。
- **我的建议**：①。落法 = 文化输出契约做成**具名、可叠、可溯源、带作用域**的修正值表（旧稿 `:52` 原话"具名、可叠、可读的修正值，不要散落 if"），本轮**只实现 `productionAptitude` 一个维度**，信用/利息维度留为**新增枚举值即可**的兼容扩展。
- **状态**：⏳ 待你一句话。

## M3 · 文化状态放哪

- **A 说**：旧稿方案 A 把文化标签塞进 `EconomyData`（见 M1 A 侧）。
- **B 说**：`…/2026-10-02-architecture-design-source.md:532` 逐字「**文化领域状态由文化模块自己持有与落账**」。
- **裁定项**：文化状态归文化模块（自家 `Snapshot` + `Codec` + `ChangeSet`）。**已定**。
- **状态**：✅ 用户已裁定。

## M4 · 生产方式"定义"的所有权（= 你上一轮问的 R1）

- **A 说**（现状 + 旧契约的隐含前提）：定义住在 `simos-economy` **实现**模块 —— `/home/cna/SimulatorMosire/simos-economy/src/main/java/io/mosire/simos/economy/model/Industry.java:126-144`（且**一个 record 里混着定义字段与运行实例字段**）；`…/2026-10-23-production-efficiency-framework.md:289` 把产业模板默认归 economy。
- **B 说**：`…/2026-10-02-architecture-design-source.md:513` 用户逐字「我还是认为应该把生产方式（其实就是产业，生产方式决定生产产出嘛）**包给一个统一的前置模块**」。
- **代价实测**：`/home/cna/SimulatorMosire/simos-economy/src/main` 有 **40 个文件**引用 `ProductionMode|ProductionRecipe`（本侧线 `grep -rl` 计数）。
- **裁定项**：甲（只立契约、定义暂留 economy，只读口进 `simos-economy-api`）／乙（定义上移新模块，照 2026-10-09 `simos-social-api` 先例）／丙（生产侧只出设计、下轮再建模块）。
- **我的建议**：**甲**（= 你说的"只需要把接口立好"），**乙**单独开一批并先出迁移依赖图与唯一写者。
- **状态**：⏳ 待你拍板。

## M5 · 调度周期：逐 tick 建表 vs 与市场同周期

- **A 说**：`…/2026-10-02-architecture-design-source.md:492`（用户首轮原话）「对于每一个家户对象，**在单 tick 运行的时候**，都算一张阶层流动表」。
- **B 说**：同文件 `:513`（第二次补充原话）「阶层滑落和家户间转业应该**和市场同步在同一个周期**做」；`:517`（补充裁定第 1 条）逐字「首次原话"逐 tick 算表"与本次"同市场周期执行"的调度关系，须在周期设计中明确，**不能擅自解释为全部业务逐日执行**」。
- **现状事实**：市场并非单一固定周期 —— `/home/cna/SimulatorMosire/simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java:1400-1423`（例行 `day%5==0` → `anyCycleClosed` 保底 → 第 3 天缺粮追加）。
- **裁定项**：半条已定（同周期）；「逐 tick 建表 vs 同周期执行」的关系**属设计书要写清的**（本轮流动表只出设计，不实现）。
- **状态**：⏳ 半待设计（已列入调查 §9.2 五组语义）。

## M6 · 空壳户：用户原稿说"删"，实现说"永远保留"

- **A 说**：`…/2026-10-02-architecture-design-source.md:366`（D-022 裁定第 5 条）逐字「源家户人口清零后是**从家户表删除**，不是被改造成目标 mode」。
- **B 说**（实现里挂着另一个带日期的决定）：`/home/cna/SimulatorMosire/simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java:684-687` 逐字「**P0（2026-10-10）：始终保留 0 人口壳行** ……一律不删」；调查报告 `:102-103` 也记了这处漂移「后续设计应显式规定空户及剩余合同的生命周期」。
- **性质**：**用户设计原稿（最高权威）vs 更晚的内部 P0** —— 权威顺序与时间顺序反向，不能自行判谁赢。
- **裁定项**：① 以 D-022 为准，改成删除；② 追认 P0（保留壳行）并改写 D-022 相应措辞；③ 按户型分别定（例如有合同/资产的保壳、干净的删）。
- **状态**：⏳ 待你拍板（可与 M4 同批）。

## M7 · 家户成员的形状

- **A 说**：`/home/cna/SimulatorMosire/docs/superpowers/specs/2026-10-09-social-api-household-architecture.md:62-68` 写 `List<PeopleLotId> memberLots`，`:70` 逐字「`memberLots` 是**唯一成员关系**」。
- **B 说**（实现）：`/home/cna/SimulatorMosire/simos-social/src/main/java/io/mosire/simos/social/household/Household.java:56-60` = `Map<PeopleLotId, Long> members`（**份额**语义）；调查报告 `:23` 逐字「同一批人口可按人数份额**属于多个家户**」。
- **裁定项**：以代码为准，接口按**份额**设计；`memberLots` 之名只活在读侧 `HouseholdView`。
- **状态**：✅ 🔧 技术自决（文档陈旧，已回代码核）。

## M8 · `metadata` 能不能当文化挂点

- **A 说**：`…/2026-10-09-social-api-household-architecture.md:45` 给出 `HouseholdProfile(name, description, metadata: Map<String,String>)`，旧稿把它当文化落点。
- **B 说**：调查报告 `:32` 逐字「但 metadata 仅能存文本，**不能算"文化已实现"**；现有程序没有消费文化标签的规则」。
- **裁定项**：文化另立状态；`metadata` 保持"自由字符串"语义不变。
- **状态**：✅ 🔧 已定。

## M9 · 后端业务模块能不能直接依赖 `simos-social`

- **A 说**：`…/2026-10-09-social-api-household-architecture.md:14`（早期口径）「Unit/Gov/Economy/Culture/Religion **只依赖契约层**消费」。
- **B 说**（同日更晚一份推翻它）：`/home/cna/SimulatorMosire/docs/superpowers/specs/2026-10-09-social-direct-boundary-and-substantive-naming.md:4` 逐字「① 选方案 A：`simos-social-api` 可以留着，但后面**后端业务模块都应允许大方依赖 `simos-social`**」；pom 实测 `simos-unit`/`simos-economy` 均已显式声明 `simos-social`。
- **裁定项**：以后一份 + pom 为准。**对新模块的落地含义**（我按此定，可否）：`simos-culture` 允许声明 `simos-social`（取家户/人口），但**只读**、不写别人状态；`simos-production` 不需要 social。
- **状态**：✅ 已定（新模块边界按此写）。

## M10 · 高危术语撞车（会导致误读）

- **A 说**：`/home/cna/SimulatorMosire/docs/superpowers/specs/2026-10-23-production-efficiency-framework.md:286` 逐字「文化/宗教/基层组织字段与 SPI 贡献者（**方案 3 延后**）」。
- **B 说**：2026-10-04 旧稿里的"方案 A/B/C"是**宿主候选**（见 M1）。
- **性质**：**同名不同物** —— 前者指"效率注入 SPI 贡献者"，与宿主候选毫无关系。
- **裁定项**：新设计引用此句**必须改写措辞**（例如写"该批把 SPI 贡献者延后"），否则会被读成"文化模块方案三被延后"。
- **状态**：✅ 🔧 已记入溯源（`…/2026-10-15-d031-interface-first-scope-trace.md` §三·补）。

## M11 · 日期口径矛盾（`docs/AGENTS.md` §五.3 要求"两存并标疑点"）

| 文档（绝对路径） | 文件名日期 | 实际提交 | 正文自称 |
|---|---|---|---|
| `/home/cna/SimulatorMosire/docs/superpowers/specs/2026-10-23-production-efficiency-framework.md` | 2026-10-23 | **2026-10-07T04:40**（`db4f899d`）★晚 16 天 | 用户已裁定并授权派单（2026-10-23） |
| `/home/cna/SimulatorMosire/docs/superpowers/specs/2026-10-09-social-api-household-architecture.md` | 2026-10-09 | **2026-10-05T01:09**（`37f0a900`）★晚 4 天 | 用户 2026-10-08 最终裁定 |
| `/home/cna/SimulatorMosire/docs/superpowers/plans/2026-10-04-household-culture-and-community-effects-plan.md` | 2026-10-04 | 2026-10-04T23:17（`fb1c392c`）✔吻合 | 2026-10-04 |

- **裁定项**：引用时**两存标注**；**判断"谁更新"只看提交时间与正文，不看文件名**。特别是效率框架那份**早于 D-031**，不能用它反推 D-031 之后的边界。
- **状态**：✅ 🔧 已按此写进溯源。

## M12 · 预期利润要不要看见文化/技术修正

- **A 说**（批内接受、记为已知边界）：`…/2026-10-23-production-efficiency-framework.md:362`「GM 覆盖只作用于生产路径：`ExpectedProfitBook` 等只读预期仍读配方默认值（避免本批扩面）」、`:371`「规划读数（劳动/市场/债务/预期利润）……**看不到修正参数/GM 覆盖**」。
- **B 说**：调查报告 `:162` 逐字「`ExpectedProfitBook` 当前也没有文化/效率修正输入；仅在收获结算加成，会使"**实际赚钱**"与迁移决策的"**预期赚钱**"脱节」；`:125`「新文化/技术条件也须进入**同一预期口径**」。
- **裁定项**：长期方向（文化/技术是否进预期利润）。**本轮只立接口**，我建议在契约里**预留修正输入位**（不实现计算），避免下次改形状。
- **状态**：⏳ 待你认可"本轮只留位"这一处理。

## M13 · 修正通道有契约、没有生产者

- **事实**：契约在 `/home/cna/SimulatorMosire/simos-economy-api/src/main/java/io/mosire/simos/economy/api/production/ProductionEfficiencyModifier.java`（`(unit, perMille∈[0,2000], source, reason)`），注入口 `EconomyDayStepper.updateProductionModifiers`；**实测全仓只有测试在调它**，没有任何业务生产者。
- **设计含义**（会影响文化状态形状）：该契约的确定性要求是**"机制给出的值必须是已持久化状态的纯函数"** ⇒ 文化的熟练度表**必须落盘**（不能瞬态），否则回放不收敛。
- **裁定项**：文化模块成为第一个生产者；文化状态持久化 + 纯函数派生。
- **状态**：✅ 🔧 技术自决（已写入不变量）。

## M14 · 调查报告的自更正

- **A 说**：调查报告 `:84` 把"借粮"列为市场轮之后的后续步骤。
- **B 说**：同文件 `:271` 逐字更正「当前 `EconomySettlement.java:2745` 起明确退役独立借粮腿；……原文保留，追加更正」。
- **裁定项**：引用时以更正节为准。
- **状态**：✅ 🔧 已记。

## M15 · 「只立接口」vs 本仓的机械代价

- **A 说**（你的目标）：本轮不做复杂效果，只把接口立好。
- **B 说**（机械规则，实测）：`/home/cna/SimulatorMosire/docs/agents/01-〇-铁律与模块边界.md:80` 逐字「**禁列 = 全仓 simos 模块 − 自己 − 允许面，零例外**」⇒ **每新增 1 个模块，其余 15 个模块的 pom 都要回改**（各补 1 条 `<exclude>` + 改 ban 文案；`simos-app` 不设 enforcer）；另加 5 处测试清单，其中 **2 处漏了是静默的**（`/home/cna/SimulatorMosire/simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java:2352-2365` 逐字「不补就是"扫描器静默"」；`/home/cna/SimulatorMosire/simos-core/src/test/java/io/mosire/simos/core/ArchitectureGuardsTest.java:96-98` 逐字"不进 MODULES 就**不会红、不会提示**"）。
- **换算**：2 模块 ≈ 30 条 exclude；4 模块（两对 api+impl）≈ 60 条 + 4 条 module 行 + 4 条 dependencyManagement。
- **裁定项**：R2 —— 走 **2 个 impl-only 模块**（`simos-culture` + `simos-production`，照 `simos-gov`/`simos-army`/`simos-unit` 先例）还是 **4 模块**（每领域 api+impl）。两种都能"立接口"：跨模块契约本就该住**上游 api 模块**（文化→经济走既有 `ProductionEfficiencyModifier`；生产定义的只读口加进 `simos-economy-api`）。
- **我的建议**：**2 模块**；等出现"非 app 的第二个消费者"再升 api（纯增量）。
- **详细清单**：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module/.agents/notes/proposed/architecture/2026-10-15-new-module-mechanical-checklist.md`
- **状态**：⏳ 待你认可。

## M16 · 新模块的命名/包/依赖图

- **A 说**：调查报告 `:195` 逐字「新模块的 Maven 名称、契约包及允许依赖图**尚未裁定**」。
- **我的默认**（可否）：`simos-culture`（包 `io.mosire.simos.culture`，`namespace()` = `"culture"`）+ `simos-production`（包 `io.mosire.simos.production`，`namespace()` = `"production"`）；依赖面：culture → social + economy-api（取 `ProductionModeId` 等 ID）；production → util + economy-api；**经济永不反向依赖**；组合根 `simos-app` 显式声明两者。
- **状态**：⏳ 默认走，可否。

---

## 附 A · 本文引用的全部文档（绝对路径 · 已被本侧线读过的）

**用户权威与调查**
1. `/home/cna/SimulatorMosire/docs/superpowers/specs/2026-10-02-architecture-design-source.md` —— **用户设计原稿（最高权威）**；`D-022` `:350-367`、`D-023` `:369-394`、`D-024` `:396-407`、**`D-031` `:487-534`**（三段：`:487` 首轮、`:509` 补充一、`:522` 补充二）。
2. `/home/cna/SimulatorMosire/docs/superpowers/reports/2026-10-15-household-mobility-culture-production-investigation.md` —— 314 行现状调查（口径溯源表 `:119-133`、文化现状 `:135-162`、生产现状 `:164-181`、建议 `:183-220`、周期事实 `:250-290`、工单基础 `:292-314`）。
3. `/home/cna/SimulatorMosire/POLITICAL_ECONOMY_DESIGN.md` —— 761 行理想架构；切片与所有权 `:332-346`、`HouseholdId` 是"账户、债务、**文化扩展点**" `:387`。

**旧三稿（含日期疑点）**
4. `/home/cna/SimulatorMosire/docs/superpowers/plans/2026-10-04-household-culture-and-community-effects-plan.md`（98 行，宿主 A/B/C `:48-51`、集成点 `:59-67`、开放问题 `:84-91`、非目标 `:93-98`）
5. `/home/cna/SimulatorMosire/docs/superpowers/specs/2026-10-23-production-efficiency-framework.md`（394 行 ★文件名晚 16 天；写口矩阵 `:69-81`、`ProductionEfficiencyModifier` `:146`、注入口 `:155`、延后九条 `:284-293`、已知边界 `:362`/`:371`）
6. `/home/cna/SimulatorMosire/docs/superpowers/specs/2026-10-09-social-api-household-architecture.md`（160 行 ★文件名晚 4 天；契约上移先例 `:37-56`）
7. `/home/cna/SimulatorMosire/docs/superpowers/specs/2026-10-09-social-direct-boundary-and-substantive-naming.md`（推翻 6 的口径，`:4`）

**机械约束**
8. `/home/cna/SimulatorMosire/docs/agents/01-〇-铁律与模块边界.md`（边界表全文 + `searchTransitive` 闭合口径 `:74-84`）
9. `/home/cna/SimulatorMosire/docs/AGENTS.md`（口径优先级 + 日期口径规则 §五.3）
10. `/home/cna/SimulatorMosire/tools/gate.sh`（`GUARDS_TABLE:94-99`、`claim_guard_sources:509-522`）
11. `/home/cna/SimulatorMosire/simos-core/src/test/java/io/mosire/simos/core/ArchitectureGuardsTest.java`（`MODULES:25-42`、判据 `:117-119`、静默警告 `:96-98`）
12. `/home/cna/SimulatorMosire/simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java`（handler 扫描根 `:2356-2365`）

**代码事实**
13. `/home/cna/SimulatorMosire/simos-economy/src/main/java/io/mosire/simos/economy/model/Industry.java:126-144`（定义与实例混装）
14. `/home/cna/SimulatorMosire/simos-economy/src/main/java/io/mosire/simos/economy/time/ClassSlideSettlement.java:188-249`（滑落只改位置、不搬人）
15. `/home/cna/SimulatorMosire/simos-economy/src/main/java/io/mosire/simos/economy/time/MarketSettlement.java:1400-1423`（市场触发优先级）
16. `/home/cna/SimulatorMosire/simos-economy/src/main/java/io/mosire/simos/economy/time/ModeMigrationSettlement.java:684-687`（0 人口壳行 P0）
17. `/home/cna/SimulatorMosire/simos-social/src/main/java/io/mosire/simos/social/household/Household.java:56-60`（成员＝份额）
18. `/home/cna/SimulatorMosire/simos-social/src/main/java/io/mosire/simos/social/workorder/HouseholdWorkOrderPlan.java:76-83`（工单封闭步骤集，无文化阶段）
19. `/home/cna/SimulatorMosire/simos-util/src/main/java/io/mosire/simos/util/state/SimulationState.java:14`（世界状态按 namespace 挂 Snapshot）
20. `/home/cna/SimulatorMosire/simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java:184-199`（codec/participant 注册与 namespace 唯一性）
21. `/home/cna/SimulatorMosire/simos-economy-api/src/main/java/io/mosire/simos/economy/api/production/ProductionEfficiencyModifier.java`（唯一程序内修正契约）

**本侧线自己的笔记（都在工作树里）**
22. `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module/.agents/notes/proposed/architecture/2026-10-15-d031-interface-first-scope-trace.md`（口径溯源 11 条 + 开放 6 条 + 不变量 5 条）
23. `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module/.agents/notes/proposed/architecture/2026-10-15-new-module-mechanical-checklist.md`（新增模块的机械清单与代价）
24. `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module/.agents/notes/proposed/architecture/2026-10-15-contradiction-to-ruling-list.md`（**本文件**）
