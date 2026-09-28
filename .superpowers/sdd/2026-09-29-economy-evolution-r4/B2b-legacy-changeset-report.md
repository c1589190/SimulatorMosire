# B.2b 旧 changeset（Timeline 直读）兼容修复 —— 实施与验收报告（2026-09-29）

> 切片：R4 计划 §2.B.2 的兼容缺陷修复（B.2b）。执行者：修复编码代理。
> 约束遵守：只改 `src/main/java`（另加本报告）；未写/改任何测试；未跑 `test`/`verify`；未 `git commit`/push。
> `package` 按任务书例外执行（仅为重建 shaded jar 跑探针），执行前确认无服务/无 Maven 在跑。
> 起点：`6170760f`（R3B.1）+ B.2 未提交工作树；本报告只覆盖 B.2b 的增量改动。

---

## 0. 结论先行

- **报告缺陷已修复**：`Timeline.readChangeSet` 现在能严格读出真实旧 changeset（`/tmp/world-rev4.json`、`/tmp/world-rev2.json`
  两条 `OK`）。
- **能读也能 apply**：把 `/tmp/b2-tick0-fixture/simos.db` 的 revision 2/3/4 依次 apply 到
  `EconomyData.empty()`，**60 项断言全 PASS / 0 FAIL**：每次构造不再抛；`units 数 == 有正 capacity 的 industry 数`；
  `Σ AssetShare(industry, asset) == 旧 Industry.capacity` 逐项相等；`relations`/`operatorConditions` 键全是真实 unit id；
  人口/劳动/市场/成员份额等组件逐条 `base + 本批 upsert`，无丢失/凭空。
- **新形状没被破坏**：新 changeset 经 `EconomyCodec` 与 `Timeline` 两条线严格往返一致；新 snapshot 往返一致；中性兼容位不会被
  误判成旧档（Probe3 60/0）。`EconomyChangeSet` 仍是 14 个组件（未增未减），`WorldChangeSet` 未动，全局严格绑定未关闭。
- **一条必要的既有行为修正**：`LegacyHouseholdMigration.migrate` 在**已有 memberships** 时改为原样保留（原来无条件重算）。
  实测证据：真实 tick0 旧 changeset 重算会把 rev2 的 12912 条人口份额改成 **9896** 条；不修就违反“只做键归一、不丢人口事实”
  与验收项“人口相关组件 apply 前后数量不变”。该修正在报告 §4 单列。

---

## 1. 改了什么（文件 / 方法）

### 1.1 `simos-economy/.../model/Industry.java`

| 位置 | 改动 |
|---|---|
| 类注（:20、:75-87） | 明确写出：record 末尾 5 个是**旧档反序列化兼容位**，加回它们的唯一目的是让 `Timeline.readChangeSet` 的全局严格 mapper 能绑定历史 changeset / 旧字节；**生产结算路径一律不得读**；新代码只走 12 参构造器；旧值在 `EconomyData` 构造期归一化后清成中性。 |
| record 头（:119-133） | canonical 末尾加回 `ActorRef operator, long progressDays, Map<AssetKind,Long> capacity, long cycleLaborMilli, Map<CommodityId,Long> cycleInputUsedMilli`（顺序与任务书逐字一致）。 |
| 12 参构造器（:142-176） | 新形状唯一入口；委托 17 参 canonical，兼容位传 `null/0L/Map.of()/0L/Map.of()`。现有 12 参调用点一行不改。 |
| compact 构造器（:191-212、:279-311） | 兼容位合法值域：`operator` 允许 null；`capacity`/`cycleInputUsedMilli` null ⇒ `Map.of()`；`progressDays ∈ [0, cycleDays]`；`cycleLaborMilli ≥ 0`；两张兼容表逐值非空、≥ 0（0 合法）、`LinkedHashMap + unmodifiableMap` 冻在赋值处。其余 12 个模板组件守卫一字未动。 |

**只改生产代码、不改结算算式**：这 5 个 accessor 在 main 生产路径零读取（全仓 main 源码无 `Industry.operator()/progressDays()/capacity()`
读取；`ApiViews` 等显示的 operator/progress 走 `ProductionUnit`）。

### 1.2 `simos-economy/.../EconomyData.java`

| 位置 | 改动 |
|---|---|
| compact 构造器（:213-329） | 在 `LegacyHouseholdMigration.needed/migrate` **之前**做一次“旧 Industry 兼容位 → 默认 unit + 整额 OWNED AssetShare”归一化。判据与任务书逐字一致：`operator!=null \|\| progressDays>0 \|\| cycleLaborMilli>0 \|\| !capacity.isEmpty() \|\| !cycleInputUsedMilli.isEmpty()`。 |
| 归一化规则 | ① operator：`industry.operator` 有就用，否则 `RegimeOperators.defaultOperator(regime, industryId)`（直接复用唯一拼写点，不另拼）；② AssetShare：该 industry 尚无任何份额时，按 `capacity` 每个 `quantity > 0` 的项合成 `OWNED`、`owner==operator`、`AssetShare.idOf(..., sequence=0)`；③ 默认 unit：`ProductionUnitId.idOf(industryId, operator)`、`modeKey=industryId.value()`、进度/劳动/投入原样带过；**同 industry 已有 unit 则不动**；**capacity 全 0/空 ⇒ 不造 unit**（也不再物化 0 值份额）；④ 每条旧 Industry 换成 12 参模板（兼容位清中性）。 |
| 新增私有判据（:1166-1187） | `hasLegacyProductionBits(Map<IndustryId,Industry>)` / `hasLegacyProductionBits(Industry)`：只判兼容位是否非中性；新形状恒 false ⇒ codec 已整形路径 no-op、Timeline 路径兜底，两条路径幂等共存。 |

### 1.3 `simos-economy/.../codec/EconomyCodec.java`

| 位置 | 改动 |
|---|---|
| `isLegacyIndustry`（:713-732） | **由“键是否出现”改为“值是否非中性”**：`operator` 非空 / progress>0 / labor>0 / capacity 非空对象 / cycleInputUsed 非空对象。原因：B.2b 后新模板 canonical 带 5 个兼容位，新档序列化会写出 `operator:null / progressDays:0 / capacity:{} / cycleLaborMilli:0 / cycleInputUsedMilli:{}`；若仍按键判，新档会被 codec 误当旧档、凭空合成 unit/份额并破坏新形状往返（Probe3 的 snapshot 往返断言的就是这条）。判据与 `EconomyData.hasLegacyProductionBits` 一致。 |

### 1.4 `simos-economy/.../migrate/LegacyHouseholdMigration.java`

| 位置 | 改动 |
|---|---|
| `migrate` 的 memberships 段（:258-270） | **已有 memberships 时原样 `putAll` 保留**；只有 memberships 缺失（旧档没有该组件）时才走原来的 `deriveMemberships`。类注/触发条件/“unit 不由本迁移器合成”的表述同步更新为 B.2b 事实。 |

---

## 2. 编译与探针命令、原文结果

### 2.1 编译（要求 1）

```
$ pgrep -af "surefirebooter|classworlds.launcher"   # 无命中
$ tools/mvn-lock.sh -DskipTests compile              # /tmp/r4-b2b-compile-final2.log
...
[INFO] BUILD SUCCESS
[INFO] Total time:  4.400 s
[INFO] Finished at: 2026-09-29T02:22:15+08:00
```

（`checkstyle-check` 0 violations；`spotless:apply` + `spotless:check` 均 exit 0。为重建 shaded jar，另按任务书例外跑了
`tools/mvn-lock.sh -Dmaven.test.skip=true package` → BUILD SUCCESS，`/tmp/r4-b2b-package-final2.log`。）

### 2.2 Probe2：旧 changeset 直读（要求 2）

**修复前**（B.2 工作树 + 旧 shaded jar，实测原文）：

```
/tmp/world-rev4.json FAIL in 287ms
  cause[0] java.lang.IllegalArgumentException: 变更集 JSON 非法或缺类型信息（@class）
      at io.mosire.simos.core.timeline.Timeline.readChangeSet(Timeline.java:383)
  cause[1] com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException: Unrecognized field "progressDays"
      (class io.mosire.simos.economy.model.Industry), not marked as ignorable (12 known properties: ...)
```

**修复后**：

```
$ javac -proc:none -cp simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar -d /tmp /tmp/Probe2.java
$ java -cp /tmp:simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar Probe2 /tmp/world-rev4.json /tmp/world-rev2.json
/tmp/world-rev4.json OK modules=[social, economy, actor, map, sd, unit] in 406ms
/tmp/world-rev2.json OK modules=[social, economy, actor, map, sd, unit] in 147ms
```

（输出落盘 `/tmp/r4-b2b-probe2-final2.out`。）

### 2.3 Probe3：apply 到 base + 全部验收不变量（要求 3）

`/tmp/Probe3.java`：JDBC 读 `/tmp/b2-tick0-fixture/simos.db` 的 revision 2/3/4 → `Timeline.readChangeSet` →
取 `economy` 模块的 `EconomyChangeSet` → 依次 `EconomyChangeSet.apply` 到 `EconomyData.empty()`。

```
$ java -cp /tmp:...-shaded.jar Probe3 /tmp/b2-tick0-fixture/simos.db
=== apply revisions 2,3,4 from /tmp/b2-tick0-fixture/simos.db to EconomyData.empty() ===
-- revision 2 (13995374 chars)
  PASS Timeline 读出 economy 模块且类型为 EconomyChangeSet
  PASS 人口 classes 数量 == base + 本批 upsert（无丢失/凭空）: 0 + 3440 = 3440
  PASS laborSupply 数量 == base + 本批 upsert: 0 + 2152 = 2152
  PASS allocations 数量 == base + 本批 upsert: 0 + 12048 = 12048
  PASS markets 数量 == base + 本批 upsert
  PASS memberships 数量 == base + 本批 upsert（迁移器不再重算人口份额）: 0 + 12912 = 12912
  PASS relations 条数 == base + 本批 upsert（旧键→unit 键，不新增/丢失）: 0 + 968 = 968
  PASS assetShares 数量 == base + 本批 upsert
  PASS 人口 memberships 逐行原样保留（base 条目 + 本批 upsert 条目）
-- revision 3：
  PASS memberships: 12912 + 4104 = 17016；relations: 968 + 309 = 1277
-- revision 4：
  PASS memberships: 17016 + 6984 = 24000；relations: 1277 + 522 = 1799
=== post-apply invariants over accumulated state ===
  accumulated industries=1799 units=1799 shares=1799
  PASS 所有 Industry 兼容位均为中性（清空证明）
  PASS 归一化后的 12 参模板与旧档模板字段逐值相等
  PASS units 数 == 有正 capacity 的 industry 数（1799 vs 1799）
  PASS 每个默认 unit 的 id/operator/modeKey/进度/劳动/投入与旧兼容位逐值相等
  PASS Σ AssetShare(industry,asset) 与旧 capacity 逐项相等：expected=1799 keys, actual=1799 keys
  PASS relations 键全部是真实 unit id（键==activity，operator==unit.operator）
  PASS operatorConditions 键全部是真实 unit id
  PASS allocations.activity 全部对齐到真实 unit（未对齐 0 条）
  PASS unit.operator 与 allocation.actor 一致（不一致 0 条）
  PASS 同一批 changeset 重放一次是 no-op（兼容位归一化幂等）
  PASS EconomyChangeSet.between(归一化态, 重放态) 全 Unchanged（无兼容位漂移）
=== new-shape strict round-trip (requirement 3) ===
  PASS EconomyCodec.decodeChangeSet(encodeChangeSet(cs)) 严格往返一致
  PASS Timeline.changeSetJson/readChangeSet 严格往返一致
  PASS EconomyCodec snapshot 严格往返一致（中性兼容位不被误判成旧档）
  serialized neutral compat bits: operator=null progressDays=0 capacity={} cycleLaborMilli=0 cycleInputUsedMilli={}
=== legacy changeset 兜底合成（不带 assetShares/units 的路径）===
  PASS capacity 全 0 ⇒ 不造 unit / 不物化 0 值份额 / 兼容位清中性
  PASS 原本没有 assetShares 的旧 changeset ⇒ 归一化合成默认 unit
  PASS unit 进度/劳动/投入从兼容位原样带过
  PASS capacity > 0 ⇒ 合成整额 OWNED 份额（idOf sequence=0，与既有迁移同 id 规则）
  PASS 兜底合成幂等（第二次 apply 不变）
=== Probe3 result: PASS=60 FAIL=0 ===
```

（完整输出 `/tmp/r4-b2b-probe3-final2.out`。）

### 2.4 Probe5：两条读档路径收敛 + 残留形状实测

```
$ java -cp /tmp:...-shaded.jar Probe5 /tmp/b2-tick0-fixture/simos.db
=== A. Timeline 直读 vs EconomyCodec reshape：同一旧 changeset 收敛性 ===
  timeline: units=968 memberships=12912 meta.rulesVersion=pre-modern-v1
  codec   : units=968 memberships=12912 meta.rulesVersion=pre-modern-v1
  PASS units / assetShares / relations / allocations / classes / memberships / industries / meta 逐值一致
  full EconomyData.equals = true
=== B. 旧 useRights 变更集经 Timeline 直读（预期仍 gap）===
  observed: IllegalArgumentException -> UnrecognizedPropertyException: Unrecognized field "useRights"
      (class io.mosire.simos.economy.change.EconomyChangeSet ...)
=== C. 旧 Remove 键（relations 旧 industry id）===
  PASS 旧 Remove 键（farm@0_0）未命中 unit 键（unit-farm@0_0-ESTATE-farm@0_0）⇒ relation 保留（no-op gap 实测）
=== Probe5 result: PASS=11 FAIL=0 ===
```

（完整输出 `/tmp/r4-b2b-probe5-final2.out`。）

---

## 3. 旧兼容位清空的证明

1. **逻辑清空 + 归一化等价**：apply 后逐条检查 1799 个 Industry 的 `operator==null && progressDays==0 && cycleLaborMilli==0 &&
   capacity.isEmpty() && cycleInputUsedMilli.isEmpty()` 全部成立；且每个归一化模板与旧档模板字段 12 参逐值相等
   （Probe3 `所有 Industry 兼容位均为中性`、`归一化后的 12 参模板与旧档模板字段逐值相等`）。
2. **线格式中性**：新代码 12 参构造出的 Industry 经 `SimosObjectMapper` 序列化为
   `operator=null progressDays=0 capacity={} cycleLaborMilli=0 cycleInputUsedMilli={}`（Probe3 原文打印）。
3. **diff 无漂移**：同一批 changeset 重放一次 `EconomyData.equals` 成立；`EconomyChangeSet.between(归一化态, 重放态).isEmpty()`
   为 true（全 Unchanged）。这是任务书点名要的“新序列化中性值 / between 对归一化状态 unchanged”证据。
4. **新旧严格绑定共存**：新 changeset 经 `EconomyCodec` 与 `Timeline` 两条 mapper 严格往返一致；新 snapshot 往返一致
   （若 `isLegacyIndustry` 仍按键判，此断言会红：codec 会把中性字段误当旧档并因“unit 无份额”在构造期抛）。

---

## 4. 与 B.2 计划的差异 / 仍未覆盖的旧档形状 / 剩余阻断点

### 4.1 与 B.2 计划/实施的有意差异

1. **默认 unit 的生成落点多了 `EconomyData` 构造期**。B.2 计划写在 `LegacyHouseholdMigration`，B.2 实现在
   `EconomyCodec/EconomyPayloads` 节点边缘；B.2b 按本任务要求在 `EconomyData` compact 构造器（迁移器之前）做一次
   typed 归一化，兜住 **Timeline 直读 changeset** 这条不经 codec 的路径。三条路径判据同一、幂等（Probe5 A 两条路径
   `full EconomyData.equals = true`）。
2. **`EconomyCodec.isLegacyIndustry` 从“键出现”改为“值非中性”**。这是 B.2b 加回兼容位后的必然配套：否则新档线格式会
   被旧档识别器误伤。旧档判定不丢（旧档 `operator` 恒非 null 或 capacity 非空）。
3. **`LegacyHouseholdMigration.migrate` 已有 memberships 时不再重算**（B.2 行为差异，唯一的行为性修正）。
   - 实测：rev2 的旧 changeset 自带 12912 条 memberships；原 `migrate` 无条件 `deriveMemberships` 会重算成 **9896** 条
     （`deriveMemberships` 走“劳动配额 lot”口径，与播种载荷自己的成员分摊不同）。
   - 本任务验收明确要求“人口/商品/货币相关组件 apply 前后数量不变（economy 侧只做键归一）”，且硬约束禁止“静默丢失事实”。
   - 新行为：memberships 非空 ⇒ 原样搬运（并逐行断言 base + 本批 upsert 都在）；memberships 为空 ⇒ 仍走原
     `deriveMemberships`（缺组件旧档的补齐行为逐字不变）。
   - 这不是把问题藏起来：Probe3 对每一批逐行校验了 base 条目与本批 upsert 条目的值都原样保留。
4. **capacity 全 0/空不造 unit** 是严格按任务书实现的（unit 创建条件 = 至少一个 `capacity[k] > 0`，而非“有 assetShares 就造”）。
   这也让 `units 数 == 有正 capacity 的 industry 数` 成为构造性事实（Probe3 1799 vs 1799）。

### 4.2 仍未覆盖的旧档形状（实测确认，未静默）

1. **旧 `useRights` 变更集经 Timeline 仍读不回**：`EconomyChangeSet` 没有 `useRights` 组件，全局严格 mapper 抛
   `UnrecognizedPropertyException: Unrecognized field "useRights"`（Probe5 B 原文）。R3B.1 之后的旧档已是
   `assetShares` 键，本条只影响更早的历史 changeset；如需覆盖，需要像 B.2 给 `EconomyCodec` 那样给 Timeline 路径也提供
   节点整形入口（当前 Timeline 对 economy 类型零知识，改动面不属于 B.2b）。
2. **旧 `Remove` 键不重写**：`FieldDelta.rebuild` 对 relations/conditions 的 Remove 只按字符串键删除；旧键
   `farm@0_0` 不会命中新键 `unit-farm@0_0-ESTATE-farm@0_0` ⇒ 删除静默变成 no-op（Probe5 C 实测：relation 保留）。
   当前 main 无任何路径删除 relation/condition（B.2 报告已如实记），真实旧档未构造出样本；要覆盖需要“旧 industry 键 ×
   Remove”时按 base 里的 unit 反查改写（Remove 没有 operator 值，歧义时必须 fail-closed）。
3. **旧 unit 的进度更新不入已有 unit**：任务书要求“同 industry 已有 unit 则不动”。当后续旧 revision 只改
   `industry.progressDays/cycleLaborMilli/cycleInputUsedMilli` 而不带 units 分量时，Timeline 路径不会把这几个兼容位
   搬进既有 unit（tick0 夹具的进度恒 0，故本批验收不涉及）。要让跨 tick 的旧档重放逐值等价，需要额外规则（例如
   “modeKey == industryId 且 operator 相同的既有 unit 才允许吸收兼容位”），B.2b 未做，避免 clobber 显式新 unit。
4. **codec 路径与 Timeline 路径对“有份额但 capacity 为 0/空”的旧 industry 仍可能分叉**：codec changeset reshape 的
   `hasCapacity` 判“capacity 对象非空”，Timeline 归一化判“存在正值”。夹具 1799 个产业全部为正 capacity，未分叉；
   极端旧档（capacity 全 0 但带 0 值份额）未覆盖。

### 4.3 剩余阻断点

- 就本任务报告的缺陷（旧 economy changeset 的 `progressDays` 等字段导致 `Timeline.readChangeSet` 失败 + 旧键未归一化）：
  **无剩余阻断点**；读、apply、键归一、份额对账、幂等、新形状往返全部有实测证据。
- 就“完整旧档跨 tick 重放 / 90 tick 逐值等价”这一 B.2 总验收：以上 4.2 的 1~3 仍需后续切片处理；B.2b 没有把它们
  假装成已修。

---

## 5. 明确“我没做 / 没验证的”

1. **没写/改任何测试，没跑 `test` / `verify`**；由任务书要求测试阶段另行处理。**没跑 test-compile**：当前测试树仍是
   B.2 之前的旧形状调用点（例如 `new Industry(...)` 的旧 17 参顺序、断言 `Industry.progressDays()`），预期与 B.2 生产形状
   不一致，本批按派单不动它们。
2. **没有跑 SpotBugs**（未跑 `verify`）；`spotless:apply/check` 通过，checkstyle 0 violations，compile/package 绿。
3. **没有用 Core/Store 的完整 CommandBus/WorldChangeSet 重放**：Probe3 走的是
   `Timeline.readChangeSet` + 取 economy 模块 + `EconomyChangeSet.apply` 到 `EconomyData.empty()`（任务书允许的降级路径）。
   完整 `WorldChangeSet` 六模块一起 apply、`sqlite store` 读写、命令层路由**没有跑**。
4. **只验证 economy 切片的人口/劳动/市场/成员/份额计数与逐行保留**；“商品/货币”住在 actor/social 切片，本次没有把
   social/actor 模块一起 apply，因此**没有验证**跨模块的商品/货币数量守恒。
5. **没有修** 4.2 列出的旧 `useRights` 变更集、旧 `Remove` 键、跨 tick 进度搬运、codec/Timeline 零 capacity 分叉；
   只做了实测与如实记录。
6. **没有 commit / push**；没有起服务；`package` 只在确认无 Maven/服务运行时执行（任务书例外）。

---

## 附录：最终探针/日志文件

| 文件 | 内容 |
|---|---|
| `/tmp/Probe2.java` | 旧 changeset 直读（任务书给定） |
| `/tmp/Probe3.java` | revision 2/3/4 逐个 apply + 60 项不变量/往返/兜底合成断言 |
| `/tmp/Probe5.java` | Timeline vs codec 路径收敛 + useRights/Remove 残留形状实测 |
| `/tmp/r4-b2b-compile-final2.log` | 最终 `-DskipTests compile` 原文 |
| `/tmp/r4-b2b-package-final2.log` | 最终 `-Dmaven.test.skip=true package` 原文 |
| `/tmp/r4-b2b-probe2-final2.out` | Probe2 两条 OK 原文 |
| `/tmp/r4-b2b-probe3-final2.out` | Probe3 `PASS=60 FAIL=0` 全量原文 |
| `/tmp/r4-b2b-probe5-final2.out` | Probe5 `PASS=11 FAIL=0` 全量原文 |
