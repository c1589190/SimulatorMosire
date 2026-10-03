# 19 格中心城十年探针：辐射商路、道路、迁移与城市扩建（2026-10-05）

> 对应设计：`docs/superpowers/specs/2026-10-05-city-merchant-urbanization.md`
> 探针：`simos-economy/src/test/java/io/mosire/simos/economy/market/SevenHexCityMerchantProbeTest.java`
> 运行：`./mvnw -q -Dtest=SevenHexCityMerchantProbeTest -Dsurefire.failIfNoSpecifiedTests=false test -pl simos-economy -am`

## 1. 地图与机制

### 1.1 19 格：中心城 + 内环 6 + 外环 12

```text
              O4
         O3   R2   O2
    O5   R3   C    R0   O0
         R4   R1   O1
         O6   O7   O8 ...
```

- C：中心城市；
- R0–R5：内环 6 格（R0 主农场，R1–R5 小农）；
- O0–O11：外环 12 格（O0 卖粮、O1 买粮，其余为辐射距离测试格）。

### 1.2 商人活动范围 = 辐射 + 道路

```text
transportPerMille =
    基础距离费
  + 离最近城市距离 × 20‰          # 辐射：离城越远越贵
  − 道路折扣（沿已有道路的瓶颈等级 × 50‰）
  + 农村商人累积成本（+2‰/轮，封顶 100‰）
  − 城市商人折扣（按离 home city 的距离衰减）
```

- 城市商人总池：`homeHex=C`、`serviceRadiusHex=4`、lane 通配；
- 服务半径内所有 lane 共享运力，不再一条 lane 一队；
- 道路按 **路径** 生效：`C→R0→O0→O1→O2` 一条道路建好后，
  `C→O2` 的运输费也按整条路的瓶颈等级打折，不是只看直接边；
- 商人盈利 ⇒ 运力 +5/轮；亏损 ⇒ 缩编。

### 1.3 迁移、城区占地、landPerUnit

- 内环小农在作坊劳动不足时迁入 `W-city`，每户至少留 15 人；
- 人口与债务随人走；
- 城市占地辐射到半径内各格：`cityBuiltAreaMu(hex)` 按离城距离分配；
- `availableArableMu = arableMu − cityBuiltAreaMu`；
- `Tenancy/WageFarm.landPerUnit` 把可耕地下降直接转成农业规模上限。

## 2. 3650 tick 终局读数

```text
[7HEX2] pop=1455 deaths=0
        R0->C=0 -> 67   (ruralPenalty=100)
        R1->C=0 -> 0
        cityPoolMoved=1965 cityPoolProfit=-400 cityPoolCapacity=20780

[7HEX2] city capacity=370 used=7549
        expansions=18 builtAreaPerMille=360
        arableC=2999 arableR0=3019
        cumulativeTrade=7,534,716

[7HEX2] radial:
        R1->C=0  R1->R2=14  R1->R2 afterRoad(level1)=0
        O0->O1 (road)=0
        O2->O11 (off-road)=70
        C->O2 (road)=0
        C->O11 (off-road)=20
        outerBuyerDebt=11,341,300

[7HEX2] migratedToCity=175 cityLaborer=175
        workshopOutput=650
        farmOutput=1370（landPerUnit=22 压住）
        rent=410
```

读法：

1. **辐射奏效**：近城 `R1→C=0`；外环无路 `O2→O11=70`、`C→O11=20`；
2. **道路奏效**：`R1→R2` 修一条一级路 → 14 立刻变 0；
   `C→O2` 沿道路走廊走 → 0，而同样距离的无路 `C→O11` 还要 20；
3. **城市商人扩散到外环**：总池 radius4 覆盖外环，实际承运 1965，十年里总体扩编到 20,780；
4. **外环贸易真实发生**：O0 卖粮、O1 买粮，买家欠款 1,134 万（不断滚动）；
5. **城市扩建 18 次**：承载 10→370、城区比例 0→360‰；
6. **城区辐射占地**：R0 可耕地 3100→3019；`landPerUnit=22` 把农场产出从 1420 压到 1370；
7. **迁移发生**：175 个内环小农迁入城市，作坊用工补到 650 布；
8. 人口 1455、十年零死亡。

## 3. 测试状态

- `SevenHexCityMerchantProbeTest`：1 个测试全绿；
- market 探针：**26 个测试全绿**；
- `simos-economy` 模块：**172 个测试全绿**。

## 4. 剩余缺口

1. 商人还是承运人，不是买低卖高的交易者；`MarketRegion`/`TradeRoute`/`ShipmentBatch` 还没接；
2. 迁移还是探针版 `migratePopulation`，未拆 `PopulationGroup` 跨 social/economy 回写；
3. 道路现在是探针 `roadLevels` 接口，未绑 `GameMap.pathways`；
4. 外环 12 格里只有 O0/O1 有贸易，其余仅用于距离测试；
5. 城市财政/商税还未接 GOV 账，也未逐国检查 `Government.issuable` 发行权。
