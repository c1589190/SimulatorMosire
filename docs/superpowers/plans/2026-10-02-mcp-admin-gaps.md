# MCP 迁都 / 行政区域修改 / 合并 —— 缺口记录（2026-10-02）

> 背景：大蜀国第一次初始化时，首都区落到了 `(55,-65)`（原占领国首府旧址）。
> 想用现有 MCP 工具把首都区迁到 `(44,-68)` 周围，实测发现以下问题。当前采用最小回退：
> **不写迁移命令，直接在干净世界副本上以正确的 `capitalHex` 重新初始化大蜀国**。
> 本文件记录需要后续补的 MCP/命令面缺口。

## 一、当前无法用 MCP 干净完成的事

### 1. 迁都（首都区 / 中央 GOV / 国库）
- `map.UpdateRegion` 只能整体替换某个 Region 的 `hexes`/`meta`；它**不会**处理：
  - 中央 GOV 的新位置（另需 `unit.PlaceAt`）；
  - 中央国库账（`GoodsAccount` 的 key 是 `(owner, location)`，位置改了账不会跟着走；
    需显式 `actor.RemitGovTreasury` 从旧格搬到新格）；
  - 原首都区 hex 的归属（要从原省划出/并入他省，需另发多条 `map.UpdateRegion`）；
  - 城市 `region` 归属（另需 `social.UpdateCity`，城市 `at` 改不了）。
- 没有任何一条“迁都”组合命令能把这些原子地做完。

### 2. 城市点位不可变
- `social.UpdateCity` 只支持 `name` / `props` / `region`；`at` 不在载荷里。
- 没有 `social.MoveCity` / `social.DeleteCity`。
- 要移动首都城市实体，只能：
  - 在新址 `social.CreateCity` 新建（城镇人口为派生量，不会跟着搬）；
  - 旧城改名改归属保留在原地。

### 3. 人口批次不可迁移（MCP 读/写双缺）
- `PopulationGroup.residence` 本身支持换格（id 不变），但：
  - 现有读口（`simos.social.population` / facets）只给聚合数，**不暴露批次 id**；
  - 没有 `social.MovePopulationLots` 之类的命令/组合工具；
  - `social.SeedGroups` 只能按已知 id 重写，靠现有 MCP 读不出旧都各批次的 id。
- 因此“把旧都 18 万城市人口搬到新都”当前做不到；要么新增命令/读口，要么破坏性 clearData + 重播。
- 破坏性重播会丢掉旧都的人口/经济历史，不建议对已运行世界使用。

### 4. 省界修改 / 合并 / 拆分
- 没有 `map.MergeRegions` / `map.SplitRegion` / `map.ReassignHexes` 这类语义命令。
- 用 `map.UpdateRegion` 换 hex 后，下面这些都要人工逐条另发命令，否则会漂移：
  - 省 GOV 的 `jurisdiction` 是否还覆盖新 hex；
  - hex 上的城市 `region` 是否仍指向正确省；
  - 税率键、`GovDemand` 配编是否按新管辖重算；
  - 中央首都区/省属关系的 `unit.SetGovSuperior` 是否需要改。
- 现在没有一条把“改省界 + 重算管辖/城市/编制”做成原子的组合工具。

### 5. 国家实体删除 / 重建
- 没有 `sd.DeleteNation`；`sd.CreateNation` 对国家已存在是**拒绝**。
- 一旦某个国家初始化错了，想“原地重来”不能只清 region：
  - `simos.region.clearData` 清社会/经济/actor 数据；
  - `simos.region.clearStructures` 清 GOV/决策人/管辖/派生结构；
  - 但 `sd` 里的 Nation 实体仍存在。
- 因此本次回退采用**干净世界副本 + 按正确参数重新初始化**，而不是原地删除重建。

### 6. canonical 地址兼容
- 决策人从读口拿到的是 `unit:<id>`、`map:<mapId>:region.<id>` 这类 canonical 地址。
- 已修：`UnitId.parse` 接受 `unit:` 前缀；`RegionId.parse` 接受 `map:<mapId>:region.` 前缀。
- 仍未统一：`actor.*` 的目标单位 id 由 payload 裸值直接参与账键（部分路径需调用方给裸值）；
  `actor.RemitGovTreasury` 已做 `unit:` 前缀归一化，但其他组合工具/命令尚无统一入口。

## 二、建议的后续补齐批次（不是本批范围）
1. `social.MoveCity` / `social.DeleteCity`（或允许 `UpdateCity.at`）。
2. `social.MovePopulationLots`（按居住格批量迁移；读口补批次 id）。
3. `map.MergeRegions` / `map.SplitRegion` / `map.ReassignHexes`（同批重算 jurisdiction/城市归属/税率/编制）。
4. `actor.MoveAccount`（按 owner 搬账，免手工 remit 逐资源金额）。
5. `sd.DeleteNation`（或 `sd.DeleteNationIfEmpty`）。
6. 高层组合工具：`simos.gov.moveCapital`（首都区 + GOV + 国库 + 城市归属 + 编制，一条 revision）。
7. 统一 canonical 地址归一化入口（所有 ID 字段都接受 canonical 前缀）。

## 三、本次最小回退
- 不新增迁移命令。
- 用 `2026-10-01-gov-sim-v2-backup-20261001-2357`（大蜀初始化前、head 76 / tick 90）的干净副本。
- 重新初始化大蜀国时，`simos.province.apply` 显式传 `capitalHex = (44,-68)`；
  新都城市用 `social.CreateCity` 建在 `(44,-68)`；旧生成都城改名并归省。
- 达到“行政区/中央 GOV/新都城市在新址”的最小效果，人口迁移缺口记录在本文件，后续补命令后再做。

## 四、大蜀国策所需的 map / 迁都补充缺口（2026-10-02 补充）

> 用户给出：铁门坎在 `(34,-55)`，应当用 MCP 命名这个 hex；西陵首都应当在 `(35,-60)`。
> 本节只记录这两项在当前 MCP/命令面上的可行路径与缺口，不在本轮执行。

### 1. 单格命名（铁门坎 `(34,-55)`）
- 没有 `map.NameHex` / `map.SetHexLabel` / `map.UpdateHex` 这类“给单个 hex 命名”的命令。
- 现有最接近的路径是 `map.CreateRegion` 建一个 **1 格 overlay Region**：
  `{regionId:"铁门坎", name:"铁门坎", hexes:[{q:34,r:-55}], meta:{color:null, tag:"Landmark", description:"…", annexedBy:null}}`。
- 注意：
  - 与已有 `西陵__CAP` 重叠不会报错，但 region 列表会多一条；hex 的 `nation` 仍由 `nation:西陵` Region 决定，不会因为 overlay 改归属。
  - 当前世界已有一座名为“铁门坎”的城市 `c-31_-60`（`(31,-60)`，Town），与用户指定的 `(34,-55)` 冲突；命名前应先用 `social.UpdateCity` 把旧城改名/改归属。
  - `map.UpdateRegion` 的 `meta` 是整体替换（`color/tag/description/annexedBy` 四键必须给全）；没有单格 label 的原子替换。

### 2. 西陵首都从 `(33,-55)` 移到 `(35,-60)`
- 现状（head 126 / tick 120）：
  - `西陵__CAP` 现为 7 格：`(32,-55),(32,-54),(33,-56),(33,-55),(33,-54),(34,-56),(34,-55)`；**不含** `(35,-60)`。
  - `西陵城`（MajorCity，城镇人口 13,756）在 `(33,-55)`；`Lindenheim`（MarketTown，城镇人口 6,853）在 `(35,-60)`，归属 `西陵__P01`；`西陵-gov-central` 在 `(33,-55)`，辖 `西陵__CAP`，国库账在 `(33,-55)`。
  - 另有生成器城市“铁门坎” `c-31_-60` 在 `(31,-60)`，归属 `西陵__P02`。
- 没有 `simos.gov.moveCapital` / `social.MoveCity` / `social.DeleteCity` / `social.MovePopulationLots`；`social.UpdateCity` 不能改 `at`。
- 可行的多命令 workaround（非原子，需要按顺序执行并逐步核验）：
  1. `map.UpdateRegion` 改 `西陵__CAP` hexes 加入 `(35,-60)`（并保留/调整旧 hex）；同时改 `西陵__P01` hexes 去掉 `(35,-60)`，避免区域汇总重复。
  2. `social.UpdateCity`：`c-35_-60` 改名 `西陵城`、region `西陵__CAP`；`c-33_-55` 改名 `旧都·西陵城`（region 视需要）。
  3. `unit.PlaceAt`：`西陵-gov-central` → `(35,-60)`。
  4. `actor.RemitGovTreasury`：把中央 GOV 在 `(33,-55)` 的账搬到 `(35,-60)`（grain/cloth/money 逐项；金额需先读实时余额）。
  5. `unit.SetJurisdiction`：保持 `西陵__CAP`（region id 不变、hexes 变），或按新省界重设。
  6. `sd.PutInfo`：写迁都审计。
- 代价 / 不可做：
  - 新首都 `(35,-60)` 只有现有的 **6,853** 城镇人口；旧都的 **13,756** 城镇人口搬不过去（缺批次读口与 `MovePopulationLots`）。
  - 没有原子工具；中途失败会留下 hex/城市/GOV/国库不同步，需要人工补。
  - 若要求“旧都人口随迁”，当前命令面做不到，只能先记录缺口。

### 3. Seed 生成模式无法指定首都/城市 hex
- `simos.region.seed` 的 `capital` 参数只有 `{name, targetPopulation?}`：**没有 `hex` / `at`**。首都锚点 `CapitalAnchor` 不带坐标，生成器自行选城址；本工具不发明 hex。
- 实测后果：
  - 大蜀北谷占领区 seed 出来的「北谷城」在 `(38,-47)`，而档案北谷都城是 `(36,-53)`；
  - 西陵 seed 出来的「西陵城」在 `(33,-55)`，而用户指定首都 `(35,-60)`；
  - 西陵还有一座生成城市「铁门坎」在 `(31,-60)`，与档案铁门坎 `(34,-55)` 同名冲突。
- `simos.region.seed` 只有生成模式，没有 explicit 逐格/逐城 entries 参数。
- 缺：
  - `capitalHex` / `anchorHex` 参数（或 explicit city entries）；
  - `social.MoveCity` / `social.DeleteCity` / 批量改名；
  - 城市人口可迁移（`social.MovePopulationLots`）或建城时指定人口（`social.CreateCity` 目前明确拒收 `population`，人口只能由 `social.SeedGroups` 派生）。
- 因此“种子生成后把首都/城市挪到档案坐标”目前只能多命令 workaround，且人口搬不动。
