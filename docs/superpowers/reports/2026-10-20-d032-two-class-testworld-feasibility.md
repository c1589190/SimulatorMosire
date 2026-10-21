# D-032 二元农村测试世界可行性与种子架构调查（2026-10-20）

> 状态：调查结论，不是实现验收。尚未修改代码。
> 用户原话（2026-10-20）：
>
> > 注意到，目前分富农/贫农/中农其实意义不大，因为在目前的农业生产方式里，起实际阶层划分效果的只有两类家户，
> > 一类是有地农民，一类是无地农民，是这样吗？
> >
> > 是的，所以你应该先修改测试世界的预设，只在单个hex预设一大块的有地农民家户，和一小块的无地农民家户，
> > 流民也可以取消掉了——无地农民的劳动力分配有所空缺，就可以算是流民的表示了；这样一来，测试世界的
> > 家户流动表，也只需要处理有地-》无地、无地-》有地的循环，规则自然是无地农民财富越多，越流向有地农民，
> > 有地农民财富越少，越流向无地农民——可以回忆一下之前的家户间流动表格机制，告诉我这样是否可行
> > 以及，目前的初始世界为什么甚至需要代码写预设，而不是直接读配置文件或者改db？可以调查一下

## 1. 结论

**可行，而且简化方向正确；但不能只改测试世界预设。**

当前 `grain_farm` 的生产机制确实只剩“有地/无地 + 自耕/雇工 + 土地数量”在起作用，富农/中农/贫农/地主
不是四种生产方式。不过“家户间流动”目前有两套旧机制，而不是一套可直接复用的二元表：

1. `ClassSlideSettlement` 只写 `classStanding.currentPositionId`，**不移动人口、钱、债、土地**；
2. `ModeMigrationSettlement/ModeMigrationPolicy` 才移动人口/钱/债/部分资产，但它是**周期关账时按预期利润**
   触发的复杂迁移，不处理标量 `LAND`，且当前 organizer 迁移仍有 RF4b 的临时整源跳过；
3. D-031 的“共享家户流动表”**只有协议，没有实现**；当前 `ClassTransitionFeed` 只是关账日 class view
   的审计，不是人口迁移。

因此，二元测试世界要真正跑起来，至少需要：
- 新增一个**单 hex 二元种子 profile**；
- 新增/收口一条**二元家户流动结算**；
- 明确流动是“人口成员流动”还是“连土地所有权一起变”；
- 解决单 hex 只有两户时的人口容量/回流问题。

## 2. 当前二元的证据

- `ProductionRoleSeeder.java:95-107`：农村非流民槽位全部映射到 `grain_farm.operator`；
  注释明确“阶级只做投影，不决定生产方式”。
- `ClassProjectionBook.java:530-589`：`rich/middle/poor/landless` 是从 `ownLand`、`netLaborSold`、
  `debtStress` 等实际状态反推的投影。
- `ClassLadderBook.java:36-48`：`poor/middle/rich_peasant` 三个位置的角色元组完全相同，
  当前机械序表达不出“富农→中农→贫农”的阶梯。
- `ProductionPayloadSeeder.java:230-246`：`grain_farm × LAND` 不发 rentRule；当前标准农业没有独立租佃机制，
  地主在本模式里也只是较大的土地持有者。

## 3. 四标签残留的运行时影响

虽然标签不决定生产方式，当前仍有四类“初态/持久参数”影响世界：

| 残留 | 位置 | 说明 |
|---|---|---|
| 初始人口比例 450/350/150/50 | `WorldBalanceSeeder.CLASS_SHARE_PER_MILLE` | 每池按四阶层切人 |
| 初始 LAND 权重 100/250/300/350 | `LandOwnershipSeeder.RURAL_FARM_LAND_WEIGHT_PER_MILLE` | 四阶层初始土地量不同 |
| 初始口粮天数 30/60/120/250 | `WorldBalanceSeeder.INITIAL_RATION_DAYS_BY_CLASS` | 首轮抗饥荒能力不同 |
| 劳动参与率 950/900/750/100 | `IndustryClassSlots.LABOR_PER_MILLE` | 写入 `HouseholdEconomy.participationPerMille`，运行期一直参与劳动折算 |
| 流民 5% | `WorldBalanceSeeder.DISPLACED_SEED_PER_MILLE` | 从最贫档切出单独 displaced 户 |

⇒ 当前不是“四标签纯粹无用”，而是**四标签不再是生产方式，却仍作为初态资源包和固定劳动参与率残留**。
二元测试世界应把这些参数显式化，而不是继续借用四标签隐式携带。

## 4. 旧“家户流动表”机制的真实状态

D-031 `2026-10-15-d031-interface-first-design.md` §9 的设计是：

```text
行 = 源家户 × 目标家户
字段 = 目标 hex / 目标经济位置 / 各阶段贡献 / 合成速度 / 预计与实际人数 / 限制理由
阶段 = init（本 hex + 六邻全部方向）→ culture → production → economy（负债→库存→生产收益）
落账 = Social 的 HouseholdWorkOrderPlan + 经济财货/债 + 同一次 Command/ChangeSet/Revision
```

但这只是设计，代码里没有实现。当前实际是：

- `ClassSlideSettlement`：市场轮末按 `债务/资产` 比率写 `classStandings`；不移动人口/资产/土地，
  且目前 poor/middle/rich 位置无高低梯度。
- `ModeMigrationPolicy`：按预期利润差选目标 mode/hex，输出 `source → target` 的 `MigrationMove`；
  `ModeMigrationSettlement` 移动人口、钱、债和部分可移动资产；它只在**周期关账**触发，
  LAND/WORKSHOP 不跨 hex，标量 `landHoldings` 迁移未接入，当前 organizer source 还被整源跳过。
- `ClassTransitionFeed`：只是 `view.stratum` 的关账审计，不执行人口流动。

⇒ 二元测试世界如果复用旧机制，会遇到“语义不对 + 代码过重”的问题；更适合写一个**单 hex 二元专用结算**，
而不是继续扩展 `ModeMigrationPolicy`。

## 5. 二元单 hex 测试世界的建议形状

> 以下按“整个测试世界只保留 1 个 hex”理解；若用户本意是 32 hex 每 hex 都这样，结构相同，只是种子规模不同。

```text
1 个 hex，全平原，固定可耕 LAND = 该 hex 的 arableMilliMu
2 个家户：
  hh-0_0-landed   人口 = 总农村人口的大部分（如 160/200）
                  持有全部 LAND，grain_farm unit，可作为 operator/雇主
  hh-0_0-landless 人口 = 剩余小部分（如 40/200）
                  持有 0 LAND；在 landed unit 上作为 hired_laborer
                  未被雇佣的劳动 = idle（不再建 displaced 户/独立 displaced mode）
1 个 grain_farm 生产计划；两个 unit 均挂同一 plan
1 个 market zone；不建城市/城镇人口/手工业/商人（或只保留最小政府）
```

关键点：**无地户不是“另一个人种”，而是 LAND=0 的家户**；它可以在 landed unit 上受雇，
其未分配劳动自然就是失业/流民表示。这样不建 `displaced` 户也能保留“劳动力空缺”读数。

### 5.1 必须先定的核心口径

**A. 流动的是人，还是土地所有权？**

- **A1 人口成员流动**：只把人口/劳动/按比例的钱和债从一户转到另一户；土地仍归 landed。
  这最接近旧共享流动表，`ModeMigrationSettlement` 已有大部分人口/钱/债转移能力；
  但“无地→有地”只改变人口归属，不改变土地所有权，无地户本身仍无地。
- **A2 家户阶层/土地所有权变化**：当无地户财富超过阈值时，把一部分 LAND 从 landed 转给 landless，
  并重建/重绑生产 unit、roster、operator；有地户财富过低时反向转出土地。
  这能真正产生“有地/无地”状态切换，但当前没有标量 LAND 迁移，`ModeMigrationSettlement` 也不处理
  `landHoldings`；需要新写 land leg + production rebind。
- **A3 两者都要**：人口和土地按同一方向/比例移动，语义最完整，但最复杂。

**B. “财富”是什么？**

- 纯库存财富（钱 + 商品按市价 + LAND 市值，不扣债）；或
- 净资产（再扣债务）。
- 现有 `PureInventoryWealthBook` 提供纯库存财富序；`ClassSlideSettlement` 用债务/资产比。
- 建议测试世界先用**纯库存财富**，因为用户原话是“财富越多/越少”；债务作为第二维以后再加。

**C. 两户人口容量与回流**

- 当前 `MAX_HOUSEHOLD_POPULATION = 200`，而单 hex 总人口可设为 200。
- 若 160 landed + 40 landless，landless 一次最多搬 40 人到 landed，landed 达 200、landless 归 0；
  此后没有 landless 户可反向承接，循环断掉。
- 需要至少一种：
  - 设 `MIN_HOUSEHOLD_POPULATION` / 每次最多搬一小部分 + 保留种子人口；
  - 允许空壳户并允许从空壳重新吸纳人口（但人口源从哪来要定）；
  - 或不是搬人，而是 A2 的土地/阶层切换，两户始终存在。

## 6. 建议的流动规则（若采 A1 人口流动）

在**每个市场轮末**（与 `ClassSlideSettlement` 同时点，或独立结算器）：

1. 对每户算 `wealth = money + goodsAtMarketPrice + landValue`（先不扣债）；
2. 只有两户时，比较 `wealthLanded` / `wealthLandless`；
3. 若 `wealthLandless > wealthLanded + threshold`：
   - 无地 → 有地方向：把 landless 的一个有界人口切片（以及按比例的钱/债/可移动资产）
     迁入 landed；
4. 若 `wealthLanded < wealthLandless - threshold`：
   - 有地 → 无地方向：把 landed 的一个有界切片迁入 landless；
5. 速度 = `clamp((wealthDiff / scale), 0, cap)`；每轮最多迁 `max(1, population × cap‰ / 1000)`；
6. 保留至少 1 人/户或显式空壳规则；无地户的未分配劳动走 idle，不建 displaced 户；
7. 人口变更走 Social 工单/`EconomyPopulationTransfer`，钱/债/资产走经济变更，同一 revision 原子提交。

这样“表”退化成每 hex 两条有向边（landed→landless、landless→landed），不需要 D-031 的
source×target 全表，也不需要六邻枚举；确定性和可测性都更好。

## 7. 为什么当前初始世界是代码预设，而不是配置/DB

### 7.1 32hex 测试世界是 test-scope fixture

`Rural32HexWorld.java` 位于 `simos-app/src/test/java`，直接硬编码：

- 32 个 hex（4×8，全 plains）；
- 每 hex 农村人口 200；
- 首都 (0,0) 城镇人口 700；
- 直接构 `SettlementPlan`，再逐条发 `social.SetPopulation` / `CreateCity` / `SeedGroups` /
  `production.Seed` / `economy.Seed` / `actor.Seed`。

它是为 360 tick 数据表服务的确定性测试夹具，不是运行期世界配置。`MultiTickSimRunner` 每次跑前
`wipe(caseDir)`，所以直接改 `test-world/multitick/.../store/simos.db` 会在下一轮被清掉。

### 7.2 真实 worldgen 配置不包含这些阶层/土地/口粮字段

`config/worldgen/v17levant-nations.json` 是 `WorldgenConfig` 的**国家人口/城市化/军队**输入；
`WorldgenConfig` parser 没有 class shares、land weights、ration days、mobility 等字段。

`EconomySeeder` 类注明确写着：这些“场景参数”在当前配置里没有，因此以具名常量落在代码里
（`CLASS_SHARE_PER_MILLE`、`DISPLACED_SEED_PER_MILLE`、`RURAL_FARM_LAND_WEIGHT_PER_MILLE`、
`INITIAL_RATION_DAYS_BY_CLASS`、`GENESIS_GRAIN_RESERVE_MULTIPLIER` 等）。

所以不是“架构上不能读配置”，而是**配置 schema 没建立 + 测试世界用了 test fixture 捷径**。

### 7.3 改 DB 不是正确的写入口

`store/simos.db` 是 revision/checkpoint 存储，不是种子配置文件。直接改 DB 会绕过：

- `Command → ChangeSet → Revision`；
- 各模块 Codec/ChangeSet 的完整状态往返；
- `EconomyData`/`SocialData`/`ActorData`/`ProductionData`/`landHoldings` 的跨切片一致性；
- replay/checkpoint 的 revision 链。

而且 360 runner 每轮从 `Rural32HexWorld.state()` 重新创世，DB 改动不可复现。正确路线是：
**新增配置 schema + 读取器 + seed builder**，再把同一个 seed 结果喂给 `social.SeedGroups` /
`production.Seed` / `economy.Seed` / `actor.Seed`，保持同批命令原子落盘。

## 8. 推荐落地顺序

1. **先定 A/B/C 三个口径**（人口 vs 土地、财富定义、两户容量/最小人口）。
2. 新增一个 `two-class-single-hex` 种子 profile（先不替换 32hex 世界）：
   - 1 hex、1 landed 户、1 landless 户；
   - landless 作为 landed unit 的 hired_laborer，未分配劳动走 idle；
   - 不建 displaced 户。
3. 写二元流动结算器，只处理两条有向边；先做 A1（人口流动），验证守恒/确定性。
4. 若确认需要“无地户真正获得/失去土地”，再加 A2 的标量 LAND 转移 + `grain_farm` unit/roster rebind；
   这部分不能在 `ModeMigrationSettlement` 里偷偷扩展，必须单独责任区。
5. 最后把 profile 接到 JSON seed spec，删除测试世界对四标签常量的依赖；DB 只作为生成产物。

## 9. 未做/未验证

- 未修改任何代码；未跑新世界；未验证两户人口流动/土地转移。
- 未确认 `MAX_HOUSEHOLD_POPULATION=200` 与单 hex 200 人下的容量策略。
- 未确认 `ModeMigrationSettlement` 的 Social outbox 是否能直接承接二元流动，还是需要独立 work order 桥。
- 未确认 `DISPLACED` mode/位置是否可安全删除，还是应暂时保留为未使用目录项。
- 未改配置 schema、未落 JSON。

## 10. 用户原话附录（逐字）

见本文开头引用块；来源：2026-10-20 当前会话。
