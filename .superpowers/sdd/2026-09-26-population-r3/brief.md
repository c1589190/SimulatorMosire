# 设计要点：R3（通用生产 + 多商品 + 城乡作坊）

> **给实现者**：本文只给**判断与判据**，代码你写、测试你跑。
> 判据出处：`docs/superpowers/specs/2026-09-26-population-economy-v3-design.md` 的 **§五（V7 通用生产）**、§一.4（现状硬绑）、§四方（劳动）、§十。
> 前序：R1（`69487db`）、R1.5（`dcffa45`）、R2（`b40a353`）已关账；要点在 `.superpowers/sdd/2026-09-26-population-r{1,15,2}/brief.md`（**先读**）。

## 目标（一句话）

**让产出不再硬绑"亩 + GRAIN"**：生产规模由**最紧约束**决定、"每单位什么"成为**数据**、商品**不止粮**；
并让**城乡各有一个非土地生产**跑起来（农村家庭纺织、城市作坊），在真档里**看得见**。

## 现状（本轮要拆的三处硬绑，`EconomySettlement.harvest:705-722`）

```java
rowLand[i] = row.meansOfProduction().getOrDefault(AssetKind.LAND, 0L);   // :705
long availableMu = totalLandMilliMu / 1000L;                             // :711  三路瓶颈全是"亩"
long ableMu      = avgLaborMilli * LAND_MU_PER_LABOR / 1000L;            // :712
long seedPerMu   = industry.cycleInputPerUnit().getOrDefault(AssetKind.LAND, 0L); // :718
long actualMu    = Math.min(availableMu, Math.min(ableMu, seedCapMu));   // :720
long perMu       = industry.outputPerUnit().getOrDefault(GRAIN, 0L);     // :721  ← 商品硬绑 GRAIN
```

- **三路瓶颈全是"亩"**、产出键**写死 `GRAIN`**、分配权重**只读土地**（`:736`）。
- **`outputPerUnit` 虽是 `Map<CommodityId,Long>`，但"每单位**什么**"是隐式约定（亩）、不是数据**
  ⇒ "每座工坊产 N 匹布"**表达不出来**。这就是**手工业恒产 0**（201 个产业）的根因。
- **两个投入表的键是 `AssetKind`、值是无商品维度的标量** ⇒ 表达不了"消耗 IRON"。

## 范围

### T1 多商品词表

- `EconomyVocabulary`（**唯一拼写点**，已有"粮 id 恰一份"的源扫描护栏）加：`cloth` / `fiber` / `tool` / `iron` / `wood`（`fuel` 按需）。
- ★ **那 10 项要动的清单**（本会话早先调查已列全，逐条核）：
  ① `EconomySettlement.GRAIN` 常量 ② 三个单商品访问器（`grainOf`/`withGoodsGrain`/`withDailyNeed`）
  ③ 需求口径 `dailyRationMilli`（**标量，无商品维度**）④ 消费 ⑤ 借粮+债务商品 ⑥ 收获入账 ⑦ 流水组装
  ⑧ **两个投入表换型**（T3）⑨ 损耗模型（现在是"粮的千分比"）⑩ 读口（`grainStock` 等键写死在 JSON 形状）。
- ★ **需求口径要有商品维度**：至少粮与布两种（spec §七："粮食不足与衣物不足对死亡的时间尺度显然不能一样"）。
  **本轮只把形状做出来**（每商品一条需求），**阈值与死亡作用留 R4**。

### T2 `ProductionRecipe`（"每单位什么"成为数据）

- 形状（把 `harvest` 那三路泛化成"每种 capacity 一路"）：
  ```text
  capacity requirements:  Map<AssetKind, Long>   // 每规模需要多少生产资料（LAND/WORKSHOP/MACHINE…）
  consumed inputs:        Map<CommodityId, Long> // 每规模消耗什么（GRAIN/IRON/WOOD/FIBER…）
  labor:                  long                   // 每规模需要多少劳动（千分劳动）
  outputs:                Map<CommodityId, Long> // 每规模产出什么
  ```
- **规模 = 最紧约束**（`min` over 各 capacity 与 labor，按"每规模需求"归一）——照 spec §五"生产规模统一根据最紧约束决定"。
- ★ **`outputPerUnit` 的"每单位什么"必须成为数据**：建议把 recipe 挂在 `Industry` 上（**注意 spec v2 §4.3 与 §4.7 对"覆盖式/实例式"的口径矛盾已由控制器裁定**：
  判据 = "这个数在一国之内是否逐实例不同" ⇒ **`outputPerUnit` 全实例同值 ⇒ 覆盖式**；但**参数目录不在本轮**（见"不做"），
  故本轮它**仍在实例里**，只把"每亩"从隐式约定变成显式字段）。
- ★ **分配权重**：现在只读土地（`:736`）。本轮至少让"按劳动占比"的那一半对所有产业都有意义（非土地产业没有土地可摊）。

### T3 两个投入表换型

`Industry.dailyInputPerUnit` / `cycleInputPerUnit`：`Map<AssetKind,Long>` ⇒ **`Map<AssetKind, Map<CommodityId,Long>>`**。
★ **它们当前是零读取点的死字段**（`dailyLaborPerUnit` 亦然）⇒ **改它们的运行期风险为零**，只牵 codec/payloads/测试。

### T4 农村家庭纺织（**必须有真档可见性**）

- 新产业种类 `weave`（或挂在 `HOUSEHOLD` actor 上——**用 R2 已扩的 `ActorKind.HOUSEHOLD`**），配方形如
  `FIBER + LABOR + TOOL → CLOTH`。
- ★★ **纤维从哪来**：**农业配方同时产 `grain` 与 `fiber`**（亚麻/秸秆是农田副产物）⇒ **无需新种植流程**，
  且纤维**内生于土地**（不凭空造）。
- ★★ **真档必须真的分出一部分农村劳动给它**（**这是本轮最容易踩的坑，与 V3 同款**）：
  若创世把农村批次 1000‰ 全给农业，**家庭纺织在有配额、没活干的状态下依然是惰性的、报表里看不见**。
  ⇒ **创世要给农村批次一个非零的纺织配额**（默认值你定，**建议 100‰ 量级**），并在常量注释里写"R4 的季节性会调它"。
  **判据：真档推一年后，`CLOTH` 的库存 > 0 且农村批次的纺织配额非零。**
- ★ **"男耕女织"**：纺织配额的**性别默认权重**要偏向女性（spec §四："性别与年龄影响各类劳动活动的默认配置权重"）。
  本轮用 R1 的表（默认可按性别覆盖）；**不要**写成"女 = 纺织"的硬编码。

### T5 城市作坊

- `WORKSHOP` actor（R2 已扩 `ActorKind.WORKSHOP`），配方如
  `FIBER + LABOR + WORKSHOP → CLOTH` 与 `IRON + LABOR + WORKSHOP → TOOL`。
- ★ **目的只有两个**（spec §六）：**证明非 LAND 生产成立** + **证明城市能产出自己的产品**。
  **城乡交换（布换粮）不在本轮**（那要 R4 的 V8 统一转移）。
- 城市作坊的原料（`FIBER`/`IRON`）从哪来：**创世给城市作坊行种一份初始库存**（场景参数，明标"估计来源"），
  或让城市的 `FIBER` 从同格农业行取得（**不建议本轮做跨行实物转移**——那正是 V8 的活）。

### T6 读口

- 商品不再只有粮：读口要把**新商品**发出来（`goods` 已是 `Map<CommodityId,Long>`，看现有形状够不够；
  不够就加，但**不许**在路由层另拼一份视图）。

## 不做（留白，不是遗漏）

1. **参数目录（V7 的另一半）**：技术参数**仍住在 `Industry` 实例**里，本轮**不建目录**（`ParameterCatalog` 的落地形态 spec **未给**，
   且 spec 裁定的顺序是"先让经济跑对，目录随后"）。**不要造半套目录。**
2. **城乡交换 / 市场 / 价格**（R4 的 V8）。
3. **出生/死亡/生理压力/`CrisisMonitor`**（R4）。
4. **季节性**（农忙/农闲调纺织配额）：本轮只把配额调成非零、且**结构上允许按天变**；真正的季节曲线归 R4。
5. **`simos-ledger`**（一行不动）；**`EconomicActor` 的完整类型**（只有身份）。

## 铁律

- **不许放宽断言**；合法改法只有"按新口径重算期望值"（算式写进注释）与"修夹具"。
- ★★ **真档数字这次会变**（新商品出现、部分劳动转向纺织）⇒ **必须手算重算端到端字面量**，**不许抄实际值**。
  对不上时**先核算式、先查被测物**，并向控制器报告差异。
- ★★ **夹具必须刻意混合**：本轮做"多商品 + 多 capacity + 非土地产出"，夹具里**必须真的有**
  一个非土地产业（工坊）与一个多商品产出（田里同时出粮与纤维）——否则"只看土地 / 只看粮"的实现照样绿（**假绿**）。
  R1、R1.5、R2 的实现者**各在这里栽过一次**，三次都是自查出来的。
- **判别力声明必须真的成立**：写完"由 X 承担判别力"，X 必须**存在**且**真的会红**；**逐条自检并把表报给我**。
- **每条护栏配变异自证**：改坏判据 ⇒ 必须**红** ⇒ 用 **Edit 反向重写**还原（复原后比 md5）⇒ 复绿。
- ★ **机械改动逐个 Edit，不要用批量脚本**（R1 的实现者用批量脚本误伤 8 个测试文件）。
- ★ **测试一律一次一个类**（R2 的实现者踩到过一次**多类 `-Dtest` 过滤的假绿**，根因未查明）；
  **最终判据一律是整轮 `clean verify`**。
- ★ **验证工具本身**：报"零命中/全绿"前先证明读取模式对。**禁止 `2>/dev/null` 吞工具错误**。
- **一次只能跑一个 Maven**；判过不过看 `target/surefire-reports/*.txt` 的 **mtime 落在本轮**，不看 rc。
- 改完跑 `./mvnw -q spotless:apply`；若它动了你没改过的文件，**停下报告**。
- **收尾前台跑 `./mvnw clean verify`**，把 **Reactor 逐模块结果**抄进报告。
- **不要 `git commit`**。
