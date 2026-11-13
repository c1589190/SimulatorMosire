# SimulatorMosire 当前状态

> **更新时间**：2026-11-13 16:30（Asia/Shanghai）
> **本文是唯一实时状态入口。** 其他 `docs/superpowers/status/*` 文件都是某时点的历史快照。
> 当前事实与设计/维护裁定冲突时：设计/维护裁定优先；本文只记录“现在实际是什么”。

## 1. 当前目标

- **进行中（2026-11-13 起）**：按用户 **D-034 + D-035** 收敛产品方向——「文游地图编辑器 + 数据保存处理器」。
  - 一个状态机：时间线 + 节点式分支；**节点身份 = tick + revision（版本）**，同一 tick 允许多版本；
  - 历史节点不原地改写；要改地图就**新开节点**；**任意节点可分叉**；
  - 节点之间的中间 revision **物理删除/压缩**；只保留节点级增量；
  - GUI 收敛为 **2+1 大模式**（精确拆分待用户确认）；战役/区域编辑前端可下放相关模块；
  - 经济/生产/文化等模块**卸载挂载与调用**，时间推进不参与；
  - 用“每节点固定地址查询”（hex/region/unit/army/gov/battle）补位；
  - 原生世界文档系统（地址 → md；回收站/备份/多版本/部分地址/名称查询）；
  - PNG 网页底图与 hex 中心取色提取地形，**排在架构修改之后**。
- **当前阶段**：D-034/D-035 已入账；**现状调查已落两份报告**：
  `docs/superpowers/reports/2026-11-13-timeline-node-editor-current-state.md`、
  `docs/superpowers/reports/2026-11-13-node-version-docs-and-assets-current-state.md`（本轮补查）。
- **本轮不改业务代码、不跑 Maven**；未开始代码重构，未执行破坏性清理。

## 2. 源码基线

| 项 | 当前事实 |
|---|---|
| 分支 | `main` |
| HEAD | `4f2552ce`（`chore(worktree): 记录三棵经济性能测试工作树`，2026-10-23 23:22） |
| upstream | `origin/main`；`main` 与 origin 同步（ahead 0） |
| 工作树 | 干净；仅 `.opencode/` 未跟踪；本轮新增/修改的文档未提交 |
| 最近活动 | `main` 最后提交 2026-10-23；最新本地分支 `feature/catalog-hex-wage-integration` 2026-10-24 18:51；**截至 2026-11-13 已约 20 天无新提交** |
| reactor | **18 个 `simos-*` 模块**（根 `pom.xml`） |
| AgentLib | 本仓 POM 仍钉 `agentlib-mosire.version=0.2.3-SNAPSHOT`；版本切换未做 |

### 工作树目录

规范落点：`worktree/<name>/`。**实际 16 个**（2026-10-23 旧 STATUS 只列了 8 个，已过时）：

| 工作树 | 分支 / 状态 | 未提交状态（2026-11-13 实测） |
|---|---|---|
| `worktree/agentlib-dock` | `agentlib/dock-0.2.1` | 1 个未跟踪 `WORKTREE.md` |
| `worktree/catalog-residue-cleanup` | `feature/catalog-residue-cleanup` | 干净 |
| `worktree/d031-p1-baseline` | detached `03faf0a3` | 57 条未提交改动 |
| `worktree/economy-integration` | `feature/catalog-hex-wage-integration`（★ 与交接文档写的 `integration/economy-midterm` 不一致） | 干净 |
| `worktree/economy-test1` | `test/economy-test1` 派生的 `feat/economy-production-midterm` | 43 条 |
| `worktree/economy-test2` | `plan/economy-production-midterm` | 54 条 |
| `worktree/economy-test3` | `plan/economy-test3-midterm` | 35 条 |
| `worktree/endogenous-vacancy` | `feature/endogenous-vacancy` | 干净 |
| `worktree/ga-opt` | `ga/opt` | 3 条 |
| `worktree/hex-wage` | `feature/hex-wage` | 干净 |
| `worktree/integration-agentlib-side` | `integration/agentlib-side` | 干净 |
| `worktree/light-heavy-tools` | `feature/light-heavy-tools` | 干净 |
| `worktree/parallel-side-module` | `parallel/side-module` | 1 个未跟踪 `extensions.idx` |
| `worktree/production-catalog-config` | `feature/production-catalog-config` | 干净 |
| `worktree/wage-migration` | `feature/wage-migration` | 干净 |

> 三棵 `economy-test*` 是 2026-10-23/24 三路 Agent 经济评估/性能测试的产物；不是本轮 D-034 的实现分支。

## 3. 最近功能门禁（历史基线，2026-10-23；本轮未复跑）

来源：`docs/superpowers/status/2026-10-23-agentlib-side-integration-result.md`（历史快照）。

| 项 | 事实 |
|---|---|
| 主树全量门禁 | `tools/gate.sh full` **PASS**，耗时约 394s（集成树 386s，main 快进后同 tree 复跑） |
| Surefire | **3751 tests / 0 failures / 0 errors / 7 skipped**（504 份报告） |
| 前端 | **412/412** |
| Spotless / Checkstyle / SpotBugs | 通过 |
| shaded jar | **29,451,438 B** |
| 360 tick | `SIMOS_MULTITICK_SKIP_BUILD=1 tools/multitick-sim.sh rural-32hex-360d` **PASS**，75.9s；`cases=1 failed=0` |

> 2026-11-13 调查实测（非全量门禁）：当前 `simos-app` shaded jar 能起 `small-world` 与 `v17levant`（59,223 hex）空库；
> 前端门禁 412/412 PASS；文档检查 PASS。详见 `docs/superpowers/reports/2026-11-13-timeline-node-editor-current-state.md`（调查后补）。

## 4. 当前有效的设计与维护入口

| 类型 | 入口 |
|---|---|
| 根路由与常驻红线 | `AGENTS.md` |
| 文档系统规则 | `docs/AGENTS.md` |
| 项目维护裁定 | `docs/agents/MAINTENANCE_RULINGS.md` |
| 工程 Skill 目录 | `docs/agents/SKILLS.md` |
| 项目设计索引 | `docs/superpowers/AGENTS.md` |
| 设计裁定索引 | `docs/superpowers/DESIGN_RULINGS.md` |
| 用户设计原稿（D-001~D-035） | `docs/superpowers/specs/2026-10-02-architecture-design-source.md` |
| **D-034 产品收敛专题** | `docs/superpowers/specs/2026-11-13-timeline-node-editor-and-address-query-design.md` |
| **D-035 节点版本/文档/底图补裁** | `docs/superpowers/specs/2026-11-13-node-version-world-docs-and-asset-pipeline-design.md` |

## 5. 本轮文档/Skill 检查（已运行）

2026-11-13 记录 D-034 后实跑：

```text
python3 tools/check-docs.py
DOCS CHECK PASSED (modules=18 files=35 links=43)

python3 tools/skills-lint.py
SKILLS LINT PASSED (active skills=1)
```

> 本轮只改文档，不跑 Maven：没有 Java 行为证据。
> 2026-10-23 的文档系统重写检查（check-docs selftest 8/8、skills-lint selftest 6/6、`tools/gate.sh docs` PASS）仍是历史基线。

## 6. 未验证 / 风险 / blocker

1. **D-034/D-035 尚未工程化**：只有设计目标与专题 spec，没有实现计划/代码/验收；本 STATUS 不得被当作已实现。
2. **节点版本/压缩未定**：当前 `revision` 是分支内全局序号，不是 tick 内版本号；同一 tick 多版本需要新字段/节点表。中间 revision 删除会违反 `revisions` 自引用外键，并影响 `CombatRecord.atRevision` / `DecisionInfoEntry.at` / `Verdict.atRevision` 等持久引用。
3. **旧存档在 HEAD 不可读**：2026-11-13 实测，`run/small-world-db` 与 `test-world/store` 均因领域 schema 漂移无法打开；项目没有 schema 版本/迁移器。
4. **导入器不在 CI**：`tools/gsimap_import.py` 的 map 切片当前可读，但同库 `social/unit` 旧切片会让整库打不开；导入器/真实世界脚本未进门禁。
5. **官方一键脚本已坏**：`run-small-world.sh` 仍传 `--approval-port`（ShellMain 2026-10-23 已删除该参数），实测无法起服务且退出码为 0。
6. **经济卸载未做**：当前 Shell/GUI/MCP 仍装配全部模块；app 中 economy 引用 95 文件、production 35、social 82、actor 56、ga 70；unit/army/gov 的工具也依赖 social。卸载是代码手术，必须先确认暂停范围与最小 reactor。
7. **2+1 模式与模块前端未定**：当前 6 模式；所有 webui 资源在 simos-app，`StaticHandler` 单根，模块没有自带前端资源的注册/加载机制。
8. **世界文档系统只有候选底座**：`docs-mosire` 提供 LOG/FILE、地址、多版本、tick 查询；缺删除/回收站、备份、部分地址查询、名称别名索引、节点地址协议。
9. **PNG 底图未做**：前端有 `pixelToHex`/canvas/取色钩子与 `TerrainCatalog` 颜色；缺载入/对齐 UI、颜色→地形分类、预览/应用/持久化。
10. **清理未执行**：`.superpowers` 9.6GB（1.3GB tracked + 8.4GB ignored）、worktrees 40GB、test-world 1.7GB；9 条经济分支本地未推送、7 个脏树。清理清单见第二份报告 §6，未删任何文件。
11. **`main` 与远端同步**：2026-11-13 实测 `origin/main..main` 为 0；未获提交/推送授权前不动。
12. 历史 status 中的测试数字/工具计数只作历史证据；不得当当前事实。

## 7. 下一步

1. ✅ D-034 现状调查报告：`docs/superpowers/reports/2026-11-13-timeline-node-editor-current-state.md`。
2. ✅ D-035 补查报告：`docs/superpowers/reports/2026-11-13-node-version-docs-and-assets-current-state.md`。
3. **等待用户对第二份报告 §8 的口径补裁**：
   - 2+1 的精确拆分（地图+单位+战役？区域编辑并入地图？）；
   - 暂停范围（social/actor/ga 是否随 economy/production/culture 一起下线；gov 保留范围）；
   - `0tick-rev1` 的 `rev` 是 tick 内版本序号还是分支内全局 revision；
   - 中间 revision 删除后的 `atRevision` 引用重映射；
   - 世界文档根与 md 协议；清理授权层级（A/B/C/D/E）；
   - PNG 对齐/颜色分类/持久化。
4. 在口径确定前：不改业务代码、不合并经济分支、不删除旧模块/旧档/旧证据。
