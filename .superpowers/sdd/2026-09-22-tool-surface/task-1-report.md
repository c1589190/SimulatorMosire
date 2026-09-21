# Task 1 报告 — M1 地图组（7 条 map 窄写工具）

> 简报：`.superpowers/sdd/2026-09-22-tool-surface/task-1-brief.md`
> 证据：`.superpowers/sdd/2026-09-22-tool-surface/m1-evidence/`
> **本报告取代此前那份 BLOCKED 报告**（当时 `simos-sd` 因跨机 `~/.m2` 缺口编译失败；已由控制器在 `f04a24d` 修好）。
> ★ 报告里所有行号都是**交付字节**上的行号（本轮实测），不是简报里的提示行号。

---

## 1. 状态

**DONE_WITH_CONCERNS**

判据 §6 五条**全部有实测值且为绿**；变异 6 体 6 杀 0 存活。两条 concern 见 §7：
① 简报 §8 对 m1 的期望（(a)/(b)/(c) 三处全红）**实测只红了 (a)**——这是**结构性的**，不是没修好；
② 报内有两处护栏**没有可独立杀它的变异体**（如实登记，未粉饰）。

---

## 2. 提交

| 提交 | 内容 |
|---|---|
| **`2c8e29c`** | 实现 + 证据：7 个新工具类、`SimosToolSource.java`、3 个测试文件、`m1-evidence/**`（54 files changed, +22518/−22） |
| `HEAD`（本报告所在提交） | 本报告 + token 判据脚本 `check-token-identical.py` |

分支 `ts/m1`。**未 `git add -A`**：显式列路径暂存，`git diff --cached --stat` 逐条扫过（见 §6）。
工作区里**只剩控制器自己那份 `progress.md` 未暂存**——那是控制器的台账改动，我**没有碰它、也没有提交它**。

---

## 3. 一行测试小结

`./mvnw clean verify` **第 2 次尝试 rc=0**、**8/8 `SUCCESS [`**、**1414** = `170/369/45/259/179/141/251`、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、`[frontend-gate] OK tests=204 pass=204 fail=0`、总耗时 **09:18 min**；
变异 **6 体 6 杀 0 存活**。

---

## 4. 判据 §6 逐条实测值

### 判据 1 —— 桶正确

| 核什么 | 实测 |
|---|---|
| 7 条在 **GM** 桶 | `SimosToolsTest.roleBucketsNeverCarryGenericWrite:356-360`：GM `doesNotContain("simos.command.submit")` + `containsAll(MAP_WRITE_NAMES)` ⇒ **绿** |
| 7 条在 **EXTERNAL_WITH_GM** 复合口 | 同行 `:372-383`：`.containsAll(MAP_WRITE_NAMES).hasSize(23)` ⇒ **绿**（现有口工具数实测 == 23） |
| 7 条**不在** `EXTERNAL` 桶 | 同行 `:366-371` `.doesNotContainAnyElementsOf(MAP_WRITE_NAMES)` ⇒ 绿（★ 判别力说明见 §7-②） |
| 7 条**不在** `DECISION_AGENT` 桶 | 同行 `:361-365` `.doesNotContainAnyElementsOf(MAP_WRITE_NAMES)` ⇒ 绿（同上） |
| 桶错位**真的会被抓** | 变异 m1（把 `map.SetTerrain` 从 `addGmWrites` 挪到 `addExternalWrites`）⇒ **KILLED**，红点 `SimosToolsTest.java:360`（"M1 判据 1：GM 桶无通用写，且含 7 条 map 窄写"）与 `:302` |
| 注册面恰好 = 23 | `SimosToolsTest.registryContainsExactlyTheExternalUnionGmTools:273` + `McpServerTest.…ExposeExactlyTheExternalUnionGmTools:183`（**真 MCP SDK 客户端走真 socket** 的 `tools/list`）⇒ 绿 |

### 判据 2 —— 名字同源

- `SimosToolsTest.mapNarrowWriteToolsAreNamedAfterTheirFixedCommandType:297-299`：
  `toolNames(mapTools).containsExactlyElementsOf(MAP_WRITE_NAMES)` ⇒ 7 条 `name()` 与各自钉死的命令类型**逐条相等**（不是"含某些"）。
- 同用例 `:300-302`：同一批名字在 **GM 桶里按名可寻** ⇒ 绿。
- 同用例 `:304-307`：7 个类型**都在 `catalog` 里**（`simos.command.catalog` 真返回的 `types`）⇒ 绿。
- 变异 **m2b**（`MapSetTerrainTool.commandType()` 改返回 `unit.RenameUnit`）⇒ **KILLED**，红点含
  `SimosToolsTest.java:299`（"7 条 map 窄工具的 name() == 它们各自钉死的命令类型"）。
- 另有两条同源强判据仍绿：`catalogListsExactlyTheRegisteredCommandTypes:311`（catalog == 冻结类型表）与
  `catalogCoversEveryCommandHandlerImplementation:331`（**catalog == 全仓 43 个 handler 的 `type()` 集合**，扫描非空自证 `hasSize(43)`）。

### 判据 3 —— 敏感写

`SimosToolsTest.writesAreSensitiveAndAskWithTheToolNameAsClassKey:463-482`，**逐条**核 `covered` 集合里每条工具：
`spec().sensitive() == true`（`:474`）、`gate()` 是 `ToolGate.Ask`（`:477`）、`((Ask) gate).classKey() == name`（`:478`）、`((Ask) gate).kind() == AskKind.SENSITIVE`（`:479-481`）⇒ 全绿。
变异 **m4**（基类 `AskKind.SENSITIVE` → `MODE_LIMITED`）⇒ **KILLED**，红点 `SimosToolsTest.java:481`。

### 判据 4 —— 前置即错（本任务核心判据）：**7 条各一个用例**

共同断言 `assertDomainRejectedAndHeadUnchanged:667-680`：`success()==false` + `code()=="REJECTED"` + **`reason` 含域层文案关键片段** + **head 不变**。

| 用例 | 坏载荷 | 断的域层文案片段 | 实测 |
|---|---|---|---|
| `mapSetTerrainToolSurfacesTheDomainRejectionForAnUnknownTerrain:587` | `terrain:"not_a_terrain"` | `未知地形类型` | 绿 |
| `mapSetEdgeToolSurfacesTheDomainRejectionForAnUnregisteredKind:595` | `kind:"not_registered"` | `未知连通性类型` | 绿 |
| `mapCreateRegionToolSurfacesTheDomainRejectionForADuplicateId:607` | 同一 `regionId` 建两次（第一次经**同一条窄工具**真建成、head 1→2） | `区域已存在` | 绿 |
| `mapUpdateRegionToolSurfacesTheDomainRejectionWhenNeitherHexesNorMetaIsGiven:619` | 只给 `regionId` | `map.UpdateRegion 必须至少给 hexes 与 meta 之一` | 绿 |
| `mapDeleteRegionToolSurfacesTheDomainRejectionForAnUnknownRegion:628` | 不存在的 `regionId` | `区域不存在` | 绿 |
| `mapRandomizeRegionToolSurfacesTheDomainRejectionForAnEmptySelection:634` | `hexes:[]` | `hexes 不得为空：一条 map.RandomizeRegion 至少要选一格` | 绿 |
| `mapRegisterPathwayGroupToolSurfacesTheDomainRejectionForAMalformedColor:642` | `color:"red"` | `color 必须是 #RRGGBB 形式` | 绿 |

**判别力**：变异 **m5**（把 `ToolSupport.fold` 的域层理由换成通用文案 `{"reason":"载荷非法"}`）⇒ **KILLED**，
**7 条用例各自红**，各在自己那一行：`:588`、`:596`、`:615`、`:621`、`:629`、`:635`、`:643`（共同经助手 `:676`）。
⇒ "每条各一个用例"这个设计**实测有回报**：写成循环断 7 次的话，这一轮只会红一次。

### 判据 5 —— 修掉既有闸门缺陷（索引切片）

- 缺陷本体：原写闸用 `EXTERNAL_UNION_GM_TOOL_NAMES.subList(9, 16)`。名单 16→23 后**切片仍然合法** ⇒ 新增 7 条**完全不被写闸覆盖、零症状**。
- 修法实测（`SimosToolsTest.java:396-401`）：覆盖集改为**从真工具面派生** ——
  `shell.toolsFor(Role.EXTERNAL_WITH_GM)` 的名字 **减掉 `READ_TOOL_NAMES`**；
  断言语义 `:465-467` = **「写闸覆盖集 == 写工具全集」**（`containsExactlyInAnyOrderElementsOf(WRITE_TOOL_NAMES)`）。
- 另有完整性判据 `:468-471`：读名单 ∩ 写名单 = ∅，且 **读名单 ∪ 写名单 == 现有口全名单**。
- 变异 **m3**（把派生式覆盖集退回 `subList(9, 16)`）⇒ **KILLED**，**唯一红点** `SimosToolsTest.java:467`
  （"★ 写闸覆盖集 == 写工具全集（现有口 ∖ 读名单）：退回索引切片会让新增的写工具静默逃出写闸"）。
  ⇒ 这次修复**不是装饰**，且它只有一个承重红点，与修复意图**逐字对应**。

---

## 5. 变异：6 体 6 杀 0 存活

装置 `m1-evidence/mutants/mut-round.sh`（每轮：① 还原干净世界并逐文件比 md5 ② 按白名单推成**目标类名**、断言推送后 md5 == 变异体 md5 ③ 跑聚焦用例 ④ **强制断言 `[ERROR] COMPILATION ERROR` 计数为 0** ⑤ 只读**本轮 mtime** 的报告 ⑥ 还原并自记）。
六轮**全部**在**最终字节**（`spotless:apply` 之后）上重跑，`pristine/` 与 6 个变异体都从最终字节重新冻结/生成。

| 变异体 | 变异内容 | 判定 | 红点（各轮日志里的实测行） | 自记关键项 |
|---|---|---|---|---|
| **m1** | `SimosToolSource`：`MapSetTerrainTool` 从 `addGmWrites` 挪到 `addExternalWrites` | **KILLED** | `SimosToolsTest:360`、`:302`（**只有 SimosToolsTest 红**，见 §7-①） | `comp=0`、`failures=2`、`fresh=1`、`rc=1`、还原 md5 == 原件 |
| **m2** | `MapSetTerrainTool.commandType()` → `map.DeleteRegion`（与既有**工具名**撞名） | **KILLED**（★ 死在**更早一层**） | 注册表 fail-fast：`IllegalArgumentException: 工具名重复: map.DeleteRegion`，27 errors；判据 2 的断言**以 `<<< ERROR!` 出现、没跑完** | `comp=0`、`errors=27`、`fresh=1`、`rc=1` |
| **m2b** | 同上但改返回 `unit.RenameUnit`（**不撞名**，只有按名判据能杀） | **KILLED** | `SimosToolsTest:299`（判据 2 本身）、`:273`、`:360`、`:467`、`McpServerTest:183`、`:588 » NoSuchElement` | `comp=0`、`failures=5`、`errors=1`、`fresh=1`、`rc=1` |
| **m3** ★ | `SimosToolsTest`：退回 `subList(9, 16)` | **KILLED** | `SimosToolsTest:467`（唯一红点） | `comp=0`、`failures=1`、`fresh=1`、`rc=1` |
| **m4** | `AbstractNarrowWriteTool`：`AskKind.SENSITIVE` → `MODE_LIMITED` | **KILLED** | `SimosToolsTest:481`（判据 3） | `comp=0`、`failures=1`、`fresh=1`、`rc=1` |
| **m5** | `AbstractNarrowWriteTool`：拒绝理由换通用文案 | **KILLED** | 判据 4 的 **7 条各红**：`:588/:596/:615/:621/:629/:635/:643` | `comp=0`、`failures=7`、`fresh=1`、`rc=1` |

**装置纪律落实项**：6 轮 `compilation_error_count` 全 **0**；`reports_fresh` 全 **1**（只认本轮 mtime，不拿陈旧报告当结论）；
`post_restore_md5` 全 == 原件 md5（`d6c06e80…`/`c74d374f…`/`db14c720…`/`65472a0d…`）；每轮日志末尾自带"装置自记"段（本轮跑的是哪份字节）。

**作废轮（留档不删，如实登记）**：

- `logs/m3.log` 轮 1 → **VOID**：`compilation_error_count=1`，**但 Maven 从未报过编译错误** —— 我的装置自己那行 echo `--- ④ COMPILATION ERROR 强制断言 ---` 命中了裸模式。⇒ 改用 Maven 原生前缀 `[ERROR] COMPILATION ERROR` 后复核（`grep` 证实只有我那两行 533/535 命中）。
- `logs/m3round2.log` 轮 2 → **VOID**：`verdict=VOID(compilation-error)` **未加引号** ⇒ bash 在 ④⑤ 之后、⑥ 之前中断，**变异体留在工作树里**。⇒ 手工 md5 校验还原 + 加 `bash -n` 预检 + 给判定串加引号，重跑为轮 3（`logs/m3-round3.log`，KILLED）。
- `logs/m9.log`：装置 dry run（19 B）。

---

## 6. 门禁与提交纪律

- **第 1 次尝试 `clean-verify.attempt1.log`**：rc=1、**前 7 个模块全 SUCCESS、`SimosApp` FAILURE**，红因是 `spotless:check` 判 **7 个新工具类的中文 Javadoc 折行**不合格式（google-java-format 按字符数折，手工断行处留接缝）。
  ⇒ 把**原始字节归档**到 `mutants/preformat/`（含 `renamed-hyph/` 6 个另 6 条 map 工具）后 `spotless:apply`，再跑第 2 次尝试 → **rc=0**。
- **spotless 只改了注释与折行**（与 §7-③ 那条被我否掉的判据有关）：token 级复核 `mutants/check-token-identical.py`（**双侧自证**：① 自比必"同" ② 已知变异体 m2 必判"异"）⇒ 12 个文件**全部 `code=同`**，字符串字面量**逐条同**（如 `SimosToolsTest` 349 条、`McpServerTest` 111 条）⇒ **代码面 token 级零改动**，变异轮与门禁证据对交付字节仍成立。
- **交付字节 == 门禁所跑字节 == 变异轮所跑字节**：提交前实测四个被冻结文件的 md5 与 `pristine/orig_md5.txt` **逐字节相同**（`d6c06e80…`/`c74d374f…`/`db14c720…`/`65472a0d…`）。
- **提交前扫描**：`git diff --cached --stat` 逐条过；代码面 diff 的密钥形态扫描（`api_key|secret|password|bearer|sk-…|BEGIN … PRIVATE KEY`）**命中 0**；新代码**不含** `"SqliteStore"`/`"Timeline"`/`"CheckpointStore"` 三个字面量（简报 §7(d) 的 `AppWritePathGuardTest` 口径，命中 0）。
- **基线现场重算**：`clean-verify.attempt2.log` 里按"模块汇总行"取 `Tests run:` 相加 —— **7 个模块聚合行、合计 1414**（另测：含逐类行的全部 `Tests run:` 求和 == 2828 == 2×1414，与"每模块聚合行复述其逐类行"一致）。
  **基线 1406 = `170/369/45/259/179/141/243`** 是**本机改动前**实测（`logs/baseline-local-clean-verify.log`，`baseline-local-rc.txt` = `rc=0`）。
  **delta 干净**：只有 `simos-app` **243 → 251（+8）**，其余 6 个模块逐值不变；+8 = `SimosToolsTest` **15 → 23**（1 条桶/名字用例 + 7 条判据 4 用例），另两个测试文件 `@Test` 数 **4→4** 未变（只长了名单常量）。

---

## 7. concerns 与「我未能核实的」

### ① 简报 §8 对 m1 的期望**实测不成立**（结构性，非实现缺陷）

简报写"m1 桶错位 ⇒ 期望 **(a)/(b)/(c) 红**"。实测：

| 处 | 期望 | 实测 |
|---|---|---|
| (a) `SimosToolsTest` | 红 | ✅ 红（`:360`/`:302`） |
| (b) `McpServerTest` | 红 | ❌ **绿**（本轮 4 条、0 失败、`fresh=1`） |
| (c) `McpPortTopologyTest` | 红 | ❌ **绿**（本轮 4 条、0 失败、`fresh=1`） |

**原因**：`Role.EXTERNAL_WITH_GM` 的定义就是 `addExternalWrites(...) + addGmWrites(...)` —— 把一条工具**在两个桶之间搬家**，
**并集逐字不变**。而 (b)(c) 两处断的都是**并集**（`McpServerTest:183`、`McpPortTopologyTest:113` 都是并集的精确匹配）
⇒ 并集没变它们就不可能红。**(b)(c) 答的是"并集对不对"，只有 (a) 的逐桶断言答的是"分桶对不对"。**
⇒ 这不是 m1 没杀到，而是**期望写错了**：能看见桶错位的判据**只存在于逐桶断言**里。已实测记录，未粉饰。

### ② 两处护栏**没有可独立杀它的变异体**（如实登记，未为其造红）

1. `SimosToolsTest:365/:371` 的两条否定断言（7 条 map 窄写**不在**决策桶/外部桶）：**任何**能让它红的变异
   （把某条 map 工具放进 EXTERNAL 桶）都会在**同一方法更早的 `:360`**（`GM.containsAll(MAP_WRITE_NAMES)`）上先红，
   AssertJ 在方法内首个失败即止 ⇒ **它无法被独立杀死**。m1 那一轮正是如此（红在 `:360`，`:365` 从未执行）。
2. 决策口的精确匹配（`McpPortTopologyTest:129-130` `containsExactlyInAnyOrderElementsOf`）与
   `.doesNotContainAnyElementsOf(MAP_WRITE_NAMES)`（`:135`）**兜得住**"map 工具漏进决策口"，但**M1 未为它造变异体**
   ——简报 §7(c) 只要求"跑一遍确认"，我跑过：m1 轮里 `McpPortTopologyTest` 4 条**全绿**。

### ③ 我**否掉了自己写的**一条判据（不是"没测"，是"判据无效"）

`check-format-only-comments.sh` 按"行首是否注释"分类改动行 ⇒ 它把 `spotless` 的**折行**记成"非注释改动"：
`SimosToolsTest` 报 14 行"非注释"（实测逐条列出，全是 `assertThat(...).as(...)` 收成一行、字面量上移、`throws Exception` 下移这类**同 token 换排版**），
并据此打印"必须重跑"。**该结论是过严的假警报**——行级判据判不出折行。
（更早我还试过"去空白后比对"，那份**无效**：重新折行会重排 `*` 注释标记，去空白后**永远不等**。已弃用。）
⇒ 真正的复核是 §6 的 `check-token-identical.py`（token 级 + 字面量级，双侧自证）——结论：**零改动，证据不作废**。

### ④ 「我未能核实的」（必填）

1. **判据 4 的调用层不是 MCP 传输层**：7 条坏载荷用例走的是 `call():764-767` = `toolRegistry().find(name).execute(context(tool, args))`，`ToolContext` 用 `AccessToken.SYSTEM` ⇒ **直调工具对象、不经过 MCP socket、也不经过审批门链**。
   判据 4 的命题（"域层理由**到达调用方**"）在这个层上已实测成立；**但**"7 条新窄工具经真 MCP 传输 + 审批链的端到端调用"**未验**
   —— `McpServerTest` 只对 `simos.command.submit` 做了真传输 + 审批往返（`writeToolBlocksOnApprovalThenCommitsWithConfiguredInitiator`），对 7 条新工具只做了 `tools/list`。
2. **判据 3 是"工具对象层的 gate/spec"**，不是"真调一次、审批真的弹出"。审批链装配本身由既有用例（`McpServerTest`/`ShellApprovalTest`）覆盖，**新 7 条各自的审批往返未验**（同 ④-1）。
3. **m2 的杀点不在判据 2 上**：m2 让 app 在 `Shell.start` 就 fail-fast（工具名重复），判据 2 的断言以 `<<< ERROR!` 出现、**没跑到**。
   判据 2 的真牙齿由 **m2b** 提供（红在 `:299`）。⇒ 引用"判据 2 有判别力"时**引 m2b，不引 m2**。
4. **`map.UpdateRegion` 的 `meta` 整体替换语义未验**：简报 §3 的 `description()` 按"整体替换、只给一个键会静默清掉其余三个"写了，但这是**既有域层行为**，简报 §5 明示不在 M1 修 ⇒ 我**没有**为它造用例（既没验它会发生，也没验它不会）。
5. **简报 §5 列的 7 处域层静默缺口逐条未验**（通路组 id 大小写变体 / SetEdge 无邻接校验 / meta 整体替换 / 未知字段静默忽略 / 空操作仍落 revision / `PropertyDef.type` 不白名单 / CreateRegion 无 name 唯一性）——控制器已裁"不在 M1 修"，我按裁定**一处未动**，故也无实测值。
6. **变异体的"红"只在聚焦用例集上判**：每轮跑的是 `-Dtest=<该变异体相关类>`，**不是**全量 ⇒ 我**未**核"这 6 个变异体在全量 `clean verify` 下的表现"（也不该核：装置按简报 §8 的聚焦形态设计）。
7. **前端门禁 `tests=204` 与本任务无关**：本轮零前端改动，204 是**未改动的复核**，不得读成"我验了前端"。
8. **`nproc=2` 与超时纪律**：本轮全量门禁**只有一次成功尝试**（第 2 次，09:18 min < 600 s，前台跑完、未被杀）。我**没有**复跑第三次，故"该绿轮可复现"**未验**。
9. **行号会漂移**：简报的行号提示（如 `SimosToolsTest` 的在 487 之类）与交付字节不符；本报告与判据引用**一律用交付字节实测行号**（本轮 grep 实测）。
10. **`agentlib-mosire` 是外部依赖、不在本仓** ⇒ 审批/传输侧的实现**未能核实**（与 unit-ext T9/T10、sd 阶段同一条盲区）。

---

## 8. 交付物清单

**生产代码（`simos-app/src/main/java/io/mosire/simos/app/`）**

- `tools/SimosToolSource.java`（+7 import，`addGmWrites` 追加 7 条）
- `tools/write/MapSetTerrainTool.java`、`MapSetEdgeTool.java`、`MapCreateRegionTool.java`、`MapUpdateRegionTool.java`、`MapDeleteRegionTool.java`、`MapRandomizeRegionTool.java`、`MapRegisterPathwayGroupTool.java`（7 个新类；`summary()` 各用自己的中文动作短语；`description()` 逐字照简报 §3 表）

**测试（3 个文件）**

- `tools/SimosToolsTest.java`：`READ_TOOL_NAMES`/`WRITE_TOOL_NAMES`/`MAP_WRITE_NAMES` 三名单；`writeFaceCoveredByTheWriteGate()` 派生式写闸；判据 1/2/3 断言；7 条判据 4 用例 + `callNarrowWrite`/`assertDomainRejectedAndHeadUnchanged` 助手；过期注释里的 16/9/4 一并改对。
- `McpServerTest.java`、`McpPortTopologyTest.java`：名单常量 16→23、`GM_NARROW_WRITES` 4→11。

**证据（`m1-evidence/`）**

- `logs/`：`baseline-local-clean-verify.log`（本机基线）、`clean-verify.attempt1.log`（Spotless 红）、`clean-verify.attempt2.log`（绿，rc=0）、`baseline-notes.md`
- `mutants/`：`mut-round.sh`（变异装置）、`regen-mutants.sh`（从最终字节重生成）、`check-format-only-comments.sh`（行级，已判定**过严**）、`check-token-identical.py`（**token 级**，双侧自证）、`pristine/` + `preformat/` + `whitelist/m1,m2,m2b,m3,m4,m5/` + `logs/`（每轮含"装置自记"段）

**未动**：`simos-map`（零领域改动）、`CatalogTool.PAYLOAD_HINTS`、`McpCoverageTest`（简报 §7(e)：对本任务是空操作）、控制器的 `progress.md`。
