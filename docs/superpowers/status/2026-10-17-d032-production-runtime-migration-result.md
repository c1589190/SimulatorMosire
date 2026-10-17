# D-032 生产方式运行时迁移：交付与验收结果（2026-10-17）

> **后续独立审核（2026-10-17）发现待修复问题**：`docs/superpowers/reports/2026-10-17-d032-final-independent-review.md` 记录 5 类问题及最终 JAR 的独立复现：经济预计/选择不读熟练度、共享实例重复计资产、实际执行绕过条件、零经验合户平均错误、原始经验公式同 tick 顺序依赖。下文历史门禁通过记录保留，但不能继续作为“无已知缺陷／整体最终验收通过”的结论；修复与新增判据尚未执行。
> **状态：主链已验证接通（侧线工作树，未提交/未同步主树）。** 本文是跨会话入口；判据全文见
> `docs/superpowers/specs/2026-10-17-d032-production-runtime-migration-design.md`（§6 判据表、§13 各批裁定、§14 测试阶段、**§14.28 终局验收**）。
> 台账与证据在 `.superpowers/sdd/2026-10-17-d032-runtime-migration/`（本机留痕、不进 git）。

## 一、交付内容（一句话）

**生产方式运行时归位**：方案定义/实例/进度/经验/熟练度/效率余数/解锁/GM 产出数值覆盖的**唯一权威在 `simos-production`**；economy 只保留"选择、资源分配、经济关系、经营状态"；日循环按两步契约真实驱动：`probe`（无副作用试算）→ economy 统一分配 → `execute`/`executeDay`（按最终投入）→ 回执 → economy 用回执做经济结算 → 与 SOCIAL/ACTOR/GOV **同一次 Revision** 原子提交。

- 删除面：D1 科技点抽象面 · D2 文化→效率倍率链（culture 收为占位）· D3 劳动只读口 · D4 候选面（`ProductionCandidate`/`templateFor`）· economy 的 `units`/`productionEfficiency`/`outputQuantityOverrides`/`candidates`/`industries`/`modes` 六个第二权威 · 旧效率书与旧效率状态 · `unitsWorkCopy` 桥 · 旧 `runtime=null` 构造器。
- 新增面：`production.contract` 契约族（受限表达式/经验条/两步契约/回执/只读投影/tick 条件/身份分层）· 组合根装配与两条推进接线 · `production.CreateInstance` 命令 · `EconomyMeta.planAssetSources/planLaborSources` 与 `economy.SetPlanSources` 命令 · I10 经验拆合契约 · 铸币权威收口。

## 二、控制方终局验收（2026-10-17，本人独立跑）

```bash
cd .claude/worktrees/parallel-side-module
tools/mvn-lock.sh -pl simos-app -am clean verify
# ⇒ rc=0 · BUILD SUCCESS
# ⇒ 18/18 项目模块 SUCCESS（SKIPPED 0 / FAILURE 0）
# ⇒ app: Tests run 1008 / 0F / 0E / 7 skipped
# ⇒ [frontend-gate] OK tests=412 pass=412 fail=0
# ⇒ [shaded-guard] 结论：OK（8121 条目；7 条运行期类 + Main-Class 全在）
```

各模块测试（控制方静止树复跑）：production **66/0/0** · economy **381/0/0** · app **1008/0/0/7**；SpotBugs 全 0。
边界：HEAD `03faf0a3` 未动 · `stash@{0}` 未动 · **未提交**（193 M / 20 D / 44 ??）· POM/enforcer/边界表零改动 · 主树/`ga-opt`/`d031-p1-baseline` 未碰。

**覆盖收尾轮（T8，2026-10-17）+ 控制方终局复跑二（在 T8 之后的最终树）**：
`clean verify` ⇒ **rc=0 · 18/18 SUCCESS** · app **1012/0/0/7** · economy **387/0/0** · production **70/0/0** ·
`[frontend-gate] OK tests=412 pass=412 fail=0` · `[shaded-guard] OK（8121 条目）`；T8 新增 **14 条判据**（app+4 · economy+6 · production+4）。
边界复核：10:29 后 `src/main` **零改动**；未提交（194 M / 20 D / 51 ??）。

## 三、验证阶段抓出并修掉的**真运行期缺陷**（全被"编译绿+Spotless绿+装配齐全"掩盖）
1. **tick 协议不可用**：`beginTick` 在登记 tick 身份前调 `advanceTick` ⇒ 新引擎每次 beginTick 抛 `tick-not-started` ⇒ 生产日第一天必炸。
2. **trade 路径 `plan-unknown`**：按需建定义只写会话覆盖层、未 `registerPlan` 就 `createInstance`。
3. **生产日不推进（毛产恒 0）**：清零位置（本日实扣被当场抹掉）+ **读源**（劳动分配读实例旧值而非本日实扣）+ 步表读入口快照（当天新建实例整天漏掉）三层。
4. **`economy.Seed` 关系表静默丢失**：B5 删了填表循环却留下读空表的守卫 ⇒ 创世后 relations 为空、补偿规则/地租/分成不生效。
5. **three-powers 创世被拒**：`ThreePowersGovBootstrap` 缺建 office 实例一步 ⇒ 该世界建不起来（~19 条 app 红）。
6. **SpotBugs**：production 26 bugs + economy 5 bugs（集合暴露/死代码等）。
7. **N22 串行推进不等价**：复位启发式错算 ⇒ `advance(0,2)≠advance(0,1)+advance(1,2)`。
8. **测试夹具覆盖缺口**：`GovZ6WorldFixture` 缺 `production` 分支 ⇒ 静默丢弃 production ChangeSet ⇒ 一切 production 跨 revision 的 app 级判据失效。**新纪律**：任何世界夹具的模块 switch 必须覆盖全部可能出现在 `moduleChanges` 的模块，缺省必须抛错。

## 四、判据终局

| 组 | 状态 |
|---|---|
| V1–V12 | **全部有载体**（V4/N14 的"按实际贡献分成"腿已由 T8 补齐：两户 600,000/400,000 劳动 ⇒ 工资 1,200/800 · 分成 720/480 · 租 500 · 剩余 300，守恒 5,000） |
| N1–N25 | **全部有载体**（N9/N14/N20 已补齐：`UnifiedBudgetNoDoubleBookingTest` 3/0/0 + `N9BatchZeroPartialCommitTest` 1/0/0 · `N20ThreeCommandSeedOrderEndToEndTest` 1/0/0） |
| T3 变异自证 | **完成**：M1–M7 + 夹具变异，全部"改坏⇒当场红⇒逐字节还原(md5)⇒重跑绿"，零等价存活 |
| T4 门禁 | **达成**（控制方两次复跑 rc=0：T7 树 1008、T8 最终树 1012） |
| 黄金对比 | 653 行全量落盘；T6→T7 新差异 **0**；裁定二十六两键两侧 0/0 |

### 四之二、覆盖收尾关键修复（T8）

- **`EconomyOwnershipFixture` production 片**（与第 8 条同类覆盖缺口）：改为三片同进同出 + `default` 抛错 + namespace 审计表；守卫测试 **2/0/0**（160 实例 → advance 后 162；未知 namespace `ga` 具名 ISE）。

## 五、剩余具名项（**不阻塞主链验收**；均为后续小批/U10）

黄金 653 行逐行 provenance（U10 面）· main 清理三项（`birthsToday` 死读 / `GovWorldBootstrap:89-100` 类注缺行 / `EconomyDayStepper.productionRuntime()` 0 调用去留）· `EconomyTestWorld` 死夹具（0 引用）删除 · Enterprise 处不可达分支仅源码钉死（已写明，不伪造行为负向）。

## 六、下一步（**需用户授权**）

1. **提交/同步主树**：侧线工作树 194 M / 20 D / 51 ?? 未提交；未经明确授权不 commit、不合并、不 stash。
2. 可选清理小批（见 §五，纯 main 清理 + 一行类注，无行为变化）。
3. 可选后续：U10 的 GM 写入口（运行中产业原地改产出率 / 经验条/可用性条件写入口）与 GUI/工具读数。


## 七、2026-10-17 下午：独立审核后的修复轮与口径更正（补充记录；取代 §二/§四 的"无已知缺陷"口径）

### 7.1 独立审核发现并已修复/具名的问题

| # | 问题 | 处置 | 状态 |
|---|---|---|---|
| R1 | 经济收益排序绕过新生产计算（熟练度/GM 覆盖不进选择链）：预计 100 vs 实际 200 | **F2**：`LaborQueueBook.offer` 与 `ExpectedProfitBook.productionProspect` 物理量改走 `probe`（REF=1000；E=1000 中性；占用留 R2 替换点） | ✅ 已修（编译 rc=0；T9 R1 6 条绿） |
| R2 | 同方案多实例重复计入资产（100 土地被两实例各算 100） | 按重规划**暂不修**；A 阶段统一占用关闭。保留具名复现判据 | ⛔ **未修（具名）** |
| R3 | 实际执行绕过解锁/属性条件 | **P 批**：execute/executeDay 与 probe 共用 `qualificationFailure`；主体=经营者家户、组织对经验/解锁条件 fail-closed；零残留 | ✅ 已修（T9 R3 6 条绿） |
| R4 | 零经验迁入者不参与合户平均 | **P 批**：`mergeExperienceInto` 来源∪目标并集、缺行=ZERO | ✅ 已修（T9 R4 5 条绿） |
| R5 | 原始经验公式同 tick 顺序依赖 | **P 批**：`ProductionTickCondition` 第三组件冻结原始经验；执行路径只读冻结值 | ✅ 已修（T9 R5 3 条绿） |
| R6 | 入口 probe 死路（空占用/每日劳动口径/plan-unknown/二次夹） | **E1-A + F1**：占用=将实占、劳动=日×cycleDays、probe 用 unbound id、删二次夹 | ✅ 已修（T9 R6 4/4 绿） |
| F5 | `planEntries` 名义只读却真写 production ⇒ 首次采用 `unit-conflict` | **F5**：`applyIntent` 分叉 WriteMode（意向=只读模拟；执行=真写） | ✅ 已修（T9 端到端转绿） |

### 7.2 修复轮真实读数（T9 独立测试 owner，F5 后全量）

`tools/mvn-lock.sh -pl simos-app -am test`（日志 `.superpowers/sdd/2026-10-17-d032-runtime-migration/test/evidence/t9-full-test-04-post-f5.log`）：
**全仓 3513 / 0F / 0E / 7 skipped**；production **84**（70+14）· economy **398**（387+11）· app **1012/0/0/7**。
新增判据：R1 6 · R3 6 · R4 5 · R5 3 · R6 4 · R2 具名未修 1；变异自证 5 条"改坏⇒红⇒还原⇒md5 一致⇒绿"，零等价存活。既有 8 条旧口径按新语义校准（R1 库存通道进选择链是设计要的改变，不是放宽）。

### 7.3 口径更正（取代前文"无已知缺陷/整体最终验收通过"）

- 最终独立审核（`reports/2026-10-17-d032-final-independent-review.md`）证明：**此前 §二/§四 的"V/N 全部有载体、无已知缺陷"过宽**；载体绿灯 ≠ 能力实现。
- **R2 未修**：I8 的"两实例"腿仍缺；V3/N9 只覆盖"两方案"腿（记录更正）。
- 当前可声明：**R1/R3/R4/R5/R6 + F5 已修并由独立测试 owner 验证**；**R2 具名未修**；全仓测试 3513/0/0/7。
- **提交/同步继续冻结**（HEAD `03faf0a3`；未 commit/merge/stash）。

### 7.4 后续（新设计基线）

重规划 v2 已冻结（`reports/2026-10-17-d032-production-line-replan-v2-review.md`）；D1 共享生产定义形状冻结落盘（`specs/2026-10-17-d032-production-definition-v2-freeze.md`）⇒ 下一步 D2/D3/D4 设计书；C/W/A 实现等各自设计书；R2 关闭在 Phase A。

### 7.5 F4 控制方终局门禁（干净树重跑，2026-10-17 15:21–15:27）

- 前置：`tools/mvn-lock.sh spotless:apply`（修复 P/F1/F2/F5/T9 新增代码的格式债；之后 Spotless check 绿）。
- 命令：`tools/mvn-lock.sh -pl simos-app -am clean verify` ⇒ **rc=0 · BUILD SUCCESS · 19/19 reactor SUCCESS（Total 05:55 min）**；日志 `impl-fix/evidence/f4-final-verify-3.log`（335k 行，15:27 完成）。
- 测试：util 217 · ga-spi 45 · map 379 · calendar 33 · social-api 3 · actor-api 8 · economy-api 50 · social 217 · unit 518 · core 241 · actor 130 · **production 84** · **economy 398** · gov 33 · army 109 · culture 16 · ga 20 · **app 1012/0/0/7**。
- `[frontend-gate] OK tests=412 pass=412 fail=0`；`[shaded-guard] 结论：OK（8123 个条目；7 条运行期类 + Main-Class 全在）`；Spotless/Checkstyle/SpotBugs 全过（BUILD SUCCESS 含 spotbugs:check 策略门）。
- 注：一次**被污染**的门禁（T9 变异体在树时）已作废并留痕 `f4-final-verify-VOID-collided-with-t9-mutation.log`；本次是 F5/T9 全退、main md5 基线核对后的干净树读数。`spotless:apply` 后 F5 文件格式变化（md5 `5c2868b7…` → 格式化后 `8a736dfc…`），语义未变（全量测试与检查全绿）。
- **口径**：本门禁只覆盖 F 修复轮限定范围（R1/R3/R4/R5/R6 + F5）；**R2 仍具名未修**（A 阶段关闭），不写成"全无已知缺陷"。

## 八、2026-10-17 19:xx 工作状态恢复与 D2 设计落盘（侧线工作树）

> **性质**：本次只恢复工作状态、补 D2 设计文档；未改业务/测试、未编译、未跑门禁、未提交/同步。

- **实际当前基线**：侧线工作树 `parallel/side-module` HEAD = `0128b0cc`，工作区在本次文档修改前干净，且该提交已推送 `origin/parallel/side-module`。§六/§七 中“HEAD `03faf0a3`、未 commit/merge”的句子是 15:22 之前的历史口径；15:23 的五个提交 `5217ec57`/`347c288b`/`6dfd1505`/`c0194084`/`0128b0cc` 已把 D-031 P1/D-032 实现与文档入账。主树仍停在 `03faf0a3`，另有 2026-10-16 的旧未提交 D-031 P0 改动；**本次未触碰主树**。
- **D2 设计已落盘**：`docs/superpowers/specs/2026-10-17-d032-cooperative-production-design.md`（对应重规划 §4 D2 / 设计书 §16）。内容覆盖参与名册、按户预计所得、角色两层分配、资格/组织主体、经验增长与迁移/合户、C2 状态/命令/ChangeSet/codec、不变量、验收与负向判据、文件所有权。
- **D1 局部更正**：`docs/superpowers/specs/2026-10-17-d032-production-definition-v2-freeze.md` 顶部已加 D2 更正注：`ProductionRoleId` 改落 `simos-economy-api`（避免 Maven 环）；补 `MONEY_WAGE ⇒ share=0`、`PRODUCT_SHARE ⇒ share>0`；`NORMALIZED_PARTICIPATION` 公式固定为 `laborMilli × 1000 / householdLaborBudgetMilli`（不再要求 `standardLabor>0`）；执行请求的逐参与户属性/劳动预算/管理劳动输入见 D2 §6；量纲统一为毫小时。
- **用户裁定状态**：D2 不需要新增用户业务裁定。经验规模效应按重规划 §5 的工程默认 (c)：可配置、默认按参与强度归一化；D2 固定分母为家户当日可用劳动。R2 仍具名未修，归 D4/A；D3 工资报价/支付与 D4 资产占用仍待各自设计书。
- **下一步**：D3 工资链设计书 → D4 资产/占用设计书 → C0/C1/C2 设计与实现复核；在用户明确授权前不提交/不合并/不推送。
- **本次未做/未验证**：只写了文档；没有编译、测试、门禁、仿真；没有证明 D2 设计可编译或判据已通过。

## 九、2026-10-17 19:55 D3 工资链设计落盘（侧线工作树）

> **性质**：本段是 D3 设计交付记录；未改业务/测试、未编译、未跑门禁、未提交/同步。

- **新增设计书**：`docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md`（1052 行，未跟踪）。内容覆盖 U15–U21 逐字原话、工资 posting/quote/进位余数/allowIdle 状态、U20 非对称调价、角色级劳动匹配与 idle、W1 工资前置、U21 直付/抵物/成债、tick 时序、失败词表、不变量、正向/负向判据与 W1–W5 文件所有权。
- **用户原话核对**：U15–U21 与 D2 §1 逐字比对一致；U13/U14 不在 D3 范围，未重复收录。
- **D3 对 D2 的最小修正**（D3 文档 §0.3；未直接改 D2）：回执需逐 tick 逐 `(household, role)` 实际劳动；MONEY_WAGE 角色改用 posting 表达需求、不强制 roster participant；`candidateHouseholdsOf` 按角色类型分流；D2 §8.2 工资预计改用 posting target；W2 自动候选资格失败是 skip、roster 内 PRODUCT_SHARE 资格失败仍整实例拒绝。
- **控制方复核结论**：设计结构完整，未发现与用户原话/五条铁律/模块边界的直接冲突；以下实现前置项需在 W1/W2/W3 派单前由 D3 局部补充或由派单书明确：
  1. W1 依赖 quote 读口、W2 才落真实 posting 状态；W1/W2 的验收拆分与 fixture/依赖顺序须写死，避免 W1 以空读口“编译过但无能力”交账。
  2. W2 在 W3 未接时必须明确“含 MONEY_WAGE 角色的执行在何处 fail-closed”，防止匹配写入 allocations 后生产执行、形成无工资实际劳动。
  3. W3 的“可支付活动库存”在 D4 未接时如何取得“已投入/占用”事实须写死；不得把账户余额未经证明地当全部可支付，也不得静默跳过 U16 的再生产权益排除。
- **当前 git status**：2 个已跟踪文档修改（D1 更正注、本 status）+ 2 个未跟踪设计书（D2、D3）。无 Java/XML/POM/测试改动。
- **下一步**：先请用户审 D3 设计/工程默认；D2/D3 接受且用户授权提交/同步后，才可按 C0→C1→C2→W 顺序派实现。**当前不派 W1–W5 代码单**：C 批尚未实现，且设计书未提交/未获提交授权。
- **本次未做/未验证**：只写文档；没有编译、测试、门禁、仿真；没有证明工资链可实现或判据已通过。

## 十、2026-10-17 20:40 R2 最小修复实现、独立测试与提交检查点

> **性质**：U22“先修 bug”执行记录；R2 已做最小 fail-closed 收口，仍未完整关闭多实例真实分配。

- **文档检查点**：commit `2ca37052`（D2/D3/U22-U23 裁决/R2 最小设计入账，已推送）。
- **R2 约束设计**：`docs/superpowers/specs/2026-10-17-d032-r2-shared-asset-minimal-design.md`。最小口径：单 unit scope 沿用整池；同 `(industry, operator)` scope >1 unit ⇒ 每 unit allocation 空、scope 汇总进 `unallocated`、每 unit 具名 `asset-scope-shared:awaiting-D4`；不伪造等分，完整 D4 实占/占用期/释放后置。
- **R2-I 实现**：commit `3eaa0474`（已推送）。新增 `AssetBudgetBook`；`SettlementIndex` 只算一次 `AssetBudget`；`ProductionProcessBook`/`LaborQueueBook`/`ExpectedProfitBook`/`EconomySettlement.driveProductionDay`/`EconomyEnterpriseSettlement`/`EconomyEntrySettlement`/`MarketDemandBook`/`DebtCapacityBook`/`HouseholdClassRule`/app 读口消费同一结果。economy/app `-DskipTests compile` rc=0；spotless check 绿。
- **R2-T 独立测试**：commit `9e35a11f`（已推送）。测试台账 `.superpowers/sdd/2026-10-17-d032-r2-minimal/test/`（本机忽略，不进 git）。
  - targeted：economy R2+Unified+N23 13/0/0；app R2 readout 2/0/0；
  - 模块：economy **405/0/0**；app **1014/0/0/7** + 前端 **412/412**；
  - 变异自证 5 组：整池复制回 unit、只修 SettlementIndex/ExpectedProfitBook 仍扫份额、执行在 progress 后拒绝、单实例误清零、unallocated 静默丢弃——均改坏即红、还原 md5 一致、重跑绿；
  - 原 R2 复现 `100 → 两个实例各 100 = 200` 已变为：两 unit allocation 空、`unallocated=100`、均具名拒绝、Σ规模=0；`OwnershipStake` 一字不改。
  - 未发现生产代码 bug；唯一全量失败是 `N23` 源码行号漂移，已按既定测试载体同步，消息与判据未放宽。
- **仍未关闭/未验证**：多实例真实实例/地点占用、占用期、释放、统一预算仍归 D4/A；R2 只证明“不再整池复制”，不写成完整关闭。未跑 `clean verify`/`tools/gate.sh`；app 7 条 skip 为既有；未做真实世界/18Lvt 多实例长周期；未逐消费者分别做独立变异（由 P4/P5 同源断言与台账覆盖）。
- **下一步**：处理 D3/D2 设计债（A1–A5、B1–B4、U23 三项更新），再完成 D4 正文与 D1–D4 统一；之后独立复核、终局门禁、C0 派单。提交/推送按小分支检查点继续，主树仍未合并/同步。
