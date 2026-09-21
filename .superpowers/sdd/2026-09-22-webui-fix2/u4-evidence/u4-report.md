# U4 关账报告 —— 富世界改用截至 n0008 回合的最全区域

> 分支 `wsf2/u4`，worktree `.claude/worktrees/wsf2-u4`，基线 `9c4a987`（含 U1/U2/U3/U5）。
> 证据目录 `.superpowers/sdd/2026-09-22-webui-fix2/u4-evidence/`。
> 用户原话（feedback §一）：「**为啥不用截至 n0008 回合的最全所有区域？n0000 的区域构成是缺的**」。

## 〇 一句话

T11 的富世界复刻用的是 `n0000_map.json`（**98** 区域）；本任务新增可重跑脚本 `tools/materialize_v17levant.py`，
把 `n0000` 依次应用 `n0001~n0007` 的 MapDiff（province 口径照 GSimulator `MapResolver.applyDiff`）物化成一份
完整 `map.json`，再经 `tools/gsimap_import.py` 重跑复刻 ⇒ 签入资源升到 **252 区域**；对拍装置与 Java 断言按新语义同步。

## 一 交付物

| 文件 | 变更 |
|---|---|
| `tools/materialize_v17levant.py` | **新**：MapDiff 链 → 完整 `map.json`（只物化 provinces；含 `parentNodeId` 链完整性 fail-closed） |
| `simos-app/src/main/resources/worlds/v17levant.json` | 重跑复刻（4,219,540 B，`md5=1d817eee4d238c5255953e274a8f00f0`） |
| `tools/check_v17levant_import.py` | 期望值 98→252、tag 全 Nation、**新增区域逐格内容 sha256 冻结摘要**、可选第 3 参做「最全」证明 |
| `simos-app/.../app/demo/RichWorldTest.java` | `provincesAre98…` → `provincesAre252AllNation`（252 / 0 王国）；**新增** `carriesRegionsThatN0000Lacked`（5 个实名区域） |
| `simos-app/.../app/ShellMain.java` | `--demo` 日志文案 98 → 252 区域 |
| `docs/superpowers/specs/2026-09-22-webui-fix2-design.md` | 补 **§七 U4** 设计（口径 / 只物化 provinces 的取舍 / 判据 / 遗留） |

## 二 判据逐条实测（`logs/criteria.txt`，全部当场算出）

| 判据 | 实测值 |
|---|---|
| **区域数 252** | 物化源档 **252** / 签入资源 **252**（n0000 = 98）；链 n0000→n0007 = 98→104→114→145→148→223→240→**252** |
| **hex 数不变** | 源 **59223** / 资源 **59223** / n0000 **59223** |
| **河流边数不变** | 源 **240** / 资源 **240**（`edgeTags` 480 有向 ÷2）/ n0000 **240** |
| **地形直方图不变** | 源档原 key 直方图与 n0000 **逐值相同**（`hills 14107 / plains 11315 / lowland 16933 / water 14927 / mountain 1096 / desert 746 / swamp 99`）；资源 simos 5 类同 T11 |
| **覆盖面 18.67% → 32.37%** | n0000 **11057** hex（18.67%）⇒ 物化档 **19171** hex（32.37%） |
| **抽查「n0000 没有、最终有」的实名区域** | `瓦伦狄乌斯专制国`(578 hex) / `霜脊伯国`(19) / `大汉都护府政权`(42) / `艾达王国`(234) / `蒙特卡西诺修道院领`(10) —— 五个在 n0000 **均不存在**、最终均存在 |
| **多从属样例** | `(-105,67) → {区域14, 石冠诸部}`；全档 **444** 个多从属 hex；最大 **3** 从属 `{区域27, 大汉, 灰角伯国}` |
| **tag 分布** | 资源 **`{Nation: 252}`**（n0000 = `{Nation: 97, 王国: 1}`，`王国` 在 n0001 被覆盖） |
| **区域逐格内容 sha256** | `44174bb9435eb498b4eb564fd3664703249e0a2a503f32f23a5776b72e187418`（源/资源同） |
| 差异画像（相对 n0000） | 新增 **176**（占位 **108** + 实名 **68**）/ 删除 **22**（其中**改名 12**：hex 集合逐字相同）/ 存活 **76**（其中 hex 集合变过 **20**） |
| 附带发现 | 物化档首次出现**多环区域**（T11 的 n0000 全为单环）：`石冠诸部` 2 环 276 hex、`区域23734123412` 2 环 28 hex |

## 三 物化口径与取舍（★ 显式裁定）

- **province 口径照 GSimulator 权威实现**（`MapResolver.java:224-230`）：每份 diff **先 `remove(provinces_removed)`、再 `put(provinces_changed)`**（upsert = 整对象全量替换），按 n0001→n0007 顺序。
  另校验每份 diff 的 `parentNodeId` 与前一节点一致（`n0001.parent=n0000 … n0007.parent=n0006`，`n0008.parent=n0007`）。
- ★ **只物化 provinces，hex 段有意不应用**——**实测对照**（`logs/criteria.txt` 与本节）：
  | 方案 | hex 数 | 区域数 | 河流边（源档） | `hills` | `plains` |
  |---|---|---|---|---|---|
  | 只物化 provinces（**采用**） | 59223 | 252 | 240 | 14107 | 11315 |
  | 连 hex 段一起应用 | 59223 | 252 | **447**（894 有向） | **16489** | **9708** |
  判据锁定「hex/地形/河流边不变」；连 hex 段一起应用会**破坏这三条**，且 U4 的对象是「最全**区域**」。
  GSimulator 的 `applyDiff` 也应用 hex 段，本项目**有意不跟**——这是**取舍**，不是漏做。
- **可重跑**：同一输入两次运行输出 `md5=9929eeface65bb738df929475d5237ab` 逐字节相同。
- 物化档 → 导入器产出的 checkpoint 与签入资源**逐字节相同**（两处 `md5=1d817eee…`）。

## 四 铁律 1/2/5 与「真读路径」

- **不是绕过 `Command → ChangeSet → Revision`**：资源经 `bootstrapGenesis` 种入（CLAUDE.md 在案的**唯一**绕过 `submit` 的写路径、**只在空库**）。
- **真读路径读回 + 往返一致**（M6/T11 证法，**在最终门禁里真跑**）：`RichWorldTest.bootstrapsAndReplaysThroughTheRealEngine` —— `RichWorld.state` 经 `Envelope.decode` + 4 个真 codec 解出状态 → `bootstrapGenesis` 落盘 → `Replay((main,1))` 解码 **== 原状态**（真 `CoreSimos` + 真 SQLite）。
- `T12InfoRedlineTest.richWorldCarriesNoInfoAndTheWorldIsNotEmpty` 同路径再证：新资源仍 `info == empty`（`{`bySubject`: {}}`）。

## 五 门禁

- ★ **最终绿轮 = `logs/clean-verify.FINAL-GREEN.log`**（`md5=e959ce8b600a6708371127b88ee59d78`），**第 1 次尝试**，`rc=0`。
- **8/8 `SUCCESS [`**（显示名）：`SimulatorMosire / UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos / SDSimos / SimosApp`。
- 用例总数**现场重算**（只取模块汇总行）：**1360** = `170 / 368 / 45 / 259 / 178 / 129 / 211`。
- **基线现场重算**（基线 `9c4a987` 的干净 worktree 实跑 `clean verify`，`rc=0`）：**1359** = `170 / 368 / 45 / 259 / 178 / 129 / **210**`。
  ⇒ **delta 干净**：**app 210→211 恰 +1**（`RichWorldTest` 新增 `carriesRegionsThatN0000Lacked`），其余六个模块**逐字不变**。
- `BugInstance size is 0` **×7**；`[ERROR]` **0 行**；前端 `[frontend-gate] OK tests=184 pass=184 fail=0`（下界 184 实测，未改，故 `run-gate.cjs`/`gate-contract.test.cjs` 两处**都不动**）。

## 六 变异（装置 `mutants/mut-run.sh`，九道门禁：白名单 / md5 自证 / 清陈旧 `.class` 与陈旧资源 / `COMPILATION ERROR=0` / 逐字节还原 / 日志自指）

| 轮 | 靶子 | 结局 | 红点（被保护断言） |
|---|---|---|---|
| m1 | `materialize`：`DIFF_NODES` 里**漏 n0005** | **KILLED** | `parentNodeId` 链完整性守卫（n0005 缺失 ⇒ 链断裂，`rc=2`） |
| m1b | `materialize`：链照走但**丢掉 n0005 的 provinces** | **KILLED** | `provinces.count 187` + digest + `tags {Nation:187}` + `completeness 3/5` |
| m2 | `materialize`：**不应用 `provinces_removed`** | **KILLED** | `provinces.count 284`；`tags {Nation:283, 王国:1}` |
| m3 | `materialize`：upsert 退化成 **add-only**（已存在键不替换） | **KILLED** | ★ **仅 `region-content-digest` 抓到**（区域数仍 252）⇒ 该摘要判据不可省 |
| m4 | `materialize`：diff 链**倒序**应用 | **KILLED** | `parentNodeId` 链完整性守卫（倒序 ⇒ 首份 parent 不符） |
| m4b | `materialize`：倒序 + 去掉链校验（同一语义"顺序反了"） | **KILLED** | `provinces.count 281` + digest + `tags {Nation:281}` |
| **m5** | `materialize`：同一 diff 内**先 put 后 remove**（任务书点名靶子） | **SURVIVED（等价）** | 见下 |
| i1 | `gsimap_import`：`provinces = {}`（丢全部区域） | **KILLED** | `provinces.count 0` + digest + `tags {}` + `multi-owner 0` |
| r1 | **资源**：换回 T11 的 n0000 资源（`md5=76384c9f…`） | **KILLED** | `RichWorldTest.provincesAre252AllNation:88` + `carriesRegionsThatN0000Lacked:130` |

**m5 存活归因（等价变异体，非门禁漏跑）**：实测**没有任何** province 同时出现在同一份 diff 的
`provinces_removed` 与 `provinces_changed`（7 份 diff 的交集均为 **0**）⇒ 先 remove 还是先 put，最终
`provinces` 映射（键序 + 值）**完全一致**。m5 轮的物化输出通过了与权威档**相同的 252/digest 断言**
（`judge_rc=0`）即为直证。★ 但"顺序确实重要"由 **m4b（倒序 ⇒ 281）** 证明，而**链完整性守卫**把 m1/m4 的
乱序当场拒掉 ⇒ **顺序有护栏**，只是 m5 那种"同 diff 内换两条独立循环"在**本数据上**等价。
★ 另：**m3 是唯一需要 digest 才被杀**的一条——只数区域个数会放过它（add-only 后仍是 252 键）。

## 七 全仓 `98` 逐处核实（`logs/search-98.txt`）

| 位置 | 处置 |
|---|---|
| `tools/check_v17levant_import.py`（EXPECTED_PROVINCES / EXPECTED_TAGS） | ✅ 改 252 / `{Nation:252}`，并加 digest |
| `simos-app/.../RichWorldTest.java` 的 `hasSize(98)` / 97+1 | ✅ 改 252 / 252+0；新增实名区域断言 |
| `simos-app/.../ShellMain.java` 日志「98 区域」 | ✅ 改 252 |
| `docs/.../2026-09-22-webui-fix2-design.md` §五「U4 不做」 | ✅ 补 §七（记 U4 已落地） |
| `tools/check_v17levant_docs.py:11,38`（EXPECTED_PROVINCES=98 / 16+82） | ⏸ **保留**——叙事文档产物（见 §九） |
| `tools/v17levant_docs.py:35,122,134,184,189,222`（生成器文案「98 个区域」） | ⏸ **保留**——同上 |
| `tools/gsimap_import.py:99` 色号 `#68798C` | 误命中（十六进制），非断言 |
| `RichWorldTest.java:88` 注释「n0000 只有 98」/ `materialize` docstring | 史实说明，非断言 |

## 八 与既有约定的关系

- **只做 U4**：未改 U1/U2/U3/U5 的已关账实现（`map.js`/`panels.js`/`styles.css`/`index.html`/`ApiViews.java` 一行未动）。
- 未 `git add -A`、未 `mvn install`、未 kill `5818`、未动别的 worktree、未把任何断言改成恒真。
- **同文件串行**：`ShellMain.java` 被 T4/T11 改过（均已关账）；本次只改一行日志文案，无行为变化。
- **前端下界未调低**：本轮零 JS 改动，184 保持原值。

## 九 我未能核实的（如实登记）

1. **`docs/worlds/v17levant/*.md` 仍是 98 区域的叙事档**：它们是**离线文档产物**（从存档
   `checkpoints[].elements[]` 导出 615 条，与运行时世界是两件事）。U4 只改运行时世界 ⇒ 未重生成文档、
   未动 `v17levant_docs.py`/`check_v17levant_docs.py` 的 98/16/82 冻结值（**记为遗留**：若要把文档也对齐
   252 区域，是**另一个交付物**，会牵动 615/319630 与 16/82 标注）。
2. **进程级 `ShellMain --store <物化导入档>` 未重跑**：真读路径由 `RichWorldTest`（真 `CoreSimos` +
   真 SQLite + 真 codec 往返）覆盖；T11 的进程级 `overview` 观测**未在本次复核**（端口/实例动作留给控制器）。
3. **"最全"是相对 n0000 的**：只证了 handful（5 个）实名区域在 n0000 不存在、最终存在；未逐一枚举全部 176 新增。
4. **跨 JVM 字节稳定性未测**（物化脚本在同一 Python 下逐字节稳定已证）。
5. **`materialize` 只物化 provinces 的取舍**已量化（§三），但**未与 GSimulator 完整 `applyDiff`（含 hex 段）做端到端观感对比**。
6. 多环区域（`石冠诸部` 等）经**导入器 + Java 解码**通过（`Region` 边界硬校验接受），但**未逐环逐顶点与 py 对拍**（同 T11）。
