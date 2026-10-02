# 年份系统设计稿：儒略历 + `simos-calendar` + 季节系统（2026-10-02）

> **状态**：设计稿。用户裁定见 `2026-10-02-architecture-design-source.md` **D-018 + D-018 补裁**；实施计划见
> `../plans/2026-10-02-calendar-and-seasons-plan.md`。§九 是已裁定的**默认历法/气候计算算法**与实现选择；
> 它是缺省行为的唯一口径，实施时不得静默改默认值。
>
> **一句话**：`1 tick = 1 天` 不变；新增纯计算模块 `simos-calendar`，把 tick 经 **JDN** 映射到**儒略历**年月日，
> 并给出 24 节气/天文季界 + 南北/热带坐标分带的季节，另把既有 365 类“年”常量改为历法派生。

---

## 一、目标 / 非目标

**目标**

1. `simos-calendar` 能回答“第 N 天在这一种历法里的**年/月/日**”（本轮实现儒略历；接口按多历法设计）。
2. tick → 日期有**唯一锚点**：默认 tick 0 = 儒略历 `1445-01-01`；每世界可配、GM 工具可改。
3. 季节系统同时提供 **24 节气**与**天文（二分二至）**两套季界，并按地图南北坐标分
   **北半球 / 赤道附近 / 南半球** 三带。
4. `CLOTH_CYCLE_DAYS`、`AgeBracket` 等**真正表“年”**的常量改为历法派生（数值行为会变，须全量回归）。
5. 日期/季节是 **tick 的纯函数派生量**：不落 revision、不加状态组件、不写 `calendarLabel`。
6. **GUI（D-020）**：时间线同时显示 tick + 具体日期；点击 hex 显示该格季节（工作台与旧页同步）。

**非目标**

- 不做格里高利历、1582 儒略/格里高利双历切换（用户选“儒略历全期”）。
- 不做中国阴阳历/闰月、年号纪年、伊斯兰历等（接口留 `CalendarSystem` 扩展位）。
- 不做逐格气候带/纬度/温度模型（地图 `HexCell` 只有 height；本批只用 `r` 作南北坐标代理）。
- 不改 `120` 天经济周期与计划里的 `360` 天“经济年”（D-018 补裁：业务/关账节律保持不动）。
- 本批不把季节接入农业结算（只提供 `SeasonState` 钩子；R2/R4 农忙农闲后续再接）。

---

## 二、时间轴与 tick → 日期

| 概念 | 定义 |
|---|---|
| tick | 既有第一序；**1 tick = 1 天**（2026-09-24 日制裁定） |
| dayNumber | **JDN（儒略日号）整数**；历法无关的连续日轴 |
| epochJdn | tick 0 的 JDN；世界锚点 |
| 映射 | `dayNumberOf(tick) = epochJdn + tick`；`tickOf(dayNumber) = dayNumber − epochJdn` |

- **默认锚点**：儒略 `1445-01-01` = **JDN 2248845**。故当前世界 `tick 120` = **儒略 1445-05-01**。
- 已实测对拍锚点（整数公式，见 §三）：
  - 儒略 `1445-01-01` = JDN 2248845；`+120` 天 = `1445-05-01`；
  - 儒略 `2000-01-01` = JDN 2451558；格里高利 `2000-01-01` = JDN 2451545；
  - 儒略 `1582-10-04` = JDN 2299160、`1582-10-15` = JDN 2299171（儒略历无 10 天跳变，两日相邻）。
- JDN 的**整数**表示“该民用日”；儒略/格里高利正反算公式都按这个约定（与
  `java.time.temporal.JulianFields.JULIAN_DAY` 同轴，测试可直接对拍）。
- `CalendarDate(long year, int month, int day)`：月/日 **1 起**；年内用**天文年编号**（可 ≤ 0），
  显示层再出“公元前/公元”。世界实际只用 1444+，但换算本身不设“无 0 年”阻断。
- 锚点存在的意义：tick 只是“第几天”，不是日期；换世界/换历法时只换 `epochJdn` + `CalendarSystem`，映射不变。

---

## 三、儒略历规则与算法

- 闰年：`year % 4 == 0`（**无世纪例外**：1500、1700、1900 均闰）；平年 365、闰年 366。
- 月长：31/28(29)/31/30/31/30/31/31/30/31/30/31；闰日 = 2 月 29 日；day-of-year 1..365/366。
- 正算（`CalendarDate → JDN`，儒略版整数公式）：
  `a=(14−m)/12; y=year+4800−a; m'=m+12a−3; JDN = d + (153m'+2)/5 + 365y + y/4 − 32083`（整除）。
- 反算（`JDN → CalendarDate`）：`c=J+32082; d=(4c+3)/1461; e=c−1461d/4; m=(5e+2)/153;
  day=e−(153m+2)/5+1; month=m+3−12(m/10); year=d−4800+m/10`。
- 上述正反算**已用 Python 实测**（1445/2000/1582 锚点全部一致）；实现时不得换用 `LocalDate`
  （它是先行格里高利，二者 1445 年差 9 天）。
- 模块内另需：`isLeapYear`、`daysInMonth`、`daysInYear`、`dayOfYear`、`daysBetween`（同日历两日差天数）。

---

## 四、`simos-calendar` 模块形状

- **落点**：新 Maven 模块 `simos-calendar`；包 `io.mosire.simos.calendar`。
- **依赖（D-019 用户裁定）**：`simos-util` + `simos-map`。util 供共享 `YearFraction` 值类型；map 供 `HexCoord`
  （季节分带用 `r`）。依赖方向 `util → map → calendar`；`util`/`map` 的 enforcer 禁列表补 `simos-calendar` 防反向依赖。
  不得依赖 core/app/social/unit/economy*/actor*/gov/army/sd/agentlib。
  enforcer 按 `simos-map` 同款写“禁一切领域/编排模块”；父 pom 增 `<module>`；AGENTS §〇 模块表增一行。
- **无状态**：无 Snapshot / Codec / ChangeSet / handler / 存储；不 import Jackson、不碰文件系统。
- 核心类型（形状，非最终代码）：

```text
record CalendarDate(long year, int month, int day)          // 月/日 1 起
interface CalendarSystem {
  String id();                                              // "julian"
  CalendarDate dateOf(long dayNumber);                      // ← 用户要求的“某天的年月日”
  long dayNumberOf(CalendarDate date);
  boolean isLeapYear(long year);
  int daysInMonth(long year, int month);
  int daysInYear(long year);
  int dayOfYear(CalendarDate date);
}
JulianCalendar implements CalendarSystem                    // 单例 INSTANCE；正反算见 §三

final class CalendarClock {                                 // 世界绑定：历法 + 锚点
  CalendarSystem system(); long epochDayNumber(); CalendarDate epochDate();
  CalendarDate dateOfTick(long tick);
  long dayNumberOfTick(long tick);
  long tickOfDayNumber(long dayNumber);
  int daysInYearAtTick(long tick);
  YearFraction yearFraction(long fromTick, long toTick);    // 精确有理数，见 §六
}
final class CalendarAge {                                   // 年龄 = 整历法年数
  int ageInYears(CalendarSystem system, long birthDayNumber, long currentDayNumber);
}
```

- `YearFraction`（`record(long numerator, long denominator)`，`ONE`、`ZERO`）放 **simos-util**
  （`io.mosire.simos.util.time`）：calendar 与 `EconomyVocabulary` 共用同一值类型，避免两个“年分数”形状。
- 月份/节气名等**中文展示串**放 calendar 的枚举/named 常量，不散落在 GUI。

---

## 五、季节系统

### 5.1 太阳黄经（两套季界共同的基础）

- `SolarLongitude.longitude(long dayNumber)`：Meeus 低精度公式（输入 JDN，输出 [0,360) 度）：
  `n=JDN−2451545.0; L=280.460+0.9856474n; g=357.528+0.9856003n; λ=L+1.915 sin g+0.020 sin 2g`。
- **已实测**：J2000 λ≈280.38°；2024 立春（λ=315°）落在 **2024-02-04 ~08:24 UTC**（与真实立春一致到小时级）；
  精度足够日粒度季界。默认锚点 1445 年（儒略历）实测：`1445-01-01` λ≈290.28°（冬）；`tick 120 = 1445-05-01`
  λ≈49.35°；该年节气：立春 **01-25**、春分 **03-11**、立夏 **04-26**、夏至 **06-12**（儒略历日期，
  因 1445 年儒略历比回归年落后约 9 天，春分在 3/11 是史实正确的）。
- 日粒度约定：`dayNumber` 取整数（民用日），节气边界日 = 黄经跨越当天的那个民用日；边界日用
  二分（黄经在该日前后单调递增，已验证）求到 ±0 天。

### 5.2 24 节气与两套季界

- `SolarTerm` 枚举 24 项，每项带：`longitude()`（15° 的倍数）与 `key()`/中文名；
  从 λ=0° 起序：春分/清明/谷雨/立夏/小满/芒种/夏至/小暑/大暑/立秋/处暑/白露/秋分/寒露/霜降/
  立冬/小雪/大雪/冬至/小寒/大寒/立春/雨水/惊蛰。
- `SeasonBoundary { SOLAR_TERM, ASTRONOMICAL }`；**缺省 = `SOLAR_TERM`**（D-018 补裁），`ASTRONOMICAL` 可切换：
  - `SOLAR_TERM`：北半球春=立春(315°)→立夏(45°)、夏=立夏→立秋(135°)、秋=立秋→立冬(225°)、
    冬=立冬→立春(315°)；南半球整体移相 180°。
  - `ASTRONOMICAL`：北半球春=春分(0°)→夏至(90°)、夏=夏至→秋分(180°)、秋=秋分→冬至(270°)、
    冬=冬至→春分(0°)；南半球移相 180°。
- `SolarTerms.of(long dayNumber)` 返回当天所在节气；另给“某节气在给定日附近的精确边界日”查询。

### 5.3 坐标分带（南北 / 赤道附近）

- 南北轴 = 六角坐标 **`r`**（`hexToPixel.y = 1.5r`）：**负 r = 北**，`r=0` 为赤道；世界 `r∈[-140,140]`。
- `LatitudeBands(long northMax, long southMin, boolean northIsNegative)`：
  - **不预设数值**（D-018 补裁：只给接口、GM 配置）；`zoneOf(r)`：`r ≤ northMax` ⇒ `NORTH_TEMPERATE`；
    `r ≥ southMin` ⇒ `SOUTH_TEMPERATE`；其余 `TROPICS`；`northIsNegative=false` 时整体取反。
  - **未配置的降级**：明确按“全球北半球四季”处理，并在 `info`/读口标注 `zoneSource: fallback`（不假装已分带）。
- 带宽由 **GM 工具**配置（用户要求“同时设定对应坐标界限”）；season API 直接收 **`HexCoord`**（D-019），
  由模块读 `.r()` 判带；`LatitudeBands.zoneOf(long northSouthCoordinate)` 仍是纯函数层（便于测试/非六角调用）。
  本批**不读** `GameMap` 地形/高度（后续地形气候再接，依赖口已就位）。

### 5.4 季节返回值

```text
sealed interface ClimatePhase permits TemperateSeason, TropicalSeason
enum TemperateSeason { SPRING, SUMMER, AUTUMN, WINTER }
enum TropicalSeason  { RAINY, DRY }
record SeasonState(ClimatePhase phase, int dayOfSeason, int daysInSeason, double progress)  // progress 0..1
interface SeasonSystem { SeasonState seasonOf(long dayNumber, HexCoord at); }
final class ZonedSeasonSystem implements SeasonSystem                                      // bands + boundary + 热带模型
```

- `progress` 是给农业/经济连续曲线的钩子（“农忙/农闲”不需要另外发明季节枚举）。
- **热带模型（已裁定）**：`TropicalModel { RAINY_DRY, TEMPERATE_LIKE }`，缺省 `RAINY_DRY`；
  雨季窗口用太阳黄经区间配置，**缺省 `[45°,165°)`**（立夏→白露，约 120 天；南热带取反相，可配）。
- 没有地图纬度/气候带数据 ⇒ 本批只做“按 `r` 分三带”的世界级近似；逐格气候是后续。
- **季节系统只在“分带已配置”时输出热带/南半球相位**；未配置走 §5.3 的全球北半球四季降级。

---

## 六、历法派生常量（数值行为会变）

**口径**：历法年 = 该日所在**儒略历年**的天数（365/366）；“每人每年 1 匹布”按**真实历法年**折算，
不再用固定 365。

1. **衣着年长**
   - `EconomyVocabulary`：删除固定 `CLOTH_CYCLE_DAYS=365` 的除法口径；保留
     `CLOTH_MILLI_PER_PERSON = 1000`（毫布/人·**历法年**）。
   - 累计需求 = `population × 1000 × YearFraction(fromTick, toTick)`；`YearFraction` 由 `CalendarClock`
     按区间跨过的历年逐段精确求和（整年 = 1；闰年窗口用 366 分母）。
   - 调用点：`GovDaily`（gov，按当前 tick 的 `daysInYearAtTick` 折算每日定额）、`CrisisMonitor`（app，按
     本周期至今的绝对 tick 区间算分数）；`EconomyVocabulary` 里的旧 `cumulativeClothMilli(pop, days)`/
     `dailyClothNeedMilli` 改签名或移除，**不许留第二套除法**。
2. **年龄档**
   - `AgeBracket`：上界从 `{15×365, 60×365} 天` 改为 `{15, 60} 整历法年`；`ofYears(long)` +
     `boundedMaxExclusiveYears()`。
   - 年龄换算走 `CalendarAge.ageInYears(system, birthDayNumber, currentDayNumber)`：按生日是否已过做整年差；
     **2/29 出生的生日惯例**按“平年 2/28 未过、3/1 已过”处理（即平年 3/1 长一岁），写进类注与测试。
   - 调用点：`SocialData.aggregate`（social）、`PopulationDynamics`（social 死亡/生育档）、`CrisisMonitor`
     （app）、`RegionAllocations`（app）、`EconomySeeder`（app 创世折算，改为按历法年采样年龄）。
   - `simos-social` 新增对 `simos-calendar` 的直接依赖（并更新 AGENTS 模块表）；`simos-util` 保持零 simos 依赖。
3. **不动的部分（已裁定）**
   - `RATION_CYCLE_DAYS = 120`、`EconomySeeder.CYCLE_DAYS = 120` 与计划里的 360 天“一年”= 3 个经济周期：
     保持原值，只作为**业务/关账节律**，在文档/注释里与“历法年”分开命名；改了会推翻
     `h6raw tick120/240/360` 全部历史基线与 360=120×3 判据。

---

## 七、配置与 GM 工具

- **配置形状**（`store_meta` 键 `calendar`，JSON；core 只当不透明字符串存取）：

```json
{
  "version": 1,
  "calendar": "julian",
  "epoch": "1445-01-01",
  "seasonBoundary": "SOLAR_TERM",
  "tropicalModel": "RAINY_DRY",
  "tropicalRainyStartLongitude": 45.0,
  "tropicalRainyEndLongitude": 165.0,
  "northIsNegative": true,
  "northMax": null,
  "southMin": null
}
```

- `northMax`/`southMin` **缺省为 null = 未配置**（D-018 补裁 A：只给接口、GM 配置；降级见 §5.3，`info` 报 `zoneSource`）。
- **默认算法必须记录**（D-018 补裁 A）：代码里设唯一缺省常量（暂名 `CalendarDefaults`），等于本节 JSON 的非 null 项；
  本设计稿 + D-018 是它的文字口径；`simos.calendar.info` 报 `calendarSource/seasonSource/zoneSource`，
  缺省来源与已存 `store_meta` 配置必须可辨（`default` vs `store`），不得静默换算法。

- **core**：`SqliteStore` 加一个**通用键值存取** API（读/写 `store_meta` 键），core 不认识历法语义；
  与 `time_base` 同属库级元数据。不落 revision（改锚点不改变世界状态；若落 revision，重放时反要问“当时配置
  是什么”，反而制造第二真相）。
- **app `CalendarService`**：启动时读 `store_meta.calendar`（缺省用默认配置，**不静默写盘**）；
  持有不可变 `CalendarClock` + `SeasonSystem` 快照；`apply(config)` 先校验、再持久化、后原子换快照；
  持久化失败不换内存。`ClassFirstPopulationEconomyTimeParticipant`、`CrisisMonitor`、工具/读口从这里取。
- **工具**：
  - `simos.calendar.info`（读，四桶共享）：当前配置 + 今天日期/节气/季节（可带 `tick`/`q`/`r` 参数查询）。
  - `simos.calendar.configure`（GM 写）：部分合并字段；`dryRun=true` 时只回“新配置下的今天日期/季节”与
    `warnings`（如“锚点变更会让所有历史显示日期整体平移”），不落盘。写成功记 INFO（旧/新摘要，不落敏感值）。
- **风险**：锚点/分带中途变更会让“已发生的日期显示”整体平移；建议在创世期配置，工具返回警告但不强制阻断。

---

## 八、读口与 GUI（D-020 用户裁定）

**接口字段**（服务端用 `CalendarService` 算好，前端只渲染）：
- `ApiViews.timestamp(at, calendarService)` 与 `ToolSupport` 在 `{tick, calendarLabel}` 基础上加：
  - `date: {calendar:"julian", year, month, day, dayOfYear}`
  - `season: {phase, key, name, dayOfSeason, daysInSeason, progressPerMille, zone, zoneSource}`
  - `solarTerm: {key, name, longitude}`
- `GET /api/state` → `meta.timestamp` 带 `date`；
- `GET /api/timeline` → 每个 node 带 `date`（同 tick 多条 revision 同日；历史节点按其 tick 算）；
- `GET /api/map/hex` → 增 `date` + `season`（用该格 `coord.r()` 判带；未配置分带 ⇒ `zoneSource:"fallback"`）+
  可选 `solarTerm`。旧页 `map.html` 与工作台 `index.html` 共用这一份 `/api/map/hex` 响应。

**GUI（本批必须做）**：
1. **时间线**（工作台 `timeline.js`）：节点在 `tick` 刻度下加一行日期（如 `1445-05-01`）；`title`/`aria-label`
   与 `#timeline-meta` 同带日期。日期来自 `/api/timeline`/`/api/state` 的 `date`，**前端不重算历法**。
2. **hex 详情**：工作台 `panels.js` 的 `renderHex` 与旧页 `map-hostpage.js` 的 `showHex` 各加一行
   `季节`（如「夏（北温带 · 立夏后第 6 天）」；未配置分带时写「夏（未配置分带，按北半球四季）」）。
3. 两个宿主页都要同步；前端门禁下界（`run-gate.cjs` 的 `MIN_TESTS` 与 `gate-contract.test.cjs` 的
   `MIN_ASSERTIONS`）**两处同值、改一处必须改两处**；新增 `.test.cjs` 才需要动 `REQUIRED_FILES`。
- `calendarLabel` 保持自由文本、**不由历法模块回写**（避免第二真相）。
- **不做**：按季节给地图上色/新图层；战斗事件浮层（D-017）不动。

---

## 九、默认历法/气候计算算法（已裁定，缺省行为的唯一口径）

> 用户第二轮裁决（D-018 补裁）：「应当记录这个东西为默认历法/气候计算算法」。以下缺省值写在代码里的
> `CalendarDefaults` 常量与 `simos.calendar.info` 的可辨来源里；**改缺省值必须另开 D 条目，不许静默改**。

| 维度 | 缺省 | 可配 | 备注 |
|---|---|---|---|
| 历法 | 儒略历（全期，4 年一闰无世纪例外） | GM（未来可扩历法） | 用户选“儒略历全期” |
| 锚点 | tick 0 = 儒略 `1445-01-01`（JDN 2248845） | GM 工具 | 每世界可配 |
| 季界 | **24 节气**（立春/立夏/立秋/立冬） | GM 可切天文 | 天文 = 春分/夏至/秋分/冬至 |
| 热带 | **雨季/旱季**，窗口 `[45°,165°)` | GM 可配窗口 | 南热带反相 |
| 分带 | **不预设**（只给接口，GM 配置） | GM 工具 | 未配置 ⇒ 全球北半球四季 + `zoneSource: fallback` |
| 120 天经济周期 / 360 天关账节律 | **保持不动** | 不改 | 只改 365 类“年”常量 |

**实现选择（agent 提议）**：`YearFraction` 放 util；配置落 `store_meta`、不落 revision、GM 工具 + INFO 日志
（用户选“1”）；依赖 = util + map（D-019 用户裁定）。

---

## 十、验收判据（总）

1. `JulianCalendar` 正反算：儒略 `1445-01-01` ↔ JDN 2248845；`tick 120 → 1445-05-01`；400 年/随机抽样往返零差异。
   `tick 120` 的季节示例：节气季界下 = **夏**（λ≈49.35°，刚过该年立夏 04-26）；天文季界下 = **春**（0°<λ<90°）。
2. 闰年：1500/1600/1700/1900/2000 在儒略历全为闰年；1900-02-29 合法；1582-10-04+1 = 1582-10-05。
3. 季节：2024 立春落在 2/4（±0 天）、春分/夏至/秋分/冬至各日对表；**在 GM 配置的两条带宽上**边界精确切换
   （未配置世界的降级 = 全球北半球四季，`zoneSource: fallback`）；节气/天文两族季界可切。
4. 派生常量：同一人口整历法年（365 或 366 天）衣着需求 = `人口×1000`；15/60 岁档按历法年整年切换；
   2/29 生日平年按 3/1 长岁。
5. 配置：`simos.calendar.info` 能辨 `default` vs `store` 来源；GM 工具改锚点后 `/api/state` 的日期整体平移、
   重启后仍在；`dryRun` 不落盘；缺省算法与 §九 表逐项一致。
6. 回归：`120`/`360` 业务节律的既有读数与判据（在明确保留的部分）不变；全部生产代码落地后由测试代理跑
   `./mvnw clean verify`。
7. **GUI（D-020）**：时间线节点显示 `tick` + 具体日期（同一 tick 的多条 revision 同日）；点击 hex 在
   `index.html` 工作台与 `map.html` 旧页**都**显示该格季节；未配置分带时显示 fallback 文案；前端门禁
   两处下界同值更新并全绿。
