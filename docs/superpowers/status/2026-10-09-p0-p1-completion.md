# 2026-10-09 P0/P1 完成汇报（子 Agent 新架构实验第一批）

> 目标：按 `AGENTS.md` §一.10 的实验原则，自主完成 P0（唯一经济路线 + 经济正确性 + S3b 家户投影）
> 与 P1（`--world` / 后端行政命令 / Log 骨架 / 小世界真实路径），然后在 P1 完成处停下。
> 状态：**生产代码 compile 门禁全部通过；测试、前端、真实起服一律后置，尚未验收。**
> 回退点：tag `agent-experiment-baseline-20261009`（实验前 `6562c215`）。

## 1. 本批提交

| commit | 内容 |
|---|---|
| `6562c215` | AGENTS §一.10 + 实验基线（本批起点，已打 tag） |
| `2445b7c2` | P0.1 删除 class-first 主路径，`production-runtime`（政府内置）成为唯一路线 |
| `d7604ea5` | P0.2 经济正确性三件套（质押跟随 / 承运实收 / 0 价免费交易与运费解耦） |
| `75de1964` | P0.3 家户投影、`Unit.manpower` 退役、军官/领导层家户配置 |
| `323c1ee7` | P1.1 `--world` + 配置文件 + 世界注册表 |
| `323b56f7` | P1.2 后端行政/迁移命令（`MoveAccount` / `MoveCity` / 区划语义 / `DeleteNation` 等） |
| `62703db3` | P1.3 全项目 Log 骨架（8 个模块门面 + log4j2 开关） |
| `c19da361` | P1.4 小世界真实 DB/GUI 路径 + `/api/economy/gov` |

## 2. 逐项目标完成情况

### P0.1 唯一经济路线 ✅（生产主路径）

- `FoundationProfile` 收敛为唯一 `PRODUCTION_RUNTIME`；`class-first`、`production-runtime-government`
  线格式名不可解析；`WorldgenInitializeTool` 缺省改 `production-runtime`。
- `EconomyData` 删除 `classFirst` 组件（30 组件）；ChangeSet / Codec / StateBuilder 同步；
  `simos-economy/classfirst/**` 整包删除；app 侧参与者/写回/投影/日税/GM 债务工具删除。
- production-runtime 播种固定内置 official GOV 家户，国库指向它；`seignioragePerCycle=2000`、
  `debtIssuePerCycle=5000` 成为新世界默认政策。
- 已知缺口：`GovDaily` 行政税/俸禄、`JurisdictionDailyTax` 未迁到新参与者；
  `economy.UnitBorrow` / `UnitRepay` 工具删除；多 region 政府“先到者胜”；webui 旧 `classFirst` 分支未适配；
  旧测试仍引用已删类。

### P0.2 经济正确性三件套 ✅（编译级）

- 质押：拆分/合并/整对象转移时 ACTIVE 质押按比例落到新份额/新家户；终态校验 ΣACTIVE ≤ quantity；
  新增确定性 `pledge-follow-N`；非 ACTIVE 保留零行引用。
- 承运：查清 `SevenHexFullChain merchantFee=0` 根因是自适应价格失稳导致跨区成交归零；
  需求改按当前价可付量；区内跨格纳入商号运输；运力不足收缩成交并落 `LOGISTICS_CAPACITY`；
  `CARRIER_FEE` 仍进 merchant principal 账户并写回 `lastFeeEarnedMilli`。
- 0 价免费：`Market` 允许明确 0 价并用 `hasPrice/isFree` 区分“未定价”；价格地板 1→0；
  运费与货价彻底解耦（商品种类基数 × 路线费率 × 承运成本）；0 价货款腿 0、运费照收。
- 已知缺口：新运费常数为粗估、未标定；自承运无独立运费读数；`HexTradeCost` 货币侧仍 0；
  部分估值路径未打 `FREE` 具名标签；商号运力只在关账日重置；商号世界区内撮合退化单线程。
- 正式测试未跑：`SevenHexFullChain3650Test`、`SevenHexNatural3650Test`、
  `RealTwelveHexProductionRuntime3650Test`、`RealTwelveOneTickTraceTest` 均未执行。

### P0.3 家户投影 / manpower 退役 ✅（编译级）

- `Unit` 删除第 5 组件 `manpower`（18→17 组件）；人员来源改为 `Unit.households` + Social 家户现算；
  旧 `manpower` 非空载荷具名拒；`equipment` 照常保留。
- `raiseUnit` 改为 create household → transfer members → create unit(households)；
  `RecordCasualties` 人员上界改读 `unitPopulation`（simos-sd 为编译联动改动，超出原定大致范围）。
- 军官团/领导层：`ArmyFormation.householdDuties` / `GovFormation.householdPosts` 以 `HouseholdId` 为键落配置；
  键必须在 `Unit.households` 内；规则不落 `Info`。
- 推进前 Unit↔Social 一致性校核与必要单向同步；`ClassRow.population` 按 Social 家户单向数值重投影。
- 已知缺口：`ClassRow` 身份仍是经济合成的“格×居住×阶层”id（1 个 Social 家户 ↔ 4 条阶层行）；
  `LaborAllocation.household` 仍是经济行 id；UNIT 家户经济接线未做，可能在 `MembershipWriteback` fail-closed；
  `simos-gov` 尚未读 staff 家户投影；Worldgen 军队仍无家户来源；军官配置尚未接战斗/动员/供给；
  大量工具由“写 headcount”改为具名拒，尚未迁移。

### P1.1 `--world` + 配置文件 + 世界注册表 ✅（编译级）

- `ShellConfig` 增加 `worldId`；新增 `ShellConfigFile` 读 `config/shell.json`；
  优先级 = CLI > 配置文件 > 内置缺省；新增 `--world=<id>`（兼容空格）与 `--config`。
- `WorldRegistry` 登记 `v17levant -> RichWorld`、`corridor -> CorridorWorld`；后来 P1.4 追加 `small-world`。
- 未知 worldId / 坏配置 fail-closed；非空库不覆盖。
- 已知缺口：未真实起 Shell、未跑空库 bootstrap 全链；`CorridorWorld` 无 army 片；
  未知 worldId 对非空库也 fail-fast。

### P1.2 后端行政/迁移命令 ✅（编译级；MCP 工具后置）

- actor：`actor.TransferAccounts` / `actor.MoveAccount`（原子、冻结随行、溢出拒）。
- social：`social.MoveCity` / `DeleteCity` / `MovePopulationLots`；人口读口 `groups.lots[]` 暴露批次 id。
- map：`map.MergeRegions` / `SplitRegion` / `ReassignHexes`；只改 map 自身数据。
- sd：`sd.DeleteNation`（引用未清具名拒，显式开关才清外交两表）。
- unit：`unit.SetVisionRadius`；人员迁移继续走家户/成员批次。
- app：`MoveCapitalPlan` 后端组合（一条 revision；不包装 MCP/GUI）。
- 已知缺口：map 语义命令的下游 jurisdiction/城市/税率/编制重编编排未做；
  `MoveCapitalPlan` 不搬人口；`DeleteNation` 的历史 Directive 清理路径缺；
  `DeleteCity(deletePopulation=true)` 不检查 economy 引用；`social.UpdateCity` 仍静默忽略 `at`。

### P1.3 全项目 Log 骨架 ✅（编译级；输出未验证）

- 新增 `MapLog` / `SdLog` / `ActorLog` / `GovLog` / `ArmyLog` / `CalendarLog` / `CoreLog` / `AppLog`；
  76 个新事件名；`log4j2.xml` 增加 8 组 `simos.<module>.logLevel` / `traceLevel`。
- core/app 既有日志行未改；新增行一律 `event=key=value`。
- 已知缺口：未真实起服采集日志；sd 仍有约 11 个 handler 未覆盖；gov 的日志点为当前无生产调用者；
  `WorldgenInitializeTool`/`RegionSeedTool` 等组合工具未逐工具记行；全仓既有 logger 名未统一。

### P1.4 小世界真实 DB/GUI 路径 ✅（编译级；未真实起服）

- 新增 `SmallWorld`：15 hex、1 Region、首都+镇、4000 人；经真 handler/codec 走 production-runtime 创世。
- `WorldRegistry` 登记 `small-world`；`config/shell.json` 默认切到 `small-world`；
  新增 `run-small-world.sh`（store `run/small-world-db`、GUI 5811、不覆盖 jar/非空库）。
- 新增只读 `GET /api/economy/gov`：政府家户/国库账户/铸币发债政策/发行审计。
- 已知缺口：未真实起 Shell/GUI、未跑 `run-small-world.sh` 成功路径、未 HTTP 验证 `/api/economy/gov`；
  脚本要求预先 `package`，而当前旧测试无法 test-compile，需要测试迁移后才能走真实路径；
  铸币生产式算法仍未实现；`config/shell.json` 默认切换是否保留待用户裁定。

## 3. 测试/前端/真实运行的现状（必须如实看）

- 本批所有推进门禁都只跑了 `./mvnw -q -pl simos-app -am -DskipTests compile`，每步 rc=0；
  控制方在每个子 Agent 之后都亲手重跑过该命令。
- **测试未跑、`test-compile` 未跑、`verify` 未跑、`package` 未跑、前端门禁未跑**。
- 旧测试仍大量引用已删除的 `ClassFirst*`、`Unit.manpower` 与旧 API；现在整仓 test 编译必然失败。
  按用户口径“老架构老测试、新架构新测试，直接删被改架构的旧测试”，下一步应先做测试迁移 Agent，
  再谈 `clean verify` 与真实起服。
- webui 仍可能读已删除的 `classFirst` 键；P0.1/P0.2/P0.3 的读口形状改动未做前端适配。

## 4. 实验评估（第一批）

- **有效处**：办事 Agent 拿到目标/边界/大致范围后可以自主完成大块生产代码；class-first 删除、经济三件套、
  家户投影、后端命令、日志骨架、小世界都在单 Agent 上下文里完成；compile 门禁稳定通过；
  控制方不必写生产代码。
- **问题处**：
  1. compile 门禁放行了“main 能编译但测试全面失效”的状态；真实 `clean verify` 必须等测试迁移。
  2. “大致文件范围”执行中会被合理突破（如 P0.3 改 `simos-sd`），应接受但要具名；
  3. 子 Agent 多次跑全仓 `spotless:apply`，虽已还原，说明“只编译门禁 + 全仓工具”仍有事故风险；
  4. 删旧架构代码时，`GovDaily`/税/俸禄/单位借还这些“挂在旧参与者上的真实功能”会一起消失；
     需要后续批次恢复，不能把“删干净”误当“功能完成”。

## 5. 下一步（等用户裁定）

1. 测试 Agent：删/迁移旧架构测试，补新架构关键判据；恢复 `test-compile` 与 `clean verify`。
2. 前端 Agent：删除 classFirst 分支，适配 `visionRadius`、`groups.lots[]`、`/api/economy/gov` 等新读口。
3. 恢复/迁移 class-first 删掉时一起消失的功能：GovDaily 行政税/俸禄、单位借还、多 region 政府语义。
4. 决定 `config/shell.json` 默认是否保留 `small-world`。
5. 真实起服：package → `run-small-world.sh` → curl 冒烟。
6. 再进入 P2（文化、铸币生产式算法、地图重建、GUI 面板等）。
