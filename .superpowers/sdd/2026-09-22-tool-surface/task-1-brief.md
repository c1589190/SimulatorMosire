# Task 1 简报 — M1 地图组（7 条写工具）

> 控制器交给你的**唯一需求来源**。下面的文件名/类名/字符串/行号**逐字照用**。
> 行号会漂移：**先按名找，行号只当提示**。

## 0. 你在哪 / 干什么

- 工作区：**worktree `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1`**（分支 `ts/m1`）。★ **本仓同时有主检出与 worktree，同名文件各一份 ⇒ 改文件一律用 worktree 内的绝对路径。**
- 项目：SimulatorMosire（simos），模块化仿真引擎。八模块：`simos-util` → `simos-map` → {`simos-social`, `simos-unit`} → `simos-core` → `simos-app`，外加 `simos-sd`。
- 你的任务：把 map 域的 **7 条写命令**各包一个**窄写工具**，挂进 **GM 桶**；并修好这次改动会打红的既有断言。

## 1. 背景（一句话就够）

simos 有**通用写** `simos.command.submit`（模型自选 type + 自填载荷）。本阶段要做的是**窄写工具**：
**命令类型在工具里固定死**，模型只能给载荷 ⇒ "选什么命令"不再是模型能自由发挥的面。

窄写工具**不走新路径**：它照样组一条 `CommandEnvelope` 交给 `core.submit`（铁律 2：一切修改都走
`Command → ChangeSet → Revision`）。

## 2. 形制 —— 照抄这个文件，不要发明

`simos-app/src/main/java/io/mosire/simos/app/tools/write/IssueDirectiveTool.java`（全文 29 行）：

```java
package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/** {@code sd.IssueDirective} 窄工具（spec §八.3，N9）：决策人出令的**唯一**写面。 */
public final class IssueDirectiveTool extends AbstractNarrowWriteTool {

  public static final String NAME = "sd.IssueDirective";

  public IssueDirectiveTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "决策出令 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }

  @Override
  public String description() {
    return "决策人出令：固定 sd.IssueDirective，载荷 {directiveId, decisionMakerId, tick, target?, intentInfo, commands[], effects[]?}";
  }
}
```

`AbstractNarrowWriteTool`（**同包，package-private，你在同包内可直接继承**）已经钉死了：
schema（`payloadJson`/`branch`/`expectedRevision`，required 只有后两者）、`spec()` = `ToolSpec.level(AccessToken.DEFAULT, true, false)`
（第三参 `sensitive=true` ⇒ 走审批门链）、`gate()` = `ToolGate.Ask(name(), summary, AskKind.SENSITIVE)`、
`resources()` = `ToolSupport.ALL_WRITE`、`execute()`（组信封 → `core.submit` → `ToolSupport.fold`）。
**你一个字都不用改基类。**

## 3. 要建的 7 个类

包：`io.mosire.simos.app.tools.write`。类名 / `NAME` / `description()` 返回串**逐字如下**：

| 类名 | `NAME` | `description()` 返回串 |
|---|---|---|
| `MapSetTerrainTool` | `map.SetTerrain` | `"GM 改地形：固定 map.SetTerrain，载荷 {hexes[{q,r}…], terrain}"` |
| `MapSetEdgeTool` | `map.SetEdge` | `"GM 改连通性：固定 map.SetEdge，载荷 {kind, edges[\"q_r\|q_r\"…], mode（replace\|merge，无默认）}"` |
| `MapCreateRegionTool` | `map.CreateRegion` | `"GM 建区域：固定 map.CreateRegion，载荷 {regionId, name, hexes[{q,r}…], meta?}"` |
| `MapUpdateRegionTool` | `map.UpdateRegion` | `"GM 改区域：固定 map.UpdateRegion，载荷 {regionId, hexes?, meta?}（至少给一个；★ meta 是整体替换——给了就把 color/tag/description/annexedBy 四键给全，只给一个键会静默清掉其余三个）"` |
| `MapDeleteRegionTool` | `map.DeleteRegion` | `"GM 删区域：固定 map.DeleteRegion，载荷 {regionId}"` |
| `MapRandomizeRegionTool` | `map.RandomizeRegion` | `"GM 随机化区域地形：固定 map.RandomizeRegion，载荷 {hexes[{q,r}…], seed（整数，无默认）}"` |
| `MapRegisterPathwayGroupTool` | `map.RegisterPathwayGroup` | `"GM 注册通路组：固定 map.RegisterPathwayGroup，载荷 {id, name, color（#RRGGBB）, description?, visible?（缺省 true）, properties?}"` |

`summary(args)` 一律照 `IssueDirectiveTool` 的形态，把工具名换掉：

```java
  @Override
  protected String summary(Map<String, Object> args) {
    return "改地形 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }
```

（中文短语用各工具自己的动作：改地形 / 改连通性 / 建区域 / 改区域 / 删区域 / 随机化区域 / 注册通路组。）

每个类都要有**中文类 Javadoc**，说明它是什么、归哪个桶。风格照 `IssueDirectiveTool` / `StartDecisionTool`。

**★ 载荷真值表**（`description()` 已写进去，此处备你写用例）：

| 命令 | 必填 | 可选 / 无默认 |
|---|---|---|
| `map.SetTerrain` | `hexes`、`terrain` | — |
| `map.SetEdge` | `kind`、`edges`、`mode` | `mode` **没有默认值**，只收 `replace`/`merge` |
| `map.CreateRegion` | `regionId`、`name`、`hexes` | `meta?` |
| `map.UpdateRegion` | `regionId` | `hexes?`、`meta?`（**至少给一个**） |
| `map.DeleteRegion` | `regionId` | — |
| `map.RandomizeRegion` | `hexes`、`seed` | `seed` **没有默认值** |
| `map.RegisterPathwayGroup` | `id`、`name`、`color` | `description?`、`visible?`（缺省 `true`）、`properties?` |

（`hexes` 形如 `[{"q":1,"r":2}, …]`；`edges` 形如 `["1_2|1_3", …]`。）

## 4. 接线：进 GM 桶

文件 `simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java`。

- 桶由 `enum Role { EXTERNAL, GM, DECISION_AGENT, EXTERNAL_WITH_GM }` 决定；
  `EXTERNAL_WITH_GM` 已经是 `addExternalWrites(...) + addGmWrites(...)` ⇒ **你只改 `addGmWrites`，不要动别的桶**。
- 在 `addGmWrites(...)` 里（现有 4 条 `IssueDirectiveTool`/`SubmitVerdictTool`/`SetViewScopeTool`/`StartDecisionTool` 之后）
  按 §3 表格顺序追加 7 条 `built.add(new MapXxxTool(core, initiator, mapId));`。
- ★ **每条工具都要能经 `catalog` 反映出来**（同源）——`SimosToolSource` 里读工具与写工具的装配点都在同一个文件，你读完就知道该接哪。

## 5. 前置即错 —— 本任务**不加**工具层前置校验（控制器裁决，照办）

用户口径（原话）：`CreateArmy`「**有对应 Nation/Army 就正常，没有就报错**」；
creed §三：「工具层要做前置校验，失败要**可读的理由**，**不许静默返回成功/空**」。

**裁决：M1 的 7 条命令，域层已逐条具备可读拒绝文案 ⇒ 工具层不重复校验。** 理由：
1. 域层拒绝经 `ToolSupport.fold` 变成 `ToolResult.error("REJECTED", <域层理由原文>)` ⇒ **可读理由已经到达调用方**；
2. 工具层的校验**可被 `simos.command.submit` 绕过** ⇒ 那才是装饰（本仓纪律：「护栏必须自证」，绕过得了的护栏不算护栏）；
3. 逐条核过（recon 取证），7 条命令的缺前置路径**都有**可读文案，**没有一条静默成功**。

⇒ **你的义务是"证明拒绝理由真的到达调用方"**，不是"再写一遍校验"。见 §6 判据 4。

**同理，下面 7 处域层静默缺口控制器已裁「不在 M1 修」，你也不要顺手修**
（它们是**既有的、今天经 `simos.command.submit` 就可达**的行为，改它们会牵动已关账的 M2/M8 变异轮，
代价远超本任务；已记入台账开口项）：

1. 通路组 id **大小写变体**：注册侧 `containsKey`（敏感），解析侧 `resolveKind` 大小写不敏感且取**首个** ⇒ 已有 `river` 时注册 `River` 成功，此后 `kind:"River"` 落到先注册的 `river`；
2. `map.SetEdge` **无邻接校验**（可连相隔半张图的两格）；
3. `map.UpdateRegion` 的 `meta` 是**整体替换**（见 §3 的 description 已按此写）；
4. 未知多余字段被**静默忽略**（可选字段拼错 ⇒ 静默取缺省）；
5. **空操作仍落 revision**（`MapChangeSet.isEmpty()` 存在但 `CommandBus` 从不读它）；
6. `PathwayGroup.PropertyDef.type` **不做取值白名单**；
7. `map.CreateRegion` **无 name 唯一性校验**。

## 6. 判据（必须逐条有实测值）

1. **桶正确**：7 条新工具出现在 `GM` 与 `EXTERNAL_WITH_GM` 两个桶的工具名集合里；
   **不出现**在 `EXTERNAL` 桶里，**不出现**在 `DECISION_AGENT` 桶里。
2. **名字同源**：每条工具的 `name()` == 其固定命令类型；且 7 个类型都已在 `catalog` 里。
3. **敏感写**：每条工具 `spec().sensitive()` 为真，`gate()` 是 `AskKind.SENSITIVE` 的 `Ask`。
4. **★ 前置即错（本任务的核心判据）**：7 条工具**各一条**"坏载荷 ⇒ 可读 `REJECTED` 且 head 不变"的用例。
   用**域层已有文案**触发，逐条如下（断言里**要含该文案的关键片段**，不要只断 `Rejected` 这个词——
   「只判 token 判不出是哪一层拒的」是本仓反复踩过的坑）：

   | 工具 | 坏载荷 | 域层拒绝文案（关键片段） |
   |---|---|---|
   | `map.SetTerrain` | `terrain:"not_a_terrain"` | `未知地形类型` |
   | `map.SetEdge` | `kind:"not_registered"` | `未知连通性类型` |
   | `map.CreateRegion` | `regionId` 用一个已存在的 | `区域已存在` |
   | `map.UpdateRegion` | `hexes`/`meta` **都不给** | `必须至少给 hexes 与 meta 之一` |
   | `map.DeleteRegion` | 不存在的 `regionId` | `区域不存在` |
   | `map.RandomizeRegion` | 空 `hexes: []` | `hexes 不得为空` |
   | `map.RegisterPathwayGroup` | `color:"red"`（非 `#RRGGBB`） | `color 必须是 #RRGGBB` |

   ★ 这条判据要**每条各一个用例**，不要写成一个循环里断 7 次——循环里断 7 次时，
   变异杀掉一条其余六条照样绿，**判别力会被稀释**。
5. **★ 修掉一处既有闸门缺陷**（见 §7）。

## 7. 爆炸半径 —— 会红的 4 处，**全部**要你处置

**(a) `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java`**
- 名单常量 `EXTERNAL_UNION_GM_TOOL_NAMES`（常量名照用）**现 16 条**（9 读 + `simos.command.submit`/`advance`/`fork` + 4 条 sd 窄写）
  ⇒ 加 7 条 map 写后 **23 条**。改名单常量。
- 同文件里一处 `.hasSize(16)` ⇒ **23**。
- ★★ **同文件的两处 `subList` —— 这是缺陷，不是"改个数字"**：
  读闸用 `EXTERNAL_UNION_GM_TOOL_NAMES.subList(0, 9)`、写闸用 `subList(9, 16)`。
  **索引切片**在名单变长后仍然合法 ⇒ **测试照绿，但新增的 7 条写工具完全不被这两条闸门用例覆盖**
  （本仓纪律里「把没发生伪装成没发生」那一族的又一实例）。
  **修法**：改成**按名单选**，并把断言语义从"这 7 条被覆盖"改成
  **"写闸覆盖的集合 == 写工具全名单集合"**（即：读工具名字的补集）。
  ⇒ 这样"退回索引切片"这个变异体**才杀得掉**（切片下覆盖集 ≠ 写工具全集）。
- 同文件有若干**过期注释**提到 16/9/4 等数字，一并改对。

**(b) `simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java`**
- 同形的名单常量（现 16 条）+ 一处 `containsExactlyInAnyOrderElementsOf` ⇒ 加 7 条 ⇒ **23**。

**(c) `simos-app/src/test/java/io/mosire/simos/app/McpPortTopologyTest.java`**
- 三名单 `READ_TOOLS`(9) / `GENERIC_WRITES`(3) / `GM_NARROW_WRITES`(4) 拼起来做精确匹配
  ⇒ `GM_NARROW_WRITES` 加 7 条 ⇒ **11**。
- 同文件的**决策口**精确匹配与 `.doesNotContainAnyElementsOf(GENERIC_WRITES)`：因为 7 条进的是 GM 桶
  （不是决策桶、也不是通用写）⇒ **不会红**，但请**跑一遍确认**，不要假定。

**(d) `simos-app/src/main/java` 的禁字约束**（不红，但新代码要遵守）
`AppWritePathGuardTest` 扫描 `simos-app/src/main/java` 下全部 `.java`，**剥注释后**禁止出现字面量
`"SqliteStore"` / `"Timeline"` / `"CheckpointStore"`。你的新类**不得**含这三个串。

**(e) 明确【不改】的两处**（recon 已证，别白干）
- `CatalogTool.PAYLOAD_HINTS`：7 条 map 提示**已在表内**，构造期校验也**已经会通过** ⇒ **零改动**。
  （计划 §三.4 写的"补一条"对 M1 是空操作，控制器已记台账。）
- `McpCoverageTest`：7 个类型**已在**双向载荷断言与最小载荷表里 ⇒ **零改动**。

## 8. 变异（≥3 条，每条都要**真的被杀**，且存活要如实报）

- **m1 桶错位**：把 7 条里的任意一条从 `addGmWrites` 挪到 `addExternalWrites` ⇒ 期望 (a)/(b)/(c) 红。
- **m2 名字错**：把任意一条工具的 `commandType()` 返回值改成另一个已注册类型 ⇒ 期望名字同源判据红。
- **m3 ★ 退回索引切片**：把 §7(a) 的"按名单选"改回 `subList(9, 16)` ⇒ 期望"覆盖集 == 写工具全集"那条**红**
  （**这条是本次最重要的变异**：它证明 §7(a) 的修复不是装饰）。
- 变异纪律（本仓硬要求）：**按变异文件名（而非目标类名）拷入会让"红"变成编译错误 ⇒ 不算数**；
  必须按**白名单**把变异体推成**目标类名**，并**强制断言日志里 `COMPILATION ERROR` 计数为 0**，
  不为 0 就当场作废该轮。每轮开跑前把工作目录恢复成干净世界（重编原件、比 md5）。
  红的理由必须是被保护的那行本身。

## 9. 门禁

- `./mvnw clean verify` **rc=0**；模块判据 **`SUCCESS [`**（显示名 `UtilSimos`/`SimosApp` 等）**8/8**；`[ERROR]` **0** 行；
  `BugInstance size is 0` **×7**；前端 `[frontend-gate] OK … fail=0`。
- ★ **基线数字一律现场重算**（只取模块汇总行 `Tests run:` 相加），**不得引用任何文档里的现成数字**（含台账）。
- ★ **本机 `nproc=2`**：一次只准有一个重活。**跑 Maven 时不要同时跑别的重活**。
- ★ **全量 `clean verify` 耗时压在 600 s 线附近**：前台起跑、给足超时；**被杀既不是红也不是绿**，
  如实记"第几次尝试"并留档不删。
- 迭代期可只跑相关模块：`./mvnw -pl simos-app -am -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false test`。
- ★ 关账前**必须**跑过一次全量 `clean verify`（`mvn test` 不跑 SpotBugs）。

## 10. 证据 + 报告

- 证据目录：`.superpowers/sdd/2026-09-22-tool-surface/m1-evidence/`（已建）。
  门禁日志、变异体与每轮日志、判据实测输出**都落这里**；★ **每份产物要自指**（把"这一轮跑的是哪份字节（md5）"追加进日志本身）。
- 报告写到 `.superpowers/sdd/2026-09-22-tool-surface/task-1-report.md`，含：
  1. 状态（DONE / DONE_WITH_CONCERNS / NEEDS_CONTEXT / BLOCKED）
  2. 提交（`git rev-parse HEAD` 的短 sha，一条或多条）
  3. 一行测试小结（跑了什么、多少条、结果）
  4. **判据 §6 逐条实测值**（不是"已完成"，是**数字/输出**）
  5. **变异**：每个变异体 → 被杀 / 存活 + 红点在哪一行
  6. **§我未能核实的**（必填，没有就写"无"）
- 返回给我的**只要**：状态、提交 sha、一行测试小结、concerns。**不要把报告正文贴回来。**

## 11. 全局约束（本仓硬规矩，违反会出事）

- ★ 绝不 `git add -A`；提交前先扫 `git diff --cached`。
- ★ **一个 key 都不许进代码**；值绝不进日志/异常/argv/env。
- ★ 不许 `pkill -f '<含端口/store 名的串>'`。
- ★ 不许改 `simos-map`（本任务**零领域改动**，只动 `simos-app`）。
- 注释与文档用**中文**，与既有风格一致。中文 Javadoc 的折行交给 `./mvnw -q spotless:apply`，**不要手工调行宽**。
- **你是实现者，不要派子代理**（不派助手，更不派评审）。评审在控制器收到你的报告之后。
