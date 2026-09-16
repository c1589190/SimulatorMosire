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
| `docs/superpowers/plans/2026-09-16-simos-master-plan.md` | **实现计划**：M0 可执行分解（5 任务）+ M1~M6 路线图 |

**注意粒度**：总纲是**总纲**，不是五份 spec 的合集。各模块的**内部设计**（`MapChangeSet` 字段清单、
`Region` 如何统一 GSimulator 的三个 region 概念、`TemporalSeries` 插值语义、时间线 DAG 存储 schema）
**尚未裁决**——总纲 §十三 有意把它们留给各模块自己的 spec。给 M1~M6 写 bite-sized 步骤前，
先确认对应模块的待决项已裁决，否则等于编造设计。

**⚠️ 不要用 `@` 导入这两份文档。** 官方语义是导入文件**在启动时展开进上下文**——导入**不省上下文**，
只会让每个会话白白载入 1800+ 行。上面用反引号书写路径（反引号 = 字面量，不触发导入），需要时按需读取。
只有**必须每会话都生效**的短内容才该进本文件。

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
- **护栏必须自证**：任何 enforcer 规则、格式门禁、测试不变量，都要有一个**故意违规**的用例
  证明它真的会响。没有这个的护栏等于装饰
- **密钥纪律**：值绝不进日志/异常/事件/argv/env/stdio；读配置只打印路径 + 长度
- 注释与文档用中文，与既有风格一致

## 当前状态（2026-09-16）

| 项 | 状态 |
|---|---|
| 总纲 spec | ✅ 已批准、已提交 |
| 实现计划 | ✅ 已落（本地 `2610229` + 本机化修正，**未推送**）；阶段推进机制见其 **§六** |
| M0 | ✅ 已完成（5/5，2026-09-16 本会话内联执行；`./mvnw clean verify` 全绿） |
| M1 | ⬜ 未开始——**先裁决** spec §十三 的五项待决，再写模块 spec（见实现计划 §六 P1） |
| 远程仓库 | `https://github.com/c1589190/SimulatorMosire`（**PRIVATE**，默认分支 `main`） |

**M0 已完成的东西**：五模块骨架（`simos-util/map/social/unit/core`）+ 父 POM；
模块边界 enforcer；门禁三件套（Spotless/Checkstyle/SpotBugs）；`simos-core` 接入
`agentlib-mosire` 并由 `AgentLibAvailabilityTest` 钉住（13 个类可加载 + JAR 类数 ≥ 118）。
每条护栏都有一个**故意违规用例**证明它会响，见实现计划 Task 3/4/5。

**AgentLibMosire 依赖现状**：`~/.m2` 里的 `0.1.0-SNAPSHOT` 曾重建为 118 类的完整构建
（2026-09-16；此前是 2026-09-13 的 109 类陈旧构建，缺 `permission` 包 8 类与
`plugin.HostServices`）。**注意这是 `2026-09-16` 在 `/root` 那台机器上的结果**，
`/home/cna` 这台仍是 49 类的陈旧构建，尚未重建。
**用户已裁决：暂缓**升为固定版本（ProjectMosire 有在途工作），
本仓暂依赖 SNAPSHOT。若哪天 `AgentLibAvailabilityTest` 红了，先按测试里的提示
`cd ~/ProjectMosire && ./mvnw -pl AgentLibMosire -am install` 重建。

**机器与路径**：本项目**在多台机器上交替推进**，家目录不固定（已见 `/root` 与 `/home/cna`
两种），故文档里一律写 `~/`、不写死绝对家目录。工具可用性同样因机而异：数 JAR 类数一律用
`jar tf`（比 `unzip -l` 通用，`unzip` 并非每台都有）。

**换设备后的自检清单**（本机踩过的坑，按序做）：
1. `git status` 看 `core.autocrlf`——曾把整棵工作树 checkout 成 CRLF，`mvnw` 的 shebang 变
   `#!/bin/sh\r` 导致 Maven 完全起不来、Spotless 全红。仓库已用 `.gitattributes` 钉死 LF，
   新机器首次 clone 后若仍异常，先查这条。
2. `~/.m2` 是**每台机器各自的**：`agentlib-mosire` 的重建不会跨机同步，新机器上若
   `AgentLibAvailabilityTest` 红，按上条命令在本机重建一次。
