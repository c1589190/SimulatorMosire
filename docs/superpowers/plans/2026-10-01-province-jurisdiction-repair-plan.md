# 行政区划 / 辖区修复计划（省份长条 · 城市归省 · 税与上缴显式决策 · GM 工具）

> 日期：2026-10-01
> 起因：模拟库 `2026-10-01-gov-sim` 用 `simos.province.apply` 自动生成三国省份后，用户实测发现省界全是长条、城市仍归国、中央 GOV 财政空心等问题。
> 用户裁定（2026-10-01，覆盖此前问题清单）：
> 1. 省数/粒度：**正常，不改**；
> 2. 省名：**不用改**（保持自动占位名）；
> 3. 长条省界：**要修**（`ProvinceDivider` 轴选择错误）；
> 4. 中央 GOV 财政：**列入修复计划**；交多少税/上缴由**省份决策人显式决定**，不能自动；
> 5. 城市归省：**要改**；
> 6. 历史/地理合理性：**不自动优化**，由决策人提出、GM 改；对应 GM 工具要做全；
> 7. 标签爆炸：允许 GM 自己改名字/标签；显示层仍需按层级/缩放收敛（不阻塞 GM 改名）；
> 8. 先写修复计划，再开始修复。
> 状态：计划已定；R1 开工中；测试仍按仓规最后统一补。

---

## 0. 已核实的根因与证据

### 0.1 长条不是国土形状造成的

实测三个国家的 Region 自身长宽比：

| 国家 | Region hex | bbox | PCA 长/宽 | 长宽比 |
|---|---:|---:|---:|---:|
| 德意志第二帝国 | 430 | 27×33 | 35.5 / 20.4 | 1.74 |
| 奥斯特马克侯国 | 138 | 18×18 | 23.5 / 8.7 | 2.71 |
| 霍赫兰伯国 | 231 | 24×20 | 29.6 / 12.7 | 2.33 |

### 0.2 长条是 `ProvinceDivider.chooseCut` 的轴顺序造成的

`simos-app/.../gov/ProvinceDivider.java`：

- `chooseCut` 按 `Axis.values()` 的固定顺序 **Q → R → S → D** 逐个尝试；
- 对每个轴把点按投影排序，从平衡点向两边找第一条“左右都连通”的切；
- 一旦 Q 轴能切就直接切 Q，不再看 R/S/D 哪个方向更长；
- 递归后只要 Q 还能切就继续切 Q ⇒ 得到 q 宽 2~3、r 长 17~25 的竖条；
- `bfsFallback` 只在主轴二分切不动时才触发，这三个国家没有触发。

实测 27 个省（不含 3 个首都区）的 PCA 长宽比：中位数 **3.76**，德意志 P09 达 **12.5**（bbox 2×25）。

### 0.3 已有功能（不要重复实现）

- `unit.SetTaxRate` 命令 + `UnitSetTaxRateTool`：GOV 设辖区长期税率；税率 0 跳过；
- `JurisdictionDailyTax`：每 tick 按 `税基 × ratePerMille × efficiency` 自动征收，落**该 GOV 自己的国库账**（`ActorRef(UNIT, unitId)` 在有效位置）；
- `map.UpdateRegion` / `MapUpdateRegionTool`：改 Region 名称/meta/hex 集；
- `unit.SetJurisdiction` / `unit.SetTaxRate` / `unit.RenameUnit` 等窄工具；
- `social.UpdateCity` 命令：改城市名/props（**不能改 region**）；
- `actor.AdjustAccounts`：GM 裸账原语（纯正增量可新建账；整条原子；`GmOnlyCommand`）。

---

## 1. 目标与验收

### 1.1 R1 长条修复（必须）

- `chooseCut` 改为：对 Q/R/S/D 四轴分别计算投影跨度，**按跨度降序**（并列按枚举序 Q→R→S→D）尝试；
- 每个轴内仍按“最接近一半、且左右连通、非碎片”的既有规则选切点；
- 保持确定性、`min/max` 带、碎片 warning、BFS 回退不变；
- 原型实测（真实三国数据，仅改轴顺序）：
  - 德意志：16 省（旧 15），长宽比 [1.06–2.69]，中位 1.35；
  - 奥斯特马克：4 省，[1.10–1.74]，中位 1.49；
  - 霍赫兰：8 省，[1.03–1.60]，中位 1.37；
  - 对比旧：中位 3.76、max 12.5。
- 验收：三国 all-provinces 中位长宽比 ≤ 2.0、max ≤ 3.5；无 2~3 格宽、15+ 格长的条状省。

### 1.2 R2 城市归省（必须）

- `SocialCity` 增加 `withRegion(Optional<RegionId>)`；
- `social.UpdateCity` 载荷扩展可选 `region`：
  - 键缺席 = 不动；
  - 字符串 = 设为该 Region；
  - `null` = 清空（无归属）。
- 新增 GM 窄工具 `social.UpdateCity`（固定命令类型）或等价 `simos.city.update`：可改 name/props/region；
- `simos.province.apply` 在落盘时**追加城市归省**：把 `at` 落在新省/首都区的城市 `region` 改为对应新 Region（缺省开，可用参数关）；
- 新增只读/组合工具 `simos.province.assignCities`：对已有世界按“城市 at 落在哪个省”批量改 `SocialCity.region`（preview/apply，一批一条 revision）；用于当前 `gov-sim` 库；
- 以下读口/门要同步：
  - `RegionSeedPlan` clean gate 对城市改为“`at` 落在目标 Region hex 集”也命中（防归省后漏检）；
  - `ApiViews.regionSummaries` 增加 `containedCityCount/containedCityPopulation`（按 at 落在 Region hex 集）；国家卡片/区域详情改用 contained 口径，避免 city.region 改为省后国家计数掉 0；
  - 左栏城市详情“所属区域/父国”按 city.region（省）+ 省的 `superiorGov` 链显示父国（具体展示方案在实现时定）。
- 验收：201 个城市全部 `city.region` ∈ {所在省/首都区}；国家汇总城市数与归省前一致；`region.seed` 清空门在归省后仍能命中城市。

### 1.3 R3 税与上缴：省份决策人显式决定（必须）

- **税收本身**：沿用 `unit.SetTaxRate`；确认它：
  - 在 GM 工具面可达；
  - 非 `GmOnlyCommand`，可嵌入决策令（`DirectiveWhitelist` 允许）；
  - 决策人 catalog 与 scope 允许对自己 GOV 的 jurisdiction 区域设税率。
- **上缴/转移**：新增显式命令，建议 `actor.RemitGovTreasury`（名字实现时定）：
  - 载荷：`fromUnitId, fromQ, fromR, toUnitId, toQ, toR, grain?, cloth?, money?, reason`；
  - 从 `(UNIT:<fromUnitId>, fromQ/fromR)` 的国库账扣，加到 `(UNIT:<toUnitId>, toQ/toR)`；
  - 三个资源维度至少一个 >0；所有金额 ≥0；源账必须存在且可用量足（走 `AvailableStock`，不侵占冻结）；
  - 整条原子；目标账缺 ⇒ 纯正增量新建；不凭空造资源；
  - **非 GmOnly**，可被省份决策人嵌进 `sd.IssueDirective`；GM 也有窄工具 `simos.gov.remit`（preview/apply、expectedRevision、reason）。
- 语义边界：
  - 省份决策人先决定税率（`unit.SetTaxRate`），税收自动进**省国库**；
  - 省是否上缴、上缴多少、上缴什么，必须再发一条显式 `actor.RemitGovTreasury` 决策；不发 ⇒ 中央一分钱拿不到；
  - 中央 GOV 自己的税基 = 首都区税率 × 首都区税基；
  - 禁止自动转移/自动上缴；不新增“自动 remit”participant。
- 风险（需要实现时处理）：
  - `CommandTargets.targetPaths` 必须返回源/目标 actor 格路径，决策 scope 才能判；若 scope 不让省份看到中央格，需要在 `DecisionScopeFunctions` 为 `actor.RemitGovTreasury` 加“目标必须是源 GOV 的 superiorGov”白名单规则；
  - `actor` 模块 handler 不应 `import` unit；目标 GOV 是否为源 GOV 的 superior 由 app 层 scope/工具校验，domain handler 只做账目原子与余额校验；
  - 走 `Command → ChangeSet → Revision`，不新增旁路。
- 验收：不 remit 时中央国库 30/60 天不增加；省发一条显式 remit 后中央到账、省国库减少，金额与账相符；remit 超出可用量被具名拒。

### 1.4 R4 GM 工具补齐（与 #6 对应）

- 已有：`map.UpdateRegion`、`unit.SetJurisdiction`、`unit.SetTaxRate`、`unit.RenameUnit`、`actor.AdjustAccounts`。
- 新增/补齐：
  - `social.UpdateCity` GM 窄工具（region/name/props）；
  - `simos.province.assignCities`（按 at 批量归省）；
  - `simos.gov.remit`（显式省→中央/任意 GOV 国库转移）；
  - 决策人侧：`unit.SetTaxRate` 与 `actor.RemitGovTreasury` 的 catalog/文档/白名单/scope 贯通。
- 更新 `SimosToolsTest` / `McpPortTopologyTest` / `SimosToolSource` 工具面断言（测试阶段统一补）。

### 1.5 R5 重建模拟世界并复跑

- 新建 `2026-10-01-gov-sim-v2`（或清空当前 `gov-sim` 的省结构）：
  1. 空库 bootstrap v17levant；
  2. 三国 `worldgen.initialize`；
  3. 三国 `province.apply`（新算法 + 自动城市归省 + cadence 30 + provider `mosire-flash`）；
  4. 逐 GOV 算行政点数、`recruit` 配满编；
  5. 给 GOV 国库发 60 天行政粮/布启动金；
  6. 复测省界形状（中位/max 长宽比）；
  7. 按用户确认的范围跑 60 天、每 30 天一轮真 LLM；审批由控制方 MCP watcher 处理。
- 本轮范围默认建议：先跑**三国中央 GOV 决策人 3 个 × 2 轮**（约 1 小时）；地方决策人本轮不跑。范围以用户后续裁定为准。

---

## 2. 分批与依赖

| 批次 | 内容 | 依赖 | 交付 |
|---|---|---|---|
| R1 | `ProvinceDivider` 轴顺序修正 | 无 | 编译过 + 预览形状指标 |
| R2 | 城市归省 + region summary/clean gate | R1（重划后才有稳定省 id） | 编译过 + `assignCities` 预览 |
| R3 | 税率决策核实 + `actor.RemitGovTreasury` + GM remit 工具 | 无（可与 R1/R2 并行设计） | 编译过 + 命令/工具探针 |
| R4 | GM 工具面补齐与登记 | R2/R3 | 编译过 + 工具可达性 |
| R5 | 重建世界、配编、60天两轮 | R1–R4 | 推演账本/指令/审批记录 |
| TEST | 统一补测试 + `clean verify` | R1–R5 生产代码落地 | 门禁真数、变异自证 |

★ 每批仍是“一个写代码代理只写生产代码、编译过、不 commit、不跑测试”；控制方审后提交；测试最后统一补。

---

## 3. 非目标

- 不自动追求历史/文化合理的省界（用户裁定 #6）；
- 不改省数、不改自动省名（用户裁定 #2/#3）；
- 不新增自动中央上缴/自动转移 participant；
- 不做多级政府 UI 编辑器（本批先把 GM 工具做全，图形编辑器后置）；
- 不在 R1–R4 期间跑正式测试/`clean verify`（测试统一在 TEST 批）。

---

## 4. 待实现时需确认/可能追问的点

1. `actor.RemitGovTreasury` 命令放 actor 模块是否合适：它只写 actor 账，不读 unit；superior 校验放 app 层 scope/tool。
2. 决策 scope 对“源 GOV 看得见自己、目标 superior 可能在视野外”的处理：建议按命令类型白名单放宽目标格可见性，但必须由 app 层验证 `toUnitId == fromUnit.superiorGov`。
3. `simos.province.apply` 自动归省是否默认开：建议默认开，preview 里列出将改哪些城市。
4. 60 天两轮的最终跑法（3 中央 / 6 个 / 全 30）：R5 前再和用户确认；按实测单 DM 约 9 分钟估算。
