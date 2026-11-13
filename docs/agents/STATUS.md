# SimulatorMosire 当前状态

> **更新时间**：2026-11-13 17:10（Asia/Shanghai）
> **本文是唯一实时状态入口。** 其他 `docs/superpowers/status/*` 文件都是某时点的历史快照。
> 当前事实与设计/维护裁定冲突时：设计/维护裁定优先；本文只记录“现在实际是什么”。

## 1. 当前目标

- **进行中（2026-11-13 起）**：按用户 **D-034 + D-035 + D-036** 收敛产品方向——「文游地图编辑器 + 数据保存处理器」。
  - 一个状态机：时间线 + 节点式分支；**节点地址 = `treeXXX-tickXXX`，弃用 rev**；同一 tick 的另一版本 = 新分支；
  - 历史节点不原地改写；要改地图就**新开节点**；**任意节点可分叉**；
  - 节点之间的中间 revision **物理删除/压缩**；只保留节点级增量；
  - GUI 收敛为 **2+1 大模式**：地图编辑（含区域）、单位编辑、战役 +1；战役阶段独立地址；
  - 经济/生产/文化等模块**卸载挂载与调用**，时间推进不参与；gov/ga/actor/social 保留；
  - 用“每节点固定地址查询”（hex/region/unit/army/gov/battle/stage）补位；
  - 世界文档系统（`docs/` 根；地址 → md；回收站/备份/多版本/部分地址/名称查询）；**领域模块只存结构化数据，自然语言归文档系统**；
  - PNG 网页底图与 hex 中心取色提取地形，**排在架构修改之后**。
- **当前阶段**：D-036 八个工程切片已落地（P1.1 分叉 / P1.2a 地址 / P1.3 editor 一 tick 一节点 / P2a 文档服务 / P2b 文档 GUI / P3 2+1+模块前端 / P4 editor profile / P5 PNG）；**剩余：领域自然语言字段迁移（RegionMeta.description、CombatRecord/CombatStage.text 等）与 full profile/旧档压缩**。
- **当前事实入口**：目标运行设计 `docs/superpowers/specs/2026-11-13-node-docs-runtime-target-design.md`；实施计划 `docs/superpowers/plans/2026-11-13-node-editor-refactor-plan.md`。

## 2. 源码基线

| 项 | 当前事实 |
|---|---|
| 分支 | `main` |
| HEAD | `1ad3ab7e`（`test(gate): 移除两处慢测根因`，2026-11-13） |
| upstream | `origin/main`；`main` 领先 origin 18 个提交（D-036 全切片 + 测试/格式修复），**未推送** |
| 工作树 | 干净；仅 `.opencode/` 未跟踪；`worktree/` 已清空 |
| 最近活动 | 2026-11-13：D-036 全切片（节点/地址/一 tick 一节点/文档/GUI/2+1/editor profile/PNG）落地；full gate PASS 402s；慢测治理中 |
| reactor | **18 个 `simos-*` 模块**（根 `pom.xml`） |
| AgentLib | 本仓 POM 仍钉 `agentlib-mosire.version=0.2.3-SNAPSHOT`；版本切换未做 |

### 工作树与清理结果（2026-11-13 执行）

- **`worktree/` 已清空**：15 个附属工作树全部 `git worktree remove --force` 移除；`git worktree list` 只剩主树。
- **本地经济分支全部保留**（66 个本地分支未删）：
  - `feature/catalog-hex-wage-integration`、`feature/catalog-residue-cleanup`、`feature/endogenous-vacancy`、`feature/hex-wage`、`feature/light-heavy-tools`、`feature/production-catalog-config`、`feature/production-catalog-hex-wage`、`feature/wage-migration`、`integration/economy-midterm`；
  - `integration/agentlib-side`、`plan/economy-production-midterm`、`plan/economy-test3-midterm`、`test/*`、`wip/d031-p1-baseline-20261113` 等也保留。
- **清理前已保存 dirty 内容**：
  - `agentlib-dock`、`economy-test1`、`economy-test2`、`economy-test3`、`ga-opt` 分别做了 WIP commit；
  - `d031-p1-baseline` 新建分支 `wip/d031-p1-baseline-20261113` 后做 WIP commit；
  - 仓库外归档：`/home/cna/simos-cleanup-20261113/`（tracked patch + untracked tgz，约 764KB）。
- **`.superpowers/sdd` 删除**：9.6GB → 8KB；`.superpowers/AGENTS.md` 保留；`.gitignore` 增加 `.superpowers/sdd/`。
- **`test-world/`、`run/`、各 `target/` 删除**：可重建/旧运行产物。
- **保留**：`.m2-repo`（779MB，离线构建缓存）、`.git`（949MB，历史）、`docs/`（13MB）。
- 仓库总体积：**53GB → 1.8GB**。

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

### 3.1 本轮已跑（FOCUSED，不是 full gate）

| 命令 | 结果 |
|---|---|
| `tools/mvn-lock.sh -q -pl simos-util,simos-core -am test` | util 224 / core 248，0 failures / 0 errors，rc=0 |
| `tools/mvn-lock.sh -q -pl simos-app -am -Dtest=QueryServiceTest ... test` | 17 / 0 |
| `tools/mvn-lock.sh -q -pl simos-app -am -Dtest=WorldDocsServiceTest ... test` | 8 / 0 |
| `tools/mvn-lock.sh -q -pl simos-app -am -Dtest=SimosToolsTest,ShellAssemblySmokeTest,McpServerTest ... test` | 65 / 1 / 5，全 0 failures；前端 412/412 |
| `tools/gate.sh full`（2026-11-13，速度治理前） | **PASS**，耗时 402s；Surefire 1078 / 0 failures / 0 errors / 7 skipped；前端 418/418；shaded jar 产出 |
| `python3 tools/check-docs.py` / `skills-lint.py` | PASS（links=44 / active skills=1） |

> `full` 门禁未跑；上述结论只能写 FOCUSED TEST PASSED。

## 4. 当前有效的设计与维护入口

| 类型 | 入口 |
|---|---|
| 根路由与常驻红线 | `AGENTS.md` |
| 文档系统规则 | `docs/AGENTS.md` |
| 项目维护裁定 | `docs/agents/MAINTENANCE_RULINGS.md` |
| 工程 Skill 目录 | `docs/agents/SKILLS.md` |
| 项目设计索引 | `docs/superpowers/AGENTS.md` |
| 设计裁定索引 | `docs/superpowers/DESIGN_RULINGS.md` |
| 用户设计原稿（D-001~D-036） | `docs/superpowers/specs/2026-10-02-architecture-design-source.md` |
| **D-034 产品收敛专题** | `docs/superpowers/specs/2026-11-13-timeline-node-editor-and-address-query-design.md` |
| **D-035 节点版本/文档/底图补裁** | `docs/superpowers/specs/2026-11-13-node-version-world-docs-and-asset-pipeline-design.md` |
| **D-036 节点地址/战役阶段/自然语言所有权** | `docs/superpowers/specs/2026-11-13-node-address-and-docs-ownership-design.md` |
| **目标运行设计（当前实现序列）** | `docs/superpowers/specs/2026-11-13-node-docs-runtime-target-design.md` |
| **实施计划** | `docs/superpowers/plans/2026-11-13-node-editor-refactor-plan.md` |

## 5. 本轮文档/Skill 检查（已运行）

2026-11-13 记录 D-034 后实跑：

```text
python3 tools/check-docs.py
DOCS CHECK PASSED (modules=18 files=35 links=44)

python3 tools/skills-lint.py
SKILLS LINT PASSED (active skills=1)
```

> 本轮已跑 Java 聚焦测试与前端门禁（见 §3.1）；**未跑** `tools/gate.sh full`，不得写成 full gate。
> 2026-10-23 的文档系统重写检查（check-docs selftest 8/8、skills-lint selftest 6/6、`tools/gate.sh docs` PASS）仍是历史基线。

## 6. 未验证 / 风险 / blocker

1. **D-034~D-036 工程主体已落地**：目标运行设计、8 个切片与聚焦测试已实现；**不是所有历史字段都迁移完**（见第 2 条）。
2. **自然语言字段迁移未完成**：`RegionMeta.description`、`CombatRecord.text`、`CombatStage.text` 等仍存正文；文档系统已就位，但领域 schema 改造与旧档迁移未做。另有 full profile/旧档的中间 revision 压缩未做；editor 新世界从源头不产生中间 revision。
3. **旧存档在 HEAD 不可读**：2026-11-13 实测，`run/small-world-db` 与 `test-world/store` 均因领域 schema 漂移无法打开；项目没有 schema 版本/迁移器。
4. **导入器不在 CI**：`tools/gsimap_import.py` 的 map 切片当前可读，但同库 `social/unit` 旧切片会让整库打不开；导入器/真实世界脚本未进门禁。
5. **官方一键脚本已坏**：`run-small-world.sh` 仍传 `--approval-port`（ShellMain 2026-10-23 已删除该参数），实测无法起服务且退出码为 0。
6. **经济卸载未做**：当前 Shell/GUI/MCP 仍装配全部模块；app 中 economy 引用 95 文件、production 35、social 82、actor 56、ga 70；unit/army/gov 的工具也依赖 social。卸载是代码手术，必须先确认暂停范围与最小 reactor。
7. **2+1 模式与模块前端未定**：当前 6 模式；所有 webui 资源在 simos-app，`StaticHandler` 单根，模块没有自带前端资源的注册/加载机制。
8. **世界文档系统已落第一版**：`docs/world/` create/edit/delete/versions/contentAt/search、HTTP `/api/docs`、MCP `simos.docs.*`、工作台文档面板、战役/阶段文档入口均已实现；仍缺文档事件日志与逐实体专用嵌入。
9. **PNG 底图未做**：前端有 `pixelToHex`/canvas/取色钩子与 `TerrainCatalog` 颜色；缺载入/对齐 UI、颜色→地形分类、预览/应用/持久化。
10. **清理已执行（2026-11-13）**：15 个 worktree 移除、`.superpowers/sdd` 9.6GB 删除、`test-world`/`run`/target 删除；仓库 53GB → 1.8GB。本地经济分支 66 个全部保留；dirty 内容已 WIP commit + 仓库外归档（`/home/cna/simos-cleanup-20261113/`）。`.git` 949MB 历史与 `.m2-repo` 779MB 保留。
11. **`main` 已领先远端 8 个提交且未推送**：`origin/main..main = 8`（2026-11-13 实测）；用户未要求 push 前不动。
12. 历史 status 中的测试数字/工具计数只作历史证据；不得当当前事实。

## 7. 下一步

1. ✅ P1.1 任意节点分叉；P1.2a `tree-tick` 地址；P1.3 editor 一 tick 一节点（同 tick 原地合并 + 一推进一节点）。
2. ✅ P2a 文档服务；P2b 文档 GUI + 战役/阶段入口。
3. ✅ P3 2+1 模式 + 模块前端 manifest 发现；P4 editor profile 卸载 economy/production/culture；P5 PNG 取色/应用。
4. ✅ full gate 2026-11-13 PASS（402s）；本轮又修 GovPayToolTest 30s 空库等待、把 180 tick 模拟移出默认门禁。
5. **下一步第一命令**：等本轮 full gate 复跑完成，确认 402s → 预期 ~320s；记录实际值。
6. **剩余工程**：
   - 自然语言字段迁移：RegionMeta.description、CombatRecord/CombatStage.text → 文档地址；
   - full profile/旧档的中间 revision 物理压缩与 `atRevision` 重映射；
   - 世界生成器 editor profile 不 seed 经济；
   - 决策人查看/编辑模式（暂缓）。
7. 未获推送授权前不 push；不删除本地经济分支；`.git` 不做 history rewrite。
