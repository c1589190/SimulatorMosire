# 给 AgentLib 侧的需求清单（simos 对接用）

> 来源：SimulatorMosire（simos）侧只读调查（`~/ProjectMosire/AgentLibMosire` 源码 + `~/.m2` jar 均已核对）。
> ★ **用户裁定**：「Agent 的上下文数据格式、Provider 配置等**都由 AgentLib 负责**」「我这边**只需要直接用、对接**」「**哪一层包需要对接，就把 AgentLib 安装在哪一侧**」。
> ⇒ 本文是 **simos 侧提出的对接需求**，供**隔壁改 AgentLib 的 Agent** 使用。**每条都给现状 / 需求 / 为什么 / 验收**。

---

## 〇. 总原则（用户裁定）

1. **数据格式与 Provider 配置归 AgentLib**：Agent 上下文、消息、路由、provider 配置的**格式与生命周期**由 AgentLib 定义
2. **simos 只对接**：不重造等价物（★ 现在 simos 自造的 `HttpLlmClient`/`LlmProvider`/`LlmProviderRegistry` 大部分是**重复**，将**删改对齐**）
3. **依赖就近**：**哪个模块需要对接，就在那个模块装 AgentLib**（simos 侧现状：`simos-sd` 与 `simos-app` 均已依赖 `agentlib-mosire`；若对接层只在 app，则**收敛到 app**）

---

## 一、必改项（simos 对接挡在这里）

### A1 ★★ `reasoning_content` 必须被处理（**实测踩到的坑**）

- **现状**：AgentLib 的 SSE 解析**刻意忽略** `reasoning_content`（见 `OpenAICompatibleLlmClient` 的 SSE 解析段）
- **实测**：用户的 provider（`deepseek-flash`）**是推理模型** —— `max_tokens=16` 时返回 `"content": ""`、内容全在 `"reasoning_content"`
- **需求**：AgentLib 需明确处理该字段。三选一（请 AgentLib 侧定）：
  1. 把它并入文本（`reasoning_content` 非空且 `content` 空时取它）
  2. 在 `LlmResponse` 上暴露为独立字段（如 `reasoning`）
  3. **视为"未完成"并抛错/标记**（★ 但**绝不能**静默返回空文本）
- **为什么**：否则会出现「**LLM 明明答了，我们读到空**」——**这类失败在日志里看起来像"没输出"，极难查**
- **验收**：对推理模型发一次小 `max_tokens` 请求，**能观察到 `reasoning_content` 被非静默地处理**（三种行为任一，但要**有明确可观测的表现**）

### A2 ★ `LlmRequest` 缺 `temperature` / `maxTokens` / `extraBody`

- **现状**：`LlmRequest = record(List<LlmMessage> messages, List<ToolDef> tools)`，**只有这两个组件**
- **需求**：支持**采样参数**（`temperature`、`maxTokens`）与**透传扩展体**（`extraBody`，给 Ollama/vLLM/网关的私有字段）
- **为什么**：simos 的 provider 配置里已有这些，判决场景需要**低温度**（可复现）；没有 `maxTokens` 时无法约束推理模型的花费
- **验收**：能通过请求携带 `temperature=0` 并**在 provider 端观察到生效**；`extraBody` 能原样透传

### A3 ★ Provider 配置的表达力：`ModelRoute` 缺 `timeout` 与协议

- **现状**：`ModelRoute = record(name, baseUrl, model, credentialsRef)`；**超时是 `OpenAICompatibleLlmClient` 构造器参数**；**无 `protocol` 字段**
- **需求**：请 AgentLib 决定以下三项归谁：
  1. **每 provider 的超时**（simos 侧有 `timeoutMs`）
  2. **协议/方言**（现在只有 "OpenAI 兼容" 一种；将来 Ollama/Anthropic 要不要区分？）
  3. **`baseUrl` 语义**：AgentLib 是 `POST {baseUrl}/chat/completions`（★ **`/v1` 要调用方写进 baseUrl**）—— ★ **请在 Provider 配置的文档/校验里明确此约定**（否则每个人都会踩）
- **为什么**：simos 的 GUI 配置页要让用户填这些；**约定不明 = 每个人各写一套**

### A4 ★★ Provider 配置的**读写**：`ConfigStore` 现在能不能**写**？

- **现状**：`FileConfigStore` 读 `config.json`（`llm.*` / `keys.*` / `agents.<id>.*`）+ `agents/<id>.json` + 环境注入；**`ModelProvider`/`LlmRouteLoader` 在 AgentLib main 里无人调用（只有测试）**
- **需求**：simos 要做一个 **provider 配置页（CRUD + 测试连接）**，因此需要：
  1. **可写**的配置存储（**增/删/改 provider**）—— ★ 若 `ConfigStore` 只读，请补写能力
  2. **枚举全部 provider**（`LlmRouteLoader.availableNames()` 已有，请确认是**权威入口**）
  3. ★★ **"从配置装配到 `ModelProvider`"的官方入口**（现在只给零件、不给总装；simos 不想自己写装配循环）
- **为什么**：用户原话是「**额外做一个简单的 provider 配置页面就行**」—— 页面必然要**写**
- **验收**：能给出一段"**从配置加载 → 得到可用 `LlmClient`**"的**官方示例**（3~5 行），simos 照抄即可

### A5 ★ 密钥形式的分歧：`ConfigApiKeySource` 只认 `keys.*` 且**拒绝环境变量**

- **现状**：`ConfigApiKeySource` 支持 `keys.<name>`，**明确拒绝 env-var 形式**；而 simos 自造的 `SecretRef` 支持 `ENV | FILE`
- **需求**：请 AgentLib 侧裁定支持面（二选一或都给）
  - ① **只 `keys.*`**（simos 对齐它）
  - ② **也支持 env / 文件引用**（部署更友好）
- **为什么**：这是**政策问题不是技术问题**，但**必须一方改**，否则两边各有一套"密钥怎么引用"
- **约束（不可协商）**：★ **密钥值绝不进日志 / 异常 / 事件 / argv / stdio**；读配置只打印**路径 + 长度**

---

## 二、便利项（不挡事，但会让对接更顺）

### B1 `chat(LlmRequest) → LlmResponse` 与"要一段文本"之间的落差
- simos 的 D 阶段 SPI 是 `String complete(LlmRequest)` ⇒ 需要**薄适配**。★ 若 AgentLib 愿意提供一个 `default String text(...)` 之类的便捷法，simos 连适配器都不用写。

### B2 `FakeLlmClient` 的复用
- simos 测试现在用 lambda 假客户端；若 AgentLib 的 `FakeLlmClient`（脚本化 + 耗尽即抛）可直接用，**请给出用法示例**（simos 侧改用它，少一套自造）。

### B3 `LlmQuota` / `LlmException` 的语义
- simos 的 `LlmDecisionAdjudicator` 有**失败降级（N13）**。请确认：**哪些异常表示"该降级"、哪些表示"该炸"**（现在 simos 捕 `RuntimeException` 一刀切）。

### B4 `ToolDef` 与 simos `AgentTool` 的关系
- `ToolDef` 刻意独立于 `io.mosire.agentlib.tool` ⇒ 若 simos 想把**工具**也交给 LLM，请说明"`AgentTool` → `ToolDef`"的官方转换点在哪。

---

## 三、simos 侧对应的改动（我方认领，**不在 AgentLib 改**）

| simos 现状 | 处置 |
|---|---|
| `simos-app/.../app/llm/HttpLlmClient`（自造 HTTP） | ★ **删**，改用 `OpenAICompatibleLlmClient`（+ 薄适配） |
| `LlmProvider` / `LlmProviderRegistry` | ★ **对齐 `ModelRoute`/`ModelProvider`**；★ **只保留 simos 特有**（文件持久化 / GUI CRUD / 掩码视图 / `ENV|FILE` 视 A5 裁定） |
| `LlmProviderResolver`（providerId → client，fail-closed） | ★ 保留（这是**决策人绑定**的领域语义，AgentLib 没有） |
| `sd.SetDecisionMakerProvider`（决策人绑定 provider，落 revision） | ★ 保留（**世界语义**，归 sd） |

---

## 四、待用户/AgentLib 侧裁定

| # | 问题 | simos 倾向 |
|---|---|---|
| **D-A** | 配置存储：改用 AgentLib `ConfigStore`（`config.json` 的 `llm.routes.<name>`）还是保 simos 的 `<store>/llm-providers.json`？ | 若 AgentLib **提供可写 ConfigStore + 装配入口** ⇒ **改用 AgentLib 的**（用户原话"都由 Agentlib 负责"） |
| **D-B** | 密钥形式：只 `keys.*`，还是也支持 env/file？ | 若 AgentLib 支持 env ⇒ 对齐；否则 simos 收窄或自带一层 |
| **D-C** | `reasoning_content` 的三种处理方式选哪个？ | **并入文本**（最不易踩），但**必须有可观测表现** |
| **D-D** | 依赖装在哪一层？ | 若对接层只在 `simos-app` ⇒ **收敛到 app**（`simos-sd` 是否还需 AgentLib 需复核） |

---

## 五、我未能核实的

1. ★ **`FileConfigStore` 是否可写**（只读了它的布局，没读到写方法）⇒ A4 的前提**待 AgentLib 侧确认**
2. ★ **`LlmRouteLoader.load` 是否**被 GSimulator/Brain 等宿主实际调用过**（本机 AgentLib 的 main 里无人调用）
3. ★ `~/.m2` 的 jar 与源码一致（已抽样核对：126 类、llm 包 15 类均在 jar 内、`javap` 签名与源码一致）—— ★ **但逐方法字节码未比对**
4. ★ GSimulator 的 `llms.json`/`gsim.properties`（**明文内嵌 key**）与 AgentLib 的配置**是两套**（格式/命名都不同）⇒ 若将来要统一，**是另一个话题**
