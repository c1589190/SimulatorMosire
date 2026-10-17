# D-031 接口先行 实现计划（culture / production / 经济挂点）

> **当前进度（2026-10-16）**：Z5→Z2→Z3→Z1→Z4→Z6 均已完成，V0–V5 已通过，主树完整门禁3422/0/0/7、前端412/412；未提交/推送。实际验收与未做项见 `docs/superpowers/status/2026-10-16-d031-interface-first-result.md`。下文未勾选清单、暂停提示及“计划未执行”是原始计划留痕，当前进度以本条和结果文档为准。

> **恢复执行（2026-10-16）**：用户原话「现在你可以继续你手头上的工作了」。暂停解除；Z5 已交编译报告，继续派 Z2。所有构建按最新规则从侧线自己的包装器执行，以下暂停提示与旧构建命令保留作历史。

> **同日再次更新（2026-10-16，当前执行口径）**：已同步 `03faf0a3`。以下旧锁说明及任务内从主树 `-f` 调用的命令，以 `docs/superpowers/reports/2026-10-16-d031-worktree-coordination.md` §5 为准：从侧线目录调用本树包装器，每树独立 `.m2-repo` 与锁，同树串行、跨树可并行，`-pl` 仍带 `-am`。主树唯一入账口不变；开发任务继续暂停。

> **执行规则更新（2026-10-16）**：Global Constraints 第 4 条及任务内旧锁解释以 `docs/superpowers/reports/2026-10-16-d031-worktree-coordination.md` §2 为准：侧线已包含项目共享锁提交，树内脚本不再是另一把锁；沙箱权限不足须结束并上报，不能无锁编译或无限重试。主树为唯一提交/完整门禁/推送入口。开发派单仍暂停，不因同步规则自动恢复。

> **执行方式（本仓纪律优先于通用 TDD 流程）**：按**责任区**派单，一个责任区 = 一个写代码代理，**生产代理只写到"编译过"**（`-DskipTests compile` rc=0），**不写不跑 Java 测试**；所有 Java 测试清单/测试代码/变异统一交 **Z6 测试代理**；控制方负责调度与验收。
> 本计划里的命令**是各任务自证用的编译/边界命令**，**不构成"每个小任务跑一轮测试"**（复核 §6 第 7 条）。
> **Goal**：在不动现有数值行为的前提下，把「文化与基层治理」「生产与科技」两个模块的**契约、状态、命令与接线点**立起来，并把生产定义的迁移计划钉死。
> **Architecture**：两个新模块（`simos-culture` / `simos-production`，各带公开契约包）+ `economy-api` 两个只读口 + economy 侧临时适配器（标"尚未迁移"）+ app 组合接线（**无条件空注入**）。
> **Tech Stack**：Java 21、Maven 多模块（根 pom 16 → 18 模块）、Jackson、maven-enforcer。
> **Spec**：`docs/superpowers/specs/2026-10-15-d031-interface-first-design.md`（执行者必读；本计划的每个任务都从它取形状）。

## Global Constraints（每个任务隐含包含）

1. **三处"未完成"标记**必须留在代码类注与文档里：定义尚未迁移 / 真实效果未启用 / 旧双流动机制未替换。
2. **不写不跑 Java 测试**（生产代理）；**不擅自 `git commit`**；**不动隔壁在飞文件**。
3. 编译自证一律 `-DskipTests compile`（**不是 `test-compile`**；修正 D）。运行时**不新增激活位/开关/profile**；P0 真实循环空注入（修正 A）。
4. Maven 一律走**主仓锁 + `-f` 指本工作树**：`cd /home/cna/SimulatorMosire && tools/mvn-lock.sh -f .claude/worktrees/parallel-side-module/pom.xml …`。**主锁路径须在 Z5 用最小命令实测并记 rc**（修正 E）；**该锁策略不擅自扩张到其他独立项目**；本工作树自带的 `tools/mvn-lock.sh` 是**另一把锁**，本批不使用。
5. **每文件一个 owner**（修正 D）：**Z2/Z1/Z3/Z4 任何一个都不得改 root `pom.xml` 或同个领域 `pom.xml`**；全部 POM/边界文档由 **Z5 单一责任区先一次做完**，再按依赖顺序编译，避免前序编译撞旧 enforcer。
6. 契约值类型字段**只能**取自 `util` / `map` / `economy-api`；**禁**引用 `io.mosire.simos.economy.model.*`。
7. 保序不可变用 `LinkedHashMap` + `Collections.unmodifiableMap`，**禁 `Map.copyOf`**。
8. 依赖方向照 spec §4；`economy-api` **不得**反向引用 production 新类型；**economy 只 import production 的 `contract` 包**（不 import 具体目录、不解析 `IndustryId` 猜产业种类；修正 C）。
9. 日志：每模块一个 `XxxLog` 门面（logger 名唯一拼写点）+ `XxxLogSource`；业务拒绝 = INFO、契约故障 = ERROR。

## Review Focus（最可能咬人的八件事；各自有任务里的负向用例）

1. **中性被当成"效果已生效"** —— 空列表 ≠ 注入恒 1000‰；**即使填了非中性规则，P0 真实循环也不得启用**（修正 A）。
2. **缺技能被当成产业不存在** —— `IndustryDefinitionView` 必须照常返回完整真实定义，`skill` 可 `Optional.empty()`（修正 B）。
3. **公开契约引用经济实现类**或**economy import production 具体目录类**（`ProductionSkillCatalog`）⇒ 依赖环 / 包边界红（修正 C）。
4. **定义视图混入运行实例字段**（`operator/progressDays/capacity/cycleLaborMilli/cycleInputUsedMilli`）⇒ 快照抖动、语义混乱。
5. **契约包被绕过**：外部模块 import `io.mosire.simos.production` 的非 `contract` 包 ⇒ 边界名存实亡。
6. **依赖环**：`economy-api` 反向引用 production 新类型 ⇒ `production → economy-api → production`。
7. **技能键不稳**：拿 `IndustryId`（可能带位置语义）当跨地区技能键 ⇒ 跨地区规则错配。
8. **静默面**：模块清单 / handler 扫描根未同步 ⇒ 新模块的实现面**没人守**。

---

## 任务 0（Z5）· 机械面先行：全部 POM 与边界表（**单一 owner；先于任何编译**）

> ★ 修正 D/E：POM/允许面/禁列收敛必须在受影响编译前到位。此任务**不拥有任何 Java 文件、不拥有任何测试文件、不拥有 `tools/gate.sh`**；`tools/gate.sh` 的 GUARDS_TABLE 归 Z6。

**Files**
- Modify: root `pom.xml`（`<modules>` 加 `simos-production`、`simos-culture`，api 不拆；`<dependencyManagement>` 加两条坐标）
- Create: `simos-production/pom.xml`、`simos-culture/pom.xml`（抄 `simos-ga-spi/pom.xml:1-93` 形制；各自允许面见 spec §4；自带 enforcer 块 `<searchTransitive>false</searchTransitive>`；此时只有 pom、无 Java）
- Modify: **其余 15 个模块**的 `pom.xml`：`<bannedDependencies><excludes>` 各补 `io.mosire:simos-culture`、`io.mosire:simos-production` 两条（**除允许面外的每一个**），并把 ban `<message>` 文案同步成"只许依赖谁"；`searchTransitive=false` 原样保留
- Modify（**允许面变更的显式声明，"声明即允许"**）：`simos-economy/pom.xml` 加 `<dependency>io.mosire:simos-production</dependency>`（注释写明"由 2026-10-15 复核决定放行；只读 contract；禁反向"）；`simos-app/pom.xml` 加两个新模块（app 不设 enforcer）；两个新模块 pom 内的允许面 `<dependency>` 同批写齐
- Modify: 根 `AGENTS.md`（边界表加两行 + 允许面）、`docs/agents/01-〇-铁律与模块边界.md`（全表加两行 + 允许面注记）

**证据（不用测试）**
- [ ] **步骤 1（主锁路径实测，修正 E）**：先用一条最小命令确认"主仓锁 + `-f` 本工作树"真实可复现并记 rc：
  `cd /home/cna/SimulatorMosire && tools/mvn-lock.sh -f .claude/worktrees/parallel-side-module/pom.xml -q -DskipTests compile -pl simos-util -am`
  **期望** rc=0；把命令原文与 rc 写进台账，并写明锁作用域=本仓、不扩张到其他独立项目。
- [ ] **步骤 2**：改/建 POM 与两处边界文档。
- [ ] **步骤 3**：编译自证（空模块也应可编译）：
  `cd /home/cna/SimulatorMosire && tools/mvn-lock.sh -f .claude/worktrees/parallel-side-module/pom.xml -q -DskipTests compile -pl simos-culture,simos-production -am`
  **期望** rc=0。
- [ ] **步骤 4**：`… tools/mvn-lock.sh -f …/pom.xml -q validate` **期望** rc=0。
- [ ] **步骤 5**：闭合自证（数字要带命令）：
  `grep -c "<exclude>io.mosire:simos-culture</exclude>" simos-*/pom.xml`、`… simos-production …` —— 逐模块输出与"允许面白名单"逐条对齐后写进台账。

## 任务 1（Z2）· `simos-production` 全部生产代码（**只 Java；先于 Z3/Z1/Z4**）

**Files**
- Create: `simos-production/src/main/java/io/mosire/simos/production/contract/`：`ProductionSkillKey`、`ProductionSlotView`、`AllocationRuleView`（sealed: `SplitView`/`WageFirstView`）、`MoneyOutputView`、`IndustryDefinitionView`（`skill` 为 `Optional<ProductionSkillKey>`）、`ProductionModeDefinitionView`、`RecipeDefinitionView`、`ProductionDefinitionLookup`、**`ProductionSkillLookup`**、`TechnologyId`、`TechnologyDefinition`、`MasteryScope`、`TechnologyMastery`、`TechnologyLookup`、`package-info.java`
- Create: `ProductionData.java`、`ProductionSnapshot.java`（`implements Snapshot`，`namespace()` = `"production"`）、`codec/ProductionCodec.java`（`ModuleCodec` + `ModuleDiffer`）、`change/ProductionChangeSet.java`、`spi/RegisterTechnologyHandler.java`（`type()` = `"production.RegisterTechnology"`）、`spi/SetMasteryHandler.java`（`"production.SetMastery"`）、**`skill/ProductionSkillCatalog.java`（非 contract 包，`implements ProductionSkillLookup`）**、`ProductionLog.java`、`ProductionLogSource.java`、`package-info.java`
- 形状照 `simos-gov` 的同名件（`GovSnapshot.java:18-36`、`GovCodec.java:64/135-140/181/206`、`GovChangeSet.java:49-57`、`CreateNationHandler.java:48-55`）

**Interfaces**
- Produces（供 Z3/Z1/Z4 消费）：`ProductionDefinitionLookup.industry(IndustryId) → Optional<IndustryDefinitionView>`（**真实定义存在即返回**，`skill` 可 empty）、`.mode(...)`、`.recipe(...)`；`ProductionSkillLookup.keyOf(IndustryId) → Optional<ProductionSkillKey>`；`ProductionSkillKey(String)`；`IndustryDefinitionView` 的**全部定义字段**（含 `dailyLaborPerUnit`、`moneyOutputPerUnit`）。字段表见 spec §6.1，逐字照抄不得增删。
- ★ `ProductionSkillCatalog` 只住本模块内部（非 `contract`）；**未命中 ⇒ empty，不猜、不解析 id 文本**；P0 内置默认表覆盖现存产业种类 + 显式覆盖表。

- [ ] **步骤 1**：写 contract 包 + 状态/编解码/变更集/两个 handler/技能目录/日志门面。
- [ ] **步骤 2**：编译自证。
  `cd /home/cna/SimulatorMosire && tools/mvn-lock.sh -f .claude/worktrees/parallel-side-module/pom.xml -q -DskipTests compile -pl simos-production -am`
  **期望**：rc=0；输出无 `ERROR`。
- [ ] **步骤 3**：把"契约与生产代码已可编译"的 rc 与命令原文写进 `.superpowers/sdd/<topic>/impl-ledger.md`（本仓台账形制）。

## 任务 2（Z3）· `economy-api` 只读口 + economy 适配器（**只 Java；注入契约，不 import 具体目录**）

**Files**
- Create: `simos-economy-api/src/main/java/io/mosire/simos/economy/api/production/HouseholdLaborShare.java`、`UnitProductionFacts.java`、`ProductionLaborLookup.java`
- Create: `simos-economy/src/main/java/io/mosire/simos/economy/production/EconomyProductionDefinitionLookup.java`（`implements ProductionDefinitionLookup`，**构造注入 `ProductionSkillLookup`**；**类注逐字标"★ 尚未迁移：定义权威仍在 economy，P1 搬入 simos-production"**）、`EconomyProductionLaborLookup.java`（`implements ProductionLaborLookup`，从 `allocations()` 现算，`activity` = unit id）
- ★ **不 import** `io.mosire.simos.production.skill.ProductionSkillCatalog`（具体目录由 app 装配注入；修正 C）；**不解析 `IndustryId` 猜种类**。
- `simos-economy/pom.xml` 对 `simos-production` 的显式声明由 Z5 统一改，本任务不改 pom。

**Interfaces**
- Consumes：任务 1 的 **contract 包**（`IndustryDefinitionView` / `ProductionSkillLookup`）。
- Produces：`ProductionLaborLookup.factsOf(ProductionUnitId) → Optional<UnitProductionFacts>`；`EconomyProductionDefinitionLookup`（供 app 装配）。
- ★ 缺技能：把 `skill` 填 `Optional.empty()`，视图**照常返回**（修正 B）；不得 `industry(id)` 返回 empty。

- [ ] **步骤 1**：写三个契约记录 + 两个适配器。
- [ ] **步骤 2**：`… -q -DskipTests compile -pl simos-economy -am` **期望** rc=0（同时验证"economy 认得 production 契约"）。
- [ ] **步骤 3**：台账记 rc。

## 任务 3（Z1）· `simos-culture` **完整**生产代码（契约 · 状态 · 命令 · 合成服务；**同一责任区，不拆两次写者**）

> ★ 修正 E：原计划的 Z1-a（契约/状态/命令）与 Z1-b（合成服务）是**同一 culture 责任区**，合并为本任务，一次写完。

**Files**
- Create: `io/mosire/simos/culture/contract/`：`CultureId`、`ReligionId`、`CustomTag`、`CultureTag`、`HouseholdCulture`、`ProductionAptitudeRule`、`CultureLookup`、`package-info.java`
- Create: `culture/CultureData.java`、`CultureSnapshot.java`、`codec/CultureCodec.java`、`change/CultureChangeSet.java`、`spi/SetHouseholdCultureHandler.java`（`"culture.SetHouseholdCulture"`）、`spi/UpsertAptitudeRuleHandler.java`（`"culture.UpsertAptitudeRule"`）、`spi/RemoveAptitudeRuleHandler.java`（`"culture.RemoveAptitudeRule"`，不存在 ⇒ 具名拒 INFO）、**`aptitude/ProductionAptitudeComposer.java`（+ 其纯函数部件 `AptitudeBlend.java`）**、`CultureLog.java`、`CultureLogSource.java`、`package-info.java`
- `simos-culture/pom.xml` 由 Z5 创建，本任务不改 pom

**Interfaces**
- Produces：`CultureLookup`（`cultureOf(HouseholdId)`、`rulesFor(CultureId)`）；三条命令的 `type()` 常量；`ProductionAptitudeRule(culture, skill, modifierPerMille∈[0,2000], source, reason)`；`ProductionAptitudeComposer.compose(Collection<ProductionUnitId> units) → List<ProductionEfficiencyModifier>`（按 laborMilli 千分加权、无规则家户按 1000 参与、结果 clamp `[0,2000]`、`source="culture.aptitude"`；技能缺失 ⇒ 中性 + 具名原因，不判产业不存在）。
- Consumes：任务 1 的 `ProductionSkillKey`；任务 2 的 `ProductionLaborLookup`（economy-api）；`simos-social-api` 的 `HouseholdId`。
- ★ **合成服务是"可测试纯服务"，但不在 P0 日循环里被调用**（修正 A）；app 只空注入，见任务 4。

- [ ] **步骤 1**：写模块与全部件（规则表可编辑**必须**有增改删三条命令，不得只留硬编码填表路径；composer 公式逐字照 spec §7；**不得**只看经营者文化；**不得**在 app 侧实现公式）。
- [ ] **步骤 2**：`… -q -DskipTests compile -pl simos-culture -am` **期望** rc=0。
- [ ] **步骤 3**：台账记 rc；类注写明"本轮中性、未接入日循环、未启用"。

## 任务 4（Z4）· 组合根接线（小、集中、可机械重放；**只 Java**）

**Files**
- Modify: `simos-app/src/main/java/io/mosire/simos/app/Shell.java`（codec 列表 + 实例 + 注册循环；handlers 列表 + 注册循环 —— 照 `GovCodec`/`CreateNationHandler` 的既有登记点；**装配 `ProductionSkillCatalog` 并注入 `EconomyProductionDefinitionLookup`**）
- Create: `simos-app/src/main/java/io/mosire/simos/app/household/CultureAptitudeBridge.java`（在当日 `step(day)` **之前** **无条件**调用 `updateProductionModifiers(List.of())`；**本轮不调用 `ProductionAptitudeComposer`**，无激活位/开关）
- `simos-app/pom.xml` 由 Z5 统一改，本任务不改 pom

**Interfaces**
- Consumes：任务 1/2/3 的全部产出。
- Produces：接线点（供 Z6 的中性回归与将来的启用批次）。★ 空注入是**显式执行边界**，不是"世界为空所以中性"。

- [ ] **步骤 1**：写 Shell/bridge（装配具体目录 + 注入契约；bridge 空注入）。
- [ ] **步骤 2**：`… -q -DskipTests compile -pl simos-app -am` **期望** rc=0。
- [ ] **步骤 3**：台账记 rc；★ 记下 `Shell.java` 与隔壁线的**潜在合并冲突点**（spec §13 已预警）。

## 任务 5（Z6）· 测试、变异与静默面（**测试代理统一做；唯一拥有测试文件的区**）

**Files**
- Create/Modify：`CultureRoundTripTest`、`ProductionRoundTripTest`（铁律 5；覆盖**全部**状态组件）· 包边界测试（外部只许 import `….contract`，含 **N2：economy import 具体 `ProductionSkillCatalog` ⇒ 红**）· `IndustryDefinitionView` **字段白名单**测试（禁运行实例字段）· 负向 N1–N9（spec §11 V3）· `ArchitectureGuardsTest`/`EconomyVocabularyGuardTest` 的 MODULES · `SimosToolsTest.commandHandlerSourceRoots()` · `ShellAssemblySmokeTest` 计数 · `tools/gate.sh` 的 `GUARDS_TABLE` 各加一行
- ★ 必测（修正 A/B）：
  - **V2③/N8**：先 `culture.SetHouseholdCulture` + `culture.UpsertAptitudeRule(modifier=2000)` 写入非中性规则，再跑真实日循环 ⇒ 读数与"未接线"路径**逐值相同**，接收端只收到空列表（**不能只测空世界**）。
  - **N9**：构造一个真实存在但 `ProductionSkillLookup` 未命中的产业 ⇒ `industry(id)` 仍返回完整 `IndustryDefinitionView`（`skill=empty`），文化贡献中性且具名，**不**判产业不存在。
- [ ] **步骤 1**：按 spec §11 V1/V3/V4 落测试。
- [ ] **步骤 2**：**变异自证**（每条负向：改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5；做不到就如实写"等价存活"）。变异要么**先声明**、要么在独立工作树做（`docs/agents/02-一-并发.md` 新纪律）。
- [ ] **步骤 3**：只跑**受影响模块**：`… -q test -pl simos-culture,simos-production,simos-economy,simos-economy-api,simos-app -am`，记 rc 与用例数。
- [ ] **步骤 4**：把"跑了什么/没跑什么"逐条写进台账（**不许把"没跑"写成"通过"**）。

## 任务 6（控制方）· 验收与合并

- [ ] 隔壁写者**停手后**跑全量 `tools/gate.sh`（含 `-pl … -am` 纪律、`git add` 白名单）。
- [ ] 核对三处"未完成"标记仍在、V0–V5 证据齐、Z4/Z5 的合并冲突已按 spec §13 预案处理。
- [ ] 提交/推送由控制方按 `docs/agents/06-五-提交与协作.md` 执行（**本侧线不擅自 commit**）。

---

## 依赖顺序（硬序，不许并发写同一文件）

```text
Z5（POM/边界表，单一 owner）
  └─→ Z2 production 完整生产代码（compile）
        └─→ Z3 economy-api + 适配器（注入 ProductionSkillLookup；compile）
              └─→ Z1 culture 完整代码（contract/state/commands/composer；compile）
                    └─→ Z4 app 接线（空注入；compile）
                          └─→ Z6 统一 Java 测试与测试清单（含变异）
                                └─→ 控制方全量门禁/合并
```

---

## 未做 / 未验证（计划自身）

- 本计划**未执行**：没有创建任何模块、没有改 pom、没有跑 Maven/测试/变异。**主锁 + `-f` 路径未实测**（为其单列 Z5 步骤 1）。
- 任务 1 的"现存产业种类"清单（`ProductionSkillCatalog` 的默认表）**需在实现时逐条回代码核**（economy 播种处）；未核完不得填表。
- 任务 2 的 `allocations()` 现算路径需在实现时确认**不引入半成品读**（`HouseholdLaborCommitment` 当日会被劳动再分配改写 —— `SettlementIndex.java:50` 类注）。
- 合并冲突面（`Shell.java`、15 个 pom）是**预测**，未实测。
- `ExpectedProfitBook` 无修正消费一句沿用控制方复核 §2/M12，**本计划未回代码核**。
