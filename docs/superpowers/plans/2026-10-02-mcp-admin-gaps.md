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
