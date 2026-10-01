# LLM 供应商链路问题报告（致 AgentLib / 中转站侧）

> 报告人：SimulatorMosire 控制方　日期：2026-10-01　来源：GOV/Army 阶段 13-C「真 LLM 多决策人场景测试」
> 关联提交：SimulatorMosire `a37fdf5a`（场景 harness）；AgentLib `36ff486c`（`echoReasoningContent` 修复，已在本次实测中确认有效）
> 本报告**不含任何 API key**；所有请求均由调用方按既有 `config/llm-providers.json` 发出。

---

## 0. 摘要（两类问题，需分别处理）

| # | 现象 | 面 | 影响 |
|---|---|---|---|
| 1 | `mosire-flash`（`deepseek-flash`）chat/completions 返回 **HTTP 500**：`upstream error: do request failed` | 中转站 → 上游 | 该模型完全不可用；本次真跑被迫改用同站 `glm-5.3-flash` |
| 2 | 长会话推理调用 **120000ms 超时**：`LlmException: LLM 调用超时：120000ms 内未拿到完整响应（连接/响应头/SSE 流，供应商未在期限内完成）`，`llmCalls=0` | AgentLib 客户端 / 中转站流式 | 2/3 省级决策人反复失败，场景测试硬判据（无 TOOL_ERROR）被 12 条 blocker 判红 |

**已排除**：不是 SimulatorMosire 引擎/命令/权限/守恒问题——同一场景里三国中央决策人 **9/9 轮全部成功**、四类人员流转全部真跑且守恒为 0 失败、范围隔离正确；AgentLib 的 `echoReasoningContent` 修复也已实测有效（多轮 reasoning 回传不再 400）。

---

## 1. 复现环境

- SimulatorMosire：`refactor/class-first-economy @ a37fdf5a`（Java 21 / Maven）
- AgentLib：`agentlib-mosire-0.1.0-SNAPSHOT`（HEAD `36ff486c` 起含 `echoReasoningContent`）
- 中转站：`http://121.40.130.178:3000/v1`；`GET /v1/models` 列出 `deepseek-flash`、`glm-5.3-flash`
- 路由配置（`config/llm-providers.json`，仅列结构，不含 key）：
  ```json
  {"keys":{"mosire-flash":"<已配置>"},
   "llm":{"routes":{"mosire-flash":{
     "baseUrl":"http://121.40.130.178:3000/v1",
     "model":"deepseek-flash",
     "credentialsRef":"keys.mosire-flash",
     "timeoutMs":120000,
     "echoReasoningContent":true}}}}
  ```

### 复现命令（真场景，env 门控）
```bash
cd /home/cna/SimulatorMosire
SIMOS_REAL_LLM=1 SIMOS_GOV_SCENARIO_MODEL=glm-5.3-flash SIMOS_GOV_SCENARIO_ROUNDS=3 \
  tools/mvn-lock.sh -pl simos-app -am \
  -Dtest='RealLlmGovScenarioTest#multiLevelGovScenario' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
- 世界：compact 三国 class-first；每国 1 中央 GOV + 1 省 GOV，各绑一个真 provider 决策人（3 轮，共 18 次 `sd.RunDecision`）。
- 场景结果日志：`/tmp/gov-glm-run2.log`（本机）；矩阵行 `[GOV-SCENARIO-MATRIX]`。

---

## 2. 问题 1：`deepseek-flash` 上游 HTTP 500

- 探针（2026-10-01 03:41–11:00 多次一致）：
  ```bash
  curl -sS -m 60 http://121.40.130.178:3000/v1/chat/completions \
    -H "Authorization: Bearer <key>" -H 'Content-Type: application/json' \
    -d '{"model":"deepseek-flash","messages":[{"role":"user","content":"只回两字：在吗"}],"max_tokens":64}'
  ```
  返回 **HTTP 500**：
  ```json
  {"error":{"message":"upstream error: do request failed (request id: 202609301941572358280748268d9d6Sz61ekzu)",
            "type":"new_api_error","param":"","code":"do_request_failed"}}
  ```
- 同 key、同站、同探针换 `glm-5.3-flash` **HTTP 200**（约 2.5s），说明鉴权/网络/请求形状都正常，问题在 **`deepseek-flash` 路由到上游的那一段**。
- 影响：`mosire-flash` 是 SimulatorMosire 默认真 provider；故障期间所有真 LLM 场景/验收只能换模型或停摆。

**请求**：修复/恢复 `deepseek-flash` 上游；恢复后跑一次 `GET /v1/models` + 上述 chat 探针，确认 200。

---

## 3. 问题 2：长会话推理调用 120s 超时

### 3.1 原始证据（3 轮场景，12 条 blocker）
- 失败方：`dm-gov-province-1`、`dm-gov-province-2`（省级 GOV 决策人，第三国省 DM 及三国中央 DM 均成功）。
- 原始错误（每个失败轮两条 blocker：trigger 与 run 各记一次）：
  ```
  {"result":"failed","trigger":{"branch":"main","revision":49},
   "decisionMakerId":"dm-gov-province-1",
   "conversationId":"decision-maker:dm-gov-province-1",
   "llmCalls":0,"reason":"LlmException","abortedByBudget":false,
   "detail":"LLM 调用超时：120000ms 内未拿到完整响应（连接/响应头/SSE 流，供应商未在期限内完成）"}
  ```
- 单轮耗时：省级失败轮 `ms=180620`、`ms=225510`（>120s 超时阈值，首调即失败）；对照：中央成功轮 `ms=150402`、`llmCalls=7`（总时长超过 120s，但**每次 LLM 调用都在 120s 内完成**）。
- 矩阵统计：
  ```json
  {"rounds":3,
   "nations":[{"centralRunsOk":3,"centralRunsError":0,"centralDirectiveCount":3,"punished":false}, …
              {"centralRunsOk":3,"centralRunsError":0,"centralDirectiveCount":7,"punished":false}, …],
   "criteria":{"centralAnyDirective":true,"provinceDirective":true,"provinceIntentAction":false,
               "scopeDeniedObserved":true,"scenarioToolTypesCount":4,"allFourToolsRun":true,
               "punishedNations":0},
   "conservationFailures":[], "blockers":12}
  ```
- 测试唯一红的断言：`[不得有 TOOL_ERROR / 未捕获异常 / MCP 调用失败；发现即命中，不重试掩盖]`，全部 blocker 均为上述超时。

### 3.2 根因判断
1. `glm-5.3-flash` 是 reasoning 模型；省级会话更短但历史更长/工具轨迹更多，单次响应可能 > 120s（路由 `timeoutMs=120000`）。
2. AgentLib 的超时口径是**一次调用的总时长**（含连接/响应头/SSE 流）；若中转站对 SSE 做了**整段缓冲**（客户端在完成前收不到任何 chunk），任何 >120s 的推理都必然被判超时，即使上游仍在正常生成。
3. 中央会话成功说明流式/回传至少在某些情况下可用；但 timeout 文案与失败形态（`llmCalls=0`，一次未回）指向**总时长上限 + 可能的缓冲**，而不是"模型没答完"。

### 3.3 请求修复（按优先级）
1. **路由超时可配置并提高**：`timeoutMs` 120000 → 300000~600000（reasoning 模型建议按模型配置；AgentLib 侧可在 `LlmTransport` 增加"按模型/按路由"的 read timeout，或把默认 read timeout 提到 5 分钟）。
2. **区分超时阶段**：连接/响应头（应短，如 10–30s）与流空闲（应长）分开；总时长不应成为唯一判据。
3. **确认并启用 SSE 流式**：若中转站当前缓冲整流，请开启逐 chunk 下发 + heartbeat；若已流式，请确认长推理期间有 keepalive 事件，避免代理/客户端按空闲判死。
4. **错误可读性**（AgentLib 侧，可选但建议）：超时消息带上阶段（connect/header/idle）、已等待毫秒、模型名，便于区分"上游慢"与"链路断"。

### 3.4 验收判据（修复后由我方复跑）
- `deepseek-flash` 探针 200；`glm-5.3-flash` 在长会话下不再出现上述超时。
- 原命令 3 轮跑完后 `[GOV-SCENARIO-MATRIX]` 满足：
  - 每国 `centralRunsOk=3, centralRunsError=0`；
  - 每国 `provinceRunsOk=3, provinceRunsError=0`（当前缺失项）；
  - `criteria.provinceIntentAction=true`（省 DM 自主意图被观测）；
  - `blockers=[]`、`conservationFailures=[]`、`scopeViolations=[]`；
  - `allFourToolsRun=true`（当前已为 true，回归不得退化）。

---

## 4. 附：本次已确认修好的 AgentLib 项（供隔壁确认无回归）
- `echoReasoningContent`：三国中央决策人同一会话连续 3 轮、每轮 5–7 次 LLM 调用，**未再出现** `HTTP 400 The reasoning_content in the thinking mode must be passed back to the API`；多轮 reasoning 回传链路按预期工作。
- 结论：本报告的两个问题与 `echoReasoningContent` 无关，是两个独立项。

---

## 5. 附：不影响本报告的具名观察（SimulatorMosire 侧，已记录、不在本次修复范围）
- 中央决策人读省级 GOV 时返回 `NOT_FOUND: 单位不存在`（而非 `RESOURCE_DENIED`/不可见）：这是读口把"作用域外"与"不存在"折叠成同一表现的既有语义；"权限≠信息"仍成立（越权命令被裁决拒），但原始证据不够直白。控制方另行裁定是否区分。
- 省级 DM 有指令但 `provinceIntentAction=false`：本次主要受超时阻断；省侧自主触发工具意图的观测需在修复后重跑确认。
