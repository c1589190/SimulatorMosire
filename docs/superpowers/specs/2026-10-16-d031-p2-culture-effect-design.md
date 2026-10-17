# D-031 P2：启用真实文化加成（预期利润 × 实际生产同语义）+ 移除中性假设声明（2026-10-16）

> **取代者：`docs/superpowers/specs/2026-10-17-d032-production-experience-choice-design.md`（2026-10-17，用户原稿 D-032）。** 用户明确要求文化与生产效率解耦；本文原“文化身份 → 真实生产效率倍率”方案不再适用，仅保留为历史调查/设计留痕，勿据此开工或续派 P2。取代不表示旧方案已经实现；其他历史状态与原文不改。

> **本文取代**：`docs/superpowers/specs/2026-10-16-d031-p1-definition-migration-design.md` 的 **I11「文化日循环继续空注入」**（本批改为"I11′ 真实效果已启用且两路同语义"）；
> 局部取代 `docs/superpowers/specs/2026-10-15-d031-interface-first-design.md` **§7 修正 A（P0 真实效果禁用是执行边界）**与 **§8（接线点固定空注入）**的**当前执行口径**；
> P0/P1 的历史验收、判据与证据**原样保留**，不改写。
> 依据：P0 设计 §10 迁移计划表的 **P2 行**、§7 末段「一旦启用真实加成（后续批次）：预期利润与实际生产必须消费同一 `IndustryDefinitionView` 与同一修正语义」、用户设计原稿 D-031。
> 现状前提：**P1 已收口**（侧线 `clean verify` rc=0、18 模块 3473/0/0/7、V1–V7 全过；见 `docs/superpowers/status/2026-10-16-d031-p1-worktree-progress.md`）。

---

## §1 目标、来源与范围

### 1.1 目标（本批要长成什么样）

1. **真实文化加成在真实日循环里生效**：家户的文化标签 + 该文化对该生产技能键的熟练度规则，经既有加权合成服务算成**每 unit 一个**的 `ProductionEfficiencyModifier`，在当日结算**之前**注入，替换掉现在的固定空注入。
2. **预期利润与实际生产消费同一份定义与同一套修正语义**：预期利润必须能拿到**同一个技能键、同一张规则表、同一值域与中性约定、同一舍入/clamp** 算出的修正；不得出现第二份定义、第二张倍率表或第二套公式。
3. **移除"真实文化效果未启用"的中性假设声明**：代码/包注/类注里所有"文化真实效果未启用""空注入""可测试纯服务但未被调用"的标记按实际语义改写；P1 遗留的"生产定义尚未迁移"过期标记一并清除（清单见 §10）。
4. **唯一写者不变**：production 仍是生产定义的唯一权威写者；本批**不新增任何定义写路径**。

### 1.2 非目标（本批不做，别顺手做）

| 不做 | 依据 |
|---|---|
| **科技升级 / 传播**（"在恰当的时候让已有生产方式发生升级"） | P0 §6.1：「本轮只定义 typed 契约，**不因接口先行自动生成传播/升级行为**」；属独立后续批次，本批不立批次 |
| **共享阶层流动表落账**（本户×邻户流动表、与市场周期同步落账） | P0 §9 明确「**只设计，不实现**；旧双机制未替换」；属独立批次 |
| 新经济公式 / 新定价 / 新分配规则 | P1 §4 I8「旧定价/生产/分配/市场/人口/财债公式和常量不变」继续有效 |
| 第二份可编辑定义表 / 通用规则引擎 / GM 开关 / 激活位 / profile / 系统属性 | P0 §10 修正 G + P0 §7 修正 A；P2 是**移除中性边界**，不是"加开关" |
| 文化同化、宗教利息、复杂治理 | 用户口径：本轮是生产熟练度这一条通路 |
| 旧档兼容 | Alpha 口径：旧存档/旧格式一律作废 |

---

## §2 回代码核过的现场（2026-10-16，P1 收口后；全部本仓 `file:line`）

### 2.1 已经具备、本批直接复用（**不要重写**）

| 事实 | 位置 |
|---|---|
| 加成规则与家的文化标签的**权威状态**在 culture | `simos-culture/src/main/java/io/mosire/simos/culture/CultureData.java:47-49`（`householdCultures` + `aptitudeRules`），`:50` `implements CultureLookup` |
| **三件套纯服务已存在**：按**全部实际出工家户劳动量加权**、无规则/无标签户按 1000 参与分母、BigInteger 防溢出、四舍五入、clamp `[0,2000]`、具名 reason（`skill-unknown` / `no-labor`）、每 unit 一个结果 | `simos-culture/.../aptitude/ProductionAptitudeComposer.java`：构造 `:69-77`、`compose(unit)` `:96`、`compose(Collection)` `:96` 起、中性分支 `:148`/`:191`、合成 `:215`、`SOURCE="culture.aptitude"` `:53` |
| 修正的**接收口**（替换语义、逐 unit 恰一个、未知 unit 与重复注入当场拒） | `simos-economy/.../time/EconomyDayStepper.java:513`（null/未知/重复守卫 `:517/:531/:536`）、`EconomySession.java:70/:189/:197`（进程内集合，**不落持久表**） |
| 修正在**结算里的真实消费点** | `EconomySettlement.java:1793-1794`（取当日注入集）→ `:1818-1825`（逐 unit 取 `modifierPerMille`，缺省 1000）→ `ProductionEfficiencyBook.accrueTick(...)` `:71-87` |
| 「家户劳动 → unit」只读事实口（economy 实现，P0 能力原样保留） | `simos-economy-api/.../api/production/ProductionLaborLookup.java`、实现 `simos-economy/.../EconomyProductionLaborLookup.java:46/:70` |
| app 侧装配入口（production 定义口 + 劳动口） | `simos-app/.../household/ProductionAptitudeAssembly.java`（`definitionLookup` / `laborLookup`） |
| 生产定义的**唯一权威**与只读口 | `simos-production/.../ProductionData.java:47-52`（`implements TechnologyLookup, ProductionDefinitionLookup, ProductionSkillLookup`）、`ProductionSnapshot.java:27` |

### 2.2 本批必须改掉的现状（这就是缺口）

| # | 现状（回代码核过） | 为什么是缺口 |
|---|---|---|
| G1 | **真实日循环固定空注入**：`simos-app/.../household/CultureAptitudeBridge.java:43` `stepper.updateProductionModifiers(List.of());`；调用点 `PopulationEconomyTimeParticipant.java:646`（真实路径，`step(day)` 在 `:642` 一带）与 `EconomyOwnershipTimeParticipant.java:202`（备用 fixture 路径） | 即使命令面写入 2000‰ 规则，结算**恒中性** |
| G2 | `ProductionAptitudeComposer` **全仓无生产调用点**（仅测试构造） | 服务是纯的、可测的，但没接线 |
| G3 | **`CultureSnapshot` 只实现 `Snapshot`**（`CultureSnapshot.java:20`），**没有**实现 `CultureLookup`；对照 production 侧 P1 已让 `ProductionSnapshot implements ProductionDefinitionLookup, ProductionSkillLookup`（`ProductionSnapshot.java:27`） | app 目前**拿不到契约形状**的文化只读口 ⇒ 谁去读 `CultureData` 必须先定形状，否则 app 会去 import 实现细节 |
| G4 | **预期利润路径完全没有修正入参**：`ExpectedProfitBook.prospect(...)`（两个重载 `:194`/`:240`）签名里没有任何 efficiency/modifier 参数；全文件对 `ProductionEfficiencyModifier`/`perMille` 零命中 | 预期利润与实际生产**不同语义**，正是 P0 §7 末段禁止的状态 |
| G5 | 预期利润的真实消费点至少两处：`GovernmentServiceDesertionBridge.java:828-829`（单位劳动预期利润）与 `ModeMigrationSettlement` / `PrimaryModeRanking`（转业排序，`PrimaryModeRanking.java:40` 提到同一 `Prospect` 家族） | 这些决策会驱动家户转移/转业 ⇒ 若两路不同语义，用户会看到"算的是甲、落的是乙" |
| G6 | **过期/待改标记**：8 个主源码文件仍写「生产定义**尚未迁移**（仍在 `simos-economy`）/实现是 economy 适配器」——P1 已迁移，属**过期**；另有若干处写「真实文化效果未启用/空注入/composer 未被调用」——P2 完成后必须改写。清单见 §10 | 活注释与真实语义不一致会误导后续会话（本仓纪律：机制性描述必须回代码核） |

### 2.3 生成现场的命令（可复跑）

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
grep -rn "updateProductionModifiers" simos-app/src/main simos-economy/src/main --include=*.java
grep -rn "CultureAptitudeBridge" simos-app/src/main --include=*.java
grep -n "ProductionEfficiencyModifier\|modifierPerMille" simos-economy/src/main/java/io/mosire/simos/economy/time/ExpectedProfitBook.java   # 期望：零命中
grep -rn "ProductionAptitudeComposer" simos-*/src/main --include=*.java                                                                  # 期望：无生产调用点
grep -rln "尚未迁移\|真实文化效果未启用" simos-*/src/main --include=*.java
```

---

## §3 冻结的语义与数据流（本批"世界必须长成什么样"）

### 3.1 两条路径、一套语义（★ 本批的核心裁定）

**同一套语义** = 同一个**技能键**（`IndustryDefinitionView.skill`，P1 后为唯一权威）、同一张**规则表**（`CultureData.aptitudeRules`）、同一**中性约定**（无标签/无规则/缺技能 ⇒ `1000`，且逐值等于现状）、同一**值域与处理**（`ProductionAptitudeRule` 构造期 `[0,2000]`、合成 clamp `[0,2000]`、四舍五入到整数）。

**允许不同的只有两处**（P0 §7 末段已授权）：**聚合域**与**时间窗口**。

| 路径 | 聚合域 | 时间窗口 | 输出 |
|---|---|---|---|
| **实际生产** | 该 unit **全部实际出工家户**，按 `laborMilli` 加权；无规则户按 1000 进分母 | 当日结算前的**当刻**状态 | 每 unit 一个 `ProductionEfficiencyModifier`（既有服务 `compose(Collection)`） |
| **预期利润** | **该家户自身**（评估者自己的文化对该候选生产方式技能键的规则值） | 决策调用时刻（可与结算窗口不同） | 一个 `modifierPerMille`（`1000` = 中性） |

★ **控制方技术决定 D-2**（**非用户原话**，用户可推翻）：预期利润的聚合域取"该家户自身"，理由是预期利润回答的是"**我**转过去能赚多少"，而实际生产的加权域回答"这个 unit 今天实际产多少"；两者共用同一规则表/技能键/中性/值域，**不复制第二套倍率**。若后续用户要求预期利润也按"目标 unit 现有出工结构"加权，只需替换入参来源，语义与接口不变。

### 3.2 实际生产路径（数据流，逐跳）

```
CultureData(aptitudeRules) ─┐
                            ├─► ProductionAptitudeComposer.compose(当日 units) ─► List<ProductionEfficiencyModifier>
production 切片(定义/技能键) ─┤                                                        │
economy 劳动事实(laborMilli) ─┘                                                        ▼
                             CultureAptitudeBridge（app 组合根）──► EconomyDayStepper.updateProductionModifiers(list)  [替换]
                                                                                       │
                                                                  当日 step(day) ─► EconomySettlement(:1818) ─► ProductionEfficiencyBook.accrueTick
```

- **谁算**：`simos-culture` 的领域服务（既有 `ProductionAptitudeComposer`）。**app 不实现任何公式**（铁律 4）。
- **当日 unit 集合从哪来**：app 只读枚举当刻 economy 状态的 units（稳定 `ProductionUnitId`）。**不许**用"文化规则里出现的户"反推 unit，也不许预先把规则播满。
- **注入时机**：必须在当日 `step(day)` **之前**、替换语义（与现有 `CultureAptitudeBridge` 的两处调用点同位置，只是内容从 `List.of()` 变成真实列表）。
- **两条日循环路径都要接**：`PopulationEconomyTimeParticipant`（真实）与 `EconomyOwnershipTimeParticipant`（备用 fixture）。两条路径的语义一字不差；不得只接一条。
- **空表语义不变**：当日没有 unit / 没有劳动承诺 ⇒ 传空列表 = 全中性，与现状逐值一致。

### 3.3 预期利润路径（数据流）

```
决策点（app 或 economy 的调用方）
   ├─ 取该户 CultureId（CultureLookup.cultureOf）
   ├─ 取候选生产方式的技能键（production 定义投影的 IndustryDefinitionView.skill）
   ├─ 算该户修正（culture 领域服务的新纯查口，见 §3.4）
   └─► ExpectedProfitBook.prospect(..., modifierPerMille)   ← 新增**可选**入参，缺省 1000
```

- **`prospect(...)` 的旧签名必须保留且行为逐值不变**（新增重载 + 旧重载委派 `1000`）：所有既有调用点、夹具、黄金读数在"无文化规则"时**逐值不动**。
- **调用点清单是交付物的一部分**：实现 owner 必须回代码枚举**全部** `prospect(` 调用点，逐个写"接入 / 不接入 + 理由"；至少**必须接入**：`GovernmentServiceDesertionBridge` 的单位劳动预期利润、转业/迁移排序（`ModeMigrationSettlement` / `PrimaryModeRanking` 一线）。
- **economy 不认识 culture**：economy 只接收一个 `long modifierPerMille` 参数（或既有 `ProductionEfficiencyModifier` 值类型），**不得** import culture 任何类型；策略判断（怎么用这个值）仍归 economy。

### 3.4 新形状（最小新增面；★ 若实现需要更多形状，先报告）

1. **culture 侧新增一个纯查口**（名字由实现者定，建议 `ProductionAptitudeComposer.aptitudeFor(CultureId culture, ProductionSkillKey skill)` 或等价）：
   - 输入：文化 ID + 技能键；输出：`long modifierPerMille ∈ [0,2000]`，无规则 ⇒ `1000`；
   - **必须是纯函数**、不读状态以外的任何东西、不缓存、不落表；
   - 该查口与 `compose(...)` **共用同一段取值逻辑**（实现上抽同一个私有方法；禁止复制两份公式）。
2. **`CultureSnapshot implements CultureLookup`**（对称于 P1 的 `ProductionSnapshot.java:27`）：让 app 能从 `SimulationState` 取到契约形状的文化只读口，无需 import `CultureData`；`CultureData implements CultureLookup`（`:50`）已具备实现。
3. **`CultureAptitudeBridge` 改为真算并注入**：签名接收"当刻状态 + stepper"（或等价的最小入参），内部用 `ProductionAptitudeAssembly` 与 culture 契约口组装 composer；**不新增激活位/开关/profile/系统属性**。
4. **`ExpectedProfitBook.prospect` 增加可选修正入参**（重载，缺省 1000）。

### 3.5 所有权与依赖边界（**零新依赖边**是本批的设计约束）

| 面 | 所有者 | 本批变化 |
|---|---|---|
| 加成的规则/标签状态与规则→千分映射 | `simos-culture` | 新增纯查口；Snapshot 实现契约口 |
| per-unit 加权合成（实际生产） | `simos-culture` | 不变（已具备） |
| 修正的接收与结算消费 | `simos-economy` | 不变（已具备）；`ExpectedProfitBook` 增加可选入参 |
| 定义与技能键权威 | `simos-production` | **不变**（唯一写者） |
| 装配与调用时机 | `simos-app`（组合根） | 桥从空注入改真算注入；预期利润调用点接线 |
| 人口 | `simos-social` | **不变**（culture 不写人口） |

★ **允许依赖面照旧**（根 `AGENTS.md` 模块边界表）：`culture` = util + map + social-api + economy-api + production(**只读 contract**)；`economy` **不依赖 culture**；`production` 不新增依赖。⇒ **本批预期不需要改任何 POM / enforcer / 边界表**；若实现发现必须放宽依赖，**先停手报告控制方**，不得自行改 pom。

---

## §4 不变量（I1–I12；★ I11 取代 P1 的同号条目）

- **I1** 生产定义唯一权威仍是 production，唯一写者仍是 production 命令面；**不得出现第二份定义写路径**（含"只读副本当写口"）。
- **I2** 全部修改仍走 `Command → ChangeSet → Revision`；本批**不新增第四种 Command、不新增激活位/开关/profile**。
- **I3** economy 不 import culture；culture 不 import economy 实现（只认 economy-api 的 `ProductionEfficiencyModifier` 值类型与 `ProductionLaborLookup` 只读口）。
- **I4** 加成**不落持久表**：修正集是进程内、当日替换（`EconomySession.java:70`）；文化状态只持久化"标签 + 规则"两组件。
- **I5** **中性=现状**：无文化数据/无规则/缺技能 ⇒ 两条路径都得 `1000`，且**逐值等于 P1 收口时的读数**（黄金回归判据）。
- **I6** 两条路径**同一技能键、同一规则表、同一中性、同一值域/舍入**；只允许"聚合域/时间窗"不同（§3.1）。
- **I7** 缺技能 ⇒ 产业**仍然可见**（`IndustryDefinitionView.skill = Optional.empty()`），按中性并具名 reason；**不得**把产业判为不存在、不得回退成拒绝构造视图。
- **I8** 确定性：合成输入全部取自已持久化状态，同状态同输入 ⇒ 逐值可复现；不得引入 `Map.copyOf/Set.copyOf`（保序纪律）、不得依赖线程/时间/随机。
- **I9** 业务拒绝 = INFO 具名理由；契约/跨切片一致性故障 = ERROR 不降级（照日志纪律；`PRODUCTION_MODIFIER_INJECTED` 既有 INFO 每日一条保留）。
- **I10** 旧定价/生产/分配/市场/人口/财债公式与常量**一字不改**；本批只改"修正值从哪来"。
- **I11′（取代 P1 的 I11）** 文化日循环**不再空注入**：真实规则在场时必须产生非中性修正；中性只允许出现在"无规则/无标签/缺技能/无 unit"这些**具名**条件下。
- **I12** 不兼容旧档、不动其他批次（`ga-opt`）、不改主树、不恢复 stash。

---

## §5 验收判据（V1–V8 + 负向；实现与测试分离，最后统一测试）

| ID | 判据 | 判定方式（要具体读数，不要"不同/有变化"） |
|---|---|---|
| **V1 中性回归（最强）** | 未写入文化数据的世界，实际生产与预期利润**逐值等于 P1 收口读数** | ① P1 冻结证据：18 模块 `3473/0/0/7` 全绿；② 两树黄金对拍 `compare-golden.py p1.json p0.json` 仍 **32857 / 差异 0**；③ 未接线/接线但无规则两条读数逐值比 |
| **V2 实际路径真生效** | `culture.SetHouseholdCulture` + `culture.UpsertAptitudeRule(modifier=2000)` 后跑日循环：该 unit 的 `productionEfficiency` 累计与产出**按规则变**；规则改回 1000 ⇒ 逐值回到 V1 读数 | 具体非零读数 + 两次对照（改前/改后/改回） |
| **V3 预期利润路径同语义** | 同一 household、同一候选生产方式：改规则后 `prospect` 的读数按**同一技能键的同一系数**变化；缺技能/无规则 ⇒ 1000 且具名 | 具体数值 + 与 V2 用**同一个 `ProductionSkillKey`** 的对照表 |
| **V4 加权语义（实际路径）** | 多户出工且劳动量不同 ⇒ 按 `laborMilli` 加权的手算值；无规则户按 1000 **计入分母** | 手算值（不许从被测代码反算） |
| **V5 唯一权威回归** | 本批后仍只有一份可编辑定义：包边界测试 + 源码扫描（无第二写路径）；production 命令面仍是唯一写者 | 复用 P1 的 V5/N-类断言并追加"文化不新增写口" |
| **V6 确定性与往返** | 同世界同种子同操作 ⇒ 逐值可复现；文化状态 JSON 往返后结算一致；修正集不在任何快照/变更集里出现 | 往返 + 两次运行逐值比 + "快照组件清单里没有修正表"的机械断言 |
| **V7 负向** | ① 越界规则（>2000）构造期拒；② `updateProductionModifiers` 未知 unit / 重复 unit 仍当场拒；③ 缺技能 ⇒ 产业可见 + 中性具名；④ economy 侧不得 import culture（源码扫描，且先自证扫描面非空）；⑤ 无新激活位/开关（扫描 profile/系统属性/开关名）；⑥ 旧 `prospect` 签名行为逐值不变 | 每条：改坏 ⇒ 当场红 ⇒ 还原（变异纪律） |
| **V8 完整门禁** | 侧线 `tools/mvn-lock.sh -pl simos-app -am clean verify` **rc=0**（含 Spotless/Checkstyle/SpotBugs/前端门禁），测试数 ≥ P1 的 3473 且**零回归** | 真实 rc + 模块汇总 + SpotBugs 非零模块数 + `[frontend-gate]` 原文 |

**跳过不算通过**：`SIMOS_REAL_LLM` 门控的 7 条继续如实计 skipped。

---

## §6 文件责任与交付

| 责任区 | 拥有 | 禁止 |
|---|---|---|
| **P2-IMPL（一个实现 owner）** | `simos-culture/src/main`（新增纯查口、`CultureSnapshot` 实现 `CultureLookup`、标记改写）、`simos-economy/src/main`（`ExpectedProfitBook` 可选入参及其消费点）、`simos-app/src/main`（`CultureAptitudeBridge` 真算注入、装配、预期利润调用点接线、标记改写）、§10 清单内的标记清除 | 写/跑测试、改 POM/enforcer/边界表（需先报告）、改主树/`ga-opt`/P1 基线与既有证据、`git add/commit/stash`、新增依赖边、新增激活位 |
| **P2-TEST（最后另一个测试 owner）** | 受影响测试文件、门禁清单、独立模拟脚本 | 改生产代码（受控变异必还原例外） |

**实现 owner 交账必须含**：实际改动文件 `路径:行`、真实 `compile` rc 与日志、**全部 `prospect(` 调用点清单 + 接入/不接入理由**、偏离设计之处、未做/未验证一节。
**测试 owner 交账照** `test/control-v1v7.md` §8 形制（命令→读数→证明/未证明→剩余→是否 DONE）。

---

## §7 用户原话附录（逐字；不改写、不缩写、不意译）

### 7.1 D-031 首轮（`docs/superpowers/specs/2026-10-02-architecture-design-source.md:497-507`）

> 你自己先了解一下当前目录下的项目，然后我来说一下我希望达成的设计：
> 现有的家户、经济模块管理很混乱，我原本设想的家户，作用是表达一个拥有特定文化、特定习俗的社会阶层，但是目前貌似只实现了经济面上的基层；
> 另一个问题是家户流动，这一点还需要你自己看代码和之前的日志，我不多赘述；
> 重点是我希望实现什么，我希望对于每一个家户对象，在单tick运行的时候，都算一张阶层流动表，以代替之前落后且压根不知道在干什么的单一阶层流动机制；
> 这张阶层流动表首先根据这个家户的所在位置、经济状态初始化，在初始化的时候，其中有这个家户抛掉其他所有人文经济因素，所有可能的人口流动方向——因此需要列入本hex和周围六个hex，不管经济状态、生产方式还是其他目前压根没定义的人文属性等的，所有其他可流动家户；
> 初始化出来这张表之后，最后一步自然是给经济算实际流动速度，当这个家户的负债规模变大的时候（这个条件我甚至不知道靠不靠谱），这个家户其中的成员人口自然要往更低阶层的家户流动；没有反之，同时成立，如果这个家户的库存价值变大，那么会往经济状态更好的家户产生人口流动；然后是生产方式判断，如果这个生产方式相比相邻其他家户选择的生产方式不够赚钱，那么这个家户会产生流向同等经济水平其他家户的速度；以上三者速度计算是顺序进行的，算完这些根据经济决定的实际速度后，根据这张人员流动表，就可以算对其他家户的人员流动了；
> 但是很显然，中间缺了很多环节；
> 这里很显然可以引入一个之前一直在文档中若隐若现但是一直没有实装的模块了，也就是家户的文化模块，我希望命名为“文化与基层治理”——你知道的，这个项目目前服务于中世纪环境，因此宗教、文化等非经济社会存在都要交给这个模块；
> 具体实现，我认为其实就是给家户打上一个文化标签，这个家户是什么地方认可什么宗教/文化的什么人，就是这个意思——这里这个模块首先和经济相互作用的地方很简单，那就是这个家户的生产熟练度，你知道吗？生产技能和适应是和文化绑在一起的，我管你信不信，总之就是要维护一张给特定生产方式加加成的表；
> 是的，这里额外要引入一个单独管理生产方式和科技的模块，最终共同作用于大经济系统，作用自然就是格式化地定义生产方式，然后在恰当的时候让已有的生产方式发生升级，模拟科技进步；
> 总之，我认为这一轮必须要把生产与科技模块、文化与基层模块，和对应的经济模块接口等规范出来，因此你应该先开始进行调查，告诉我目前代码里是什么情况，方便进行进一步规划/裁决/设计

### 7.2 D-031 补充裁定二（同文件 `:526`，与本批"谁算"直接相关）

> 2 不是经济模块自己有个利润率水平吗？根据经济因素决定的家户流动，让经济模块自己算不就得了？遇到文化同化，也让文化模块自己在那个表格上算不得了，目前项目不是有这个工单系统吗？这不就派上用场了

### 7.3 上一轮范围口径（**侧线笔记转引**，控制方已注明来源性质；2026-10-15）

> 现在开始规划文化/生产方式/经济模块的进一步调整，现阶段不需要真的做出来复杂的文化效果，只需要把接口立好就行，根据这个目标，规划怎么写代码、出裁定

（来源：`.agents/notes/proposed/architecture/2026-10-15-d031-interface-first-scope-trace.md:5` 转引，**未当成本主会话直接收到的逐字原话**。本批是它的下一步：接口已立好，现在启用真实效果。）

### 7.4 本会话用户原话（2026-10-16，逐字）

> 查看当前目录的worktree，找到那个改文化等模块的分支，恢复工作状态

> 先告诉我目前相关模块的进度推进到哪里，已有代码理论上能做到什么吧

> 那行吧，先把P1做完？V1~V7是啥先告诉我

> 不要多创建goal……这玩意很烦人，你老实等着就行

> 行，那么开始吧，依旧使用无头dsh派单执行，记住要放在worktree里

（最后一条为 P1 执行授权，转录自 `docs/superpowers/specs/2026-10-16-d031-p1-definition-migration-design.md` §7。）

### 7.5 本批的授权形式（★ 如实标注性质）

用户在本会话的**选项**中选择「开始写 P2 的约束设计书」——**选项文字由控制方所写，不是用户逐字原话**。⇒ 本文的**范围与判据仍需用户过目**；用户随时可推翻（尤其 §3.1 的控制方技术决定 D-2）。

---

## §8 控制方技术决定（**非用户原话**，用户可随时推翻）

1. **D-1 中性不改语义**：P2 不移除"无规则 ⇒ 1000"，只移除"有规则也不生效"。中性不是开关。
2. **D-2 预期利润的聚合域 = 该家户自身**（见 §3.1 表与说明）。
3. **D-3 形状最小化**：只新增 4 个形状（§3.4）；`prospect` 用**重载 + 缺省 1000**，不破坏既有调用点。
4. **D-4 Snapshot 实现契约口**（`CultureSnapshot implements CultureLookup`），照 P1 的 `ProductionSnapshot` 先例，避免 app 依赖实现类。
5. **D-5 本批零新依赖边**；实现若发现必须放宽 ⇒ 停手报告。
6. **D-6 不新增激活位/开关/profile**；真实效果接线即生效（与 P0 修正 A 的"禁用"相反，但同样**不给 GM 开关**）。
7. **D-7 标记清除属本批交付**（§10）：过期标记不清 ⇒ 视为未完成。

---

## §9 未做 / 未验证（设计落盘时）

- **本文只是约束设计**：P2 一行代码未写、未编译、未跑测试；文中所有 `file:line` 是 2026-10-16 P1 收口后的现场。
- 未枚举**全部** `prospect(` 调用点（§2.2 G5 只举了两处已核到的）；完整清单是 P2-IMPL 的交付物。
- 未设计科技升级/传播与共享流动表（非目标）。
- 未验证"预期利润聚合域取该户自身"是否符合用户意图（D-2 是控制方技术决定，待用户过目）。
- 未评估性能影响（每 unit 一次文化查询 + 每决策一次规则查询；无缓存设计，若性能成为问题另开批次，**不得**顺手引入缓存层）。

---

## §10 标记清除清单（P2 完成后必须与代码一致；★ 今天实测）

**(a) P1 遗留的过期标记**（写"生产定义尚未迁移/实现是 economy 适配器"，但 P1 已迁移）：

| 文件 | 行 |
|---|---|
| `simos-production/src/main/java/io/mosire/simos/production/package-info.java` | `:7`、`:9` |
| `simos-production/src/main/java/io/mosire/simos/production/contract/package-info.java` | `:8` |
| `simos-culture/src/main/java/io/mosire/simos/culture/package-info.java` | `:9-10` |
| `simos-culture/src/main/java/io/mosire/simos/culture/contract/package-info.java` | `:16-17` |
| `simos-culture/src/main/java/io/mosire/simos/culture/aptitude/ProductionAptitudeComposer.java` | `:68`（`@param definitions … ★ 定义尚未迁移`） |
| `simos-economy-api/src/main/java/io/mosire/simos/economy/api/production/package-info.java` | `:16-17` |
| `simos-app/src/main/java/io/mosire/simos/app/world/GenesisModuleSlices.java` | `:27` |

**(b) P2 完成后必须改写的"未启用"标记**：

| 文件 | 行 | 现文（要点） |
|---|---|---|
| `simos-app/.../household/CultureAptitudeBridge.java` | `:10-30` 类注、`:43` 实现 | 「无条件空注入 / 恒中性 / 不调用 composer」 |
| `simos-app/.../household/ProductionAptitudeAssembly.java` | `:47` | 「文化 composer 据此拿到真实出工家户与份额；日循环仍不启用 文化加成」 |
| `simos-culture/.../CultureSnapshot.java` | `:17` | 「本轮真实文化效果未启用」 |
| `simos-culture/.../CultureData.java` | `:42` | 「本轮真实文化效果未启用：状态里有非中性规则也不改变 P0 真实日循环数值（spec §7 修正 A）」 |
| `simos-production/.../package-info.java` | `:9`（第②条） | 「真实文化效果未启用（本轮只有中性默认路径）」 |
| `simos-culture/.../package-info.java` | `:10`（第②条） | 「真实文化效果未启用（…P0 真实日循环空注入、不调用 composer）」 |
| `simos-culture/.../contract/package-info.java` | `:17`（第②条） | 「真实文化效果未启用（P0 日循环空注入，`ProductionAptitudeComposer` 是可测试纯服务但未被调用）」 |
| `simos-app/.../world/GenesisModuleSlices.java` | `:27`（第②条） | 「三处"未完成"标记照旧：…真实文化效果未启用…」 |

★ 清除口径：**改写为真实语义**（如"文化加成已启用；无规则 ⇒ 中性 1000"），**不许**只删不写；`CultureAptitudeBridge` 的"两处调用点/替换语义/step(day) 之前"这些**仍然成立**的说明要保留。
