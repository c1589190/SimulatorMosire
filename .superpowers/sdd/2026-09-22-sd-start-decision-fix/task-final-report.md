# 关账报告：去掉「战斗是决策前提」的 gate（`sd.StartDecision` 无条件走判决）+ 409 重试

> 分支 `fix/sd-start-decision`（worktree `.claude/worktrees/sdfix`），基线 `ff7719c`（主树 HEAD）。
> 用户裁定（逐字）：「**这是一个 bug**，对于任意决策人来说，**战斗是一个状态**，**决策是随时可以做的**」
> ⇒ **`StartDecision` 必须无条件走判决（调 LLM）**；战斗/情报只是判决的**输入**，不是 gate。

---

## §〇 根因与修法（一句话）

| 项 | 内容 |
|---|---|
| **根因** | `DecisionAdjudicationService.adjudicate` 里：`firstCombat(sd)` 为空 ⇒ `return List.of()`（打日志"跳过判决"）⇒ **LLM 一次没被调**，命令却成功（`main@8/9/10` 只有 `sd.StartDecision`，无 verdict）。 |
| **修法** | 删掉该 gate：**有战斗** ⇒ 跑 D1/D3/D6（主体 `sd:combat.<id>`，现状不变）；**无战斗** ⇒ 跑 **D2**（该决策人自己的决策记录，主体 `sd:decision-maker.<id>`）⇒ 判决照常落 revision。战斗/情报/世界状态进简报（输入），**不作前置**。 |
| **哪些断点有战斗前提** | **D1/D3（阶段 exit）/ D6（战斗收尾）** — 只在**存在战斗**时跑；**D2 无战斗前提**；D4/D5/D7/D8 的产出是草案、本服务不替它们编草稿（v1 口径）。 |

### 取代说明（★ 与 spec §八.2 的偏差，已具名）

- spec §八.2 的 **D2 行**把工具记为 `sd.IssueDirective`（出令）。**本实现把 D2 的判决记录落成 `Verdict`（`sd.SubmitVerdict`）**，理由：按**三件事模型**（「建议」≠「开始决策」≠「决策人出令」），「开始决策」触发的是**判决**（数据、落 revision、可回放），出令是**另一条**命令；若 `StartDecision` 直接写 `Directive`，会占掉该 tick 的 **R4** 名额，把 T10 已关账的设计决定推翻。⇒ D2 的**判决面**取 `sd:decision-maker.*`，出令仍走 `sd.IssueDirective`（两条路互不占名额）。
- 载体：`simos-sd/.../adjudication/Breakpoints.java` 新增 **决策判决面**（`COMBAT_VERDICT_BREAKPOINTS` / `DECISION_VERDICT_BREAKPOINTS`），`VerdictFreezer` 的拒绝消息同步。

### ★ 连带发现（先决条件缺口，规范级）

**活实例里的两个决策人 `providerId=null`（从未绑定 provider）**。去掉 gate 后，未绑定会 **fail-closed**（`E_LLM_PROVIDER_UNBOUND` → 500）——这正是"只在空库 `--demo`/经 GUI 建决策人、却从不绑 provider"的真实形态。派单 §4 预料到"provider 配置要落到 `ConfigStore`"；**本条补一半：决策人本身也要经 `sd.SetDecisionMakerProvider` 绑定**。e2e 已按此先绑后点。

---

## §一 改动清单（显式）

| 文件 | 改动 |
|---|---|
| `simos-sd/.../adjudication/Breakpoints.java` | 判决面拆两面（战斗 D1/D3/D6 ↔ 决策人 D2）；`producesVerdict` 含 D2 |
| `simos-sd/.../adjudication/VerdictFreezer.java` | 拒绝消息/类注同步两面 |
| `simos-app/.../sd/DecisionAdjudicationService.java` | **删 gate**；无战斗走 D2 + `decisionBrief`（事实性）；`adjudicatorFor` 仍在 gate 之前（未绑定照旧 fail-closed） |
| `simos-app/.../sd/AdjudicatorRunner.java` | 类注：D2 也自动落盘 |
| `simos-app/.../llm/AdjudicationEndToEndTest.java` | 新增 **无战斗** 用例（D2 → 真 AgentLib 客户端 → Verdict 落 revision）；genesis 可去战斗；stub 支持 D2 |
| `simos-app/.../sd/StartDecisionEndToEndTest.java` | C18 夹具：DM 绑一个**不可达** provider ⇒ 判决走 N13 降级（无 verdict/no revision）⇒ 原 C18 命令/权限断言**逐条保留** |
| `simos-app/.../webui/panels.js` | 成功分支推进游标（避免下次点击用旧 revision）+ **409 ⇒ 重取 head、拉游标到 `current.revision`、自动重试一次** |
| `simos-app/src/test/js/decision-mode.test.cjs` | 新增 3 条（成功推进游标 / 409 重试一次 / 不无限重试） |
| `run-gate.cjs` + `gate-contract.test.cjs` | 下界 **201 → 204**（两处同改） |

---

## §二 门禁（★ 现场重算；只取模块汇总行）

- **最终绿轮**：`.superpowers/sdd/2026-09-22-sd-start-decision-fix/logs/clean-verify.attempt1.log`（md5 `911686ac642e8f7e3422fdb12b677c73`）。**第 1 次尝试**。
- `./mvnw clean verify`：**rc=0**、**8/8 `SUCCESS [`**（`SimulatorMosire`/`UtilSimos`/`MapSimos`/`SocialSimos`/`UnitSimos`/`CoreSimos`/`SDSimos`/`SimosApp`）、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=204 pass=204 fail=0`。
- **模块用例（现场从日志 `Tests run:` 汇总行取）**：`170 / 369 / 45 / 259 / 179 / 141 / 243` = **1406**。
- **基线现场重算**（另一 detached worktree 真跑 `ff7719c`，`logs/baseline-ff7719c.log`，md5 `693437028c2e9e96be48172302de6255`）：`170/369/45/259/179/141/242` = **1405**，前端 **201/201**。
  - **delta 干净**：`simos-app 242→243`（+1 = `AdjudicationEndToEndTest` 新增的无战斗用例）；前端 201→204（+3 = 上述 JS 断言）；其余模块**逐值不变**。
- ★ **最终绿轮跑的就是当前字节**：变异轮 `cp` 逐字节还原后，两个被保护文件的 md5 等于变异前记录：`DecisionAdjudicationService.java = d4da347d69ff36547c159479ec4b88ed`、`panels.js = ca0ccf748f2db6f5073880539381a568`。

---

## §三 变异（2 体 2 KILLED / 0 存活；装置 `mutants/mut-run.sh`，自证在日志内）

| 轮 | 变异 | 判据 | 红点（被保护断言） | 自证 |
|---|---|---|---|---|
| **m1** | 把「无战斗 ⇒ 跳过判决」的 gate **加回去**（`return List.of();`） | `AdjudicationEndToEndTest` | `withoutAnyCombatTheDecisionMakerStillGetsAdjudicatedAndTheVerdictLandsARevision:128` —— `Expected size: 1 but was: 0` | `orig_md5=d4da347d…`、`mutant_md5≠orig`、`compilation_errors=0`、`restore identical=YES`、`KILLED` |
| **m2** | 409 分支**不再重取+重试**（只提示） | `run-gate.cjs` | `start-decision-retries-once-after-409-with-the-fresh-head` + `start-decision-does-not-retry-beyond-once`（`fail 2`） | `orig_md5=ca0ccf74…`、`mutant_md5≠orig`、`compilation_errors=0`、`restore identical=YES`、`KILLED` |

逐轮日志：`mutants/logs/m1.log` / `m2.log`（含 `target=`、两侧 md5、`byte_differs=YES`、`gate_rc=1`、`restore … identical=YES`、`verdict=KILLED`）。

---

## §四 ★ 端到端验收（**唯一打真外网的一步，显式标注**）

装置：`e2e/e2e.sh`（真 `ShellMain` + worktree 的 shaded jar，端口 5831/5832/5833/5834）。
世界：`/tmp/small`（**活实例 5817 的 store**）的**一致副本** `/tmp/sdfix-e2e`（sqlite `.backup` + checkpoints + agentlib）——**2 个决策人、0 场战斗**；**全程未动 5817**。
provider：`/tmp/small/agentlib/config.json` 的 `mosire-flash`（`baseUrl=http://121.40.130.178:3000/v1`，`credentialsRef=keys.mosire-flash`）。

### 4.1 链路（`e2e/start-decision.json` + `e2e/llm-responses.log`）

```
[绑定] POST /api/sd/set-decision-maker-provider dm-dashu->mosire-flash  ->  200  main@10→11
[409]  POST /api/sd/start-decision expectedRevision=10（过期）          ->  409  {"result":"conflict","current":{"branch":"main","revision":11}}
[重试] POST /api/sd/start-decision expectedRevision=11（current）      ->  200  {"result":"committed","ref":..., "adjudication":[{"kind":"accepted","payloadLength":341}]}
app.log: LLM 判决响应 model=deepseek-flash inputTokens=282 outputTokens=312 reasoning=SEPARATE
app.log: 命令提交: type=sd.SubmitVerdict commandId=adjudicated:D2:12  新坐标=main@13
app.log: access POST /api/sd/start-decision -> 200 3050ms
```

`GET /api/sd/verdicts`（`e2e/state-after.txt`）——**真 LLM 判决**：
```json
{"id":"adjudicated:D2:12","breakpoint":"D2","subject":"sd:decision-maker.dm-dashu","atRevision":12,
 "payload":"{\"breakpoint\":\"D2\",\"directiveId\":\"dir-dashu-D2-001\",
   \"intentText\":\"本 tick 无战斗对象，我大蜀不与西陵主动交兵：全军维持既定态势与戒备…\",
   \"commands\":[],\"rationaleText\":\"…战斗与情报仅作判决输入而非前提…\"}",
 "meta":{"model":"llm","promptVersion":"v1","inputBriefDigest":"digest:D2"}}
```
⇒ **无战斗世界里点一次「开始决策」⇒ provider 真被调 ⇒ 真判决文本落 `main@13`（`Verdict` @12）**。

### 4.2 外部 provider 原始字段（`e2e/provider-probe.json`，★ 显式标注：**直连 provider，非经 app**）

- `id`：`2ec005dd-e094-45bd-b23c-d9aa436cc99a`
- `model`：`deepseek-flash`
- `usage`：`prompt_tokens=42, completion_tokens=24, total_tokens=66, reasoning_tokens=18`

★ AgentLib 的 `LlmResponse` **不暴露** provider 的响应 `id`（llm-integration 报告 §六.1 同结论）⇒ app 路径只能给 `model`/tokens（见 4.1 日志），`id` 只能来自直连探针。

---

## §五 诚实披露 / 我未能核实的

1. **「点一次」是 HTTP 层等价物**：§四 用的是**按钮发出的那条 `POST /api/sd/start-decision`**，**没跑真浏览器**（本机 Chromium 版本与 Playwright 不匹配，是本仓长期遗留）。前端"409 自动重试一次 + 成功推进游标"由 `decision-mode.test.cjs` 的 3 条**纯函数/替身**断言覆盖，**未在真浏览器点过**。
2. **D2 落 `Verdict` 是对 spec §八.2 D2 行（`sd.IssueDirective`）的取代**，理由见 §〇；**未回填 spec 正文**（只写在 `Breakpoints` 类注与本报告）。若将来 spec 定"决策记录"的正式形态，需回填。
3. **D2 的简报格式是我定的**（无 spec）：只摆决策人自己的字段 + 世界已有清单（combats/nations），**不发明候选**；`guidance` 只复述 schema 字段要求。
4. **"有战斗"时仍只跑 D1/D3/D6，不额外跑 D2**：这样保持既有 `AdjudicationEndToEndTest` 的 2 组判决/`head 1→3` 逐值不变；"战斗时也做决策人自己的决策"**未实现**（无上游依据，不擅自加）。
5. **`D6` 在"有战斗但未结束"时仍会被跑**（沿用现状：只要存在战斗就 D1/D3/D6）；"D6 天然需要**战斗结束**"这条**更细的前提未加**（加了会改既有断言）。
6. **`Verdict` 载荷与决策语义的一致性不做校验**：`sd.SubmitVerdict` 只冻结 + schema 形状校验，不核对 `commands[]` 是否在命令白名单（出令走 `sd.IssueDirective` 时才校验）。本次实测模型输出 `commands:[]`（白名单为空）。
7. **决策人未绑 provider 时的 500 是 fail-closed 设计**（M11）；GUI 未做 4xx 友好归一——用户会看到 `{"error":"internal error"}`。**这条是本轮新暴露的 UX 缺口**，未修（不在本 bug 射程）。
8. **只对 `/tmp/small` 一份副本验过**；跨 provider / 并发 / 限流 / 超时未复现（`deepseek-flash` 一次调用约 3s）。
9. **`attemptStartDecision` 的 409 分支只在"服务端 409 且 body 带 `current`"下用 `current.revision`**；`current` 缺失时退回 `app.target().revision`（兜底路径**未单测**，由 `refreshState` 先跑兜住）。
10. **5817（pid 29731）全程未动**：worktree 产物与主树 jar 是不同文件（`/proc/29731/cwd` = 主树）；e2e 用 5831–5834，用后 kill 明确 PID（`e2e/app.pid`）。
