# T1 测试 owner 任务书：v4 R1–R6 全链测试与 360 tick

> **状态：测试 owner 任务书；现在进入统一测试阶段。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；分支 `parallel/side-module`。
> 生产代码：RC0a、RC1、RC2a/b、RC3、RC4-A/B/C/D、RC5、RC6、RC7 已落盘并 main compile 绿。
> **生产代码 writer 已停手。** 测试 owner 只改 test/fixture；发现生产实现缺陷时具名上报，不自行改 main。

## 0. 当前续跑交接（2026-10-20）

前一任 T1 测试 owner 已停；测试/fixture 改动留在工作树。已由生产 writer 修复并 compile 绿的缺陷：

- DEF-1 `LegacyClassStructure` 缺 `landed_peasant`；
- DEF-2/2b 卖冻结按当刻 `stock - base` 轴封顶；
- DEF-3 flow table 陈表 prune；
- DEF-4 零值 frozen 合户 preflight 放行；
- DEF-5 二元农村 LANDED/LANDLESS 跳过旧 `ClassSlideSettlement`，向下流动交 RC7 flow table。

续跑从 focused 复跑开始：

```bash
tools/mvn-lock.sh -q -Dtest=EconomyCycleHealthTest,Z7RemittanceE2ETest \
  -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-app -am
```

focused 绿后再跑 gate full → 360 → 四类变异自证。前一轮的 evidence/progress 在
`.superpowers/sdd/2026-10-20-d032-t1-test-owner/`（本机留痕，不在 git）。

## 1. 目标

1. 迁移/修复既有测试到新架构；
2. 补 R1–R6 正负例；
3. 跑全仓 `clean verify` / `tools/gate.sh full`；
4. 跑独立 32hex 360 tick；
5. 对四类关键不变量做变异自证。

## 2. 已知必须迁移的测试面

### 2.1 EconomyData 第 37 个组件

`EconomyData` / `EconomyChangeSet` 已从 36 组件变成 **37 组件**，末尾新增：

```text
Map<HouseholdId, HouseholdFlowTable> householdFlowTables
```

已知会红：

- `simos-economy/src/test/java/io/mosire/simos/economy/change/EconomyRoundTripTest.java`
  - 仍按 36 参构造 `EconomyData`；
  - `getRecordComponents()).hasSize(36)` 要改 37；
  - 需补 `householdFlowTables` 的 mutate/restore/roundtrip 分支；
- `EconomyCodecTest` / `EconomySeedHandlerTest` 等如仍按 36 参构造或断言组件数，同样迁移；
- `EconomyChangeSet` 的组件数/反射断言同步。

### 2.2 构造器数量/签名

- `EconomySession` 现在有 3/5/6/7 参构造器；
- `EconomyDayStepper` 现在有 5/7/9/10/11 参构造器；
- `T2bInvalidEntryPointsAndProbeCostTest` 若反射固定构造器数量，需要同步；
- `ProductionRuntime` 新增 `rekeyCycleContributions(...)`；动态代理不受影响，手写 fake 要补。

### 2.3 seed / class nature / culture

- `ProductionModeDefinitionView` 多 `supportedClassNatures`；
- `ProductionRole` 多 `Optional<ClassNature> classNature`；
- `EconomyPolicyPayloads` 的 `classPositions[]` 支持可选 `classNature`；
- `ProductionPayloadSeeder` 的 modes 发 `classNatures`，positions present 时发 `classNature`；
- `HouseholdCulture` 多 `label`，旧两参构造=空串；
- 任何精确 JSON/Map 形状、record 等值、构造器参数数量断言都要迁移。

### 2.4 32hex seeder

- 农村池：2 个 `SocialClassId`（`landed_peasant/landless_laborer`）；
- 每农村 hex 160 / 40；
- 无 displaced 户；
- LAND 全归 landed；
- landless 无自营 grain_farm unit，作为 landed farm roster 的 hired_laborer；
- 城市四阶层/政府户/市场区保持既有配置；
- `Rural32HexWorld`、`Rural32Hex360DaySim`、`MultiTickSimRunner`、`EconomyTestWorld` 等 fixture 的旧四阶/流民/160 unit 断言需迁移。

### 2.5 行为断言会变

- 市场现在在 S6 工资之后，市场买入不再供应当 tick S4 消费/生产；
- S7-M2 用工资后/收获后余额清偿；本 tick 新工资债 id cutoff；
- LANDLESS 买地会产生账户/人口变化、临时 LAND 清零；
- 同 key 家户会在 step 前合并，source 保留 0 人口壳；
- S7-C flow table 会新增第 37 组件；
- `ctx.base.demands()` 已统一为 `ctx.dayView.demands()`。

## 3. 必须覆盖的正/负例

### 3.1 Class nature / 门控

- `grain_farm` mode set = `{LANDED,LANDLESS}`；operator/hired_laborer 位置正确；
- LANDED 户不能占 MoneyWage；LANDLESS 户不能占 ProductShare/organizer；
- LANDLESS position 不被 `EnterpriseOrganizationBook` 自动组织；
- class nature empty 旧路径不变；冲突 class nature ⇒ 契约故障。

### 3.2 R1 同 key 合并

- mergeKey=(hex,cultureKey,mode,classNature)，survivor 规则；
- population/money/goods/debt/experience/LAND 守恒；
- production unit.operator/enterprise.organizer/relation.operator/roster organizer 全为 survivor；
- cycleContributions 同 role 相加；
- source 0 人口壳保留；S3 只看到 survivor；
- 自债净额，无 debtor==creditor；
- 无同 culture target / frozen / treasury / planAssetSources 等负例具名 fail-closed。

### 3.3 R2/R3 S7-B

- LANDLESS 买方多 hex 买地：全部 LAND 转对应 hex LANDED；
- 人口按各 hex LAND 权重最大余数拆；钱/货/债/经验随人走；
- sourcePopulationBefore 逐笔递减；
- migrants=0 仍清 LAND；
- 临时 LAND 不持久化；
- LANDED 买方/债务抵地不触发 S7-B；
- 无 target/mode mismatch/残地/LAND 不守恒 ⇒ fail-closed。

### 3.4 R5 向下流动表

- 第 37 组件 roundtrip/Codec/ChangeSet；
- 每市场轮更新 lastObserved；首轮只建表不流动；
- debt 扩大且 target 存在/可创建 ⇒ flow ≤P-1，LAND 不动；
- target 不存在时创建 0 人口 LANDLESS 壳 + targetIsNew outbox；
- 同 hex/同 culture/mode 优先；跨 hex/landed target ⇒ 红；
- 每市场轮最多一次；0 人口 source/target 不被当作活户。

### 3.5 RC5

- LANDLESS 债权人 LAND 腿跳过，余债保留，LAND 留债务人；
- 货币/商品腿照常；
- LANDED/无 class nature 旧路径不变。

## 4. 测试 owner 的执行顺序

1. `git status --porcelain`；确认生产 writer 已停手。
2. 先跑 targeted test-compile/focused tests，列出失败：
   ```bash
   tools/mvn-lock.sh -q -DskipTests test-compile -pl simos-economy,simos-app -am
   ```
3. 按 2.1→2.5 迁移测试/fixture；每次只修测试面。
4. 补 3.1–3.5 正负例。
5. 跑相关模块 test 与 app test。
6. 跑全仓：
   ```bash
   tools/gate.sh full
   ```
   长命令用受管后台进程，记录 rc；不得把“未跑/被杀”写成通过。
7. gate 通过后，用当前 classes 跑独立 360：
   ```bash
   SIMOS_MULTITICK_SKIP_BUILD=1 \
   JAVA_OPTS='-Dsimos.economy.logLevel=INFO' \
   tools/multitick-sim.sh rural-32hex-360d
   ```
   记录 rc、CSV/MD 哈希、360 行 status、ADVANCE_EXCEPTION。
8. 四类关键变量变异自证：
   - LAND 每 hex 守恒；
   - 合户不丢人口/资产/债；
   - `LANDLESS+LAND>0` 不落盘；
   - `targetIsNew`/0 人口 source 不参与 S3。

   变异必须“改坏 ⇒ 当场红 ⇒ 恢复 ⇒ md5 逐字节一致”；无法表达就如实写“等价存活”。

## 5. 测试 owner 边界

- **允许改**：`simos-*/src/test/**`、测试 fixture、`.superpowers/sdd/**` 证据、测试数据。
- **禁止改**：`simos-*/src/main/**` 生产代码、主树、seeder main、production/culture 契约；发现生产缺陷只报告。
- 不 `git commit`/`push`。
- 一个 worktree 同时只跑一个 Maven；用 `tools/mvn-lock.sh`。
- gate/360 长命令用受管后台任务；不得在另一个 Maven 还在跑时并行启动。
- 不把旧 RF6 的 360 PASS 当新代码通过；必须新跑。

## 6. 交付报告

必须含：

- 测试迁移清单；
- 新增测试清单（按 R1–R6/RC5–RC7 分类）；
- targeted test 结果；
- `tools/gate.sh full` rc、tests/failures/errors/skipped、frontend 数、SpotBugs 数、耗时；
- 360 rc、CSV/MD 哈希、status 行数、关键 ADVANCE_EXCEPTION；
- 四类变异自证结果；
- **没做/没验证/blocker**；
- 发现的生产实现缺陷（file:line + 复现 + 期望/实际）。

## 7. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣
R2：有地的家户挂单根据库存情况等写好的判断挂单，如果是没地的家户买到了土地，那么就把买到的土地连同那一部分比例的人口全部转移走，如果是有地的家户买到了土地，那就是买到了，走正常的生产支配流程
R3：完全可以
R4：把这个人口上限取消掉！此外，单个家户不应该有一个对应的文化属性标签吗？如果没有，那就紧急加一个只有自然语言描述的标签，让文化模块维护
R5：是的，随着人走
R6：测试世界依旧32hex，每个hex一个有地农民家户一个无地农民家户就行，政府和市场区配置照旧
```
