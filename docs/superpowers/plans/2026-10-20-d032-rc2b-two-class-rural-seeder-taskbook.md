# RC2b 任务书：32hex 二元农村 seeder

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v2 S2/S3/S4；v4 R1/R6、§4、§10。
> 依赖：RC0a/R C1/RC2a 已完成并 compile 绿。
> 本单只改 seeder/main 生产路径，不写测试、不改 `Rural32HexWorld` 测试夹具。

## 1. 目标

让新建世界（至少 32hex 测试世界）的**农村池**只产生：

```text
1 个 LANDED 农户（landed_peasant）
1 个 LANDLESS 农户（landless_laborer）
无 displaced 农户
```

并保证：

- 农村 LAND 全部归 landed 户，landless 户 LAND=0；
- landed 户有 grain_farm unit/roster；
- landless 户**不建自己的 grain_farm unit**，只作为 landed 户名册上的 hired_laborer 劳动力来源；
- 城市池、政府户、市场区保持既有配置（首都 700 城镇人口和现有 urban 四类池本轮不重写）；
- 生产 payload 已经携带 RC1/RC2a 的 class nature，seed 后 class position 能正确派生 classness。

## 2. 当前事实（file:line）

- `SocialClassId.java:31-71`：封闭词表没有 `landed_peasant`。
- `WorldBalanceSeeder.java:517-589`：`CLASS_*` 数组仍是四阶层；`DISPLACED_SEED_PER_MILLE=50`。
- `PopulationSeeder.java:230-271`：所有池都按同一 `CLASS_SHARE_PER_MILLE` 切四阶层 + displaced。
- `RuralHouseholdSeeder.java:136-167`：`poolCohorts` 固定按 `CLASS_IDS` 取四户，并把 `landless_laborer` 当 displaced。
- `RuralHouseholdSeeder.java:31-60`：`ruralCohort` 用四阶层的 fiber/ration 表。
- `RuralLaborSeeder.java:54-80,400-455`：`industryDailyLabor`、`stratumSlotOfLot`、`appendAllocation` 固定四阶层。
- `ProductionRuntimeSeeder.java:180-320,420-520`：grain_farm 逐四户建 unit、流民 hired participant；farm 配额按全部农村池发放。
- `LandOwnershipSeeder.java:40-92`：农村 LAND 权重四档；`grainFarmUnits` 给每户（含 displaced）建 unit。
- `IndustrySeeder.java:347-354`：所有产业 slots 固定四档。
- `ProductionRoleSeeder.java:99-123`：农村全部槽位映射 `grain_farm.operator`；`landless_laborer` 被映射到 displaced.laborer。
- `ProductionPayloadSeeder.java` 已在 RC2a 发 mode/position class nature。

## 3. 冻结口径

### 3.1 SocialClassId

新增：

```text
SocialClassId.LANDED_PEASANT = "landed_peasant"
```

- 追加到 `VALUES`/`ALL` 末尾，不改变旧四档索引（`IndustryClassSlots` 仍按前四个旧 id 工作）。
- `landless_laborer` 直接复用，作为农村无地户社会阶层。

### 3.2 农村参数

在 `WorldBalanceSeeder` 增加农村专用数组（保留旧 `CLASS_*` 给城市池/旧路径）：

```text
RURAL_CLASS_IDS              = {"landed_peasant","landless_laborer"}
RURAL_CLASS_NAMES            = {"有地农民","无地农民"}
RURAL_CLASS_SHARE_PER_MILLE  = {800,200}       // 200 农村人口 => 160 landed / 40 landless
RURAL_CLASS_LABOR_PER_MILLE  = {950,950}
RURAL_RATION_DAYS            = landed 120 / landless 30
RURAL_DISPLACED_SEED_PER_MILLE = 0
```

- 旧 `CLASS_IDS`/`CLASS_SHARE_PER_MILLE`/`CLASS_LABOR_PER_MILLE` 保留给城市池，不改成 2 档。
- `DISPLACED_SEED_PER_MILLE` 可保留常量，但新世界必须为 0；不得再生成 displaced 农户。
- `INITIAL_RATION_DAYS_BY_CLASS` 增加 `landed_peasant=120`、`landless_laborer=30`（landless_laborer 已存在则取农村值，不改旧城市/单位户路径的行为由测试 owner 复核）。

### 3.3 PopulationSeeder

- `appendPool` 按 `ResidenceKind` 选择 class ids/shares：
  - RURAL ⇒ `RURAL_CLASS_IDS` / `RURAL_CLASS_SHARE_PER_MILLE`，displaced = 0；
  - URBAN ⇒ 既有 `CLASS_IDS` / `CLASS_SHARE_PER_MILLE`，displaced 也按全局 0 不再生成。
- 仍是每 `(hex, residence, class)` 一户；农村池正好 2 户，无 displaced 户。
- `Seeding` 形状不变。

### 3.4 RuralHouseholdSeeder / RuralLaborSeeder

- `poolCohorts` 按 `ResidenceKind` 选择 class ids：
  - RURAL two classes；URBAN four classes。
- 取消“`landless_laborer` = displaced”映射；新的农村 landless 户是 regular class household，不能被 `withoutDisplaced` 排除。
- `ruralCohort` 使用 RURAL class ids/labor/shares/ration；`urbanCohort` 保持旧四档。
- `cohortGroup` 必须参数化 class ids/labor/ration（或拆成 rural/urban 两个入口），不得把 `CLASS_IDS` 写死。
- `RuralLaborSeeder`：
  - `industryDailyLabor(List<PopulationGroup>)` 按批次 residence 选 rural/urban 权重；
  - `participationAdjustedLaborMilli(group)` 按 residence 选 `CLASS_WEIGHTED_LABOR_PER_MILLE`；
  - `stratumSlotOfLot` 按 residence 判 slot 上界；
  - `appendAllocation` 的 fallback 按 residence 选 class ids/shares；
  - 保留旧 `industryDailyLabor(long[], long, long)` 重载给手搭夹具/城市路径，另加 rural-aware 重载或按 residence 参数化。

### 3.5 LandOwnershipSeeder / ProductionRuntimeSeeder

- `RURAL_FARM_LAND_WEIGHT_PER_MILLE = {1000,0}`（长度 2）。
- `grainFarmUnits` 只给 `share > 0` 的 landed 户建 unit；**不给 landless 建零土地 unit**；`displacedHousehold` 参数可删除或传 null。
- `ProductionRuntimeSeeder`：
  - `farmUnits` 只包含 landed 户；
  - farm 配额只发给 landed groups（landless 不进 farm 自营配额）；
  - landless 户作为 landed farm 的 `hired_laborer` participant 加入 roster，`plannedLabor = landless 可用劳动`；
  - 不再生成 displaced 初始雇工；
  - 若保留农村 weave plan，其配额只能从 landed groups 发，landless 不得同时被 weave 和 farm hire 双计；最简单工程默认：本单把 rural weave 配额视为 landed-only，landless 只通过 hired roster 提供劳动。
  - 城市 craft/trade/urban 四阶层保持原逻辑。

### 3.6 Industry / ProductionRole

- `IndustrySeeder.agriculture` 的 `slots` 改为 2 个农村 class slot：
  - `landed_peasant` 有地农民
  - `landless_laborer` 无地农民
  - 旧的 `CLASS_IDS` 四档只保留给城市产业（handicraft/merchant）。
- `ProductionRoleSeeder`：
  - `RURAL + landed_peasant -> grain_farm.operator`；
  - `RURAL + landless_laborer -> grain_farm.hired_laborer`；
  - `URBAN + old four classes` 保持现有映射；
  - 不再把 `landless_laborer` 映射到 displaced.laborer；displaced mode 目录可保留但新世界不播种它。

### 3.7 文化标签

- 本单不新增 culture 命令/seed 形状；culture label 已由 RC1 支持。
- 32hex 测试夹具（测试 owner）在 T1 通过 `culture.SetHouseholdCulture` 给所有户落统一默认 label。
- 本单不得把 culture 状态塞进 PopulationSeeder/EconomySeeder 第二本账。

## 4. 必须达到的行为

1. 32hex 农村池每 hex 2 个户，class id 为 `landed_peasant/landless_laborer`；
2. 农村 200 人按 160/40 切分（余数稳定，Σ=200）；
3. 无 displaced 户（所有池 displaced=0）；
4. landed 户得到全部该 hex LAND；landless 户 LAND=0；
5. landed 户有 grain_farm unit/roster；landless 户没有自己的 grain_farm unit；
6. landless 户作为 landed farm roster 的 hired_laborer participant，有可用劳动；
7. class position 为 `grain_farm.operator`（landed）/`grain_farm.hired_laborer`（landless）；
8. 城市池/政府户/市场区配置不变；
9. seed/payload class nature 与 RC2a 门控一致：LANDLESS 不会被组织成自身 farm unit，LANDED 不会被选为 wage role。

## 5. 允许修改的文件

```text
simos-economy-api/src/main/java/io/mosire/simos/economy/api/id/SocialClassId.java
simos-app/src/main/java/io/mosire/simos/app/world/WorldBalanceSeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/EconomySeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/PopulationSeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/RuralHouseholdSeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/RuralLaborSeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/LandOwnershipSeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/ProductionRuntimeSeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/IndustrySeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/ProductionRoleSeeder.java
```

必要时只改编译适配：

```text
simos-app/src/main/java/io/mosire/simos/app/world/GovernmentSeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/SeederModels.java
```

**禁止**：`Rural32HexWorld`/任何 `src/test/**`、`EconomyData`/ChangeSet/Codec、production/culture 契约、LAND 市场/债务/R1/S7 逻辑、主树、commit/push。

## 6. 失败/守恒边界

- 每个农村 hex 的 `classHouseholds.size()==2`；landed share 必须 >0；landless share 必须 =0；
- 每 hex `Σ landHoldings == landArableMilliMu`；
- 不得出现 landless 户的 grain_farm unit；不得出现 displaced 户；
- 不得把 landless 同时放进 farm 自营配额、weave 配额和 hired roster 造成劳动双计；
- 同一 `(hex, residence, class)` 仍只能有一个 Social household；
- 旧城市四阶层路径必须能编译，不得因为农村数组变 2 而访问 `CLASS_IDS[2]` 的农村调用点。

## 7. 验收锚点（测试 owner 后补）

正例：

1. 32hex世界：每 hex rural 2 户、总计 64 个农村户；无 displaced；
2. landed/landless = 160/40（每 hex），LAND 全归 landed；landless LAND=0；
3. landless 户没有 `ProductionUnitId`（或至少没有可组织 farm unit）；landed farm roster 有 landless hired participant；
4. `ProductionRoleSeeder` 得到正确的 operator/hired_laborer 位置；
5. class nature 门控通过，LANDLESS 不自动组织，LANDED 可作为 operator；
6. 城市池、政府户、市场区数量与旧 fixture 一致；
7. 生产 payload roundtrip 后 class position/class nature 不丢。

负例：

- 农村仍出现 4 户或 displaced ⇒ 红；
- landless 有 LAND 或 landless 有 farm unit ⇒ 红；
- landless 劳动同时出现在 farm 自营配额和 hired roster ⇒ 红；
- 城市四阶层索引越界/错误复用农村 2 档 ⇒ 红；
- LAND 总量不守恒 ⇒ 红。

## 8. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy-api,simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/test-compile/verify/package；不 commit/push。
- 账本：`.superpowers/sdd/2026-10-20-d032-rc2b-two-class-rural-seeder/impl-ledger.md`。
- 报告列：改动文件、编译 rc、农村/城市数组分流、LAND/unit/roster 形状、可能失效测试断言、未验证项、与任务书不一致处。

## 9. 本责任区不做

- 不写/不跑 32hex fixture（T1）；
- 不实现 R1 merge、S7-B/C、flow table、LAND 市场/债务；
- 不碰 culture 状态/命令（label 由 T1 用既有命令落）；
- 不实现 §8 整组织交易；
- 不重排日序、不改市场/工资/生产公式。

## 10. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣
R4：把这个人口上限取消掉！此外，单个家户不应该有一个对应的文化属性标签吗？如果没有，那就紧急加一个只有自然语言描述的标签，让文化模块维护
R5：是的，随着人走
R6：测试世界依旧32hex，每个hex一个有地农民家户一个无地农民家户就行，政府和市场区配置照旧
```
