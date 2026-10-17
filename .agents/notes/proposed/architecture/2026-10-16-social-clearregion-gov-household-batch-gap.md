# 既有缺陷候选：标准小世界的 `small-world` 区用 `simos.region.clearData` 恒不可清（2026-10-16）

> 状态：**proposed / 未归属批次**。本文只登记**已实测的现场与定性**，不含修复方案。
> 来源：D-031 P1 验收（S1 补口 + S2 黄金对拍）中顺手暴露；**判定为既有缺陷、非 P1 回归**。
> 本机证据（`.superpowers/sdd/**` 被 `.gitignore` 忽略、**不在版本库里**）：侧线工作树
> `/home/cna/SimulatorMosire/.claude/worktrees/parallel-side-module/.superpowers/sdd/2026-10-16-d031-p1-definition-migration/`
> 的 `test/findings.md` §7.1 / §8.1、`test/logs/s1b-probe-1.log`、`test/logs/s1b-planonly-run-2.log`、`test/logs/s2-export-*.log`。

## 现场（两棵树同形）

| 树 | 分支 / 基线 | region | head | 结局 | 首条拒因 |
|---|---|---|---|---|---|
| 侧线（P1） | `parallel/side-module` @ `03faf0a3` + P0/P1 在飞 | `small-world` | 4→**4** | `rejected` | `social.ClearRegion`：`家户 hh-unit:gov-central 的成员批次不在 groups 里: rural:-2_0:MALE:s0-1` |
| 冻结 P0 基线 | `.claude/worktrees/d031-p1-baseline`（detached `03faf0a3` + 124 个 P0 文件） | `small-world` | 4→**4** | `rejected` | **逐字相同** |
| 侧线 / 基线 | 同上 | `capital-province`（1 格） | 4→5 | 两树均 `committed` | — |

- 预览侧**一切正常**：`hexCount=18`、`hitCounts={social:590, actor:163, economy:57}`、5 条命令（`social.ClearRegion` → `actor.ClearRegion` → `economy.ClearRegion` → [P1 多一条 `production.ClearRegionDefinitions`] → `ga.PutInfo`）。
- 提交侧**必拒**，且拒因出在**第一条** `social.ClearRegion`；P1 新增的 `production.ClearRegionDefinitions` **根本没轮到执行**。
- 原子性本身**正确**：拒绝时零 revision、零切片变化（`SimulationState.modules()` 全切片逐值不变）。

## 定性依据（为什么不是 P1 回归）

1. 拒绝发生在 **P1 未触碰的 social 域**（`simos-social` 的 `ClearRegion` 处理器）。
2. **冻结 P0 基线同世界、同区域复跑，拒因逐字相同**（S2-c 对照，见上表）；可达区（`capital-province`）两树同样可清。
3. 因此这是 **P0 之前就存在**的行为：`social.ClearRegion` 处理不了"gov 创世造出的 unit 家户成员批次"（`hh-unit:gov-central` 的成员批次不在 `groups` 里）。

## 影响

- 标准创世小世界的 `small-world`（18 格）区域**事实上不可清**：GM/世界生成流程里只要涉及该区的清空，就会撞上这条具名拒绝。
- 不影响 D-031 P1 的定义迁移正确性（黄金对拍 32857 项业务读数逐值相等）。

## 建议（未实施，交后续批次裁定）

- 归 **social × gov 创世** 侧调查：`social.ClearRegion` 的 `groups` 与 `hh-unit:*` 成员批次形状是否本就该兼容；是修 `ClearRegion`、还是修 gov 创世的家户/批次造法，需该责任区回代码核后定。
- 复现入口（本机可跑）：侧线树 `tools/mvn-lock.sh -pl simos-app -am -Dtest=D031P1PlanOnlyAndBatchAtomicityTest -Dsurefire.failIfNoSpecifiedTests=false test`；两树对拍脚本 `…/test/compare-golden.py <p1.json> <p0.json>`。

## 未证明

- **未诊断根因**：只做了"P0/P1 同形"的定性，没有回读 `social.ClearRegion` 的 `groups` 构造与 gov 创世家户批次的生成代码，也没有判定修在哪一侧更正确。
- 未在**其他世界**（`three-powers` / `rich` / `corridor`）复跑同一场景。
