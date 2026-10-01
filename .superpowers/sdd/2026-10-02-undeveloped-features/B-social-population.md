# B 线调查报告：社会 / 人口 / 城市 / 人物（2026-10-02）

- 范围：`docs/superpowers/plans/2026-10-02-undeveloped-features.md`「大蜀国策」与「补充」中社会/人口/城市/人物相关的 7 条缺口。
- 方式：**只读**。读代码 + `git grep`/`rg` + `git log`，未改任何代码/文档/世界数据，未跑 Maven，未提交任何世界写接口。
- 世界事实（只读 HTTP GET，当前 `head=127 / tick=120`，`/api/state`）：
  - `/api/social/cities`：344 座 social 城市；`c44_-68`「成都」(44,-68) MajorCity、region `大蜀__CAP`、population **0**；`c-33_-55`「西陵城」13756；`c-35_-60`「Lindenheim」6853；`c-31_-60`「铁门坎」7658。
  - `/api/social/population?q=44&r=-68`：population 9296、source `batches`、urban 0、rural 9296（6 条 `rural:44_-68:*` 批次）。
  - `/api/map/overview?mapId=Map1`：`cities` = **0**；同一世界 social 侧有 344 城。
  - `/api/economy/overview`：`classFirst.available=true, poolCount=4`（当前世界是 class-first）。
- 三种结论的判据：**确认缺失** = 代码里没有该能力；**部分具备** = 有替代路径但语义/原子性/读口不完整；**已具备等价** = 现有能力可逐字段替代。

---

## B1. Person/Character/Noble/Family/Hostage（人物/家族/俘虏）领域

- **结论：确认缺失**（人物实体、家族、俘虏关系都没有）；只有**部分具备的替代表达**：`sd.PutInfo` 叙事 + 无编制「纯人员单位」占位。两者都不能表达"北谷侯一家被接到成都"的**人物身份 + 当前位置 + 亲属关系**。
- **证据**：
  - 全仓 main 零命中：`git grep -nE "record Person|class Person|PersonId|Noble|Hostage|record Family|FamilyId" -- 'simos-*/src/main/**/*.java'` = 0。social 的全部状态只有三张表：`simos-social/src/main/java/io/mosire/simos/social/SocialData.java:43-46`
    ```java
    public record SocialData(
        Map<HexCoord, PopulationSeries> populations,
        Map<CityId, SocialCity> cities,
        Map<PeopleLotId, PopulationGroup> groups) {
    ```
  - `PopulationGroup` 是**匿名同质批次**（"一批属性完全相同的活人"，`simos-social/src/main/java/io/mosire/simos/social/population/PopulationGroup.java:19-42`），没有姓名/亲属/个体身份；它只是人口统计单位。
  - `sd.CreateDecisionMaker` **不是人物**：`DecisionMaker` 只有 id/归属/工具白名单/accessLimit/cadence/providerId/会话世代 7 个字段，**没有 name、没有位置、没有年龄性别**（`simos-sd/src/main/java/io/mosire/simos/sd/model/DecisionMaker.java:40-47`）；创建载荷必填 `affiliation`（nation/army/gov）+`allowedTools`+`cadence`（`simos-sd/src/main/java/io/mosire/simos/sd/spi/CreateDecisionMakerHandler.java:50-53,66`）。归属只认三类目标（`simos-sd/src/main/java/io/mosire/simos/sd/model/Affiliation.java:30,40,53`）。DM 的"位置"由归属派生的范围函数现算，不是可搬迁的实体坐标 ⇒ 无法表达"人在成都"。
  - `unit` 也不是人物容器：`Unit` 的成员是 `int member`（人数）+ 装备表，没有人物身份（`simos-unit/src/main/java/io/mosire/simos/unit/Unit.java:67-82`）。代码自己写明了这条边界——`GovAbsorbUnitPlan` 对 ArmyFormation 源拒收，理由原文："军队单位不是人口容器…只有无 module 的纯人员单位才能被吸收"（`simos-app/src/main/java/io/mosire/simos/app/tools/write/GovAbsorbUnitPlan.java:96-106`）。
  - `sd.PutInfo` 只能做**感知层叙事**：`SdInfoEntry` 类注明言"写的是感知层；ground truth 仍由 map/unit 等领域模块持有；影响领域计算的字段**不许**走 INFO"（`simos-sd/src/main/java/io/mosire/simos/sd/model/SdInfoEntry.java:24-31`）；`PutInfoHandler` 只做 `Address.parse` + 落 `SdChangeSet.info`，**不校验地址能否解析出实体**（`simos-sd/src/main/java/io/mosire/simos/sd/spi/PutInfoHandler.java:63-85`）。
- **现状可用 workaround**（都能做，但都不忠实）：
  1. **叙事**：`sd.PutInfo {address:"map:Map1:city.c44_-68", key:"北谷侯一家", value:"…", note:"受降后迁居成都", tick?}`——写一条挂在成都城市地址下的 INFO；可加 `tags:["大蜀-gov-central-dm"]`/`affiliations` 让中央决策人的 Docs 读得到。**没有实体被搬动**。
  2. **占位单位**：`unit.CreateUnit {id:"北谷侯一家", name:"北谷侯一家", position:{q:44,r:-68}, member:N, equipment:{}, speed:6, mobilityPerMille:900, status:"RESTING"}`——本仓确实把无 `module` 的"纯人员单位"当作人员容器用（`GovDispatchTeamPlan.java:24-25,231` 造调查组、`GovSelectExamineesTool.java:46` 造考生单位）。代价：它会出现在 unit 列表/视野/编制面，member 只是人数，且没有姓名/亲属/身份，不能作为俘虏/贵族语义。
  3. 给"北谷侯"单建 DM 也不成立：当前世界没有北谷 Nation / 北谷侯 DM（`GET /api/sd/decision-makers` 无对应条目），可以补建 Nation（`sd.CreateNation` 存在），但 DM 没有位置字段，仍不能表达"一家人"。`sd.SetDecisionMakerAccess` 只能收紧、不能给 DM 加坐标（`simos-app/.../SetDecisionMakerAccessTool.java` 只配额外收紧范围）。
- **若实现（建议）**：
  - **最小领域形状**（两层，尊重铁律 3/4）：
    - social 层：`Person(PersonId id, String name, Sex sex, long ageAtAnchorDays, long anchorTick, HexCoord at, Optional<FamilyId> family, String status)`；`Family(FamilyId id, String name)`，成员由 `SocialData.persons` 现算（不加第二份成员表，避免漂移——照 `groupsAt`/`urbanPopulationAt` 的现算形制）。年龄继续走"锚点+时间差"的现算（与 `PopulationGroup` 同制），位置用 `HexCoord`（铁律 1：地址是定位、ID 是身份）。
    - sd 层（只有需要"俘虏/人身依附"这类跨片关系时）：`Hostage(HostageId id, PersonId/FamilyId subject, UnitId captorGov, String status, long sinceTick)`。理由：`simos-social` **不能 import unit**（AGENTS.md 模块表 + `simos-social/pom.xml:18-29` 只声明 util/map/economy-api），而 `sd` 是全仓唯一同时依赖 social+unit 的领域模块（`simos-sd/pom.xml:26-31`），与 `Affiliation.Gov(UnitId)` 的既有跨片引用同制。
  - 命令名/载荷（对齐 social 现有风格）：`social.CreatePerson {id,name,sex,ageDays,anchorTick?,at:{q,r},family?}`、`social.MovePerson {id,at:{q,r}}`、`social.CreateFamily {id,name}`、`sd.CreateHostage {id,subject:{kind:"person"|"family",id},captorUnit,status,tick?}`。
  - **落点二选一**：① 放进 `simos-social` 新包 `social.person`（不加新模块，但 `SocialData`/`SocialChangeSet`/`SocialRoundTripTest` 要同步扩容，见下）；② 新建 `simos-person` 模块（状态/Codec/ChangeSet 全新、不动既有 roundtrip，但要多一个 Maven 模块 + 装配）。**推荐 ①**（人物与人口/城市同属"社会维"，且 hostage 关系仍可放 sd）。
  - **铁律 5 影响**：`SocialData` 加组件 ⇒ `SocialChangeSet` 加同名 `FieldDelta`（`SocialChangeSet.java:42-45`）；旧档 null ⇒ `Unchanged`（照 `cities`/`groups` 的兼容写法）；`SocialRoundTripTest` 反射枚举会自动要求往返（`simos-social/src/test/java/io/mosire/simos/social/change/SocialRoundTripTest.java:42-55`），且 `changeSetHasExactlyThreeComponents` 的 `hasSize(3)` 必须改（:64）；若 `PersonId` 作 map 键，`SocialCodec` 要注册它的 KeyDeserializer（照 `PeopleLotId`：`simos-social/src/main/java/io/mosire/simos/social/codec/SocialCodec.java:74-80`）。
  - **handler 注册与 catalog**：`Shell.java:497-503` 的 social handlers 列表追加；`CatalogTool.PAYLOAD_HINTS` 必须同步登记（构造函数对缺项**当场抛**：`simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java:420-425`）。
  - **MCP 工具面**：读工具建议 `simos.social.persons`（默认四桶共享 + 按 `person.at` 走 `hexVisible` 收窄；若要含俘虏/归属等敏感字段则标 `GmOnlyRead`）；写工具 `simos.social.movePerson` 等 **GM 桶**，或仅用 `simos.command.submit`（GM 口）。决策人若要提"迁人"，走 `sd.IssueDirective` + `CommandTargets`（social 无 person 专属资源路径，建议按目标格声明，和 `social.SeedGroups` 同制）。
  - **前端/API**：新增 GUI 只读 `GET /api/social/persons`，视图放 `ApiViews` 并给 MCP 读工具共用（AGENTS §8.3）；`SocialResolver` 加 `social:<mapId>:person/<id>`/`family/<id>` kind（照 `city` 分支：`simos-social/src/main/java/io/mosire/simos/social/resolve/SocialResolver.java:80,85-95`）。
- **依赖与风险**：
  - "人物是否计入人口总量"必须**先裁决**：若 `Person` 与 `PopulationGroup` 并存又都算人口，就是第三份人口账；建议明确定义为"具名精英个体，不重复计入批次"，或建人物时同批从某批次扣减（后者的算式/守恒要写清）。
  - social 放进人物后，`simos-sd` 可以引用 `PersonId`（sd→social 已允许），但 social **不能**反向引用 sd/unit；俘虏的 captor 只能用 sd 层承载。
  - 新 `PersonId`/`FamilyId` 要按本仓"稳定 ID"三件套（value/parse/toString）实现；不要复用 `PeopleLotId`（批次≠个体，`PopulationGroup:19-21` 明令）。
- **未核实项**：现有 DM/Unit 面是否有外部 GM 客户端已经在用"替身单位"表达人物（未查外部客户端）；人物模型是否应挂到 Unit 编制（将领/幕僚）——Unit 的 `StaffRole` 只覆盖 GOV 幕职（`GovFormation`），未做将领模型。

---

## B2. 城市点位不可变（social.UpdateCity / MoveCity / DeleteCity）

- **结论：确认缺失**。`social.UpdateCity` 只支持 `id` + `name?` + `props?`（合并）+ `region?`（string/null/缺席三态）；**没有** `social.MoveCity` / `social.DeleteCity`，也没有任何改 `at` 的命令。
- **证据**：
  - 城市实体的 `at` 是 `SocialCity` 的不可变组件：`simos-social/src/main/java/io/mosire/simos/social/city/SocialCity.java:40-44`；三个 `with*` 方法只改 name/props/region，**没有 `withAt`**（:70,:75,:85）。
  - `UpdateCityHandler` 解析的键只有这四个：`simos-social/src/main/java/io/mosire/simos/social/spi/UpdateCityHandler.java:62-71`（`id`/`name`/`props`/`region`）。**边界**：载荷里多给 `at` **不会报错、会被静默忽略**——handler 没有任何"未知键"校验（`git grep fieldNames/Unrecognized simos-social/.../spi/` = 0），所以现状是"给 `at` 看起来能传、其实一个字都不写"。
  - `social.UpdateCity` 的 catalog 提示也只列这四个键：`simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java:156-158`；窄工具 `SocialUpdateCityTool` 的 schema/描述同（`simos-app/.../SocialUpdateCityTool.java:47-50`）。
  - 全仓无 MoveCity/DeleteCity：`git grep -n "MoveCity\|DeleteCity" -- '*.java'` = 0；运行世界命令目录 `social.*` 只有 `ClearRegion/CreateCity/SeedGroups/SetPopulation/UpdateCity`（`command_catalog.txt` 的 `types`）。
  - 单城删除的最近替代是 `social.ClearRegion`，但它是**按 Region 批量清**（`city.at ∈ region.hexes` 或 `city.region == regionId` 任一命中整条删，`simos-social/.../ClearRegionHandler.java:82-101`），不是按 cityId 删。`map.DeleteRegion` 也只删 Region 不删 social 城市（城市归属 `map` 不知道 social，铁律 3）。
  - 读口：`ApiViews.cities` 输出 `{id,name,at,region,tier,population,props}`（`simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java:382-409`），GUI 路由 `GET /api/social/cities`（`GuiServer.java:258,660-668`）；`SocialResolver` 支持 `social:<mapId>:city.<id>` 但只回身份、不回 `at`（`simos-social/.../SocialResolver.java:85-95`）。城市 `at` 本身没有独立索引，是从 `SocialData.cities()` 现算的（`SocialData.java:43-46` 注释："某格有哪些城市"是派生量）。
- **现状可用 workaround**：迁都/挪城只能"新建 + 旧城改名/改归属/清空"多命令、非原子：
  1. 新格 `social.CreateCity {id:新id,name,at:{q,r},region?,props?}`（城市 id 由调用方给，可以是新 id，也可在清空旧城后复用）；
  2. 旧城 `social.UpdateCity {id:旧id,name:"旧都·…",region?}` 或整区 `social.ClearRegion {regionId}` 清掉；
  3. 若要求"城还是同一座"（同一 `CityId`），现有命令面做不到——`CreateCity` 对已存在 id 拒绝，`ClearRegion` 又不能只删一座。
  4. 城镇人口不随迁：见 B3/B4。
- **若实现（两案，推荐 B）**：
  - **案 A：给 `social.UpdateCity` 加可选 `at:{q,r}`**。改动最小：`SocialCity.withAt`（新 1 个方法）+ `UpdateCityHandler` 读 `at` + `SocialPayloads` 加 `optionalHex` 形状助手 + catalog/工具描述更新。**ChangeSet/Codec 零改动**（`at` 本来就是 `SocialCity` 组件，FieldDelta 已覆盖），铁律 5 往返不变式无需新测试。风险：`at` 与 `region`/人口批次可能不一致（城挪到区外、城镇人口批次仍在旧格），需要一条一致性口径。
  - **案 B（推荐）：新命令 `social.MoveCity {id, at:{q,r}, region?（string/null/缺席三态）, moveUrbanLots?(bool，缺省 false)}` + `social.DeleteCity {id, deleteUrbanLots?(bool)}`**。
    - `MoveCity`：一条 `SocialChangeSet` 原子改 `city.at` + 可选改 `region` + 可选把 `urban:<cityId>:*` 批次的 `residence` 一起挪到新格（id 不变，符合 `PopulationGroup:25` 的"迁移 = 换 residence"）。目标格必须有 `populations` 序列，否则拒绝（`SocialData.java:89-99` 的跨组件校验）。
    - `DeleteCity`：语义要显式裁决：删城是否连带删 `urban:<cityId>:*` 批次；若只删城，`SocialData.urbanPopulationAt(city)` 会对残留批次失去归属（且该方法查不存在的城会抛，`SocialData.java:218-224`）。
    - 两案都**不新增状态组件** ⇒ `SocialChangeSet`/`SocialCodec`/`SocialRoundTripTest` 零改动（铁律 5 不受影响），只动 handler + catalog hints + 注册。
- **handler 注册与 catalog / MCP**：`Shell.java:497-503` 追加两条；`CatalogTool.PAYLOAD_HINTS` 追加（缺项构造函数抛，:420-425）。写面建议 **GM 桶 + `GmOnlyCommand`**（迁城/删城是 GM 行政区划动作）或至少只在 GM 窄工具；决策人若要，走 `sd.IssueDirective` 时 `CommandTargets` 可按载荷的新格声明（`UpdateCityHandler.targetPaths` 现在返回空列表、决策路径 fail-closed：`UpdateCityHandler.java:44-49`——新命令可让目标更可判）。读面见 B6。
- **依赖与风险**：
  - 与 B3 强耦合：`MoveCity` 若不带人口，会出现"城在新格、城的城镇人口仍记在旧格"的错位（见 B3 的 id 前缀口径）。
  - `social.ClearRegion` 会连带删掉区内城市/批次/序列，不能当 `DeleteCity` 用（破坏面过大）。
  - `at` 目前无 map 校验（`CreateCity` 也不查 `map.hexes()`），加 MoveCity 前要决定是否校验"目标格在图上"；不校验会造出图外城市。
- **未核实项**：是否有前端/脚本依赖 `social.UpdateCity` 静默忽略未知键的行为（未查外部客户端）；迁都是否该作为一条组合工具（`simos.gov.moveCapital`）而不是两条领域命令——涉及 GOV/国库/编制，属 B 线之外。

---

## B3. 人口批次不可迁移 + 批次 id 读口缺失

- **结论：部分具备**。`PopulationGroup.residence` 在类型上可换（同 id 新对象），`social.SeedGroups` 的"同 id 整条覆盖"事实上能换格且 id 不变；但：**没有** `social.MovePopulationLots`，**没有**任何读口暴露批次 id，且"城镇人口属于哪座城"由 **id 前缀**而非 `residence` 判定 ⇒ 只换格不能把"城市人口"搬到另一座城。
- **证据**：
  - `PopulationGroup` 是 record，`residence` 是组件（`simos-social/.../population/PopulationGroup.java:35-42`）；类注写"迁移 = 换 residence，id 不变"（:19,25），但**没有 `withResidence`**：实例方法只有 `withCountAndStress`/`withPhysiologicalStress`（:89-97）。
  - `social.SeedGroups` 对同 id **整条替换**：`simos-social/.../spi/SeedGroupsHandler.java:80-81` `next.putAll(entries)`；载荷可以给同 id 的新 `q/r`，所以"按已知 id 换格"今天就能做到。**前提**：目标格已有 `populations` 序列——`SocialData` 构造期强制 `populationsCopy.containsKey(group.residence())`（`SocialData.java:89-99`）。
  - 若目标格还没有序列，`social.SetPopulation {entries:[{q,r,population}]}` 可**先造一条序列**（`SetPopulationHandler.java:61-69`）；`SeedGroups` 之后即可落批次。两者都是各自独立的 revision，非原子。
  - **批次 id 无读口**：
    - `simos.social.population` 只给该格聚合（total/urban/rural/ageBrackets/sex/stress + labor），**不含 lot id**：视图装配 `ApiViews.groupsView`（`simos-app/.../ApiViews.java:5442-5480`），工具 `PopulationTool.java:68-83`。
    - `PopulationFacet`（`/api/facets`、`simos.map.facets`）只回一个 `Population` 聚合值（`simos-social/.../facet/PopulationFacet.java:57-65`）。
    - GUI `GET /api/social/cities` 只给城的 `population`（`urbanPopulationAt` 现算，`ApiViews.java:405`）。
    - `git grep -n "PeopleLotId" simos-app/src/main/java/io/mosire/simos/app/tools/read/` = 0：读工具面完全看不到批次 id。
    - 世界实测（checkpoint head=100）：social groups **14442** 条，其中带 `:b<月>` 的**后续出生批次 5988 条**（`/home/cna/simos-testspace/worlds/dashu-v2/store/checkpoints/main/100.json` 解析）。⇒ 即便按创世命名约定（`rural:<q>_<r>:<SEX>:0/1/2`）反推，也已不可行：R4 出生批次的 id 含 `b<结算月>`（`PopulationLots.born`/`bornCohort`，`PopulationLots.java:79-112`），读口读不出来。
  - **"换 residence ≠ 换城"**（本条最关键）：城的城镇人口 = 全部 id 以 `urban:<cityId>:` 开头的批次之和，**不看 residence**——`SocialData.urbanPopulationAt`（`SocialData.java:218-235`）用 `PopulationLots.urbanPrefix(city)` 前缀匹配（`PopulationLots.java:124-132`）；城乡二分同样按 id 前缀（`isUrban`，:151-156；经济侧 `ResidenceKind.ofLot` 也只认前缀，`simos-economy-api/.../ResidenceKind.java:79-94`）。⇒ 把 `urban:c-33_-55:*` 批次的 residence 改到 (35,-60)，(35,-60) 的城乡读口会多出这批人，但 `c-33_-55`（西陵城）的 `urbanPopulationAt` **仍然**统计他们，(35,-60) 的 `c-35_-60`（Lindenheim）仍统计不到。id 不变 ⇒ 城籍不变。
  - 无迁移命令：`git grep -n "MovePopulation" -- '*.java'` = 0；catalog 的 `social.*` 只有 5 条（见 B2）。
- **现状可用 workaround**（对**已有批次 id 且批次数可控**的世界才成立）：
  1. 目标格无序列：`social.SetPopulation`（造 0/占位序列）→ `social.SeedGroups`（同 id、新 q/r、原 count/ageDays/anchorTick/stress）→ 若还要保留"该格原有农村人口"，必须把农村批次也按原值一并种回（因为**只要出现任何批次，读口就从"批次"取数、旧序列被整体旁路**，见下条）。
  2. 目标格已有序列/批次：直接 `social.SeedGroups` 按 id 重写 residence。
  3. 局限：拿不到 id（上面第 4 点）；非原子（多 revision，中途失败留半成品）；城籍不随迁（上面第 5 点）；出生批次不可重建。
- **若实现（建议）**：
  - **读口（P0，独立可先做）**：给 `simos.social.population` 的 `groups` 块追加 `lots:[{id,sex,count,ageDays,anchorTick,stress,urban}]`（按 `(id)` 固定序），或另开 `simos.social.groups` 读工具。视图必须从 `ApiViews` 出、与 GUI 共用（AGENTS §8.3），批次的可见性沿用 `social:<q>_<r>` 既有资源判定（`ToolSupport.populationVisible`，`ToolSupport.java:241-244`），不新开更宽的判据。**零 ChangeSet/Codec 影响**。
  - **迁移命令**：`social.MovePopulationLots {entries:[{id,to:{q,r}}], moveCityPopulation?(bool)}`（按 id 精确迁）或 `social.MovePopulationAt {from:{q,r}, to:{q,r}, filter:{urbanRural?,sex?,ageBracket?}, newCityId?}`（按源格批量迁，解决"读不到 id"）。
    - 语义必须显式裁决 **城籍**：`newCityId` 给出时，把城镇批次的 id 从 `urban:<oldCity>:<SEX>:<cohort>` 重写为 `urban:<newCityId>:<SEX>:<cohort>`（新 id 会与目标城既有批次重名 ⇒ 冲突处理要写死：合并 count 或拒绝）；不给 `newCityId` 就只换 residence、并**在文档/读口明确"城籍不由位置决定"**。若希望"城随人走"，更彻底的方案是给 `PopulationGroup` 增加显式 `cityId` 字段或建立 `city↔lot` 关系表——那是状态组件变更（铁律 5：SocialData/ChangeSet/Codec 同步），不建议塞进本轮。
    - 目标格无序列时，建议命令内部**同批**创建序列/农村批次，或在命令里带 `createSeries:true`；否则多 revision 非原子。
    - `ChangeSet/Codec`：以上两种命令都只改既有 `groups` map 的键值（FieldDelta 覆盖增删改），**不新增状态组件 ⇒ 铁律 5 的往返不变式不受影响**；但如果采用"显式 cityId 字段"方案，就走新组件流程（同 B1）。
  - **handler 注册与 catalog**：`Shell.java:497-503` 追加；`CatalogTool` hints 追加；写命令建议 **GM-only + `GmOnlyCommand`**（搬人是 GM/行政动作），GM 走 `simos.command.submit`；若要允许决策令，`CommandTargets` 按 `entries[].to` 的逐格 social 路径声明（照 `SeedGroupsHandler.targetPaths`，`SeedGroupsHandler.java:54-61`）。
- **依赖与风险**：
  - 与 B2 同批做才有意义（城与人的关系要一起裁决）；与 B4 的"建有人口城市"共用同一个城籍口径。
  - class-first 世界里 migration 后经济侧是否同步**未验证**（见 B7）：社会批次动了、class-first 池/家户账户不动会造成"两侧人口不一致"；seed/levy 类组合工具都是"social 批次 + economy/actor 同批改"的成对写法（如 `LevyRegionTool.java:57,157`、`GovRecruitPlan.java:44-48`），裸 `SeedGroups` 不带经济侧。
  - `SeedGroups`/`SetPopulation` 都**不校验目标格是否在图上**（只校验序列存在），批量迁移实现时建议补 map 存在性校验，否则会造出图外人口。
- **未核实项**：`MovePopulationAt` 的年龄/性别过滤是否会破坏"批次属性同质"（现有批次天然同质，按格筛选出的批次仍是整批，安全；但若支持部分人数拆分就需要新批次 id 规则）；class-first 结算对"新增未入池人口"的处置（见 B7）。

---

## B4. social.CreateCity 不能带目标人口

- **结论：部分具备**。`CreateCity` **明确拒收** `population`（不是静默忽略），原因是 R1 后城镇人口是 `PopulationGroup` 派生量；在指定 hex 建"有人口城市"的现有路径是 `CreateCity` + `SeedGroups`（目标格已有 `populations` 序列），但**没有单命令**，且这条路径只改 social 切片、不同步 economy/actor。
- **证据**：
  - 拒收是显式的：`simos-social/.../spi/SocialPayloads.java:189-197`，"不再接受 population 字段（R1：城的城镇人口是派生量 = 该城名下各批次之和；改人口请改批次：social.SeedGroups 的 id = urban:<cityId>:<SEX>:<细分>）"；`CreateCityHandler.java:69` 调它。设计理由在 `SocialCity` 类注（`SocialCity.java:20-28`：城市人口是派生量、不落字段，避免第三份人口账）。
  - 城镇人口确实由批次现算：`SocialData.urbanPopulationAt`（`SocialData.java:218-235`），归属靠 `urban:<cityId>:` 前缀（`PopulationLots.urbanPrefix`，`PopulationLots.java:124-132`）；批次由 `PopulationSeeder.groups/appendCityLots` 创世时落（`simos-app/.../world/PopulationSeeder.java:82-101,121-135`）。
  - 既有单批路径（生成器）就是固定序 `social.SetPopulation → social.CreateCity × N → social.SeedGroups`：`RegionSeedTool.buildBatch`（`simos-app/.../RegionSeedTool.java:531-570`），同一份 `PopulationSeeder.payload`（`PopulationSeeder.java:161+`）；`WorldgenInitializeTool.createCityPayload` 明确**不再发 population**（`WorldgenInitializeTool.java:1127-1150`）。
  - 运行世界可用路径实测：成都 (44,-68) 已有 `populations` 序列 + 6 条 `rural:44_-68:*` 批次（`GET /api/social/population?q=44&r=-68`：population 9296、urban 0），城市 `c44_-68` 已存在（population 0，`GET /api/social/cities`）⇒ 现在就能用 `social.SeedGroups` 落 6 条 `urban:c44_-68:{MALE,FEMALE}:{0,1,2}`（q=44,r=-68）把成都的城镇人口做出来，`urbanPopulationAt(c44_-68)` 立即读得到。**无需** `SetPopulation`（已有序列）。
- **现状可用 workaround**：
  1. 目标格已有 `populations` 序列（生成器格默认都有）：`social.CreateCity {id,name,at,region?,props?}` + `social.SeedGroups {entries:[{id:"urban:<id>:<SEX>:<cohort>",q,r,sex,count,ageDays,anchorTick?,stress?}...]}`，两条命令可经 `simos.command.submit` 分两次落，或经外部批组合成一条 revision（现有 `submitBatch` 只在 app 组合工具里）。
  2. 目标格无序列：先 `social.SetPopulation` 造序列，再重复 1（三步、非原子）。
  3. 陷阱：**在只有旧序列的格上只加"城镇批次"会让该格总人口变成"只剩城镇批次之和"**——只要出现任何批次，`headlinePopulationAt` 就走批次求和、整条旧序列被旁路（`SocialData.java:181-184` `hasGroupsAt → BATCHES`；`populationAt` 只加 batches，`SocialData.java:196+`）。要保住该格原有农村人口，必须把农村批次也一起种回去。
- **若实现（建议）**：
  - **不要**把 `population` 字段放回 `CreateCity`（会重新制造"看着在记、其实只是派生输入"的字段，且何时拆成 6 条批次/年龄锚点都要新裁决）。推荐 **app 层组合工具** `simos.social.createCityWithPopulation {id,name,at,region?,props?,urbanPopulation,anchorTick?}`：内部一条 revision 发 `social.CreateCity` + `social.SeedGroups`（批次拆分复用 `PopulationSeeder`/`EconomySeeder.splitByShares` 的既有口径；`CreateCity` 与 `SeedGroups` 的载荷助手已在 `WorldgenInitializeTool` 包内，`setPopulationPayload/createCityPayload`）。前置：目标格必须有 `populations` 序列，否则 BAD_REQUEST 具名拒（现有跨组件校验会拒绝，`SocialData.java:89-99`）。
  - 若要"连农村人口一起建"，直接用 **B5 的 explicit seed 模式**，而不是给 `CreateCity` 加人口。
  - `ChangeSet/Codec`：组合工具只发既有命令 ⇒ **零状态/往返影响**；catalog 不新增命令类型（工具名非命令类型，照 `simos.region.seed` 的先例）。
  - MCP：GM 桶（写），`preview` 模式照 `simos.region.seed`（`preview=true` 零写入）；读回用 B6 的城市读口 + B3 的批次读口。
- **依赖与风险**：
  - 只改 social，**不同步 economy/actor**：current world 是 class-first，新增批次没有对应的 class-first 池/家户账户/库存；`ClassFirstSocialWriteback` 只按 residence 把经济差分写回已有批次（`simos-app/.../time/ClassFirstSocialWriteback.java:150-170`），新增批次是否被纳入结算**未验证**。若要求两侧一致，组合工具要么只用于创世/清空后的格，要么同批发 `economy.Seed`/`actor.Seed`（那是 `RegionSeedTool` 的完整路径，但它的 clean gate 会拒绝已有社会数据的 Region，`RegionSeedPlan.java:47-63,393-431`）。
  - `CreateCity`/`SeedGroups` 都不校验 hex 在图上；组合工具应补。
- **未核实项**：class-first 结算对"事后新增批次"的处置；`urbanPopulation` 拆成 6 条批次的年龄代表值口径是否需要与 `PopulationSeeder.AGE_REPRESENTATIVE_DAYS` 完全一致（建议复用，该表在 `PopulationSeeder.java:63-65`）。

---

## B5. simos.region.seed 不能指定城市 hex / 明细

- **结论：确认缺失**。`simos.region.seed` 的 `capital` 只有 `{name, targetPopulation?}`，**没有** `capitalHex`/`at`/explicit entries；首都落点由生成器按候选分自选（最高分格 = `chosen[0]`）。
- **证据**：
  - `CapitalAnchor` 只有两个组件：`simos-social/.../gen/CapitalAnchor.java:15`（`String name, OptionalLong targetPopulation`）；无任何坐标字段。
  - `simos.region.seed` 的 `capital` 解析只认这两个键：`simos-app/.../RegionSeedTool.java:320-342`（`optionalCapital`：`name` 必填、`targetPopulation` 可选）；schema/描述同（:176-179，:146 明写"CapitalAnchor 无 hex，本工具不发明"）。**边界**：schema 不含 `additionalProperties:false`（`ToolSupport.schema`，`ToolSupport.java:418-426`），`parseParams` 也只读已知键 ⇒ 今天传 `capitalHex` 会被**静默忽略**，不会报错。
  - 生成器选城址（纯分数，不看档案坐标）：`SettlementGenerator` 先按 `score` 降序、再做最小距离抑制得 `chosen`（`SettlementGenerator.java:175-201`）；有首都时 `chosen[0]` 被强制为 MajorCity（:204-225 的 `tier[0] = TIER_MAJOR_CITY; // chosen[0] = 最高分 = 首都落点`）。`chosen[0]` 即首都落点；`PlannedCity.id = c-<q>_<r>` 也在这一刻定死。
  - 全仓 `capitalHex` 只出现在 `province.apply`/`ProvinceDivider`（`git grep -n capitalHex -- '*.java'` 命中 `ProvinceApplyTool.java:174-182,306` 等），与 seed 无关；`SettlementRequest` 无 anchor 字段（`simos-social/.../gen/SettlementRequest.java:49-50`：`capital`/`hexes`/`documentedNames`）。
  - 运行世界后果与 mcp-admin-gaps 一致：西陵城 (33,-55)、铁门坎 (31,-60) 都是生成器选的，与档案坐标不一致（`GET /api/social/cities` 实测）。
- **现状可用 workaround**：seed 之后多命令挪城/改名（B2 workaround）+ 多命令搬人口（B3 workaround，当前基本不可行）；或整世界重建时用 `simos.province.apply` 的 `capitalHex` 只解决"首都圈"行政划分，不解决城市落点。
- **若实现（两案）**：
  - **案 A（capitalHex，最小）**：`CapitalAnchor` 加 `Optional<HexCoord> at`（或 `SettlementRequest` 加 `anchorHex`）；`SettlementGenerator` 在 `chosen` 生成前校验 anchor 在 `request.hexes()` 内且 arable，把它作为 `chosen[0]` 插入、其余候选仍按分数+最小距离（anchor 也要参与距离抑制，否则两城可能重叠）；`tier[0]`/命名/硬目标逻辑不动。涉及文件：`CapitalAnchor.java`、`SettlementGenerator.java`、`SettlementRequest.java`（若字段放这里）、`RegionSeedPlan.Params`（`RegionSeedPlan.java:122`）、`RegionSeedTool`（schema/parse/描述）、如要让冻结配置也用则 `WorldgenConfig/NationSetup.CapitalSeed`（`NationSetup.java:76-84`）。**无状态组件变更 ⇒ ChangeSet/Codec/往返完全不受影响**；catalog 无新命令类型；工具参数向后兼容（缺席 = 旧行为）。
  - **案 B（explicit entries，较大）**：新增 GM 工具 `simos.region.seedExplicit {regionId, rural:[{q,r,population}], cities:[{id,name,at,region?,tier?,population,props?}], anchorTick?, includeEconomy?, includeActors?, ...}`：绕开 `SettlementGenerator`，直接用公开构造器拼 `SettlementPlan`（`SettlementPlan.java:38-70` 可显式构造 `ruralPopulation`+`cities`），再复用 `PopulationSeeder.groups` + `EconomySeeder.plan` + 固定批序（与 `RegionSeedTool.buildBatch` 同一份 helpers）。**必须自己重放生成器出口的三条不变量**（Σ rural、Σ city == urbanTotal、rural+urban == total，`SettlementGenerator` 类注第 9 步），否则不一致输入会被静默落盘。不新增命令类型、无 ChangeSet/Codec 影响；clean gate 仍应保留（`RegionSeedPlan.inspectCleanGate`，:393-431）。
  - 两条可先后：案 A 先落地（解决"首都必须在 (35,-60)"）；案 B 用于"逐城/逐格精确落点"（解决铁门坎命名冲突、北谷城坐标）。
- **依赖与风险**：
  - 案 A 改了生成器选择逻辑 ⇒ 必须更新生成器的确定性/不变量用例（`SettlementGenerator` 的"换插入序结果不变"与退化情形：anchor 是唯一 arable 格、anchor 与最高分重合、anchor 距其它候选 < 最小距离等）。
  - 案 B 的 explicit 模式一旦允许任意坐标，`PlannedCity.id` 的唯一性、城市人口与 `ruralPopulation` 的守恒都要显式校验；`props` 是自由 map（`optionalProps` 只禁 null，`SocialPayloads.java:210-220`）。
- **未核实项**：冻结配置 `config/worldgen/v17levant-nations.json` 是否要在下一轮同时带 anchor（未查该配置的完整字段；仅确认 `NationSetup.CapitalSeed` 只有 name/target，`NationSetup.java:76-84`）。

---

## B6. social 城市 MCP 读口缺失

- **结论：确认缺失**（MCP 无城市读工具）；`simos.map.overview.cities` 恒空是**两个独立原因叠加**：① map 切片自己没有任何城市写入者；② social 城市只有 GUI 读口、没挂 MCP 工具。另有 `simos.state.resolve` 对 social 城市的**静默过滤**（连已知 id 都解析不出来）。
- **证据**：
  - `simos.map.overview.cities` 取 `GameMap.cities()`：`simos-app/.../ToolSupport.java:604-616`；GUI 的 map overview 同样取它（`ApiViews.java:351-360`）。而 main 代码里**没有** `GameMap.withCities` 的写入者：`git grep -n "withCities" -- '*.java'` 命中只有 `GameMap.java:136` 定义、`SocialData.withCities`、测试与 `.claude` 工作树；`map.CreateCity` 命令不存在（catalog `types`）。⇒ `map.cities()` 永远是空表。
  - 运行世界实测：`GET /api/map/overview?mapId=Map1` 的 `cities` = 0；同期 `GET /api/social/cities` = 344 城；checkpoint head=100 的 map 切片 `cities` 长度 0、social 切片 291 城。
  - GUI 城市视图已经存在且完整：`ApiViews.cities(social, regionFilter, visible)`（`ApiViews.java:369-409`），路由 `GET /api/social/cities[?region=&?as=]`（`GuiServer.java:253-258,660-668`），带 `as=` 时逐城按 `at` 走 `seesHex` 过滤。
  - MCP 工具清单里没有任何 social 城市读工具：`SimosToolSource.readTools` 的读工具列表（`SimosToolSource.java:585-650`）只有 `PopulationTool`（:619）没有 city；`CatalogTool` 只管命令类型、不管读工具；`SimosToolSource` 的 GM 写面里与城市相关的只有 `social.UpdateCity` 窄工具（`SocialUpdateCityTool`）和组合工具 `simos.province.assignCities`（GM，见下）。
  - `StateResolveTool` 对已知 social 城市 id 也会回空：`SocialResolver` 确实认 `social:<mapId>:city.<id>`（`simos-social/.../SocialResolver.java:80,85-95`），但 `StateResolveTool` 用 `ToolSupport.subjectVisible` 过滤候选（`StateResolveTool.java:59-70`），而 `subjectVisible` 的 switch 里**没有 `social.city`**，落到 `default -> false`（`ToolSupport.java:279-306`）；`map.city` 分支又依赖恒空的 `map.cities()`（`ToolSupport.java:288-291,311-314`，`MapResolver.java:124-132` 的 `resolveCity` 对空表返回空候选）。
  - 写入侧确实需要 city id：`social.UpdateCity` 必填 `id`（`UpdateCityHandler.java:63`）；纯 MCP 流程若不知道 id 就只能靠 B5 生成器约定 `c-<q>_<r>` 反推（对生成器城成立、对 `CreateCity` 自定义 id 不成立）。
- **现状可用 workaround**：
  1. GUI `GET /api/social/cities`（只读 HTTP，非 MCP 工具面）——目前唯一完整的城市全量清单。
  2. `simos.province.assignCities preview=true`（GM 写工具，默认预览、零 revision）会返回**需要改归属的**城市 `[{id,name,at,fromRegion,toRegion}]`（`ProvinceAssignCitiesTool.java:297-320`）；但它只列"会变更"的城、要求 `reason`、且断言 WRITE 资源（`ProvinceAssignCitiesTool.java:167`），不能当通用读口，也没有 visibility 过滤。
  3. `simos.region.seed` 的结果里含 `capital`/`largestCity` 的 name/population/at/tier（`RegionSeedTool.java:714`），但不给 id、不给全量城市表。
  4. 已知 id 时 `simos.state.resolve` 也读不到（上面第 5 点）——**没有任何 MCP 读口能确认 social 城市存在**。
- **若实现（建议）**：
  - 新读工具 `simos.social.cities`：可选 `region?` 过滤 + 目标 branch/revision；**视图直接复用** `ApiViews.cities`（AGENTS §8.3：GUI 与 MCP 共用同一份视图，`ApiViews.java:369-409` 类注也写明"GUI 与未来 MCP 共用"）。可见性用 `ToolSupport.hexVisible(context, mapId, map, city.at())`（与 `map.overview` 判 city 同口径，`ToolSupport.java:604-607`）或 `populationVisible`；不要另写一份字段装配。
  - 桶归属：**默认四桶共享 + 逐城 `hexVisible` 过滤**（与 `map.overview`/`unit.list` 同制）；若认为"精确人口数字"是 GM 情报，可标 `GmOnlyRead`（只进 GM 桶）。若共享，需同时加进 `DecisionCallerFactory.WHITELIST`（`DecisionCallerFactory.java:88-122`）并注册到 `SimosToolSource.readTools`（:585-650）。
  - 顺手修 `subjectVisible`：新增 `case "social.city"`。但当前签名只有 `GameMap`、拿不到 `SocialData.cities()` 里的 `at`（`ToolSupport.java:281-306`）；要么把签名扩成带 `SimulationState`/`SocialData`（改 `StateResolveTool`/`StateFacetsTool` 等调用点），要么在 `StateResolveTool` 里对 `social.city` 特判查 social 切片。**不修的话，即使有城市读工具，`simos.state.resolve`/`simos.state.facets` 对 social 城市仍是空**。
  - 另一个可选修法（不推荐作首选）：让 app 的 map overview 视图把 `cities` 填成 social 城市。`ToolSupport.mapOverview` 是 app 层、可以组合 social（铁律 3 只禁 map 模块反向依赖），但会改 GUI/MCP 的既有响应形状与权限口径（所有桶共享 map.overview），影响面比新增一条工具大；且 `map.overview` 语义上是"map 切片总览"，建议保持空、由 `simos.social.cities` 承担城市读。
  - 前端/API：GUI 无需改动（已有端点）；新增的只是 MCP 面。若同时修 `subjectVisible`，GUI `/api/resolve` 与 MCP 的差异缩小。
  - ChangeSet/Codec/铁律 5：纯读工具，零影响。
- **依赖与风险**：`ApiViews.cities` 的 `population` 调 `urbanPopulationAt`，对 id 含 `:` 的城会抛（`PopulationLots.urbanPrefix` 的 fail-closed，`PopulationLots.java:124-132`）；读工具要把它折成可读错误而不是 500。城市 `props` 全量透出可能较大（当前 344 城 × 7 键，~几十 KB，可接受）。
- **未核实项**：`simos.province.assignCities` 在"所有城归属都正确"时 `cities` 返回空，所以它不能替代全量读口（已由代码确认）；哪一档桶归属是产品决定，未定。

---

## B7. 顺带核实：economy.MigrateHousehold / social.SetPopulation

- **结论：两者都只是部分具备，且都不能当"搬人"用**。
  - `economy.MigrateHousehold`：**部分具备**（只搬 `ClassRow.view` 的格，不搬人、不搬成员份额、不搬 actor 账），且当前世界是 class-first ⇒ **具名拒绝**。
  - `social.SetPopulation`：**部分具备**（按点名格整条覆盖旧人口序列；不碰批次；有批次时读口不看它），可当 `SeedGroups` 的"造序列前置"，但不是人口迁移。
- **证据**：
  - `EconomyMigrateHouseholdHandler` 类注：迁移只换 `ClassRow.view`，**人口不变**（不新增/删除人）、**成员份额不变**、**资产份额不变**、"账本 location 不随视图搬"（`simos-economy/.../spi/EconomyMigrateHouseholdHandler.java:26-38`）；`handle` 只重建 `ClassRow`（:94-108），只写 `EconomyChangeSet.classes`，不碰 `SocialData.groups`/`ActorData`。
  - class-first 拒收：handler 第一步 `ClassFirstCommandGuard.rejectIfClassFirst`（:65-69）；guard 判据是 `EconomyData.classFirst()` 非空即拒（`simos-economy/.../spi/ClassFirstCommandGuard.java:36-45`）。运行世界 `GET /api/economy/overview` 实测 `classFirst.available=true,poolCount=4`（head 127 / tick 120）⇒ 该命令在本世界一律 `REJECTED`（拒绝文案含指路"class-first 不读 classes.view"）。它没有窄工具，只能 GM 经 `simos.command.submit` 提交；非 `GmOnlyCommand`（`Shell.java` 未标）⇒ 理论上也可进决策指令，但执行时仍被 class-first 守卫拒。
  - `social.SetPopulation`：只对点名的格 `next.put(coord, stillPopulation(...))`（`SetPopulationHandler.java:61-69`），**不触碰 `groups`**；`stillPopulation` 增长率 0、无事件（:76-84）。`SocialData.headlinePopulationAt` 在"该格有批次"时返回批次求和、**完全忽略**旧序列（`SocialData.java:181-184`）；`populationAt` 也只加 batches（:196-212）；class-first 时间参与者只读 `social.groups()`（`ClassFirstPopulationEconomyTimeParticipant.java:214`），不读 `populations`。⇒ 在有批次的世界里 `SetPopulation` 对读口/结算**近乎无效**，其现实用途是"给没有序列的格造一条序列，好让 `SeedGroups` 通过跨组件校验"。
  - `SetPopulation` 不校验格在图上（handler 没有 map 查询），可以造出图外序列。
- **现状可用 workaround**：
  - 搬人：不要用 `MigrateHousehold`；用 B3 的 `SeedGroups` 按 id 重写 residence（受 B3 的 id 读口与城籍边界限制）。
  - 旧档（非 class-first）世界：`MigrateHousehold` 可用来搬"家户视图"（经济投影），但人的 account 仍在原格、社会批次仍不动，必须另行处理。
- **若实现（低优先，属经济线）**：
  - 若要在 class-first 世界支持"搬家户"：需要 class-first 等价命令（迁移 `classFirst.householdAccounts`/池投影 + 同步 social 批次 residence + actor 账 location），这是一个跨 economy/actor/social 的组合工具（参考 `EconomyMigrateHouseholdHandler` 类注的指路、`ClassFirstState` 的账户形状），不是给旧 handler 松绑。
  - `actor.MoveAccount`（按 owner 搬账）是 mcp-admin-gaps 已登记的缺口；与本线"搬人"配套。
  - `SetPopulation`：建议在读口/文档明确"旧序列在 batch 世界里不参与读/结算"，或在有批次的格上拒收（要评估 bootstrap `worlds/v17levant.json` 的兼容）；真正的迁移能力做在 B3。
- **依赖与风险**：class-first 与 batch 两套人口账并存是既有结构；任何"搬人"实现若不把 social 批次、economy 池/家户账户、actor 账三边一起裁决，就会制造新的"两侧人口不一致"。
- **未核实项**：class-first 世界对"事后新增 `social.SeedGroups` 批次"是否会在下一 tick 被差分写回纳入/报错（未跑世界、未做结算推演）；`economy.MigrateHousehold` 在非 class-first 分支上的完整行为只读了 handler（未跑）。

---

## 本线建议的实现批次与优先级（按对当前世界大蜀国策的解锁程度排序）

1. **批次 0（B6 读口，最便宜的先做）**：新增 `simos.social.cities` 读工具（复用 `ApiViews.cities` + 逐城 `hexVisible`），并修 `ToolSupport.subjectVisible` 的 `social.city` 分支。解锁：纯 MCP 流程第一次能拿到 city id/at/region/人口——迁都、归省、`social.UpdateCity`、B2/B3 的命令都以它为前提。零状态变更、风险最低。
2. **批次 1（B2 城 + B3 人的迁移，一起做）**：`social.MoveCity`/`social.DeleteCity` + 批次 id 读口（`simos.social.population` 加 `lots[]` 或 `simos.social.groups`）+ `social.MovePopulationLots`（含"城籍按 id 前缀还是按位置"的显式裁决）。解锁：西陵迁都 + 旧都人口随迁（当前明确不可行）。建议一阶段一个写代码代理（AGENTS §一.5），因为三者共享同一套口径。
3. **批次 2（B1 人物/家族最小模型）**：social 加 Person/Family（或新模块），sd 加俘虏/人身关系（如需）；命令 `social.CreatePerson/MovePerson/CreateFamily`。解锁：国策 2 的"北谷侯一家接到成都"，并为外交/贵族/俘虏线打底。工作量最大、涉及铁律 5 的状态组件扩容，单独立阶段。
4. **批次 3（B4 建有人口城市）**：app 组合工具 `simos.social.createCityWithPopulation`（CreateCity + SeedGroups 同 revision，复用现有 helpers）。解锁：任意指定 hex 单命令建有人口城市（含把成都从 0 城镇人口补起来；今天只能手动两步/多 revision）。依赖批次 1 的城籍口径。
5. **批次 4（B5 seed anchor）**：`simos.region.seed` 加 `capitalHex`（案 A），再考虑 explicit entries（案 B）。对**当前世界**不直接解锁（城市已生成），主要服务下一次世界初始化/重建时的坐标对齐；案 A 小、先做。
6. **批次 5（B7 语义收口）**：`SetPopulation` 与 batch 世界的关系写清/必要时拒收，`MigrateHousehold` 的 class-first 等价能力留到经济线单独立项。当前世界大蜀国策用不到它，最低优先。

依赖关系：批次 0 → 批次 1（城市 id 读口）→ 批次 3（城籍口径）；批次 2 独立；批次 4 独立；批次 5 独立但最后做。

---

## 我没做 / 没验证的

- **没跑任何 Maven/编译/测试**（本机限制 + 只读纪律）；所有结论来自代码阅读、`git grep`、世界只读 HTTP GET、checkpoint JSON 只读解析。没有执行任何命令/写入任何 world revision（head 127 系读取值，未提交）。
- **没调用 MCP 5727 的工具执行**（只用了 GUI 5827 的只读 GET 与 repo 内的 `command_catalog.txt` 快照）；`simos.state.resolve` 对 `social:...:city` 返回空是**代码推导**（`subjectVisible` 无 `social.city` 分支），未真跑 MCP 验证。
- **没验证 class-first 结算**对"事后新增 `social.SeedGroups` 批次"的处置（B4 风险点）；没推演 `MovePopulationLots` 对 class-first 池/账户的连锁影响。
- **没核实**外部客户端/脚本是否已依赖"`UpdateCity` 静默忽略未知键"或已用替身单位表达人物。
- **没设计**人物与 `PopulationGroup` 的人口口径（是否重复计入）、`MoveCity` 的 `region` 一致性细节、`MovePopulationLots` 的批次 id 重写冲突规则——这些是需要裁定的设计决策，报告只列了选项与影响面。
- **没核对** AGENTS/docs 里的工具数量、模块表措辞与最新代码是否逐条一致（只按任务要求核了本线相关机制）；AGENTS §四要求的"台账 vs 代码"风险本报告一律以代码为准。
- **没读** `simos-army`/`simos-gov` 的实现（B 线范围外），也没查 `config/worldgen/*.json` 的完整字段。
- 报告行数受 ≤400 行约束，部分边缘路径（如 `CommandTargets` 的具体资源串、`FieldDelta` 的内部实现）只给了入口路径，未展开逐行。
