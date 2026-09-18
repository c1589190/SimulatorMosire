# CLAUDE.md — SimulatorMosire 工作须知

> 本文件是**项目的常驻上下文**：任何会话、任何机器打开这个仓库，先读这里。
> 设计细节不在这里重复，只写"必须先知道的约束"和"该去哪读"。

## 这是什么

SimulatorMosire（简称 **simos**）是 GSimulator 的重构：把原本单一功能的地图推演工具，
解放为**可分模块生长**的模拟引擎。参考项目 `~/DevMosire/GSimulator`（**只作参考，不作依赖**）
与 `~/ProjectMosire/AgentLibMosire`（CoreSimos 整包依赖）。

## 五条铁律（不可协商）

1. **所有查询最终解析为稳定实体。** 地址是定位方式，ID 是身份。单位调动、区域改名，
   历史与 Info 都不断。
2. **所有修改最终表示为 `Command → ChangeSet → Revision`。** 不存在绕过该路径的写入口。
3. **所有领域模块只拥有自己的数据。** MapSimos 永远不知道 SocialSimos / UnitSimos 存在。
4. **Core 只负责组合与调度**，不重新实现领域逻辑。
5. **变更集从完整状态类型派生，且有往返不变式测试守卫。**
   `apply(changeSet, base)` 必须逐字段重建出 target。

> 铁律 5 的由来：GSimulator 的 `MapDiff` 是**手工对着 `MapData` 维护**的，`MapData` 加字段时
> 没人提醒要跟着加。四个字段漂移出去（`terrainBlocks`/`terrainTypes`/`pathwayGroups`/`edges`），
> 既无编译期也无测试期护栏，导致**对非 root 节点写连通性会静默丢失**。
> 这是本项目最贵的教训。

## 模块结构与依赖硬约束

```
UtilSimos  →  MapSimos  →  { SocialSimos, UnitSimos }  →  CoreSimos
```

| 模块 | artifactId | 允许依赖 |
|---|---|---|
| UtilSimos | `simos-util` | **仅** Jackson（databind + datatype-jdk8）+ SLF4J。不依赖 AgentLibMosire，不依赖任何 simos 模块，**不碰文件系统**。★ jdk8 模块是 2026-09-18 M4 Task 3 裁定的：`Optional` 在快照树里且处于**嵌套泛型位置**（`SegmentedSeries<Optional<…>>`、`SimosTimestamp.calendarLabel`），裸 databind 会把它写成 `{"present":…}` 并丢值。⇒ 本条描述的目标是 **spec §二 / §〇.3**；`simos-util/pom.xml` 只有 `bannedDependencies` **黑名单**（无 `includes`），加 Jackson 家族构件**不会**触发 enforcer |
| MapSimos | `simos-map` | `simos-util`。**永不** import social/unit/agentlib。**不做任何存储** |
| SocialSimos | `simos-social` | `simos-util` + `simos-map`。**不依赖 UnitSimos** |
| UnitSimos | `simos-unit` | `simos-util` + `simos-map`。**不依赖 SocialSimos** |
| CoreSimos | `simos-core` | **main scope 只有** `simos-util` + `agentlib-mosire`（+ 后续的 sqlite-jdbc / MCP SDK / 日志实现）。map/social/unit **退到 test scope** |

> ★ **CoreSimos 的 main scope 不依赖领域模块**（ADR-1，2026-09-18）。这是**铁律 4 的结构化**——
> Core 编译期看不见任何领域类型，**想重新实现领域逻辑也无从下手**。由 `simos-core` 自己的
> `bannedDependencies` 在构建期强制。具体模块的装配归 **app 层**（M5 的 GUI / MCP）。
> 连带后果：**命令跨边界是不透明载荷**（Core 只认信封的 `type` 字符串，不 `instanceof`、不 switch 类型），
> 见 ADR-1 §七。新增契约放 `io.mosire.simos.util.spi`，**既有契约原地不动**。
>
> 这些边界**由 `maven-enforcer-plugin` 的 `bannedDependencies` 在构建期强制**——越界 = 构建失败，
> 不是 code review 的事。

### 跨模块可见性走 Facet，不走反向依赖

"某个 hex 上有哪些单位"**不能**写成 `MapManager.getUnitsAt(hex)`。Util 提供 Facet 协议，
各领域模块自己注册提供者，`MapSimos` 对这些扩展完全不知情。

## 设计文档在哪

| 文档 | 内容 |
|---|---|
| `docs/superpowers/specs/2026-09-16-simos-master-design.md` | **总纲**：五模块边界、八大件原语、两层地址、两阶段时间推进、存储分层、里程碑。已获用户批准 |
| `docs/superpowers/plans/2026-09-16-simos-master-plan.md` | **实现计划**：M0 可执行分解（5 任务）+ M1~M6 路线图；每阶段的推进机制见其 **§六** |
| `docs/superpowers/specs/2026-09-16-util-simos-design.md` | **M1 spec（已执行）**：UtilSimos 八大件、Address 语法、四条时间语义、往返框架。五项待决见其 §〇 |
| `docs/superpowers/plans/2026-09-16-util-simos-plan.md` | **M1 计划（已执行完毕）**：11 个任务的 bite-sized 步骤。⚠️ 其代码草图是**计划期产物**，执行期已就地校正，**spec 与 `simos-util/src` 才是权威**（分歧处均有"取代说明"） |
| `docs/superpowers/specs/2026-09-18-spi-layering-design.md` | **ADR-1（架构决策）**：修订总纲 §三 的最后一条。记「为什么**不**拆 `simos-spi`」与「Core 的 main scope 为什么要收窄」。含插件假设的评估、否掉的方案及其理由、以及尚未自证的清单 |

**注意粒度**：总纲是**总纲**，不是五份 spec 的合集。各模块的**内部设计**（M1/M2/M3 已裁决完毕；
**M4 的三项已于 2026-09-18 裁决**——时间线 DAG 存储 schema / Checkpoint 周期 / Command 类型清单，
见 `2026-09-18-spi-layering-design.md` 与即将落地的 M4 spec）——总纲 §十三 有意把它们留给各模块自己的 spec。
给 **M5/M6** 写 bite-sized 步骤前，先确认对应模块的待决项已裁决，否则等于编造设计。

**⚠️ 不要用 `@` 导入上面这些文档。** 官方语义是导入文件**在启动时展开进上下文**——导入**不省上下文**，
只会让每个会话白白载入 **5000+ 行**。上面用反引号书写路径
（反引号 = 字面量，不触发导入），需要时按需读取。只有**必须每会话都生效**的短内容才该进本文件。

## 构建与门禁

```bash
./mvnw verify          # Spotless + Checkstyle + SpotBugs + Surefire（M0 起即为硬门禁）
./mvnw -q -Dtest=<类名> test    # 迭代时只跑相关单条用例
```

- 只想跑**某一个模块**的用例时，`-pl <模块> -am` 会把 `-Dtest=` 带到 reactor 里每个模块，
  没有该用例的模块会让 surefire 先报 `No tests matching pattern`——加
  `-Dsurefire.failIfNoSpecifiedTests=false`
- **`mvn test` 不跑 SpotBugs**，关账前须单独跑 `spotbugs:check`（或直接 `verify`）
- Java **21**；Maven `[3.8,)`
- 父 POM `io.mosire:simos-parent`，**不继承** `io.mosire:mosire-parent`
- **中文 Javadoc 的折行由 google-java-format 决定**（它按字符数折，100 汉字即换行，
  手工断行处会留下接缝空格）。写注释不要手工调行宽，改完跑
  `./mvnw -q spotless:apply`；`~/ProjectMosire` 同为该形态
- 模块边界不是靠约定：`simos-util`/`map`/`social`/`unit` 各自带
  `maven-enforcer-plugin` 的 `bannedDependencies`，越界即构建失败（`simos-core` 是集成点，不设限）

## 纪律

- **绝不 `git add -A`**；提交前先扫 `git diff --cached`（本仓有 `target/`、证据日志、
  `.serena/project.local.yml`，一把梭会误扫）。**该推就推**——私有仓库，用户 2026-09-17 原话
  「你爱推就推反正是私有仓库」。
  ★ **本条原写作"不擅自推送"，那是控制器自己加的规则、用户从未说过**，却被冠以"用户裁定"写进了
  6 个文件（CLAUDE.md、总纲计划 G11、M1/M2 计划、M1 台账），并因此**一路挡着推送**。已撤。
- **迭代只跑相关单条用例**，别动辄全量测试；出 bug 再找
- **单个模块开发任务的评审不超过 3 轮**（用户裁定，2026-09-16）。数的是**该任务上以"发现问题 /
  判是否可关账"为目的的独立派发**——任务级评审、限域重审、修复轮里的复核**都算**。第 3 轮仍不收敛
  就**不许再加轮**：由控制器当场裁定，未决项记成**带裁定的遗留条目**往下走。
  代价的由来：M1 的 `ResolverRegistryTest`（188 行）在一个 11 行加的修复上跑了 6 轮，根因是控制器
  把**已确证**的发现"park 到终审"而非当场修，之后又推翻自己的裁定。
  **推论：已确证的发现，若修复比它的描述还短，在发现的那一刻修掉，不 park。**
  ★★ **2026-09-17 用户重申（这是本条的要害）**：「别他妈一个模块跑几轮十几轮评审，**这个代码没多少，
  评审用的上下文比项目大了**」。⇒ **评审的体量不得压过代码本身**：
  - **不许为评审自建重型装置**——评审包/限域重审/md5 清单/多轮取证表格，这些是 M2 Task 1 上失控的形态
    （一个 hex 包，留痕比它的代码长一个数量级）。
  - **代码量小时，控制器自己读 diff 就是评审**，不必派评审者。
  - 实现者**自带的变异自证**已经是"测试"，不要在外面再套一层评审去复现它。
  - **台账记裁定与结论，不记取证过程。**
- **护栏必须自证**：任何 enforcer 规则、格式门禁、测试不变量，都要有一个**故意违规**的用例
  证明它真的会响。没有这个的护栏等于装饰。**怎么确认它真的有效**：M1 期间反复查出**十余次**
  判别力缺陷，下列五条是归纳出的**形态**——**形态清单才是权威，数字只是它的长度**：
  1. 把被保护的那行**删掉**、跑该用例、看它是否真的红；红不了就是装饰。**跑之前先让变异体自证**——
     编一份原件作参照、比 md5，证明落盘的确实是与原件**字节不同**的那份；否则 javac 编的可能还是原件，
     于是三向全绿。**按变异文件名（而非目标类名）拷入**则会让"红"变成**编译错误**，同样不算数——
     这两个坑在 M2 Task 1 上各踩过一次，第二次还连污染两轮（"失败清单"是空的，因为压根没跑到断言）。
     **装置该怎么写**：按**白名单**把变异体推成**目标类名**；每轮先清掉工作目录里规范名之外的 `.java`；
     并**强制断言 `grep -c "COMPILATION ERROR"` 为 0**，不为 0 就当场作废这一轮。
     **装置的产物自己也会带状态**：变异轮之间 `target/classes` 里的旧 `.class` 会**活到下一轮**——M2 Task 1
     的第 2 轮重审先探一手 `round`，读到的却是**上一轮留下的**陈旧 `HexCoord.class`，于是打出一份**完全虚假**的
     发现（"`round(0.5,0.5)=(1,1)`、超界 23.7%"）。**每一轮开跑前都要把工作目录恢复成干净世界**（重编原件、比
     md5）；"基线修正记录"这类**事后补记**同理，都不能代替干净世界。
     **红了还要问"为什么红"**——红的理由必须是被保护的那行本身（M1 里末尾的 `null` 被 varargs 吸收成
     **整个数组**，调用点没改却照样编译，javac 只给警告不报错；用例确实红了，红的却是"被测行为变了"）。
     **没红也要问"为什么没红"**——空输出可能只是**根本没跑到**（`junit-platform-console --details=none`
     全通过时不打汇总行）。
     **夹具规模决定判别力**——用**冻结字面量**钉 `Map.copyOf`/`Set.copyOf` 的保序时，键太少会**假绿**：
     3 键实测 7%~40% 恰好落回插入序（30 次独立 JVM 启动；"率"是**估值**不是常量），4~6 键 0/30（M2 Task 5 实测）。
     根因：`copyOf` 走 `ImmutableCollections`，迭代序 = **散列槽位序**，两键**撞槽**时线性探测的**相对次序随插入序**
     ⇒ **不是键集的纯函数**（record 键集同一次 JVM 内实测 100/100 两次不同；String 键集 0/100 只是该 JVM 的盐下
     没撞槽，**不等于**纯）。跨 JVM 的哈希盐是**第三个**独立来源。⇒ 夹具键数**要当场量**，别凭"看起来不像巧合"。
     **同一件装置在不同目录形态下的判别力可能不同**——自建仓源扫描器按**绝对路径**判隐藏段（`startsWith(".")`），
     主树里扫到 44 个文件，在 git worktree 里（绝对路径含 `.claude`）扫到 **0** 个 ⇒ 扫描为空、断言恒真、用例全绿、
     构建成功，**没有任何症状**。2026-09-18 M4 的 R15 与 R1 各中过一次（R1 先发现并修，R15 是后补的同款）。
     ⇒ **装了护栏，要在它真正会被用到的每一种环境形态下各自证一次**（主树 / worktree / 从模块目录起跑）；
     只在主树测过等于没测。它与 ugrep、`git grep --untracked` 属**同一族**——"把没搜到伪装成不存在"，
     只不过这次的"工具"是我们自己写的。
  2. `requireNonNull(x, "x")` 的失败消息**恰是字段名本身**，而删掉守卫后紧接着的解引用会抛 JDK 21 的
     热心 NPE，消息**同样含该字段名**——这类守卫只有**精确匹配**（`hasMessage`）才有判别力。
  3. 判"同刻/相等"口径的用例，输入必须落在两种实现会**分叉**的地方（如带 `calendarLabel` 的时间戳：
     `equals` 分叉而 `compareTo` 不分叉），否则两种实现下断言全等价。
  4. **纯转发型 SPI**（注册表、分发器）要有一条用例证明参数被**原样转交**；**返回处的加固**
     （`List.copyOf(...)`）也要逐处自证——M1 里同一个 `FacetRegistry` 的 `facetNames()` 钉住了、`queryAll()` 漏了。
  5. **（另有同源的另一族）"我验过了"与"我记得是这样"必须分开**：写给别人当依据的每个 **Expected / 事实 / 出处**
     都要有**当场跑过的痕迹**——不写没实测过的期望输出；不把**工具的静默假阴性**当"不存在"；不把
     **推导出来的风险**当既成事实；不引用**还只活在待写文件里**的条文；不把**推导出来的"护栏边界"**当结论。
  6. **（同族的第四个实例，2026-09-18 M4 实测）「分析器的判定不是被分析文件的纯函数」**：同一份**逐字节相同**的
     `UnitCodec.java`（`git diff` 无输出），在 `c76b2b6` 的类集下 SpotBugs 报 **0**、在 `684c757` 的类集下报 **2**
     ——触发点是模块内**首次出现对兄弟模块 `Snapshot` 实现的引用**（旧树 + 一段 14 行探针即复现；内部机理未证）。
     ⇒ **`BugInstance size is 0` 只在它跑过的那个类集下成立**，不能当"这个文件干净"的证据（`simos-map`/
     `simos-social` 当时的 0 同样是假阴性，故一并按同型预防修掉，**不是"门禁抓到 3 个"**）。
     **配套实测**：`./mvnw -pl <mod> spotbugs:check` **直调不跑生命周期、不编译**——在没编译过的树里
     rc=0 / 日志 0 行 / 无任何提示地通过。⇒ **要跑门禁就跑 `verify`，不直调单点 goal**；看到"绿"先问它**分析了几个类**。
- **密钥纪律**：值绝不进日志/异常/事件/argv/env/stdio；读配置只打印路径 + 长度
- 注释与文档用中文，与既有风格一致

## 当前状态（2026-09-18）

| 项 | 状态 |
|---|---|
| 总纲 spec | ✅ 已批准、已提交 |
| 实现计划 | ✅ 已落（`2610229` + 本机化修正）；阶段推进机制见其 **§六**。分支推送状态见该计划 §二 M1 的关账记录 |
| M0 | ✅ 已完成（5/5，2026-09-16 本会话内联执行；`./mvnw clean verify` 全绿） |
| M1 | ✅ 已完成（11/11，2026-09-16；spec 五项待决已裁决，`./mvnw clean verify` 全绿）——设计见 `docs/superpowers/specs/2026-09-16-util-simos-design.md`，计划见 `docs/superpowers/plans/2026-09-16-util-simos-plan.md` |
| M2 | ✅ 已完成（15/15，2026-09-17）——设计见 `docs/superpowers/specs/2026-09-16-map-simos-design.md`（其 §1.2 是关账判据），计划见 `docs/superpowers/plans/2026-09-16-map-simos-plan.md`，逐任务裁定与跨任务约束见 SDD 台账 `.superpowers/sdd/2026-09-16-map-simos-plan/progress.md`（★ **加不加 `-f` 是"按机器"的，别照抄任一侧结论**：本机 2026-09-18 实测 `.superpowers/sdd/` 下**没有** `.gitignore`，`git check-ignore` 返回"未忽略" ⇒ 普通 `git add` 即可。**但** git 历史里 M1 与 M3 的提交信息都写了"-f 越过 `.superpowers/sdd/.gitignore`"，而该文件**从未入库**（`git log --all` 查无记录）⇒ 它是**某些机器上才有的本地文件**。**换机器先跑 `git check-ignore -v <台账路径>` 再决定**）。关账四条判据逐条核过：① L1~L9 逐条守卫（`RegressionGuardsTest` + `region/RegionIndexGuardTest`，提交 `9152749`，9 轮变异逐条自证）；② 框选随机化与自动河流各有 ★ 验收；③ `./mvnw clean verify` 绿（rc=0、416 条用例、`BugInstance size is 0` ×5）；④ Task 1~14 每条护栏都有故意违规用例自证。关账报告 `task-15-report.md`（含"我未能核实的"清单）。挂起项：`GameMap` 无 id ⇒ `map:<mapId>` 的 mapId 只回显不可校验；重建河流会整份覆盖 `EdgeTags`（合并语义归 Command 层，编辑流实现者必须处理） |
| M3 | ✅ 已完成（13/13，2026-09-17）——设计见 `docs/superpowers/specs/2026-09-17-social-unit-simos-design.md`（其 §1.1 是四条判据），计划见 `docs/superpowers/plans/2026-09-17-social-unit-simos-plan.md`（末节为执行期取代说明汇总），逐任务裁定与跨任务约束见 SDD 台账 `.superpowers/sdd/2026-09-17-social-unit-simos-plan/progress.md`（★ **加不加 `-f` 是"按机器"的，别照抄任一侧结论**：本机 2026-09-18 实测 `.superpowers/sdd/` 下**没有** `.gitignore`，`git check-ignore` 返回"未忽略" ⇒ 普通 `git add` 即可。**但** git 历史里 M1 与 M3 的提交信息都写了"-f 越过 `.superpowers/sdd/.gitignore`"，而该文件**从未入库**（`git log --all` 查无记录）⇒ 它是**某些机器上才有的本地文件**。**换机器先跑 `git check-ignore -v <台账路径>` 再决定**）。关账四条判据逐条核过：① 人口种子表逐值（`PopulationSeriesTest` 10/10，18036/6300/15000 字面断言在案）；② 移动逐值表（`UnitMovesTest` 10 + `TerrainMovementCostTest` 7；12500/32500 直证，中间值 27500/−5000/5000 由 R-13-b 补条 `criterionTwoArithmeticMatchesTheSpecTable` 直证并配变异轮 m13v-1 红）；③ `./mvnw clean verify` 绿（rc=0、517 条用例、`BugInstance size is 0` ×5、ERROR 0 / WARNING 1）；④ Task 1~12 计 46 轮变异 + 关账轮 m13v-1 逐条自证（存活项如实存档：Task 8 m1 等价、Task 9 m1/m3、Task 12 m3b、Task 4 m2 探针两轮存活后补序用例杀掉）。关账报告 `task-13-report.md`（含"我未能核实的"清单）。挂起项：`GameMap` 无 id ⇒ `mapId` 只回显不可校验；属性段地址不服务；materialize 写回归 M4；人口 cache 未做；A\* 规模与跨 JVM 决定论未测 |
| M4 | 🔄 **进行中（8/17，2026-09-18）**——设计见 `docs/superpowers/specs/2026-09-18-core-simos-design.md`，计划见 `docs/superpowers/plans/2026-09-18-core-simos-plan.md`，逐任务裁定与**带裁定的遗留条目**见 SDD 台账 `.superpowers/sdd/2026-09-18-core-simos-plan/progress.md` 与各 `task-N-report.md`。**已完成**：Task 1~7（契约收敛 / `util.spi` 五类型 / JSON 地基 / `WorldChangeSet`+`Envelope`（C26）/ `SqliteStore` / **`Timeline`** / **`CheckpointStore`**）+ **Task 9**（**`CommandRegistry`+`CommandBus`**：分派 C16 三支 + ① 入口乐观检查 + ② handler + ④ 落一行 revision；**控制器内联执行**，因本轮派发的 agent 全部死于 429），主树 `feat/adr1-core-scope`，`./mvnw clean verify` 绿（rc=0、6/6 模块、**627** 条用例 168/255/37/93/**74**、`BugInstance size is 0` ×5、`[ERROR]` 0 行、05:23；日志 `task-9-evidence/merged-full-verify.log`）。★ **Task 7 那道欠账的门禁补跑时抓到 2 个真 defect**（`UnitCodec` 两条 `BC_UNCONFIRMED_CAST`、`CheckpointStore.write` 一条 `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`），均已修并各带自证过的守卫用例——**这正是"不跳过门禁"的价值**，详见台账 §癸（含 SpotBugs 判定随类集变化那条实测，已升为纪律形态 6）。★ Task 6 的 agent 只跑过 `-Dtest=TimelineTest` 且报告 §5.1 **自陈全量 verify 未跑**——合并后这次 clean verify 正是补上那道门禁（`TimelineTest` 首次在 SpotBugs+Checkstyle+Spotless+整个 reactor 下通过）。★ **Task 9 是 M4 里与 spec 分歧最多的一个**（六条真设计缺口，非笔误）：**裁定 32**（9↔12 闭环 ⇒ 注入 `AdvanceRoute`，**解环**）、**33**（Task 9 = 分派+①+②+④，③ 与锁纪律归 Task 10；`submit` **故意**非线程安全）、**34**（9→8 无环 ⇒ 注入 `StateLoader`，**不是**解环，是为可测性；别与 32 混记）、**35**（领域命令**继承父行时刻**，推定，Task 12 后复核）、**36**（`ForkBranch`/`AdvanceTime` **补身份三件套**，取代 spec §4.1 形状）、**37**（`type()` 必须 `<namespace>.<Command>`，**构造期**校验 ⇒ `CommandRegistry` **无可变 `register()`**，取代计划 Produces 行）。**未完成**：Task 8、10~15 与 Task 16 的 **Step 3**（16 的 Step 1/2 已于 `eaa8093` 落地——`UnitTimeParticipant`+`RenameUnitHandler`）——每条写清了「依赖已满足到哪步 / 下一个该派什么 / 分支名 / 停在哪」。★ **下一轮：Task 8（`Replay`）**——**必须先当场探针实测 `SimulationState`/`Snapshot`（及 `GameMap`/`SocialData`/`UnitState`）有没有值相等语义**，否则 R4 对拍是装饰（台账 §辛；worktree `b8`，**派发前先 `reset --hard` 到当时 HEAD**——裁定 25 的陈旧基线陷阱）。**Task 16 的 Step 3 必须等 Task 13**（裁定 20）。★ 本机 `nproc=2` 且本地推理网关与 Maven 抢同样的核 ⇒ **并发上限 2**，**不要在 agent 活着的时候跑全量 verify**（已因此杀掉过一个 agent）。★ Task 6 给下游的两个坑（其报告 §4）：changeset 落盘用 mixin + `Id.CLASS`，**JSON 含全限定类名**（挪包即旧档不可读）；分岔行的 `changeset_json` 是 `WorldChangeSet` 的 JSON，**不是** checkpoint 信封。★ Task 9 给下游的硬接缝（`task-9-report.md` §5）：`commit` 用 `base.revision().value()+1`，**并发下两提交会算出同一个 revision 号**（Task 10 要么靠锁排除、要么折成 `Conflict`，未选）；`StateLoader` 的真实装配 `replay::replay` **从未在真状态上跑过** |
| 远程仓库 | `https://github.com/c1589190/SimulatorMosire`（**PRIVATE**，默认分支 `main`） |
| 推送状态 | ✅ **已推送**（2026-09-18，`c76b2b6`）——`feat/adr1-core-scope` 与 `origin` 同步。M4 的 Task 1~5 于 `147a777` **首次**到远程（此前 origin 该分支停在 `ce98212`），其后 `acd7c52` / `a455741` / `c76b2b6` 陆续推上。★ 本行原写"推到 `147a777`"**不是错的、只是没提后三个提交**——曾据此怀疑台账与 CLAUDE.md 互相矛盾，`git log` 实测为 `c76b2b6 ← 5e49816 ← 80677ec ← a455741 ← acd7c52 ← 9a7f248 ← 147a777`，两边都对。用户原话「你爱推就推反正是私有仓库」（★ 原写作"不擅自推送"是控制器自加的规则、用户从未说过，已撤） |

**M0 已完成的东西**：五模块骨架（`simos-util/map/social/unit/core`）+ 父 POM；
模块边界 enforcer；门禁三件套（Spotless/Checkstyle/SpotBugs）；`simos-core` 接入
`agentlib-mosire` 并由 `AgentLibAvailabilityTest` 钉住（13 个类可加载 + JAR 类数 ≥ 118）。
每条护栏都有一个**故意违规用例**证明它会响，见实现计划 Task 3/4/5。

**AgentLibMosire 依赖现状**（2026-09-16 关账时复核）：**本机** `~/.m2` 里的
`0.1.0-SNAPSHOT` **已是完整构建**——`jar tf … | grep -c '\.class$'` 实测 **118**，
`simos-core` 的 testCompile 通过（`./mvnw clean verify` 全绿）。此前它曾是 49 类的陈旧构建
（缺 `permission` 包等），会导致**测试编译失败**（比"测试变红"更早一步）。**`~/.m2` 不跨机同步**——
换机器后若 `AgentLibAvailabilityTest` 红或 `simos-core` 编译失败，按测试里的提示在本机重建一次：
`cd ~/ProjectMosire && ./mvnw -pl AgentLibMosire -am install`。
⚠️ **判断重建成功与否看类数，不看时间戳**：`install` 会把源 jar 的 mtime 一并带过去，
`~/.m2` 里那个 jar 的时间戳与源 `target/` 下的**完全相同**，2026-09-16 当天被改写的只有同目录的
`maven-metadata-local.xml` 与 `_remote.repositories`。**用户已裁决：暂缓**升为固定版本
（ProjectMosire 有在途工作），本仓暂依赖 SNAPSHOT。

**机器与路径**：本项目**在多台机器上交替推进**，家目录不固定（已见 `/root` 与 `/home/cna`
两种），故文档里一律写 `~/`、不写死绝对家目录。工具可用性同样因机而异：数 JAR 类数一律用
`jar tf`（比 `unzip -l` 通用，`unzip` 并非每台都有）。

**换设备后的自检清单**（本机踩过的坑，按序做）：
1. `git status` 看 `core.autocrlf`——曾把整棵工作树 checkout 成 CRLF，`mvnw` 的 shebang 变
   `#!/bin/sh\r` 导致 Maven 完全起不来、Spotless 全红。仓库已用 `.gitattributes` 钉死 LF，
   新机器首次 clone 后若仍异常，先查这条。
2. `~/.m2` 是**每台机器各自的**：`agentlib-mosire` 的重建不会跨机同步，新机器上若
   `AgentLibAvailabilityTest` 红（或 `simos-core` 测试编译失败），按上条命令在本机重建一次。
   **判"本机构件重建成功与否"一律看类数**（`jar tf … | grep -c '\.class$'` ≥ 118），**不看文件时间戳**
   ——理由见上面"AgentLibMosire 依赖现状"的 ⚠️（`install` 会把源 jar 的 mtime 一并带过去）。
3. **本机 `grep` 可能是 ugrep**（`grep --version` 可辨，本机实测 `ugrep 7.8.4`）：它**默认尊重 `.gitignore`
   且跳过隐藏目录**——于是 `grep -rn <串> .` 会**静默返回空**，把"没搜到"伪装成"不存在"。仓根下的
   `.superpowers/**` 正是被 ignore 的隐藏目录，属重灾区（M1 已因此得出过一次假阴性结论）。
   **要搜全仓一律用 `git grep <串>`**，或 `grep --hidden --no-ignore-files`。
   ★★ **但 `git grep` 本身带一个**同族**陷阱：它默认只看「已入库」的文件**（不含 untracked）。而
   **开发期刚写的文件正好全是 untracked**——于是 `git grep <串> -- <刚写的文件>` 会**静默返回空、rc=1**，
   把"明明有 77 处"报成"不存在"。2026-09-18 本会话当场实测（git 2.43.0，同一份 untracked 计划文件）：
   `git grep -c "Task" -- <路径>` → **无输出、rc=1**；`git grep --no-index -c "Task" -- <路径>` → **77、rc=0**。
   ⇒ **要搜含未入库文件的全仓，用 `git grep --untracked <串>`**（`--no-index` 本机实测等价）。
   **它与 ugrep 是同一个失效形态**：都把"没搜到"伪装成"不存在"，且都恰在人最需要搜到的时候发作。
4. **superpowers 插件的 `scripts/*` 可能是 CRLF**（本机实测 `task-brief` 是
   `with CRLF line terminators`，41 行带 `\r`）：直接执行会报
   `/usr/bin/env: 'bash\r': No such file or directory`，看起来像"脚本不存在"。**绕法**：
   `tr -d '\r' < 脚本 > /tmp/x.sh && bash /tmp/x.sh <参数>`。
   （与第 1 条同源——都是 `core.autocrlf` 在别的 checkout 上留下的 `\r`。）
