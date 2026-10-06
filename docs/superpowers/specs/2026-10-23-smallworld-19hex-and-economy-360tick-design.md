# 2026-10-23 SmallWorld 扩到 19 hex + 经济 360 tick 实测（约束设计书）

> 本文件是 Phase 0（SmallWorld 扩格）的**派单前置约束设计书**（AGENTS §一.8）；
> Phase 1~3（建 GOV/军队、360 tick、日志对照）由控制方执行，不写代码。

## 1. 需求来源（用户原话，原样照录）

> 「没啥问题；现在开始修经济循环——经济循环的问题必须源自经济循环的执行本身，你先建一个17~20hex的真实Simos世界，
> 初始化一下经济模型，最好还掺一两个政府、军队之类的，然后跑个360tick经济执行，从日志中对照之前的问题表；
> 如果已经有保存的测试世界，那就重新回到tick0沿用即可，先确认有没有、准备怎么做，给我看」

用户 2026-10-23 选项裁定：世界规模 = **扩到 19 hex**；政府军队 = **2 GOV + 2 军队**；
推进 = **分 12 段每 30 tick**；对照问题表 = **P2 调查报告 A~E**。

## 2. 目标 / 非目标

**目标**：① `SmallWorld` 15 → **19 hex**（程序化、确定性、tick0 可重建）；② tick0 用真 GM 工具建 2 GOV + 2 军队（带军俸）；
③ 360 tick（12×30）实测并逐段落读数；④ 日志对照 P2 问题表 A~E，产出对照表（已闭环/仍复现/新发现）。

**非目标**：不改经济/社会/军队的任何语义与公式；不做平衡性调参；不迁移旧档；不动既有测试（测试由后续测试 Agent 统一处理）。

## 3. 现状事实（2026-10-23 回代码核，file:line）

- `simos-app/src/main/java/io/mosire/simos/app/world/SmallWorld.java`：
  `HEX_COUNT = 15`（:121）；`HEXES` 静态表（中心 + 第一环 6 + 第二环 8，:165 附近）；
  `LOW_HILLS_HEXES`（4 格）；`CAPITAL_AT = (0,0)`（:137）、`TOWN_AT = (0,2)`（:140）；
  人口 = `200×15 + 700 + 300 = 4000`；经济播种走 `production-runtime` 真命令序（social.SetPopulation → CreateCity×2 → SeedGroups → economy.Seed → actor.Seed）。
- `WorldRegistry.SMALL_WORLD = "small-world"`（`WorldRegistry.java:47/131`）。
- `run-small-world.sh`：15 hex 文案、缺省 store `run/small-world-db`、端口 5811/5815/5813；`run/` 已 gitignore。
- **无任何 `src/test` 引用 `SmallWorld`**（实测 0 命中）⇒ 扩格不会打断测试编译。
- 存档现状：**没有 17~20 hex 的 tick0 存档**（`run/small-world-db` 不存在）；`~/Simos-18Lvt` 是 59,223 hex（head 176），不作本批用。
- 问题表：`docs/superpowers/reports/2026-10-09-p2-population-economy-bug-investigation.md`
  （A 人口对账已修 / B 出生=0 复合 bug 仍是第一优先 / C 需求劳动已落地 / D Unit 多户 / E GOV·Army 补建路径；§12 有 360 tick 基线）。
- 建 GOV/军队工具（已核实存在）：`simos.gov.createOffice`、`simos.unit.spawnArmy`（已走 Social 权威）、
  `simos.unit.raiseUnit`、`simos.gov.recruit`、`simos.gm.armyPayPolicy`。
- 推进/日志：`/api/advance`；`JAVA_TOOL_OPTIONS` 可给 `tools/run-shaded.sh`（`exec java -jar 快照 "$@"`）传
  `-Dsimos.economy.logLevel=DEBUG` / `-Dsimos.economy.traceLevel=TRACE`。

## 4. 外部契约与不变量（不可破坏）

1. 世界 id 仍为 `small-world`；`WorldRegistry` 不动。
2. **19 格六邻连通**；全部属于同一 Region；`CAPITAL_AT`/`TOWN_AT` 不变且在格集内。
3. **确定性**：同一代码重建两次，hex 顺序、人口、地形分布逐值一致（`HEXES` 顺序即插入序）。
4. 人口公式不变：`RURAL_POPULATION_PER_HEX × HEX_COUNT + CAPITAL_URBAN_POPULATION + TOWN_URBAN_POPULATION`。
5. 地形只用 `plains`/`low_hills`；城市两格仍为 `plains`。
6. 经济播种仍走既有 `production-runtime` 真命令序，**不得改任何语义/常量口径**（只允许新增格与随之而来的地形/人口计数变化）。
7. **只改 `SmallWorld.java` 一个生产文件**；不动经济/社会/军队模块、不动 `run-small-world.sh` 与文案（15 hex 文案的更新留给后续单独小批）。

## 5. 验收判据与负向用例

- 编译：`tools/mvn-lock.sh -q -pl simos-app -am -DskipTests compile` rc=0。
- 正向：空 store bootstrap 后 `/api/map/overview` 报 **19 hex / 1 Region / 2 cities**；
  人口 = 公式推导值（3800 + 700 + 300 = **4800**，若实现 Agent 调整常量必须在账本说明）；
  连通性可经 `/api/map/hex` 或 dump 验证。
- 负向：无孤立格/重复格；`small-world` id 不变；bootstrap 只作用空库（不覆盖任何既有 15 hex 存档）。
- 数值行为：仅"世界形状/人口"改变（这正是本责任区目标）；经济公式零改动。
- 交账：改动文件 + 编译结果 + **实现架构账本** `.superpowers/sdd/2026-10-23-smallworld-19hex/impl-ledger.md`
  + 未验证项；账本记录：选了哪 4 个格、为什么、与本文不一致处。

## 6. 文件所有权

- ✅ 允许改/建：`simos-app/src/main/java/io/mosire/simos/app/world/SmallWorld.java`；
  `.superpowers/sdd/2026-10-23-smallworld-19hex/impl-ledger.md`（新建）。
- ❌ 禁止碰：其他任何文件（含 `src/test/**`、`docs/**`、`AGENTS.md`、`pom.xml`、`log4j2.xml`、`run-small-world.sh`）。

## 7. 后续（控制方执行，不写代码）

打包 → 起快照实例（独立 store `/home/cna/simos-runs/2026-10-23-sw19/`、独立端口 5831/5835/5833）→
GM 工具建 2 GOV + 2 军队（tick0）→ 12 段 × 30 tick 推进 + 逐段 dump → 日志对照 A~E → 对照表。
