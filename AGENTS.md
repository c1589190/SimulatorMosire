# AGENTS.md —— 本仓常驻上下文 + 多 agent 共用工作树的规矩

> ★★ **2026-09-27 起本文件叫 `AGENTS.md`**（原名 `AGENT.md`）。改名的**唯一理由**是**让新 Agent 自动加载**：
> 本机 DSH 的 workspace-instructions 候选名默认是 **`AGENTS.md`（第一顺位）→ `CLAUDE.md`**，
> 而 `AGENT.md` **不在候选里** ⇒ 用原名要靠人告诉新会话"去读 AGENT.md"，改名后 harness **自动注入**。
> 同时**删除 `CLAUDE.md`**（用户 2026-09-27 裁定：「最后把 CLAUDE.md 删了，确保新 Agent 加载时先加载 AGENT.md」；
> 它的退役决定见下）—— 旧的那份 16 行"已废弃"指针文件的职责已由本行承担。
>
> ★★ **历史留痕里的旧名一律不改**（§五.4：不篡改留痕）：`docs/superpowers/**` 与 `.superpowers/sdd/**`
> 里出现的 `CLAUDE.md` / `AGENT.md`，**读作本文件**。★ 其中 `git show <sha>:CLAUDE.md` 那种**取历史版本**的
> 写法**仍然有效**（`CLAUDE.md` 在 2026-09-24~2026-09-27 之间是一份"已废弃"指针，更早是 133KB 的历史台账，
> 两者都在 git 里）。★ 改名这件事本身**不影响任何代码**（全仓零代码引用，只有注释里的"去哪读"）。
>
> ★ **2026-09-24 起，本文件是唯一常驻文档**：原 `CLAUDE.md`（133KB，历史台账为主）**已废弃**——
> 其中**仍要遵守的纪律**（判别力的载体形态 / 评审体量上限 / 密钥纪律 / 换设备自检 …）已并入本文件
> （§三 / §五 / §八）；逐条里程碑与完整事故叙述在 **git 历史**、`docs/superpowers/**`（spec/plan）与
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

> ★ **本节 2026-09-26 更正**：原图 `UtilSimos → MapSimos → { SocialSimos, UnitSimos } → CoreSimos → ShellSimos(app)`
> **已过期**——它不含 `sd` / `actor-api` / `actor` / `economy-api` / `economy` / `ledger` 六个模块（都在图之后才建），
> 且 `ShellSimos` 已不是模块名、Core 的地位经 ADR-1 收窄（见下表）。**旧图不删，留在这里作对照**。

```
util → map → { social, unit, sd }
util → economy-api → { economy, ledger }        （economy-api 还依赖 actor-api，见下一行）
actor-api → economy-api → { economy, ledger }   （actor-api 主依赖为零；economy-api 反过来依赖它——LaborAllocation 持 ActorRef）
util + map + economy-api → actor                （actor 切片；与 economy/ledger 是同层兄弟，互不依赖）
core       只依赖 util（+ agentlib / sqlite-jdbc / jackson）；领域模块在 core 里**只许 test scope**
app（组合根）依赖全部领域模块 + core + agentlib + MCP —— **唯一认识所有模块的地方**
```

| 模块 | 允许依赖 | 要点 |
|---|---|---|
| `simos-util` | 仅 Jackson（databind + datatype-jdk8）+ SLF4J | 不依赖任何 simos 模块，**不碰文件系统**。jdk8 模块是必需的：`Optional` 在快照树里处于**嵌套泛型位置**，裸 databind 会写成 `{"present":…}` 并丢值 |
| `simos-map` | `simos-util` | **永不** import social/unit/agentlib；**不做任何存储** |
| `simos-social` | util + map | 不依赖 UnitSimos |
| `simos-unit` | util + map | 不依赖 SocialSimos |
| `simos-sd` | util + map + social + unit + agentlib-mosire | 领域模块的**下游**；**禁 core/app**（不得认识组合与调度）。★ 它声明了 `simos-social` 但源码**零 import**（死依赖，2026-09-26 查实） |
| `simos-core` | **main scope**：util + `agentlib-mosire` + jackson-databind + sqlite-jdbc | map/social/unit/**sd** **退到 test scope** ⇒ Core **编译期看不见任何领域类型**（铁律 4 的结构化），想重新实现领域逻辑也无从下手。★ 领域面（map/social/unit/sd）与**经济面 + actor 切片**（economy/economy-api/ledger/**actor**/**actor-api**）**任何 scope 一律禁**，只有 test scope 开五条窄口子（map/social/unit/**economy-api**/**actor-api**——后两条是**传递依赖逼出来的**，见 pom 里那两段注释） |
| `simos-actor-api` | **不声明任何主依赖**（util / map / jackson 都不声明） | actor 切片共用的**最底层契约**：`ActorRef`（种类 + **不透明** id）、`ActorKind`、`AssetKind` / `AssetClassKey`。★ 它比 `economy-api` 还底层（`economy-api` 反过来依赖它）⇒ 四个类型**与它们的测试**全是 `java.*`/junit/assertj，**零 simos import**（实测 `grep -rF 'import io.mosire'` = 0 命中；裁定 R-e/R-h/R-j 逐条删掉了用不到的声明）。禁一切领域/编排模块（economy-api/economy/ledger/social/unit/sd/core/app/agentlib） |
| `simos-actor` | actor-api + util + map + economy-api + jackson | 聚合式 actor 切片（S1 阶段 2）：`Actor` 身份本体 + 产权（`AssetHolding`，**键到格** ⇒ 依赖 map 拿 `HexCoord`）+ 商品库存（`GoodsAccount`，键是 `CommodityId` ⇒ 依赖 economy-api）+ 状态树/落盘切片/变更集 + `ActorCodec`（`namespace() = "actor"`）。组件**恰四件**：`(meta, actors, holdings, accounts)`。禁 economy/ledger（同层切片）与 social/unit/sd/core/app/agentlib；★ economy-api 与 actor-api **不在禁列**（契约层，与 social 依赖 economy-api 同待遇） |
| `simos-economy-api` | **actor-api** + util + map（util 与 jackson 实际**零 import**：前者是死依赖；★ map：**2026-09-27 回代码更正**——不是"只有 `LotChange` 一处"，实测 **4 个文件**用 `HexCoord`：`CohortKey` / `HouseholdActors` / `Transfer` / `LotChange`） | 只放**经济切片共用的稳定契约**（各类稳定 ID / `CommodityId`）；无 Snapshot、无存储、无公式。★ `ActorRef` / `ActorKind` **已不在本模块**（上移到更底层的 `simos-actor-api`，本模块只**引用**它们——`LaborAllocation.actor`）。禁一切领域/编排模块 |
| `simos-economy` | util + economy-api + **map** + **actor-api** | 聚合式经济切片（产业 / 阶层行 / 债务 / 流水）。禁 social/unit/sd/core/app/agentlib/**ledger**（切片间互不依赖）。★ **2026-09-27 回 pom 更正**：本行原写"util + economy-api"——**漏了 map 与 actor-api**；实测 `simos-economy/pom.xml:34-37` 显式声明 `simos-map`（H4 起市场表键 = `HexCoord`，`pom` 自述见 `:19-21`）、`main` 里有 **9 文件 / 11 处** `import io.mosire.simos.map.hex.HexCoord`。★ 另记一句自相矛盾：同 pom `:77` 的 ban `<message>` 仍写"economy 只依赖 economy-api/util"（**文案陈旧，不是禁令**；事实以依赖块为准） |
| ~~`simos-ledger`~~ | —— | ★★ **已于 2026-09-27 退役**（裁定 D2-A）：它零外部引用、无 handler、无人依赖；`Transfer` 的**形状**（不是它的 `ActorRef` 主体类型）已搬进 `simos-economy-api` 的 `transfer` 包。原模块连同 `Account`/`Claim`/三个测试一并删除；`economy-api` 的 ID 契约（`TransferId`/`ClaimId`/`AccountId`…）**保留**。★ 全仓对它的引用现在只剩历史叙述（设计文档与若干类注的留痕） |
| `simos-app`（组合根） | core + map + social + unit + sd + economy + agentlib + mcp-core + mcp-json-jackson2 + jackson + 日志实现 | **不设 enforcer**：按 `/map` `/social` `/unit` 路由 ⇒ 天然认识各模块。`Shell`/`ShellConfig`/`ShellMain`、`gui/`(5711)、`query/`、`tools/`、`binding/`、`demo/`。★ 它**用了** `util`（56 个 main 文件）与 `economy-api`（2 个）却**未声明**，靠传递依赖（2026-09-26 查实；同款情形在 `simos-core/pom.xml:33-36` 曾被定性为缺陷并修过——**"依赖传递不是契约"**） |

- 上表的边界**由 `maven-enforcer-plugin` 的 `bannedDependencies` 在构建期强制**——越界 = 构建失败，不是 code review 的事。
  ★ **这份强制长期是不完整的**：`util/map/social/unit` 的 ban 列表停留在 **M0（2026-09-16，当时全仓只有 5 个模块）**，
  此后新增的模块**长期没回填**（`git log -S'economy' -- {util,map,social,unit,core}/pom.xml` = **0 个提交**）。
  ★★ **Task 10（2026-09-26）回填了哪些**（实测，非推演）：`core` 纳入 `simos-actor` / `simos-actor-api`（宽 exclude；
  `actor-api` 的 test-scope 口子是**跑出来**的——`core → social(test) → economy-api → actor-api` 是传递依赖，与 R1 那条同因），
  `util` / `map` / `social` / `unit` 各补 `simos-actor`（`simos-actor-api` 是**契约层**，与 `economy-api` 同待遇、**不在禁列**）。
  ★★ **仍然没回填的**（如实记）：`util` / `map` / `unit` **都不拦** `economy` / `economy-api` / `ledger`
  （`social → economy` 一条已由 R1 补上；`economy-api` 是**刻意**不拦——文档三处明文允许领域模块依赖它）。
  ⇒ 设计意图是**互不依赖（对称）**，这一半仍未合上；要接的话按本条形制补（改 ban 列表 + 跑 `verify` 看是否被传递依赖逼出 `includes`）。
- 跨模块可见性走 **Facet**，不走反向依赖："某个 hex 上有哪些单位"**不能**写成 `MapManager.getUnitsAt(hex)`；
  Util 提供 Facet 协议，各领域模块自己注册提供者，MapSimos 对这些扩展完全不知情。
- ★ **命令跨模块边界是"不透明载荷"**（ADR-1，2026-09-18）：Core 只认信封的 `type` 字符串，不 `instanceof`、
  不 switch 类型；新增契约一律放 `io.mosire.simos.util.spi`，既有契约**原地不动**。
- ★ 写新阶段的 bite-sized 步骤前，**先确认该模块的待决项已裁决**——否则等于编造设计。

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

### 一.5 ★★★ 用户 2026-09-27 裁定：**派单粒度 = 一个阶段一个写代码代理，测试最后单独一个代理**

> 用户原话（连起来读才完整）：
> ① 「**不是，为啥一个 M1 能拆这么多子任务，不是先写代码和过编译，等最后再搞测试吗**」
> ② 「**也没让你自己写，是让你派子代理写代码、过编译，然后最后你搞测试，具体也是派测试 Agent 盯着，
>   一个阶段拆这么多干啥？**」

**这条同时纠正两个错**（我 2026-09-27 在 M1 上两个都犯了，代价是两次子代理被打断）：

| 错误做法 | 正确做法 |
|---|---|
| ❌ **把一个阶段拆成 8~9 个子任务**（M1 拆成 M1.0…M1.8，一个任务一个代理） | ✅ **一个阶段 = 一个写代码代理**，一次把该阶段**全部生产代码**写完 |
| ❌ **每个任务都配测试**（写一段代码、配一组用例、跑一次门禁） | ✅ 写代码代理**只写到「编译过」**；**测试整块留给最后的测试代理** |
| ❌ **控制方（我）自己动手写代码** | ✅ 代码**派子代理**写；**控制方负责派单、审、验收、提交** |

**与 §三.0 的关系**：§三.0 已裁定"**测试与变异自证留到最后统一做**"（"先一口气把业务代码都做完"）。
§一.5 补上它的**执行形态**：那条裁定落到派单上就是"**写代码代理不写测试、测试代理最后统一盯**"。
两条是同一件事的两面，一起读。

**写代码代理的任务书里必须写死的三条**：
1. **只写生产代码，写到「编译过」**（`-DskipTests … compile`）；**不要写/改任何测试文件**；
   **不要跑 `test` / `verify`**（测试阶段不归它）；
2. **不 `git commit`**（控制方审后提交）；
3. 报告里必须**点名哪些改动会改变数值行为**、以及受影响的硬编码字面量清单（**给测试代理当输入**）。

**测试代理的任务书里必须写死的三条**：
1. 按**计划里的判据清单**（`docs/superpowers/plans/*` 的该阶段各条）逐条落实，**不是**按代码反推该测什么；
2. 关键项要**变异自证**（改坏 ⇒ 当场红 ⇒ 还原 ⇒ 比 md5），做不到的如实说"等价存活"；
3. 报告里必须有**"我没做 / 没验证的"**一节。

★ **粒度判据（怎么算"一个阶段"）**：以**开发计划里的一节**为单位（M1、M2…）。
阶段内部**不再逐任务派单**——同一个代理在一个上下文里写完，才看得见各任务之间的耦合
（M1 的 `frozen` 边界、`applyTransfer` 唯一写口、关系面与保留算式的分工，都是**跨任务**才看得清的）。
★ **例外**：阶段**明显过大**（一个代理一轮写不完）时可以拆，但**按"可独立编译的层"拆**（如"钱这一层"/"关系这一层"），
**不是**按计划里的编号逐条拆；且每层仍然只写代码、不写测试。

### 一.6 ★★ 用户 2026-09-27 裁定：**后台子代理在跑时，控制方直接 sleep 等待**（★★ 本条是 **DeepseekHarness 特有**）

> ★★ **适用面（先读这一行再照抄）**：本条讲的两个现象 —— ① 后台子代理在跑时 harness 会**持续注入 goal 续轮**；
> ② **长 sleep 会被工具拒**（实测 `sleep 570` 返回 `Error: [object Object]`）—— 是 **DeepseekHarness 特有的构式机制**，
> **其他 Agent 软件貌似没有**（用户 2026-09-27 原话）。⇒ 换外壳 / 换 harness 时**先核一遍再照抄**（与 §八.6 同族纪律），
> 别把本节的绕法带到没有这个机制的环境里。

- 用户原话（两句连读才完整）：
  ①「**这个子Agent在后台运行会一直注入 goal，直接 sleep 等着就行；这条计入 AGENTS.md，避免后续浪费 token**」
  ②「**我的建议是 2 分钟 1 等，这条也计入 AGENTS.md，注意说明这是 Deepseek**」
- **规矩**：只要还有后台子代理没落定，控制方**不要再做任何"填时间"的活**（读代码 / 侦察 / 写计划都不算进展）——
  **每次 `sleep 120`（2 分钟）、醒来查一次；落定一个再执行下一步**；**不要一次 sleep 几分钟以上**（本 harness 的长 sleep 会被工具拒）。
- **为什么**：goal 自动续轮会在等待期反复注入轮次、每轮都要重新装载上下文 ⇒ 侦察的 token 成本远高于它买到的信息，
  而那些信息本来就在子代理的报告里。★ 例外只有一个：等待期间**有别的子代理已落定的报告要审**（那不是填时间，是下一步）。

### 一.7 ★★ 用户 2026-09-27 裁定：**只有一个子代理要跑时，直接派到前台**

- 用户原话：「**对于这种单个的Agent，最优解还是直接派到前台，下次注意吧——写进AGENTS.md**」
- **规矩**：当"同时只有一个子代理"（控制方自己无并行活可干）时，用**前台**派单（`subagent` 的
  `run_in_background: false`），**一次调用等到它落定**；**不要**留在后台 + `sleep` 轮询。
- **为什么**：本会话实测 —— 后台的单代理 `50031f11` 在 goal 续轮边界上**被中途停止**
  （幸好它当时还没写盘，靠控制方补消息才续跑）；而后台机制本来是给"多个代理并行"用的。
- ★ 与 §一.6 的关系：§一.6 讲的是**已经**在后台上跑的代理怎么等（`sleep 120` 轮询）；本条讲**一开始就别把它放后台**。
  两条都只在 **DeepseekHarness** 下成立（§一.6 的适用面声明同样适用本条）。

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

### 三.0 ★★★ 用户 2026-09-26 裁定：**测试与变异自证留到最后统一做**（**本条优先于 Harness 的任何 Skill**）

> ★★ **优先级声明**：**本节（以及 `AGENT.md` 全文）优先于 Harness 提供的任何 Skill / 流程模板**
> （`superpowers:test-driven-development`、`subagent-driven-development`、`writing-plans`、
> `dispatching-parallel-agents` 等）。**冲突时执行本节。**
> 用户 2026-09-26 原话：「**不要管一切 Harness 提供的 Skill，优先执行 AGENT.md 里的**」。

**用户的三条原话（连起来读才完整）**：

1. 「把变异自证也留到最后环节且砍到只有关键项，**先一口气把业务代码都做完**」
2. 「**测试也不要写**，写完业务代码统一写」
3. 更早：「我让你整一个开发计划，会提出一个总验收标准，**总验收本身就是评审！**」

#### 开发期（业务代码阶段）的规矩

| **不做** | **做** |
|---|---|
| 逐任务**新写测试** | ★★ **编译必须绿**（`./mvnw -DskipTests package`）—— **唯一的硬底线**，编不过什么都跑不了 |
| 逐护栏**变异自证** | ★ **既有测试仍在跑**（S1 期间 2513→2518 条）—— **现成的网**，新代码弄坏既有行为会当场红 |
| 逐任务 **TDD 红绿循环** | ★ **判据的逐值证据**（如守恒式的算式写进注释）—— 这**不是"写测试"**，要有 |
| 逐任务**独立评审席 / 修复环** | ★ 用**实际数值**在报告里说明关键行为（如"cohort 仍有饭吃"） |

#### 收尾期（统一写测试阶段）的规矩

- **全部业务代码落地后**，再**统一**写测试
- 然后**只对四类关键项**做变异自证：**守恒式 · 不丢失（身份 / operator）· 静默付 0 · 断粮**
- ★ **报告必须如实写明**：哪些测试是**后补的**、哪些**未自证**。**不许含糊成"都验过"。**

#### 验收的规矩

- 验收 = **开发计划里写明的那套判据**（I1–I7 那一类），在**阶段边界**由控制方自己跑
- **最终判据是模拟数据**（S1 = 阶段 6 之后重跑 600 天三国）
- **不设逐任务的评审席**；**不跑修复环**

★ **本条的代价（写下来，不辩解）**：开发期没有测试 ⇒ **曲线一旦不对，没有测试告诉我们是哪一块错的**，
诊断退回"读代码 + 二分"。与之对冲的是：既有测试仍在守**既有行为**，而**新路径**的正确性最终由**模拟数据**判定
—— 这正是用户定的判据。

★ **与本节其余各条的关系**：下面第 **2** 条（"新护栏要有判别力：变异体必须当场红"）
**在开发期暂停执行、在收尾的统一写测试阶段恢复**；本节其余各条
（不信 `rc=0`、看 surefire 报告与 mtime、如实写"没做/没验证"）**始终有效**。

> ★★ **2026-09-27 用户追加裁定：阶段门禁只做编译，所有测试统一移到最后。**
> 用户原话：「**把统一测试都移到最后，阶段门禁只进行编译，所有有关内容的测试都移到最后！**」
> ⇒ **执行形态（覆盖本文 §七"阶段边界跑全仓 `clean verify`"一条，在本计划执行期有效）**：
> ① 阶段/层的边界**只跑** `tools/mvn-lock.sh -q spotless:apply` + `tools/mvn-lock.sh -DskipTests compile`
> （**不跑** `test` / `verify` / `package`；**既有回归网在全部生产代码落地前也不跑** —— "用全绿当阶段门禁"的做法
> 在本计划执行期暂停）；
> ② **全部生产代码**（本计划 = M1 剩余 + M2）落地之后，才**统一**：写测试 → 全仓 `clean verify` → 一年期真档读数 → 台账/关账；
> ③ 收尾期的四类关键项变异自证仍照本节收尾期规矩执行；
> ④ **如实记**：开发期**没有**既有测试的回归保护 —— 编不过会当场发现，**行为回归不会**。这是用户已知并接受的对冲。

---

1. ★ 不信"rc=0 就是过了"：本仓 pom 明写前端门禁 **fail-closed、不设 skip/if 守卫**；
   而 `-q` 会吞掉 surefire 汇总 ⇒ **类名写错、测试被跳过，照样 rc=0**。
   要**看 surefire 报告**（`target/surefire-reports/*.txt`）并核对 **mtime 落在本轮**。
   ★★ **追加（2026-09-27 实测踩到）：取 rc 本身也会读错** ——
   `… | tail -25; echo "[exit=$?]"` 里 **`$?` 是 `tail` 的退出码**，于是"报告里还带 1 条红"的一次运行被读成了绿。
   ⇒ **要么不接管道，要么显式用 `${PIPESTATUS[0]}`**；且**仍要看 surefire 的真数与 mtime**。
   （同族的另一种：`-q` 下管道给 `tail` ⇒ 既丢了汇总、又读错了 rc。）
2. ★ 新护栏/新判据要有**判别力**：临时把被测逻辑改坏（变异体）⇒ 必须**当场红**，然后还原。
   做不到的（结构性不可表达）如实说"等价存活"，**不许编红点**。
3. ★ 报告里必须有**"我没做/没验证的"**一节。凡是没跑过的，不许写成通过。
4. 本会话反复用到的一条：**门禁沙箱不执行 `initHost`/`initDecision`** ⇒ "搬走函数但别处还留着裸调用点"
   这类漏改**门禁查不出来、只在运行时炸**。搬函数后必须**静态审计裸引用**。

**判别力的载体形态（摘要）**：完整叙述（每例的装置、数字、由来）在 `git show 6808aad^:CLAUDE.md` 的「纪律」节，
这里只留仍要遵守的判据：

- **"我验过了"与"我记得是这样"必须分开**：写给别人当依据的每个 Expected / 事实 / 出处都要有**当场跑过的痕迹**
  ——不写没实测过的期望输出；不把工具的静默假阴性当"不存在"；不把推导出来的风险当既成事实。
- **变异体先自证再跑**：编一份原件作参照、比 md5，证明落盘的确实与原件字节不同；变异体按**目标类名**推入
  （按变异文件名拷入 ⇒ "红"变成编译错误，不算数）；每轮先把工作目录恢复成**干净世界**（重编原件 + 比 md5）。
- **`target/` 下的一切都不还原**（`.class`、`surefire-reports/*.txt`）⇒ 读 surefire 数字**先跑干净轮**，
  并核对报告 mtime 落在本轮；别拿"上次留下的绿/红"当本轮结论。
- **红要红在被保护的那行上**（红的理由不对不算数）；**没红也要问为什么没红**（可能根本没跑到：
  `--details=none` 全通过时不打汇总行）。
- **夹具规模决定判别力**：用冻结字面量钉 `Map.copyOf`/`Set.copyOf` 保序时，3 键实测 7%~40% 恰好落回插入序
  （假绿），4~6 键 0/30 ⇒ 键数要当场量，别凭"看起来不像巧合"。
- **`mtime` 不是"字节变了"的判据，md5 才是**：变异轮还原时 `cp` 重写文件、字节一个没变 ⇒
  mtime 新不能判旧证据失效；反过来也不能因为 mtime 新就重跑。
- **拒收判据与接收判据都要有真样本**：Maven 3.9 写的是 `[INFO] BUILD FAILURE`，只认 `[ERROR]` 的正则
  会把跑完的红轮判成"没跑完"（把"有"伪装成"没有"）。
- **命中 0 先怀疑自己的正则 / 读取，别先怀疑被测物**：`grep -cE 'spotless.*SUCCESS'` 返回 0，
  而 Spotless 其实跑遍了 6 个模块；核对脚本读到空串要先断言非空再下结论。
- **分析器的判定不是被分析文件的纯函数**：同一份逐字节相同的 `UnitCodec.java`，SpotBugs 在 `c76b2b6` 的类集下报 0、
  在 `684c757` 下报 2；`spotbugs:check` 直调不跑生命周期、不编译，在没编译过的树里 rc=0 无提示通过
  ⇒ 要跑门禁就 `verify`；见绿先问"它分析了几个类"。
- **装置的输入会静默改变被测对象**：Playwright `page.fill` 会把页面滚下去 ⇒ 随后的 `mouse.click` 落在视口外、
  **无任何报错**；点击后必须断言"它真的发生了"（选中态 / 状态文案变了）。
- **装了护栏要在它真正会被用到的每种环境形态下各证一次**（主树 / worktree / 从模块目录起跑）：
  同一份仓源扫描器在主树扫到 44 个文件、在 worktree 扫到 0 个（绝对路径含 `.claude`）⇒ 断言恒真、全绿、无症状；
  **只在主树测过等于没测**。★ 装了护栏却不跑 = 装饰（自建装置不在 CI 里同罪）。
- **自建装置要自指**：把"这一轮跑的是哪份字节（md5）"追加进日志本身；引用自记要取**本名轮**那一块
  （同名日志里会留作废轮）。

## 四、台账/计划 vs 代码：**机制性描述一律回代码核**

本会话发现**至少 4 处**"计划/台账措辞 ≠ 代码实际"，都足以让人做错方向：

| 台账/计划说 | 代码实际 |
|---|---|
| "写工具用粗断言" | 写侧确实粗，但**有 3 条决策窄写覆写** `writeResources`，且目标**由身份而非载荷派生** |
| T10 "写工具的断言也成对改细" | 只兑现为那 3 条，**"改细"的范围比措辞小** |
| "读口必须走 `RedactingQueryService` 的同一份装配" | 9 条读工具走的是 `ToolSupport` 谓词；`RedactingQueryService` 是 GUI `?as=` 在走 |
| "`at.revision` = 该条目落盘时的 revision" | 实为**写入所依据的基态** revision（可能小于首次可见的 revision） |

⇒ **凡要用到台账里的机制描述，先去代码确认**；发现不符**报出来**，别照着措辞硬做。

★ **SDD 的 workspace 目录名由脚本从计划文件名推导，可能与台账实际目录不一致**：本阶段计划是
`2026-09-22-tool-surface-plan.md`，脚本推导出 `.superpowers/sdd/2026-09-22-tool-surface-plan/`，
而**真正的台账**在 `.superpowers/sdd/2026-09-22-tool-surface/`（无 `-plan` 后缀，开工时手工建的）。
⇒ 接手前先 `ls .superpowers/sdd/`；别据"脚本目录里没有 `progress.md`"判定无台账——那会被读成
"本计划没有台账 ⇒ 从头开始"，把已关账的任务全部重派（SDD 技能原文称之为**观察到的最贵的失败**）。

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
5. **该推就推**：私有仓库，用户 2026-09-17 原话「你爱推就推反正是私有仓库」。★ 曾有一条"不擅自推送"
   是控制器**自造**的规矩、用户从未说过，却被冠以"用户裁定"写进 6 个文件、一路挡着推送——已撤。
   ⇒ **自造的规矩别冒充用户裁定**；引用裁定要能指出原话与日期。
6. ★ **评审的体量不得压过代码本身**（用户 2026-09-17 原话：「别他妈一个模块跑几轮十几轮评审，
   这个代码没多少，评审用的上下文比项目大了」）：单个模块开发任务的**评审不超过 3 轮**
   （数的是"发现问题 / 判是否可关账"的独立派发）；第 3 轮仍不收敛，由控制器当场裁定，
   未决项记成带裁定的遗留条目往下走。
   不许为评审自建重型装置（评审包 / 限域重审 / md5 清单 / 多轮取证表格——M2 Task 1 上失控的形态：
   一个 hex 包留痕比它的代码长一个数量级）；**代码量小时，控制器自己读 diff 就是评审**；
   实现者自带的变异自证已经是"测试"，不要在外面再套一层去复现它；**台账记裁定与结论，不记取证过程**。
7. **密钥纪律**：值绝不进日志 / 异常 / 事件 / argv / env / stdio；读配置只打印**路径 + 长度**。
8. 注释与文档**用中文**，与既有风格一致。

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
./mvnw -q -Dspotless.check.skip=true -DskipTests package 2>/dev/null   # 见 §二「产物」——本仓无 shade 开关
./mvnw clean verify                              # ★ 关账：Spotless+Checkstyle+SpotBugs+Surefire+前端门禁
```

★ **`clean verify` 必须前台跑**：台账记过"后台跑会被内存守卫杀"，而被杀**既不是红也不是绿**（不能算过）。
★★ **真数（2026-09-27，M1.0+M1.1 之后，全仓 `clean verify` 后逐模块清点 surefire 报告）**：
**11 个模块 / 2483 条 / 0 失败 / 0 错误**（app 685、map 379、unit 305、core 215、util 201、sd 188、social 188、
economy 171、actor 105、economy-api 38、actor-api 8），前端门禁 **297/297**。
★ 历史：H6 收口轮 2460 → M0 批 2467 → **M1.0+M1.1 后 2483**（util +2、economy-api +12、actor +1、app +1）。**以本行为准**。
★ 报"测试数"之前**先 `rm -rf */target/surefire-reports`**（否则读到两轮的并集，见 §三.1）；
★ `simos-app/target/surefire-reports/` **可能整个目录不存在** —— 那说明 app 侧这一轮**没跑到 test 阶段**
（编译或前置模块失败、或被中断），**不能读成"app 没有测试"**，更不能拿上游 10 个模块的绿当 app 的绿。
★★ **追加（2026-09-27，M0 收尾查清的一处夹具 bug —— 凡"一年末"读数必看）**：
`EconomyRealScaleClothTest` 的 `YEAR_DAYS` 曾是 **365**，而本仓的"一年末"一律是 **第 360 天**
（`CYCLE_DAYS = 120` 的第 3 个关账日；一年期真档推进 = tick 120/240/360）。365 落在**第 4 个周期的第 5 天**，
而周期第一天就把现扣投入划走 ⇒ 缸里的纤维被取空 ⇒ 当年读到"年末纤维 = 0"，
并被**误诊成"推进路径造成的相位差异"**（实为时点错；M0.1 已证路径无关）。
实测：第 0 天 20,700,000 → 第 1 天 0 → 第 120/240/**360** 天 **18,042,000** → 第 365 天 **0**。
⇒ **纪律：`YEAR_DAYS` 一律 360；写一年末断言前先问"这是第几天"**。
★ 连带教训：**"上界"式区间断言（`isBetween(0, X)`）会把时点错掩盖成"相位差异"** ——
能收紧就收紧，收不紧要写明理由。

★★ **追加（2026-09-27，M0 批实测踩到，代价三轮返工）**：
① **`-Dtest='A+B'` 是 JUnit5 的 tag 表达式、不是"或"** ⇒ 不匹配任何类、surefire **一条不跑**、
打印 `test` 目标、**退出 0**。正确写法是**逗号**：`-Dtest='A,B'`。**差一点把"一条都没跑"当成"测试通过"。**
② **Maven 增量编译不可全信**：实测 `simos-economy` 的类文件比源文件**新 21 秒**却没重编
（`-pl simos-app -am` 下没有 `Recompiling the module`）⇒ 两轮诊断都跑在**旧字节码**上。
**纪律：改完生产代码，先 `rm -rf <模块>/target/classes`（或 `target/test-classes`）再跑测试。**
★★ **追加（2026-09-27 实测踩到三次）：阶段边界的门禁要跑「全仓 `verify`」，不是模块级。**
`verify -pl <某模块> -am` 只对**该模块及其上游**跑 Spotless/Checkstyle/SpotBugs ⇒ 我在别的模块欠的格式账
（`simos-social` / `simos-actor`）**跨了两个批次都没被发现**，直到一次全仓 verify 才暴露。
⇒ 三件事一起记住：① 跑了 `spotless:apply` **必须**跑 `verify`（不是 `test`）；
② **`-am` 不能省**（`verify -pl simos-app` 不带 `-am` 会用 `~/.m2` 的旧 SNAPSHOT，报"找不到包"的**假红**）；
③ **门禁是三项**（Spotless + Checkstyle + SpotBugs）——只跑前两项**不等于**绿（实测：5 条 SpotBugs 就是这么漏过去的）。
★ 全仓工具（`spotless:apply`）跑完后，按 §五.1：**`git diff --name-only` 列全**再逐条确认后提交。
★ 迭代时只跑相关单条：`./mvnw -q -Dtest=<类名> -Dsurefire.failIfNoSpecifiedTests=false test -pl <模块> -am`。
★ 中文 Javadoc 的折行由 **google-java-format**（Spotless）决定：**不要手工调行宽**（手工断行处会留下接缝空格），
改完跑 `./mvnw -q spotless:apply`；`~/ProjectMosire` 同为该形态。

## 八、环境与运维（本机实测；换机器先看这一节）

- 工具链：**Java 21**；Maven `[3.8,)`；父 POM 是 `io.mosire:simos-parent`，**不继承** `io.mosire:mosire-parent`。

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
- ★★ **2026-09-27 实测（重启会抹掉长程模拟的全部中间产物）**：本机 16:38 重启过一次，`uptime` 归零的同时
  **`/tmp` 被整个清空** —— 正在跑的 600 天批次的服务快照、`--store`（SQLite 世界本体）、
  `h6raw_*.json` / `h6agg_*.json` 原始读数**一起没了**（`/tmp` 里的痕迹 **0 个**，事后无从判断跑到哪一天）。
  ⇒ ① **长程模拟的 store 与逐关账日读数要落在重启后仍存在的地方**，或**明确接受"重启即重跑"**
  （关账批每跑一个关账日就落一次读数，正是为了这个）；② 判"这批跑到哪了"**只能看产物**，
  不要靠"进程应该还在"；③ 重启后先 `uptime` + `pgrep`，再据产物决定"接着跑"还是"从头跑"。
- ★ **600 天关账批的实测参数（同一脚本内的约定，别照抄缺省端口）**：服务由
  `.superpowers/sdd/2026-09-27-economic-cycle-impl/h6sim_full.sh` **在自己的进程树里**起
  （★ 单独 `nohup` 起的服务会在**别的后台作业结束时**收到停止信号 —— 实测两次），端口
  **GUI 5827 / MCP 5725 / 审批 5723**，推进器 `../2026-09-26-year-one-simulation/v3curve_advance.py`。
  ★ 一批要 **几小时**（一次 `advance` 到下一关账日 + 一次逐格 dump），起它要用**受管后台作业**。
  ★★ **两批分属两个代码态，顺序不能反**：基线批跑**重打包之前**的 shaded jar（H5 代码），
  关账批要**先 `package` 出 H6 的 jar 再跑**；两批各用独立 store（`/tmp/simos-v3curve-h6` / `-h6r2`），
  对照由 `h6cmp.py <基线前缀> <关账前缀>` 出表（自比 `h6cmp.py tick tick` 应当恒 +0，先拿它冒烟自检）。
  ★ 服务在跑时**不要 `package`**（§二）：要重打包就先收工（`ps -eo pid,cmd | grep simos-shaded` 拿 PID ⇒ `kill`）。

### 8.2 世界数据的两层：bootstrap ≠ 世界初始化

- **空库 bootstrap**（`RichWorld`）只给**地图 + 区域**：`/api/map/overview` 的 `cities:0`、`/api/units` 的
  `{"units":[]}` **不是故障**。
- 人口 / 城市 / 三国军队要靠 MCP 工具 **`simos.worldgen.initialize`**（一次一批 = 一条 revision，`army` 缺省真，
  连带写 map/social/unit/sd 四域）。输入是 `config/worldgen/v17levant-nations.json`，`randomization.enabled`
  出厂 `false` ⇒ 取文档硬值（可复现）。
- 地形在 simos 里是**块**（`GameMap.terrainBlocks` = 同地形六邻连通分量）；`HexCell` 只存 **height**。

### 8.3 MCP 口与审批链（2026-09-24 起）

- **MCP 口 = GM 组**（用户裁定："MCP 和 GM Agent 处于同一权限级，想改什么改什么"），当前 **95 条工具**
  （2026-10-01 实测：读 23 + 窄写 52 + 通用/组合写 20；窄写 = 18 sd + 7 map + 26 unit + 1 actor；组合写含 P5 的
  `simos.unit.spawnArmy`）。就绪判据：启动日志里 `外发工具 N 个`；工具面与桶的对齐由
  `SimosToolsTest`/`McpServerTest`/`McpPortTopologyTest` 三处名单断言把守。
- **审批链两条**：**GM 面（MCP 口）的写"无脑过"**（`GmAutoApproveGate` ⇒ 直接批准、不登记待批）；
  **决策人链仍要人批**（`AutoApproveGate → ConfirmGate`，GM 在「决策 → 审批」点头）。
  ★ 两张面的 caller 桶都是 `DEFAULT` ⇒ **从请求字段上分不开**，只能按"用哪条 authorizer"分。
  ★ `scope=session` 会被 AgentLib 收窄成 `once`（`DEFAULT` 桶非 `sessionGrantable`）。
- 读工具默认**四桶共享**；只给 GM 的那些在类上标 `app.tools.GmOnlyRead`
  （2026-10-01 实测 8 条：`simos.map.path`、`simos.map.block`、`simos.sd.decision-makers`、
  `simos.sd.decision-maker`、`simos.sd.verdicts`、`simos.economy.ownership`、`simos.gm.tool-usage`、
  `simos.llm.providers`）。
- ★ **GUI 与 MCP 读工具共用同一份视图**（`app/gui/ApiViews` 是公开的视图层）——别再各写一份
  （那正是"同一资源的两个形状"的由来，见 `.superpowers/sdd/2026-09-22-tool-surface/m4-inventory.md` §二-6）。
- ★★ **追加（2026-09-27，M0.3）：`ApiViews.economyHex` 里多了一栏 `grainDiagnosis`**（逐格粮食诊断）。
  做得到四项：`coverageDays`（库存 ÷ **日耗**）、`importDemand`（= 本周期累计未满足）、
  `affordableGrain` + `purchasingGap`（钱 × 1000 ÷ 本格粮价，**是上限**——市场只算"既缺口又付得起"的家户）、
  `satisfactionPerMille` + `unmetPersonDays`。
  ★★ **算不出的三项具名列在 `unavailable` 里、绝不填 0**：`productionSelfSufficiency`（要本周期 ledger 的毛产/损耗/投入，
  而 ledger **当日丢弃** ⇒ 落点 M2 的市场读数组件）、`logisticsGap`（M2.4 之前无定义）、
  `paymentInstrumentGap`（货币工具属 M1）。★ 视图里的 `window` 字段**写出本周期需求量**，因为
  `importDemand`/满足率/人日都是**本期累计**（只有关账日读才是整周期的量）。
  ⇒ 报表脚本按格 dump 的那一份**自动带上它**（`h6sim_dump.py` 走的就是 `/api/economy/hex`）。

### 8.4 图片通路（让决策人 / 外部 agent「看图」）

- **发不发图由路由的能力位定**：`llm.routes.<name>.capabilities.vision=true`（缺省 **false**）。决策人绑哪条路由
  ⇒ 它能不能收图；**且同一个位**还决定 `simos.map.render` 的 `format=auto` 落在图还是字符图。
  配在 `<store>/agentlib/config.json`（GUI 的 provider 页能看见 `vision`，但**不编辑**它）。
- **工具反馈带图**：`simos.map.render` 的 `assetId` 由运行流折成一条**额外的 user 图片消息**（tool 角色带图是
  AgentLib 的响亮 CONFIG 错）⇒ 图随下一轮请求发给模型。
  ★★ **图片消息必须排在本回合「所有」tool 消息之后**（真网关实测：一个是回合里模型一次要了 5 个工具，
  中间插一条 user 会被拒 `HTTP 400: An assistant message with 'tool_calls' must be followed by tool messages…`）；
  回放式假客户端**不校验协议**、看不见这个约束，只有真网关会拒。
  ★ **成本提醒**：图片分片落进会话历史后会**每一轮都重发**（base64），会话越长越贵——目前不做裁剪，知悉即可。
- **开场快照**：`--opening-snapshot` 开（缺省**关**）⇒ 决策人会话**首次为空**时，先给它一张本国首府区域的渲染图
  （军队归属的决策人**没有**快照，取景语义未裁决）。开关开着而路由无视觉能力 ⇒ 跳过 + 日志一行 warn（不静默）。
- MCP 面（外部 agent）出图是另一条：工具的图片资产由 `AgentToMcpServer` 出成 `ImageContent`（P1）。

### 8.5 编制与移动（v2，2026-09-24 裁定）

- **`parent` = 编制归属；`attached` = 是否与父同属一支"一同移动"的编制**（不再表示"位置由父继承"）。
- **顶层**：沿 `parent` 上溯到 `attached=false` 或**无父**的那个单位。**只有顶层能移动**；它一动，整支
  （全部 `attached=true` 的后代）**一起到同一格**（推进器显式搬），**速度取支内 `effectiveSpeed` 最小值**（含状态折算）。
- 成员自己下路线 ⇒ 拒，理由**点名顶层** + 指路（控制顶层 / 拆出来）。
- **进入编制必须同格**（`attach` / `merge` / `createUnit` 各自判）——这就是"跟随"取消后的代替品：
  **要合并就回同格**，不再有"不同格偏移式加入"。
- **位置永远是各单位的自己的**：没有位置 = 不知在哪（**不再向父取位**）。`offset` 与 `unit.SetFormationOffset`
  保留但**不再影响任何计算**。
- ★ 决策人**要派兵单独出去，必须先拆**（`unit.SplitFormation` / `unit.DetachUnit`）；三条令里"给下挂单位下路线"
  这种写法会被正当拒绝（三国那轮实测就是这么撞上的——见 `.superpowers` 之外的本节）。
- ★★ **同一 tick 内的"改编"是覆盖、不是拒**（`setOrAppend`，后写者胜）：创世段就在 tick 0、命令也落在 tick 0 ⇒
  第二条写会撞 `SegmentedSeries` 的"段必须按 from 严格升序"。**世界不推进时间时，任何第二次改编都会撞**——
  这是 live 跑出来的（三国在 tick 0 的 `unit.DetachUnit` 全被拒）。`attach`/`detach`/`reparent*` 都已走同刻覆盖。
- ★ **巡逻环线允许**（2026-09-24 裁定）：`Route.path` 可以回到已走过的格（`waypoints` 末点可等于首点），
  **走完一圈即停**（不是永久巡逻）。旧不变量"path 不得有重复格（R4）"已作废。
- 设计原文与取代声明：`docs/superpowers/specs/2026-09-24-formation-v2-design.md`。

### 8.6 换设备自检（本机踩过的坑，按序做）

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
6. **"按外壳 / 按树 / 按机器"的东西别照抄**：子代理类型与模型（在 Claude Code 里照抄 opencode 的
   `deepseek-flash-go` 会直接报 `not found`；那条禁令的对象是**那个模型 V4 Flash**，不是"不许派子代理"——
   该派就派、用本外壳的默认类型）、`~/.m2`、`.superpowers/.gitignore` 同族 ⇒ 换外壳 / 换 worktree 先核一遍再照抄。
   ★ 本仓常同时有主检出与 worktree，**同名文件分属不同分支**（`CLAUDE.md` 曾是重灾区：拿主检出的绝对路径去改，
   编辑成功了、改的却是另一棵树里的旧版）⇒ 改文件一律用**本树**的绝对路径，改完 `git status` 确认变的是本树。

## 九、实现与评审期间**实测**到的失败形态（2026-09-25，经济线 plan1/plan2）

> ★★ 本节全部**真发生过**，不是推演。载体：`docs/superpowers/plans/2026-09-25-aggregate-economy-v2-plan{1,2}.md`、
> `.superpowers/sdd/2026-09-25-aggregate-economy-v2-plan{1,2}/progress.md`（逐条 ruling 与实测数字）。
> **★ 本节 9.1~9.4 的每一条，都是子代理抓出来的——控制器自审一次都没看出来。** 这不是自谦，是选型依据：
> 自审与"写"共用同一个心智模型，"评审"必须换一个上下文。

### 9.1 判别力的三种**假货**（最贵，因为测试照样全绿）

| 形态 | 长什么样 | 实测证据 |
|---|---|---|
| **幻影判别力** | 注释写"X 承担判别力"，而 **X 根本不存在** | `PLAINS_HARVEST_NET` 的注释声称 `EconomySeederTest.realScaleHexIsSelfSufficientWithinTheCalibratedBand` 守着自给率——全仓 grep **只命中那句注释本身**。计划里写了它的源码，控制器改期望值时**漏建** |
| **假判别力** | 注释写"X 失效它会红"，实测**不会红** | 低丘格注释声称"地形系数若失效（低丘=平原）它会红"——实测低丘的瓶颈在**劳动**（可经营 1,745 亩 < 可用 2,064 亩），系数改 1000‰ 后两格**照样不等** |
| **放宽断言** | 把精确断言降级成关系断言 | 低丘的精确净产被换成"两格不相等"（计划 Step 5 明文禁止）⇒ 后按子代理给的闭式钉回 `99,377,750`，**一次通过** |

**纪律**：写下"判别力由 X 承担"之后，**当场核实 X 存在、且真的会红**；核不了就改写成不声称的口径。
**为什么**：这三种都不会让测试变红——它们让**测试看起来比实际强**。这是 §三「没红也要问为什么没红」的反面。

### 9.2 计划里的数字是**预测**，不是事实

`Expected:`、变异轮"会红几条"，实测**屡次不符**（plan2：说红 1 条实际 2 条；说会红的没红；说 1 条而端到端红了 3 条）。

**纪律**：执行时 `Expected:` 与实际不符 ⇒ **先核算式、先查被测物**；**不许**把 Expected 改成实际值。
**为什么**：那会让用例退化成同义反复（断言恒真）。计划是**论证**，权威是 spec 与代码。

### 9.3 夹具与不变量的三种冲突（★ 三次都不是"护栏太严"，是**夹具本身违反新口径**）

| 冲突 | 病灶 | 处置 |
|---|---|---|
| 槽位上限 vs 行参与率 | `EconomyCodecTest`/`EconomyRoundTripTest` 的槽位 `700/300` 是 R1.1 修正**前**的"人口占比"旧语义，被读成"投入率上限"后与行的 `800` 冲突 | **修夹具**（改 `950/900`），**不放宽护栏** |
| 逐组件变异 vs 跨表引用完整性 | `EconomyRoundTripTest.mutate` 从 `EconomyData.empty()` **单改一个组件**；而新不变量要求"债务两端 ∈ classes"+"行内 debts ⊆ 债务表" ⇒ 单改任一**必然非法** | 变异体**自带支撑组件**（用例只断言"目标组件进了变更集 + 往返相等"，多带支撑不破坏断言） |
| 真档量级 vs 小夹具 | `EconomyTestWorld` 是 **1,000 人/格、储备 5,395,000 毫粮**，而真档满种要 24,784,000 ⇒ 配种子会把口粮当种子播光 | **保持该夹具未配种子**，让它当"未配种子的对照格"；配种子的字面量放**真档量级**。诊断轮实测：第 1 天扣光 5,395,000、第 120 天只收 43,803,260（674 亩）、第一周期饿死 **200/1000** |

**纪律**：新护栏打红既有用例 ⇒ **先判"护栏错"还是"夹具错"**，多数是后者；修夹具，并把"为什么这个夹具必须长这样"写进注释。
**为什么**：选"放宽护栏"消掉**判别力**，选"改口径迁就夹具"消掉**正确性**——两条都是净损失。

### 9.4 工具与读取：假阴性（§三 那条**又被踩**）

| 形态 | 实测 |
|---|---|
| ★★ **`find -newermt '-5 minutes'` 在本机无效** | 本机 `find` 是 **`bfs`**：该写法报 `Invalid timestamp` 并**静默返回 0 命中**；而当时用了 `2>/dev/null` **把错误吞了** ⇒ 拿"零活动"当证据报给用户。`-mmin -5` 与绝对时间戳写法正常 |
| **"未配种子 ⇒ 颗粒无收"原先只有算式证明** | 后由端到端变异轮**实测**：`176,545,000 → 0`，且饿死回来 |
| **"字面量没改"不如"md5 未变"硬** | 前者靠人看、可自欺；后者可机械核对——实测 `EconomySettlementEndToEndTest` 的 md5 `fe489310…` **逐字节一致**，这才叫"行为恒等"的证据 |
| ★★ **把"5 天采样"当成年度特征**（第 4 次同族错） | 缺口（`FlowRow.unmetNeed`）**按周期清零**，而 tick 365 = 第 4 周期的**第 5 天** ⇒ 读到的是 **5 天的发生额**。据此写了"霍赫兰缺口 0"并当国别特征横向比三国——整周期真值是 **1,760,828 粮**。**读数对、公式对、口径也对，错在没核这个数的统计窗口**；且同一列三个数都是 5 天口径，却被当成可比的国家差异 |

**纪律**：报任何"某国 / 某格 / 某指标 **= 0**"或"最小 / 最大"之前，先核三件事：
① 这个数的**统计窗口**是什么（时点快照？本期发生额？历史累计？）；
② 同表其它列**是否同一窗口**（不同窗口的数不可并排比）；
③ 窗口**是否随周期/推进被清零**（`FlowRow` 全族都按周期清零）。
**为什么**：这是本会话第 4 次同族错（前三次是 §9.1 的两条判别力假货、§9.4 的坏仪器），
**每一次都不是算错，而是"没核自己读的是什么"**——它比算错更难自查，因为数本身是真的。

**纪律**：报"零命中 / 零活动"之前，**先造一个已知会命中的样本证明工具在该写法下有效**；**禁止 `2>/dev/null` 吞工具错误**。
**为什么**：§三 早已有这条（"命中 0 先怀疑自己的正则 / 读取"），本轮**又被踩** ⇒ 它会反复发生，不是一次性疏忽。

### 9.5 流程：技能是默认路径，不是判据（用户指令可能已让某条规矩失效）

| 形态 | 实测 |
|---|---|
| **执行模式选错** | `writing-plans` 交接要求二选一，控制器选了 `inline`（自己写代码）⇒ **烧掉控制器的上下文**——而那正是用户一直在省的东西 |
| **机械执行技能、不回头核对用户指令** | `writing-plans` 硬性要求"代码写进计划"，控制器照做三份（48 / 94 / 113 KB）；而用户早已说"评审可以等代码全写完再做" ⇒ 该规矩的**唯一价值（实现前评审）已被抽掉**，剩下的只是把同一份代码写两遍 |

**纪律**：**每批开工前回头对一次用户指令**，看有没有哪条技能规矩已被它的效力抽空；被抽空的就跳过。
**为什么**：这两条都是**照规矩办事**造成的损失，不是偷懒造成的 ⇒ 自查时最不容易起疑。

