<!-- 加载时机：本文件**不会**被 harness 自动加载；agent 用 `read`/`write`/`edit` 触达 `.superpowers/` 目录后由 harness 自动注入。 -->
<!-- 定位：**SDD 台账与证据的落点与形制**（写在哪、写成什么样）。目录本身不进 git，见 §一。 -->

# .superpowers/AGENTS.md —— SDD 台账与证据的落点与形制

## 一、落点（唯一，不许另立目录）

| 产物 | 落点 | 说明 |
|---|---|---|
| **实现架构账本** | `.superpowers/sdd/<topic>/impl-ledger.md` | 一个 topic 一份；`<topic>` 与派单书里的责任区同名（如 `2026-10-12-module-boundaries-freeze`） |
| **证据** | `.superpowers/sdd/<topic>/evidence/`（**同目录**） | 命令 + 真实输出 + md5 / 计数；变异自证与负向验证各自留证 |
| SDD 进度台账 | `.superpowers/sdd/<topic>/progress.md` | 逐任务推进记录 |
| 逐任务报告 | `.superpowers/sdd/<topic>/task-N-report.md` | 该任务的交账（含"我没做 / 没验证的"一节） |

★★ **`.superpowers/sdd/` 被该目录下 `.gitignore` 的 `*` 忽略 ⇒ 整棵不进 git**：内容只在本机留痕。
⇒ **要长期留存、跨会话 / 跨设备复用的结论，必须另写进 `docs/superpowers/**`（spec / plan / status）或根 `AGENTS.md` 索引到的长文**；
台账与证据**不是交付物**，`docs/**` 才是。引用台账时写明它是**本机留痕、不在版本库里**。

## 二、`impl-ledger.md` 五要素（实现 Agent 自己写）

1. **关键调查结论**：`file:line` 证据 → 结论 → 影响；
2. **自己的实现架构**：内部组件拆分、类 / 方法设计、实际数据流与调用次序；
3. **关键判断**：为什么这样拆、为什么不用另一条路线、调查中推翻过什么；
4. **偏离记录**：与约束设计书不一致之处及原因（主动记录，不隐瞒）；
5. **只记决策相关事实，不写流水账、不抄工具输出**。

★ 分工别混：**约束设计书**（控制方写，落 `docs/superpowers/specs/*`）冻结"世界必须长成什么样"；
**`impl-ledger.md`**（实现方写）决定"代码怎么把它长出来"；**报告**是交给控制方的交账（改动文件 / 编译结果 /
会改数值行为的清单 / 失效断言清单 / 未完成项）。三者各有落点，不要互相代替。

## 三、中期设定也遵守“目标运行设计先行”（2026-10-19 用户裁定）

责任区子 Agent 写 `impl-ledger.md`、`progress.md`、`task-N-report.md` 或任何中期设定时，**同样**必须先写：

1. 本责任区嵌在哪个目标运行阶段（引用 `docs/superpowers/specs/2026-10-19-d032-rural-runtime-target-design.md` 的阶段号或该主题的目标运行序列）；
2. 实际实现的数据流/调用次序与偏离原因；
3. 对父序列的影响表：顺序、预算/冻结、身份/持久化（Codec/ChangeSet）、失败/回滚、日志、测试；
4. 未做/未验证/blocker。

只写“改了哪些文件、编译过了”视为**交账不完整**，控制方不得据此进入下一责任区。规则全文见
`docs/agents/15-十四-目标运行设计与子设计嵌入.md` 与 `.agents/skills/target-runtime-design/SKILL.md`。
