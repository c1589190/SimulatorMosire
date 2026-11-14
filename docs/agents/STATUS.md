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
| 分支 | `image-world-import`（独立 worktree，未推送） |
| HEAD | `c4679917`（`feat(image-world): 空白世界 + PNG 底图自动六角网格导入`，2026-11-14） |
| upstream | 尚无；`image-world-import` 独立分支，相对 `origin/main` 领先 32 个提交 |
| 工作树 | 干净；仅 `.opencode/` 未跟踪；`worktree/` 已清空 |
| 最近活动 | 2026-11-14：新增 `image-world` 空白世界 + PNG 自动六角网格导入（实测 1400×760 底图 86 列 → 4644 hex）；原圆柱面 WIP 留在主工作树未提交 |
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
| `tools/gate.sh full`（2026-11-13，最终） | **PASS**，耗时 **321s**；全仓 Surefire **3777 / 0 failures / 0 errors / 7 skipped**（505 报告）；前端 428/428；Spotless/Checkstyle/SpotBugs/shade/validate 全过 |
| `node simos-app/src/test/js/run-gate.cjs`（2026-11-13，前端优化后） | **PASS**，428/428（frontend-optimization 9 条 + 区域名/字号断言调整） |
| `tools/mvn-lock.sh -q -pl simos-app -am -Dtest=WebuiAssetsTest test` | 10 / 0 failures；含模块脚本单前缀回归 |
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
| **WebUI 实测报告** | `docs/superpowers/reports/2026-11-13-webui-browser-audit.md` |

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
2b. **图片底图空白世界已可用**：`--world=image-world` 起 0 hex 空白地图；WebUI 地图编辑 PNG 面板可载入底图、填网格列数、点“从底图生成六角网格”；新的 `map.ImportHexGrid` 只在空白地图上执行并原子建立 hexes/terrainBlocks。实测 1400×760 底图 86 列 = 4644 hex。当前只是平面网格，不处理额外连通性/wrap；底图是编辑会话临时层，生成后的地形会持久化。报告：`docs/superpowers/reports/2026-11-14-image-world-grid-import.md`。
2a. **地球比例地图已做导入试验**：`tools/build_earth_hex_map.py` + Natural Earth 110m + Equal Earth 等面积投影，3965 格 / 陆地 27.64% / 8 大陆区域；`gsimap_import.py` 当前 10 模块空切片兼容修复；editor 实起并验证 `map.SetTerrain`。这是**平面等面积投影**，不是球面环绕拓扑；真要“地球仪”需 Goldberg 六角化/五边形缺陷或经度接缝语义。详见 `docs/superpowers/reports/2026-11-14-earth-globe-hex-import.md`。
2. **自然语言字段迁移未完成**：`RegionMeta.description`、`CombatRecord.text`、`CombatStage.text` 等仍存正文；文档系统已就位，但领域 schema 改造与旧档迁移未做。另有 full profile/旧档的中间 revision 压缩未做；editor 新世界从源头不产生中间 revision。
3. **旧存档在 HEAD 不可读**：2026-11-13 实测，`run/small-world-db` 与 `test-world/store` 均因领域 schema 漂移无法打开；项目没有 schema 版本/迁移器。
4. **导入器不在 CI**：`tools/gsimap_import.py` 的 map 切片当前可读，但同库 `social/unit` 旧切片会让整库打不开；导入器/真实世界脚本未进门禁。
5. **官方一键脚本已坏**：`run-small-world.sh` 仍传 `--approval-port`（ShellMain 2026-10-23 已删除该参数），实测无法起服务且退出码为 0。
6. **经济卸载未做**：当前 Shell/GUI/MCP 仍装配全部模块；app 中 economy 引用 95 文件、production 35、social 82、actor 56、ga 70；unit/army/gov 的工具也依赖 social。卸载是代码手术，必须先确认暂停范围与最小 reactor。
7. **2+1 模式与模块前端已落第一版**：模式栏 = 常规/地图编辑/单位编辑/战役；区域编辑并入地图编辑；模块资产在 `webui/modules/<id>/`，有 manifest + `/api/webui/modules` 发现；`StaticHandler` 已修 `/webui/modules/...` 双前缀。
8. **世界文档系统已落第一版**：`docs/world/` create/edit/delete/versions/contentAt/search、HTTP `/api/docs`、MCP `simos.docs.*`、工作台文档面板、战役/阶段文档入口均已实现；**真实浏览器已测**搜索→结果→地址/标题/正文回填；仍缺文档事件日志与逐实体专用嵌入。
9. **PNG 底图已落第一版**：载入/offset/scale/透明度/中心 3×3 取色/最近色分类/预览/按地形分组应用；实测 overlay 只在 map-edit 显示，参数变化会使旧计划失效。仍缺持久化与自定义文件按钮。
10. **清理已执行（2026-11-13）**：15 个 worktree 移除、`.superpowers/sdd` 9.6GB 删除、`test-world`/`run`/target 删除；仓库 53GB → 1.8GB。本地经济分支 66 个全部保留；dirty 内容已 WIP commit + 仓库外归档（`/home/cna/simos-cleanup-20261113/`）。`.git` 949MB 历史与 `.m2-repo` 779MB 保留。
11. **分支已推送**：`feature/d036-node-editor` 已推送到 `origin`；`origin/main` 仍停在旧基线，未合并/未推送 `main`。
12. 历史 status 中的测试数字/工具计数只作历史证据；不得当当前事实。

## 7. 下一步

1. ✅ P1.1 任意节点分叉；P1.2a `tree-tick` 地址；P1.3 editor 一 tick 一节点（同 tick 原地合并 + 一推进一节点）。
2. ✅ P2a 文档服务；P2b 文档 GUI + 战役/阶段入口。
3. ✅ P3 2+1 模式 + 模块前端 manifest 发现；P4 editor profile 卸载 economy/production/culture；P5 PNG 取色/应用。
4. ✅ full gate 2026-11-13 最终 PASS（321s）：GovPayToolTest 空库等待 31s→3.75s；180 tick 模拟打 `multitick` tag 移出默认门禁；全仓 3777 / 0 / 0 / 7；前端 428/428。
5. **下一步第一命令**：`tools/mvn-lock.sh -Dsimos.excludedTestGroups= -Dtest=StagedAgricultureEconomy180Test -pl simos-app -am test`（需要跑 180 tick 长测时显式 opt-in）。
6a. **地球图下一步（待裁定）**：是否把 Equal Earth 六角图登记为正式世界；是否投入球面拓扑（Goldberg）而不是继续平面投影；若登记，补齐城市/单位/人口生成。
6. ✅ 地图编辑真实链路：编辑手势改用 `hexAt`（不再被 GOV/城市/交战图标挡住）；同 tick `writeEpoch` 贯通服务端/前端缓存，命令成功后地图与读口立即新态。浏览器实测 grep 证据见 WebUI 报告第 12/13 条。
7. **剩余工程**：
   - 自然语言字段迁移：RegionMeta.description、CombatRecord/CombatStage.text → 文档地址；
   - full profile/旧档的中间 revision 物理压缩与 `atRevision` 重映射；
   - 世界生成器 editor profile 不 seed 经济；
   - 决策人查看/编辑模式（暂缓）。
7. 未获推送授权前不 push；不删除本地经济分支；`.git` 不做 history rewrite。
