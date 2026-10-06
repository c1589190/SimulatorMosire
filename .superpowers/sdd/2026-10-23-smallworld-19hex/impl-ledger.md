# SmallWorld 19-hex 实施架构账本

- 责任区：`SmallWorld` 从 15 hex 扩到 19 hex（确定性、tick0 可重建）。
- 约束设计书：`docs/superpowers/specs/2026-10-23-smallworld-19hex-and-economy-360tick-design.md`（§4 不变量 / §5 验收判据 / §6 文件所有权）。
- 基线：分支 `main`，HEAD `951ef28a`，工作树起始干净（只有约束设计书自身 untracked）。
- 可动范围：只改 `simos-app/src/main/java/io/mosire/simos/app/world/SmallWorld.java`；新建本账本。

## 1. 关键调查结论（`file:line` → 结论 → 影响）

1. `SmallWorld.java:173-192`（改前为 :164-180）——原 `HEXES` = 中心 + 完整第一环 6 格 + 第二环 8/12 格；第二环只缺
   `(2,-2)`、`(1,-2)`、`(-2,1)`、`(-1,2)` 四格。→ **补上这 4 格恰好是半径 2 的完整六边形 19 格**，无重复、连通性可由
   "每个第二环格都与第一环相邻"解析证明。→ 影响：这是最小且对称的扩格方案，不需要另挑奇形。
2. `simos-map/.../hex/HexDirection.java:13-20`——六邻域是固定轴向 delta：E/SE/SW/W/NW/NE；`HexCoord.neighbors()`
   (`HexCoord.java:72-74`) 是唯一方向表。→ 验证脚本用**运行时同一份** `neighbors()` 做 BFS，不在脚本里复制坐标代数。
3. `simos-app/.../world/PopulationSeeder.java:109-195`——家户/批次由 `SettlementPlan`（逐格农村 map + 城市表）生成，
   逐格现算人口走 `SocialData.populationAt`（`SocialData.java:815`）。→ **没有 15/4000 硬编码**；加 4 格只会多 4 个
   200 人农村池，总人口自动 = 19×200+700+300 = 4800。
4. `simos-app/.../world/EconomySeeder.java:3005-3060`（`planProductionRuntime`，格集自 `:3020` 的 `hexes` 起）——逐格建产业/劳动/资产/市场/经营者，
   格集来自 `PopulationSeeder.Seeding`，不是地图常量。→ 经济播种**零语义改动**即可吃 19 格。
5. `simos-map/.../GameMap.java:89-90`（`TerrainBlocks.requirePartition`）、`Region.java:28/35-40`
   （`Set.copyOf` + 边界重算一致）、`TerrainBlocks.java:40-42`（自然序起始 + `TreeMap`）——地形块并集==hexes 键集、
   Region 边界与 hexes 一致、分块确定，全部构造期强制。→ 新地形映射只要覆盖全部 19 格且城市格为 plains，装配必过；
   确定性由构造期护栏守。
6. `WorldRegistry.java:47/131-133`——`small-world` worldId 登记 `SmallWorld::state`，本次不改（§6 禁止）。
   `WorldRegistry.java:30/44/132` 与 `run-small-world.sh:3/92` 的人类可读文案仍写 15 hex / 4,000 人——**在所有权之外，
   保留为已知陈旧文案**（设计书 §4.7/§6 明确留给后续小批）。
7. `ShellMain.java:241-247`（`seedGenesisIfEmpty` 空库才继续）与 `:258-260`（`shouldSeedGenesis = branches.isEmpty()`）、
   `CoreSimos.java:284-288`（`bootstrapGenesis` 第二道闸：库非空具名抛"库非空…绝不覆盖已有世界"）。→ **不覆盖既有档**，
   无需改任何代码。
8. 实测 `grep -rn "SmallWorld" simos-app/src/test = 0`、`grep -rn "small-world" simos-*/src/test = 0`。→ 扩格不会打断
   既有测试编译/断言（设计书 §3 的实测口径在本次复核仍成立）。
9. `SmallWorld.java:138-139`——`TOTAL_POPULATION` 是公式常量（不是字面量）。`HEX_COUNT` 改 19 后人口常量自动 4800；
   `settlementPlan()`（`:373-404`）首都的腹地格数/农业剩余本来就引用 `HEX_COUNT`/`RURAL_POPULATION_PER_HEX`（`:386-387`），自动随动。

## 2. 实现架构（19 格清单、常量/地形调整、为什么）

**19 格声明序（前 15 条 = P1.4 原清单，相对顺序不动；后 4 条 = 第二环缺口，按第二环遍历序补齐）：**

```
  (0,0)
  (1,0) (1,-1) (0,-1) (-1,0) (-1,1) (0,1)          ← 完整第一环 6
  (2,0) (2,-1) (0,-2) (-2,0) (-2,2) (0,2) (1,1) (-1,-1)
  (2,-2) (1,-2) (-2,1) (-1,2)                       ← 新增 4（第二环缺口）
```

前 15 条逐字保留（`git diff` 只在原末行补一个逗号、追加 4 行 `new HexCoord(...)`），保证最小 diff；后 4 条顺序取第二环遍历中缺口的出现序。
19 = 1 + 6 + 12，每格到中心距离 ≤2；新 4 格分别与第一环的 `(1,-1)`、`(0,-1)`、`(-1,0)`、`(-1,1)` 相邻 ⇒ 整图连通。

**常量调整（其余不动）：**

| 常量 | 旧 | 新 | 说明 |
|---|---|---|---|
| `HEX_COUNT` | 15 | **19** | 格数；注释由"13~17 目标区间"改为"半径 2 完整六边形" |
| `RURAL_POPULATION_PER_HEX` | 200 | 200（不变） | 每格农村人口 |
| `CAPITAL_URBAN_POPULATION` | 700 | 700（不变） | 首都城镇人口 |
| `TOWN_URBAN_POPULATION` | 300 | 300（不变） | 镇城镇人口 |
| `TOTAL_POPULATION` | 公式 | 公式（3,800+1,000） | 自动 = 19×200+700+300 = **4,800**；表达式未动 |
| `CAPITAL_AT` / `TOWN_AT` | (0,0)/(0,2) | 不变 | 仍在格集内；首都仍人口最多（900）⇒ 政府家户落点不变 |
| `LOW_HILLS_HEXES` | 4 格 | **不变**（4 格） | 新增 4 格取 plains ⇒ 地形 plains 15 / low_hills 4；城市两格仍 plains |

**为什么这样选：**

- 新增格放**末尾**：不改前 15 格的声明序（= `GameMap.hexes()` 插入序 = 人口序列落盘序），把形状变化压到"纯追加"；
  确定性判据（同代码两建逐值一致）不受任何顺序整理影响，但审计 diff 最小。
- 新增格取 **plains**：`LOW_HILLS_HEXES` 的 4 格是 P1.4 已播种读到的低丘样本；保持它逐格不变，地形层只多 4 个平原格，
  使"世界形状/人口"之外的经济输入（低丘系数）不引入额外口径跳变（§4.6 经济语义零改的保守落法）。
- 不把 4 格撒成分叉/链形：悬挂格会让"六邻连通"变得依赖具体挑选，形状不对称；完整第二环的连通性论证最短。

## 3. 关键判断（为什么不用另一条路线）

- **不用"保留 15 格 + 外挂 4 格链/星"**：仍能连通，但会产生度数 1 的悬挂格、无对称性，且对经济逐格市场/道路没有收益；
  完整第二环是 19 格的规范形状（1+6+12），判据可解析证明。
- **不重排现有 `HEXES`**：重排会整片改 `GameMap` 插入序与人口序列序；虽无判据禁止，但最小 diff 更易审计，
  声明序已在类注中写明**就是**插入序（顺带修正了旧注释"格按 (q,r)"与代码不符之处）。
- **不调镇的两个审计量**（`PlannedCity.catchmentHexes=6`、`localSurplus=200.0`，`SmallWorld.java:397-398`）：它们是该城的
  既有审计字面量，不是 `HEX_COUNT` 派生量；无判据要求，改了才是无据偏离。首都的对应量引用常量，自动 19/3800。
- **不动验证器判据所依赖的生产代码**：`Region`/`TerrainBlocks` 的构造期护栏不绕过、不削弱（只补数据让它们自然通过）。

## 4. 偏离约束设计书之处及原因

- **无功能性偏离**：19 hex / 1 Region / 2 cities / 4800 / 六邻连通 / id 与地形口径 / 经济命令序全部满足 §4、§5。
  "选哪 4 格、常量怎么排"正是 §5 交账要求实现方记录的开放项，本账本 §2 已记录（完整第二环 + 新 4 格 plains）。
- 类注里旧的 "13~17 的目标区间" 已删（19 不在其中；旧区间是 P1.4 口径，设计书要求 19 即以新设计为准）。
- **已知陈旧文案（未改，所有权外）**：`WorldRegistry.java:30/44/132`、`run-small-world.sh:3/92` 仍写 15 hex / 4,000 人；
  `EconomySeeder.java:489` 注释举例 "small-world 42,358 亩"（19 hex 下该亩数必然变化，但它是历史压力验证说明、不是公式）。
  这些按 §4.7/§6 留给后续小批，本次不碰。

## 5. 真实命令与结果

编译门禁（按任务书原样执行）：

```
$ tools/mvn-lock.sh -q spotless:apply
rc=0

$ tools/mvn-lock.sh -q -pl simos-app -am -DskipTests compile
rc=0
```

一次性结构验证（脚本与 classpath 放 `/tmp/sw19/`，不入库；先由上一步 compile 产出各模块 `target/classes`，再用
`dependency:build-classpath` 取外部依赖）：

```
$ javac -nowarn -cp <15×simos-*/target/classes + /tmp/sw19/cp.txt> -d /tmp/sw19/out /tmp/sw19/Sw19Check.java
JAVAC_RC=0

$ java -cp /tmp/sw19/out:<同上 cp> Sw19Check
RUN_RC=0   PASS_COUNT=38   FAIL_COUNT=0   RESULT: ALL CHECKS PASSED
```

关键读数（验证器端到端调用 `SmallWorld.state("small-world")` **两次**，经真 handler + 真 codec 读出，不是只反射常量）：

```
PASS  HEX_COUNT == 19 (actual 19)
PASS  declared HEXES size == 19 (actual 19)
PASS  declared HEXES has no duplicate coordinate (distinct=19)
PASS  GameMap hex count == 19 (actual 19)
PASS  HEXES declaration order == GameMap insertion order
PASS  capital 0_0 in HEXES / town 0_2 in HEXES
PASS  max hex distance from center == 2 (actual 2)
PASS  6-neighbor BFS from (0,0) reaches all 19 (actual 19)
PASS  isolated cells == 0 (actual 0)
PASS  region count == 1 (actual 1) / region covers 19 hexes / region hex set == map hex set
PASS  social cities == 2 / city at capital anchor / city at town anchor
PASS  terrain counts == plains:15 + low_hills:4
PASS  capital terrain == plains / town terrain == plains
PASS  social populationAt sum == 4800 (actual 4800)
PASS  matches formula 19*200+700+300 == 4800 / matches TOTAL_POPULATION == 4800
PASS  capital pop == 900 / town pop == 500 / other 17 hexes pop == 200 each
PASS  state('small-world') rebuilt twice is field-by-field equal
PASS  hex insertion order identical across rebuilds
PASS  MAP_ID unchanged == 'small-world' / same world id still registered
PASS  all eight genesis namespaces present
PASS  SELF-TEST duplicate detector fires on injected duplicate
PASS  SELF-TEST BFS reports unreachable when 1 cell removed
PASS  SELF-TEST BFS does not jump across a gap
PASS  SELF-TEST isolation detector fires on 2 distant cells
```

负向验证：

- **无重复格**：`HEXES` distinct=19、`GameMap` 键 19；验证器自检证明"注入重复 ⇒ 检测器必红"。
- **无孤立格**：孤立计数 0、BFS 覆盖 19；自检证明"删 1 格 ⇒ BFS 报不可达"、"造 gap ⇒ 两格都判孤立"。
- **世界 id 未变**：`MAP_ID == "small-world"`、`WorldRegistry.require('small-world')` 可解析；diff 未触 WorldRegistry。
- **bootstrap 只对空库生效**（只读代码确认，未起服务）：`ShellMain.java:243`（非空分支直接 return false）、
  `:258-260`（`branches.isEmpty()`）、`CoreSimos.java:288`（第二道闸具名抛"库非空…绝不覆盖已有世界"）。
- **改动面**：`git status --porcelain` 只有 ` M SmallWorld.java`（外加派单时就存在的 untracked 约束设计书）；
  经济/社会/军队模块、`WorldRegistry`、`pom.xml`、`log4j2.xml`、`src/test/**`、`docs/**`、`AGENTS.md`、`run-small-world.sh`
  全部未动；命令序/handler/codec 零改动 ⇒ §4.6 经济语义零改。

## 6. 未完成 / 未验证项

1. **未起服务、未真正 bootstrap SQLite store、未打 jar**：按任务书（不起服务、不 `package`）执行；"空库 bootstrap 后
   `/api/map/overview` 报 19 hex / 1 Region / 2 cities"这条**端到端 HTTP 读数未做**，留给控制方 Phase 0 实跑。
2. **未跑 `test` / `verify` / `package`**（任务书禁止）。后续测试 Agent 应按设计书 §5 补：19/1/2/4800、六邻连通、
   两次重建逐值一致、worldId 不变、空库门禁（负向）。
3. **未验证 19 hex 下 360 tick 经济的数值表现**（本责任区不含；控制方后续 12×30 段推进）。
4. 验证脚本/classpath 仅在 `/tmp/sw19/`（`Sw19Check.java`、`cp.txt`、`run3.log`），**未入库**；复现需先跑上面的 compile，
   再 `dependency:build-classpath` 生成外部依赖清单。
5. **未做生产代码变异**（未故意改坏再验红）：任务书禁止"为过编译删功能/放宽校验"；本次只对**验证器自身**注入缺陷
   做了自检（重复/删格/gap 三例），生产侧读数来自端到端组装态 + 构造期护栏。
6. §4 列出的陈旧文案（WorldRegistry 描述、run 脚本文案、EconomySeeder 历史注释）本次按所有权保留，未做更新。
7. **本账本默认被 gitignore**：`.superpowers/sdd/.gitignore` 的 `*` 覆盖本路径（该目录历史文件是 `git add -f` 进去的）
   ⇒ 控制方提交时必须 `git add -f .superpowers/sdd/2026-10-23-smallworld-19hex/impl-ledger.md`，否则账本不会进提交。
