# T5 决策人查询面（后端只读）—— 报告

> 分支 `wsf/t5`（worktree `.claude/worktrees/wsf-t5`），基线 = **当前 HEAD `ecf9c81`**（T1~T4 已合并）。
> 范围**只做 T5**；未碰 spec / 计划正文；未做 T6+。
> 证据目录 `t5-evidence/`（logs/ + mutants/ + 本报告）。

---

## 〇 结论摘要

- **新增只读查询面**：`GET /api/sd/decision-makers`（可与 `?affiliation=nation:<id>|army:<id>` 过滤）+
  `GET /api/sd/decision-makers/{id}`。数据来自 sd 模块自己的切片（`SdSnapshot.state()` 的公共 record 访问器），**不新增 sd 字段**（D13）。
- **落点 = GUI 端点；不加读工具**（理由见 §一，代价核算）。
- **门禁**：`./mvnw clean verify` **rc=0**（实现轮 **第 1 次尝试** SUCCESS；变异轮后复跑 **第 1 次尝试** SUCCESS）；
  **8/8 `SUCCESS [`**；**1308** = `170/368/45/259/178/124/164`（现场重算）；`BugInstance size is 0` ×7；`[ERROR]` 0；
  前端 **114/114**（未改 JS，与基线同值）。
- **delta 干净**：基线 **1295** = `…/151` ⇒ 终态 **1308** = `…/164`，**只有 app 151→164 = +13**（= 新测试类 13 条），其余逐字不变。
- **变异 5 轮 5 KILLED / 0 存活**（覆盖"过滤写错""详情缺字段""空库当错误""fail-closed 改成空集合""路由整段消失"五个方向）。

---

## 一 落点选择：为什么 GUI 端点，不加读工具

计划 §T5 与 spec §六.1 把"读工具"列为**可选**，并要求"**先核实再动**"。核实结果：

- 读工具是 `SimosToolSource.readTools(...)` 的 9 条，**三个桶共享**（`EXTERNAL`/`GM`/`DECISION_AGENT`）——加一条 = 加进每一个运行时口。
- 现行**冻结的逐条工具面断言**至少三处会被牵动：
  - `SimosToolsTest.EXTERNAL_UNION_GM_TOOL_NAMES`（15 条：`subList(0,9)` + `subList(9,15)` + `hasSize(15)`，且类注写"前 9 条读"）；
  - `McpPortTopologyTest.READ_TOOLS`（9 条，与 `GENERIC_WRITES`/`GM_NARROW_WRITES`/`DECISION_AGENT_WRITES` 做
    `containsExactlyInAnyOrderElementsOf`）；
  - （连带）`SimosToolSource` 类注与工具数注释。
- **而没有任何 T5~T10 的消费者需要这条读工具**：T6（redaction）/ T7（决策模式 UI）/ T9（待决信号）/ T10（开始决策入口）全部经
  **GUI `/api/*` 或 `Shell` 内部**读写，不经 MCP 读工具。

⇒ **选 GUI 端点**（零工具面牵动、零既有判据改动），读工具**不做**。这是"核清代价再选落点"的结果，不是省事。
（若日后确有 MCP 侧需要，加读工具的代价已在此量化：3 处冻结清单 + 类注，属另一个任务的范围。）

---

## 二 实现清单（3 个生产文件 + 1 个新测试）

| 文件 | 改动 |
|---|---|
| `simos-app/.../app/query/SdQueryService.java` | **新增**：`AffiliationFilter`（`All`/`OfNation`/`OfArmy`）、`parseFilter`（**fail-closed**）、`listDecisionMakers`（筛 + id 字典序）、`decisionMaker`（`Optional`）、`DecisionMakerInfo` 投影 |
| `simos-app/.../app/gui/ApiViews.java` | 新增 `decisionMakers(...)` / `decisionMaker(...)` / `viewScope(...)` / `sorted(...)`（集合字段**字典序**出口） |
| `simos-app/.../app/gui/GuiServer.java` | 字段 `sdQueryService`（构造器内建，**不改构造签名 ⇒ 不动 Shell**）、`GET_ROUTES` 加 `/api/sd/decision-makers`、`handleGet` 两条分支、`isDecisionMakerDetail` + `allowedMethod`、两个 reply 方法 |
| `simos-app/src/test/.../gui/SdDecisionMakerApiTest.java` | **新增** 13 条（真 `Shell` + 真 HTTP；夹具走**真命令路径**） |

**关键设计**：
- **铁律 3**：`SdQueryService` 只经 `state.module("sd")` → `SdSnapshot` → `SdState` 的**公共 record 访问器**
  （`decisionMakers()`/`armies()`/`nations()`）取数，不反射内部结构。**未在 sd 模块新增访问器**（现有的是公共 API，够用）。
- **D13**：不新增 sd 字段；视图字段全部由现有数据派生。
- **fail-closed**：过滤串未知 kind / 缺冒号 / 空 id ⇒ `IllegalArgumentException` ⇒ GUI **400**；单个详情查不到 ⇒ **404**；
  **合法 kind + 不存在 id ⇒ 200 空列表**（这是"确实没有决策人"，与"查询坏掉"是**不同**结果）。
- **`due` 占位**：T5 恒 `null`（**不填 `false`**——`false` 是"未到周期"的断言，T5 不计算它；D7 公式归 T9）。
- **确定性**：列表按 id 字典序；`allowedTools`/`redactedFields` 出口前**字典序排序**（Set 迭代序不是内容的纯函数，M2 Task 5 教训）。

---

## 三 门禁（现场重算，只取模块汇总行）

命令：`./mvnw clean verify`（worktree 前台、独占）。

| 项 | 基线（HEAD `ecf9c81`） | 终态（T5） |
|---|---|---|
| rc | 0 | 0（实现轮第 **1** 次尝试；变异轮后复跑第 **1** 次尝试） |
| 模块 `SUCCESS [` | 8/8 | 8/8 |
| `UtilSimos` | 170 | 170 |
| `MapSimos` | 368 | 368 |
| `SocialSimos` | 45 | 45 |
| `UnitSimos` | 259 | 259 |
| `CoreSimos` | 178 | 178 |
| `SDSimos` | 124 | 124 |
| `SimosApp` | 151 | **164**（+13） |
| 用例总数 | **1295** | **1308** |
| `BugInstance size is 0` | ×7 | ×7 |
| `[ERROR]` | 0 | 0 |
| 前端 | `tests=114 pass=114 fail=0` | 同（未改 JS） |

证据：`logs/baseline-clean-verify.log` / `logs/baseline-rc.txt` / `logs/module-summary-lines.txt`；
`logs/clean-verify.log` / `logs/clean-verify-rc.txt` / `logs/module-summary-lines-final.txt`；
`logs/clean-verify-after-mutants.log` / `logs/clean-verify-after-mutants-rc.txt`。

---

## 四 查询面逐条实测（真 HTTP 响应原文）

> 探针跑完即删（源备份与产物见 `logs/probe-*.out`）；夹具：n1（甲国）+ a1（第一军，根单位 u-1）+ `dm-army`(cadence 5) +
> `dm-nation`(cadence 3, 配权 `PERCEPTION_ONLY` + `redactedFields:["position"]`)。

| 请求 | 状态 | 响应体（原文） |
|---|---|---|
| 空库 `GET /api/sd/decision-makers` | **200** | `{"decisionMakers":[]}` |
| `GET /api/sd/decision-makers` | **200** | `{"decisionMakers":[{dm-army…},{dm-nation…}]}`（**`dm-army` 在前**＝id 字典序） |
| `?affiliation=nation:n1` | **200** | `{"decisionMakers":[dm-nation]}`（**恰一条**） |
| `?affiliation=army:a1` | **200** | `{"decisionMakers":[dm-army]}`（**恰一条**） |
| `?affiliation=nation:no-such-nation`（合法，无匹配） | **200** | `{"decisionMakers":[]}` |
| `?affiliation=kingdom:n1`（未知 kind） | **400** | `{"error":"未知 affiliation kind（只认 nation / army）: kingdom"}` |
| `?affiliation=bogus`（缺冒号） | **400** | — |
| `?affiliation=nation:`（空 id） | **400** | — |
| `GET /api/sd/decision-makers/dm-army` | **200** | 见下 |
| `GET /api/sd/decision-makers/dm-nope` | **404** | `{"error":"decision maker not found","id":"dm-nope"}` |
| `POST /api/sd/decision-makers` | **405** + `Allow: GET` | — |
| 两次 `GET` 列表 | 200 | **逐字节相同** |

`dm-army` 详情原文：
```json
{"id":"dm-army","affiliation":{"kind":"army","id":"a1","displayName":"第一军","nationId":"n1","rootUnit":"u-1"},
 "allowedTools":["sd.IssueDirective","sd.SubmitVerdict"],"cadence":5,
 "viewScope":{"visibleRegions":0,"visibleHexes":0,"visibleUnits":0,"seeOwnUnits":false,
 "adjudicationDisclosure":"WITHHELD","redactedFields":[]},"due":null}
```
`dm-nation` 详情原文：
```json
{"id":"dm-nation","affiliation":{"kind":"nation","id":"n1","displayName":"甲国","nationId":"n1","rootUnit":null},
 "allowedTools":["sd.SubmitVerdict"],"cadence":3,
 "viewScope":{"visibleRegions":1,"visibleHexes":1,"visibleUnits":1,"seeOwnUnits":true,
 "adjudicationDisclosure":"PERCEPTION_ONLY","redactedFields":["position"]},"due":null}
```

★ **探针当场抓到的一处**：未排序时 `dm-army` 的 `allowedTools` 回的是 `["sd.SubmitVerdict","sd.IssueDirective"]`
（与载荷插入序**相反**，说明状态里的 `Set<String>` 迭代序不是内容的纯函数）⇒ 已改为**出口字典序排序**
（`["sd.IssueDirective","sd.SubmitVerdict"]`）。这是 T5 的唯一一处"实现期就地校正"。

---

## 五 判据逐条（计划 §T5）

| 判据 | 结果 | 证据 |
|---|---|---|
| 空库 ⇒ `200 {"decisionMakers":[]}`（非 404/500） | ✅ | `emptyLibraryGivesEmptyListNotAnError` + 探针 |
| `?affiliation=nation:<id>` ⇒ 恰该国决策人（逐值） | ✅ | `filterByNationReturnsOnlyThatNationsDecisionMakers`（size=1） |
| `?affiliation=army:<id>` ⇒ 恰该军决策人（逐值） | ✅ | `filterByArmyReturnsOnlyThatArmysDecisionMakers` |
| 详情字段与 `SdState` 逐值一致（C13 后端半边） | ✅ | `detailMatchesReplayedSdStateFieldByField`（对重放出的 `SdState`） |
| 端点**只读**（`AppWritePathGuardTest` 不新增写路径） | ✅ | 新代码仅经 `QueryService`；全量门禁含 `AppWritePathGuardTest` 绿 |
| **fail-closed**：坏过滤串显式失败（≠ 空集合） | ✅ | `filterWithUnknownKind…` / `filterWithoutColon…` / `filterWithEmptyId…` 三条 400 |
| 显示名（国家/军队）逐值 | ✅ | 探针 `甲国` / `第一军`；`listReturnsAll…` |
| `viewScope` 摘要逐值 | ✅ | `viewScopeIsSummarizedFromStoredScope` |
| 列表确定性（两次逐字节相同） | ✅ | `twoListCallsAreByteIdentical` |
| 方法面（列表端点只 GET） | ✅ | `listEndpointOnlyAllowsGet`（405 + `Allow: GET`） |

**未新增/改动任何既有断言，未调低下界**（前端门禁两处下界未动——本任务无 JS 测试）。

---

## 六 变异轮（5 轮 5 KILLED / 0 存活）

装置：`mutants/java-round.sh`（九道门禁：干净世界 / 变异体字节不同 / 推规范名 / 清陈旧 `.class`+reports /
`COMPILATION ERROR=0` 且 `Tests run≥1` / report mtime 落本轮 / 红点落被保护断言 / `cp` 逐字节还原 / 日志自指）。

| 轮 | 靶子 | 变异（语义） | 红点（被保护断言） | 判定 |
|---|---|---|---|---|
| **t5m1** | `SdQueryService.matches` 的调用点 | 列表**不按 affiliation 筛**（返回全部） | `filterByNation…` / `filterByArmy…` | **KILLED** |
| **t5m2** | `ApiViews.decisionMaker` | 详情**缺 `cadence` 字段** | `detailMatchesReplayedSdStateFieldByField` | **KILLED** |
| **t5m3** | `SdQueryService.listDecisionMakers` | 空库**抛异常**（500） | `emptyLibraryGivesEmptyListNotAnError` | **KILLED** |
| **t5m4** | `SdQueryService.parseFilter` 的 `default` | 未知 kind 不再抛、改成一个**匹配不到任何东西**的过滤器 ⇒ **空集合冒充** | `filterWithUnknownKindIsRejectedNotSilentlyEmpty` | **KILLED** |
| **t5m5** | `GuiServer` 列表路由 | 路由整段去掉 ⇒ 404 | `emptyLibraryGivesEmptyListNotAnError`（+8 条连带） | **KILLED** |

★ **裁定 42 重放**：t5m2 的靶子 `ApiViews.java` 在首轮变异后**被改过**（加排序）⇒ **只重跑 m2**（新 `orig_md5=4722545…`，
KILLED，还原后 md5 与源码逐字节相同）。m1/m3/m4/m5 的靶子 `SdQueryService.java`/`GuiServer.java` 字节**未变**
（md5 前后均 `3e892a6…`/`c2911da…`）⇒ 其轮次证据**不作废**。

★ 变异轮后复跑 `./mvnw clean verify` **rc=0、第 1 次尝试**（`logs/clean-verify-after-mutants.log`），
三份源文件 md5 与 `mutants/orig/` **逐字节相同**。

---

## 七 裁定与取代说明

1. **不加 MCP 读工具**（见 §一）：代价已量化，消费者不需要。属"核清代价再选落点"，非偷工。
2. **不加 `Shell` 接线**：`GuiServer` 构造器内自建 `SdQueryService`（仍用既有 `queryService`）⇒ **构造签名不变、`Shell` 零改动**。
   计划 §二.3 把 `Shell` 列为 T5"若接线"的可改项——本任务**未接线**，故不触发对 T4 变异轮的重跑。
3. **`due` 恒 `null` 占位**：T9 按 D7 公式填 `true|false`。**T9 改这里不算改既有断言**（T5 没有断言 `due` 的值，只断言它存在且为 `null`）。
4. **集合字段出口排序**：`allowedTools`/`redactedFields` 按字典序出口（确定性），是**视图层**的排序，不改状态语义。
5. **归属目标解析不出显示名时字段为 `null`**（显式未知，不编造）：当前命令路径下不会出现（`sd.CreateDecisionMaker` 拒绝不存在的目标）。
6. **`Army.nationId`/`rootUnit` 透出**：军队决策人详情带 `nationId` 与 `rootUnit`，供 T7 把"单位 ⇒ 决策人"接起来
   （"单位决策人"= `Affiliation.Army`，spec §四.3）。

---

## 八 我未能核实的

1. **跨 JVM 的字节决定论**：`allowedTools`/`redactedFields` 已排序，但**整份响应**的跨 JVM 逐字节相同**未实测**
   （`twoListCallsAreByteIdentical` 只在**同一 JVM** 内证；跨 JVM 哈希盐相关风险由排序消除，但未独立复现）。
2. **`SdPayloads.requireTextSet` 之外**是否还有别的 `Set` 字段会进本视图：当前视图只出口两处集合，均已排序；
   未来若新增集合字段需同样处理（无编译期护栏）。
3. **真档（`v17levant` / `test_integration`）上的查询面未验**：本任务全部实测跑在**合成夹具**（3 格 + 1 国 1 军 2 决策人）。
4. **`revisions`/`revision=` 目标参数**：端点复用了 `target(params)`（支持 `?branch=&revision=`），但**未逐值实测**指定历史 revision 的查询。
5. **`agentlib-mosire` 外部依赖**与本任务的查询面无关，未核。
6. **UI 侧未验**：T5 只交付后端只读面；T7 的界面消费未在本任务验证（不在范围）。
7. **`?affiliation=` 多值 / URL 编码 id** 未测（id 含 `:` 时按"第一个冒号"切分，语义未单独钉）。
