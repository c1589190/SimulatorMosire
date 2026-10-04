# 2026-10-08 真实小世界 + GOV 铸币生产方式详细计划

> 来源：用户 2026-10-07/08 裁定：
> ① 不要再用 test 代码当主验证路径 —— 在当前目录下建一个**真实有 DB 的世界**，13~17 hex 小地图，编译后**真跑 Shell/WebUI**；
> ② 铸币是**工匠类劳动**，应做一个和工匠相似的默认生产方式；产出是对应 GOV 的货币；生产组织者是**政府家户**；
> ③ 政府粮食/钱财/后续外汇库存都放政府家户；给军队等转移由 GM 从这个家户扣；
> ④ 铸币投入 = **只劳动 + 工具**（方案 B）；纸币/信用货币留到中世纪晚期；
> ⑤ 政府家户由决策人控制，有权决定投入多少生产规模；
> ⑥ 非法仿制以后再说，先记一笔未做；
> ⑦ **全相关日志**必须补。

## 1. 目标与非目标

**目标**
1. 一条**非测试**的真实运行路径：`./run-small-world.sh` → 建 DB → 初始化 13~17 hex 世界 → 起 Shell → 浏览器打开 WebUI。
2. 政府家户是真实经济主体：粮、钱、工具、后续外汇都在它的 actor 账户里。
3. 铸币成为默认生产方式 `mint`（工匠类），组织者 = 政府家户，投入 = 劳动 + 工具，产出 = 该 GOV 的货币。
4. GM/决策人可调：政府家户需求、产能、铸币规模、政策；并可把粮/钱/工具从政府家户转给军队等主体。
5. 所有相关动作有 INFO/DEBUG/TRACE 日志，可按既有 `simos.economy.logLevel` 统一开关。

**非目标**
- 不做非法仿制/伪币；只在计划里留 TODO。
- 不做纸币、信用货币、银行券、汇率；后期中世纪晚期再开。
- 不做完整税收/预算制度；先把“可调 + 可转 + 可生产”跑通。
- 不删除现有 test 诊断；但**主验收不再依赖 test**。

## 2. Phase W1：真实小世界与 DB/GUI 启动

### 2.1 世界形状（建议，待裁定具体 hex 数）
- 大小：建议 **16 hex**（15 陆 + 1 海；规则 4×4 或 4×4 去掉一角）。
- 区域：1 个 Region；1 座首都城 + 1 座镇；其余为农村格。
- 人口：3,000~5,000；首都放政府家户与铸币工场。
- mapId：`small-world`；DB 目录：`./run/small-world-db`（加入 `.gitignore`）。
- 政府家户位置：首都格 `CAPITAL`。

### 2.2 主代码新增（不是 test）
- `simos-app/src/main/java/io/mosire/simos/app/world/SmallWorld.java`：
  - 程序化构造 13~17 hex `GameMap`（地形、河流边、Region）；
  - 提供 `state(mapId)`，像 `RichWorld` 一样产出创世 `SimulationState`；
  - 提供 `writeConfig(dir)` 或内置 worldgen 配置（可复用 `WorldgenInitializeTool` 的配置 schema）。
- `ShellMain` 新增开关：`--small-world`（或 `--world small-world`）：
  - 空 DB：先 `bootstrapGenesis(SmallWorld.state(mapId))`；
  - 再调用真实 `WorldgenInitializeTool`（`economyProfile=production-runtime-government`）播种人口/城市/经济/actor；
  - 然后正常 `Shell.start` → GUI。
- `run-small-world.sh`（仓根）：
  - `./mvnw -q -pl simos-app -am -DskipTests package` 或直接 `exec:java`；
  - 传 `--store ./run/small-world-db --small-world --gui-port 5711`；
  - 打印 WebUI URL。
- `.gitignore`：忽略 `run/` DB。

### 2.3 验收（真实运行，非 test）
- 删除/不删 test 都行，但验收命令是 `./run-small-world.sh`；
- 进程起来后在 `http://127.0.0.1:5711/` 看到地图，hex 数正确；
- DB 文件真实存在且非空；重启进程世界仍在。

### 2.4 待决策
- 具体 hex 数：13 / 15 / 16 / 17？（建议 16）
- 首都、镇、河流与海洋的布局；
- 小世界人口、城市化率、初始库存参数。

## 3. Phase W2：政府家户库存与转移

### 3.1 库存归属
- 政府家户 id：小世界里给 GOV 家户**真实人口**（例如 50~150 人），使它有劳动、能经营铸币工场；不再是当前试点里的 population=0。
- 库存：
  - 粮 `grain`、工具 `tool`、货币 `silver`（后续 `foreignCurrency`）全在政府家户 actor 账户；
  - 铸币工场资产（WORKSHOP/TOOL 份额）挂在政府家户名下。
- `Government.treasury` 指向该家户 actor；`GovernmentSeigniorage` 在小世界里置 0，货币只从铸币产出。

### 3.2 转移工具
- 新增真实命令（建议）`actor.TransferAccounts`：支持任意两个 actor 账户间的商品/货币原子转移；
- 或扩展 `actor.RemitGovTreasury`：把源/目标从“UNIT 单位”泛化为 `ActorRef`（含 HOUSEHOLD/GOV/UNIT）。
- App 工具：`simos.gov.householdTransfer`（GM-only）：
  - `from = governmentHousehold`，`to = army/unit/gov…`；
  - 支持 grain/cloth/tool/money（多币种预留）；
  - preview/apply、reason、资源围栏；
  - 原子：源不足 ⇒ 全拒，不做部分转账。
- GUI：`/gov` 或 map 侧栏显示政府家户账户与最近转移。

### 3.3 验收
- GM 从政府家户转 10,000 粮到某军队国库 ⇒ 政府家户 −10,000、军队账户 +10,000，日志两条；
- 余额不足 ⇒ 具名拒绝；
- 无 FX：不同币种不折算。

### 3.4 待决策
- 泛化 `RemitGovTreasury` 还是新增 `TransferAccounts`（建议新增，保留旧命令语义）；
- 是否需要“转移配额/审批”还是 GM 无限；决策人控制政府家户时是否也给同一工具。

## 4. Phase M1：铸币生产方式

### 4.1 目录与位置
在 `DefaultProductionModes` 新增：
- `ProductionModeId MINT = "mint"`；
- `ClassStructure`：`mint-structure`；
- 位置：
  - `mint-operator`：OWNER + ORGANIZER + SURPLUS_RECEIVER（政府家户）；
  - `minter`：DIRECT_LABORER + PROVIDER + WAGE_EARNER（铸币工匠；可以是家户内部劳动）。
- `productionRuntimePositionId` 增加 GOV 家户映射：政府家户 `official`/`mint` 位置 → `mint-operator`。
- 政府家户的 `ClassStanding` 指向 `mint-operator`；后续若政府还要兼任其他角色，另开组合位置，不把“铸币”塞进其他 mode。

### 4.2 产出货币的表示（**关键决策**）
`Industry` 现有 `outputPerUnit: Map<CommodityId, Long>` 只能产出商品；货币是 `CurrencyId`。两个候选：

- **A（推荐候选）**：给 `Industry` 增加 `moneyOutputPerUnit: Map<CurrencyId, Long>`。
  - 铸币 industry：`moneyOutputPerUnit={silver: N}`，`outputPerUnit` 为空；
  - `ProductionSettlement` 在结算时对货币产出走 `MoneyIssuanceJournal(FISCAL_ISSUE)`，记到 operator 账户；
  - 每单位货币产出必须同时消耗劳动/工具，货币不是凭空出。
- **B**：新增独立 `MintRule`/`MintOperation` 状态组件，不扩展 `Industry`。
  - 优点：货币发行与商品产出彻底分离，`Industry` 语义不混；
  - 代价：新增第 32 个状态组件 + ChangeSet/Codec/往返；规则字段与 `Industry` 的 cycle/labor 有重复。

请用户裁定 A 或 B。

### 4.3 投入与规模
- 投入（方案 B 裁定）：**劳动 + 工具**：
  - `laborPerUnit` / `cycleLaborMilli`：每单位铸币产出所需劳动；
  - `cycleInputPerUnit[TOOL]`：工具磨损/消耗；
  - 不使用金属/粮食作为铸币投入，不引入纸币。
- 规模：政府家户决策人决定投入规模。
  - **推荐**：规模 = 政府家户名下的 mint `AssetShare`（WORKSHOP/TOOL）+ `ProductionUnit`；
  - GM/决策人通过工具 upsert 这些资产份额/unit 参数；
  - 若需要显式“计划产量”，再给 `ProductionUnit` 加 `targetScale` 字段（待裁定）。
- 产能上限由 `Industry.capacity`/`AssetShare` 决定；不能超过真实资产能支撑的规模（是否允许 GM 硬调超过，待裁定）。

### 4.4 结算与发行
- `mint` unit 在周期里像其他生产一样记 `progressDays`、`cycleLaborMilli`、`cycleInputUsedMilli`。
- 周期末：
  1. 检查工具库存；
  2. 扣工具 + 记入投入；
  3. 产出货币金额 = 规模 × `moneyOutputPerUnit`；
  4. 记 `MoneyIssuanceRecord(FISCAL_ISSUE)`，目标账户 = 政府家户；
  5. `ProductionLedger` 记 gross/inputs/outputAccrual（货币侧单列）；
  6. 货币总量守恒式：`Σ余额 = ΣINITIAL_ENDOWMENT + ΣFISCAL_ISSUE − ΣWITHDRAWAL`。
- 工具不足/劳动不足：铸不出，写 `MINT_SHORTFALL`，不静默少发。
- `GovernmentSeigniorage` 保留为无铸币工场时的兼容/世界编辑路径；小世界默认关闭。

### 4.5 非法仿制
- 本批不做；文档里记 TODO：
  - 未来可加伪币生产（私人 mint、隐蔽性、被查获风险、货币成色）；
  - 当前只保证 `MoneyIssuance` 的授权/审计链。

### 4.6 待决策
- A/B：货币产出挂在 `Industry.moneyOutputPerUnit` 还是独立 `MintRule`；
- 每单位铸币的劳动与工具系数、周期天数；
- 规模用“AssetShare 规模”还是新增 `targetScale` 字段；
- 是否允许 `ΣAssetShare > Industry.capacity`（GM 编辑器优先 vs 硬上限）；
- 政府家户人口/劳动规模、初始工具与国库库存。

## 5. Phase T1：GM/决策人工具

优先级建议：
1. `simos.economy.gov.policy`：改 `Government.seignioragePerCycle/debtIssuePerCycle`（小世界里铸币走生产，这两个字段转为政策只读/兼容）；
2. `simos.economy.household.demand`：任意家户 upsert/cancel 需求；
3. `simos.economy.household.capacity`：参与率 + 资产份额 + unit 规模；
4. `simos.gov.householdTransfer`：政府家户 → 军队/其他主体；
5. `simos.economy.mintScale`：政府家户铸币规模（可并入 3）。

工具共同纪律：
- `preview/apply`、`reason`、前后差异、GM-only、资源围栏；
- 只走 `Command → ChangeSet → Revision`；
- 决策人控制政府家户时，同一语义走 scoped 决策人工具/指令白名单。

## 6. Phase G1：GUI 与读口

- 新增 `GET /api/economy/gov`：
  - 政府家户账户（grain/tool/money/其他）；
  - 政府政策；
  - mint unit：规模、进度、劳动、工具消耗、上期产出；
  - `MoneyIssuance` 汇总（INITIAL/FISCAL/WITHDRAWAL）；
  - 最近转移。
- 新增 `/gov` 页面或 map 面板：库存、铸币产能、本期计划、货币总量、物价/自适应价快照。
- 写入操作先走 GM 工具/MCP；GUI 写面板后续再开（待裁定）。

## 7. 日志计划（必须全接）

新增 `EconomyLog` 分类：
- `.government`：政府家户/政策/转移；
- `.mint`：铸币生产全生命周期；
- 沿用 `.settlement/.market/.debt/.trace`。

事件建议：
- `GOV_HOUSEHOLD_SEED`、`GOV_POLICY_SET`、`GOV_ACCOUNT_SNAPSHOT`；
- `GOV_TRANSFER`（INFO 汇总）、`GOV_TRANSFER_TRACE`（TRACE 逐腿）；
- `MINT_SCALE_SET`、`MINT_PLAN`、`MINT_INPUT_DRAW`、`MINT_CYCLE_OUTPUT`、`MINT_ISSUANCE`、`MINT_SHORTFALL`、`MINT_ACTOR_ENTRY/EXIT`；
- `MINT_TRACE`：每枚/每批产出、每笔工具/劳动扣减（TRACE）。
- 所有日志用 `EconomyLog.kv` 结构化 `key=value`，不记密钥；级别约定 INFO 生命周期/汇总、DEBUG 池/决策、TRACE 逐笔。
- `log4j2.xml` 继续用 `simos.economy.logLevel` / `simos.economy.traceLevel` 统一控级；新子 logger 自动继承。

## 8. 分阶段验收（真实运行）

1. W1：`./run-small-world.sh` 起 Shell，DB 有世界，WebUI 可见 13~17 hex；
2. W2：GUI 看到政府家户库存；GM 转粮/钱给军队，两边账户逐值变化；
3. M1：GM 设铸币规模 ⇒ 下一周期政府家户收到铸币产出；工具/劳动真被消耗；货币总量增加 = FISCAL_ISSUE；
4. T1：GM/决策人可改需求、产能、规模；非法输入具名拒绝；
5. G1：GUI 读数与内部状态一致；
6. 365 tick 真实推进：货币总量、政府库存、铸币产出、物价快照可解释；
7. 全日志：INFO 能回答“这周期铸了多少、消耗多少、政府库存多少、转给谁多少”。

## 9. 需要用户裁定的问题

1. 小地图：13/15/16/17 hex？建议 16；首都/镇/河流布局。
2. 铸币产出表示：A `Industry.moneyOutputPerUnit`（推荐）还是 B 独立 `MintRule`。
3. 政府家户人口：小世界里给多少人口/劳动？还是从其他家户雇工？建议给 50~150 人的政府家户。
4. 铸币规模：用 mint `AssetShare`/unit 规模（推荐）还是新增 `ProductionUnit.targetScale`。
5. 产能硬上限：允许 GM 把家户产能调到 `Industry.capacity` 以上吗？
6. 铸币系数：每单位银需要多少劳动、多少工具磨损、多少天。
7. 转移命令：新增 `actor.TransferAccounts`（推荐）还是泛化 `RemitGovTreasury`。
8. GUI 写面：第一版只读 dashboard，还是同时提供 GM 写面板。
9. 现有 `production-runtime-government`（population=0 试点）是否保留为兼容 profile；小世界另开 `small-world-gov` profile？
10. 旧 test 诊断：保留还是删除；主验收是否写成 `run-small-world.sh` + 手工 GUI 检查 + 日志摘录。
