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
| UtilSimos | `simos-util` | **仅** Jackson databind + SLF4J。不依赖 AgentLibMosire，不依赖任何 simos 模块，**不碰文件系统** |
| MapSimos | `simos-map` | `simos-util`。**永不** import social/unit/agentlib。**不做任何存储** |
| SocialSimos | `simos-social` | `simos-util` + `simos-map`。**不依赖 UnitSimos** |
| UnitSimos | `simos-unit` | `simos-util` + `simos-map`。**不依赖 SocialSimos** |
| CoreSimos | `simos-core` | 以上全部 + `agentlib-mosire` + MCP SDK + sqlite-jdbc + 日志实现 |

这些边界**由 `maven-enforcer-plugin` 的 `bannedDependencies` 在构建期强制**——越界 = 构建失败，
不是 code review 的事。

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

**注意粒度**：总纲是**总纲**，不是五份 spec 的合集。各模块的**内部设计**（M1 已裁决完毕；
`MapChangeSet` 字段清单、`Region` 如何统一 GSimulator 的三个 region 概念、时间线 DAG 存储 schema
等**仍未裁决**）——总纲 §十三 有意把它们留给各模块自己的 spec。给 **M2~M6** 写 bite-sized 步骤前，
先确认对应模块的待决项已裁决，否则等于编造设计。

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

- **绝不 `git add -A`**；提交前先扫 `git diff --cached`；**不擅自推送**
- **迭代只跑相关单条用例**，别动辄全量测试；出 bug 再找
- **单个模块开发任务的评审不超过 3 轮**（用户裁定，2026-09-16）。数的是**该任务上以"发现问题 /
  判是否可关账"为目的的独立派发**——任务级评审、限域重审、修复轮里的复核**都算**。第 3 轮仍不收敛
  就**不许再加轮**：由控制器当场裁定，未决项记成**带裁定的遗留条目**往下走。
  代价的由来：M1 的 `ResolverRegistryTest`（188 行）在一个 11 行加的修复上跑了 6 轮，根因是控制器
  把**已确证**的发现"park 到终审"而非当场修，之后又推翻自己的裁定。
  **推论：已确证的发现，若修复比它的描述还短，在发现的那一刻修掉，不 park。**
- **护栏必须自证**：任何 enforcer 规则、格式门禁、测试不变量，都要有一个**故意违规**的用例
  证明它真的会响。没有这个的护栏等于装饰。**怎么确认它真的有效**：M1 期间反复查出**十余次**
  判别力缺陷，下列五条是归纳出的**形态**——**形态清单才是权威，数字只是它的长度**：
  1. 把被保护的那行**删掉**、跑该用例、看它是否真的红；红不了就是装饰。**红了还要问"为什么红"**——
     红的理由必须是被保护的那行本身（M1 里末尾的 `null` 被 varargs 吸收成**整个数组**，调用点没改却照样
     编译，javac 只给警告不报错；用例确实红了，红的却是"被测行为变了"）。**没红也要问"为什么没红"**——
     空输出可能只是**根本没跑到**（`junit-platform-console --details=none` 全通过时不打汇总行）。
  2. `requireNonNull(x, "x")` 的失败消息**恰是字段名本身**，而删掉守卫后紧接着的解引用会抛 JDK 21 的
     热心 NPE，消息**同样含该字段名**——这类守卫只有**精确匹配**（`hasMessage`）才有判别力。
  3. 判"同刻/相等"口径的用例，输入必须落在两种实现会**分叉**的地方（如带 `calendarLabel` 的时间戳：
     `equals` 分叉而 `compareTo` 不分叉），否则两种实现下断言全等价。
  4. **纯转发型 SPI**（注册表、分发器）要有一条用例证明参数被**原样转交**；**返回处的加固**
     （`List.copyOf(...)`）也要逐处自证——M1 里同一个 `FacetRegistry` 的 `facetNames()` 钉住了、`queryAll()` 漏了。
  5. **（另有同源的另一族）"我验过了"与"我记得是这样"必须分开**：写给别人当依据的每个 **Expected / 事实 / 出处**
     都要有**当场跑过的痕迹**——不写没实测过的期望输出；不把**工具的静默假阴性**当"不存在"；不把
     **推导出来的风险**当既成事实；不引用**还只活在待写文件里**的条文；不把**推导出来的"护栏边界"**当结论。
- **密钥纪律**：值绝不进日志/异常/事件/argv/env/stdio；读配置只打印路径 + 长度
- 注释与文档用中文，与既有风格一致

## 当前状态（2026-09-16）

| 项 | 状态 |
|---|---|
| 总纲 spec | ✅ 已批准、已提交 |
| 实现计划 | ✅ 已落（`2610229` + 本机化修正）；阶段推进机制见其 **§六**。分支推送状态见该计划 §二 M1 的关账记录 |
| M0 | ✅ 已完成（5/5，2026-09-16 本会话内联执行；`./mvnw clean verify` 全绿） |
| M1 | ✅ 已完成（11/11，2026-09-16；spec 五项待决已裁决，`./mvnw clean verify` 全绿）——设计见 `docs/superpowers/specs/2026-09-16-util-simos-design.md`，计划见 `docs/superpowers/plans/2026-09-16-util-simos-plan.md` |
| M2 | ⬜ 未开始——**待裁决** MapSimos 待决项（总纲 spec §十三） |
| 远程仓库 | `https://github.com/c1589190/SimulatorMosire`（**PRIVATE**，默认分支 `main`） |

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
