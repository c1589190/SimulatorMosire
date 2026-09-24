# AGENT.md —— 本仓常驻上下文 + 多 agent 共用工作树的规矩

> ★★ **2026-09-24 起，本文件是唯一常驻文档**：原 `CLAUDE.md`（133KB，历史台账为主）**已废弃**——
> 它那部分内容并没有丢：逐条里程碑在 **git 历史**、`docs/superpowers/**`（spec/plan）与
> `.superpowers/sdd/**`（SDD 台账与证据）里都留着。**新文档一律写这里。**
>
> 下文每条规矩后面都附**为什么**——它们几乎都是**真发生过的损失**，不是洁癖。

## 〇、这是什么 / 五条铁律 / 模块边界（改代码前先读）

**SimulatorMosire（simos）** 是 GSimulator 的重构：把原本单一功能的地图推演工具，解放为**可分模块生长**的
模拟引擎。参考项目 `~/DevMosire/GSimulator`（**只作参考、不作依赖**）与
`~/ProjectMosire/AgentLibMosire`（CoreSimos 整包依赖）。

### 五条铁律（不可协商）

1. **所有查询最终解析为稳定实体。** 地址是定位方式，**ID 是身份**。单位调动、区域改名，历史与 Info 都不断。
2. **所有修改最终表示为 `Command → ChangeSet → Revision`。** 不存在绕过该路径的写入口。
3. **所有领域模块只拥有自己的数据。** MapSimos 永远不知道 SocialSimos / UnitSimos 存在。
4. **Core 只负责组合与调度**，不重新实现领域逻辑。
5. **变更集从完整状态类型派生**，且有**往返不变式测试**守卫：`apply(changeSet, base)` 逐字段重建 target。

> 铁律 5 的由来：GSimulator 的 `MapDiff` 是**手工对着 `MapData` 维护**的，`MapData` 加字段时没人提醒跟着加，
> 四个字段漂移出去（`terrainBlocks`/`terrainTypes`/`pathwayGroups`/`edges`），既无编译期也无测试期护栏，
> 导致**对非 root 节点写连通性会静默丢失**。这是本项目最贵的教训。

### 模块结构与依赖硬约束

```
UtilSimos  →  MapSimos  →  { SocialSimos, UnitSimos }  →  CoreSimos  →  ShellSimos(app)
```

| 模块 | 允许依赖 | 要点 |
|---|---|---|
| `simos-util` | 仅 Jackson（databind + datatype-jdk8）+ SLF4J | 不依赖任何 simos 模块，**不碰文件系统**。jdk8 模块是必需的：`Optional` 在快照树里处于**嵌套泛型位置**，裸 databind 会写成 `{"present":…}` 并丢值 |
| `simos-map` | `simos-util` | **永不** import social/unit/agentlib；**不做任何存储** |
| `simos-social` | util + map | 不依赖 UnitSimos |
| `simos-unit` | util + map | 不依赖 SocialSimos |
| `simos-core` | **main scope**：util + `agentlib-mosire` + jackson-databind + sqlite-jdbc | map/social/unit **退到 test scope** ⇒ Core **编译期看不见任何领域类型**（铁律 4 的结构化），想重新实现领域逻辑也无从下手 |
| `simos-app`（组合根） | util+map+social+unit+core+agentlib+mcp-core+mcp-json-jackson2+jackson+日志实现 | **不设 enforcer**：按 `/map` `/social` `/unit` 路由 ⇒ 天然认识各模块。`Shell`/`ShellConfig`/`ShellMain`、`gui/`(5711)、`query/`、`tools/`、`binding/`、`demo/` |

- 上表的边界**由 `maven-enforcer-plugin` 的 `bannedDependencies` 在构建期强制**——越界 = 构建失败，不是 code review 的事。
- 跨模块可见性走 **Facet**，不走反向依赖："某个 hex 上有哪些单位"**不能**写成 `MapManager.getUnitsAt(hex)`；
  Util 提供 Facet 协议，各领域模块自己注册提供者，MapSimos 对这些扩展完全不知情。

### 文档地图（★ 不要用 `@` 导入——导入会在启动时把文件展开进上下文，白载 5000+ 行）

| 文档 | 内容 |
|---|---|
| `docs/superpowers/specs/2026-09-16-simos-master-design.md` | **总纲**：五模块边界、八大件原语、两层地址、两阶段时间推进、存储分层、里程碑（已批准） |
| `docs/superpowers/plans/2026-09-16-simos-master-plan.md` | **实现计划**：M0 分解 + M1~M6 路线图 |
| `docs/superpowers/specs/` 与 `plans/` 下的其余按日期文件 | 各阶段的 spec/plan：M1 util、M2 map、M3 social+unit、M4 core、M5 shell、M6 导入器、M7 webui、**M8 地图编辑**、M9 大图性能、M10 部署、Unit 扩容、SDSimos、工具面… |
| `.superpowers/sdd/<topic>/` | **SDD 台账与逐任务证据**（`progress.md` / `task-N-report.md` / 各 `*-evidence/`）。想要"当时到底怎么验的"看这里 |
| `docs/superpowers/HANDOFF-*.md` | 跨会话进度存档 |

★ **"当前状态"不在这里抄一遍**：看 `git log --oneline -20`、最近的 `.superpowers/sdd/*/progress.md`，
以及各计划自己那节关账记录。★ 历史里程碑的逐条台账**已随 CLAUDE.md 退役**（在 git 历史里，`git show <sha>:CLAUDE.md` 可取）。

## 一、并发：这是最容易造成返工的一类

1. ★ **一次只能跑一个 Maven。** 本机内存不足以并行；两轮 Maven 同抢 `target/` 会让**测试计数失真**
   （实测：某轮 simos-app 只跑了 **9/66** 个测试类、97 条，而正常是 459 条）。
   跑前：`pgrep -af "surefirebooter|classworlds.launcher"`；有命中就等。
2. ★ **一个文件一个 owner。** 动手前先 `git status --porcelain`，再用 `find <模块>/src -newermt "-3 minutes"`
   看有没有人正在写；看到**半成品尾巴**（例如某处 import 了刚被删的类 ⇒ 编译不过）**不要接手**——
   那通常不是坏尾，是**另一个 agent 的中间态**。本会话就发生过：一个 agent 因此停下报我，判断是对的。
3. ★ **派单要写清"谁拥有哪些文件"**；跨 agent 改同一文件 = 互相覆盖。
4. ★ **不要在别人跑 Maven 的窗口里改 Java**：编译/测试的计数会不可信，白跑一轮。

## 二、产物：别把正在跑的服务的 jar 覆盖掉

- ★ **重建产物前先停服务**（或把新 jar 打到别的路径）。Java 的 classloader 是**惰性加载**的：
  运行中被替换的 jar，之后要用到某个类时就读不到 ⇒ `ClassNotFoundException`（真发生过一次）。
  ★★ **补充实测（2026-09-23，同一天第二次踩）**：`package` 覆盖在跑的 jar 之后，服务**进程还活着、
  `/api/*` 还 200**，但**静态资源全 500**（`/`、`/index.html`、`/styles.css` 都是
  `{"error":"internal error"}`）——因为 JVM 手里的 jar inode 已被就地重写。判"服务坏了"要看
  **`/` 与 `/styles.css`**，别只看 `/api/*`。
  ★★★ **本 pom 下没有任何命令行开关能跳过 shade**：`maven-shade-plugin:3.6.0` 的 `skip` 参数
  **没有 user property**（`plugin.xml` 里是 `<skip implementation="boolean" default-value="false"/>`，
  不是 `${shade.skip}`）⇒ **`-Dshade.skip=true` 会被静默忽略**（实测：`-q -DskipTests package
  -Dshade.skip=true` 返回 rc=0，而 jar 的 md5 与大小都变了）。
  ⇒ **服务在跑时，验代码用 `./mvnw test`**（`test` 阶段**不经过** `package`/`shade`，**不动 jar**）；
  **只有必须打包/关账时才停服务**。
- 派单里若要"起得来能看"，**写明用别的端口起、别动已有的进程**。

## 三、验证：护栏必须自证，且不许把"没跑"写成"通过"

1. ★ 不信"rc=0 就是过了"：本仓 pom 明写前端门禁 **fail-closed、不设 skip/if 守卫**；
   而 `-q` 会吞掉 surefire 汇总 ⇒ **类名写错、测试被跳过，照样 rc=0**。
   要**看 surefire 报告**（`target/surefire-reports/*.txt`）并核对 **mtime 落在本轮**。
2. ★ 新护栏/新判据要有**判别力**：临时把被测逻辑改坏（变异体）⇒ 必须**当场红**，然后还原。
   做不到的（结构性不可表达）如实说"等价存活"，**不许编红点**。
3. ★ 报告里必须有**"我没做/没验证的"**一节。凡是没跑过的，不许写成通过。
4. 本会话反复用到的一条：**门禁沙箱不执行 `initHost`/`initDecision`** ⇒ "搬走函数但别处还留着裸调用点"
   这类漏改**门禁查不出来、只在运行时炸**。搬函数后必须**静态审计裸引用**。

## 四、台账/计划 vs 代码：**机制性描述一律回代码核**

本会话发现**至少 4 处**"计划/台账措辞 ≠ 代码实际"，都足以让人做错方向：

| 台账/计划说 | 代码实际 |
|---|---|
| "写工具用粗断言" | 写侧确实粗，但**有 3 条决策窄写覆写** `writeResources`，且目标**由身份而非载荷派生** |
| T10 "写工具的断言也成对改细" | 只兑现为那 3 条，**"改细"的范围比措辞小** |
| "读口必须走 `RedactingQueryService` 的同一份装配" | 9 条读工具走的是 `ToolSupport` 谓词；`RedactingQueryService` 是 GUI `?as=` 在走 |
| "`at.revision` = 该条目落盘时的 revision" | 实为**写入所依据的基态** revision（可能小于首次可见的 revision） |

⇒ **凡要用到台账里的机制描述，先去代码确认**；发现不符**报出来**，别照着措辞硬做。

## 五、提交与协作

1. **不 `git add -A`**；按**批次**提交（不同关注点分开，便于回退与审计），提交前扫 `git status`。
   ★★ **但反过来也有坑**（2026-09-23 实测踩到）：跑过**全仓工具**（`./mvnw spotless:apply`、
   格式化/重写的 codemod）之后，**按模块路径 `git add` 会漏掉别的模块的同类改动** ——
   那次 `spotless:apply` 改了 21 个文件（`simos-app` 18 个 + `simos-sd` 3 个），
   我按 `git add -A simos-app/src/...` 提交，`simos-sd` 那 3 个**留在工作树里没进提交**，
   而提交信息却写着"21 个文件"（**信息与提交内容不符**）。
   ⇒ **纪律**：全仓工具跑完后，用 `git diff --name-only` **列全**再逐条确认，
   或按"我改过的模块清单"取并集，不要只给一个模块路径。
2. 提交信息**中文**、写清"改了什么 + 为什么 + 验收的实际数字 + 未验的部分"；本仓惯例是**把诚实边界写进提交信息**。
3. 已确认的发现，**若修复比描述还短，当场修**，别 park 成 issue。
4. `.superpowers/sdd/**` 与 `docs/**` 的历史台账行是**留痕**：**不篡改**；要更正就**追加**标注。
   （`CLAUDE.md` 已于 2026-09-24 退役 ⇒ 它的历史行在 git 里，同样不回头改。）

## 六、前端（无 npm、无打包器、纯 `<script>`）

1. 拆文件后**加载顺序是语义的一部分**：`hexgeom → hexcolor → regionShape → map.js → map-mapeditor.js →
   map-regioneditor.js → renderer.js`（`renderer.js` **加载期**就要 `core.renderEdgeKindOptions`）。
2. `simos-app/src/test/js/helpers/webui-loader.cjs` 的 `BUNDLE_DEPS`：**新文件依赖它取用的宿主**（例如新文件
   依赖 `map.js`），**不许反过来**——反过来会让新文件先于宿主执行、取不到 `SimosMapCore`。
3. **可变绑定跨文件必须走 getter**（`active`、`regionNamesEnabled`）；取快照会恒为初始值。
4. 两个宿主页（`map.html` 旧页 / `index.html` 工作台）**都要同步**；旧页也加载 `map*.js` 与 `renderer.js`。
5. 前端门禁下界**两处同值、改一处必须改两处**：`simos-app/src/test/js/run-gate.cjs` 的 `MIN_TESTS` 与
   `gate-contract.test.cjs` 的 `MIN_ASSERTIONS`（当前 **275**；新增 `.test.cjs` 文件还要进 `REQUIRED_FILES`）。

## 七、门禁与关账的常用命令

```bash
node simos-app/src/test/js/run-gate.cjs          # 前端门禁（下界见 run-gate.cjs，当前 275）
./mvnw test -pl simos-app -am                    # 全量测试（含前端门禁那一步；不动 jar）
./mvnw -q -Dspotless.check.skip=true -DskipTests package 2>/dev/null   # 见 §八「打包」——本仓无 shade 开关
./mvnw clean verify                              # ★ 关账：Spotless+Checkstyle+SpotBugs+Surefire+前端门禁
```

★ **`clean verify` 必须前台跑**：台账记过"后台跑会被内存守卫杀"，而被杀**既不是红也不是绿**（不能算过）。
★ 迭代时只跑相关单条：`./mvnw -q -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false test -pl <模块> -am`。

## 八、环境与运维（本机实测；换机器先看这一节）

### 8.1 起一个实例 / 判活 / 收工

```bash
pgrep -af "surefirebooter|classworlds.launcher|maven"        # ① 先确认没有 Maven/服务在跑
stat -c '%y %n' simos-app/target/simos-app-*-shaded.jar      # ② ★ bearer：jar 的 mtime 可能**早于源码**
./mvnw -DskipTests package -pl simos-app -am                 #    早于就跑一次（build 期间**别**有服务在跑）
tools/run-shaded.sh simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar \
  --store /tmp/simos-store --gui-port 5817                    # ③ 快照式启动（JVM 独占副本，免受就地重写）
```

- ★ **判活三条一起看**：`/`、`/index.html`、`/styles.css` 全 **200**（只看 `/api/*` 会漏判 §二那种"jar 被就地重写"）
  + `/api/map/overview`、`/api/units`、`/api/timeline` 200 + 进程在。
- ★ 所有 curl 都要 `--noproxy '*'`（本机代理会拦 127.0.0.1）。
- ★ **收工用 PID，别 `pkill -f`**：本仓实例的命令行里含 jar 快照名/端口/store，`pkill -f "<那串>"` 会**连执行它的
  shell 一起杀**（已踩 4 次）。用 `ps -eo pid,cmd | grep simos-shaded` 拿 PID ⇒ `kill <pid>`。
- ★ 端口约定：GUI **5711**（常用 5817 起演示实例）、MCP **5715**、审批 **5713**。
- ★ **store 是世界的本体**：`--store <dir>` 指向一个 SQLite 库；**空库首启会就地种入富世界**
  （`v17levant` 复刻：59223 hex / 252 区域 / 240 河流边），**非空库绝不覆盖**。换库 = 换世界。

### 8.2 世界数据的两层：bootstrap ≠ 世界初始化

- **空库 bootstrap**（`RichWorld`）只给**地图 + 区域**：`/api/map/overview` 的 `cities:0`、`/api/units` 的
  `{"units":[]}` **不是故障**。
- 人口 / 城市 / 三国军队要靠 MCP 工具 **`simos.worldgen.initialize`**（一次一批 = 一条 revision，`army` 缺省真，
  连带写 map/social/unit/sd 四域）。输入是 `config/worldgen/v17levant-nations.json`，`randomization.enabled`
  出厂 `false` ⇒ 取文档硬值（可复现）。
- 地形在 simos 里是**块**（`GameMap.terrainBlocks` = 同地形六邻连通分量）；`HexCell` 只存 **height**。

### 8.3 MCP 口与审批链（2026-09-24 起）

- **MCP 口 = GM 组**（用户裁定："MCP 和 GM Agent 处于同一权限级，想改什么改什么"），当前 **67 条工具**
  （读 15 + 非窄写 7 + 窄写 45）。就绪判据：启动日志里 `外发工具 N 个`；工具面与桶的对齐由
  `SimosToolsTest`/`McpServerTest`/`McpPortTopologyTest` 三处名单断言把守。
- **审批链两条**：**GM 面（MCP 口）的写"无脑过"**（`GmAutoApproveGate` ⇒ 直接批准、不登记待批）；
  **决策人链仍要人批**（`AutoApproveGate → ConfirmGate`，GM 在「决策 → 审批」点头）。
  ★ 两张面的 caller 桶都是 `DEFAULT` ⇒ **从请求字段上分不开**，只能按"用哪条 authorizer"分。
  ★ `scope=session` 会被 AgentLib 收窄成 `once`（`DEFAULT` 桶非 `sessionGrantable`）。
- 读工具默认**四桶共享**；只给 GM 的那些在类上标 `app.tools.GmOnlyRead`
  （目前：`simos.map.path`、`simos.sd.decision-makers`、`simos.sd.decision-maker`）。
- ★ **GUI 与 MCP 读工具共用同一份视图**（`app/gui/ApiViews` 是公开的视图层）——别再各写一份
  （那正是"同一资源的两个形状"的由来，见 `.superpowers/sdd/2026-09-22-tool-surface/m4-inventory.md` §二-6）。

### 8.4 换设备自检（本机踩过的坑，按序做）

1. `git status` 看 `core.autocrlf`——曾把整棵工作树 checkout 成 CRLF，`mvnw` 的 shebang 变 `#!/bin/sh\r`
   导致 Maven 完全起不来、Spotless 全红。仓库已用 `.gitattributes` 钉死 LF。
2. `~/.m2` 是**每台机器各自的**：`agentlib-mosire` 的重建不会跨机同步。新机器上若 `AgentLibAvailabilityTest` 红
   （或 `simos-core` 测试编译失败），在本机重建一次：`cd ~/ProjectMosire && ./mvnw -pl AgentLibMosire -am install`。
   **判"重建成功与否"一律看类数**（`jar tf … | grep -c '\.class$'` ≥ 118），**不看时间戳**
   （`install` 会把源 jar 的 mtime 一并带过去）。
3. **本机 `grep` 可能是 ugrep**（`grep --version` 可辨）：它**默认尊重 `.gitignore` 且跳过隐藏目录** ⇒
   `grep -rn <串> .` 会**静默返回空**，把"没搜到"伪装成"不存在"（`.superpowers/**` 是重灾区）。
   ★ **`git grep` 有同族陷阱**：它默认只看**已入库**文件，而开发期刚写的文件全是 untracked ⇒ 也会静默空/rc=1。
   ⇒ **搜全仓**：`git grep <串>`；**含未入库文件**：`git grep --untracked <串>`（或 `grep --hidden --no-ignore-files`）。
4. superpowers 插件的 `scripts/*` 可能是 **CRLF**：直接执行会报 `/usr/bin/env: 'bash\r': No such file or directory`。
   绕法：`tr -d '\r' < 脚本 > /tmp/x.sh && bash /tmp/x.sh <参数>`。
5. **门禁耗时会压着工具 600s 上限**：越过会被摘到后台，而"后台 × 内存压力"可能被杀。
   **"被杀"既不是红也不是绿**（不能算过）；跑 `clean verify` 前先确认没有别的 Maven 在跑。
