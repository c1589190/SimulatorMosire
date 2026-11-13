# 节点编辑器重构实施计划（D-034~D-036）

> **状态**：进行中（2026-11-13 起）
> **设计依据**：D-034 / D-035 / D-036；调查报告两份。
> **原则**：先地基后界面；每切片独立可验证；不删除本地经济分支；不把旧经济/文档口径带进新实现。

## 阶段 0：清理（已完成）

- 15 个 worktree 移除；dirty 内容 WIP commit + 仓库外归档；
- `.superpowers/sdd` 9.6GB 删除；`test-world`/`run`/target 删除；
- 仓库 53GB → 1.8GB；66 个本地分支保留。

## 阶段 1：核心节点与地址

### P1.1 任意节点分叉（✅ 已实现）

- `Timeline.fork` 从“必须 head”改为“父 revision 必须存在”；
- `CommandBus.fork` 区分“分支不存在”与“目标节点不存在”；
- 测试：从历史 revision 分叉成功；未知源/未知 revision 拒绝；head 不动。

**验收**：`simos-core` 相关测试绿色；现有 fork 调用不回归。

### P1.2 节点表与 `treeXXX-tickXXX`（✅ 地址/读口已实现；存储表沿用 revisions）

- 新增节点概念：`(branch, tick)` 唯一；节点 = 可持久化状态点；
- 节点增量：相对父节点的 `WorldChangeSet`，由各模块 `between(父状态, 本节点状态)` 派生；
- 地址：`treeXXX-tickXXX/<type>[/<subtype>]/<id>`；
- 一个 tick 一个节点；同 tick 另一版本 = 新分支；
- 读口按 `(branch,tick)` 解析到节点。

**验收**：同一分支不能有两个同 tick 节点；新建分支可复用同一 tick；地址可唯一解析。

### P1.3 一 tick 一节点 / 中间 revision 清理（✅ editor 路径已实现：从源头不制造）

- 中间 revision 物理删除/压缩；
- `CombatRecord.atRevision` / `DecisionInfoEntry.at` / `Verdict.atRevision` 重映射到节点或改节点地址；
- checkpoint 只保留节点；
- 旧档处理：显式迁移或明确不兼容（需在实现前落一句）。

**验收**：节点链回放与压缩前逐字段相等；引用不断；体积显著下降。

## 阶段 2：世界文档系统（✅ 第一版实现）

- 文档根 `docs/`；地址→md 路径协议；
- 增 / 删（回收站 + 日期）/ 改（备份旧档）；
- 多版本、多名称、完整/部分地址/模块名称查询；
- 复用/扩展 `docs-mosire`；自然语言字段从领域模块迁出；
- GUI 映射：战役/阶段 → 文档入口。

**验收**：每个地址可建/改/删/查；回收站可追溯；领域模块不再存自然语言正文。

## 阶段 3：2+1 模式与模块前端（✅ 第一版实现）

- 地图、单位两个核心模式 + 战役 +1；
- 区域编辑并入地图；战役前端下放 army；单位类型小窗由模块提供；
- 模块前端资源机制（目录、classpath 聚合、注册清单、门禁）。

**验收**：三模式可进入；模块前端可加载；写白名单与工具组一致。

## 阶段 4：经济/生产/文化卸载（✅ 第一版 editor profile）

- 卸载 economy/production/culture 挂载与 app/其他模块调用；
- 拆 `PopulationUnitTimeParticipant`，时间推进保留 unit/army/gov/ga/actor/social 所需部分；
- 世界生成器不再 seed 经济；
- GUI/工具面/测试同步裁剪。

**验收**：时间推进不触发经济；保留模块可独立构建/启动/编辑/查询。

## 阶段 5：PNG 底图（✅ 第一版实现）

- 载入 PNG、hex 对齐、中心取色、颜色→地形分类、预览/应用；
- 底图持久化策略后定。

**验收**：真实 PNG 可提取地形并写入地图；可撤销/预览。

## 阶段 6：自然语言字段迁移（未完成）

- `RegionMeta.description`、`CombatRecord.text`、`CombatStage.text`、`SocialCity.props` 等既有自然语言字段仍需逐项迁到文档地址；
- 需要领域 schema 改造、旧档迁移策略与 GUI 专用文档嵌入；
- editor 新文档系统已就位，但本轮未动这些字段的持久化形状。
