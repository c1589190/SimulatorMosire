# 年份系统实施计划：`simos-calendar` + 儒略历 + 季节（2026-10-02）

> **设计稿**：`../specs/2026-10-02-calendar-and-seasons-design.md`；**用户裁定**：`../specs/2026-10-02-architecture-design-source.md` D-018。
> **执行纪律（覆盖 Harness Skill）**：按 AGENTS §一.5 / §三.0 —— **一个阶段一个写代码代理**，只写到「编译过」
> （`tools/mvn-lock.sh -q spotless:apply` + `tools/mvn-lock.sh -DskipTests compile`），**不写/不改测试、
> 不跑 test/verify、不 commit**；**测试全部留到 C7** 由单独的测试代理统一做；控制器派单、审、验收、提交。
> **开工前**：设计稿 §九 的默认算法已由 D-018 补裁冻结；实现时**不得静默改缺省值**（要改另开 D 条目）。

---

## 阶段总览

| 阶段 | 内容 | 代码代理 | 门禁 |
|---|---|---|---|
| **C0** | 设计冻结：D-018 + 补裁已记；默认算法表（设计稿 §九）已冻结；本计划定稿 | 控制器 | 文档 |
| **C1** | 模块骨架：`simos-calendar` pom / 父 pom / enforcer / AGENTS 模块表 | 1 个写码代理 | 只编译 |
| **C2** | 历法核心：`CalendarDate` / `CalendarSystem` / `JulianCalendar` / `CalendarClock` / `CalendarAge` / `YearFraction`（util） | 1 个写码代理 | 只编译 |
| **C3** | 季节：`SolarLongitude` / `SolarTerm` / `SeasonBoundary` / `LatitudeBands` / `ClimatePhase` / `ZonedSeasonSystem` | 1 个写码代理 | 只编译 |
| **C4** | 历法派生常量：`EconomyVocabulary` 衣着口径、`AgeBracket` 历法年、及全部调用点 | 1 个写码代理（**数值行为变化，报告必须逐条点名**） | 只编译 |
| **C5** | 配置与工具：core `store_meta` 键值 API、app `CalendarService`、GM `simos.calendar.configure` / 读工具 `info` | 1 个写码代理 | 只编译 |
| **C6** | 读口 + GUI（D-020）：`ApiViews`/`ToolSupport` 加 `date`/`season`/`solarTerm`；时间线显示 tick+日期；hex 详情显示季节（两宿主页） | 1 个写码代理 | 只编译 |
| **C7** | **测试代理**：按本计划 §测试判据逐条落实 + 关键项变异自证 + 全仓 `clean verify` | 1 个测试代理 | 全量门禁 |
| **C8** | 关账：AGENTS/文档更新、`git status` 清点、按批次提交（中文信息 + 未验边界） | 控制器 | —— |

> C2~C6 的边界是「可独立编译的层」，不是按文件拆单；同一代理在一个上下文里写完该层，避免跨层耦合漏改。

---

## 各阶段任务书要点

### C1 模块骨架
- 父 `pom.xml` 增 `<module>simos-calendar</module>`（置于 `simos-util` 之前或之后均可，Maven 按依赖排序）。
- 新 `simos-calendar/pom.xml`：parent `simos-parent`；依赖 **`simos-util` + `simos-map`**（D-019 用户裁定）、
  junit/assertj/spotbugs-annotations（测试）；
  enforcer `bannedDependencies` 禁 social/unit/sd/core/app/economy*/actor*/gov/army/agentlib。
- `simos-util`/`simos-map` 的 enforcer 禁列表**补 `simos-calendar`**（防反向依赖）；`util` 保持零 simos 依赖，
  `map` 仍只依赖 util。
- 更新 `AGENTS.md` §〇 模块表：新增 `simos-calendar` 行（允许依赖 **util + map**；禁一切领域/编排模块；无状态/无存储）。★ 由**控制器在 C1 审查后同步**；C1 写码代理不动 `AGENTS.md`。
- 报告点名：新增文件清单、pom 依赖图变化。

### C2 历法核心（`simos-calendar` + util 的 `YearFraction`）
- `CalendarDate`（year/month/day，1 起，构造期校验）、`CalendarSystem` 接口、`JulianCalendar`（§三公式）、
  `CalendarClock`（锚点 = JDN + tick）、`CalendarAge`。
- `simos-util` 新增 `io.mosire.simos.util.time.YearFraction`（numerator/denominator，`ONE`/`ZERO`，不可变、约简可选）。
- 明确定义：`dayNumber` = JDN 整数；`CalendarDate.year` 用天文年编号；正反算不调用 `java.time.LocalDate`。
- 报告点名：所有硬编码锚点字面量（默认 1445-01-01 / JDN 2248845）、可供测试代理使用的常量名。

### C3 季节
- `SolarLongitude`（低精度黄经，输入 JDN）、`SolarTerm`（24 项 + 中文名 + `longitude()`）、
  `SeasonBoundary`、`TemperateSeason`/`TropicalSeason`/`ClimatePhase`/`SeasonState`、`LatitudeBands`、
  `ZonedSeasonSystem`；season API 收 **`HexCoord`**（D-019；读 `.r()` 判带），本批不读 `GameMap` 地形。
- 边界日求解：在目标日 ±2 天内二分/牛顿求黄经跨越日；实现必须保证同一天多次调用结果稳定。
- 缺省已裁定（D-018 补裁）：季界 `SOLAR_TERM`、热带 `RAINY_DRY` 窗口 `[45°,165°)`、分带**不预设**（未配置 ⇒
  全球北半球四季 + `zoneSource: fallback`）。实现只准按设计稿 §九，**不得自选另一套**。
- 报告点名：缺省算法常量（`CalendarDefaults`）、季节边界字面量（黄经 315/45/135/225、0/90/180/270、雨季 45/165）。

### C4 历法派生常量（**数值行为变化层**）
- `EconomyVocabulary`：删固定 365 除法口径；`CLOTH_MILLI_PER_PERSON=1000` 语义改为「毫布/人·历法年」；
  累计/日需求改收 `YearFraction`（或区间 + `CalendarClock`），**不留第二套除法**。
- `AgeBracket`：改整历法年（`ofYears` / `boundedMaxExclusiveYears`），2/29 惯例写进类注。
- 调用点逐一改：`GovDaily`（gov）、`CrisisMonitor`、`RegionAllocations`、`EconomySeeder`（app）、
  `SocialData`、`PopulationDynamics`（social）；`simos-social` pom 增 `simos-calendar`。
- **不许动**：`RATION_CYCLE_DAYS=120`、`CYCLE_DAYS=120`、计划里 360 天关账节律（D-018 补裁：业务/关账节律保持不动）。
- 报告必须逐条点名：改了哪些数值行为、受影响的硬编码字面量清单（给 C7 测试代理当输入）、
  哪些测试预期会红（但**不跑测试**）。

### C5 配置与工具
- core：`SqliteStore` 增通用 `store_meta` 读/写（键值、事务内、与既有 `time_base` 同表；core 不解析历法）。
- app：`CalendarService`（默认配置、启动加载、`apply` 三段式：校验→持久化→换快照）、JSON 解析用 app 既有
  Jackson；`ClassFirstPopulationEconomyTimeParticipant` 注入 `CalendarService`。
- 工具：`simos.calendar.info`（读，四桶；报 `calendarSource/seasonSource/zoneSource`，默认/已存可辨）、
  `simos.calendar.configure`（GM 写，部分合并、`dryRun`、warnings；可分带配置带宽）；
  按既有工具目录/桶/UiTool 注册规矩接线（`CatalogTool` hints 必须同步，缺项当场抛）。
- **默认算法记录（D-018 补裁）**：代码里设唯一缺省常量 `CalendarDefaults`，等于设计稿 §九 表；
  未配置分带 ⇒ 全球北半球四季 + `zoneSource: fallback`；改缺省值必须另开 D 条目。
- 报告点名：新增/修改的命令面、工具面计数变化、缺省配置 JSON、来源标记行为。

### C6 读口 + GUI（D-020 必做）
- 服务端读口：`ApiViews.timestamp`、`ToolSupport` 加 `date`/`season`/`solarTerm`；
  `/api/state` 的 `meta.timestamp` 带 `date`；`/api/timeline` 每个 node 带 `date`；
  `/api/map/hex` 带 `date` + `season`（按 `coord.r()` 判带、`zoneSource: default|store|fallback`）± `solarTerm`。
  `GuiServer` 注入 `CalendarService`（旧构造器用默认实例兜底，避免测试成批改签名）；历史 revision 按该 revision 的 tick 算。
- 前端：`timeline.js` 节点加日期行（+ `#timeline-meta` 日期；暴露纯函数供门禁测试）；
  `panels.js` `renderHex` 加“季节”行；`map-hostpage.js` `showHex` 同步加；`styles.css` 加必要的日期/季节行样式。
- **两个宿主页同步**（`index.html` 工作台 + `map.html` 旧页）；`timeline.js`/`map.js` 的加载顺序与
  `helpers/webui-loader.cjs` 的 `BUNDLE_DEPS` 不许反向。
- 前端门禁下界两处同值：`run-gate.cjs` 的 `MIN_TESTS` 与 `gate-contract.test.cjs` 的 `MIN_ASSERTIONS`
  （新增 `.test.cjs` 才动 `REQUIRED_FILES`）。
- 不改 `calendarLabel` 语义；不写 revision/状态；不做季节上色/新图层。
- 报告点名：改动到的读口、前端 JSON 键、前端文件清单、门禁下界新旧值（给 C7）。

### C7 测试代理（单派）
- 先按 **设计稿 §十 的 7 条判据逐条落实**（不是按代码反推该测什么）；再用本计划 §「测试代理专用输入」的
  字面量清单补边界；关键项做**变异自证**（改坏→当场红→还原→比 md5），做不到的如实写「等价存活」。
- 后端：Julian/季节/派生常量/配置工具按判据 1~6；前端（D-020）：`/api/state`+`/api/timeline` 的 `date`、
  `/api/map/hex` 的 `season`、时间线日期行、两个宿主页的“季节”行、未配置分带的 fallback 文案、前端门禁。
- 必跑：全仓 `./mvnw clean verify`（前台、`-am`、Spotless+Checkstyle+SpotBugs+Surefire+前端门禁全过）；
  报测试数前先 `rm -rf */target/surefire-reports`，核对 mtime；前端单独跑 `node simos-app/src/test/js/run-gate.cjs`。
- 报告必须有「我没做 / 没验证的」一节；不得把没跑写成通过。

---

## 测试代理专用输入（写码代理报告必须带全）

**oracle**
- 儒略正反算：JDN 2248845 ↔ 1445-01-01；JDN 2451558 ↔ 儒略 2000-01-01；JDN 2299160/2299171 ↔ 1582-10-04/15；
  可用 `LocalDate` + `JulianFields.JULIAN_DAY` 生成 JDN 对拍轴（注意 LocalDate 是格里高利，日期本身不可直接比）。
- 节气：2024 立春 2/4（λ=315°）、春分 3/20、夏至 6/21、秋分 9/22、冬至 12/21（±1 天）；
- 闰年：1500/1600/1700/1900/2000（儒略全闰）、1901/1902/1903（平）。
- 默认算法（D-018 补裁）：`CalendarDefaults` 与设计稿 §九 表逐项相等；空 store 时
  `calendarSource=default`、`seasonSource=default`、`zoneSource=fallback`（全球北半球四季）；
  GM 写入后再读 `source=store`；分带边界测试用 **GM 配置的带宽**（例如临时配 `northMax=-40/southMin=40`，
  坐标用 `HexCoord` 构造——calendar 已依赖 map，直接可用），不依赖任何硬编码默认值。

**GUI oracle（D-020）**
- `/api/state`（head tick 120）`meta.timestamp.date` = `1445-05-01`；`/api/timeline` 同 tick 的多条 revision
  日期相同；
- 默认分带未配置：`/api/map/hex?q&r` 对任意格 `season.zoneSource = "fallback"`、`season.name = "夏"`
  （tick 120 + 默认节气季界）；
- GM 配 `northMax=-40/southMin=40` 后：`r=-100` ⇒ 北温带·夏；`r=100` ⇒ 南温带·冬（相位 180°）；`r=0` ⇒
  热带·雨/旱（按黄经窗口）；
- 工作台时间线节点：存在日期文本节点（如 `1445-05-01`）且 `aria-label`/`title` 含日期；
- 两宿主页格详情：`selection-detail` / `hex-detail` 出现“季节”行；旧页 `map-hostpage.js` 同步；
- 门禁两处下界同值；只改一处 ⇒ `gate-contract.test.cjs` 应红。

**受影响字面量清单（C4 写码代理填全，测试代理据此收紧）**
- `AgeBracket.UPPER_BOUNDS`（365 → 历年）
- `EconomyVocabulary.CLOTH_CYCLE_DAYS`、`cumulativeClothMilli` / `dailyClothNeedMilli`
- `EconomySeeder.AGE_BRACKET_MAX_EXCLUSIVE_DAYS` 及年龄/配额采样
- `GovDaily` 布料需求式、`CrisisMonitor` clothNeed 累计
- 其余由写码代理 `git grep -nE "365|15L \*|60L \*" simos-*/src/main` 查全后补

**变异清单（至少）**
1. 儒略闰年规则 `%4` 改成“格里高利 4/100/400” ⇒ 1900-02-29 用例红；
2. 反算公式某一步（如 `c=J+32082` 改 `+32082±1`）⇒ 往返/锚点红；
3. 太阳黄经公式去掉 `1.915 sin g` ⇒ 立春日期红；
4. `LatitudeBands.zoneOf` 的 `≤/≥` 边界改错 ⇒ **在 GM 配置的两条带宽上**边界用例红（无配置时另测 fallback=全球北半球四季）；
5. `YearFraction` 整年返回 365/366 分母互换 ⇒ 衣着整年=人口×1000 用例红；
6. 去掉 `timeline.js` 的日期行 / 去掉 `panels.js` 的“季节”行 ⇒ 前端门禁用例红（两宿主页各测一遍）；
7. `/api/map/hex` 的 `season` 写死为固定值（不看 `coord.r()`/tick）⇒ 分带 GUI 用例红。

---

## 风险与对冲

| 风险 | 对冲 |
|---|---|
| C4 改数值行为，既有测试成批红 | 用户已选“历法派生、接受回归”；写码期只编译不跑测试，C7 统一适配/收紧；提交信息写明变化 |
| 锚点中途变更导致历史读数平移 | GM 工具 `dryRun` + warnings；建议创世期配置；`store_meta` 不落 revision |
| 默认算法/缺省值被实现者静默改掉 | 设计稿 §九 是唯一口径；代码 `CalendarDefaults` 与 `info` 来源可辨；改缺省另开 D 条目 |
| 地图南北方向/带宽与用户直觉不符 | 轴 = `r`、负 r = 北（已实测）；分带由 GM 配置、不预设；`northIsNegative` 可配 |
| 依赖方向（`util → map → calendar`）与现有模块表冲突 | C1 同步更新 AGENTS §〇；util 零 simos 依赖、map 只依赖 util 不变；两侧 ban 列表补 calendar 防反向 |
| 既有 R4 经济线的季节预期与 R2“农忙农闲” | 本批只提供 `SeasonState` 钩子，不接结算；接入另开批次 |
| 两个宿主页漂移（只改工作台、旧页没季节行） | C6 任务书写死两页同步；C7 两页各测一遍（静态接线 + API 形状） |
| 前端门禁下界两处不同步 | 任务书点名两处同值；C7 变异“只改一处”应红；新增 `.test.cjs` 才动 `REQUIRED_FILES` |
| 日期/季节在前端被重算成第二套算法 | 接口字段由服务端下发；任务书禁止前端重写儒略历/节气；C7 断言前端只读字段 |

---

## 关账判据

1. 设计稿 §十 的 **7 条**判据全部有**当场跑过的**证据（测试数、报告 mtime、变异红记录）；GUI 两条（D-020）
   含前端门禁与两宿主页。
2. `./mvnw clean verify` 全绿；提交信息中文，写清改了什么/为什么/实际数字/未验部分。
3. AGENTS §〇 模块表、D-018/补裁/D-019/D-020 的「关联实现」回填提交号；默认算法 `CalendarDefaults` 与设计稿 §九 对齐。
