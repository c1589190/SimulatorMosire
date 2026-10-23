# 工程 Skill 目录与维护规则

> **本文件属于项目维护。**
> 工程 Skill 面向维护本仓库的编码 Agent，不是 Brain 运行时 Skill，也不共享运行时目录、加载器或权限面。
> **实际可发现的内容**只在 `.agents/skills/<name>/SKILL.md`；本文件只做目录、状态、契约和复核登记。

## 一、什么该进 Skill

按以下顺序分流，**不要把所有经验都做成 Skill**：

1. **项目设计硬约束** → `docs/superpowers/specs/**`（模块边界、数据所有权、命令/载荷、公式、工具归属）。
2. **项目维护硬约束** → `docs/agents/MAINTENANCE_RULINGS.md` / `docs/AGENTS.md`（文档、门禁、协作、提交、环境）。
3. **能机械判定的** → `tools/check-docs.py`、`tools/skills-lint.py`、`tools/gate.sh`、测试。
4. **只在某类重复任务中使用的判断流程** → `.agents/skills/<name>/SKILL.md`。
5. **当前状态/一次性证据** → `docs/agents/STATUS.md`、`docs/superpowers/status/**`、报告。

## 二、状态机

```text
proposed -> drafted -> pilot -> active -> deprecated -> retired
                         |          |
                         +----------+--(回归/宿主失配/越界)--> drafted / blocked
```

| 状态 | 位置 | 能否被宿主发现 | 进入条件 |
|---|---|---|---|
| proposed | `.work/skill-candidates/<name>/` | 否 | 有重复任务/返工迹象，先登记候选 |
| drafted | `.work/skill-candidates/<name>/` | 否 | 已写 `SKILL.md` 草案，契约自检通过 |
| pilot | `.work/skill-candidates/<name>/` | 否 | 已在真实任务中对照验证，仍只显式使用 |
| **active** | **`.agents/skills/<name>/SKILL.md`** | **是** | pilot 通过；有 owner、复核日、失败/退役条件；进入本目录表格 |
| deprecated | `docs/archive/skills/<name>/` | 否 | 失真、被替代、无 owner、维护成本大于收益 |
| retired | `docs/archive/skills/<name>/` | 否 | 归档/删除实现，只留历史说明 |

硬规则：
- 不跳过 pilot 直接 active；没有真实任务证据的 Skill 不进入 `.agents/skills/`。
- 任何导致误导执行的 Skill 先停用再修，不靠“仅供参考”续命。
- 复核逾期只触发复核，不自动退役。
- 候选/归档 Skill 不得放进 `.agents/skills/`，否则宿主会误发现。

## 三、当前 active Skill

| name | 状态 | 触发场景 | 路径 | owner | 最近复核 | 下次复核 | 退役条件 |
|---|---|---|---|---|---|---|---|
| `target-runtime-design` | **active** | 修改模块/类型、写设计/派单/中期确认前 | `.agents/skills/target-runtime-design/SKILL.md` | repo-maintainers | 2026-10-23 | 2027-01-21 或相关流程变更时 | 被新的目标运行设计流程取代，或连续在真实任务中失真 |

## 四、复用规范的分流实例

| 复用规范 | 落点 | 为什么不是 Skill |
|---|---|---|
| 领域工具必须住领域模块、app 只组装 | `docs/superpowers/specs/2026-10-12-ga-module-and-tool-descent.md` | 代码/架构设计裁定，属于项目设计 |
| 文档状态唯一入口、设计/维护分账 | `docs/AGENTS.md`、`docs/agents/MAINTENANCE_RULINGS.md` | 项目维护硬规则，属于全仓常驻约束 |
| 文档链接/模块 AGENTS/Skill frontmatter 检查 | `tools/check-docs.py`、`tools/skills-lint.py` | 能机械判定，放脚本 |
| 目标运行序列先于裁定/子设计 | `.agents/skills/target-runtime-design/SKILL.md` | 可复用的判断流程，适合宿主发现 |
| 每次门禁如何取证 | `docs/agents/VERIFICATION.md` | 项目维护操作手册，不是通用技能 |

## 五、候选与归档登记

| name | 状态 | 触发场景 | 位置 | 说明 |
|---|---|---|---|---|
| （当前无） | — | — | — | 新候选先放 `.work/skill-candidates/`，不直接进 active |

> 不按数量配额创建 Skill；也不因为“用得少”自动删除。

## 六、Skill 契约

`SKILL.md` 最小 frontmatter：

```yaml
---
name: <目录名>
description: <一句话说明何时使用>
status: active
owner: repo-maintainers
last_reviewed: YYYY-MM-DD
---
```

必备章节：
1. **何时使用 / 何时不使用**；
2. **前置条件**（目录、工具、权限、是否联网/跑 Maven）；
3. **步骤**（可执行、带工作目录）；
4. **Gate**（明确命令、退出码、必须核对的输出/证据）；
5. **Report**（固定字段：任务、基线、实际执行、改动、未做/未验证、证据边界）；
6. **失败与停止条件**（BLOCKED，不把没跑写成通过）；
7. **维护**（owner、复核周期、退役条件）。

体量与安全：
- `SKILL.md` 建议 ≤ 200 行，单文件 ≤ 32 KiB；
- 不写 `/home/<user>`、本机临时目录、密钥、token、真实配置；
- 不用 `@` 导入长文；
- 不硬编码 `skill_search` 作为工程 Skill 发现方式；交给宿主机制；
- 不复制仓库级根规则；需要时放链接。

## 七、机械检查

```bash
python3 tools/skills-lint.py
python3 tools/skills-lint.py --selftest
# 或
tools/gate.sh docs
```

检查内容：只允许一层 active 目录、frontmatter/目录名一致、status=active、必备元数据、大小上限、相对链接存在、目录和本表登记一致。
CI：`.github/workflows/docs-skills.yml`。
