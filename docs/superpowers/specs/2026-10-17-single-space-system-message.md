# 2026-10-17 LLM system 消息固定单空格（约束设计书）

> 工作树：`agentlib-dock`，基线 `e26d49a4`。这是用户本轮追加的硬约束，不属于批 R 的角色权限设计。
> 本文补充 AgentLib 0.2.1 对接设计；仅在本项上扩展其“不动 LlmDecisionAdjudicator”的旧范围。不得改上游 AgentLib 的通用提示词能力。

## 0. 用户原话附录

2026-10-17，逐字记录：

> 我这边的要求是，系统上下文应该直接传一个空格，因为多种证据表面过长的系统上下文会导致模型性能衰减，加上现在正在测试，所以应当硬要求只传一个空格

“长系统提示导致性能衰减”是用户给出的实验背景，本批不扩展为适用于所有模型的研究结论。执行要求无条件成立：本项目发往 LLM 的 system 消息内容只能为一个 ASCII 空格（U+0020）。

## 1. 已核实现场

- `simos-ga/.../decision/DecisionAgentRunner.java:1376` 的每回合 `AgentConfig.systemPrompt` 已为 `" "`；`:1236` 组装 system，过滤窗口中的旧 system，再经 `:1405` 发请求。
- `simos-app/.../decision/DecisionAgentRunnerTest.java:567` 已断言 system 角色，`:569` 附近精确断言内容等于 `" "`。
- `simos-ga/.../adjudication/LlmDecisionAdjudicator.java:67` 将 `systemPrompt(breakpoint)` 交给客户端，`:144` 生成非空格裁决指令；这是当前不满足要求的生产路径。
- `simos-app/.../llm/LlmProviderResolver.java:190` 将该裁决请求的 systemPrompt 原样变成出站 system 消息。
- `simos-ga/.../adjudication/LlmRequest.java:16` 当前只校验 systemPrompt 非 null，仍允许任意长提示。
- 扫描命令：`rg -n 'LlmMessage\\.system|systemPrompt\\(' --glob '*.java' --glob '**/src/main/**' simos-*`。本轮发现的出站组装点即以上决策人与裁决器两条路径。

## 2. 外部契约与边界

1. 每条本项目生产 LLM 请求恰有一条 system 消息，内容逐字符为 `" "`；不能改成空串、换行、多个空格或省略消息。
2. 决策人的身份、规则、信件、快照、工具结果与历史仍按既有顺序放在非 system 消息中。不得新增重复上下文、将任何规则移入 system、改变预算或工具权限。
3. 裁决器原 system 提示中的断点身份、按 schema/合法选项裁决、不给新规则、只输出 JSON 等全部有效指令迁入 user 提示；原简报/schema/命令白名单/弃权约定必须保留，不能删掉指令来缩短 system。
4. 通过现有裁决 `LlmRequest` 手工构造不合规 system 提示，应在请求边界明确拒绝。合法值只有 `" "`，拒绝错误不能回显用户传入的提示词；本项目为 Alpha，不需要兼容旧提示格式。
5. `LlmProviderResolver` 的最终请求保持 system + user 两条消息、temperature=0、maxTokens=4096；错误分类与判决 schema 校验不变。
6. 单空格字面量/策略应有唯一生产来源，两条路径复用；内部类拆分与具体落点由实现者决定，遵守模块允许依赖。
7. 上述 system 消息与批 R 中的宿主内部 `ToolContext` / `AgentPermissionSet` 是两个概念。本批不赋予内部上下文任何新角色，不改变裸预览/规划边界。

## 3. 验收判据

- S1：真实决策人请求在首轮与后续轮次恰有一条 system 且内容精确为 `" "`；已有身份/历史顺序判据继续通过。
- S2：捕获裁决器交给客户端的请求，system 精确为 `" "`，user 中同时保留原裁决指令、断点、简报、schema、命令白名单和弃权约定。
- S3：裁决请求接受单空格，拒绝 null、空串、多空格、换行和普通长文本；异常不回显提示词内容。
- S4：验证适配层最终出站的消息角色和 system 内容，以及原采样参数；无需真实 LLM 或网络。
- S5：既有裁决成功/弃权/schema拒绝/LLM异常分类行为、决策人请求顺序用例维持通过。
- 负向自证：至少临时将单空格改成非空格文本，目标判据必须红，之后原样还原并核对摘要。只做关键项，不扩展为多轮审查。

## 4. 责任区与交账

一名生产实现代理负责全部生产代码及编译；先写本机账本 `.superpowers/sdd/2026-10-17-single-space-system/impl-ledger.md`。允许改 `simos-ga` 中以上两个调用者/裁决请求载体、共享提示词策略，以及 `simos-app/.../llm/LlmProviderResolver.java` 的必要接线；不改上游、不改批 R、不改其余提示内容或权限面。只跑本树 `tools/mvn-lock.sh -DskipTests compile -pl simos-app -am`，不写不跑测试，不提交。

全部生产代码完成后，一名测试代理统一调整有关夹具、落实 S1–S5，允许改 `simos-app/src/test` / `simos-ga/src/test` 的相关测试，临时变异必须还原。使用本树 Maven 包装器，`-pl` 必带 `-am`；不 package、不覆盖运行 jar、不提交。

交账包含：文件、实际命令/退出码/计数、与本文偏离、数值行为变化（预期仅提示词消息角色布局改变，无经济数值公式变化）、受影响测试字面量、未做/未验证项。
