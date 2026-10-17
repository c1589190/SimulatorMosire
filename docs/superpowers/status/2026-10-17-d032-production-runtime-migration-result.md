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
