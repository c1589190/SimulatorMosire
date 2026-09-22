# agent-permission 阶段台账

> **阶段**：决策人权限组与可见范围
> **设计**：`docs/superpowers/specs/2026-09-22-agent-permission-design.md`
> **计划**：`docs/superpowers/plans/2026-09-22-agent-permission-plan.md`
> **工作树**：`/home/cna/SimulatorMosire`（**主检出**，分支 `ts/m1`；本机**没有** `ts+m1` worktree
> ——`git worktree list` 实测。★ 换机器先核这一条，见 CLAUDE.md「哪一份是按树的」）

---

## 〇 起点

- 起点提交：`92b8a73`（spec + 计划）→ `f88f01d`（+§2.3 运行流 / Task 11B / 验收节）
- 本阶段**尚未跑过**全量门禁；上一次全量绿轮是 `dc69f5c`（**1449** 用例 = 170/369/45/259/179/141/286、
  rc=0、8/8 SUCCESS、前端 204/204、**75 s**——本机 `nproc=8`，不受 Bash 600 s 线约束）

## 一 用户裁定（2026-09-22，本阶段）

| # | 裁定 | 出处 |
|---|---|---|
| ① | GM 身份**保留** `AgentIdentity.external()`（审批面语义，与权限级别正交） | 用户「1、对」 |
| ② | 资源路径语法：国家 = tag 为 `nation:<id>` 的区域；军队 = 位置 + 视野半径的函数值 | 用户「2、如我刚刚说的」 |
| ③ | 新命令 `sd.SetDecisionMakerAccess` **取代** `sd.SetViewScope` | 用户「3、前者」 |
| ④ | 计划**写全** | 用户「4、写计划写全了啊？」 |
| ⑤ | `visionRadius` 缺省 **1** | 用户「5、对」 |
| ⑥ | 视野**纯半径**，地形遮挡不做 | 用户「6、你真能写出来地形遮挡算你牛逼，写不出来就用纯半径」 |
| ⑦ | **不改 AgentLib**：决策人运行流在 Simos app 层自建 | 用户「尽可能还是不要改」＋实测（`AgentPipeline` 绑 Brain 抽象，搬不动） |
| ⑧ | 决策人**自己调工具 + 跨 tick 上下文** | 用户「对，就是要决策人自己调工具、带上下文」 |
| ⑨ | 自主推进全流程（不必逐步请示） | 用户「没问题的话你自己干完全流程」 |

## 一之二 ★ T1 的裁定：旧档兼容缺口（**记遗留，不现在修**）

**事实**（T1 实现者用合成探针实测）：`Unit` 的 `visionRadius` 是 `int`，Jackson 对缺失的 primitive 填 `0`
⇒ **旧档（无该键）读回来是 0（= "只看自身格"），不是缺省 1，且完全静默不抛**。
影响方向是 **fail-closed**（改造前存的档里的军队可见范围**变窄**，不泄密）。

**裁定：不修，记为带裁定的遗留。** 理由：
1. 影响 fail-closed；本机**没有生产档**（M8 T12 实测记账：全部 e2e 跑在 `--demo` 合成小世界，
   真档 `v17levant` 是**地图**档）；
2. 本轮验收会**新建**世界，不读旧档；
3. 修法两条都不便宜：`@JsonCreator` 逐参工厂 = GSimulator `MapDiff` "手工对着类型维护"的**同形物**
   （加第 15 个分量时静默停止填充）；改类型（`Integer`/`Optional`）改动面大；
4. **真要修时的正确路径已明确**：`UnitCodec` 读路径"缺键补 1"的**就地迁移**（M9 T6 的 `MapCodec` 先例）。

★ 另记两条 T1 自己声明**未核实**的（不改判据）：
- **`visionRadius` 目前没有生产读者**（`ArmyScope` 属 T4）⇒ "经真 `Shell`/MCP 端到端能存能读"**未验**（只到模块级）。
- **命令面无法设置视野半径**（`CreateUnitHandler` 不改载荷是有意：spec §4.1 只说缺省 1）⇒
  若 T4 需要"按单位配视野"，要**另加**可选载荷字段（届时评估）。

## 一之三 ★ T2-T4 的三条裁定（实现者报上来请裁的，**三条均同意**）

1. **范围函数只表态 `map` 命名空间**（spec §3.2 还给 NationScope 列了 `unit`/`social`）——**同意**。
   理由：控制器本轮已裁定"暂不改 `requireUnitRead`/`requireSocialRead` 的字面量 `"*"`"，
   而**粗断言 + 细围栏 = 整调被拒**（AgentLib 卡点 A）⇒ 此刻配 `unit`/`social` 会把那两类读工具**整体拒掉**。
   `unit`/`social` 的细粒度化随 **T10 同轮**做。
   ★ 实现者给了**实测反证**：`ScopeFenceTest.aCoarseAssertionAgainstAFineFenceIsDeniedWholeCall`
   证明 `map.overview` 今天的粗断言（`map:<mapId>`）落在这条细围栏**之外** ⇒ **T2 与 T10 必须成对上线**。
2. **算不出来 = 看不见（fail-closed）而非抛**：军队不在 sd / 单位根不在 unit / 单位无有效位置 ⇒ 显式 `none()`。
   **同意**——依据是**既有同口径**：`RedactingQueryService.ownUnitIds` 对未知 actor/军队同样给空集
   （`RedactingQueryServiceTest.unknownActorIsFailClosedToEmptyScope`）；spec 未规定此格，从既有口径。
3. **六角球是纯几何**（不按地图成员过滤）——**同意**。spec 把"R=1 ⇒ 7 条 / R=2 ⇒ 19 条"写成常量、隐含不依赖地图；
   地图边缘会拿到若干**不存在的格**前缀，无害（那里没有资源可读）。

★ 实现者另外两条**如实记**（不是缺陷，但别误读）：
- 本轮产物**尚未接线**——没有任何生产代码消费 `DecisionScopeFunctions`（消费方是 T6/T10）⇒
  **端到端一行未验**，别把"34 条绿"读成"决策人权限已经生效"。
- `ScopeFenceTest` 里 `anEmptyScopeMapIsNotADenial` / `anExplicitNoneDeniesAndTripsThePreGate` 断言的是
  **AgentLib 自己的语义**（钉住依赖行为），**不是**本产物的护栏、没有针对性变异体。

## 一之四 ★ T5-T8 带回的两条**实测发现**（控制器裁定）

**发现 1：决策人今天根本出不了令 —— 写工具的粗断言撞细围栏。**
实现者探针实测：`sd.IssueDirective` 走通审批后**死在资源层**，拒因原文
`WRITE map:Map1 不在调用者的可达面内: 调用者可达=Map1/region/701`。
根因：`AbstractNarrowWriteTool` 的 `requireAllWrite` 要 `map:<mapId>`（**粗**），而国家范围是**区域级前缀**（**细**）
⇒ 粗断言撞细围栏 = **整调被拒**（spec §5.2 第 2 条）。
⇒ **裁定：T10 范围扩大** —— 不只改读工具，**写工具的断言也必须成对改细**。
这是 **T11B（决策人运行流）的硬前置**：不修则决策人连出令都出不了。

**发现 2：`ResourceDeniedException` 被工具自己吞掉、拒因被降级。**
`AbstractNarrowWriteTool.execute` 的 `catch (RuntimeException)` 把 `require` 抛的拒因折成 `TOOL_ERROR`
⇒ `ToolCallAuthorizer` 边界上的 `catch (ResourceDeniedException) → RESOURCE_DENIED` **永远收不到**
⇒ 模型看到的是"命令提交失败"而不是"换个资源就行"——AgentLib 特意分的两个码的意图**丢失**。
⇒ **裁定：并入 T10 修**（在 `catch (RuntimeException)` **之前**加
`catch (ResourceDeniedException e) { throw e; }`）。两条都会改动写工具族 ⇒ 按裁定 42 **连带重跑**受影响变异轮，同轮做完省一轮。

★ **如实记**（不是缺陷）：实现者自陈 **T7 的 TDD 红轮没在实现前采到**（测试改动与实现同轮落地），
替代证据是变异轮 m5/m7 在**最终字节**上证明那两条守卫会红——与 T5/T6/T8 的"先红后绿"**不同强度**，别当同等证据用。
★ 另一条**接缝**：决策人 caller 在生产路径上**还没有调用者**（T11B 才装配），且 `Shell` 已不再持有
`DECISION_AGENT` 注册表 ⇒ T11B 需要时用 `shell.toolsFor(Role.DECISION_AGENT)` 或自建注册表。

## 一之五 ★ T10 的裁定与它自己抓到的缺陷

**实测验收面**（真 `Shell` + 真工具链 + 真审批）：国家决策人 `map.overview` 只剩本国 2 格、
`map.hex(越界)` ⇒ `NOT_FOUND` 且消息与"真不存在"**逐字同模板**；军队决策人方向相反
（两类范围函数在夹具里故意分叉，"写成同一个"当场红）；**`sd.IssueDirective` 经真审批链提交成功**（head 1→2）。

**控制器裁定四条**（实现者报上来请裁）：
1. **删 `requireUnitRead`/`requireSocialRead`** —— **同意**（读侧改走 `allows` 后无调用点；留无人调用的公开助手是诱饵）。
2. **`population` 判据用 `social:<q>_<r>`** 而非 `resourceHex` —— **同意**（既有断言钉死其 manifest == `SOCIAL_READ`，
   `ResourceAuthorizer` 对未声明命名空间判拒 ⇒ 在它里面查 `map:` 恒拒）。
3. **`hexVisible` 两条通道取并集** —— **同意**（国家范围是**区域级**前缀，段边界下不含 `Map1/hex/…`；
   只按 hex 判会让国家决策人读不到本国格，与 spec §3.4 矛盾）。并集只在可达面内取、不放大权限。
4. ★ **"署名 B"的洞要修** —— 载荷的 `decisionMakerId` 与调用者身份**无交叉校验** ⇒ 决策人 A 可落一条
   **署名 B** 的 directive。违反 spec **N16**「渠道**不得冒称**任意 actor」的精神 ⇒ **归 T11B 同批修**
   （T11B 正是决策人下指令的路径，且它改 sd 域 ⇒ 连带重跑 sd 变异轮）。

★ 实现者自己抓到并修掉的 **fail-open**（值得记）：`IssueDirectiveTool` 的 sd 缺省策略原为 `UNRESTRICTED`
⇒ "调用者**未表态** sd"回落成**放行**（spec §5.2 第 3 条：空 = 不表态 = 放行）⇒ **忘了配前缀反而拿到 sd 全量写权**，
失效方向是**放宽**。已改 `READ_ONLY`（fail-closed），并补用例把方向钉死。

## 二 任务状态

| 任务 | 状态 | 备注 |
|---|---|---|
| **T1** unit `visionRadius` | ✅ **已完成** | 门禁 **1478**（基线 1449，unit +28 / app +1）；**11 变异体全 KILLED**（`maven_rc=1`×11、SURVIVED 0、`restored_identical=True`×11）；证据 `t1-evidence/`（`verify-final.log` md5 `cb509d96…` 与报告自报逐字相同） |
| **T2+T3+T4** 路径语法 + 两个范围函数 | ✅ **已完成** | 打包一派（三者紧耦合）；门禁 **1512**（+34 恰为 5 个新用例类）；**9 变异体全 KILLED**、0 存活、0 VOID；证据 `t234-evidence/`。裁定见 §一之三 |
| **T5~T8** 权限组落地（GM 组 + 决策人 caller + 撤 5717 + 收窄工具面） | ✅ **已完成** | 门禁 **1521**（+9）；**8 变异体全 KILLED**；证据 `t5t8-evidence/`。★ **带回两条实测发现**，裁定见 §一之四 |
| T9 sd `accessLimit` 取代 `viewScope` | ⏸ | **最大的一块**：影响面实测 **main 25 + test 24 文件、224 处引用** ⇒ 拆 9a/9b/9c 三步派单（见 plan） |
| **T10（扩展版）** 细粒度断言与过滤 | ✅ **已完成** | 门禁 **1538**（+17）；**22 变异轮全 KILLED**（含裁定 42 连带重跑 12 条旧轮）。★ **实测**：决策人读得到范围内数据、越界与"不存在"同款；**出得了令**（head 1→2）。裁定见 §一之五 |
| **T11+T11B** 派生信息 + 决策人运行流 | ✅ **已完成**（`ac42290`） | 门禁 **1573**（+35）；**14 变异体全 KILLED**。★ **实测 J11 通过**：模型 `toolCall(map.hex) → toolCall(sd.IssueDirective) → 文本`，head 1→2、会话带真值 `desert`；**第二轮 run 的首个 LlmRequest 逐值带着上一轮 3 条消息**（断言"喂进去了"而非"存下来了"）。裁定：`HexOwner` 不按区域过滤**接受**（见提交信息）；`neighbors` 收窄**归 T9** |
| **T11C** 装配运行流 + 触发口 | 🔄 子代理在跑 | 运行流此前**无生产调用者** ⇒ 验收做不了。新增窄工具 `sd.RunDecision`（GM 桶，返回"最终文本 + 工具调用轨迹"= 验收判断"决策人看见了什么"的载体）+ 接 `LlmProviderResolver`（未绑定 ⇒ 响亮失败）+ 连带 catalog/PAYLOAD_HINTS/McpCoverageTest |
| T11 邻国 / hex 归属国家 | ⏸ | |
| T11B 决策人 agent 运行流 | ⏸ | 会话落 `ConversationStore`（J11） |
| T12 护栏 + 端到端 + 关账 | ⏸ | |

## 三 验收（用户指定，改造完成后执行）

真 LLM（`config/llm-providers.json` 的 `mosire-flash` = `121.40.130.178:3000/v1` / `deepseek-flash`）
＋ **Python 模拟的外部 agent 连 MCP**（控制器自己起客户端）：

> 建国家 → 建军事单位 → 建**两个**决策人（国家/军队）→ 决策人报告"自己能看见什么" →
> 在 MCP 里判定（对 ⇒ 成功；权限出错 ⇒ 失败）→ **发现问题当场修、重跑**，直到无新发现。
> 另测**上下文沿用**：推进时间线，不同 tick 让同一决策人用同一上下文做多轮工具调用与决策。

详见 plan 的「验收」节。

---

## 四 验收现场记录（2026-09-22 实跑：真 LLM + 自写 MCP 客户端）

**环境**：`java -jar simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar --store /tmp/acc1 --demo`
（`--demo` 现在种的是**富世界**：59223 hex / 252 区域）；MCP `127.0.0.1:5715/mcp`、GUI 5711、审批 5713。

**工具**（都在 `e2e/`）：`mcp_client.py`（**标准库自写**的 MCP 客户端——本机 Python 是 externally-managed
且**没装** `mcp` 包）、`auto_approver.py`（自动批准器）。

**已跑通**：
- MCP 连通（协议 `2025-06-18`）、`tools/list` = **56 条**（含新的 `sd.RunDecision`）；**读工具不经审批**（实测）
- **审批链路**：★ 现场踩到并修掉——批准器 POST 枚举名 `APPROVE_SESSION` ⇒ **HTTP 400**；
  AgentLib 的线格式是 `{"decision":"approve","scope":"session","by":…}`（`scope` 是**独立字段**）
- **建世界**（全经 MCP，head 2→9）：`unit.CreateUnit` → `sd.CreateNation` → `sd.CreateArmy` →
  `sd.CreateDecisionMaker`×2（Nation / Army）→ `sd.SetDecisionMakerProvider`×2（`mosire-flash`）

**验收发现（现场实测，逐条待修/待记）**：

| # | 发现 | 性质 |
|---|---|---|
| **1** | ★ **真 LLM 首轮直接失败**：`sd.RunDecision` 返 `result=failed, llmCalls=0, reason=LlmException, detail="…HTTP 400: field messages is required"`——首次请求 `messages` **为空**（首轮会话空且无 system prompt）。★ 错误**被正确报告**（不是静默） | **真缺陷** ⇒ ✅ **已修**（`c884a22`：注入并**落盘**决策人身份 system 消息，6 变异体全杀、门禁 1589） |
| **2** | 富世界的区域 tag 是**字面量 `'Nation'`**（**无 `nation:` 前缀**）⇒ `sd.CreateNation` 的 homeRegion 前置**不满足**、**建不了国家**——正是 M6 的 D-5 连带①预言的"先有鸡还是先有蛋" | **既有缺口**（D-5 未裁未做）；本轮验收用 `map.UpdateRegion` 手动补前缀绕过 |
| **3** | ★ **工具名含 `.` 被 LLM 端点拒**（修 #1 后暴露）：`Invalid 'tools[0].***.name': … '^[a-zA-Z0-9_-]+$'` | **真缺陷** ⇒ ✅ **已修**（`7d519f0`：送出 `.`→`_` 转义 + 回来映射 + 碰撞响亮失败；5 变异体全杀、门禁 1602） |
| **4** | ★ **真 LLM 跑通整条链，但两处要调优**：模型自述「single nation `osman`, one region (**573 hexes**), one unit `u-army1` … **neighbor polity `cicilia`**」⇒ 权限/派生信息全对；出令用了 `sd.RegisterEffect` ⇒ 被 `DirectiveWhitelist` **正确拒绝**（防递归）；重试时 **8 次预算用尽** ⇒ `abortedByBudget`（设计如此、不静默） | **调优**（非缺陷）：① 预算对真模型偏紧；② prompt 未告知"commands 不得含 `sd.*`" ⇒ 已派单 |

★★ **验收已实测成立的东西**（真 LLM + 真 MCP，这是本阶段最关键的一组正面证据）：

- 决策人**自己调工具**：一轮里 8 次 LLM 调用、多次工具调用（catalog / branches / overview / unit.list /
  unit.get / map.hex / facets / resolve / population），**不是被动收简报**（J11 ①）；
- **权限过滤真的生效**：`simos.map.overview` 给的是 **`hexCount=573`**（本国国土），**不是全图 59223**；
- **J5 邻国**：模型自己报告了 `neighbor polity cicilia`；
- **J6 归属国**：`simos.map.hex` 的返回带 `nation`；
- **工具名线格式**：会话里是 `simos_map_hex` 这类线名，且映射回真名执行成功；
- **失败对模型可见**：出令被拒时它拿到 `REJECTED` + 中文理由（**不静默**）；
- **跑飞有兜底**：预算用尽 ⇒ `abortedByBudget=true`（**不静默截断**）；
- **跨 tick 上下文**：会话逐条落 `conversations.db`（35 条在案）。

## 四 本阶段的环境事实（本机实测，避免照抄别台）

- `nproc=8`、内存 11 G；`~/.m2` 的 agentlib jar **134 类**（判据看类数，不看时间戳）。
- 全量 `clean verify` **75 s**（不是 `nproc=2` 那台的 991 s）。
- 本机**没有** `ts+m1` worktree ⇒ 改 `CLAUDE.md` 用主检出路径即可（那条 worktree 纪律是为另一台写的）。
- 子代理类型：**默认**（本机无 `~/.claude/agents` 定义，`deepseek-flash-go` 在 Claude Code 里不存在）。
