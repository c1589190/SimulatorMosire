# docs/AGENTS.md —— 文档系统规则（项目维护）

> **本文件属于项目维护，不属于项目设计。**
> **用户原话（2026-10-23，逐字）**：
>
> 「我只说一点，那就是对项目具体代码的裁定和对项目维护的裁定是不一样的，前者属于项目设计的一部分，后者则是项目维护的一部分，这个得区分，其他没问题，按照如此规范重写本项目的文档系统」
>
> **当前状态入口**：[`docs/agents/STATUS.md`](agents/STATUS.md)。本文件只规定“文档放哪、谁说了算、什么时候写、怎么被检查”。

## 一、两条文档轴：项目设计 ≠ 项目维护

| 轴 | 收什么 | 正本位置 | 不放在哪里 |
|---|---|---|---|
| **项目设计** | 代码结构、模块边界、数据所有权、命令/ChangeSet/Codec、接口/工具面/载荷、运行时阶段、领域公式、生产/经济/文化等业务语义 | `docs/superpowers/specs/**`；最高权威是 `docs/superpowers/specs/2026-10-02-architecture-design-source.md` 里的 D-xxx 用户设计；跨切面索引在 `docs/superpowers/DESIGN_RULINGS.md` | 不写进 `docs/agents/MAINTENANCE_RULINGS.md`；不把维护流程塞进设计原稿 |
| **项目维护** | 文档系统、Skill 系统、门禁/验证、工作流、子 Agent 协作、工作树/环境、提交/归档/检查脚本 | `docs/agents/MAINTENANCE_RULINGS.md`、`docs/AGENTS.md`、`docs/agents/STATUS.md`、`docs/agents/SKILLS.md`、`docs/agents/WORKFLOW.md`、`docs/agents/VERIFICATION.md`、`docs/agents/ENVIRONMENT.md` | 不写进设计原稿；不冒充用户设计裁定 |

**分账规则**：
1. 一条规则只能有一个**主正本**。另一侧需要提及时只放链接，不复制全文。
2. 同一条用户原话同时影响两边 ⇒ 拆成两行：设计影响写入设计文档，维护影响写入维护裁定；两边各自给原话出处。
3. 代码行为“为什么这样”属于设计；代码怎么被验证/派单/提交属于维护。
4. 分不清时先停手，按用户原话补充分类；不得默认塞进 `AGENTS.md` 或旧长文。

## 二、文档分层

| 层 | 路径 | 放什么 | 何时加载 |
|---|---|---|---|
| 常驻路由 | `AGENTS.md` | 项目简介、冲突顺序、五条铁律、工具归属红线、文档/模块索引 | 总是 |
| 目录局部 | `simos-*/AGENTS.md`、`docs/AGENTS.md`、`.agents/AGENTS.md`、`.superpowers/AGENTS.md`、`docs/agents/AGENTS.md`、`docs/superpowers/AGENTS.md` | 本目录特有规则和局部索引 | agent 触达该目录后按祖先链加载 |
| 实时状态 | `docs/agents/STATUS.md` | 当前目标、源码基线、最近门禁、未验证项、下一步 | 每次开工/收工、上下文压缩前 |
| 维护裁定 | `docs/agents/MAINTENANCE_RULINGS.md` | 文档、Skill、门禁、协作、环境等维护裁定的**逐字原话 + 落点** | 需要改维护机制前 |
| 维护手册 | `docs/agents/WORKFLOW.md`、`VERIFICATION.md`、`ENVIRONMENT.md`、`FRONTEND.md` | 当前可执行流程、命令、证据规则 | 对应任务开始时 |
| 工程 Skill | `.agents/skills/<name>/SKILL.md` | 可被宿主发现的可复用判断流程；目录见 `docs/agents/SKILLS.md` | 任务匹配时由宿主发现/加载 |
| 项目设计 | `docs/superpowers/specs/**` | 设计原稿、专题设计、契约、运行时目标、不变量 | 设计/实现前 |
| 设计索引 | `docs/superpowers/AGENTS.md`、`docs/superpowers/DESIGN_RULINGS.md` | 设计目录规则、关键设计裁定索引 | 找设计依据时 |
| 计划 | `docs/superpowers/plans/**` | 进行中的实现计划；完成后归档/转决策记录 | 派单/执行前 |
| 历史快照 | `docs/superpowers/status/**` | 某时点的状态、交接、证据摘要；**不是当前状态入口** | 追溯历史时 |
| 报告/评审 | `docs/superpowers/reports/**`、`docs/superpowers/reviews/**` | 调查、证据、独立复核 | 评审/追溯时 |
| 决策解释 | `.agents/notes/{proposed,implemented,archived}/**` | 为什么这样定、被谁取代；**不替代设计或维护正本** | 需要决策背景时 |
| 机械强制 | `tools/gate.sh`、`tools/check-docs.py`、`tools/skills-lint.py` | 能退出码判定的检查；CI 见 `.github/workflows/docs-skills.yml` | 直接运行 |

## 三、权威顺序与取代关系

### 3.1 冲突顺序

1. 用户当次明确指令；
2. 项目设计裁定（设计原稿 / 当前有效设计）；
3. 项目维护裁定（`MAINTENANCE_RULINGS.md` / 本文件）；
4. 当前有效目标设计或契约；
5. 根 `AGENTS.md` 的常驻路由；
6. `STATUS.md` 中的当前事实（只覆盖事实，不覆盖设计/维护规则）；
7. 旧长文、历史 status、归档文档。

### 3.2 取代怎么写

- 被取代的文档**不删**，在文件头加：
  `> **状态**：历史（YYYY-MM-DD）· **取代者**：<路径> · **勿据此开工**。`
- 取代者一侧在标题下写：
  `> **本文取代**：<旧路径>（YYYY-MM-DD）。`
- 局部修正只标范围和取代者，不把整篇历史文档改写成“当前事实”。
- **不得按文件名日期推断有效期**；以正文状态头、用户原话日期和实际代码/测试为准。

## 四、记录频率：开发中期必须勤快

每次触发都要更新对应层，不能只留在聊天里：

| 触发 | 必须记录 |
|---|---|
| 用户给出新裁定 | 先分类：项目设计还是项目维护；设计写设计原稿/专题 spec，维护写 `MAINTENANCE_RULINGS.md`；都保留原话和日期 |
| 开始一个责任区 | 在 `STATUS.md` 写当前目标、范围、预期产物；计划落 `docs/superpowers/plans/` |
| 完成一个可验证步骤 | 更新 `STATUS.md`：改动、命令、rc、测试数、证据路径、未验证项 |
| 门禁/真模型/长跑完成 | 在报告或 status 快照留命令与真实输出；`STATUS.md` 只留摘要和链接 |
| 发现文档矛盾 | 记录双方路径/日期/事实；能判定的在旧文加取代头；不能判定的列入待裁定 |
| 子 Agent 交接或上下文压缩前 | 更新 `STATUS.md` 的下一步第一条命令、当前 dirty、未验证项；写清可继续工作的最小上下文 |
| 发现可复用判断流程 | 先登记 Skill 候选；经真实任务验证后再进 `.agents/skills/`，不要批量造空 Skill |
| 变更维护机制 | 先写 `MAINTENANCE_RULINGS.md` 条目，再改脚本/CI/目录；脚本是执行面，不是第二份正文 |

**当前状态唯一入口**：`docs/agents/STATUS.md`。任何 dated status 文件都只是历史快照。

## 五、STATUS 与交接模板

`docs/agents/STATUS.md` 至少包含：

```text
更新时间（绝对日期 + 时区）
当前目标 / 进行中范围
源码基线（分支、HEAD、upstream、工作树状态）
最近门禁（命令、rc、测试数、证据路径、是否 full）
当前有效设计/维护入口
未验证 / 风险 / blocker
下一步（可执行的第一条命令）
```

交接时另写一份 dated 文件也可以，但必须：
- 放在 `docs/superpowers/status/`；
- 文件头标 `历史快照` + 指向当前 `docs/agents/STATUS.md`；
- 不把 dated 文件叫作“最新/唯一入口”。

## 六、目录级 AGENTS 规则

- 每个有独立工作规则的目录可以有 `AGENTS.md`；根到 cwd 的每一级会被 harness 按祖先链加载。
- 局部文件必须短，写：本目录职责、局部边界、关键入口、验证命令、当前文档入口。
- 局部文件**不得复制**根五条铁律、不得另立用户裁定、不得把历史状态写成当前事实。
- 模块目录的模板见任一 `simos-*/AGENTS.md`；新增模块时同批补局部文件。
- 局部规则只在本目录范围生效；与项目设计/维护正本冲突 ⇒ 停手上报。

## 七、Skill 与规则的边界

不是所有经验都升格为 Skill：

| 规则形态 | 去处 |
|---|---|
| 项目设计硬约束（模块边界、工具归属、数据所有权、公式） | 设计文档 / 设计原稿 |
| 项目维护硬约束（文档格式、门禁、提交、工作树） | `MAINTENANCE_RULINGS.md` / 维护手册 |
| 能机械判定的内容 | `tools/check-docs.py`、`tools/skills-lint.py`、`tools/gate.sh`、测试 |
| 只在某类重复任务里使用的判断流程 | `.agents/skills/<name>/SKILL.md` |
| 当前事实和一次性证据 | `STATUS.md` / `docs/superpowers/status` / 报告 |

工程 Skill 的目录、状态机和契约见 [`agents/SKILLS.md`](agents/SKILLS.md)；不要用 Brain 运行时 Skill 目录代替它。

## 八、机械检查

在仓库根执行：

```bash
python3 tools/check-docs.py
python3 tools/skills-lint.py
# 或
tools/gate.sh docs
```

CI：`.github/workflows/docs-skills.yml`（只跑文档/Skill/脚本检查，不跑 Maven）。

检查目标包括：模块与 README/AGENTS 一致、相对链接存在、`STATUS.md` 存在且有关键字段、模块局部 `AGENTS.md` 存在、根入口不再指向 dated status、Skill 目录与目录文件一致。

## 九、引用与日期

- 引用用户原话必须**逐字**，日期取用户实际说出的日期；不是文件名日期，也不是提交日期。
- 现状事实要给 `file:line`；跑过的结论要给命令、rc、关键输出与证据路径。
- 没有跑过的，不写“通过”；只写 `NOT_RUN` / `未验证` / `BLOCKED`。
- 历史事实与当前事实分开；历史文档不回头篡改，只加状态/取代头。
- 长文一律用普通 Markdown 链接，不用 `@` 导入。
