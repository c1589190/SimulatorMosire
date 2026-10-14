# GM ⇄ DM 自主问答轮次：约束设计书

> **状态**：**§9 四条已全部裁定（2026-10-14）** ⇒ 本文件即为派单依据（§一.8 约束设计书）。
> **责任区**：`simos-ga`（运行流 + 决策族工具）+ `simos-ga-spi`（资源/角色契约）+ `simos-app`（装配、权限面、GM 工具）。
> **前提**：**不改世界状态类型**（`GaState` 不加组件）、**不新增命令类型**（全部复用 `ga.PutInfo` / `ga.UpsertDecisionPacket` / `ga.SubmitDecisionPacket`）、**不改铁律**。

---

## 0. 用户原话附录（逐字，不改写、不缩写、不意译）

**2026-10-14（本次，设计目的）**：

> 「我认为DM-GM的流程可以自由一些，本质上就是GM问，DM答，同时DM问，GM再答，问答到最后DM确认决策，然后GM推演、反馈给DM，DM确认推演，进入下一轮，这样的轮次本身其实是交给GM自主行动的，我们的工具设计应当尽可能满足这样的工作流
> 因此，首先DM要允许通过工具把问题打给GM
> 其次，DM要允许通过工具确认之前的所有决策
> 然后，DM不是已经有那种工具调用决策，和通用的自然语言决策了吗？要允许自由覆盖/修改；
> 最后……根据设计目的，你自己想想看吧」

**2026-10-14（同日，权限等级）**：

> 「现有的ga系列命令，权限等级我记得只有三个吧？分别是GMonly、DM经审批执行、DM无条件执行，这些命令允许DM进行基本的决策-提交流程，很显然属于DM无条件执行，并且必须写进ga的决策流程里；能不能简化一下决策流程？决策人一轮运行产生多个工具决策是很正常的」

**2026-10-14（同日，sd 退役口径，本次调查确认仍在生效）**：

> 「**sd整个弃用！**」「**`sd:*` 地址族 → 直接扔**」

**2026-09-24（GM 面无脑过）**：

> 「为啥这种 GM 级命令要额外审批？改成 MCP/GM Agent **无脑过**」

**2026-10-14（本次裁定，§9 四条）**：

> 「C，这个上限太傻逼了，至少改成128，其他按你说的做」

（A / B / D 三条与设计书 §9 的推荐值逐字相同，用户答"其他按你说的做" ⇒ 裁定生效。）

**2026-10-22（回合统一结算的由来，本次要改的对象）**：

> 结算从"LLM 调用 submit 的时刻"改到"回合结束之后"（`DecisionTurnFinalizer` 类注）。

---

## 1. 设计目的

**GM 是唯一的驱动者**。DM 与 GM 之间不是"一次提交、一次裁决"的单向漏斗，而是**一个由 GM 自主编排的多轮问答**：

```
        ┌─────────────────────────────────────────────────────────────┐
        │  GM 问 ──► DM 答 ──► DM 问 ──► GM 答 ──► …（任意轮）        │
        │                    │                                        │
        │                    ▼                                        │
        │              DM 确认决策（本 tick 的全部决策定稿）           │
        │                    │                                        │
        │                    ▼                                        │
        │              GM 推演（合并执行 → 世界推进）                  │
        │                    │                                        │
        │                    ▼                                        │
        │              GM 反馈 ──► DM 确认推演 ──► 下一轮              │
        └─────────────────────────────────────────────────────────────┘
```

三条推论（本设计书的全部内容都由它们推出来）：

1. **"一次 DM 回合"不再等于"一次决策"**。一个 tick 内同一 DM 会被跑**很多轮**（问答几轮 + 确认一轮 + 推演后一轮）。轮次边界由 **GM** 掌握，不由运行流掌握。
2. **决策表达是 DM 的自由**：写、改、删、覆盖、确认、撤回，全程不落世界效果 ⇒ 全属**类3 `DM_FREE`**（用户同日的权限裁定）。
3. **世界在"问答 + 确认"期间不动**（GM 只在 DM 确认之后才推演）⇒ 现有的**冻结快照语义正好就是这套流程要的**，不用改（§2.1 第 5 条）。

★ **诚实的边界**：本轮**不**引入"防止 GM 在问答中途推演"的强制机制。GM 是自主行动方，工具只**报告**"谁还没确认"，不替它踩刹车（与用户「触发器本身只管是否被触发」同口径）。见 §9 待裁项 D。

---

## 2. 现状核查（回代码核实，逐条 `file:line`）

### 2.1 已经支持的（**不要重做**）

| # | 能力 | 证据 |
|---|---|---|
| 1 | **同一 tick 可反复跑同一 DM**（问答多轮的结构前提） | `ga.RunDecision` 的 INFO 条目**不带显式 id** ⇒ `SdInfoIds.synthesize(地址, 序号)` 合成 `ga:decision.<dm>#<n>`（`RunDecisionHandler:104-109`、`SdInfoIds:29`）。`PutInfoHandler` 只对**显式给的** id 做命令期查重（`PutInfoHandler:46-48,130-133`）⇒ 同 tick 第二轮**不会撞 id** |
| 2 | **GM→DM 的推演反馈已有**（结构化） | `sd.AdjudicateTick` 落 `ga:adjudication.<tick>`，`key=result`，`tags=<involved>`（`AdjudicateTickTool:144,766-780`）；DM 侧 `sd.DecisionResults` 按 tags 可见性读（`DecisionResultsTool:44-57`） |
| 3 | **DM 的对话历史跨轮保留** | `conversations.load/append`（`DecisionAgentRunner:1052,1106`）；开场消息只在**空会话**注入一次（`:1057`）⇒ 第 2 轮起 DM 记得自己问过什么 |
| 4 | **NL 决策已可整体覆盖** | `simos.sd.packet.intent` = `existing.withIntent(text)`（`PacketIntentTool:153-155`）⇒ 整段替换，不是追加 |
| 5 | **冻结快照语义与"问答期间世界不动"一致** | `PACKET_WRITE` 分支：`args.put("branch", frozen.branch())` + `args.remove("expectedRevision")`（`DecisionAgentRunner:1217-1222`）⇒ **世界读**钉在触发 revision，**包写**总是取当下 head ⇒ 包能在更新的 revision 上继续累积、而世界对 DM 保持不变 |
| 6 | **包状态机 = DRAFT / PENDING 两态**（"确认"就是这个动作） | `PacketStatus`；`ga.SubmitDecisionPacket` 仅 DRAFT→PENDING（`SubmitDecisionPacketHandler:81-90`）；`packet.submit` 对 PENDING 幂等（`SubmitPacketTool:138-140`） |
| 7 | **`DecisionPacket` 已备好改/删所需的 wither** | `withIntent` / `withCalls` / `withStatus`（`DecisionPacket:91-104`） |
| 8 | **`ga.UpsertDecisionPacket` 是整包覆盖、无状态机**（撤回用不着新命令） | `UpsertDecisionPacketHandler:93,132-136`：status 从载荷取、整包替换、同 id 幂等 |
| 9 | **GM 读全部 INFO**（不受可见性谓词约束） | `RedactingQueryService.infoVisibleToDecisionMaker` 类注：「**GM 不走本方法**：解不出决策人身份时读全部」（`:456-459`） |
| 10 | **GM 拿得到某个 DM 的全部决策** | `simos.gm.packets`（摘要 `{id,tick,proposerId,status,callCount,toolNames}`，`GmPacketsTool:58-61`）+ `simos.gm.packet`（全量 intent + 逐 call 的 args/preview/targets/status，`GmPacketTool:52-56`） |
| 11 | **每 tick 每人一个包** | id = `pkt-<dmId>-<tick>`（`DecisionTurnFinalizer:131-132` 等）⇒ "本 tick 的所有决策"有天然容器 |
| 12 | **消息落点已在 ga 地址族、零新状态** | `ga:message.<from>.<to>.<tick>`，`key=message`，`tags={from,to}`（`GaMessageSendTool:198-221`） |

### 2.2 缺口（**本设计书要补的**）

| # | 缺口 | 证据 |
|---|---|---|
| **G1** | **DM 问不了 GM**：`simos.ga.message.send` 的收件人**必须是另一个决策人**，`to==自己` 当场拒；唯一的授权命令要求**两个 id 都是已存在的决策人**，而 GM 不是决策人 | `GaMessageSendTool:179-183,271-273`；`SetDecisionMakerPeerHandler:105-111` |
| **G2** | **GM 收不到 DM 的信、也回不了话**：`gm.*` 一族里**没有任何**能读 INFO 或发信的工具（`gm.packets`/`gm.packet`/`gm.approvals`/`gm.tool-usage` 全是别的用途）；`GaMessageSendTool.roles()={DM}` 不进 GM 桶 | `grep simos.gm.*` 全量见 §2.3 |
| **G3** | **结构化决策只能追加，不能改/删**：`propose` 的 callIndex 恒为 `现有最大+1`，且非 DRAFT 一律拒；**没有** revise / drop / reopen 工具 | `ProposeCallTool:117-121`（载荷只有 `{tool,args,intent}`）+ `DecisionAgentRunner:870`（"一条命令一次提议"）；`PacketIntentTool:131`、`ProposeCallTool:191`、`SubmitPacketTool:144`（非 DRAFT 拒） |
| **G4** | **确认（submit）被运行流吞掉**：`packet.submit` 在决策回合内被**拦截**、只回 `{"submitted":false,"deferred":true}`，真正的 PENDING 由 `DecisionTurnFinalizer` 在**回合结束后自动**落 —— DM 的"确认"不是 DM 的动作 | `DecisionAgentRunner:1181-1190`；`DecisionTurnFinalizer:232-240` |
| **G5** | **消息每 tick 每方向只有一条**（问答至少需要来回多条） | `GaMessageSendTool:198`：`infoId = "msg-<from>-<to>-<tick>"`，显式 id ⇒ `PutInfoHandler` **命令期重复即拒** |
| **G6** | **DM 不会主动去捞 GM 的答**：`inbox` 是拉取式，开场消息与每轮提醒（`decisionRoundNotice`）都**没有**提到它；DM 不调 `inbox` 就永远看不到答复 | `GaMessageInboxTool:168-180`；`DecisionAgentRunner:933-956` |
| **G7** | **"确认推演"没有落点、GM 也查不到谁确认了** | 全仓无 ack 类工具；`gm.packets{status}` 只覆盖"决策确认" |
| **G8** | **`propose`/`packet.intent` 是 `Ask` + `SENSITIVE` = 「只能到人」**，无人值守时每调一次阻塞上限 5 分钟后 `APPROVAL_DENIED` | `ProposeCallTool:138-146`、`PacketIntentTool:104-106`、`SubmitPacketTool:102-103`；`AskKind.SENSITIVE` 类注「**永远到人**」；决策人链 = `AutoApproveGate → ConfirmGate`（恒 `empty` ⇒ 去问人），`Shell:1213-1220`，`APPROVAL_TIMEOUT=5min`（`Shell:304`） |
| **G9** | **一轮最多 4 条决策**（`propose` 一次一条 + 预算 4–6 次工具调用） | `ProposeCallTool:117-121` 载荷 `{tool,args?,intent?}`；`DecisionAgentRunner:882`「本回合最多做 4–6 次工具调用」、`:947` 同款 |
| **G10** | **finalizer 无条件把整轮最后一段文本并进 intent**（"【最后陈述】"），DM 显式写过的意图被追加改写 | `DecisionTurnFinalizer:290-307`（`mergeIntent`） |
| **G11** | **无稳定 GM 身份**：`GaToolIdentity.decisionMakerIdOf(GM 身份)` 为空；全仓无 `"gm"` 常量（只有 `<store>/workspaces/gm` 一个目录名） | `AppGaToolIdentity:33`；`grep '"gm"'` 仅 `SimosToolSource:362` |

### 2.3 GM 桶全量（本设计书要往里加东西的那一份）

`simos.gm.approvals` · `simos.gm.approve` · `simos.gm.mergedPlan.apply` · `simos.gm.mergedPlan.upsert` · `simos.gm.packet` · `simos.gm.packet.decide` · `simos.gm.packet.execute` · `simos.gm.packets` · `simos.gm.tool-usage`（读/写各半）。
**没有一条能读 `ga:message.*` / `ga:question.*` / `ga:answer.*`。**

---

## 3. 设计

### 3.1 唯一的新原语：**信件（message）双向化**

不新造"问答"机制 —— 现有 `ga:message.*` + `peer` 域的形状**就是**这套流程要的（零新状态、零新切片、零新命令类型、tags 即可见性）。只改三件事：

| 改点 | 现在 | 改成 |
|---|---|---|
| **GM 有稳定 id** | 无 | `"gm"`（唯一拼写点 `ToolResources.GM_ID`，写进地址段与 tags） |
| **DM 的 `peer` 基础可达面** | `{自己} ∪ accessLimit.peer` | `{自己, "gm"} ∪ accessLimit.peer`（★ 加在 `narrowTo` **之后**，与自指域同一处、同一条纪律：`DecisionCallerFactory.withPeerSelfFace`，`:334-352`） |
| **`to` 的合法值** | 另一个决策人 | DM：`gm` 或授权的 peer DM；GM：任意存在的决策人 |

**为什么 DM→GM 不需要 GM 事先授权**：DM 写的是**自己**的疑问，不读也不写任何别人的数据 ⇒ 不构成越权；GM 读全部 INFO 本来就是它的位置。**DM→DM 仍需授权**（那是把信息给第三方）。

**新增两个工具（GM 侧）**：

- `simos.gm.inbox`（只读，`{GM}`）：列出 `to = gm` 的信，逐条带 **`answered`**（= 存在一条 `replyTo` 指向它的 GM 回信）。附带 `{sinceTick?, limit?}`。**纯派生**，零新状态。
- `simos.gm.message.send`（写，`{GM}`）：`{to, subject?, body, replyTo?}` ⇒ 一条 `ga.PutInfo`，地址 `ga:message.gm.<to>.<tick>.<n>`，`tags={gm, to}`，`from="gm"`。GM 走 GM 面 ⇒ `gate=Ask`（GM 链 `GmAutoApproveGate` 自动过，与其余 GM 写同制）。

**条数上限（§9-C 裁定）**：**同 tick 同方向 128 条**，命令期判（不是提示词）。`<n>` = 该地址族下已有序号（0..127），第 129 条**具名拒、世界零变化**。★ **正文注入不截断**（截断 = 静默丢掉 GM 的答复 = 问答断链），只在每轮提醒里报出"已收发 N / 上限 128"。

**DM 侧两个工具**（`simos.ga.message.send` / `inbox`）**保留**，广义化 `to` 的合法值。

### 3.2 决策表达：自由覆盖/修改（补 G3 / G9 / G10）

| 工具（新名） | 载荷 | 语义 |
|---|---|---|
| `simos.ga.packet.propose` | `{tool, args?, intent?}`（**单条**）+ 可选 `{calls:[…]}`（**多条**，向后兼容单条形态） | 追加 1..N 条 `FormattedCall`；`intent` 有则覆盖包意图。**一次调用可提多条**（解 G9） |
| `simos.ga.packet.revise` | `{callIndex, tool?, args?, intent?}` | 改**一条**已存在的 call（改完重跑 catalog 校验 / 真 preview / 目标 scope 校验，与 propose 同一段代码）；`intent` 有则覆盖包意图 |
| `simos.ga.packet.drop` | `{callIndex}` | 删一条 call（callIndex 重排） |
| `simos.ga.packet.intent` | `{text}` | 覆盖**整段** NL 决策（**已是**这个语义，只改名） |
| `simos.ga.packet.my` | `{tick?}` | 读自己的包（只改名） |
| `simos.ga.packet.confirm` | `{tick?}` | **确认本 tick 的全部决策**：DRAFT → PENDING（"之前的所有决策"= 包里的全部 calls + intent，跨轮累积） |
| `simos.ga.packet.reopen` | `{tick?}` | **撤回确认**：PENDING → DRAFT。**仅**当 `decidedBy` 为空（GM 未裁决）且调用者是 proposer |

★ **改/删/覆盖的边界（硬）**：只在 **DRAFT** 上允许。已 PENDING ⇒ 具名拒，提示"先用 `reopen` 撤回"。已裁决（APPROVED/REJECTED/MERGED/PARTIALLY_APPROVED）⇒ `reopen` **也必须拒** —— 这一条是**防线**，不是顺手：`ga.UpsertDecisionPacket` **本身没有状态机**（`UpsertDecisionPacketHandler:93`：status 从载荷取、整包覆盖）⇒ 守卫必须落在工具侧，否则 DM 能把 GM 已批的包翻回 DRAFT 再提交，**GM 的裁决被静默抹掉**。

### 3.3 确认（补 G4 / G8）

**工具的 `gate` 一律改成 `ALLOW`（类3 `DM_FREE`）**，与用户裁定一致：`propose` / `revise` / `drop` / `intent` / `my` / `confirm` / `reopen` / `message.send` / `message.inbox` / `adjudication.ack` —— 这些**不产生任何世界效果**（写的是自己的包 / 自己的信 / 自己的确认），它们的产物要**经 GM 裁决**才生效 ⇒ 闸门在 GM 侧（`gm.packet.decide` / `mergedPlan.*` / `packet.execute` 保持 GM-only）。

**回合结算改成"条件式"**（`DecisionTurnFinalizer`）：

| 条件 | 行为 |
|---|---|
| 本回合**有未答提问**（本 tick 该 DM 发出的信里，存在没有 GM 回信 `replyTo` 指向的） | **不结算**：包保持 DRAFT（仍可被 `gm.packet` 读到 ⇒ **不丢**），等 GM 答完再跑 |
| 本回合**无未答提问**、且 DM 已 `confirm` | 正常结算（提交） |
| 本回合**无未答提问**、DM 未 `confirm` | **保留现有 `auto-no-submit` 兜底**（把整轮最后一段文本并进 intent 并提交）——**不静默丢决策**这条红线不退让 |
| 预算中止 / 运行时失败 | 现有 `finalizeAborted` 不变 |

**`mergeIntent` 改成条件式**（补 G10）：**DM 显式写过 intent（本轮或本 tick）⇒ 不再追加「【最后陈述】」**；只有从未显式写过时才用最后一段自然语言兜底。

### 3.4 答复必须到达 DM（补 G6）

**新信件在每轮开始时自动注入，且落盘**（与开场消息同一条纪律：只注一次）：

1. 每轮开始，取该 DM **收件箱里、且会话历史里还没有的**信（GM 的回信 + 被授权 peer DM 的来信）；
2. 拼成一条 **user** 消息，`conversations.append` 落盘，排在身份消息之后、本轮提醒之前；
3. **游标由历史自己承载**：注入消息正文里写明"已注入至 `<最后一条信的 id>`" ⇒ 重启后扫历史即可恢复，**零新存储**。
4. ★ **无新信 ⇒ 一个字节都不写会话**（不许每轮塞一条空消息）。

★ 同时把"你有未读信件"写进 `decisionRoundNotice`（不落盘的那条每轮提醒），让 DM 知道该调 `inbox` 复查。

★★ **前提（派单时未写明，实测推出来的一条硬约束）**：**GM 每答一次，必须重跑一次触发（`run-decision-makers` / `ga.RunDecision`），答复才会到达 DM。** 因为冻结回合的 `state` 是**触发 revision 的快照**（`DecisionAgentService.runRound:250`：`core.replay(new StateRef(branch, revision))`），而 `run-decision-makers` **每次调用都会先落一批 `ga.RunDecision` ⇒ 新的触发 revision**（`RunDecisionMakersTool.apply`）。所以：
- GM 答（写 INFO，revision N）⇒ 再触发（revision N+1，≥N）⇒ DM 的冻结快照含那条答复 ✔
- **不重跑触发就看不到答复** ⇒ 这条要写进 `simos.gm.inbox` / `simos.gm.message.send` 的 `description`：**"回信之后要重跑一次该决策人，它才会看到"**。

### 3.4.1 信件序（**实现中发现的一个真缺陷，批 2 修**）

信件 id = `msg-<from>-<to>-<tick>-<n>`，而 `<n>` 是**十进制**。按 `(tick, id)` **字典序**排 ⇒ 同一 tick 内 ≥10 条时顺序变成 `0,1,10,11,2,…,9`：

```
$ printf 'msg-gm-dm1-5-%s\n' 0 1 2 3 4 5 6 7 8 9 10 11 | sort | tr '\n' ' '
msg-gm-dm1-5-0 msg-gm-dm1-5-1 msg-gm-dm1-5-10 msg-gm-dm1-5-11 msg-gm-dm1-5-2 … msg-gm-dm1-5-9
```

★ 后果：注入游标是"最后一条的 id + 1"，而新到的第 12 条（`-12`）**排在游标前面** ⇒ `start = index(cursor)+1` **跳过它** ⇒ **答复被静默丢掉**（正是本设计最不能出的错）。同理 `gm.inbox` 的"截最近 N 条"会截错。
★ 修法：`Letter` 加**数值** `sequence()`（取 id 最后一段，解析失败退回不参与排序），排序改成 `(tick, sequence, id)`。

### 3.5 确认推演（补 G7）

- **DM 侧**：`simos.ga.adjudication.ack`（`{tick, note?}`，`{DM}`，`gate=ALLOW`）⇒ 一条 `ga.PutInfo`，地址 `ga:adjudicationAck.<tick>.<dm>`，`key=ack`，`tags={dm}`，**显式 id** `ack-<tick>-<dm>`（⇒ 同 tick 第二条命令期具名拒，天然幂等一次）。
- **GM 侧**：`simos.gm.round`（只读，`{GM}`，`{tick?}`）⇒ 逐 DM 一行：`packetStatus` / `callCount` / `openQuestions`（DM 发来未答的条数）/ `adjudicationAcked`。**纯派生读口，零新状态**（包、信、ack 都可查）。

### 3.6 命名：一次改到位（顺带完成 sd 退役里最大的一块）

工具名 `simos.sd.packet.{intent,my,submit}` / `simos.sd.propose` → **`simos.ga.packet.*`**（新名，**不做旧名兼容**：Alpha 不兼容旧档，`docs/agents/02-一` §一.11）。
★ **不动**：`ToolResources.SD_NAMESPACE` / `ResourcePaths#sd` / `ToolSupport.SD_NAMESPACE`（权限声明面标识符，跨 `simos-ga-spi` + `simos-util`，`01-〇` 里有**意**留给批次 5）；其余 9 条非决策族 `simos.sd.*` 工具名（另批）。

---

### 3.7 ★ GM 代打：`simos.gm.actAs`（2026-10-14 第二轮裁定）

**用户原话**：

> 「那我建议还是决策人归决策人，真人归真人吧——反正真人真要下令也是自然语言，如果有些工具注定只有决策人需要用，那GM就算碰，那出于真实的伪造决策的需求，也应该让GM搞，总结就是该咋整咋整，不要动现有架构，GM想干啥干啥」

**裁定拆解**：

| 子项 | 裁定 | 后果 |
|---|---|---|
| **真人 / 决策人分家** | **不做**（`ChannelAdmission.LANDING_POINTS` **不动**） | 真人要下令就走**自然语言**；决策包七件套**不进**落点 |
| **GM 代打（伪造决策）** | **要做** | 出于"从外部把真人玩家的东西输进去"的真实需求 |
| **总口径** | **不要动现有架构；GM 想干啥干啥** | ⇒ 只能是**加法**：一个**新工具**，零改既有七件套、零改权限面、零改通道 |

**★ 为什么是"新工具"而不是"给七件套的 `roles` 加 GM"**（这一条是红线）：后者的载荷必须新增 `decisionMakerId`，而七件套现在的身份是 **从 `context.identity()` 派生、不采信载荷**（`ProposeCallTool:158` 等）。把身份改成从载荷取 = 给 GM 开一条**替任意 DM 提交**的通道，且破掉"模型写的载荷不等于身份"这条不变量。⇒ **一个工具、唯一的口**，好过在七件套里各开一个洞。

**形状**：

- `NAME = "simos.gm.actAs"`；`roles() = {GM}`；`gate = Ask`（GM 链 `GmAutoApproveGate` 无脑过，与其余 GM 写同制）
- 载荷：`{decisionMakerId(必填，必须是当前存在的决策人), tool(必填，真实工具名), args?(对象，缺省 {}), branch?, expectedRevision?}`
- 执行：**建那个 DM 的环境，再走决策人回合内**完全同一条路：
  ```
  DecisionCallerFactory.callerFor(dm, state, mapId)   ← 该 DM 的身份 + 现算权限组（范围/accessLimit/docs/peer）
  DecisionToolCaller.execute(决策人桶注册表, tool, dm, state, mapId, args, config)
  ```
  ⇒ 五段判定链（档位 / 资源声明前置闸 / 审批 / 执行）**一段都不跳**；用的注册表必须是**决策人桶**（不是 GM 桶），用的白名单必须是 `whitelistFor(该 DM)`
- 拒绝：决策人不存在 ⇒ 具名拒；工具不在该 DM 的可达面 ⇒ 照实回 `PERMISSION_DENIED` / `TOOL_NOT_FOUND`（**不许**为了"让 GM 成功"而绕过任何判定）
- ★ **诚实标注（必须写进类注）**：GM 本来就有通用写（`simos.command.submit` 可提交任意命令，用户 2026-09-22 裁定 D2）⇒ 本工具**不放大任何权力**。它存在的意义是给"以某个决策人的环境落一次调用"这件事一个**唯一的、可见的、进 `GmToolUsage` 留痕的**口——否则 GM 只能手拼命令，**手工拼＝绕过权限链**（`DecisionToolCaller` 类注：手工拼 `ToolContext` 再直接 `tool.execute()` 会让每个 `require` 的工具 fail-closed，那是"没有判定者"的缺省，不是正确行为）
- 留痕：`GmToolUsage`（GM 口每次工具执行都留痕）+ 日志记 `decisionMakerId` 与 `tool`
- ★ 它**不进 DM 桶**（`roles()={GM}`）

### ★★ 3.7.1 三条落地裁定（2026-10-14 用户口述，逐字）

> 「1 按照你说的吧，log肯定是要的，agent上下文没必要伪造 / 2 不能 / 3 GM想放哪放哪」

| # | 裁定 | 落地 |
|---|---|---|
| **1** | **留痕 = 日志（+ `GmToolUsage`）**；★ **"agent 上下文没必要伪造"** | ⇒ ① 日志**必须**有（INFO，记 `decisionMakerId` + `tool` + 结局码）；② **决策包与 INFO 里都不加"这次是 GM 代打"的标记** —— 包里 `proposerId` 就是那个 DM，不多一个字段（用户要的是**真实的伪造**）；③ ★★ **`actAs` 不写任何会话**：不往 `conversations` 追加任何消息、**不跑 LLM** ⇒ "那个 DM 的 agent 上下文"保持原样、不被伪造 |
| **2** | **不能触发"跑一轮 LLM"** | ⇒ 对**编排族工具**（`simos.sd.run-decision-makers` / `ga.RunDecision` / `ga.StartDecision`）**显式具名拒**（`BAD_REQUEST`，文案写明"代打只落工具调用，不触发决策人跑轮"）。★ 它们本来就不在决策人桶里、会被自然拒 —— 但**显式拒**比"碰巧不可达"清楚，模型也看得懂 |
| **3** | **GM 想放哪放哪** | ⇒ `branch` / `expectedRevision` **原样生效**：`actAs` 用 GM 给的那个坐标 `replay` 状态，**在那个状态上**现算该 DM 的权限组与环境。★ **不限制成 head**；不传 ⇒ 缺省 head（既有惯例） |

### 3.8 ★ `sd` 权限域名改名（2026-10-14 第二轮裁定）

**用户原话**：

> 「但凡和被取缔的sd模块有关的就改，正常语义不用」

**是什么**：`sd` 作为**数据**早就没了（模块退役、`sd:*` 地址族 0 处、`sd.*` 命令类型 0 条）。剩下的是一个**权限声明面的域名串**，三个拼写点 + ~106 处引用：

| 拼写点 | 位置 |
|---|---|
| `ToolResources.SD_NAMESPACE = "sd"` | `simos-ga-spi:50` |
| `ResourcePaths.sd(kind, id)` | `simos-util:90` |
| `ToolSupport.SD_NAMESPACE = "sd"` | `simos-app:120` |

**改成什么**：**`ga`**。理由：现存的资源路径是 `sd:decision-maker/<id>` / `sd:decision-packet/<id>`，而**地址族**早就是 `ga:decision-maker.<id>` / `ga:directive.<id>`（`GaResolver` 只接受 `ga`）⇒ 改后**权限域名与地址族同名**，一处一致。
★ **已核无撞名**：`ToolResources` 现声明 8 个域名（`map`/`social`/`unit`/`sd`/`economy`/`actor`/`army`/`peer`），**没有 `ga`** ✔

**红线（「正常语义不用」的落地）**：**只换名字串，不改任何权限口径**——类1/类2/类3 的归属、各命名空间的表态、决策人基础面（`{sd:decision-maker/<自己>, sd:decision-packet/<自己>}`）、GM 组的 `unlimited` 表态，**逐条不变**。证明方式 = **重跑 `DecisionFaceGolden`（决策面金样本）** + `DecisionCallerFactoryTest`；漏改一处不会报错，只会**静默放宽或收紧权限**。

★ `ResourcePaths.sd(...)` → `ResourcePaths.ga(...)`；方法名与常量名一起改（`SD_NAMESPACE` → `GA_NAMESPACE`），**不留别名**（留别名 = 第二处拼写点）。

**✅ 2026-10-14 已落地（提交 `6f157693`）**：87 文件（main 76 / test 11）= +211/−213；派生符号 `SD_NAMESPACE_WRITE → GA_NAMESPACE_WRITE`、`sdNamespaceWriteResources → gaNamespaceWriteResources` 一并改（不改则"旧符号残留 grep = 0"这条验收过不了）。红线守住的三条证据：① **`DecisionFaceGolden` 单文件 `git diff` 为空**（不是靠改期望过的）② 控制方 `clean verify` **rc=0**（3250/0/0/5 · SpotBugs 16×size 0 · 前端 412/412）③ 机械自证：87 文件反向替换 4 个标识符后与 HEAD 逐字 diff，非注释残留**恰好 16 行、全是预期值改动**。

### ★★ 从这一批学到的取数教训（写给后来者）

**量"改名波及面"必须符号 + 字符串字面量两路 grep。** 控制方给实现方的清单是按**符号**（`SD_NAMESPACE` / `resourceSd` / `ResourcePaths.sd(`）grep 出来的（main 134 / test 14），**漏了只含 `"sd"` 字面量的文件**——实现方自查又找出 2 个：`GmPermissionGroupTest:50`（GM 命名空间清单）与 `SdDecisionScopeApiTest:179`（`namespaces.get("sd")`，**不改必 NPE**）。⇒ 正确姿势：`grep -rn 'SD_NAMESPACE\|resourceSd\|ResourcePaths\.sd(\|"sd"'`。

★ 顺带（同属"和被取缔的 sd 模块有关"）：`SimosToolSource.java:1570` 的注释写「工具名（`simos.sd.*`）」，但它管的 5 条里 3 条是 `ga.*` ⇒ 注释失真，一并改。

---

## 4. 不变量（I 清单）

| # | 不变量 | 守它的判据 |
|---|---|---|
| **I1** | **决策表达零世界效果**：`propose`/`revise`/`drop`/`intent`/`confirm`/`reopen`/`message.send`/`adjudication.ack` 都不写世界命名空间 | 逐工具的 `resources()` 只声明 `sd`（包/INFO 覆盖层）；E2E 断言"调一次 ⇒ 世界切片逐字段不变" |
| **I2** | **闸门在 GM 侧**：DM 侧全 `ALLOW`，GM 侧 `gm.packet.decide` / `mergedPlan.*` / `packet.execute` / `run-decision-makers` 全 `{GM}` | 装配期 `ToolRoleAudit.requireFaces`；源码扫描"DM 面工具里没有一个 `gate().Ask`" |
| **I3** | **已裁决的包不可回到 DRAFT** | `reopen` 的用例：APPROVED/REJECTED/MERGED/PARTIALLY_APPROVED 四种逐一具名拒（负向） |
| **I4** | **不丢决策**：任何回合结束路径下，DM 写进包里的 calls 与 intent 都可在 `gm.packet` 读到 | 未答提问分支（保持 DRAFT）+ 预算中止分支（`finalizeAborted`）各一条用例 |
| **I5** | **世界在问答期间对 DM 不变**：同一 tick 的每一轮，DM 读到的世界 revision == 触发 revision | 既有 frozen 语义；补一条"跑两轮后读到的 revision 仍 == triggerRef"的用例 |
| **I6** | **包能跨 revision 累积**：后一轮的包写不因前一轮的包写而 CONFLICT | 既有 `args.remove("expectedRevision")`；补一条"同 tick 连跑三轮、三条 call 都在"的用例 |
| **I7** | **信件的 `from` 不可伪造**：DM 的 `from` 从身份派生；GM 的 `from` 恒 `"gm"` | 载荷里没有 `from` 键；DM 调 GM 侧工具 ⇒ 不在白名单（`PERMISSION_DENIED`） |
| **I8** | **DM 不能冒充 GM 的身份段** | `to="gm"` 可由 DM 发起；但 `from` 永远不会是 `"gm"`；地址段 `ga:message.gm.<dm>.*` 只能由 GM 侧工具产生 |
| **I9** | **条数有界**：同 tick 同方向信件条数上限 **128**（**命令期**拒，不是提示词） | 负向用例：第 129 条具名拒、世界零变化 |
| **I10** | **确认是 DM 的动作**：`packet.confirm` 在回合内**不再被拦截**（拦截只留给…**删除**），PENDING 由它产生 | 回合内调 `confirm` ⇒ 返回 `submitted:true`；随后 `gm.packets{status:PENDING}` 能查到 |
| **I11** | **不可见性不外泄**：DM 的 `inbox` 只回 `to == 自己` 的信；GM 的 `gm.inbox` 只回 `to == "gm"` 的信 | 正负各一条（peer DM 的信不出现在 `gm.inbox`） |
| **I12** | **零新状态**：不新增 `GaState` 组件、不新增命令类型 | `GaRoundTripTest` 组件数不变；命令类型总数不变 |

---

## 5. 权限表（三分类，用户同日裁定）

| 类 | 定义 | 本批落进去的 |
|---|---|---|
| **类1 `GM_ONLY`** | `roles={GM}`，不进 DM 面 | `simos.gm.inbox`（新）· `simos.gm.message.send`（新）· `simos.gm.round`（新）· 既有 `gm.packet.decide` / `mergedPlan.*` / `packet.execute` / `approvals` / `approve` / `run-decision-makers` |
| **类2 `DM_APPROVAL`** | `roles={GM,DM}` + `gate=Ask` | **本批一个都不加**（用户裁定"决策-提交流程属无条件执行"） |
| **类3 `DM_FREE`** | `roles={DM}`（或 `{DM,GM}`）+ `gate=ALLOW` | `simos.ga.packet.{propose,revise,drop,intent,my,confirm,reopen}` · `simos.ga.message.send` · `simos.ga.message.inbox` · `simos.ga.adjudication.ack` · 既有 `sd.DecisionResults` / `sd.DecisionDocs` |

---

## 6. 验收判据（正向）

1. **DM 问 GM 一问**：DM 调 `simos.ga.message.send{to:"gm",body:"…"}` ⇒ 世界零变化；`simos.gm.inbox` 能读到它，`answered=false`。
2. **GM 答**：`simos.gm.message.send{to:<dm>,body:"…",replyTo:<id>}` ⇒ 该条 `answered=true`；**该 DM 下一次跑轮时，这条答自动出现在会话里**（不需要它自己调 inbox）。
3. **多轮**：同 tick 对同一 DM 连跑 4 轮（问、答、再问、再答）⇒ 无 id 冲突、无 CONFLICT；第 4 轮 DM 能引用第 1 轮的答复（上下文连续）。
4. **一轮多条决策**：一次 `simos.ga.packet.propose{calls:[c1,c2,c3]}` ⇒ 包里 3 条 call，`gm.packet` 全看得见。
5. **自由改/删**：`revise{callIndex:1,…}` 改完 `gm.packet` 里的 args 变了、`preview` 重算过；`drop{callIndex:0}` 后 callIndex 重排且 callCount 少一。
6. **覆盖 NL**：先 `intent{A}` 再 `intent{B}` ⇒ 包里只有 B；GM 读到的 intent == B（**没有**"【最后陈述】"尾巴）。
7. **确认**：`confirm` ⇒ 返回 `submitted:true`，`gm.packets{status:PENDING}` 查到；再 `confirm` ⇒ 幂等成功。
8. **撤回**：`reopen` ⇒ 回到 DRAFT；`revise` 又能用了。
9. **推演确认**：GM 推演后 DM 调 `adjudication.ack{tick}` ⇒ `simos.gm.round` 里该 DM 的 `adjudicationAcked=true`；同 tick 再 ack ⇒ 具名拒。
10. **GM 看板**：`simos.gm.round` 一次返回"谁已确认决策 / 谁有未答问题 / 谁已确认推演"。

## 6.1 负向用例（判别力所在）

| # | 负向 | 期望 |
|---|---|---|
| N1 | DM 调 `simos.gm.message.send` | `PERMISSION_DENIED`（不在 DM 白名单） |
| N2 | DM 给**未授权**的另一个 DM 发信 | `RESOURCE_DENIED`（`peer:<对方>` 够不着） |
| N3 | DM 给自己发信 | `BAD_REQUEST`（既有守卫不退化） |
| N4 | `reopen` 一个 APPROVED 包 | 具名拒 + 世界零变化 |
| N5 | `revise`/`drop` 一个 PENDING 包 | 具名拒，提示先 `reopen` |
| N6 | 伪造 `from` | 载荷**没有** `from` 键；身份外的署名一律不生效 |
| N7 | 第 N+1 条同 tick 信件 | 命令期具名拒、世界零变化 |
| N8 | 未答提问时回合结束 | 包**保持 DRAFT**（不是 PENDING），且 calls/intent 一条不少 |
| N9 | GM 面的 `gm.inbox` 里出现 peer DM 之间的信 | 不得出现（`to` 判据） |
| N10 | `simos.ga.packet.*` 的工具 `gate()` 出现 `Ask` | 源码扫描当场红（I2 的机械判据） |

---

## 7. 批次与文件所有权（一文件一 owner）

| 批 | 责任区 | 写范围 | 状态 |
|---|---|---|---|
| **A** | 信件双向化 + GM 收/发 + 自动注入 | `simos-ga-spi/.../ToolResources.java`（加 `GM_ID`）· `simos-ga/.../tools/write/GaMessageSendTool.java` · 新 `.../tools/read/GmInboxTool.java` · 新 `.../tools/write/GmMessageSendTool.java` · `simos-app/.../access/DecisionCallerFactory.java`（`withPeerSelfFace` 加 `gm`）· `simos-ga/.../decision/DecisionAgentRunner.java`（新信件注入 + 提醒文案） | ✅ **已完成**（5 改 4 新建；`test-compile` rc=0，控制方独立复跑亦 rc=0；未写未跑测试） |
| **C** | 推演确认 + GM 看板 | 新 `AdjudicationAckTool` · 新 `GmRoundTool` | ✅ **随 A 一并完成** |
| **B** | 决策自由化 + 确认/撤回 + 权限转类3 **+ 信件序修正 + 收件箱文案** | `simos-ga/.../tools/write/ProposeCallTool.java` · 新 `ReviseCallTool` / `DropCallTool` / `ReopenPacketTool` · `PacketIntentTool` / `MyPacketTool` / `SubmitPacketTool` → `ConfirmPacketTool`（改名 + gate）· `simos-ga/.../decision/DecisionToolNames.java` · `simos-ga/.../decision/DecisionAgentRunner.java`（删 submit 拦截 + 预算口径）· `simos-app/.../decision/DecisionTurnFinalizer.java`（条件式结算 + 条件式 mergeIntent）· `simos-app/.../decision/ProposalCatalog.java`（revise 复用其校验段）· ★ **`simos-ga/.../tools/read/GmInboxTool.java`（§3.4.1 信件序改为数值序 + §D1 收件箱文案"每 tick 每方向最多 128 条"）** | ✅ **已完成**（11 改 + 4 新 + 1 `git mv`；`test-compile` rc=0，控制方独立复跑亦 rc=0；未写未跑测试） |
| **E** | **旧名/过时文案收口**（批 B 交账时发现的残留） | `simos-app/.../access/DecisionCallerFactory.java`（`GOAL` 里 4 个旧工具名）· `simos-app/.../tools/read/CatalogTool.java`（`:855,:858` 两处旧名）· `simos-ga/.../tools/read/GaMessageInboxTool.java`（`description()` 仍写"每 tick 每方向**至多一条**" ⇒ 128）· `simos-ga/.../tools/write/SetDecisionMakerPeerTool.java`（`description()` 同上）· `simos-ga/.../tools/read/GmPacketTool.java`（javadoc 指向 `simos.sd.packet.my`）· `simos-ga/.../tools/DecisionPacketViews.java`（同）· `simos-app/.../tools/SimosToolSource.java:1935`（注释同） | ✅ **已完成**（7 文件 +13/−9，全文本；控制方用 `grep -a` 在 **`.class` 常量池**里核到「每 tick 每方向最多 128 条」已折叠进去 ⇒ 模型真读到；`test-compile` rc=0） |
| **F** | `sd` 权限域名改名（§3.8） | `simos-ga-spi/.../ToolResources.java` · `simos-util/.../ResourcePaths.java` · `simos-app/.../tools/ToolSupport.java` + 全仓 ~106 处引用 + `SimosToolSource:1570` 失真注释 | ✅ **已完成**（87 文件 +211/−213；金样本单文件 diff 为空；控制方 clean verify rc=0） |
| **G** | GM 代打工具 `simos.gm.actAs`（§3.7 + §3.7.1 三条裁定） | 新 `simos-ga/.../tools/write/GmActAsTool.java` · `simos-app/.../tools/SimosToolSource.java`（注册，`addGmWrites`）· `simos-app/.../access/DecisionCallerFactory.java`（若需暴露 `callerFor` 的窄口） | ⬜ **下一批**（F 已完成） |
| **D** | 测试面（**单独一个测试代理**） | `simos-app/src/test/**` · `simos-ga/src/test/**` | ⬜ **下一批**（覆盖 A/B/E；F/G 落地后再来一轮小的） |

★ **`DecisionAgentRunner.java` 在 A 与 B 各出现一次** ⇒ **A 必须先完成并提交**，B 再动它（一文件一 owner，见 §一 并发）。✅ 已满足。

★ **`GmInboxTool.java` 的所有权裁定（原批 A 的偏差 D1 收口）**：批 A 的任务书 §4 把它列为禁碰（写范围只含「新建」），故其文案仍写"每 tick 每方向一条"（已过时）。⇒ **并入批 B**，一次把"文案 + §3.4.1 的信件序缺陷"两件都在同一文件里收口（一文件一个 owner，不另开批）。✅ 已办（但 §2.11 的文案只覆盖了类注，`description()` 那一条漏网 ⇒ 归批 E）。

★ **A 批的两条已知红测试**（行为按用户裁定改变，**由测试代理**改成新靶子）：`DmMessagingEndToEndTest#p4_secondMessageInTheSameTickIsRejectedByNameAndLeavesNoRevision` ⇒ 改成"第 129 条具名拒"；`SimosToolsTest` / `DecisionFaceGolden` 的计数（GM-only 读 +2、非窄写 +1、GM 桶 175→178、DM 桶 50→51、磁盘类 174→178；**窄写 68 与 catalog 143 不动**）。

---

## 7.1 批 A/B 的落地裁定（2026-10-14，控制方核过后追认）

| # | 事项 | 裁定 | 理由 |
|---|---|---|---|
| **L1** | "未答提问"判据只认 `to == "gm"` 的信 | ✅ **追认** | peer DM 之间的信**没有 `replyTo` 键**（DM 侧发信载荷就没有这个键）⇒ 把它们算进"未答"会让该 DM **永远不结算** |
| **L2** | 判据按**本 tick**，不按"本回合" | ✅ **追认** | "本回合"在**冻结快照**里不可判定（快照里没有"回合"这个概念）；不限 tick 则历史未答问题会让该 DM 永久卡死。★ 副作用（接受）：DM 在 tick N 问、GM 不答就推进到 N+1 ⇒ 那个问题成为孤儿 |
| **L3** | `reopen` 除 `decidedBy` 非空外，再加"四种终态 ⇒ 拒" | ✅ **追认** | fail-closed：`decidedBy` 为空但状态已是终态 = 契约故障，也不许放行 |
| **L4** | `intent` / `my` / `confirm` 也收 `tick?`（缺省 = 当前 tick） | ✅ **追认** | 族口径一致；向后兼容 |
| **L5** | 旧 4 参 `DecisionTurn` 构造器**删除** | ✅ **追认** | 与规范构造器签名同形，Java 不允许并存；`finalizeAborted` 改显式 5 参 |
| **L6** | 批 B **动了 3 处批 A 文件的注释**（`AdjudicationAckTool` 5 处 / `GmRoundTool` 1 处 / `DecisionAgentService` 1 段） | ✅ **追认** | 控制方用 `git diff -U0 \| grep -v` 核过：**注释行改动，零代码改动**；原文指向已改名的类 = 缺陷 |
| **L7** | 残留旧名/过时文案 **8 处 7 文件** | ⬜ **批 E 修** | 见 §7 批 E。★ 判据：**模型/GM 直接读到的文本（`description()` / `GOAL` / catalog 文案）必须修**；javadoc 指向不存在工具的必须修；**明确标注"旧名"的历史留痕保留**（`MyPacketTool:43` / `ProposeCallTool:88` / `PacketIntentTool:53` / `ConfirmPacketTool:42`） |

---

## 7.2 ★ 公用 `sim-log` 挂载缺陷（2026-10-14 实测发现 + 修复）

**这是本设计书之外、但由本批的测试面暴露出来的一个真生产缺陷**（测试代理在跑全量时红：`DecisionResultsShellEndToEndTest.startShell:118`，全量 925 条里唯一 Error）。**症状**：只要 `Shell.start` 跑在一个**世界里已有决策人**的 store 上，装配期就抛 `WorkspaceException(M9/W1)`，**壳起不来**——即"第二次启动一个已有决策人的 store"必炸；首次启动因宿主还没挂过 `sim-log` 而侥幸通过。

### 根因（**依赖库给出的硬约束，不是我们的设计意图错**）

WorkspaceMosire（`io.mosire:workspace-mosire`，**依赖 jar、不可改**）三道守卫连着咬：

| 位置 | 判据 |
|---|---|
| `DefaultWorkspaceRegistry:103`（M2） | `caller.holder().equals(spec.holder())` ⇒ **`Mount.holder` 必须逐字等于 `caller.holder`** |
| `DefaultWorkspaceRegistry:113`（M2） | `Lineage.inSubtree(lineageOf(caller.holder()), lineageOf(spec.user()))` ⇒ caller 必须是 user 的祖先**或本身**（`Lineage:37` 含自身） |
| `DefaultWorkspaceRegistry:164-173`（M9/W1） | `own = ownMountContaining(caller.holder(), spec.dir())`；`own != null && !own.strictlyContains(dir)` ⇒ 拒 |
| `Mount.contains:95` | `equals(dir) \|\| startsWith(dir)` ⇒ ★ **"等于"也算包含**；而 `strictlyContains:104` 要的是**真子目录** |
| `ownMountContaining:479-494` | ★ 只查 **`slices.get(holder)` 这一片**（切片键 = `spec.user()`） |

⇒ ★★ **同一个 `holder` 名下、同一物理 dir 只能有一条挂载。**

原实现 `mountSharedSimLog` 对**每个用户都用同一个 holder**（`WORKSPACE_HOST_KEY`）⇒ 宿主那条挂上之后，决策人那条要挂**同一个物理 dir** 就被判成"等于自己的工作区根"而拒。**设计意图没错**（"同一块物理地分头挂给多个使用者"是挂载表 `(user, name)` 分片的正常用法），**错在 holder**。

### 修法（唯一可行，行为改动 4 行）

`mountSharedSimLog` 里 **holder 与 caller 双双换成 `userKey`**：
```java
Mount.of(new MountName(SHARED_SIM_LOG_NAME), userKey, userKey, dir, Kind.LOG, …)
new WorkspaceCaller(userKey, assemblyPermissions(docsRoot))
```
逐条对上：`holder == user` ⇒ `:103` 过；`inSubtree(x,x)` ⇒ `:113` 过；**每个 user 的切片只装自己那条** ⇒ 对每个用户 `ownMountContaining` 都是 `null` ⇒ M9/W1 整段跳过。
★ **不允许**改的：物理落点 `<docsRoot>/shared/sim-log` · `Kind.LOG` · `Origin.ASSEMBLY` · 能力集 · `hasMount` 幂等 · 排除口语义 · `mountDecisionMaker`（**它是对的**：私有落在 `<docsRoot>/dm/<id>`，与宿主的 `<docsRoot>/gm` 是兄弟目录 ⇒ `own == null`）· 依赖版本。

### ★ 给后来者的教训（本仓口径）

1. **"同一物理 dir 挂给多个 user"在本库上需要 `holder == user`** —— 写成"宿主统一持有"必然在**第二条**挂载上炸，且**首次启动不炸**（宿主那一片还是空的）⇒ 只在"已存在决策人"的 store 上现形。
2. **依赖库的行为要回源码核，别照类注推断**：原类注写「{@code holder} 恒为宿主（M1：{@code holder} 恒为创建者本人）」——**这句被实测证伪**，而它正是错误实现的依据。修完已把该句删掉并写进因果链与行号。
3. **`Kind` 不是权限**（§2.1 同族）：`sim-log` 对决策人**只读**是靠 `withDecisionMakerDocsScopes` 的**写面只取私有工作区**（挂载名 == `DEFAULT_WORKSPACE_NAME`），不是靠 `Kind.LOG`。修后已复核：read 含 `sim-log`、write **不含**。

---

## 7.3 ★ 门禁暴露的第二个生产缺陷：SpotBugs `REC_CATCH_EXCEPTION`（2026-10-14）

**症状**：`clean verify` 跑到 `simos-ga` 后 FAILURE —— 断在 SpotBugs ⇒ **`simos-app` 的 SpotBugs 与测试整轮 SKIPPED**（"全仓干净"这个数字当轮拿不到）。

```
[ERROR] Low: Exception is caught when Exception is not thrown
        ...PacketDraftSupport.parseArgsObject(String) At PacketDraftSupport.java:[line 99] REC_CATCH_EXCEPTION
[ERROR] Failed to execute goal ...spotbugs-maven-plugin:4.10.4.1:check (spotbugs-check) on project simos-ga:
        failed with 1 bugs and 0 errors
```

**归属**：`PacketDraftSupport.java` 由批 B（`4187c51a`）**新增** ⇒ **本批引入的新违规**，不是既有的 `16×"size is 0"` 基线债。

**修法**（唯一文件，2 insertions / 1 deletion）：catch 参数 `Exception` ⇒ `com.fasterxml.jackson.core.JsonProcessingException`。文案 / `IllegalArgumentException` 折叠 / 原异常作 cause **逐字未动**（catch 行号 `:99` ⇒ `:100`）。★ **不许**用 `@SuppressFBWarnings` 压——这条能真修干净，压掉就是撒谎。

**等价性**（修复代理给 `javap` 证据，非口称）：本仓 `jackson-databind-2.22.2` 的 `readValue(String, TypeReference)` **只声明** `throws JsonProcessingException, JsonMappingException`（后者是子类）⇒ 受检异常集恰为 `{JsonProcessingException}`，宽 catch 多出的那一截**无可达抛出路径**。

**真实数字**：改前 `-pl simos-ga -am` ⇒ RC=1 / `:99` / `1 bugs and 0 errors`；改后 `-pl simos-ga,simos-app -am` ⇒ **RC=0**，两模块各 `BugInstance size is 0` + `Error size is 0`，17/17 SUCCESS。★ 本轮被 SKIPPED 的 `simos-app` 静态检查首次结果：**干净，无任何违规**。

### ★★ 记录：只到"编译过"的实际漏检率

本批的纪律是「实现方只写到**编译过**，测试最后单独一个代理统一做」。代价在本批**实测**出来了：

| 缺陷 | 引入批 | 只到"编译过"时 | 在门禁里才现形于 |
|---|---|---|---|
| `sim-log` 挂载被 M9/W1 拒（**壳起不来**） | 权限版图（早期批） | 编译 rc=0、`spotless:check` rc=0 | 测试代理跑全量 |
| SpotBugs `REC_CATCH_EXCEPTION` | **批 B** | `test-compile` rc=0 | 测试代理跑 `clean verify` |

⇒ **两条都是"编译过"完全看不见的**（一条是装配期行为、一条是静态分析），且**都在门禁里现形、没有流到真机**。这就是这条纪律的收益；代价是缺陷晚一步现形、且需要**控制方在最终门禁上独立复跑**才能确认——**不能拿实现方的"编译过"当验收**。

### ★ 范围判据（本批确立，供后来者照用）

**"本批引入的"才修，历史的一律不动**（哪怕代码形状一模一样）：
- `DecisionPacketViews.java:80` 的**同类宽 catch** 属历史代码（`git blame` = `996cfd418`，2026-10-06；★ 与 `git log -S -1` 给出的哈希有分歧 —— **以 `git blame` 单行为准**，`-S` 数的是字符串出现次数变化，行移动会让它指到别的提交），本轮 SpotBugs **没有**报它 ⇒ **不改，仅记录**。
- 另 7 处宽 catch（`GaCodec:127,330` / `GmMergedPlanUpsertTool:287,337` / `DecisionEffectExecutor:410` / `ProposalCatalog:250` / `GuiServer:644`）SpotBugs 均未报 ⇒ 其 `try` 内确有 `Exception` 级抛出路径，属**合法宽捕获** ⇒ **不另立责任区**。

---

## 8. 不在本批范围（明确不做的）

- `ToolResources.SD_NAMESPACE` 等**权限命名空间标识符**改名（批次 5）。
- 其余 9 条非决策族 `simos.sd.*` 工具名（另批）。
- 类1/类2/类3 的**全仓 175 条工具**三分类归位（批次 3）。
- GUI 的问答显示（用户已定「GUI 还是放着」）。
- 防止 GM 在问答中途推演的**强制**机制（见 §9-D）。

---

## 9. 裁定结果（2026-10-14，**已全部生效**）

| # | 问题 | 裁定 | 落地要点 |
|---|---|---|---|
| **A** | GM 的稳定 id 用 `"gm"` 吗？ | **是** | `ToolResources.GM_ID = "gm"`（唯一拼写点）；进地址段 `ga:message.gm.<dm>.<tick>.<n>`、进 `tags={gm,<dm>}`、进 `peer` 前缀表 `peer:gm` |
| **B** | 撤回（`reopen`）要不要？ | **要，但只到 PENDING 为止** | PENDING → DRAFT；`decidedBy` 非空（GM 已裁决）⇒ 具名拒（不变量 I3）；`gm.packet.decide` 之后不可回退 |
| **C** | 同 tick 同方向信件上限 N | ★ **`N = 128`**（用户原话：「这个上限太傻逼了，至少改成128」；设计书原推荐 8 被否决） | 落**命令期**（`PutInfoHandler` 之前由工具数一次）⇒ 第 129 条具名拒、世界零变化。★ **正文注入不截断**：截断会静默丢掉 GM 的答复 = 问答断链；改为在 `decisionRoundNotice` 里报告「本 tick 已收发 N 条 / 上限 128」，把收敛责任交给 DM 与 GM（不许静默） |
| **D** | "有未确认 DM 时禁止推演"的强制机制 | **不做** | 只在 `simos.gm.round` 的 `description` 里写明纪律；GM 是自主行动方，机制不替它判断（与用户「触发器本身只管是否被触发」同口径）。若真机反复出现"GM 提前推演"，再按急停协议的形态补 |
