# RC1 任务书：class nature 与 culture label 共享契约

> **状态：约束设计书/实现任务书；只写生产代码到编译过，不写/不跑测试。**
> 工作树：`/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module`。
> 父设计：v2 S3/S7；v4 §4（classness）与 §9（文化标签）。
> 派单总表：`docs/superpowers/plans/2026-10-20-d032-two-position-rural-class-mobility-dispatch-plan.md` §5 的 RC1。
> 本责任区只冻结共享契约，不改 seeder、不做 S3 class 门控、不做 R1 合并、不做 S7-B/C。

## 1. 嵌入点与目标

```text
父序列：S1 载入 / S3 生产位置选择 / S7-B post-land class check / S7-C 向下流动
本责任区：只提供后续 RC2–RC7 共用的类型、只读端口和持久值字段
```

完成后系统必须能回答：

- 一个生产位置属于 `LANDED` 还是 `LANDLESS`；
- 一个 mode 允许哪些 class nature；
- 一个家户的文化 key 是什么（typed tag + 自然语言 label）；
- economy 不 import culture 模块也能读到 culture key；
- 新增字段在 ChangeSet/codec 往返中不丢失（测试后置，但类型必须可序列化）。

## 2. 当前代码事实（file:line）

- `simos-production/.../ProductionModeDefinitionView.java:19-36`：只有 `id/name/version/classStructureId`，没有 class nature。
- `simos-economy/.../model/ProductionRole.java:30-39`：阶层位置有 `modeId`、relation/labor/surplus，没有 class nature。
- `simos-economy/.../model/DefaultProductionModes.java:214-229`：`grain_farm` 的 `operator` + `hired_laborer` 两个位置由同一 mode 定义。
- `simos-production/.../ProductionModeUpserts.java:78-79`：构造 mode 视图时只有四字段。
- `simos-production/.../ProductionSeedHandler.java:71-75`：seed 构造 mode 视图时只有四字段。
- `simos-production/.../ProductionDefinitionPayloads.java:472-473`：`ModePayload` 只有 `id/name/version/classStructureId`。
- `simos-culture/.../contract/HouseholdCulture.java:17-30`：只有 `(household, tag)`，没有 label。
- `simos-culture/.../spi/SetHouseholdCultureHandler.java:62-67`：只解析 `cultureId/religionId/customs`，构造无 label 的 `HouseholdCulture`。
- `simos-culture/.../CultureData.java:36-79`：`householdCultures` 是唯一组件，直接实现 `CultureLookup`。
- `simos-economy/pom.xml` 禁 culture；`simos-culture/pom.xml` 允许 economy-api + production contract ⇒ class/culture 的共享类型必须放低层契约，不要让 economy 直连 culture。

## 3. 冻结形状

### 3.1 Production class nature

```text
simos-production/contract/ClassNature.java
    enum ClassNature { LANDED, LANDLESS }

ProductionModeDefinitionView(
    ProductionModeId id,
    String name,
    int version,
    ClassStructureId classStructureId,
    Set<ClassNature> supportedClassNatures)
```

- `supportedClassNatures` 非 null；保序、去重、不可变；空集 = 非农村/无 class nature 定义。
- `grain_farm` 的 mode 视图 = `{LANDED, LANDLESS}`；其他现有 mode 可为空集。
- `ProductionModeDefinitionView` 可为旧四参构造器提供 `Set.of()` 便利重载，但**新 catalog/seed/upsert 必须显式给 set**；不得把“缺省空集”当成农村 mode 的合法默认真相。

```text
simos-economy/model/ProductionRole.java
    Optional<ClassNature> classNature
```

- `grain_farm.operator` = `Optional.of(LANDED)`；
- `grain_farm.hired_laborer` = `Optional.of(LANDLESS)`；
- 其他非农村位置可空；
- 六参旧构造器可保留为 `Optional.empty()` 便利重载，但 `DefaultProductionModes.modeSpec(...)` 必须显式传。
- 一个 role 的 class nature 若 present，必须属于其 mode 的 `supportedClassNatures`；该检查先放在 `DefaultProductionModes` 构造/目录自检或 `ProductionReferenceValidation`，不得留到运行时才猜。

### 3.2 Production mode payload

`production.Seed` / `production.UpsertProductionMode` 的 mode 节点增加可选：

```json
"classNatures": ["LANDED","LANDLESS"]
```

- 缺省 = 空 set（非农村 mode 的合法值）；数组元素必须是 `ClassNature` 名字；
- 未知值/非数组/重复项按既有 payload 风格具名拒绝；
- 输出/输入保持确定性（按声明序 `LinkedHashSet`，不 `Set.copyOf`）。

### 3.3 Culture label

```text
HouseholdCulture(
    HouseholdId household,
    CultureTag tag,
    String label)
```

- `label` 非 null；缺省空串；不 trim 用户文本（只校验非 null）。
- `culture.SetHouseholdCulture` 载荷增加可选 `label`；缺省空串。
- 不改 `CultureData` 组件形状；label 在 `householdCultures` 值里，随既有 FieldDelta 自动往返。
- `CultureTag` 不变；文化/宗教/习俗仍是 typed 字段。

### 3.4 跨模块只读 culture key

```text
simos-economy-api/.../culture/HouseholdCultureKey.java
    record HouseholdCultureKey(
        String cultureId,
        String religionId,
        List<String> customs,   // 按字符串升序、去重、不可变
        String label)

simos-economy-api/.../culture/HouseholdCultureKeyLookup.java
    Optional<HouseholdCultureKey> cultureKeyOf(HouseholdId household)
```

- `CultureData` 实现 `HouseholdCultureKeyLookup`，从 typed `HouseholdCulture` 映射；
- missing household/culture ⇒ `Optional.empty()`；`label` 缺失由 culture 写入时落空串；
- economy 只依赖 economy-api 的 lookup 接口，不 import `simos-culture`；
- app 负责把当前 culture 切片作为 lookup 传给 economy（本责任区可只加接口与 CultureData 实现；真正接线可在 RC4/RC6）。
- `HouseholdCultureKey` 的规范串/排序必须确定：customs 升序、culture/religion 原样；禁止 HashMap 迭代序。

## 4. 必须达到的行为

1. `production.Seed` / `production.UpsertProductionMode` 的 mode 视图携带 classNatures；
2. `DefaultProductionModes` 的 `grain_farm` 两个 position 带正确 classNature，mode set 为 `{LANDED, LANDLESS}`；
3. `ProductionRole`/`ProductionModeDefinitionView` 的 public 形状能表达位置级 classness；
4. `HouseholdCulture.label` 可通过 `culture.SetHouseholdCulture` 设置，写入后 Codec 往返不丢；
5. `CultureData` 可为每个 household 提供 `HouseholdCultureKey`；
6. 任何 unknown class nature/缺 mode 引用仍按既有 fail-closed 风格具名拒绝；
7. 不改变生产配方/市场/人口/债务公式；本单不改变任何经济数值。

## 5. 允许修改的文件

主要：

```text
simos-production/src/main/java/io/mosire/simos/production/contract/ClassNature.java（新增）
simos-production/src/main/java/io/mosire/simos/production/contract/ProductionModeDefinitionView.java
simos-production/src/main/java/io/mosire/simos/production/spi/ProductionDefinitionPayloads.java
simos-production/src/main/java/io/mosire/simos/production/spi/ProductionModeUpserts.java
simos-production/src/main/java/io/mosire/simos/production/spi/ProductionSeedHandler.java
simos-economy/src/main/java/io/mosire/simos/economy/model/ProductionRole.java
simos-economy/src/main/java/io/mosire/simos/economy/model/DefaultProductionModes.java
simos-economy-api/src/main/java/io/mosire/simos/economy/api/culture/HouseholdCultureKey.java（新增）
simos-economy-api/src/main/java/io/mosire/simos/economy/api/culture/HouseholdCultureKeyLookup.java（新增）
simos-culture/src/main/java/io/mosire/simos/culture/contract/HouseholdCulture.java
simos-culture/src/main/java/io/mosire/simos/culture/spi/CulturePayloads.java（只在需要 optionalText 时）
simos-culture/src/main/java/io/mosire/simos/culture/spi/SetHouseholdCultureHandler.java
simos-culture/src/main/java/io/mosire/simos/culture/CultureData.java
```

必要时只做编译适配的相邻 main 文件：

```text
simos-economy/src/main/java/io/mosire/simos/economy/migrate/LegacyClassStructure.java
simos-app/src/main/java/io/mosire/simos/app/world/IndustrySeeder.java
simos-app/src/main/java/io/mosire/simos/app/world/ProductionPayloadSeeder.java
```

**禁止**：

- `EconomyData`/ChangeSet/Codec 新增/删除组件；
- 任何 `src/test/**`；
- seeder 的 32hex 两户改造、S3 门控、R1 合户、S7-B/C、LAND 市场/债务逻辑；
- 主树、`.m2-repo`、GUI/服务脚本、commit/push。

## 6. 失败/回滚/保守边界

- `ClassNature` 未知值 ⇒ 构造/payload 解析具名拒绝。
- mode 的 classNatures 为空时不得自动补出 `{LANDED,LANDLESS}`；只有显式 catalog/seed 给。
- `ProductionRole.classNature` present 但不在 mode set ⇒ 构造/候选态校验错误。
- `HouseholdCulture.label` null ⇒ 拒；空串合法。
- `HouseholdCultureKey` 的 customs 复制、排序、冻结；任何 null 元素 ⇒ 拒。
- `CultureData.cultureKeyOf` 不得抛“找不到”——missing 返回 empty。
- 本单不改变 mutation 路径的旧档兼容；Alpha 不写旧档迁移。

## 7. 验收锚点（测试 owner 后补，不由 writer 运行）

正例：

1. `production.Seed` 带 `"modes":[{"id":"grain_farm",...,"classNatures":["LANDED","LANDLESS"]}]` ⇒ mode 视图 set 正确；
2. `DefaultProductionModes.mode(GRAIN_FARM)` 的 set = `{LANDED,LANDLESS}`；`operator` classNature=LANDED，`hired_laborer`=LANDLESS；
3. `culture.SetHouseholdCulture` 带 `label:"本地稻农"` ⇒ `HouseholdCulture.label` 可读；
4. 同值重放/重复 label 设置幂等；Codec/ChangeSet 往返保留 label；
5. `CultureData.cultureKeyOf(h)` 得到 culture/religion/customs/label；customs 排序确定；
6. economy 模块 import 检查：0 个 `io.mosire.simos.culture` import。

负例：

- class nature 不合法 ⇒ 拒；
- mode set 不含 role class nature ⇒ 拒；
- `HouseholdCulture.label=null` ⇒ 拒；
- `HouseholdCultureKey.customs` 含 null ⇒ 拒；
- `CultureData` 缺 household ⇒ 返回 empty，不伪造 key；
- 试图让 economy 直连 culture ⇒ enforcer 红。

## 8. 编译与交账

```bash
cd /home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module
tools/mvn-lock.sh -q spotless:apply
tools/mvn-lock.sh -q -DskipTests compile -pl simos-production,simos-economy-api,simos-culture,simos-economy,simos-app -am
```

- 只写生产代码；不写/不跑 test/verify/package；不 commit/push。
- 实现账本：`.superpowers/sdd/2026-10-20-d032-rc1-class-culture-contract/impl-ledger.md`，必须含父阶段锚点、实际实现、影响表、file:line、未做/未验证。
- 报告必须列：改动文件、编译 rc、公开 record/payload 形状、所有旧构造器/调用点变化、会让既有测试断言失效的清单、未完成/未验证项、与任务书不一致处。

## 9. 本责任区明确不做

- 不改 seeder / 32hex 两户 / LAND 归属；
- 不做 class nature 在 S3 的候选过滤（RC2）；
- 不做 R1 merge（RC4）；
- 不做 S7-B PostLandTradeClassCheck / 多 hex 拆分（RC6）；
- 不做 S7-C flow table（RC7）；
- 不做 §8 同阶级整组织交易。

## 10. 用户原话附录（逐字）

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v4.md` §1.2，2026-10-20。

```text
R1：如果一个hex上有多个同一文化属性、同一生产方式和对应阶级性的家户，只是库存和人口不同，那么这两个家户应当合并，目前的有地农民家户早已脱离地主语义了，事实上是小农和地主的集合体——地主导致的灾难性阶级分化事实上也能表示，就是单个hex的有地农民人口极少土地极多，大部分人口都只能接受雇佣
R4：把这个人口上限取消掉！此外，单个家户不应该有一个对应的文化属性标签吗？如果没有，那就紧急加一个只有自然语言描述的标签，让文化模块维护
```

来源：`docs/superpowers/specs/2026-10-20-d032-two-position-rural-class-mobility-v3.md` §1，2026-10-20（classness 维护路径来源）。

```text
这样一来，家户的阶级性也就必须跟着生产方式的定义走了，经济模块和生产方式模块必须根据阶级性的代码定义来维护，农业生产方式的具体维护路径如上
```
