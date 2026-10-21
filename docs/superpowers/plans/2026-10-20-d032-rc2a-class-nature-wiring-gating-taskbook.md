# RC2a 任务书：class nature 接线与 S3 位置门控

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v2 S2/S3/S7；v4 §4（classness）、§3（LANDED/LANDLESS 位置）。
> 依赖：RC1 已完成（`ClassNature`、mode `supportedClassNatures`、`ProductionRole.classNature` 已编译）。
> 本单不做 32hex 两户 seeder（RC2b），不改持久组件。

## 1. 目标

把 RC1 的 class nature 从“类型存在”接到运行时判据：

```text
S2 组织阶段：LANDLESS 位置不得被自动组织成自己的生产 unit；
S3 位置选择：LANDED 户不得占 MoneyWage 雇工位置；
             LANDLESS 户不得占 ProductShare/owner-organizer 位置；
             无 class nature 的旧位置保持既有行为。
```

本单不改变生产/工资/市场公式，只增加 fail-closed 门控。

## 2. 当前代码事实（file:line）

- `ProductionRole.java` 已有 `Optional<ClassNature> classNature`（RC1）。
- `DefaultProductionModes.java` 已把 `grain_farm.operator=LANDED`、`grain_farm.hired_laborer=LANDLESS`。
- `ProductionPayloadSeeder.java:133-149`（mode 节点）和 `:189-205`（position 节点）尚未把 class nature 写进 seed wire；因此当前持久状态里 mode set/role classNature 会落成空。
- `EnterpriseOrganizationBook.java:721-725`：`shouldProduce` 只看 laborRole/surplusRole，会把 `hired_laborer`（LANDLESS）也当成可自动组织的位置。
- `ProductionPlanBridge.java:212-248`：`positionLocationRejection` 只做跨 hex 与 LAND marker 判据，没有 class nature 判据。
- `SettlementDayExpectedProductionPipeline.java:67` 与 `SettlementDayActualProductionPipeline.java:211/268` 都调用同一 `positionLocationRejection` ⇒ 门控放在这里可同时覆盖 S3/M6。
- `ProductionReferenceValidation.java`（RC1）已有 role classNature ⊆ mode set 的候选态检查。

## 3. 冻结行为

### 3.1 seed wire

`ProductionPayloadSeeder.productionModeNodes()` 每个 node 增加：

```json
"classNatures": ["LANDED","LANDLESS"]
```

- 来自 `mode.supportedClassNatures()`；空集可发空数组或省略，但为了形状确定**显式发空数组**。
- `productionRoleNode()`（economy.Seed 的 `classPositions[]` 节点）每个 position 增加可选：

```json
"classNature": "LANDED"
```

- 来自 `position.classNature()`；`Optional.empty()` 时**不发键**（保持非农村旧节点形状）；present 时发枚举 name。
- **必须补 economy.Seed 的解析面**：`simos-economy/.../spi/EconomyPolicyPayloads.productionRole(JsonNode)` 当前只读
  `id/modeId/name/relationToMeans/laborRole/surplusRole/ruleExtensions`；本单增加可选 `classNature` 解析并传入
  `new ProductionRole(..., Optional<ClassNature>, ruleExtensions)`。这不是 production 的 `ProductionRoleDefinition` 载荷，
  不要改错模块。

### 3.2 派生 classness

新增 economy 内唯一 helper（放 `time` 或 `model` 内均可）：

```text
ClassNatureBook.classNatureOf(EconomyDayView base, HouseholdId household)
    -> Optional<ClassNature>
```

- 读 `base.classStandings().get(household).effectivePositionIds()`；
- 对每个 position 读 `base.classPositions().get(id).classNature()`；
- 忽略 empty；若出现两个不同 present class nature ⇒ `IllegalStateException`（契约故障，不降级）；
- 没有任何 position/没有 classNature ⇒ `Optional.empty()`，保持旧行为；
- 不读 LAND 数量、不读库存、不把 classness 做成持久字段。

### 3.3 S3/M6 门控

`ProductionPlanBridge.positionLocationRejection(...)` 在每个 participant 上增加判据（顺序在 role 解析之后、跨 hex 判据之前或之后均可，但同一 helper）：

```text
nature = ClassNatureBook.classNatureOf(ctx.dayView, participant.household())
if nature == LANDLESS:
    role 必须是 MoneyWage（工资角色）；否则 reject "class-nature-landless-cannot-own"
if nature == LANDED:
    role 必须是 ProductShare（owner/operator）；否则 reject "class-nature-landed-cannot-wage"
if nature empty:
    保持既有行为
```

- 组织者 participant 必须属于 LANDED；不得让 LANDLESS 组织者进入 roster。
- 同一 role 的 payment 判据只读 `ProductionRoleDefinition.payment()`；不得新增第二套“岗位类型”。
- 拒绝理由必须具名；Expected/Actual 产线已有的拒绝日志照常发 INFO（包含 unit/participant/reason）。
- 非土地型产业若 participant classNature present，也按同一规则门控；classNature empty 的旧位置不误伤。

### 3.4 组织阶段

`EnterpriseOrganizationBook.shouldProduce(ProductionRole position)` 增加：

```text
position.classNature() == Optional.of(LANDLESS) ⇒ false
```

- 这样 LANDLESS 位置不会被 `EnterpriseOrganizationBook` 自动建 unit / relation / labor commitment；
- LANDLESS 户仍可通过名册作为 hired participant（其实际劳动由 S3/M6 选择和工资角色处理）；
- `displaced` 的既有跳过保留；不恢复流民。

## 4. 必须达到的行为

1. seed 产物中 `grain_farm` mode 有 `classNatures=["LANDED","LANDLESS"]`；`operator` 有 `classNature=LANDED`，`hired_laborer` 有 `classNature=LANDLESS`；
2. 有 class nature 的家户：
   - LANDED 不能入选 MoneyWage 位置；
   - LANDLESS 不能入选 ProductShare/owner-organizer 位置；
   - LANDLESS 位置不自动组织 unit；
3. 没有 class nature 的旧位置/旧户保持既有行为；
4. 同一户多个 position 出现冲突 class nature ⇒ 契约故障；
5. 不改变 class nature 以外的任何公式、状态形状、市场/债务/工资金额。

## 5. 允许修改的文件

```text
simos-app/src/main/java/io/mosire/simos/app/world/ProductionPayloadSeeder.java
simos-economy/src/main/java/io/mosire/simos/economy/time/ProductionPlanBridge.java
simos-economy/src/main/java/io/mosire/simos/economy/time/EnterpriseOrganizationBook.java
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayExpectedProductionPipeline.java（只改拒绝日志白名单/注释，如需要）
simos-economy/src/main/java/io/mosire/simos/economy/time/SettlementDayActualProductionPipeline.java（同上）
simos-economy/src/main/java/io/mosire/simos/economy/model 或 time 下的新 helper（ClassNatureBook）
simos-economy/src/main/java/io/mosire/simos/economy/spi/EconomyPolicyPayloads.java（classPositions 的 classNature 解析）
```

**禁止**：`EconomyData`/ChangeSet/Codec 持久组件、seeder 的人数/土地分配、32hex fixture、测试文件、主树、commit/push。

## 6. 验收锚点（测试 owner 后补）

正例：

1. seed 产物 roundtrip 后 mode/position classNature 仍在；
2. classNature=LANDED 的家户在 MoneyWage roster 位置 ⇒ 拒绝理由 `class-nature-landed-cannot-wage`；
3. classNature=LANDLESS 的家户在 ProductShare/organizer 位置 ⇒ 拒绝理由 `class-nature-landless-cannot-own`；
4. classNature empty 的旧户/旧位置仍可走旧路径；
5. `EnterpriseOrganizationBook` 不为 LANDLESS position 建新 enterprise；
6. 同一户有效 positions 一个 LANDED 一个 LANDLESS ⇒ 契约故障。

负例：

- 任何按 `SocialClassId` 猜 classness 的第二套判断 ⇒ 红；
- `LANDLESS` 自动组织 farm unit ⇒ 红；
- 拒绝理由丢失/被改成静默 continue ⇒ 红；
- 旧无 class nature 路径被新门控误伤 ⇒ 红。

## 7. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-rc2a-class-nature-wiring-gating/impl-ledger.md`。
- 报告列：改动文件、编译 rc、seed wire 字段、门控拒绝理由、旧路径兼容、可能失效断言、未验证项。

## 8. 本责任区不做

- 不做 32hex 两户 seed（RC2b）；
- 不做 R1 merge、S7-B/C、flow table；
- 不做 classness 持久字段；
- 不重排日序、不改工资/债务/市场公式；
- 不实现 §8 同阶级整组织交易。

## 9. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣
R2：有地的家户挂单根据库存情况等写好的判断挂单，如果是没地的家户买到了土地，那么就把买到的土地连同那一部分比例的人口全部转移走，如果是有地的家户买到了土地，那就是买到了，走正常的生产支配流程
```

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v3.md` §1，2026-10-20。

```text
这样一来，家户的阶级性也就必须跟着生产方式的定义走了，经济模块和生产方式模块必须根据阶级性的代码定义来维护，农业生产方式的具体维护路径如上
```
