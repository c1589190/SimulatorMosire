# 商品运费「基础费表」纠正批：实现架构账本（责任区 F2，2026-10-09）

> **任务**：用户 2026-10-09 裁定「甲」后的**方向纠偏**——把 F 批（`f3366545`）的「商品运费**系数**（‰，乘在整条费率上）」
> 改成「商品运费**基础费表**」，并**撤销** F 批的费率乘数维。
> **唯一权威文档**：`docs/superpowers/specs/2026-10-09-commodity-freight-and-merchant-ladder-design.md` **§4.1 修正版**（契约）、§5、§6。
> **基态提交**：`f6e6a618`（= 含 §4.1 修正版的 HEAD）。**参照提交**（逐值对照轮）：`f3366545`。
> **任务板**：`team_task_get task-24` 返回"不是 active Agent Team 成员"⇒ **未能 claim/complete**（按任务书"不要卡住"继续执行）。
> ★ 本账本只记决策相关事实与真实读数，不抄工具输出、不写流水账。

---

## 0. 一句话结论

商品维**只剩一处**（面值维 = 基础费表，GM 可改、读状态）；费率维回到纯"距离/辐射/道路/城乡"。
**未设表的世界与 `f3366545` 逐值相同**（two worlds：SHA-256 摘要逐字相同、归一化全日志 diff = **0 行**、239/87 笔真实成交运费逐笔相同）；
旧档（缺组件键 / 带旧名 `commodityFreightPerMille`）**不炸且可读**，基础费回落"现行硬编码分档"（粮1/纤维1/布2/工具3；未登记 ⇒ 1）。

---

## 1. 关键调查结论（`file:line`，2026-10-09 于本工作树核实）

| # | 证据 | 结论 / 影响 |
|---|---|---|
| S1 | `MarketSettlement.commodityFreightBaseMilli(CommodityId)`（改前 `:4781-4797`）是**硬编码** dispatch：粮1/纤维1/布2/工具3/其余1，注释原文即用户口径「运费只和商品种类有关」 | 商品维**早就有**，且是**面值维**；F 批的"系数"是**第二条**商品维（费率乘数）⇒ 用户裁定「甲」= 把这条面值维搬进状态表 + 撤销乘数维 |
| S2 | `freightUnitMilli(CommodityId, rate, carrierCost)`（改前 `:4830`）= `max(1, ⌈基础费×(1000+费率)×(1000+承运)÷1e6⌉)`，三项来源里只有**第①项**是商品维 | 唯一算式**逐字保留**；商品维只需换掉第①项的来源（硬编码 → 状态表），②③一字不动 |
| S3 | F 批在 `TransportTariff.perMille` 加第 6 入参（`TransportTariff.java:127-170`）、在 `MarketTopology` 加 3 个商品维费率入口（`:715/:736/:777`）、在 `ExpectedProfitBook` 取全表最大系数（`:1270`） | 这三处 = 乘数维的**全部**落点 ⇒ 撤销它们（回 `f3366545^` 形状）；`ExpectedProfitBook` 回到 2 参费率（商品不参与费率上界） |
| S4 | `MarketTopologyBook.from(state)`（`MarketTopologyBook.java:145`）是**唯一**拓扑装配点；`MarketSettlement` 手上只有 `MatchContext`（`MarketSettlement.java:6337`，**无** EconomyData 引用） | "基础费从状态读"的落点 = 组合根 `from(state)` 把 `economy.commodityFreightBaseMilli()` 注入拓扑快照；`MarketSettlement` 经 `ctx.topology` 读（与 F 批同一装配口径，权威仍是 EconomyData） |
| S5 | 快照写入的组件名 = record 组件名（`EconomyData` 第 38 组件）；`EconomyCodec.keyModule()` 早已注册 `CommodityId` 键反序列化器 | 改名后**快照/变更集的线格式**自动跟着走，不需要新注册；但**旧档里的旧名**必须显式兼容（见 §4） |
| S6 | ★★ **重放走 Core 的第四台 mapper**（`simos-core/.../timeline/Timeline.java:387` 的 `readChangeSet`），**不经过** `EconomyCodec`；既有先例 = `HouseholdEconomy` 上的 `@JsonIgnoreProperties("debts")`（`model/HouseholdEconomy.java:80`，注明"只在 codec 里摘键会在重放路径当场死"） | **探针实测复现**：只加 codec 迁移时，新码打开 F 批写的 store ⇒ `readChangeSet` 抛 `UnrecognizedPropertyException: Unrecognized field "commodityFreightPerMille"`（**每一行 F 批 revision 都带该组件键**）⇒ 必须加**类型级**具名忽略（见 §4） |
| S7 | `EconomyStateBuilder.build`（`:395-402`）逐组件显式带过；`EconomySeedHandler:235`、`EconomyClearRegionHandler:440` 同理 | 三处 `base./staged.` 必须跟着改名（漏 = advance/补种/清区静默抹表） |
| S8 | `Shell.java:666` 注册 handler、`CatalogTool.PAYLOAD_HINTS:402` 载荷提示、`SimosToolSource:787` GM 窄工具 | 命令/工具**名**不变 ⇒ 计数面（handler 137 / GM 工具 / 窄写 66）与权限单调性**零改动**；只改**载荷字段名**与文案 |
| S9 | 硬编码分档常量 `MARKET_FREIGHT_BASE_PER_UNIT_*`（改前 `MarketSettlement.java:4764-4769`）**零测试引用**（`grep simos-*/src/test` = 0） | 可安全搬走（搬到 `CommodityFreightBase`），不存在"测试还指着旧常量"的连带 |
| S10 | `MarketTopologySingleRegionTest:109` 用 **2 参**费率入口、`TransportTariffP4Test` 用 **5 参** `perMille` | 撤销后两个入口形状**未变** ⇒ 这两条既有用例不受影响 |

---

## 2. 实现架构（本批实际长出来的形状）

```
状态层   EconomyData 第 38 组件 commodityFreightBaseMilli : Map<CommodityId, Long>   （EconomyData.java:342）
         语义 = 基础运费（毫计价货币 / 商品单位 / 程），与价格无关；值域 ≥ 0（0 = 明确"免基础费"）
           · compact 构造器：null ⇒ 空表；逐键复制 + 冻结；值 < 0 ⇒ 具名抛（COMMODITY_FREIGHT_CONTRACT_PREFIX:364）
         缺键 ⇒ 具名缺省 = CommodityFreightBase.legacyMilli（现行硬编码分档；唯一拼写点）
         唯一读取口 = EconomyData.commodityFreightBaseMilliOf(CommodityId)（:373）
缺省解析 model/CommodityFreightBase.java（**新**，67 行）：粮1 / 纤维1 / 布2 / 工具3 / 未登记 ⇒ 1（DEFAULT_MILLI）
         它不是第二本权威：表里**有**该商品的键 ⇒ 状态值唯一权威（0 也在表里，读出来就是 0）
算式     MarketSettlement.freightUnitMilli(long commodityBaseMilli, long ratePerMille, long carrierCostPerMille)（:4825）
         unit = max(1, ⌈ 基础费 ×(1000+路线费率)×(1000+承运成本) ÷ 1,000,000 ⌉ )   ← 算式逐字保留（只把第①项变成入参）
         基础费读口 = MarketSettlement.commodityFreightBaseMilli(MarketTopology, CommodityId)（:4776）→ topology.commodityFreightBaseMilliOf
费率     TransportTariff.perMille(distance, radial, road, cityDiscount, ruralPenalty)（:95）—— **5 参，无商品维**
         MarketTopology.freightPerMilleBetween(from,to)（:708）/ (from,to,cityDiscount,ruralPenalty)（:767）—— **只有这两条**
拓扑快照 MarketTopology 字段 commodityFreightBaseMilli（:122）+ commodityFreightBaseMilliOf（:732）
         + withCommodityFreightBase(Map)（:745，"没传 ⇒ 空表 ⇒ 现行分档 ⇒ 逐值不变"这条默认语义写在一处）
组装     MarketTopologyBook.from(state)（:145-146）= build(...).withCommodityFreightBase(economy.commodityFreightBaseMilli())
命令     economy.SetCommodityFreight（spi/EconomySetCommodityFreightHandler.java；GmOnlyCommand 不变）
         载荷 {"commodityId":"…","baseMilli":N,"reason"?}（:84）；三条具名拒：unknown-commodity / negative-freight /
         freight-unchanged（`current` 走 commodityFreightBaseMilliOf ⇒ 缺键时的"现值"就是现行分档）
变更集   FieldDelta<Long> commodityFreightBaseMilli（键 = CommodityId 裸值；change/EconomyChangeSet.java:181）
         三处齐：between（:366）/ apply(rebuild+CommodityId::parse)（:429）/ isEmpty
兼容     EconomyData / EconomyChangeSet 各一类型级 @JsonIgnoreProperties("commodityFreightPerMille")（:290 / :134）
         + EconomyCodec 两个反序列化器各一句 dropRevokedCommodityFreightPerMilleComponent（:578，快照 :713 / 变更集 :857）
旧档     无该组件键 ⇒ 空表 ⇒ legacyMilli ⇒ 逐值等于改动前（I-F1）；带旧名 ⇒ 具名摘除（不翻译，见 §4）+ 日志
```

**数据流**：`Command(economy.SetCommodityFreight{baseMilli})` → handler 读 `EconomyData.commodityFreightBaseMilliOf` 判"逐字相同" →
`withCommodityFreightBaseMilli` → `EconomyChangeSet.between` → Core 落 revision →（下一次 advance/读数）
`MarketTopologyBook.from(state)` 把表注入拓扑 → `MarketSettlement.commodityFreightBaseMilli(ctx.topology, 商品)` →
`freightUnitMilli(基础费, 路线费率, 承运成本)` → 成交运费 / 承运收费 / 商号收入估算上界。

---

## 3. 逐值等价（本批第一判据）—— 证据

**装置**：`/tmp/cfbprobe/`（**不进仓库、不进 `src/test`**）

- `src-common/.../LegacyDigestProbe.java`：**只用改动前后都存在的老 API** ⇒ 同一份源码对新码/旧码各编一次；
  真 `Shell.start` + 真创世 + 真推进，逐日打 `MARKET/HOUSEHOLD/MERCHANT/LANE/FILL/REPORT` 规范行 + SHA-256 摘要。
- 旧码对照：`git worktree add --detach /tmp/cfbprobe/oldwt f3366545` + `tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am`。

**跑法**（每条真跑，exit=0）：

```bash
CP_NEW="$(ls -d simos-*/target/classes | tr '\n' ':')/home/cna/SimulatorMosire/simos-app/target/simos-app-0.1.0-SNAPSHOT-shaded.jar"
java -cp "/tmp/cfbprobe/out-new:$CP_NEW" io.mosire.simos.app.time.LegacyDigestProbe three-powers /tmp/cfbprobe/store-new-3p 5
# 旧码轮：同一份源码、同一命令，classpath 前缀换成 /tmp/cfbprobe/oldwt/simos-*/target/classes（jar 仍取主树那份供三方依赖）
```

| 世界（5 天） | `f3366545` 摘要（旧码轮） | 本批新码摘要 | 归一化全日志 diff |
|---|---|---|---|
| `three-powers` | `2c288f4cb77dba9d6b7e8202138a2a7597c0bd297d5382cabd3bb2ad4155c354` | **同** | **0 行**（239 笔真实成交运费逐笔相同） |
| `small-world` | `fead1d0871f1bb08dccae5d6d62fb60fc6349de4d9238fcc30b153fe5bbc4492` | **同** | **0 行**（87 笔） |

★ 归一化只抹掉**环境噪声**（临时端口号、store 路径、时间戳前缀）；域内行一字不改。两轮摘要与两笔账（F 批账本 §7.1 记的
`a558422f` 值）也逐字相同 ⇒ "撤掉乘数维（缺键=1000）+ 基础费表缺键=现行硬编码"两条**恰好抵消**，实测成立。
★ 日志面同样零差异：新码在**新世界**上不产生任何旧档兼容日志（旧名只在读旧档时出现）。

---

## 4. 旧档 / 旧名字的处置与理由

| 情形 | 处置 | 依据 / 实测 |
|---|---|---|
| 缺该组件键的档（本批之前的任何档） | `null ⇒ 空表` ⇒ 每个商品走 `CommodityFreightBase.legacyMilli` = 粮1/纤维1/布2/工具3（未登记 ⇒ 1） | `OLD_ARCHIVE_ABSENT_KEY table={} grainBase=1 fiberBase=1 clothBase=2 toolBase=3 ironBase=1`；且空表世界与 `f3366545` 逐值相同（§3） |
| F 批写的档（带旧名 `commodityFreightPerMille`，**每一行 revision 都带**） | **不翻译，整体作废**：快照路径具名摘除 + 日志；变更集/重放路径靠**类型级** `@JsonIgnoreProperties` 忽略 ⇒ 读成 `Unchanged` ⇒ 基础费回落现行分档 | ① 量纲/方向不同、**没有一对一迁移**（"费率千分乘数 1500"硬凑成"基础费 1500 毫/单位"会把运费放大三个量级）；② 用户裁定「甲」= 撤销该维。实测：`OLD_ARCHIVE_REVOKED_KEY loaded=true table={} grainBase=1 clothBase=2 toolBase=3`；真档 `store-old-gm`（旧码下 `grain perMille=1500`）用新码读回 ⇒ **所有 lane = 35‰、grain base = 1**（F 批的 52‰ 消失） |
| 两键并存 | **fail-closed** 具名抛（同一件事两处拼写） | `OLD_ARCHIVE_BOTH_KEYS failClosed=IllegalStateException:EconomyData 不得同时给 commodityFreightPerMille（已撤销的费率乘数维，F 批 f3366545）与 commodityFreightBaseMilli（基础费表）…` |
| 旧档整体可读性 | **实测不炸**：新码打开旧码写的 store（`store-old-3p` / `store-old-sw` / `store-old-gm`）exit=0；旧码当时的 6 条区域 lane 行与新码读回的 lane 行**唯一化后 diff = 0** | `diff <(sort -u lanes-old.txt) <(sort -u lanes-new-read.txt)` ⇒ 0 |

**为什么必须是类型级注解**（本批最贵的一条发现）：探针第一版只在 `EconomyCodec` 里摘键，结果新码打开 F 批的档时
**死在重放路径**：`Timeline.readChangeSet` → `CHANGESET_MAPPER`（Core 的第四台 mapper，不经过 EconomyCodec）⇒
`UnrecognizedPropertyException: Unrecognized field "commodityFreightPerMille" (class EconomyChangeSet)`。
既有先例是 `HouseholdEconomy` 的 `@JsonIgnoreProperties("debts")`（同样注明"重放走第四台 mapper"）⇒ 照抄形制，
`EconomyData` 与 `EconomyChangeSet` 各加一条**只忽略这一个名字**的注解（`ignoreUnknown` 仍 fail-closed）。
★ **代价（如实记）**：第四台 mapper 那条路径是**静默**的（注解无法落日志）；codec 路径仍有具名摘除 + 日志，
且按"**有没有数据被丢**"分级：`revokedRows > 0 ⇒ INFO`（GM 设过的值被作废，必须看得见）、`0 ⇒ DEBUG`（什么都没丢，不刷屏）。

**日志实证**（同一次 `mode=full` 运行）：
```
INFO  event=ECONOMY_LEGACY_COMMODITY_FREIGHT_PER_MILLE_DROPPED where=EconomyData
      revokedComponent=commodityFreightPerMille keptComponent=commodityFreightBaseMilli revokedRows=3 …
```
（恰好 1 条 —— 只有"带 3 行数据的那个节点"被作废；空表节点走 DEBUG，默认不打印。）

---

## 5. ★ 怎么保证"两个商品维只剩一个"

1. **删掉乘数维的三处落点**：`TransportTariff` 第 6 入参（回 5 参）、`MarketTopology` 三个商品维费率入口
   （回 2 参/4 参）、`ExpectedProfitBook` 的"全表最大系数"上界（回 2 参）。
   静态审计：`TransportTariff` 里 `CommodityId` **零命中**；`MarketTopology` 的费率入口只剩
   `(from,to)` 与 `(from,to,cityDiscount,ruralPenalty)` 两条，**无商品入参**。
2. **商品维只活在面值维**：全仓 `commodityFreightBaseMilli*` 的读取点只有
   `MarketSettlement:3643 / 3852 / 4381`（路线构建与承运分摊）→ 都收敛到唯一解析器 `:4776` → `Topology:732` → `CommodityFreightBase.legacyMilli`。
   费率公式的两处调用点（`MarketTopology:715/:774`）都把商品维**排除在外**。
3. **算式唯一拼写点**：`freightUnitMilli`（`MarketSettlement:4825`）—— 全仓只有它把"基础费"乘进运费；
   三个调用点（`:3677 / :3852 / :4650`）传的都是"读状态表得到的基础费"，没有任何第二处把商品乘到费率上。
4. **缺省唯一拼写点**：`CommodityFreightBase.legacyMilli`（`:50`）—— `EconomyData:373` 与 `MarketTopology:732` 都调它，
   不存在第二份分档表（改前那份硬编码常量已从 `MarketSettlement` **删除**，值原样搬入）。
5. **回归护栏**：`TransportTariff` 与 `MarketTopology` 的类注写明"商品维**不在这里**（唯一权威 = 基础费表）"，
   并留 F 批的历史留痕（谁的、哪次提交、为什么撤销），防止后人再把商品维加回费率。

---

## 6. 探针输出（T1' / 旧档 / 负向 / 往返 / 推进后）—— 真跑读数

**装置**：`/tmp/cfbprobe/src-new/.../FreightBaseProbe.java`（`mode=full|read`）+ `src-new/.../economy/time/UnitFreightReader.java`
（同包桥梁 ⇒ 调的是**生产函数本身** `MarketSettlement.freightUnitMilli` / `commodityFreightBaseMilli`，不复制公式、不复制分档）。
两世界各跑一轮：`three-powers`（1332 个市场格配对）与 `small-world`（342 个），30 天推进，exit=0。

### 6.1 T1'（基础费 ⇒ 单位运费）

```
TABLE_BASE {} → grain=1 fiber=1 cloth=2 tool=3 iron=1 wood=1        ← 未设表 = 现行硬编码分档
LANE_BEFORE_SHORT from=-3_2 to=-3_3 distanceHex=1 ratePerMille=10  （短途）
  grain{base=1,unit=2} cloth{base=2,unit=3} fiber{base=1,unit=2} tool{base=3,unit=4} iron{base=1,unit=2} wood{base=1,unit=2}
LANE_BEFORE_LONG  from=-3_0 to=0_3  distanceHex=6 ratePerMille=95  （长途）
  grain{base=1,unit=2} cloth{base=2,unit=3} fiber{base=1,unit=2} tool{base=3,unit=4} iron{base=1,unit=2} wood{base=1,unit=2}
```
⇒ **商品维现在管整条运费**：短途与长途**两条 lane 上**单位运费都随基础费变（不再只影响长途的费率乘数）。

**`grain.baseMilli = 2`（APPLY 5 次全 Committed，head 1→6）**：

| 读数 | 短途 lane（10‰） | 长途 lane（95‰） |
|---|---|---|
| 单位运费 base=1 | 2 | 2 |
| 单位运费 base=2 | **3** | **3** |
| **未取整乘积** base=1 → base=2 | 1,111,000 → 2,222,000（**恰好 ×2**） | 1,204,500 → 2,409,000（**恰好 ×2**） |
| 全配对（1332 / 342） | `rawProductLinear=1332/1332`、`unitWithinOneMilliOfDouble=1332/1332`、`unitExactDouble=0` | 342/342 同 |

★ **如实记（本批唯一一处"判据字面不符"）**：**单位运费**不是"恰好翻倍"，而是 **未取整乘积恰好翻倍**；
`max(1, ⌈·⌉)` 把 1→2 抬成 2、2→3（真实 lane 的 `(1+费率)(1+承运成本)` 系数只有 1.01~1.23，永远跨不过整数台阶）。
**无取整时"恰好翻倍"的逐值证据**（仍用生产函数，`rate=1000, cost=0` ⇒ 系数恰为 2）：
```
series(rate=1000,cost=0){ unit(b=1)=2 unit(b=2)=4 unit(b=3)=6 unit(b=4)=8 unit(b=8)=16 } double1to2=true double2to4=true
```
**真实成交（钱真的变了）** —— 同 lane、同量、同对手方，唯一差异是基础费：
```
无表      : FILL day=3 from=-1_-1 to=-2_-1 grain qty=19345 freightPerUnitMilli=2 freightMilli=39
grain=2   : FILL day=3 from=-1_-1 to=-2_-1 grain qty=19345 freightPerUnitMilli=3 freightMilli=59
无表      : FILL day=3 from=-1_-1 to=-2_-1 grain qty=43761 freightPerUnitMilli=2 freightMilli=88
grain=2   : FILL day=3 from=-1_-1 to=-2_-1 grain qty=43761 freightPerUnitMilli=3 freightMilli=132
无表（-1_1→-3_1）: grain qty=29935 freightPerUnitMilli=2 freightMilli=60
grain=2（同 lane）: grain qty=29935 freightPerUnitMilli=3 freightMilli=90
```

**`tool.baseMilli = 1` ⇒ 与粮同级**（改前 tool 硬编码 3、GM 改不动）：
```
SAME_LEVEL label=TOOL1 ratePerMille=35 grainBase=1 toolBase=1 grainUnit=2 toolUnit=2 equal=true
（改前：tool base=3 ⇒ unit=4 > grain unit=2）
```

**`baseMilli = 0` 必须被接受 + `max(1,·)` 仍抬到 1**：
```
APPLY APPLY_wood0 result=Committed@5 revisionsAdded=1
ZERO commodity=wood stateTable={grain=1, tool=1, wood=0} baseMilli=0 rawProduct=0 unitMilli=1
     synthetic(0,1000,0)=1 synthetic(1,1000,0)=2
```

### 6.2 负向（各自具名拒 + head 不动 + 零 revision）

```
REJECT NEG_negative          Rejected[negative-freight: 基础运费必须 ≥ 0（毫/单位/程）：负值不是'说不出价'；★ 0 是合法的 = 该商品免基础费] headUnchanged=true
REJECT NEG_unknownCommodity  Rejected[unknown-commodity: 商品不在词表里（本仓商品恰 6 种：[grain, cloth, fiber, tool, iron, wood]）] headUnchanged=true
REJECT NEG_unchangedGrain    Rejected[freight-unchanged: 与现值逐字相同（现值 = 1 毫/单位/程）…（★ 缺键时的现'值'就是现行硬编码分档 1）] headUnchanged=true
REJECT NEG_unchangedCloth    Rejected[freight-unchanged: … 现值 = 2 …（现行硬编码分档 2）] headUnchanged=true   ← 证明缺键 ⇒ 布 2（不是 1）
REJECT NEG_unchangedIron     Rejected[freight-unchanged: … 现值 = 1 …] headUnchanged=true                        ← 未登记商品 ⇒ 1
REJECT NEG_oldPayloadField   Rejected[economy.SetCommodityFreight 的字段 baseMilli 必须是整数: null] headUnchanged=true  ← 旧字段名 perMille 不再被接受
```
（`mode=full` 的 `REJECTED` 日志计数 = 6，与上表逐条对应。）

### 6.3 往返 + 重放（第四台 mapper）+ codec

```
ROUNDTRIP delta=Upsert[entries={grain=2, tool=1, wood=0}]
ROUNDTRIP apply(diff(base,target),base).equals(target)=true            （铁律 5）
REPLAY readChangeSet->apply.equals(target)=true                        （Timeline.changeSetJson → readChangeSet → apply）
REPLAY_LEGACY_KEY renamed=true fieldUnchanged=true apply(legacyCs,base).equals(base)=true   ← 旧名变更集：读成 Unchanged、不抛
REPLAY_ABSENT_KEY fieldUnchanged=true apply(cs,base).equals(base)=true                      ← 旧变更集（整个组件键缺席）
CODEC decode(encode(x)).equals(x)=true
OLD_ARCHIVE_ABSENT_KEY table={} grainBase=1 fiberBase=1 clothBase=2 toolBase=3 ironBase=1
OLD_ARCHIVE_REVOKED_KEY loaded=true table={} grainBase=1 clothBase=2 toolBase=3
OLD_ARCHIVE_BOTH_KEYS failClosed=IllegalStateException:EconomyData 不得同时给 commodityFreightPerMille（已撤销的费率乘数维，F 批 f3366545）与 commodityFreightBaseMilli（基础费表）：同一件事两处拼写，无法判谁对
```

### 6.4 推进后仍在（★ `EconomyStateBuilder` 带过没漏）

```
三区世界：30 天 ADVANCE 全 Committed ⇒ AFTER_ADVANCE revision=36 table={grain=2, tool=1, wood=0} grainBase=2 toolBase=1 woodBase=0
单区世界：同（revision=36，表仍在）
TOOLFACE tool=simos.economy.setCommodityFreight gmBucket=true decisionBucket=false gmTools=161 decisionTools=44
```

### 6.5 旧档端到端（真 store，非 JSON 手改）

| store（旧码 `f3366545` 写） | 新码读回 | 结论 |
|---|---|---|
| `store-old-3p`（三区，5 天，**未设表**） | exit=0；6 条区域 lane 行与旧码当时 `diff = 0`；`READ_BASE` = 粮1/布2/纤维1/工具3/铁1/木1 | 旧档逐值不变 |
| `store-old-sw`（单区，5 天，未设表） | exit=0；基础费同上 | 同上 |
| `store-old-gm`（旧码提交 `grain perMille=1500`） | exit=0；撤销维摘除（INFO，`revokedRows` 有值）；**所有 lane = 35‰**、grain base = 1 | 撤销维真的作废 ⇒ 回到 F 批之前的读数 |

---

## 7. 偏离设计书处（主动报）

| # | 偏离 | 事实与理由 |
|---|---|---|
| **D1** | ★ **"缺键 ⇒ 具名缺省 = 1"落成"分级缺省"**：表里**没有该商品** ⇒ `CommodityFreightBase.legacyMilli(商品)`（粮1/纤维1/布2/工具3），**不是**一律 1 | §4.1 同时要求"旧档缺**该组件键** ⇒ 归一到现行硬编码分档（粮1/纤维1/布2/工具3）"。而**新世界创世与夹具都传空表**（`Map.of()`）⇒ 若空表一律取 1，布/工具会当场从 2/3 变 1，"未设表的世界逐值不变（I-F1）"与**本批第一判据**（与 `f3366545` 逐值相同）会双双失败。两个"缺"（缺组件键 / 缺商品键）在**同一个读取口**归并：**有键 ⇒ 状态值唯一权威（含 0）**；无键 ⇒ 现行分档。实测：空表时 cloth=2 / tool=3（§6.2 的 `NEG_unchangedCloth` 与 §6.5 的 `READ_BASE`）。 |
| **D2** | 状态读取**经拓扑快照**（`MarketTopologyBook.from` → `MarketTopology` 字段），而不是 `MarketSettlement` 直接读 `EconomyData` | `MatchContext` 里没有 EconomyData（`MarketSettlement.java:6337`）；组合根是唯一"看得见状态"的装配处（F 批同一口径）。权威仍是 EconomyData：只有 `MarketTopologyBook:146` 一处注入，`MarketTopology` 只做逐键拷贝 + 冻结。 |
| **D3** | ★ 新增**类型级** `@JsonIgnoreProperties("commodityFreightPerMille")`（`EconomyData` / `EconomyChangeSet`）—— 设计书未提 | 只在 `EconomyCodec` 摘键时**旧档打不开**（探针实测：`readChangeSet` 抛 `UnrecognizedPropertyException`，因为 F 批**每一行** revision 都带该组件键）。既有先例 = `HouseholdEconomy` 的 `"debts"`。代价：该路径静默（§4 已记）。 |
| **D4** | 拒因码 `non-positive-freight` → **`negative-freight`** | 0 现在**合法**（= 免基础费），旧码名会撒谎；设计书只列了三种拒因、没给码名。 |
| **D5** | `MarketTopologyBook` 保留 `build(...)` + `from(...)` 两层（F 批拆出的形状），没有还原成单方法 | 注入点仍需要"先装配后注入"；形状与 F 批一致 ⇒ diff 更小。 |
| **D6** | 硬编码分档常量从 `MarketSettlement` **删除**（搬到 `CommodityFreightBase` 并 public static），不再保留旧名 | 两条同值常量 = 两处拼写；测试零引用（S9）⇒ 删除零连带。 |
| **D7** | `revokedRows` 这个日志读数是我加的（设计书未要求） | 让"到底有没有 GM 设过的值被作废"可判（> 0 ⇒ INFO，= 0 ⇒ DEBUG）。 |

★ **没有偏离**（逐条对齐 §4.1 修正版）：组件名/语义/值域、缺键读取口、唯一算式逐字保留、`baseMilli = 0` 合法且仍被
`max(1,·)` 抬到 1、撤销费率乘数维、费率入参回 5 参、命令载荷字段 `baseMilli`、三条具名拒 + 零 revision、GM-only 不变。

---

## 8. 会改变数值行为的清单（给测试代理当输入）

| # | 何时改变 | 改什么 | 量级（实测） |
|---|---|---|---|
| N-1 | **仅当** GM 设过某商品的基础费（表里有那一行） | 该商品的**单位运费**（`freightUnitMilli`）：短途与长途**都**变（改前 F 批只在长途的费率乘数上变） | `grain=2`：短途(10‰) 2→3、长途(95‰) 2→3；真实成交 `freightMilli` 39→59、88→132、60→90（同 lane 同量） |
| N-2 | 同上 | `tool=1`：工具单位运费 4→2（**与粮同级**，改前硬编码 3 不可改） | `SAME_LEVEL … grainUnit=2 toolUnit=2 equal=true` |
| N-3 | 同上 | `baseMilli=0`：未取整乘积 → 0，单位运费仍 = 1（`max(1,·)`，**本批不改**该语义） | `ZERO … unitMilli=1` |
| N-4 | ★ **旧档里带已撤销的 `commodityFreightPerMille`** | 该维**整体作废** ⇒ 基础费回落现行分档、**费率回到乘数之前** | 真档 `store-old-gm`：所有 lane 52‰ → **35‰**、grain base 1（F 批的 1500 不再生效） |
| N-5 | **永远不变**（表为空 / 键缺失 / 未登记商品） | 所有运费口径 + 全日志面 | two worlds：摘要逐字相同、归一化 diff **0 行**、239/87 笔成交运费逐笔相同 |

**受影响硬编码字面量（值一个没改，只是搬家 + 新增具名缺省）**：
`MARKET_FREIGHT_BASE_PER_UNIT_{GRAIN=1,FIBER=1,CLOTH=2,TOOL=3,DEFAULT=1}_MILLI`（原 `MarketSettlement` 5 个常量）
⇒ `CommodityFreightBase.{GRAIN_MILLI=1,FIBER_MILLI=1,CLOTH_MILLI=2,TOOL_MILLI=3,DEFAULT_MILLI=1}`；
**删除** `TransportTariff.DEFAULT_COMMODITY_FREIGHT_PER_MILLE = 1000`（乘数维的恒等元，随该维一起撤销）。
承运成本标定（`MARKET_FREIGHT_CARRIER_COST_PER_MILLE_PER_TIER_STEP=25`、`MARKET_FREIGHT_DEFAULT_CARRIER_COST_PER_MILLE=25`）**未动**。

---

## 9. 会让既有测试失效的清单（★ 我没改任何测试、没跑测试）

| # | 测试 | 为何失效 |
|---|---|---|
| T-1 | `simos-economy` `change/EconomyRoundTripTest.java:512`、`:570` | 引用 `base.withCommodityFreightPerMille(...)` / `cs.commodityFreightPerMille()` ⇒ **测试源码编译不过**（组件与写口已改名）。★ 组件计数仍是 **36**（`:327` 的 36 不变），`mutate`/`changedOf` 的 case 名需同步改成 `commodityFreightBaseMilli` |
| T-2 | `simos-app` `McpCoverageTest.java:691-692` | 最小合法载荷仍是 `{"commodityId":"grain","perMille":1500}` ⇒ 现在走**载荷形状拒**（`economy.SetCommodityFreight 的字段 baseMilli 必须是整数: null`）⇒ 该轮的 `result == committed` 断言会红。改字段名 + 换一个非现值（现值 grain=1 ⇒ 用 2）即恢复 |

**预期仍绿（本批刻意保住）**：`TransportTariffP4Test`（5 参入口形状未动）、`MarketTopologySingleRegionTest`（2 参入口未动）、
`SimosToolsTest`（命令/工具**名**与计数未变）、`CatalogVisibilityTest`、`EconomyCodecTest`（新组件名的快照/变更集自洽往返）、
`EconomyRoundTripTest` 里"组件数 = 36"那条断言本身。
★ 仍要为**新判据**补用例（下一批测试代理）：基础费 ⇒ 单位运费的**未取整乘积恰好 ×2**、`0` 合法且仍收 1 毫、
三条具名拒 + 零 revision、旧档（缺键 / 带旧名 / 两键并存 fail-closed）、往返 + 第四台 mapper 重放、推进后表仍在。

---

## 10. 未完成 / 未验证 / BLOCKED

| # | 项 | 状态 |
|---|---|---|
| U1 | **编译**（本批唯一获授权的 Maven）：`tools/mvn-lock.sh -q -o -DskipTests compile -pl simos-app -am` | ✅ **exit=0**（跑前 `ps` 确认无 `classworlds`/`surefire`） |
| U2 | `test` / `verify` / `package` | ❌ **未跑**（派单书禁止）。T-1/T-2 的既有用例**必然红/编译失败**，留给测试代理 |
| U3 | Spotless / Checkstyle / SpotBugs / 前端门禁 | ❌ 未跑（未获授权跑门禁）。已手工控行宽 ≤ 100（`MarketTopology:699` 的 >100 行是既有 `<pre>` 块，google-java-format 不折行）；**Spotless 是否逐字满意未验证** |
| U4 | T1' 的**字面**读法"单位运费恰好翻倍" | ⚠️ **不成立**（真实 lane 上 2→3）：成立的是"**未取整乘积**恰好 ×2"（1332/1332 + 342/342）与"无取整时单位运费恰好 ×2"（生产函数合成序列 2/4/6/8/16）。**已如实记，不粉饰**（与 F 批 D2 同族的 `ceil` 粒度） |
| U5 | 旧档第二路径（第四台 mapper 的 journal 行）摘除的**日志** | ⚠️ 静默（类型级注解无法落日志）；结果正确（Unchanged ⇒ 回落分档）。已记入 §4 |
| U6 | `v17levant` 大世界 | ❌ 未跑（时间/体量）。逐值不变由 three-powers + small-world + 旧档读回三条链路证 |
| U7 | 决策人的**间接**路径（`sd.IssueDirective` 载荷里嵌该命令） | ❌ 未端到端打（同 F 批）：由 `GmOnlyCommand` 标记 + 组合根 `directiveCommandTypes` 过滤保证；探针只证到"GM 桶有 / 决策人桶无" |
| U8 | **任务板 claim/complete** | ⚠️ `task-24` 返回"不是 active Agent Team 成员" ⇒ **未能 claim/complete**（按任务书继续执行，如实报） |
| U9 | 无 **BLOCKED** | — |

---

## 11. 改动文件（17 改 + 1 新；全在允许面内）

**simos-economy（12）**：`EconomyData.java`、`EconomyLogSource.java`、`model/CommodityFreightBase.java`（**新**）、
`model/TransportTariff.java`（回 `f3366545^` 形状 + 一句"商品维不在这里"）、`change/EconomyChangeSet.java`、
`codec/EconomyCodec.java`、`spi/EconomySetCommodityFreightHandler.java`、`spi/EconomySeedHandler.java`、
`spi/EconomyClearRegionHandler.java`、`time/EconomyStateBuilder.java`、`time/MarketSettlement.java`、
`time/MarketTopology.java`、`time/ExpectedProfitBook.java`
**simos-app（5）**：`time/MarketTopologyBook.java`、`tools/read/CatalogTool.java`、`tools/SimosToolSource.java`、
`tools/write/EconomySetCommodityFreightTool.java`、`Shell.java`（**只改注册点上方那段注释**：命令类型名/注册位置/工具面一概未动
⇒ handler 137 / GM 工具 / 窄写计数**零变化**，权限单调性不受影响）

**未碰**：任何 `src/test/**`、任何 `pom.xml`、`docs/**`、`.superpowers/**`（除本目录）、`simos-util/**`（`EconomyVocabulary` 未改）、
需求侧（D 批）、口岸/关税、`.superpowers/sdd/2026-10-09-commodity-freight/`（F 批账本，留痕不改）。

**探针（不进仓库）**：`/tmp/cfbprobe/{src-common,src-new,src-old}/…`、日志 `/tmp/cfbprobe/logs/`、
参照 worktree `/tmp/cfbprobe/oldwt`（`f3366545`，可 `git worktree remove /tmp/cfbprobe/oldwt`）。
