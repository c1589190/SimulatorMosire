# .agents/AGENTS.md —— 决策记录与工程 Skill 局部规则

> **本目录属于项目维护。**
> - 当前实时状态：`../docs/agents/STATUS.md`
> - 文档系统规则：`../docs/AGENTS.md`
> - 维护裁定：`../docs/agents/MAINTENANCE_RULINGS.md`
> - 项目设计裁定：`../docs/superpowers/`

## 一、两个子目录

| 目录 | 放什么 | 不能放什么 |
|---|---|---|
| `.agents/skills/<name>/SKILL.md` | 可被宿主发现的**active** 工程 Skill | 候选/归档 Skill、Brain 运行时 Skill、密钥、本机绝对路径 |
| `.agents/notes/{proposed,implemented,archived}/<分类>/` | 决策解释：为什么这样定、被谁取代 | 当前状态、流水账、完整契约正文 |

## 二、Skill 规则

- active 唯一位置：`.agents/skills/<name>/SKILL.md`，目录名 = frontmatter `name`。
- 状态、候选位置、契约和目录表见 `../docs/agents/SKILLS.md`。
- 工程 Skill 与产品运行时 Skill 必须隔离；不得扫描 Brain 的 `SkillAccess`/`<dataDir>/skills`。
- 使用宿主技能发现机制；不硬编码 `skill_search` 作为通用前置工具。
- 改动后跑 `python3 tools/skills-lint.py` 或 `tools/gate.sh docs`。

## 三、Notes 规则

- 生命周期：`proposed` = 未定、无约束力；`implemented` = 已落地，代码/设计正本为准；`archived` = 被取代，只留痕。
- 一份 note 只记一个决策；命名 `YYYY-MM-DD-<slug>.md`。
- **项目设计裁定**：可以把“为什么”解释写进 note，但**权威正文**必须在 `docs/superpowers/specs/**`。
- **项目维护裁定**：权威正文在 `docs/agents/MAINTENANCE_RULINGS.md`，note 只做补充解释。
- 不写“当前进度/当前状态”；那些去 `docs/agents/STATUS.md` 或历史 status。
- 被取代的 note 不删除；移到 `archived/` 并在头部写取代者。
- 既有中文目录与 README 保留；目录级规则以本文件为准。

## 四、检查

```bash
python3 tools/skills-lint.py
python3 tools/check-docs.py
tools/gate.sh docs
```
