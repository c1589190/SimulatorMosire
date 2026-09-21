# M2 侦察报告 — unit 域 20 条写命令的窄写工具化

> **性质：全程只读**。本次侦察**未改任何文件、未提交、未跑 Maven / node / npm、未派子代理**。
> **工作区**：worktree `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1`（分支 `ts/m1`，HEAD 已含 M1 的 7 条 map 窄写工具）。
> ★ 本仓**同时有主检出与 worktree、同名文件各一份** ⇒ 本报告提到的每一个路径都在 worktree 内，下表「锚点」列省略公共前缀
> `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1/`，以 `模块/路径:行` 给出。
> ★ 本机 `grep` 实测是 **ugrep 7.8.4**（默认尊重 `.gitignore`、跳过隐藏目录）⇒ 凡"搜全仓"一律用 `git grep --untracked`；
> 凡本报告写"没搜到"的地方，**都同时写出命令与范围**（见 §十）。
> ★ 凡标「**未核实**」的，都在 §十 说明**为什么没读到**。
> ★★ **本报告没有一条结论来自"运行"**：全部是**读码 + 现场计数**（`grep -c` / `sed` / `sort | uniq -c`）。
> 「必红哪几处」是**读断言 + 数集合**推出来的，**不是跑出来的**——这是一条口径声明，不是谦辞。

---

## 〇 我实测到的数字

| # | 项 | 实测值 | 怎么得到的 |
|---|---|---|---|
| 1 | unit 域写命令条数 | **20** | 三源交叉，逐源现场数（§一） |
| 2 | `Shell` 注册的 handler 总数 | **43** = 7 map + 20 unit + 16 sd | 读 `Shell.java:307-344` + `:351/:360-364` 五条晚注册 |
| 3 | 现有 4 桶工具数 | EXTERNAL **12** / GM **20** / DECISION_AGENT **11** / EXTERNAL_WITH_GM **23** | 读 `SimosToolSource` 四个追加点逐条数（§四） |
| 4 | M2 落地后 4 桶工具数 | EXTERNAL **12**（不变）/ GM **40** / DECISION_AGENT **31** / EXTERNAL_WITH_GM **43** | 同上 + 20 |
| 5 | M2 **必红**断言 | **4 处**（精确集合断言） | §五 |
| 6 | 需扩长的名字常量 | **5 个**，分布在 **3 个测试文件** | §五 |
| 7 | `CatalogTool.PAYLOAD_HINTS` | **43** 条 = 20 unit + 7 map + 16 sd ⇒ **20 条 unit 提示全在** | `sed 33,103p` + `grep -oE` |
| 8 | `McpCoverageTest.EXPECTED_COMMAND_TYPES` | **43** 条 = 20 unit + 7 map + 16 sd ⇒ **20 条 unit 类型全在** | `sed 108,152p` + `grep -oE` |
| 9 | `McpCoverageTest.MINIMAL_PAYLOADS` | **43** 条键（与 #8 同集） | `grep -oP 'put\(\s*\n?\s*"\K[^"]+'` |
| 10 | `webui/modes.js` unit 模式写白名单 | **6** 条（**不含** T9 新注册的 12 条） | 读文件 |
| 11 | `AppWritePathGuardTest.FORBIDDEN` | **3** 条 | 读文件 |

**★ 现场计数命令（可复现）**

```bash
# PAYLOAD_HINTS 逐命名空间
sed -n '33,103p'  …/simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java \
  | grep -oE '"(unit|map|sd)\.[A-Za-z]+"' | cut -d. -f1 | tr -d '"' | sort | uniq -c
# → 20 unit / 7 map / 16 sd   （合计 43，与 grep -c 'Map.entry(' 同值）

# EXPECTED_COMMAND_TYPES 逐命名空间
sed -n '108,152p' …/simos-app/src/test/java/io/mosire/simos/app/McpCoverageTest.java \
  | grep -oE '"(unit|map|sd)\.[A-Za-z]+"' | cut -d. -f1 | tr -d '"' | sort | uniq -c
# → 20 unit / 7 map / 16 sd   （合计 43）
```

**★ 一条当场踩到的形态（写下来是因为它会骗下一个人）**：`grep -c 'Map.entry("unit\.'` 只数到 **19**，而真值是 **20**。
根因：`unit.CreateUnit`（以及若干净/多行的 `map.*`/`sd.*` 若干条）是**换行**写法——
```java
Map.entry(
    "unit.CreateUnit",
    "id, name, position{q,r}, member, equipment, speed, mobilityPerMille, parent?"),
```
⇒ **数表项要按"块"数（先取块再抽串），不能按"行前缀"数**。与本项目「按整份文件计数 vs 逐片段计数」同族（T5-L5 已有前例）。
本报告所有"43 / 20 / 7 / 16"都是**按块重数过的**。

---

## 一 权威 20 条清单：三源交叉 —— **核到了，零分歧**

**三源逐条一致、条数都是 20、集合逐字相等**：

| 源 | 锚点 | 条数 | 结果 |
|---|---|---|---|
| ① **注册面（权威）** | `simos-app/src/main/java/io/mosire/simos/app/Shell.java:314-333` | **20** | — |
| ② `HANDOFF.md §三`（T9 开工前控制器只读实测钉死） | `.superpowers/sdd/2026-09-20-unit-extension/HANDOFF.md` §三 | **20** | 与 ① **逐条同** |
| ③ **实现面（独立第三源）** | `simos-unit/src/main/java/io/mosire/simos/unit/spi/*Handler.java` 的 `public String type()` | **20 文件 / 20 个不同 `unit.*` 串** | 与 ①② **逐条同** |

第 ③ 源的两条计数命令：
```bash
ls simos-unit/src/main/java/io/mosire/simos/unit/spi/*Handler.java | wc -l
# → 20
grep -H -oE 'return "unit\.[A-Za-z]+";' simos-unit/src/main/java/io/mosire/simos/unit/spi/*Handler.java | sort -u | wc -l
# → 20
```

★ 另有**第四条独立核对**（不是清单源，但能证集合闭合）：`McpCoverageTest.catalogCoversEveryCommandHandlerImplementation`
扫 `simos-unit|map|sd/src/main/java` 的 `*Handler.java` 抽 `type()`，断言行数 **43**；其中 unit 恰 20（§六）。

### 20 条清单（注册序 = 建议的工具落地序）

| # | 命令类型 | # | 命令类型 |
|---|---|---|---|
| 1 | `unit.RenameUnit` | 11 | `unit.DetachUnit` |
| 2 | `unit.CreateUnit` | 12 | `unit.ReparentSubtree` |
| 3 | `unit.ReparentUnit` | 13 | `unit.SetFormationOffset` |
| 4 | `unit.SetStrength` | 14 | `unit.SplitFormation` |
| 5 | `unit.PlaceAt` | 15 | `unit.MergeFormation` |
| 6 | `unit.PlanRoute` | 16 | `unit.PlanSparseRoute` |
| 7 | `unit.CancelRoute` | 17 | `unit.SetRejoinTarget` |
| 8 | `unit.DisbandUnit` | 18 | `unit.CreateCommandChain` |
| 9 | `unit.SetStatus` | 19 | `unit.UpdateCommandChain` |
| 10 | `unit.AttachUnit` | 20 | `unit.ApplyCasualties` |

### ★★ 一处必须写死的口径更正

`HANDOFF.md §三` 的「**12 条待注册 / 8 条已注册**」**已经过期**——T9 已把 20 条**全部**注册
（`Shell.java:314-333` 实测 20 条，无一条缺席）。⇒ **M2 读 §三 时只取它的"20 条清单"，不要取它的"注册状态"。**

### 零分歧声明

三源之间**没有任何差异可报**：既没有多、也没有少、也没有拼写差异。**本条是"核到了"，不是"看起来一致"。**

---

## 二 逐命令载荷真值表

口径：「**必填**」= 缺了就被**载荷层**（`UnitPayloads`，抛 `IllegalArgumentException` → handler catch → `HandlerOutcome.Rejected`）拒。
「**可选 / 缺省语义**」写缺省时会发生什么——★ 注意有些字段"缺省"**不是**空操作，而是**有语义的**（下表加粗标出）。

### 2-A 必填 / 可选 / 缺省语义

| # | 命令 | 必填 | 可选 / 缺省语义 | 解析锚点（`simos-unit/.../unit/spi/`） |
|---|---|---|---|---|
| 1 | `unit.RenameUnit` | `id`, `name` | — | **不走 `UnitPayloads`**：`RenameUnitHandler.java:53,57-61` 自己 `MAPPER.readTree` |
| 2 | `unit.CreateUnit` | `id`,`name`,`position{q,r}`,`member`,`equipment`,`speed`,`mobilityPerMille` | `parent?`（缺 ⇒ 根）; `status?`（**缺 ⇒ `MOVING`，有默认**） | `CreateUnitHandler.java:48-56` |
| 3 | `unit.ReparentUnit` | `id` | `parent?` —— ★ **缺 / `null` = 清根**（不是"不动"） | `ReparentUnitHandler.java:37-38` |
| 4 | `unit.SetStrength` | `id`,`member`,`equipment` | — | `SetStrengthHandler.java:35-37` |
| 5 | `unit.PlaceAt` | `id` | `hex?` —— ★ **缺 / `null` = 撤销位置**（不是"不动"） | `PlaceAtHandler.java:36-37` |
| 6 | `unit.PlanRoute` | `id`,`waypoints[{q,r}…]`（≥2 由 `Route` 兜） | — | `PlanRouteHandler.java:42-43` |
| 7 | `unit.CancelRoute` | `id` | — | `CancelRouteHandler.java:29` |
| 8 | `unit.DisbandUnit` | `id` | — | `DisbandUnitHandler.java:33` |
| 9 | `unit.SetStatus` | `id`,`status`（`MOVING\|RESTING\|ENGAGED`） | — | `SetStatusHandler.java:36-37` |
| 10 | `unit.AttachUnit` | `id` | `parent?` —— ★ 缺 ⇒ `Optional.empty()`；**域层语义见 2-C** | `AttachUnitHandler.java:38-40` |
| 11 | `unit.DetachUnit` | `id` | — | `DetachUnitHandler.java:35` |
| 12 | `unit.ReparentSubtree` | `rootId` | `parent?` —— ★ **缺 / `null` = 提升为根** | `ReparentSubtreeHandler.java:39-40` |
| 13 | `unit.SetFormationOffset` | `id` | `dq?`,`dr?` —— ★ **两者全缺 = 清除偏移**（不是"不动"；`dq` 缺但 `dr` 给 ⇒ `dq` 按 **0**） | `SetFormationOffsetHandler.java:37-43` |
| 14 | `unit.SplitFormation` | `rootId`,`subUnitIds[]` | — ★ `[]` **载荷层放行**，由域层拒（2-C） | `SplitFormationHandler.java:40-42` |
| 15 | `unit.MergeFormation` | `childId`,`parentId` | — | `MergeFormationHandler.java:37-38` |
| 16 | `unit.PlanSparseRoute` | `id`,`waypoints[{q,r}…]` | — | `PlanSparseRouteHandler.java:58-59` |
| 17 | `unit.SetRejoinTarget` | `id` | `target?` —— ★ **缺 / `null` = 清回归意图** | `SetRejoinTargetHandler.java:43-44` |
| 18 | `unit.CreateCommandChain` | `chainId`,`name`,`commander`,`members[]` | — ★ **四个全必填**（无 `?`） | `CreateCommandChainHandler.java:47-51` |
| 19 | `unit.UpdateCommandChain` | `chainId` | `name?`,`commander?`,`members?` —— ★ **三个全缺 = 合法（近似 no-op 但会落 revision）** | `UpdateCommandChainHandler.java:46-50` |
| 20 | `unit.ApplyCasualties` | `id`,`personnel`,`equipment{键:负增量}` | — ★ **`equipment` 必填**：只报人员战损也得显式传 `equipment: {}`（T8-G1/G2 裁定） | `ApplyCasualtiesHandler.java:38-40` |

**★ 三条会被误读成"可选"的字段**（都**不是**"缺省 = 不动"）：
`unit.ReparentUnit.parent?`（缺 = 清根）、`unit.ReparentSubtree.parent?`（缺 = 提升为根）、
`unit.SetFormationOffset.{dq?,dr?}`（全缺 = 清偏移）。
⇒ **M2 的 `description()` 必须逐条写清这四条，不能只写"可选"。**（现有 7 条 map 工具的 `description()` 就是这么写的，
例：`MapSetTerrainTool.description()` 里明写「（replace|merge，无默认）」。）

**★ `unit.RenameUnit` 是唯一的形状异类**：它**不走 `UnitPayloads`**，自己的文案是
`payload 不是合法 JSON: …`（`:55`）与 `payload 必须是 {"id":字符串,"name":字符串}: <整个 payloadJson>`（`:60`），
而其余 19 条走的是 `UnitPayloads` 的统一文案（如 `字段 id 必须是字符串: <整个 payload>`，`UnitPayloads.java:56`）。
⇒ 这不是缺陷，但**写工具 description 时两条文案族要分开说**；也让"载荷层 vs 域层"的判别（T10-b 那一族）在第 1 条上口径不同。

**★ 全 20 条 handler 的拒绝接线是同一种**：`catch (IllegalArgumentException e) → new HandlerOutcome.Rejected(e.getMessage())`。
计数命令与结果：
```bash
grep -lc 'catch (IllegalArgumentException e)' simos-unit/src/main/java/io/mosire/simos/unit/spi/*Handler.java | wc -l
# → 20    （20/20，无一例外）
```

### 2-B 逐命令域层拒绝文案（逐字）

★ 全部来自 `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`，外加它的两个下游
（`Unit.java` 构造期 / `Route.java` 构造期）。**行号是本次读到的行号**。

| # | 命令 | 域层拒绝文案（逐字，`<…>` 为拼接值） | 锚点 |
|---|---|---|---|
| 1 | `unit.RenameUnit` | `单位不存在: <id>` | `UnitOperations.java:714`（`require`） |
| 1 | （同上，名字空白） | `name 不得为空白` | ★ **`Unit.java:47`（构造期）**，非 op 层 |
| 2 | `unit.CreateUnit` | `单位 id 已存在: <id>`；`父单位不存在: <id>` | `:56`；`:721` |
| 2 | （同上，构造期） | `member 必须 ≥ 0: <n>`；`speed 必须 ≥ 1: <n>`；`mobilityPerMille 必须 ≥ 1: <n>`；`equipment 的键不得空白`；`equipment 的值必须 ≥ 0: <键>`；`parent 不得指向自身: <id>` | `Unit.java:57,63,66,162,165,53` |
| 3 | `unit.ReparentUnit` | `单位不存在: <id>`；`父单位不存在: <id>` | `:714`；`:721` |
| 4 | `unit.SetStrength` | `单位不存在: <id>`；+ 构造期那六条（同上） | `:714` + `Unit.java` |
| 5 | `unit.PlaceAt` | `单位不存在: <id>` | `:714` |
| 6 | `unit.PlanRoute` | `单位 <id> 在 <at> 没有可确定的位置，无法下达路线`；`路线起点 <routeStart> 不是单位在 <at> 的位置 <start>` | `:216-219` |
| 6 | （同上，`Route` 构造期） | `waypoints 至少两个（总纲：多个路径点，最低两个）`；`path 至少两格`；`path 的首尾必须等于 waypoints 的首尾`；`waypoints 必须是 path 的子序列`；`path 相邻格必须相邻：<a> → <b>`；`path 不得有重复格（A* 产物天然是简单路径）` | `Route.java:17,20,24,33,37,41` |
| 7 | `unit.CancelRoute` | `单位不存在: <id>` | `:714` |
| 8 | `unit.DisbandUnit` | `单位不存在: <id>`；`单位 <id> 仍是链 <chainId> 的 commander：先改链、再解散`；`单位 <id> 仍是链 <chainId> 的成员：先改链、再解散`；`单位 <id> 在 <at> 仍有下属 <下级id>：先改编、再解散` | `:714`；`:332`；`:336`；`:319-320` |
| 9 | `unit.SetStatus` | `单位不存在: <id>` | `:714` |
| 10 | `unit.AttachUnit` | `单位不存在: <id>`；`父单位不存在: <id>`；`父单位 <parent> 落在 <id> 的子树内（含自身）：会成环` | `:714`；`:721`；`:360` |
| 11 | `unit.DetachUnit` | `单位不存在: <id>`；`单位 <id> 在 <at> 已是根单位：没有可脱离的父` | `:714`；`:382` |
| 12 | `unit.ReparentSubtree` | `单位不存在: <id>`；`父单位不存在: <id>`；`新父 <p> 落在 <rootId> 的子树内（含自身）：会成环` | `:714`；`:721`；`:432-433` |
| 13 | `unit.SetFormationOffset` | `单位不存在: <id>` | `:714` |
| 13 | （同上，溢出） | `RelativeOffset` 的 `Math.addExact` 溢出消息 | `RelativeOffset.java:24`（T10-a 修） |
| 14 | `unit.SplitFormation` | `单位不存在: <id>`；`subUnitIds 不得为空：拆分命令至少要指名一个目标`；`单位 <id> 不在 <rootId> 在 <at> 的子树内：不能拆分` | `:714`；`:467`；`:474-475` |
| 15 | `unit.MergeFormation` | `单位不存在: <id>`；`父单位不存在: <id>`；`单位 <c> 或 <p> 在 <at> 没有可确定的位置：只有同格才能合体`；`单位 <c> 在 <at> 位于 <hex>，与 <p> 的 <hex> 不同格：只有同格才能合体`；`单位 <c> 的状态是 <STATUS> 而不是 MOVING：只有移动中的单位才能合体` | `:714`；`:721`；`:508-509`；`:512-526`；`:526-527` |
| 16 | `unit.PlanSparseRoute` | `单位不存在: <id>`；**`稀疏路线的段不可达: <from> → <to>`**；+ 上表 `Route` 构造期六条 + `PlanRoute` 两条 | `:714`；**`:274-275`** |
| 17 | `unit.SetRejoinTarget` | `单位不存在: <id>`；`回归目标不得是自身: <id>`；`回归目标不存在: <targetId>` | `:714`；`:658`；`:661` |
| 18 | `unit.CreateCommandChain` | `链 id 已存在: <chainId>`；`链 <chainId> 的 commander 不存在: <id>`；`链 <chainId> 的成员不存在: <id>`；`链 <id> 的 members 不得含 null` | `:554`；`:626`；`:630`；`:596` |
| 19 | `unit.UpdateCommandChain` | `链不存在: <id>`；`链 <id> 的 commander <c> 不在 members 内：先把它加进 members 再改链`；+ 建链那三条 | `:590`；`:606-607`；`:626,:630` |
| 20 | `unit.ApplyCasualties` | `单位不存在: <id>`；`人员增量必须 ≤ 0（战损只减员）: <n>`；**`人员战损超出当前值: <当前> + (<Δ>)`**；`装备增量键不得空白`；`装备增量不得为 null: <键>`；**`未知装备键: <键>`**；`装备增量必须 ≤ 0（战损只减员）: <键>=<Δ>`；**`装备战损超出当前值: <键>=<当前> + (<Δ>)`** | `:714`；`:145`；`:148-149`；`:156`；`:159`；**`:164`**；`:167`；`:170-171` |

★ **一条通用兜底**：`Unit.java:44` 的 `id 不得为 null` 与 `:69,72,77` 的三条 `不得为 null` — 这些是**编程错误面**（1 参兼容构造器等），
不是可拒绝的坏命令。

### 2-C ★ 四项点名检查（用户逐条要求）

#### (1) `unit.DisbandUnit` + 悬空引用（`rejoinTarget`）—— **有守卫，但只覆盖"链"这一半**

- **链引用**：`disband`（`:311-324`）依次调 `require(state,id)` → `requireNotInAnyChain(state,id)`（`:327-341`，两半：**commander** `:332` / **member** `:336`），
  再遍历其他单位判"是否还有下属"（`:319-320`）。→ **三条可读拒绝都在**。
- **`rejoinTarget` 悬空引用**：★★ **`disband` 不检查、也不清理它** —— 这是 unit-ext T7 的 **裁定 G1**（挂账）。
  运行期口径安全：`effectivePosition` 对不存在的 id 返回 `Optional.empty()` ⇒ **不回归、不写任何东西**；
  且 T10-d 已补 `UnitRejoinDanglingTest`（引用不清 + 不回归不抛）⇒ **有判据，但是"运行时兜住"而不是"命令期拒绝"**。
  ⇒ **M2 的工具 description 不得声称 `DisbandUnit` 会拒绝悬空的 `rejoinTarget`。**
- 断开引用**不会**静默成功：`requireNotInAnyChain` 的文案带"先改链、再解散"，是**可操作**的理由（符合「前置即错」）。

#### (2) `unit.ApplyCasualties` 的上界 —— **双侧上界都在，且理由文案可读**

- `personnel`：`> 0` ⇒ `人员增量必须 ≤ 0（战损只减员）: <n>`（`:145`）；`< -unit.member()` ⇒ **`人员战损超出当前值: <当前> + (<Δ>)`**（`:148-149`）。
- `equipment{}`：每个键的 delta `> 0` ⇒ `装备增量必须 ≤ 0…`（`:167`）；`< -current` ⇒ **`装备战损超出当前值: <键>=<当前> + (<Δ>)`**（`:170-171`）。
- ★ **T8 的实测口径照抄**：`Unit.java:57` 的 `member 必须 ≥ 0` 也在**构造期**独立把守 ⇒ 删掉 op 层上界守卫**仍然**落不了负数，
  红的是**领域理由文本**——「上界 ⇒ 可读的领域理由」与「值不变」分别由**两层**保证，**不要把它们记成一件事**。

#### (3) 未知装备键 —— **拒绝，不视作 0（P14 兑现）**

`UnitOperations.java:160-165`：
```java
Integer current = equipment.get(key);
if (current == null) {
  // ★ P14：未知键**拒绝**，不视作 0（"没有这件装备"不是"这件装备是 0"）。
  throw new IllegalArgumentException("未知装备键: " + key);
}
```
⇒ **逐字文案 = `未知装备键: <键>`，锚点 `UnitOperations.java:164`。** 直接回答用户的问题：**拒绝**。

#### (4) `unit.PlanSparseRoute` 的"相邻段不可达" —— **命令期拒绝，不跳段、不截断**

`UnitOperations.java:271-276`（`expandSparsePath`）：
```java
List<HexCoord> segment =
    PathFinder.findPath(map, from, to, unit, cost)
        .orElseThrow(() -> new IllegalArgumentException("稀疏路线的段不可达: " + from + " → " + to));
```
⇒ **逐字文案 = `稀疏路线的段不可达: <from> → <to>`，锚点 `:274-275`。** 类注（`:255-260`）明写"**绝不**静默截断或跳段"。
⇒ 用户点名的**四项全部成立**；`disband` 那一项**只成立一半**（链有守卫，`rejoinTarget` 靠运行时兜住）。

### 2-D ★ 静默缺口表（"把没发生伪装成没发生"族）

★ 全部是**读码结论、未运行验证**（本次全程只读）。逐条给锚点。

| # | 缺口 | 锚点（逐字） | 级别 |
|---|---|---|---|
| **U-1** | **未知/拼错的字段被静默忽略**。`UnitPayloads.parse` 与 `MapPayloads.parse` 都走 `MAPPER.readTree(...)`（**tree 读取，不做 bean 绑定**），因此 `SimosObjectMapper` 那条"**不关闭 `FAIL_ON_UNKNOWN_PROPERTIES`**"的严格**对载荷解析不生效**。⇒ 把 `status` 拼成 `staus`、把 `equipment` 拼成 `equipmet`，都会**静默走缺省/报"字段缺失"**，而不会报"你写错了字段名"。 | `UnitPayloads.java:42`；`MapPayloads.java:42`；对照 `SimosObjectMapper.java:46`（那条只对 `readValue` 到 bean 生效） | 中（两侧同形，非 M2 新造） |
| **U-2** | **空操作仍落 revision**：`UnitChangeSet` 有 `isEmpty()`，但 `CommandBus` 从不读 ⇒ 一条"什么都没改"的 `unit.UpdateCommandChain`（三字段全缺）或 `unit.SetFormationOffset`（两字段全缺）会**留下一条 revision**。 | M1 侦察的 **F** 条（`MapChangeSet.java:107` vs `CommandBus.java:320-332,347-378`）**在 unit 侧同形**；unit 侧具体锚点**未核实**（§十-6） | 中 |
| **U-3** | `unit.CreateUnit` 的 `status?` **有默认 `MOVING`** ⇒ payload 里 `status` 打错成 `"moveing"` 会被 `optionalStatus` 拒（这条**不静默**），但**完全不给** `status` 与**给对**`MOVING` 无法从 revision 上区分。 | `CreateUnitHandler.java:56`；`UnitPayloads.java:116` | 低（有默认是设计） |
| **U-4** | `unit.SetFormationOffset` 的 `dq` 与 `dr` **各自缺省按 0**：只给 `dr` ⇒ `dq=0`；两者全缺 ⇒ **`Optional.empty()` = 清偏移**。⇒ "给一个分量"和"清偏移"是**两种语义**，而 payload 里只差一个字段。 | `SetFormationOffsetHandler.java:40-43` | 低（Javadoc 已写） |
| **U-5** | `unit.SplitFormation` 的 `[]` **载荷层放行**（`requireTextArray` 允许空数组），由域层 `subUnitIds 不得为空…` 拒 ⇒ **不是静默**，但**"哪一层拒的"要看文案**。 | `UnitPayloads.java:171-181`；`UnitOperations.java:467` | 低 |
| **U-6** | `unit.RenameUnit` **是唯一不走共享解析器的命令**（§2-A 末）：它的拒绝文案族与另外 19 条不同 ⇒ 写"载荷层 vs 域层"类断言时，**第 1 条必须单独写**（否则会得到一个恒真的 token 断言，T4/T10-b 那一族的坑）。 | `RenameUnitHandler.java:53-61` | 低（是形状差异） |
| **U-7** | **重名不拒**：`unit.CreateUnit` 只拒**同 id 已存在**（`:56`），**不拒同名**；`unit.RenameUnit` 也不拒"改成与别人同名"。 | `UnitOperations.java:56`；`:84-98`（`rename` 无名字唯一性检查） | 中（用户可见，但**不是 M2 引入的**） |
| **U-8** | `unit.AttachUnit` / `unit.ReparentSubtree` 的 `parent` 缺省语义**相反于直觉**（前者缺 ⇒ 无父，后者缺 ⇒ **提升为根**）⇒ 若 description 只写"可选"，模型会误用它做"改父"。 | `AttachUnitHandler.java:38-40`；`ReparentSubtreeHandler.java:39-40` | 中（**M2 可直接在 description 里消掉**） |

★ **U-1 / U-2 / U-7 是既有的**，经通用写 `simos.command.submit`（已在 EXTERNAL∪GM 桶里）**今天就可达** ——
不是 M2 新造的。**是否需要修，请按 M1 裁决 B 的同一口径判**（本报告只登记，不裁决）。

---

## 三 桶现状（M1 之后）与 M2 的追加点

### 3-A `enum Role` 与四个桶构造点

`simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java`

| 项 | 锚点 | 内容 |
|---|---|---|
| `enum Role` | `:55-71` | **4 个值**：`EXTERNAL` / `GM` / `DECISION_AGENT` / `EXTERNAL_WITH_GM` |
| 桶分派 `switch` | `:108-116` | 读工具先铺（`:107`），再按 role 追加写面 |
| `EXTERNAL_WITH_GM` | `:112-115` | ★ **它自动含 GM**：`case EXTERNAL_WITH_GM -> { addExternalWrites(...); addGmWrites(...); }` ⇒ **对，是并集** |

```java
List<AgentTool> built = new ArrayList<>(readTools(core, query, mapId, commandTypes));  // :107
switch (role) {                                                                        // :108-116
  case EXTERNAL        -> addExternalWrites(built, core, initiator, mapId);
  case GM              -> addGmWrites(built, core, initiator, mapId);
  case DECISION_AGENT  -> addDecisionAgentWrites(built, core, initiator, mapId);
  case EXTERNAL_WITH_GM -> { addExternalWrites(...); addGmWrites(...); }
}
this.tools = List.copyOf(built);   // :117
```

### 3-B 四个桶的当前工具名集合（**逐条数过**）

| 桶 | 读 | 通用写 | sd 窄写 | map 窄写 | **合计** |
|---|---|---|---|---|---|
| `EXTERNAL`（`:121-126`） | 9 | **3**（submit/advance/fork） | 0 | 0 | **12** |
| `GM`（`:134-147`） | 9 | 0 | **4**（IssueDirective/SubmitVerdict/**SetViewScope**/StartDecision） | **7** | **20** |
| `DECISION_AGENT`（`:150-154`） | 9 | 0 | **2**（IssueDirective/SubmitVerdict） | 0 | **11** |
| `EXTERNAL_WITH_GM`（`:112-115`） | 9 | **3** | **4** | **7** | **23** |

★ **读工具四桶共享**：`readTools(...)`（`:156-168`）返回 `List.of(...)` 9 条，**先铺**再追加写面（`:107`）。
★ `DECISION_AGENT` **无** `sd.SetViewScope`、**无** `sd.StartDecision`、**无**通用写（N9）。

### 3-C ★★ M2 的两个追加点（逐行）

**追加点 #1 — GM 桶**：`SimosToolSource.java:134-147`，**追加在 `:146` 的 `MapRegisterPathwayGroupTool` 之后**（即 `addGmWrites` 方法体末尾）：

```java
private static void addGmWrites(
    List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
  built.add(new IssueDirectiveTool(core, initiator, mapId));        // :136
  built.add(new SubmitVerdictTool(core, initiator, mapId));         // :137
  built.add(new SetViewScopeTool(core, initiator, mapId));          // :138
  built.add(new StartDecisionTool(core, initiator, mapId));         // :139
  built.add(new MapSetTerrainTool(core, initiator, mapId));         // :140
  … 7 条 map 到 …
  built.add(new MapRegisterPathwayGroupTool(core, initiator, mapId));// :146  ← M2 的 20 条接在这里
}
```

**追加点 #2 — 决策人桶**：`SimosToolSource.java:149-154`，**追加在 `:153` 的 `SubmitVerdictTool` 之后**（`addDecisionAgentWrites` 方法体末尾）：

```java
/** 决策 Agent 窄写（N9）：**无** `sd.SetViewScope`、**无**通用写。 */
private static void addDecisionAgentWrites(
    List<AgentTool> built, CoreSimos core, String initiator, String mapId) {
  built.add(new IssueDirectiveTool(core, initiator, mapId));   // :152
  built.add(new SubmitVerdictTool(core, initiator, mapId));    // :153  ← M2 的 20 条接在这里
}
```

★ **★ 顺序有可观后果（写给实现者）**：`this.tools = List.copyOf(built)`（`:117`）保插入序；
`writeFaceCoveredByTheWriteGate()`（`SimosToolsTest.java:396-401`）**按名过滤**不按下标 ⇒ 顺序**不影响**该判据。
但 `SimosToolsTest:299` 用 `containsExactlyElementsOf(MAP_WRITE_NAMES)`（**有序**）钉住"逐条构造的 7 条 map 工具"——
那是**局部 `List.of(...)`** 的顺序，不是桶内顺序 ⇒ **M2 在 GM 桶里的插入位置不触发它**。
⇒ 结论：**追加点放在方法体末尾即可，无顺序约束**；但**不要**插到 `:136-139` 的 4 条 sd 前面（会让"sd 在前"的阅读约定失效，且
`StartDecisionEndToEndTest:230-232` 等按名断言虽不红、人的预期会乱）。

### 3-D M2 后四桶的实际数字（供测试改数用）

| 桶 | M1 后 | M2 后 | 变化 |
|---|---|---|---|
| `EXTERNAL` | 12 | **12** | 不变（D-1 只给 GM + 决策人） |
| `GM` | 20 | **40** | +20 |
| `DECISION_AGENT` | 11 | **31** | +20 |
| `EXTERNAL_WITH_GM` | 23 | **43** | +20（因为它是并集，**自动含** GM 的新 20 条） |

---

## 四 爆炸半径：三个测试文件（**M2 必红逐处**）

★ 结论先说：**必红 4 处**，全部是"**精确集合相等**"型断言；**"按名 `contains` / 不含"型一律不红**（这也解释了 M1 裁定 E 里 m1 为什么不红）。

### 4-A `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java`（爆炸半径 a）

| 常量 / 断言 | 锚点 | 现尺寸/内容 | M2 后 | 红？ |
|---|---|---|---|---|
| `EXTERNAL_UNION_GM_TOOL_NAMES` | `:112-136` | **23**（9 读 + 3 通用 + 4 sd + 7 map） | **43** | — |
| `READ_TOOL_NAMES` | `:143-153` | **9** | 9（**不变**） | — |
| `WRITE_TOOL_NAMES` | `:161-176` | **14**（= EXTERNAL_UNION 减 READ 的补集） | **34** | — |
| `MAP_WRITE_NAMES` | `:179-187` | **7** | 7（**不变**） | — |
| `EXPECTED_COMMAND_TYPES` | `:189-233` | **43**（20 unit + 7 map + 16 sd） | 43（**不变**） | — |
| `registryContainsExactlyTheExternalUnionGmTools` | `:270-274` | `.containsExactlyInAnyOrderElementsOf(EXTERNAL_UNION_GM_TOOL_NAMES)` | 常量扩到 43 后**仍绿** | **不红**（除非常量没改——那是"改了一半"） |
| `catalogListsExactlyTheRegisteredCommandTypes` | `:311-320` | `:318 .hasSize(EXPECTED_COMMAND_TYPES.size())` + `:319 containsExactly…` | 驱动量是**命令类型** | **不红** |
| `catalogCoversEveryCommandHandlerImplementation` | `:331-343` | `:335 .hasSize(43)`（扫 `*Handler.java`） | 同 | **不红** |
| ★ `roleBucketsNeverCarryGenericWrite` → **externalWithGm 块** | `:372-383`，**`:383 .hasSize(23)`** | ★ **精确 23** | 真值 43 | ★★ **必红（第 1 处）** |
| 同上 → **gm 块** | `:356-360` | `doesNotContain(submit)` + `contains(4 条 sd)` + `containsAll(MAP_WRITE_NAMES)` | 按名，**不含精确尺寸** | **不红** |
| 同上 → **agent 块** | `:361-365` | `doesNotContain(submit, SetViewScope, StartDecision)` + `contains(IssueDirective, SubmitVerdict)` + `doesNotContainAnyElementsOf(MAP_WRITE_NAMES)` | ★ M2 把 20 条 unit 加进**决策桶** ⇒ **unit 不在 `MAP_WRITE_NAMES` 里，不红** | **不红** |
| ★ `writeFaceCoveredByTheWriteGate()`（M1 修掉切片缺陷后的**逐字形态**） | `:396-401` | 见下方引文 | 覆盖集从**真工具面**派生 ⇒ 自动含新 20 条 | **不红（这正是修复的价值）** |
| `writesAreSensitiveAndAskWithTheToolNameAsClassKey` | `:463-490` | `:465-467 containsExactlyInAnyOrderElementsOf(WRITE_TOOL_NAMES)`；`:468` READ∩WRITE 空；`:469-471` `concat(READ,WRITE)==EXTERNAL_UNION_GM`；`:472-482` 逐名判 `sensitive/noExport/ToolGate.Ask/classKey==name/AskKind.SENSITIVE` | ★ **`WRITE_TOOL_NAMES` 扩到 34 后仍绿**；但那 20 条新工具**必须真的满足** `sensitive + Ask + classKey==name`（继承 `AbstractNarrowWriteTool` 即自动满足） | **不红**（是护栏，不是约束） |
| `readsAreAllowGatedAndDeclareTheirResources` | `:440-460` | `:443 for (String name : READ_TOOL_NAMES)`（**按名**，不是切片） | 同 | **不红** |
| `catalogRejectsACommandTypeWithoutAPayloadHint` | `:410-414` | 故意违规用例，`Set.of("unit.NotARealCommand")` | 同 | **不红** |
| M1 判据 4 的 7 条拒绝用例 | `:582-680`（助手 `:667-680`） | 每条一个 `assertDomainRejectedAndHeadUnchanged(toolName, payloadJson, reasonFragment)` | M2 若照抄，会新增 20 条 | **不红**（新用例） |

**★ M1 修掉切片缺陷后的写法（逐字，`:390-401`）**：
```java
/**
 * 写闸**实际覆盖**的工具名。★★ 它从**真工具面**（{@code EXTERNAL_WITH_GM} 桶）派生、减去读名单，**不是**从名单常量取下标 切片。
 *
 * <p>这是 M1 修掉的那处缺陷的替代形态：原实现用 {@code subList(9, 16)}，名单加到 23 条后切片**仍然合法** ⇒ 新增的 7 条 map
 * 写工具完全不被写闸覆盖、且没有任何症状。现在覆盖集从工具面派生，退化成切片会当场红。
 */
private List<String> writeFaceCoveredByTheWriteGate() {
  return shell.toolsFor(SimosToolSource.Role.EXTERNAL_WITH_GM).stream()
      .map(AgentTool::name)
      .filter(name -> !READ_TOOL_NAMES.contains(name))
      .toList();
}
```
★ **`subList` 已全仓清零**（本次实测，见 §十-2 的命令）：`SimosToolsTest` 里只剩**注释里**提到 `subList(0, 9)`/`subList(9, 16)`
（`:141`/`:158`/`:393`），**代码里一处都没有**。其余 `subList` 命中全在**与本任务无关**的地方
（`RandomizeOperationsTest.java:229,326`、`MapRandomizeEndToEndTest.java:214`、`UnitOperations.java:275` 的 A\* 段拼接）。

### 4-B `simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java`（爆炸半径 b）

| 项 | 锚点 | 现尺寸 | M2 后 | 红？ |
|---|---|---|---|---|
| ★ **第二份手抄的** `EXTERNAL_UNION_GM_TOOL_NAMES` | `:102-126` | **23**（与 `SimosToolsTest` **内容逐条相同**） | **43** | — |
| `initializeAndToolsListExposeExactlyTheExternalUnionGmTools` | `:174-184`，`:180-183 extracting(McpSchema.Tool::name).containsExactlyInAnyOrderElementsOf(EXTERNAL_UNION_GM_TOOL_NAMES)` | — | 真值 43 | ★★ **必红（第 2 处）** |
| `writeToolBlocksOnApprovalThenCommitsWithConfiguredInitiator` | `:244-290` | 真 MCP 传输 + 审批两轮（DENY ⇒ `APPROVAL_DENIED` 且 head 不动；APPROVE_ONCE ⇒ `committed` + initiator 逐字） | — | **不红** |
| `unitListMatchesQueryServicePerValue` / `mapHexReturnsTheSameFacetsAsQueryService` | `:189-211` / `:214-240` | 逐值对拍 | — | **不红** |
| 其余（`220-440` 已读） | `:292-440` | 夹具（`seedGenesis`、`corridorMap`、`unit()` 等） | — | **不红** |

★ **`EXTERNAL_UNION_GM_TOOL_NAMES` 是"手抄两份"的确证**：`SimosToolsTest.java:112-136` 与 `McpServerTest.java:102-126`，
**两份内容逐条相同、都是 23**。⇒ **M2 必须两处同改**（漏一处 = 只红了另一处的替代品）。

### 4-C `simos-app/src/test/java/io/mosire/simos/app/McpPortTopologyTest.java`（爆炸半径 c）

| 常量 / 断言 | 锚点 | 现尺寸 | M2 后 | 红？ |
|---|---|---|---|---|
| `READ_TOOLS` | `:42-52` | **9** | 9（**不变**） | — |
| `GENERIC_WRITES` | `:54-55` | **3** | 3（**不变**） | — |
| `MAP_WRITES` | `:58-66` | **7** | 7（**不变**） | — |
| `GM_NARROW_WRITES` | `:69-81` | **11**（4 sd + 7 map） | **31**（+20 unit） | — |
| `DECISION_AGENT_WRITES` | `:83-84` | **2** | **22**（+20 unit） | — |
| ★ `existingPortExposesExternalUnionGmToolFace` | `:106-116`，**`:113-114 containsExactlyInAnyOrderElementsOf(concat(READ_TOOLS, GENERIC_WRITES, GM_NARROW_WRITES))`** | — | 真值 43 | ★★ **必红（第 3 处）** |
| ★ `decisionPortExposesOnlyDecisionAgentToolFace` → 正向 | `:120-137`，**`:130 containsExactlyInAnyOrderElementsOf(concat(READ_TOOLS, DECISION_AGENT_WRITES))`** | — | 真值 31 | ★★ **必红（第 4 处）** |
| 同上 → 反向三条 | `:131-135` | `doesNotContainAnyElementsOf(GENERIC_WRITES)` / `doesNotContain("sd.SetViewScope")` / `doesNotContainAnyElementsOf(MAP_WRITES)` | ★ **unit 20 条既不在 GENERIC_WRITES、也不在 MAP_WRITES** | **不红** |
| `bothServersListenAndCloseReleasesBothPorts` | `:141-160`，`:152 contains("sd.SetViewScope")` / `:153 doesNotContain("sd.SetViewScope")` | — | 按名 | **不红** |
| `decisionPortHasNoGenericWriteSoSubmitIsUnknownTool` | `:164-189` | 协议层 `Unknown tool` + 不留 revision | — | **不红** |

★ **`READ_TOOLS` 也是"手抄两份"**：`SimosToolsTest.java:143-153`（名叫 `READ_TOOL_NAMES`）与 `McpPortTopologyTest.java:42-52`
（名叫 `READ_TOOLS`）。本文件里那份**被两个端口共用**（`:114` 与 `:130` 都引它）⇒ **一处改两处红**（M4 侦察已记的同一条）。

### 4-D 第四处会红的地方（**不在上面三个文件里的"意外"**）—— 无

★ 我逐文件找了所有**枚举工具面**的位置：
```bash
git grep --untracked -n 'listTools()\|toolsFor(\|toolRegistry()' -- 'simos-app/src/test' 'simos-core/src/test'
# → simos-app/.../McpPortTopologyTest.java  (listTools, 1)
#   simos-app/.../McpServerTest.java        (listTools, 1)
#   simos-app/.../ShellApprovalTest.java    (toolRegistry, 1)
#   simos-app/.../gui/GmToolUsageApiTest.java (toolRegistry, 2)
#   simos-app/.../sd/StartDecisionEndToEndTest.java (toolRegistry, 1 / toolsFor, 3)
#   simos-app/.../tools/SimosToolsTest.java (toolRegistry, 13 / toolsFor, 6)
```
逐处判过：

| 站点 | 会不会被 M2 打红 | 依据 |
|---|---|---|
| `StartDecisionEndToEndTest:228-239` | **不会** | 三条都是 `contains(StartDecisionTool.NAME)` / `doesNotContain(...)` —— **按名、非精确**（`:230-238`） |
| `GmToolUsageApiTest:79,103` 的 `hasSize(1)`/`hasSize(2)` | **不会** | 断言对象是 `entries` = **真调过工具后的审计行**（`:74-85,97-106`），**不是工具面** |
| `ShellApprovalTest:278` | **不会** | `toolRegistry().find(CommandSubmitTool.NAME)`，**按名** |
| `simos-core/.../MapSetTerrainEndToEndTest.java:…` | **不会** | core **编译期看不见 `simos-app`**（ADR-1）⇒ 它引用的是**命令类型**，不是工具名 |
| `webui/*.test.cjs` 4 个文件 | **不会**（且 **M2 不该动 JS**） | 见 §五 |

★ **一条口径**：`McpCoverageTest` 里也出现 `"map.SetTerrain"`（`:130`/`:190`），但那是**命令类型**（catalog 的 `types[]` 与 `MINIMAL_PAYLOADS` 的键），
**不是工具名** ⇒ **M2 = 零改动**（与 M1 实测相同）。

---

## 五 两张表是否已经就位（**都是空操作**）

| 表 | 锚点 | 事实 | M2 的动作 |
|---|---|---|---|
| **`CatalogTool.PAYLOAD_HINTS`**（§六） | `simos-app/.../tools/read/CatalogTool.java:33-101` | **43 条**，其中 **20 条 unit 全部在场**（`:35-56`），逐条含 `unit.CreateUnit` 的 `"parent?"`、`unit.PlaceAt` 的 `"id, hex{q,r}?（null=撤销位置）"`、`unit.ApplyCasualties` 的 `"id, personnel(负增量), equipment{键:负增量}"` 等 | ★★ **零改动** |
| **`CatalogTool` 构造期强制** | `:109-122` | 缺提示即抛 `已注册命令类型未登记载荷提示（PAYLOAD_HINTS）: <missing>`（T10-j） | 新工具**不新增命令类型** ⇒ 不触发 |
| **`McpCoverageTest` 双向载荷** | `EXPECTED_COMMAND_TYPES` `:108-152`（43，含 20 unit）；`MINIMAL_PAYLOADS` `:155-267`（43 键，含 20 unit） | 20 条 unit 类型**全在**两张表里；`:318-323` 双向 `containsExactlyInAnyOrderElementsOf` | ★★ **零改动** |

★ **这条与 M1 的实测一致**（M1 台账 §1.2：「7 条 map 提示**已在表内** ⇒ 对 M1 是空操作」）——
**规矩的对象是"命令类型"，不是"工具"**。creed 五写「每加一条**命令/工具**」**逐字读会误导**；
M1 台账已把它列为**待用户确认项**，本报告独立复核：**该措辞对 M2 同样误导**（M2 加 20 条**工具**，两张表都不动）。

★ **M2 不需要动的另一个文件**：`Shell.java` —— 20 条 handler **已全部注册**（`:314-333`），M2 只加工具、不加 handler。

---

## 六 GUI 侧第二源：`webui/modes.js` —— **确认 M2 是 Java 侧，不该改 JS**

`simos-app/src/main/resources/webui/modes.js`（116 行，本次全读）

| 项 | 锚点 | 内容 |
|---|---|---|
| `unit` 模式的 `writes` 白名单 | `:50-57` | **只有 6 条**：`unit.PlanRoute` / `unit.CancelRoute` / `unit.ReparentUnit` / `unit.SetStrength` / `unit.DisbandUnit` / `unit.CreateUnit` |
| 语义裁定注释 | `:43-49` | ★ **T10-i**：该表的语义 = **工作台（workbench）实际写命令面**，**不是**后端注册面；`unit.RenameUnit`/`unit.PlaceAt` **有意不列**（只由**调试页** `unit.js` 直发） |
| 同上 | `:43-49` | ★ **T10-l 范围声明**：T9 新注册的 12 条 unit 命令**在工作台无 UI 入口**（unit-ext 是 **MCP/agent-only**）⇒ **有意不列** |
| fail-closed | `:102-107` | `isWriteAllowed` = `allowedWrites(mode).indexOf(type) >= 0` ⇒ **未知模式/未知类型一律 false** |

**结论（逐条）**：
1. **M2 不改 JS。** 20 条新工具是 **MCP/agent 面**，工作台**看不到也发不出**（白名单 fail-closed + 无 UI 入口）。
   ⇒ 与调用方的预期**一致**。
2. ★ **"全绿"不等于"界面上能用"** —— 这是 T10-l 的范围声明，M2 的报告里**要照抄这条口径**，
   否则读报告的人会以为 20 条 unit 命令在 GUI 上可用了。
3. ★ **一条隐患（登记，不裁决）**：`modes.js` 的 `writes` 表与后端工具面**没有同源判据**——
   后端加了 20 条工具、前端白名单**不会因此变红**。T10-i 补的守卫 `workbench-write-calls-are-all-whitelisted`
   是**反向**的（扫 `webui/*.js` 的 `writeCommand("<type>"` 字面量，要求每个都在某个模式里被放行），
   **挡不住**"后端新增能力而前端不知道"。⇒ 与 M4 侦察的「读口没有 `McpCoverageTest` 的等价物」**同族**。

---

## 七 `AppWritePathGuardTest` 的三个禁字（逐字）

`simos-app/src/test/java/io/mosire/simos/app/AppWritePathGuardTest.java`（165 行，本次全读）

```java
/** 三个禁止出现的存储/时间线写面符号（spec §十一 R1）。 */
private static final List<String> FORBIDDEN =
    List.of("SqliteStore", "Timeline", "CheckpointStore");     // :35-36
```

| 项 | 锚点 | 内容 |
|---|---|---|
| 扫描范围 | `:32` + `:72-82` | `MODULE_MAIN = Paths.get("src/main/java")`（**相对路径** ⇒ 主树与 worktree 都成立，`:20-21` 有明确的形态说明） |
| **非空自证** | `:42-46` | `hasSizeGreaterThanOrEqualTo(5)` + 必须含 `GuiServer.java` 与 `Shell.java` |
| 扫代码不扫注释 | `:49` + `:98-164` | `stripComments(...)` 之后再判串 |
| 去注释器自证 | `:59-70` | 注释里的串被去掉；**字符串字面量里的串保留**（`Object c = CheckpointStore.class;` 与 `"SqliteStore"` 都必须命中） |

**⇒ 对 M2 的约束（逐条）**：
1. **20 个新类不得出现 `SqliteStore` / `Timeline` / `CheckpointStore`**（含**字符串字面量**里）。
   ★ 特别注意：**Javadoc 里的提及也会被 `stripComments` 去掉 ⇒ 安全**；但**字符串字面量不安全**。
2. 新类放在 `simos-app/src/main/java/.../tools/write/` 下 ⇒ **在扫描范围内**（`Files.walk` 递归）。
3. ★ **一处必须提醒**：新类的 Javadoc 里**不要**写「本类不经 `Timeline`」这类反例式说明——
   虽然去注释器会去掉它，但**那只在被 `stripComments` 正确识别的前提下成立**；
   本文件自己就是被这条坑过的（`:25-28` 的取代说明）。**保守做法：干脆不写这三个词。**

---

## 八 代价核算

| 项 | 结论 |
|---|---|
| **必改文件（生产）** | **1 个**：`simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java`（两个追加点各 +20 行） |
| **必建文件（生产）** | **20 个**：`simos-app/src/main/java/io/mosire/simos/app/tools/write/UnitXxxTool.java`（各继承 `AbstractNarrowWriteTool`，≈25-45 行） |
| **必改文件（测试）** | **3 个**（5 个常量 + 4 处断言）：`SimosToolsTest`（`EXTERNAL_UNION_GM_TOOL_NAMES` 23→43、`WRITE_TOOL_NAMES` 14→34；**无断言需改**——只有 `:403-401` 的派生式守卫会自动跟上）、`McpServerTest`（`EXTERNAL_UNION_GM_TOOL_NAMES` 23→43）、`McpPortTopologyTest`（`GM_NARROW_WRITES` 11→31、`DECISION_AGENT_WRITES` 2→22） |
| **必红断言** | **4 处**：`SimosToolsTest:383`（`.hasSize(23)`）、`McpServerTest:180-183`、`McpPortTopologyTest:113-114`、`McpPortTopologyTest:130` |
| **零改动文件** | `Shell.java`（handler 全注册）、`CatalogTool.java`（43 条提示全在）、`McpCoverageTest.java`（43 类型 + 43 载荷全在）、`webui/**`（前端白名单语义另有所指）、`simos-unit/**`（域层不动）、`simos-map/**`、`simos-sd/**`、`simos-core/**`（M1 简报 §11 已禁改 map） |
| **保险丝** | `AppWritePathGuardTest` 的三个禁字（§七） |
| **建议新增（M2 判据 4 的对应物）** | 20 条坏载荷用例（照 M1 的 `assertDomainRejectedAndHeadUnchanged` 形态）。★ **成本提示**：M1 只写 7 条；M2 是 **20 条**，且**第 1 条 `unit.RenameUnit` 的载荷层文案与其余 19 条不同**（§2-A 末），不能一条模板套 20 次 |
| **建议新增（M2 判据 1 的对应物）** | GM 桶 40 条 / 决策桶 31 条 / 复合口 43 条的**精确集合**断言 —— ★ **必须走"扩常量"而非"再抄一份"**，否则会造出**第三份**手抄名单（本仓已有两份，§4-A/§4-B） |

**★ 代价最容易被低估的一处**：`UnitPayloads` 是**包级私有**（`static JsonNode parse(...)`，类注 `:1-30`），
`AbstractNarrowWriteTool` 在 **`simos-app`**、`UnitPayloads` 在 **`simos-unit`** ⇒ **工具层看不见它**。
⇒ M2 **不能**在工具层复述载荷校验（也**不应该**——M1 裁决 A：工具层校验可被 `simos.command.submit` 绕过 ⇒ 是装饰）。
⇒ **M2 的义务与 M1 一致：证明拒绝理由真的到达调用方**（经 `ToolSupport.fold` → `ToolResult.error("REJECTED", <原文>)`）。

---

## 九 我未能核实的（逐条给"为什么没读到"）

★ 本节的每一条都**没有**用推导顶替；给出的是**为什么没读到**（范围/权限/口径），不是"我认为应该是"。

1. **★ 最大的一条：全部结论未经运行验证。**
   本次侦察是**只读**的（调用方明令「不跑 Maven / node / npm」），且本机 `nproc=2` 且**另有一个 agent 在跑**（调用方明令「不要跑任何构建」）。
   ⇒ §〇 的所有数字是**读码 + 计数**；§四的"必红 4 处"是**读断言 + 数集合**推出的。
   **"必红"的实测认证（跑一次全量、看它红在哪一行）本次没有做，也不该由侦察者做。**

2. **`subList` 的全仓清零** —— **已核实**（写在这里是因为它是"我没搜到"型，必须给命令与范围）：
   ```bash
   git grep --untracked -n 'subList' -- simos-app simos-core simos-map simos-unit simos-sd simos-util
   ```
   结果：`SimosToolsTest:141,158,393`（**只在注释里**）+ `MapRandomizeEndToEndTest:214` +
   `RandomizeOperationsTest:229,326` + `UnitOperations:275`。**`SimosToolsTest` 的代码里一处都没有。**
   ★ 范围声明：这条命令**覆盖六个模块的已入库 + 未入库文件**；**不含** `simos-app/src/main/resources/webui/**`（那里没有 `subList` 语义；
   JS 的 `.slice(` 未查——**与 M2 无关**，M2 不改 JS）。

3. **`agentlib-mosire` 是外部依赖、不在本仓** ⇒ `ToolSpec.level(...)` / `ToolGate.Ask` / `AskKind.SENSITIVE` /
   审批链的**真实语义**未核实。我只读到 **simos 侧的调用点**（`AbstractNarrowWriteTool.java:62-74`）。
   为什么没读到：仓库里没有它的源码（`~/.m2` 里的 jar 是另一台机器上建的，本阶段台账 §〇-pre 记过一次跨机构件差距）。

4. **`ToolSupport.fold` 的三条映射只读到 form**：
   `ToolSupport.java:235-245` 逐字读过（`Committed → ok` / `Conflict → error("CONFLICT", …)` / `Rejected → error("REJECTED", …)`），
   但**`fold` 真的被 20 条新工具调用过**这一点，本次**未核实**（新工具还不存在）。
   为什么没读到：它们还没有被写出来。

5. **`SimosObjectMapper` 的严格性对 `readTree` 不生效（§2-D 的 U-1）—— 这是我的"读码推导"，未跑验证。**
   我读到的是 `UnitPayloads.java:42` 与 `MapPayloads.java:42` 都调 `MAPPER.readTree(...)`，
   且 `SimosObjectMapper.java:46` 的那句"不关闭 `FAIL_ON_UNKNOWN_PROPERTIES`"指的是 **bean 绑定**的 feature。
   为什么标"未核实"：`FAIL_ON_UNKNOWN_PROPERTIES` 在 Jackson 里是否**也**对 tree 读取有任何影响，**我没有跑实验确认**，
   也没有找到 simos 侧对它的直接断言。⇒ 建议 M2 若要在报告里引用这条，**先跑一个两行的探针**（或干脆不引用）。

6. **`UnitChangeSet.isEmpty()` 是否与 `MapChangeSet.isEmpty()` 在同一处境（§2-D 的 U-2）—— 未核实。**
   为什么没读到：我读的是 M1 侦察对 **map** 侧的结论（`MapChangeSet.java:107` vs `CommandBus.java:320-332,347-378`），
   **没有逐行读** `UnitChangeSet` 与 `CommandBus` 的组合面。

7. **`unit.js`（旧调试页）里的写命令构造** —— 未核实。
   为什么没读到：T10-i 的台账说 `unit.RenameUnit`/`unit.PlaceAt` **只由调试页 `unit.js` 经 `SimosApi.submitCommand` 直发**，
   我**读了 `modes.js`（工作台白名单）但没读 `unit.js`** ⇒ **M7c 保留的那条"调试面 PlaceAt 表单"只从台账得知，未自证。**

8. **其它模式（map/social/sd）的 `modes.js` 写白名单逐条内容** —— 未逐条列。
   为什么没读到：与 M2 无关（M2 不改 JS，且 unit 模式 `:50-57` 已读全）。**只读了 unit 模式那一段。**

9. **`McpCoverageTest.MINIMAL_PAYLOADS` 的 43 条载荷"顺序即语义合法序"是否被 M2 影响** —— 未核实。
   为什么：M2 **不新增命令类型** ⇒ 顺理成章"不受影响"；但这是**推导**。我只数了键数（43），**没有逐条模拟 T9 的时序设计**
   （例如 `unit.DisbandUnit` 在 put 序第 8 位去掉 `u-1`，其后的命令必须不依赖 `u-1`）。
   ★ 若 M2 的 20 条坏载荷用例**要复用** `MINIMAL_PAYLOADS` 的顺序，**必须先读 T9 的那段时序设计**。

10. **`git grep` 的未入库覆盖** —— 本次所有全仓搜索都用了 `--untracked`（调用方点名的那一族）。
    但**未逐条复核** `git grep --untracked` 在本机 git 版本下是否**等价于** `--no-index`（CLAUDE.md 说实测等价）。
    为什么：本次的每条搜索都只看**已入库的 Java 源**（`simos-*/src/**`），untracked 与否不改变结论。

11. **`Enum Role` 之外是否还有第五个桶入口** —— 未核实（只读了 `SimosToolSource` 的 `Role` 与 `Shell` 的调用点）。
    为什么：`git grep --untracked -n 'toolsFor(\|listTools()'` 的命中都在测试里（§4-D），
    **生产侧的另一个入口（如 `McpSourceBridge.bind`）我没有逐行读**。

12. **M2 落地后 `Shell.toolRegistry()` 的规模是否触发别的护栏**（如审批上限、审计行数、`ToolUsage` 表的容量）—— 未核实。
    为什么没读到：这些面在 `agentlib-mosire`（外部依赖）里。

---

## 十 一条给控制器的形态记录（本次当场踩到，值得进本仓纪律）

**"数表项要按块数，不能按行前缀数"**（§〇 的 ★ 条）：
`grep -c 'Map.entry("unit\.'` → **19**，真值 **20**，差的那条是**换行写法** `Map.entry(\n "unit.CreateUnit", …)`。
它与本仓已有的两条同族：
- **T5-L5**：变异自证的计数判据必须**逐片段**判，不能按**整份文件**判；
- **M2 Task 1 的旧 `.class`**：聚合模式的 `"$CLASS_NAME".*.class`（多一个点）只匹到一半文件、读到空串 ⇒ 恒真。

⇒ 共同形态：**"计数器的判据范围"与"被数的对象"不重合时，读数会静默偏小，而偏小的读数看起来仍然像"数过了"。**
建议本仓记一条：**凡用一个数字当依据，先问"这个数字是按什么单位数的"，并至少用第二种数法交叉一次。**
