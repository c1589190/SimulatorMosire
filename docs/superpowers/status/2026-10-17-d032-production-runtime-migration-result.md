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

- **新增设计书**：`docs/superpowers/specs/2026-10-17-d032-wage-chain-design.md`（1052 行，未跟踪）。内容覆盖 U15–U21 逐字原话、工资 posting/quote/进位余数、allowIdle 状态（历史：该状态已由 U23 撤回）、U20 非对称调价、角色级劳动匹配与 idle、W1 工资前置、U21 直付/抵物/成债、tick 时序、失败词表、不变量、正向/负向判据与 W1–W5 文件所有权。
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

## 十一、2026-10-17 21:15 D1–D3 设计整合与 U23 收口（侧线工作树）

> **性质**：只改 D1/D2/D3 设计正文；未改 Java/XML/POM/测试，未跑 Maven/测试/门禁，未提交 main/同步主树。

- **设计提交**：commit `4c34b3d9`（已推送 `origin/parallel/side-module`）。D1 215 行 / D2 797 行 / D3 1455 行。
- **U23 三项已并入当前有效正文**：欠薪零息（区分普通贷款、旧工资欠款不再 20‰）；撤回 `allowIdle`/`householdLaborChoices`，改为下一 tick 生存条件资格 `idleEligible` + 独立选择 `idleChosen`；抵物折价 `BarterDiscountPolicy`（仅 `LOCAL_BID`/`LOCAL_ASK` + `discountPerMille` 可 <1000）由 production 定义、economy 执行。
- **A1–A5**：候选窗口统一 `cycleDays`、实际 due 逐 tick 不乘；idle 独立 `IdleOption`/`Plan.idleChosen`，不经过 `queueable()/maxAbsorbable`；候选级 `ProductionWageDefault`（33）解决新进入报价闭环，执行期 `ProductionWagePosting`（34）仍唯一；降价条件 `satisfiedStreak > decreaseDelayTicks`；产品池措辞收窄。
- **B1–B4**：批次重排 W2a→W1→W2b→W3→W4→W5；W2b 未接 W3 时在任何抽料/写回/进度/经验/实际劳动前全 tick fail-closed；W3 唯一 `WagePayableInventoryLookup`（地点用 `production.contract.ProductionLocation`），D4 未接时库存腿跳过并成债；同 payer×location 预算，跨格 `wage-payer-location-unsupported`。
- **U23-b 选择细化（控制方工程默认，用户可推翻）**：新增 `EconomyPolicyKey.SURVIVAL_IDLE_POLICY`，默认 `PREFER_IDLE_WHEN_SUBSISTENCE_UNMET`（1）使 `idleEligible=true` 有实际 `IDLE_CHOSEN` 路径；`BEST_EFFORT_WORK`（0）允许正收益工作即使不足生存仍被选中。该键不是 per-household `allowIdle`，不恢复 `householdLaborChoices`；默认值不冒充用户裁定。
- **SurvivalOutlook 纯只读契约**：D3 §2.5.1 定义输入/输出、逐需求行覆盖、库存/收入去重、同劳动/资源预算组合、空集合具名原因、缺价/不可达不折算、预测不抵实际消费；O9（Social 下一 tick 需求投影）、O10（可达购买价/供给/币种事实）、O11（既有债务/不可用权益快照）仍为开放工程项。
- **组件号（R-18 当前更正；由 D3 §9.1 / D4 §8.2 局部取代旧记录）**：31 基线；32 `productionRosters` / 33 `productionWageDefaults` / 34 `productionWagePostings` / 35 `wageCarryRemainders` / 36 `assetOccupancies` / 37 `inventoryReservations`。D2/D3 只新增 32–35，D4 后续 36/37；“D2/D3 无第 36 个”只描述当时写面，不是全库禁令；36 不得让给已撤回的 `allowIdle`/`householdLaborChoices`。
- **原话核对**：U15–U21 D2/D3 逐字相同；U22/U23 与 architecture source/decision doc 逐字匹配。`git diff --check` 通过；无 `LOCAL_LAST_TRADE`/`referencePriceOf`/`SettlementLocationRef` 残留。
- **未做/未验证**：所有 P/负向判据仍只是设计判据；D4 正文未完成；未实现工资链/生存判定/抵物；R2 多实例真实分配仍归 D4/A；未跑 `clean verify`/`tools/gate.sh`。下一步：D4 正文与 D1–D4 统一，再独立复核、提交检查点、C0 派单。

## 十二、2026-10-17 21:25 D4 资产与占用设计落盘（侧线工作树）

> **性质**：只新增 D4 设计文档；未改 Java/XML/POM/测试，未跑 Maven/测试/门禁，未提交 main/同步主树。

- **D4 设计**：`docs/superpowers/specs/2026-10-17-d032-asset-occupancy-design.md`（876 行，commit `3da1237d`，已推送）。
- **核心形状**：`OwnershipStake` 最小增加必填 `ProductionLocation location`，仍是唯一资产数量权威；新增 `AssetOccupancyGrant`（引用 share/unit、数量、半开区间、ACTIVE/RELEASED、释放回填）与 `InventoryReservation`（商品库存占用声明，不是第二本余额）。D4 后 `EconomyData` 第 36/37 组件；D2/D3 已占 32–35，旧“第 40/41/42”编号作废。
- **统一预算**：`AssetOccupancyBook` 按 industry/operator/location/assetKind/from/to 形成 scope；先认 ACTIVE 已占，单实例拿整池保持逐值不变，多实例按请求量整数最大余数分配、按 share id 稳定拆 grant；农业/工具 horizon 为控制方工程默认，到期/取消释放并回填；预计/排队/执行/读口/债务容量/需求簿读同一份预算。
- **R2 路径**：D4-I 编译 + D4-T 独立测试 + 旧路径清零 + 统一入口守卫 + 原复现改判，才允许删除 `asset-scope-shared:awaiting-D4` 并声明 R2 完整修复；D4-D0 本身不关闭 R2。原 `LAND=100` 两实例按地点/份额真实分配，Σ≤实有。
- **W3 接入**：D4 首次实现 `WagePayableInventoryLookup`，逐字保持 D3 §6.9 的 `payableInventory(payer, ProductionLocation)`；返回已扣 `frozenGoods` + ACTIVE `InventoryReservation` 的最终商品量，`Optional.empty()` = 不可用而非 0；W3 不二次扣减、不重定价、不回退整账户余额；同 payer×location 一次快照，跨格/不可用具名。
- **模块边界**：资产数量/占用/预留归 economy；生产定义/实例/技术归 production；账户余额/冻结归 actor；Social 不碰资产；app/Core 只编排。D4 不新增玩法命令。
- **用户原话**：U8/U16/U21/U22/U23 逐字收录并核对；U23 的零息/生存不从业/折价边界仅作输入，D4 不重定义。
- **未做/未验证**：全部 P1–P22/负向/数值例仍只是设计判据；R2 完整功能未实现；`WagePayableInventoryLookup` 未实现；未跑 Maven/测试/仿真/门禁。下一步：D4-I 单 owner 实现（只编译）→ D4-T 独立测试 → 控制方复核；之后 W3 串行接入，R2 关闭须 D4-T 证据。
- **AGENTS 索引**已补 D2/D3/D4/D3 裁决列条目；本轮未改任何代码或测试。

## 十三、2026-10-17 D1–D4 独立复核修正记录（侧线工作树）

> **性质**：只改 D1/D2/D3/D4 设计、D3 裁决文件历史标记、重规划局部更正与本状态记录；未写/改 Java/XML/POM/测试，未跑 Maven/测试/仿真/门禁，未提交/推送/同步主树。

- **复核输入**：`docs/superpowers/reports/2026-10-17-d032-d1-d4-design-review.md`（P1=12 / P2=6，R-01..R-18）。
- **当前组件号（唯一口径）**：31 基线 → 32 `productionRosters` → 33 `productionWageDefaults` / 34 `productionWagePostings` / 35 `wageCarryRemainders` → 36 `assetOccupancies` / 37 `inventoryReservations`；D2/D3 只新增 32–35，D4 后续 36/37。
- **已修要点**：
  - R-01：D4 的 `AssetKind` 统一 `SMALL_TOOL`/`LARGE_TOOL`；前者 TICK、后者 CYCLE；`TOOL` 输入具名拒；两值各自往返判据。
  - R-02：D2-I2 改为 `PRODUCT_SHARE` 角色必须有 participant，`MONEY_WAGE` 可无 participant（目标由 posting/default 表达）。
  - R-03：D2/D3/status 的“无第 36”改为“D2/D3 只新增 32–35，D4 后 36/37”。
  - R-04：D3 §2.5.1 增加逐需求行覆盖算法，接入预计货币/实物/产品份额并明确去重顺序与来源 provenance。
  - R-05：W3 在 lookup/支付前按 payer 聚合，>1 settlement location 整组 `wage-payer-location-unsupported`，零部分支付；`AccountSession` 为户级账户权威。
  - R-06：D1/D2 原话附录补 U22/U23，input 改为 U13–U23。
  - R-07：D1/D2/D3 统一毫小时口径（1 小时 = 1000 毫小时）。
  - R-08：D4-I 明列全部 `OwnershipStake` 构造/seed 入口与 location 来源，app `EconomySeeder` 也在 D4-I 写面。
  - R-09：D2 增加 C0-A owner，负责 `AssetKind.java` 与旧 `TOOL` 引用扫描；测试归 C4。
  - R-10：`requestedScale` 默认 1 只留“已存在单实例且无请求”兼容路径，候选/新实例缺显式规模即拒。
  - R-11：D3 增加可持久 `DebtSource`（WAGE_ARREARS/RENT_ARREARS/ORDINARY_LOAN/LEGACY_UNKNOWN），`capitalizeArrears(WAGE)` 写 0‰，普通贷款保原 terms。
  - R-12：裁决文件头部加 U23 取代标记，U-D3-2/U-D3-3 标历史/关闭，原文不删。
  - R-13/R-14：D1 `ProductionRoleId` 当前包名改 `simos-economy-api`；`UNALLOCATED` 归 organizer residual 并补 400/400/100。
  - R-15：D4 location 规范 `<q>_<r>`，非法 `foo` ⇒ `asset-location-missing`。
  - R-16：`WagePayableInventoryLookup.java` 唯一新增/实现 owner = D4-I；W3 只消费已编译读口；批次 D4-I→D4-T→W3。
  - R-17：D1 末尾补未实现/未编译/未验证声明。
  - R-18：replan Phase W 标旧顺序并被 W2a→W1→W2b→W3→W4→W5 局部取代；status 组件号更正。
- **共享 economy 文件 owner 交接顺序（F-05，当前有效）**：`C0/C0-A → D4-C → C2 → W2a → W1 → W2b → D4-I → D4-T → W3`；C1/C3 只处理各自 production/app 面，不插入共享 economy 文件交接；C2/W2a/W2b/D4-I 不得并行改同一 `EconomyData`/`EconomyChangeSet`/`EconomyCodec`/`ExpectedProfitBook`/`LaborQueueBook`/`EconomySettlement`。
- **未做/未验证**：R-01..R-18 的修正仍只是设计文字；未实现、未编译、未跑测试/仿真/门禁，未验证任何数值例；未修 R2；`WagePayableInventoryLookup`/`DebtSource`/D4 occupancy 均未落形。
- **下一步**：对修正后的 D1–D4 做独立复评；通过后才派 D4-I（唯一写代码 owner，只编译）→ D4-T（独立测试）→ W3；任何提交/同步按另行授权。

## 十四、2026-10-17 第二次复核 N-01..N-09 定点修正记录

> **性质**：只改 D1/D2/D3/D4、D3 裁决文件、replan 与本 status；未改代码/测试，未跑 Maven/测试/仿真/门禁，未提交/推送/同步主树。

- **复核输入**：`docs/superpowers/reports/2026-10-17-d032-d1-d4-design-rereview.md`（N-01..N-09；R-01..R-18 多数关闭/部分关闭）。
- **N-01**：D4 `InventoryReservation` overcommit 改为 payer×commodity **全地点** ACTIVE 预留 + frozen ≤ `AccountSession` 余额；lookup 扣除该 payer 全部地点的 ACTIVE 预留，location 仅作结算/预算分组；D3 §6.9 同步；P18c/P38b 正负判据。
- **N-02**：新增共享 `WagePayerLocationPrecheck`（W2a 唯一实现）；W1/W2b/W3 在 lookup/候选成本/匹配/支付前按 payer 聚合，>1 settlement location 时 W1/W2b 候选不可行/skip、W3 整组拒绝，均不 lookup；P39b/P26 判据。
- **N-03**：D4 要求 `OwnershipStakeBook.ShareTuple` 纳入 `ProductionLocation`；Move/split/rebuild/transfer 使用 `source.location()`，跨地点不合并；源码核对 `OwnershipStakeBook.java:247-252,371-374,486-489`；P24b/负向判据。
- **N-04**：`LivelihoodAlternative` 增加 `incomeAvailableAtTick`；`expected*` 只表示该 tick 可交付量，不表示周期累计；SurvivalOutlook 只计 `incomeAvailableAtTick <= nextTickNeed.forTick` 的量；D2 §8.2 明确周期预计不得直接当下一 tick 口粮；P21/P49 判据。
- **N-05**：`DebtSource` 写入点/owner 补全：普通贷款/市场延期/铸币 = `ORDINARY_LOAN`；迁移/合户逐值保留 source；seed/codec/ChangeSet 显式 source；`LEGACY_UNKNOWN` 不冒充工资债；P29b/负向判据。
- **N-06（P2）**：D3 删除“D4 正文仍未完成/本次不修改 replan”旧句，改为当前现状：D4 已落盘、replan 已同步、lookup 未实现。
- **N-07（P2）**：D1 U14/U15 blockquote 去控制方括注，原文逐字保留；解释移到引文外的控制方说明。
- **N-08（P2，后续由 F-05 修正）**：曾引入 payer 预检与共享 economy 文件顺序；当前有效顺序以 §15 F-05 为准（`C0/C0-A → D4-C → C2 → W2a → W1 → W2b → D4-I → D4-T → W3`）。
- **N-09（P2）**：`SurvivalOutlookRequest.maxCombinations`（工程默认 4096，可配置，>0）限制确定性 DFS 枚举；超限 `survival-combination-limit` fail-closed，不返回截断 best/idleEligible；P50/负向判据。
- **未做/未验证**：仍只是设计文字；未实现/编译/测试/仿真/门禁；未修 R2；未验证任何 N 判据；未提交/推送/同步主树。

## 十五、2026-10-17 终局复核 F-01..F-10 定点修正记录

> **性质**：只改 D1/D2/D3/D4、replan 与本 status；未改代码/测试，未跑 Maven/测试/仿真/门禁，未提交/推送/同步主树。

- **F-01**：`WagePayerLocationPrecheck` 输入升级为 `WagePayerLocationSnapshot`：`existingSettlementLocations`（当前 revision 该 payer 全部已有 production unit/roster/posting 地点）+ `candidateSettlementLocations`（候选/本次 unit 地点），由 W2a 唯一实现；W1/W2b/W3 均用完整集合。已有 unit A + candidate B ⇒ W1/W2b 候选立即不可行/skip、W3 整组拒绝，均不 lookup。D3 §5.1/§5.2/§6.2/§6.9/§6.9.1、D4 §7.4/§7.5、D2 §10.1 与 P22/P26/P39b 同步。
- **F-02（历史，已由 F-05 取代）**：曾把共享 economy 文件顺序改为 D4-I 先于 W2a/W1/W2b；该顺序与 32→33/34/35→36/37 组件落形冲突，现仅作历史。
- **F-05（当前有效）**：D4-C 只冻结必选 `WagePayableInventoryLookup` + `AssetOccupancyLookup` 只读接口，先于 C2；C2 落 32、W2a 落 33/34、W2b 落 35、D4-I 落 36/37 并实现真实 lookup、D4-T 后 W3。共享 economy 文件顺序 = `C0/C0-A → D4-C → C2 → W2a → W1 → W2b → D4-I → D4-T → W3`；旧 F-02 顺序作废。
- **F-06**：D4 P18d 覆盖 A/B 各 600、户级余额 1000 的跨地点 overcommit：第二次创建/合并 ACTIVE 预留必须 `inventory-reservation-overcommitted`，零新增/零状态、不截断；同步负向表与例 J。
- **F-07/F-08（当前有效）**：`AssetOccupancyLookup` 与 `WagePayableInventoryLookup` 并列为 D4-C 必选只读接口；冻结 `AssetOccupancySnapshot`/`occupancyOf(unit, from, to)` 精确签名与 `UnavailableAssetOccupancyLookup` fail-closed 适配器；D4-I 唯一真实实现；C2 只用 `allocation` 填 probe/execution，empty ⇒ `asset-occupancy-unavailable`/零产出，空 Map ⇒ 合法 0，mismatch ⇒ `asset-occupancy-snapshot-mismatch`，旧 `usableAssets` 回退 ⇒ 红。
- **F-09/F-10（当前有效）**：`AssetOccupancySnapshot` 带现有 `io.mosire.simos.util.state.RevisionId`；`occupancyOf(unit, expectedRevision, fromTick, toTickExclusive)`；`snapshot.revision != expectedRevision` 或 unit/location/interval mismatch ⇒ ERROR `asset-occupancy-snapshot-mismatch`。D4-C 同时提供 `UnavailableWagePayableInventoryLookup`（empty）与 `UnavailableAssetOccupancyLookup`；W1/W2b 的 wage empty 记 `wage-inventory-scope-unavailable`、asset empty 记 `asset-occupancy-unavailable`，不得混用/回退账户余额；D4-I 唯一真实实现，D4-T 验证 C2/W1/W2b 只依赖 D4-C 接口。
- **F-03**：D3-I3/I25 改为 W1/W2b/W3 共用 `WagePayerLocationSnapshot` + `WagePayerLocationPrecheck`；W1/W2b 候选不可行/skip、W3 整组 `wage-payer-location-unsupported`，均零 lookup/零部分支付/零新债/零 carry 变化。
- **F-04**：新增 W2b 唯一 pure `NextTickIncomeProjection`（`time/NextTickIncomeProjection.java`）：输入 D2 cycle `Prospect`/候选 + `nextTick` + cycleStart/End + locked quote/target + 下一 tick 劳动计划，输出下一 tick 可交付 `expected*` 与 `incomeAvailableAtTick`。cycleEnd > nextTick 的 product share 本 tick 为 0；cycleEnd <= nextTick 才可计。缺 cycle 边界/quote/target/劳动计划 ⇒ `survival-income-projection-missing`/`survival-income-timing-unknown` fail-closed；D2 §8.2/P23、D3 §2.5.1.0/P51 同步。
- **未做/未验证**：F-01..F-04 仍只是设计文字；未实现/编译/测试/仿真/门禁；未修 R2；未验证任何判据；未提交/推送/同步主树。

## 十四、2026-10-17 21:xx D1–D4 设计终局复核通过（侧线工作树）

> **性质**：设计冻结记录；仍无代码/测试实现，未跑 Maven/测试/仿真/门禁。

- **终局复核**：`docs/superpowers/reports/2026-10-17-d032-d1-d4-design-final-review-5.md`，结论 **通过（设计层）**，P0=0/P1=0/P2=0。
- **D1–D4 设计可冻结**：D1 定义形状、D2 合作名册/分配、D3 工资链/生存判定/抵物、D4 资产占用/R2/W3 lookup 的当前有效正文已通过独立复核。终局检查点 HEAD `59a17b6c`（复核报告随后单独提交）。
- **组件号/owner 顺序**：C2=32 `productionRosters`；W2a=33/34 `productionWageDefaults`/`productionWagePostings`；W2b=35 `wageCarryRemainders`；D4-I=36/37 `assetOccupancies`/`inventoryReservations`。共享 economy 文件顺序：`C0/C0-A → D4-C → C2 → W2a → W1 → W2b → D4-I → D4-T → W3`。
- **D4-C**：只读接口批次（`AssetOccupancyLookup` + `WagePayableInventoryLookup` + `Unavailable*` fail-closed 适配器），不新增 EconomyData 组件；D4-I 唯一真实实现；D4-T 后 W3。
- **关键边界**：`AssetOccupancySnapshot` 带 `RevisionId`，跨 revision/地点/区间 mismatch 具名 ERROR；W1/W2b/W3 共用 payer 全地点快照预检；`SurvivalOutlook` 只计 `incomeAvailableAtTick <= nextTick`；工资债 0‰ + `DebtSource.WAGE_ARREARS`；普通贷款/租金按各自 source/terms。
- **未做**：没有任何代码落形；未编译、未跑测试/仿真/门禁；未修 R2 完整功能；`WagePayableInventoryLookup`/`AssetOccupancyLookup`/D4 occupancy/DebtSource/SurvivalOutlook 仍未实现；主树未同步。
- **下一步**：按 owner 顺序派 C0/C0-A（production 定义形状 + `AssetKind` 词表）单 owner，只写生产代码到编译，不写/不跑测试；然后 D4-C、C2、W2a… 串行。提交/推送按小分支检查点继续；C0 派单前需确认当前设计提交/工作树边界。

## 十五、2026-10-17 21:xx C0-A `AssetKind` 词表落形检查点

> **性质**：C0-A 生产代码已实现并编译/格式检查通过；未跑测试，未实现角色/份额/工资/占用。

- **提交**：`4a01326a feat(actor-api,economy,production,app)!: C0-A AssetKind 词表 SMALL_TOOL/LARGE_TOOL 落形`（已推送）。
- **词表**：`AssetKind = LAND, CATTLE, SMALL_TOOL, LARGE_TOOL, WORKSHOP, MACHINE, SHIP`；`TOOL` 退役。`AssetKind.parse` 是唯一文本解析点，旧 `TOOL` ⇒ `asset-kind-retired:TOOL`，未知值 ⇒ `asset-kind-unknown:<text>`；不做别名/兼容位。
- **旧引用**：`simos-*/src/main` 开工 26 处 → 收工仅 `AssetKind.java` 的 `RETIRED_TOOL = "TOOL"` 拒绝字面量；enum/载荷/seed/迁移/分类旧引用清零。所有旧通用工具默认映射 `SMALL_TOOL`，未发现明确大型设备上下文。
- **编译/格式**：`tools/mvn-lock.sh -q -DskipTests compile -pl simos-app -am` rc=0；`spotless:check` rc=0；`git diff --check` rc=0。未跑 test/verify/gate。
- **已知边界**：`HouseholdClassRule` 仅把 `SMALL_TOOL` 纳入 tool/workshop 分类，`LARGE_TOOL` 暂无 seed/生产者，未扩大行为面；`LARGE_TOOL` 的 seed/recipe/分类/测试归 C0/C1/C2/C4 后续确认。`ModeMigrationSettlement.isMobileAsset` 把 `SMALL_TOOL`/`LARGE_TOOL` 都视为可移动；当前无 LARGE_TOOL 生产者。
- **测试影响**：C4 需迁移至少 10 个测试文件的 `AssetKind.TOOL` 引用；C0-A 未写/未跑测试。
- **下一步**：C0（D1 落形：economy-api `ProductionRoleId` + production 角色/份额/经验/支付/条件/`RoleDistribution` 契约/state/ChangeSet/codec/payload/GM）单 owner；只编译、不写/不跑测试、不提交。之后 D4-C、C2、W2a… 串行。
