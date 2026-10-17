# 新增领域模块：机械清单与代价（2026-10-15 侧线调查，只读核过）

> 用途：`2026-10-15-d031-interface-first-scope-trace.md` 的附录。行号为当前工作区实测。
> 结论先行：**新增模块的机械代价与"模块个数"成正比** —— 见 §A2/§A4，这是 R2（模块数/命名）的核心输入。

## A. 要改/建什么

### A0 新模块目录（抄现成骨架）
- api 模块：抄 `simos-economy-api/pom.xml:1-50`（parent `:6-10`、坐标 `:12-14`、deps `:20-50`、enforcer `:52-97`）。api **无** Snapshot/Codec/ChangeSet（`:14` description 逐字「无 Snapshot、无存储、无经济公式」）。
- impl 模块：抄 `simos-ga-spi/pom.xml:1-93`；依赖块加 `impl → api` 一条（形如 `simos-ga/pom.xml:33-35`）；junit/assertj（`simos-ga-spi/pom.xml:32-37`）。
- ★ **impl-only 也是本仓既有形态**：`simos-gov` / `simos-army` / `simos-unit` 都没有 api 模块，读口由组合根直连（app 的 `ApiViews` 直接用 `UnitState`）。

### A1 根 `pom.xml`（3 处；边界规则**不在**根）
- `<modules>` 每模块一行（形如 `pom.xml:23`；api 排在 impl 前）。
- `<dependencyManagement>` 每条一个坐标块（形如 `pom.xml:126-128`）。
- 根 pom **只有** `enforce-java-and-maven`（`:263-278`）；**边界 enforcer 在每个模块自己的 pom 里** ⇒ 新增模块要自带 `bannedDependencies`。
- 文档面同步：根 `AGENTS.md` 边界表、`docs/agents/01-〇-铁律与模块边界.md:37-60` 全表、`pom.xml:14` 的 description。

### A2 ★★ 最大头：其余 **15 个模块**的 pom 禁列
- 规则（`docs/agents/01-〇-铁律与模块边界.md:80` 逐字）：「**禁列 = 全仓 simos 模块 − 自己 − 允许面，零例外**」⇒ 每新增 1 个模块 ⇒ 其余每个模块的 `<bannedDependencies><excludes>` 各补 1 条 `<exclude>` + `<message>` 文案同步；`<searchTransitive>false</searchTransitive>` 原样保留（`simos-ga-spi/pom.xml:55`）。
- 实测：`grep -l bannedDependencies simos-*/pom.xml` = **16 个**，其中 `simos-app/pom.xml` 只在 `:22-23` 注释里声明**不设** ⇒ **15 个 pom 要改**。
- **代价换算**：新增 **2 个**模块 ⇒ 约 **30 条 exclude**；新增 **4 个**（每领域 api+impl）⇒ 约 **60 条 exclude** + 4 条 module 行 + 4 条 dependencyManagement。
- 若某模块被写进允许面 ⇒ 它少补一条 exclude，**并**同时加 `<dependency>`（"声明即允许"）。

### A3 新模块自己的 enforcer
- api 样板 `simos-economy-api/pom.xml:63-89`（`searchTransitive` 在 `:65`）；impl 样板 `simos-ga-spi/pom.xml:51-84`；持状态的照 `simos-ga/pom.xml:92-127`。

### A4 ★ 测试侧清单（漏了**要么静默、要么当场红**）
| 位置 | 判据 | 漏了会怎样 |
|---|---|---|
| `simos-core/src/test/.../ArchitectureGuardsTest.java:25-42` | `MODULES` 清单 + `:117-119` `containsExactlyInAnyOrderElementsOf` | 多/少/拼错**当场红**；`:96-98` 明写"不进 MODULES 就**不会红、不会提示**" |
| `simos-util/src/test/.../verify/EconomyVocabularyGuardTest.java:53-69` | 同款清单（`:99` 判据） | 同上 |
| `simos-app/src/test/.../tools/SimosToolsTest.java:2356-2365` | `commandHandlerSourceRoots()`；类注 `:2352-2355` 逐字「不补就是"扫描器静默"（新模块的实现面没人守）」 | **静默** |
| `ArchitectureGuardsTest.java:68-90` | `changeSetHasExactlyNineMainSourceImplementors`（`:69`）+ 文件名 `containsExactly` 清单 | 有 ChangeSet 就**当场红** |
| `simos-app/src/test/.../ShellAssemblySmokeTest.java:164` | `MODULE_COUNT = 8`（断言 `:178-180`）；工具数 `GM_BUCKET_SIZE:159`/`DECISION_BUCKET_SIZE:162`（`:219-220`「增删工具须显式改本行」） | 有 codec/工具就**当场红** |
| `tools/gate.sh:94-99` GUARDS_TABLE | `claim_guard_sources:509-522` **只判"文件在位"**，无条数断言 | **静默**（新模块 RoundTripTest 可加一行，不加不红） |

## B. enforcer 写法（逐字要点）
- 允许面 = `<dependencies>` 显式声明 + ban `<message>` 正向文案（样板 `simos-economy-api/pom.xml:89`）。
- `searchTransitive`（`01-〇:74-80,84`）：enforcer 3.6.3 默认 **true** ⇒ 会查传递依赖；2026-10-12 闭合时 14 个模块各加 `<searchTransitive>false</searchTransitive>` 并补回 19 条禁列。★ 口径：「**只拦"直接声明"**，传递依赖不再拦（那正是开关的目的，不是放松）」。
- **零 `<includes>`**（`simos-economy/pom.xml:106` 逐字「★ I-F2 禁止加 `<includes>` 白名单绕过」）；`simos-core/pom.xml:146-168` 的 `<includes>` 是 test-scope 另一套机制。
- ★ **以 pom 为准**（`01-〇:43-44,52-53`）：文档允许列会漏/会陈旧 ⇒ 必须**逐 pom grep 核**。

## C. 模块骨架必备件（作用 + 现成例子）
**契约层（api）**：`package-info.java`（`simos-ga-spi/.../spi/package-info.java:1-37`）· 稳定 ID record（`TransferId.java:9-28`：compact ctor 空白即抛 + `toString` 返裸值 + `static parse`）· 只读/窄写 SPI（`ToolReadPort.java`/`ToolWritePort.java`）。
**持状态层（impl）**：State（`GovState.java:74`）· Snapshot（`GovSnapshot.java:18-36`，`namespace()` `:34-36`）· Codec（`GovCodec.java:64` `implements ModuleCodec, ModuleDiffer`；`namespace` `:135-137`、`decodeChangeSet` `:140`、`apply` `:181`、`diff` `:206`）· ChangeSet（`GovChangeSet.java:49-57`；`between` `:96`/`apply` `:110`/`applyTo` `:147`/`isEmpty` `:154`）· handler（`CreateNationHandler.java:48-55`，`type()` 返 `"gov.CreateNation"`）· Log 门面 + LogSource（`GovLog.java:47-69`、`GovLogSource.java:29`）· participant（`ArmyTimeParticipant.java:70-96`）· resolver（`ArmyResolver.java:56`，按需）· 归属 provider（`GovAffiliations.java:31-37`，按需）。
★ **namespace 两处同字面**（`GovSnapshot.java:13-16` + `GovCodec.java:35-37`）：「改一处必须同时改另一处（装配期那处**有牙**：写歪即抛）」。

## D. 组合根接线点（`simos-app`，gov 为例）
`pom.xml:80`（显式声明，`:52` 逐字「依赖传递不是契约」）· `Shell.java:600` codecs 列表 / `:612` 实例 / `:621-623` 注册循环 · `:640` handlers / `:975` / `:1079-1081` · `:1109` participants / `:1123-1126` · `:1174-1188` resolver · `ShellAssemblySmokeTest` 计数 · 工具进桶 `SimosToolSource.java:1596-1598` · 权限归属 `DecisionScopeFunctions.java:80-84`（类注 `:29`「加一个新模块 = 各域加一个 provider + 这里注册一行」）· 创世空片 `RichWorld.java:114-117` + `:143-147`、`SmallWorld.java:381`、`CorridorWorld.java:109`、`ThreePowersWorld.java:684`。

## E. 环与红线
- **不许依赖 `core`**（`01-〇:59`，`map → ga-spi → core → map(test)` 成环）；**永不依赖 `app`**（`01-〇:60`，Maven 建图期直接拒）。
- 组合根**不设 enforcer**（`01-〇:58`），新增模块别指望它被拦。
- 退役模块**不留 `<module>` 行**、只留禁列留痕（`pom.xml:38-39`）。
- ★ **未找到集中登记"模块清单"的其它可执行判据**；`tools/gate.sh` 里**没有**强制新模块登记 RoundTripTest 的断言（建议本批顺手补一条，属"能机器拦的别只写文档"）。

## F. ★★ 2026-10-15 控制方纠正（覆盖上文相关条目）

来源：`/home/cna/SimulatorMosire/docs/superpowers/reports/2026-10-15-d031-side-line-review-decisions.md` §3。

1. **`TimeParticipant` 按需添加**：`simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java:167` 起 **codec 与 participant 各自注册、不要求一一对应**（`simos-gov` 有持久状态而没有自己的 participant）⇒ **纯声明/纯持久模块不必造空推进器**。上文 §C 第 10 项只作"按需可加"读。
2. **模块清单 / enforcer / 往返守卫 / 扫描根按实际新增能力同步**；**不为满足计数造空工具或空推进器**；测试统一在测试阶段执行，**不许把"没跑"写成"通过"**。
3. **修改数量不决定架构**：不得以"要回改 15 个 pom"作为架构取舍理由；上文 §A2 的代价换算**只作工作量估计**。模块个数已定为 2（`simos-culture` / `simos-production`，暂不拆 api+impl）。
4. **两个无冲突领域可共享底层契约**；**契约包所在 Maven artifact 不自动等于状态所有者** ⇒ 设计必须列出「定义对象 / 状态 / 写者 / 读取接口 / 实例引用」的**实际流向**，不能只画模块名。上文 §A0 的"impl-only 先例"结论仍有效，但**依据改为"按实际依赖需要"，不是"省 POM 改动"**。
5. 允许面变更（经济 → 生产模块）须同步四处：约束设计书、POM/enforcer、边界表（根 `AGENTS.md` + `docs/agents/01-〇-铁律与模块边界.md`）、测试清单。
