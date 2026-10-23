# SimulatorMosire 当前状态

> **更新时间**：2026-10-23 23:18（Asia/Shanghai）
> **本文是唯一实时状态入口。** 其他 `docs/superpowers/status/*` 文件都是某时点的历史快照。
> 当前事实与设计/维护裁定冲突时：设计/维护裁定优先；本文只记录“现在实际是什么”。

## 1. 当前目标

- **进行中**：按用户 2026-10-23 裁定重写文档系统。
  - 区分项目设计裁定与项目维护裁定；
  - 建立根索引、目录级 `AGENTS.md`、唯一 `STATUS.md`、Skill 目录、维护裁定台账与机械检查；
  - 本轮只改文档、检查脚本和 CI，**不跑 Maven**。
- 本轮未开始新的代码实现批次。

## 2. 源码基线

| 项 | 当前事实 |
|---|---|
| 分支 | `main` |
| 本轮开始前 HEAD | `8e6414c5`（`docs(integration): 标记主树集成与最终门禁完成`） |
| upstream | `origin/main`；本轮开始前 `main` 领先 106 个提交，**未推送** |
| 工作树 | 本轮开始前仅 `.opencode/` 未跟踪；本轮文档系统重写后另有未提交文档/脚本/CI/模块 AGENTS 改动 |
| reactor | **18 个 `simos-*` 模块**（根 `pom.xml`） |
| AgentLib | 本仓 POM 仍钉 `agentlib-mosire.version=0.2.3-SNAPSHOT`；是否切到已发布版以另行任务为准 |

### 工作树目录（2026-10-23 迁移后）

规范落点：`worktree/<name>/`，不再使用 `.claude/worktrees/`。

| 工作树 | 分支 / 状态 | 未提交状态（迁移后保留） |
|---|---|---|
| `worktree/agentlib-dock` | `agentlib/dock-0.2.1` | 1 个未跟踪 `WORKTREE.md` |
| `worktree/d031-p1-baseline` | detached `03faf0a3` | 57 条未提交改动 |
| `worktree/ga-opt` | `ga/opt` | 3 条改动/未跟踪 |
| `worktree/integration-agentlib-side` | `integration/agentlib-side` | 干净 |
| `worktree/parallel-side-module` | `parallel/side-module` | 1 个未跟踪 `extensions.idx` |

迁移使用 `git worktree move`，未手工 `mv`；分支、dirty 状态与未跟踪文件均保留。

## 3. 最近功能门禁（历史基线，不冒充本轮）

来源：`docs/superpowers/status/2026-10-23-agentlib-side-integration-result.md`（历史快照）。

| 项 | 事实 |
|---|---|
| 主树全量门禁 | `tools/gate.sh full` **PASS**，耗时约 394s（集成树 386s，main 快进后同 tree 复跑） |
| Surefire | **3751 tests / 0 failures / 0 errors / 7 skipped**（504 份报告） |
| 前端 | **412/412** |
| Spotless / Checkstyle / SpotBugs | 通过 |
| shaded jar | **29,451,438 B** |
| 360 tick | `SIMOS_MULTITICK_SKIP_BUILD=1 tools/multitick-sim.sh rural-32hex-360d` **PASS**，75.9s；`cases=1 failed=0` |

> 文档系统重写本身**未运行 Maven**：这是文档/脚本/CI 变更，不构造 Java 行为证据；文档检查结果见 §5。

## 4. 当前有效的设计与维护入口

| 类型 | 入口 |
|---|---|
| 根路由与常驻红线 | `AGENTS.md` |
| 文档系统规则 | `docs/AGENTS.md` |
| 项目维护裁定 | `docs/agents/MAINTENANCE_RULINGS.md` |
| 工程 Skill 目录 | `docs/agents/SKILLS.md` |
| 项目设计索引 | `docs/superpowers/AGENTS.md` |
| 设计裁定索引 | `docs/superpowers/DESIGN_RULINGS.md` |
| 用户设计原稿 | `docs/superpowers/specs/2026-10-02-architecture-design-source.md` |

## 5. 本轮文档/Skill 检查（已运行）

2026-10-23 文档系统重写后实跑：

```text
python3 tools/check-docs.py
DOCS CHECK PASSED (modules=18 files=35 links=41)

python3 tools/check-docs.py --selftest
SELFTEST PASSED check-docs.py (8/8 cases)

python3 tools/skills-lint.py
SKILLS LINT PASSED (active skills=1)

python3 tools/skills-lint.py --selftest
SELFTEST PASSED skills-lint.py (6/6 cases)

tools/gate.sh docs
DOCS CHECK PASSED
SKILLS LINT PASSED
[gate] docs 结论：PASS
```

> `tools/gate.sh pre` 也会跑这两个文档检查；本轮 pre 已 PASS。
> 本批不跑 Maven：变更范围是文档、检查脚本、CI 与局部 AGENTS；没有 Java 行为证据。

## 6. 未验证 / 风险 / blocker

1. **文档系统重写尚未提交/推送**：当前改动只在工作树，提交前必须逐文件审阅。
2. **工具归属迁移尚未完成**：领域工具仍在部分模块/app 之间迁移中；以最新设计/实现任务为准，不能把本 STATUS 当完成证明。
3. **AgentLib 版本切换未做**：本仓仍是 `0.2.3-SNAPSHOT`；是否切到固定发布版与 `CoreDecisionPackageReadPort` / `CoreDecisionSubmissionPort` 接线另立任务。
4. **`main` 未推送**：本轮开始前已领先 `origin/main` 106 个提交；未获提交/推送授权前不动。
5. 历史 status 中的测试数字/工具计数只作历史证据；不得当当前事实。

## 7. 下一步

1. 运行 §5 的文档/Skill 检查与 selftest；红了先修检查或修文档，不写假绿。
2. 回写本 STATUS 的真实检查结果和提交范围。
3. 后续代码批次按用户选择执行；工具归属迁移必须走项目设计文档，不把维护台账当设计稿。
