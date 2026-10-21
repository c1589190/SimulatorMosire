# D-032 RA4a changeset replay + GUI land readout · 实现任务书（2026-10-19）

> **状态：可派单。** RA5a 测试已把测试面迁到标量 LAND；360 tick 已 PASS。现在修两个已由测试/读口实测暴露的生产缺口。
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`；只动本树，不碰主树。

## 1. 缺陷 A：Timeline changeset replay 缺 HexCoord map-key 解码

- 复现：`tools/mvn-lock.sh -pl simos-app -am -Dtest=N20ThreeCommandSeedOrderEndToEndTest -Dsurefire.failIfNoSpecifiedTests=false test`
- 现象：`Timeline.readChangeSet` → `InvalidDefinitionException: Cannot find a (Map) Key deserializer for type io.mosire.simos.map.hex.HexCoord`
- 根因：
  - `LandArableSupply` 的 record 形状是 `Map<HexCoord, Long>`；
  - `Timeline.CHANGESET_MAPPER` 是 core 的通用 changeset mapper，只含 changeset 类型信息，不装 economy 的 `EconomyCoreCodecs.keyModule()`（core 也不能依赖 map/economy）。
- **修复方向（控制方已冻结）**：在 `simos-economy-api/.../land/LandArableSupply.java` 上提供**自包含的 Jackson wire 格式**，不得要求 core 注册 HexCoord key codec，也不得让 util 依赖 map。
  - 线格式建议：`arableMilliMuByHex` 对外写成 `Map<String,Long>`，key = `HexCoord.toString()`；读入时 `HexCoord.parse(key)`。
  - 可用 `@JsonSerialize` / `@JsonDeserialize` 自定义 serializer/deserializer，或等价的自包含 wire DTO；API 记录内部形状不变。
  - 同版本内往返必须成立；`Timeline.changeSetJson/readChangeSet` 必须能恢复 `EconomyChangeSet` 里的 `FieldDelta<LandArableSupply>`。
  - Alpha 不写旧档兼容；旧 LAND 格式不迁移。
- 必须回归：
  - `N20ThreeCommandSeedOrderEndToEndTest`；
  - `D031P1PlanOnlyAndBatchAtomicityTest`、`GovCreateOfficeEndToEndTest`、`ProvinceApplyEndToEndTest`、`McpCoverageTest` 的同一 replay 失败链；
  - `EconomyCodecTest` 中原有 land 线格式断言同步（测试 owner 文件已迁移，writer 不改测试；若测试因新 wire 格式需改，报告给控制方再由测试 owner 改）。

## 2. 缺陷 B：ApiViews LAND 读数仍从 assetShares 汇总

- 现场：`simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java:1255,1311,1416` 及 `ownershipStakeViews`/`assetSharesByKind` 仍只读 `assetShares`；LAND 已不在其中，导致 `/api/economy/hex` 的 `landMilliMu=0`、`unit.assets/capacity` 不显示 LAND。
- **修复方向**：
  - `landMilliMu` 从 `EconomyData.landHoldings()` 按本 hex 求和；
  - 视图新增/改用明确的 `landHoldings` 栏（owner/hex/quantity，稳定排序按 `LandHoldingKey`），不要伪造回 `assetShares`；
  - `assetShares`/`assetSharesByKind`/`ownershipStakeViews` 只展示非 LAND（保持现语义）；
  - D4 `assetOccupancies` 视图不显示 LAND（LAND 使用标量且 grant 为瞬态；不得显示投影合成 grant）；
  - `unit.assets/capacity` 若无法与标量 landHoldings 精确关联，明确保留 D4 allocation 语义并加 `landHoldings` 独立栏；不要用 `assetShares` LAND 填充。
  - 读口只读，不改状态/公式；排序确定，缺数据不伪造 0。
- 不要求扩展全部 GUI；只修 `/api/economy/hex` 及直接列 LAND 的视图。

## 3. 责任区

- 允许修改：
  ```text
  simos-economy-api/src/main/java/io/mosire/simos/economy/api/land/LandArableSupply.java
  simos-economy-api/src/main/java/io/mosire/simos/economy/api/land/（自包含 wire codec 可新增）
  simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java
  .superpowers/sdd/2026-10-19-d032-ra4a-replay-and-land-readout/
  ```
- 禁止：core、economy 生产逻辑、测试、POM/XML、tools、Seeder、其他 app main；当前脏文件不要接手。
- 编译：
  ```bash
  tools/mvn-lock.sh -q -DskipTests compile -pl simos-economy-api,simos-app -am
  ```
- 只写生产代码；不写/不改/不跑测试；不 commit/push；遇到设计冲突停手报告。

## 4. 验收

1. Timeline `changeSetJson/readChangeSet` 对含非空 `landArableSupply` 的 `EconomyChangeSet` 往返成功；`HexCoord` key 自包含编解码。
2. 相关 9 条 replay 用例可由测试 owner 复跑通过；若 writer 只编译，报告具体待跑测试。
3. `/api/economy/hex` 的 land 读数来自 `landHoldings`；`assetShares` 不出现 LAND；`landHoldings` 栏稳定排序。
4. production 零改动，ChangeSet 仍 36 组件。
