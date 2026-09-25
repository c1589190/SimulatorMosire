# 设计要点：R1（第三阶段底座）

> **给实现者**：本文只给**判断与判据**，代码你写、测试你跑。
> 判据出处：`docs/superpowers/specs/2026-09-26-population-economy-v3-design.md` 的 §一/§二/§三/§八/§九/§十。
> 前序台账（**先读**，含控制器犯过的错）：`.superpowers/sdd/2026-09-25-aggregate-economy-v2-plan{1,2}/progress.md`、
> `.superpowers/sdd/2026-09-25-economy-v4v5/brief.md`、`.superpowers/sdd/2026-09-26-economy-v6/brief.md`。

## 目标（一句话）

**让 `social` 成为人口的唯一真值源**：人口有了真实体（年龄逐日精度 + 性别）、能在推进中变老、城市人口由它派生；经济侧**从同一份人口派生**，不再各抄一份。

## 已裁定的前提（用户 2026-09-26，不要重开）

1. **性别必须做**。
2. **D4 三档降为生成期 preset**（运行时只有逐日精度；`EconomySeeder:136,139` 两个常量降为创世输入）。
3. **`simos-ledger` 退役**（本轮**不动它**，留到 V8 轮；本轮**不要**去改 ledger）。
4. **旧档：重建也没关系** ⇒ **不写迁移工具、不写旧档兼容分支**。★ **唯一保留的一行**：新组件的 `null ⇒ 空表`（否则随包的 `worlds/v17levant.json` 打不开——那是**创世文件**）。旧世界存档**可作废**。

## 任务（按序做，每步独立可验收）

### T1：清 enforcer 洞 + 补 app 的未声明依赖（独立小事，先做）

- **补禁**：`simos-social/pom.xml` 的 `bannedDependencies` 加 `<exclude>io.mosire:simos-economy</exclude>`（当前 ban 列表停在 M0，漏了它）；`simos-core/pom.xml` 加 `simos-economy` / `simos-economy-api` / `simos-ledger` 三条。
- **补声明**：`simos-app/pom.xml` 加 `simos-util` 与 `simos-economy-api`（它**用了**——56 个 main 文件与 2 个——却靠传递依赖；本仓先例 `simos-core/pom.xml:33-36` 已把同款定性为缺陷："**依赖传递不是契约**"）。
- ★ **不要**给 `simos-social` 加 `simos-economy-api` 依赖（T2 的 id 落点见下）。
- 验收：`./mvnw -q verify -DskipTests` 仍绿（加 ban 会**暴露既有违规**——若真暴露了，**停下报告**，不要偷偷放行）。

### T2：`PopulationGroup` 类型 + `social` 加组件

- 新增 `Sex` enum（`MALE`/`FEMALE`，照本仓枚举的形状——给中文 javadoc）。
- 新增 `PopulationGroup`（**形状照 spec §三**）：`id`/`residence`/`sex`/`count`/`ageAtAnchorDays`/`anchorTick` + **年龄是派生纯函数** `ageDaysAt(nowTick)`。构造期不变量照本仓惯例（非空、非负、`count ≥ 0`、`ageAtAnchorDays ≥ 0`）。
- **id 落点**：★ **复用 `simos-economy-api` 已有的 `PeopleLotId`**（它已存在、注释自称"该批次的稳定身份"，且被 spec v2 列为待裁死代码——**本阶段它复活**）。
  ⇒ 这需要 `simos-social/pom.xml` **新增一条 `simos-economy-api` 依赖**。**这是文档允许的**（`economy-api/package-info.java:9-11` 原文："五个经济切片…**与 `simos-social` 可依赖本模块与 util/map**"），★ **T1 里不要预先禁掉它**。
- `SocialData` 加第 3 组件 `Map<PeopleLotId, PopulationGroup> groups`（**照 `cities` 的 null 兜底那一段**，一行；其余照既有冻结/逐键 null 检查）。
- **预期会红且是好的红**（**逐条修**，不要绕过）：
  - **57 处 `new SocialData(…)` 编译不过**（main 5 + test 52；清单见 spec §八.1 或自行 grep）——统一补 `Map.of()`。
  - `SocialRoundTripTest` 三处：`mutate`/`changedOf` 的 `default -> throw`、`hasSize(2) ⇒ 3`（方法名一并改）。
- **必须补的测试**（照 `SocialDataTest`/`SocialCodecTest` 既有条目同款）：冻结、保序、逐键 null、`nullGroupsMeansEmptyGroups`、codec 往返（含"旧档缺 `groups` 键"一条——**这一条是唯一保留的兼容用例**）。
- **`SocialCodec`**：若 `PeopleLotId` 作 Map 键 ⇒ 加一行 `addKeyDeserializer`（模板 `keyModule()` 里现有两条）。

### T3：`social` 侧新增一条创世命令 `social.SeedGroups`

- 让 group **能落盘**（否则它只活在测试里）。载荷：`{entries:[{id, q, r, sex, count, ageDays, anchorTick?}…]}`，`anchorTick` 缺省 = 当前世界时刻。
- 照本仓惯例：**同时实现 `CommandTargets`**、坏载荷 ⇒ `IllegalArgumentException` ⇒ handler 折成 `Rejected`。
- **必改的守卫**：`McpCoverageTest` 的 `EXPECTED_COMMAND_TYPES` + `MINIMAL_PAYLOADS`（漏一条两处都红——这是**设计好的**）。
- 验收：一条命令写 N 个 group，**一条 revision**。

### T4：`EconomicActor` 不在本轮——但**创世两侧必须构造性一致**

★ **本轮的接缝设计**（**不要**引入跨切片协调器，那是后面轮次的事）：

> **创世时由 app 一次算出 `PopulationGroup` 列表，同一份喂给两条命令**：
> `social.SeedGroups`（落人口）与 `economy.Seed`（只从它取"每格人数"来切阶层份额）。

- 改 `EconomySeeder`：人口**不再直接从 `SettlementPlan` 抄**，而是从**同一份 group 列表**按格聚合（`Σ count`）。
  ★ 这样 `Σ group == 经济侧总人口` 是**构造性成立**的，不需要运行时读 social。
- ★ **`Sex` 必须真的参与**劳动折算：把 `EconomySeeder:136,139` 的年龄档**保留为创世输入**，但**新增按性别的劳动系数**（默认值：两性同系数，**但权重表要可按性别覆盖**——这是"男耕女织"的落点，R2 才调值）。**本轮的判据只是"性别进入了折算"，不是"男女系数不同"。**

### T5：`SocialCity.population` 降级为**派生量**

- 它**生产代码零读取**（实测：渲染/解析只用 `at()`/`name()`/`id()`/`region()`）⇒ 降级**对读侧零影响**。
- 写它只有 2 处（`CreateCityHandler` / `UpdateCityHandler`）。
- ⇒ 改成**现算**（`SocialData` 上一条派生方法，照 `SocialData.java:16` 的明令："要算总量，请**现算**，别找地方存"）。
- ★ **必须一起改的跨模块断言**（否则整组红，**不许放宽**）：`WorldgenInitializeToolTest:196/449`（`Σ SocialCity.population == 该国 urban`）与 `:262-272`（"首都必须是人口最大那座"）⇒ 改成**从 group 求和**。

### T6：`social` 成为时间参与者（**只做 aging 骨架**）

- 实现 `TimeParticipant`（**只改自己一个切片 ⇒ 实现最窄的 `simulate`**，返回 `TimeProposal`；★ 不要实现 `simulateWorld`——两者二选一，同时实现会抛）。
- **本轮的推进逻辑只有一条**：`anchorTick` 不动、年龄**由 `ageDaysAt(nowTick)` 派生** ⇒ **实际上不需要改任何字段**。
  ★ 那"参与推进"的意义是什么？—— **让 social 出现在推进链里并为后续留下落点**：本轮实现为 **"不变变更集"**（`WorldTimeProposal` 的契约明令：无事的参与者必须交**不变变更集**，不得交空提案）。
  ⇒ **验收判据就是"social 在推进日志的参与者里，且不破坏 `§十一 等价性`（一次 N 天 == N 次单日）"**。
- 注册处：`Shell.java:488-492` 的清单加一行（循环 `493-495` 自动注册**无需另改**）。

## 铁律

- **不许放宽断言**。合法改法只有两种：**按新口径重算期望值**（算式写进注释）与**修夹具**（夹具自身违反新口径时）。
- **期望值优先写成由常量推出的算式**，注释里复述算式。
- **每条护栏配变异自证**：改坏判据 ⇒ 必须**红** ⇒ 用 **Edit 反向重写**还原（**不许** `cp`）⇒ 复绿。
- ★★ **判别力声明必须真的成立**：凡写"由 X 承担判别力"，X 必须**存在**且**真的会红**。写完**逐条自检并报给我**。
  （前几轮控制器犯过 **幻影判别力**、**假判别力**、**放宽断言**各至少一次。）
- ★ **验证工具本身**：报"零命中/全绿"前先证明读取模式对（前几轮踩过：`find -newermt '-5 minutes'` 在本机 `bfs` 下报错并静默返 0；`grep '^\[INFO\] simos-.*'` 取不到模块结果，因为 Reactor Summary 写的是**显示名**）。**禁止 `2>/dev/null` 吞工具错误**。
- **一次只能跑一个 Maven**；判过不过看 `target/surefire-reports/*.txt` 的 **mtime 落在本轮**，不看 rc。
- 改完跑 `./mvnw -q spotless:apply`；若它动了你没改过的文件，**停下报告**。
- **收尾前台跑 `./mvnw clean verify`**，把 **Reactor 逐模块结果**抄进报告。
- ★ **别留孤儿进程**（起后台命令就自己收干净）。
- **不要 `git commit`**；**不要**改本轮范围外的文件（**尤其不要动 `simos-ledger`**）。
