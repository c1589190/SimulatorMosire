package io.mosire.simos.app.tools.read;

import io.mosire.agentlib.tool.AgentTool;
import io.mosire.agentlib.tool.ToolContext;
import io.mosire.agentlib.tool.ToolResult;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.app.access.CatalogVisibility;
import io.mosire.simos.app.tools.ToolSupport;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.sd.spi.DirectiveWhitelist;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@code simos.command.catalog}（spec §7.1 读工具）：列出**已注册命令类型**及其载荷字段提示。
 *
 * <p>★ **判据②（R5）的载体**：清单与 {@code Shell} 实际注册的 handler **同源**（构造期注入），故 catalog 列出的每个 type 都能经 {@code
 * simos.command.submit} 到达。工具面不另立一份"支持的类型"表。
 *
 * <p>★★ **按调用者可见性过滤（用户 2026-09-23 裁定）**：清单不再对所有人一样——**GM / MCP 口看到全量**，**决策人只看到自己
 * 有途径触发的类型**（能直接调的窄工具 ∪ 令里可嵌的领域命令）。理由：旧行为下决策人看得见 {@code sd.PutInfo} / {@code sd.AdjudicateTick} /
 * {@code sd.CreateDecisionMaker} 这些**它执行不了**的类型，于是"照着目录去试"每一试都白烧一轮真 LLM
 * 调用——目录在回答"系统允许你干什么"时**必须与真权限面同源**。判据本体在 {@link CatalogVisibility}（一份实现，可单测）。
 *
 * <p>★ **构造期的覆盖断言不受过滤影响**：{@link #PAYLOAD_HINTS} 仍要求覆盖**全部已注册** type（缺项即抛）——那是"声明式清单不随注册面自动
 * 延伸"的护栏，与"列给谁看"是两件事。
 *
 * <p>★ **不给 Core 加新面**（spec §7.1 原文）：Core 不暴露 {@code CommandRegistry.types()}，清单由 app 持有。
 */
public final class CatalogTool implements AgentTool {

  /** 工具名（全局唯一）。 */
  public static final String NAME = "simos.command.catalog";

  /**
   * {@code economy.Seed} 里 {@code operator} 缺省时的推导档位提示（**从 {@link RegimeOperators#registered()}
   * 现取**）。
   *
   * <p>★ 为什么不写成字面量：{@code regime → 默认经营主体} 的**唯一拼写点**在 {@code RegimeOperators}（S1 阶段 3 的 T1）。
   * 在这里再手抄一份 ⇒ 新登记一档制度时本提示会**静静地说谎**（正是 {@code RegimeId} 类注那种假声明的形态，裁定 D8）。 迭代序取登记表自己的序（{@code
   * LinkedHashMap} = spec §六 的表序）⇒ 串是**内容的纯函数**；写法与 {@link #PAYLOAD_HINTS} 同口径
   * （声明**不随**别处的表自动延伸，但这一处**本就是** 那个表的投影，所以现取才对）。
   */
  private static final String OPERATOR_HINT = operatorHint();

  /**
   * 每个已注册 type 的载荷字段提示（spec §四表；仅给人/模型看，不参与执行）。
   *
   * <p>★ **必须与注册面逐条对齐**（T10-j）：本表是"声明式清单不随注册面自动延伸"的第三个静默面——取值曾用 {@code getOrDefault(type, "")}
   * 兜底，缺项**不报错**、只回空串（看起来有值、实际是空）。⇒ 改为**构造期强制**（见 {@link #CatalogTool}）：任何已注册 type 在本表缺项即抛，缺项不再静默。
   */
  private static final Map<String, String> PAYLOAD_HINTS =
      Map.ofEntries(
          Map.entry("unit.RenameUnit", "id, name"),
          Map.entry(
              "unit.CreateUnit",
              "id, name, position{q,r}?, member, equipment, speed, mobilityPerMille, parent?"
                  + "（省略 position ⇒ 无自身位置、跟随父，此时 parent 必填）"),
          Map.entry("unit.ReparentUnit", "id, parent?（null=清根）"),
          Map.entry("unit.SetStrength", "id, member, equipment"),
          Map.entry("unit.PlaceAt", "id, hex{q,r}?（null=撤销位置）"),
          Map.entry("unit.PlanRoute", "id, waypoints[{q,r}...]"),
          Map.entry("unit.CancelRoute", "id"),
          Map.entry("unit.DisbandUnit", "id"),
          Map.entry("unit.SetStatus", "id, status(MOVING|RESTING|ENGAGED)"),
          Map.entry(
              "unit.SetStateDescription",
              "id, state, address?（★ 阶段 D1 / D-012：当前回合状态 → 状态描述地址的链接；"
                  + "state 必填非空白；address 缺省/null/空串 = **清除**该状态链接（本来没有 ⇒ 具名拒），"
                  + "非空 = canonical 地址文本（如 army:combat.c-1；unit 不解析目标域）；"
                  + "非 GmOnly，可嵌进决策令，目标就是载荷点名的那个单位）"),
          Map.entry("unit.AttachUnit", "id, parent"),
          Map.entry("unit.DetachUnit", "id"),
          Map.entry("unit.ReparentSubtree", "rootId, parent"),
          Map.entry(
              "unit.SetFormationOffset",
              "id, dq?, dr?（★ 已退役：调用会被具名拒（无消费点）——移动/编队/战斗都不读 RelativeOffset）"),
          Map.entry("unit.SplitFormation", "rootId, subUnitIds[字符串...]"),
          Map.entry("unit.MergeFormation", "childId, parentId"),
          Map.entry("unit.PlanSparseRoute", "id, waypoints[{q,r}...]（非相邻，逐段展开）"),
          Map.entry("unit.SetRejoinTarget", "id, target?（null=清回归意图）"),
          Map.entry("unit.CreateCommandChain", "chainId, name, commander, members[字符串...]"),
          Map.entry("unit.UpdateCommandChain", "chainId, name?, commander?, members?"),
          Map.entry("unit.ApplyCasualties", "id, personnel(负增量), equipment{键:负增量}"),
          Map.entry(
              "unit.SetJurisdiction",
              "unitId, regions[regionId...]（必填；空数组 = 撤销全部管辖）,"
                  + " levyGrainCapPerCommand?, levyMoneyCapPerCommand?, levyManpowerCapPerCommand?,"
                  + " administrationPerMille?(0..1000)"
                  + "（★ 每个 regionId 必须在当前地图里存在，否则具名拒；未给的可选字段保持原值；"
                  + "三个 levy*CapPerCommand 的上限 = 一条抽取命令的上限，0 = 该类无额度、拒，"
                  + "本批不建周期累计账本；布（simos.unit.levyRegion 的 cloth?）本批无单命令上限、只受可用量约束，"
                  + "上限字段留后续；administrationPerMille 已退役：生产路径零读取，仅为旧档兼容保留，"
                  + "长期税改读 gov 读数的 efficiencyPerMille）"),
          Map.entry(
              "unit.SetGovFormation",
              "unitId, level(CENTRAL|PROVINCE), superiorGov?,"
                  + " staff?{SCRIBE|YAMEN|POST:整数}, policy?{grainPerStaffPerTick?,"
                  + " clothPerStaffPerCycle?, moneyPerStaffPerTick?, retirementPerStaff?,"
                  + " staffCap?{SCRIBE|YAMEN|POST:整数}}"
                  + "（★ staff 缺省空表、policy 缺省 OfficePolicy.defaults() 且可给部分字段；"
                  + "既有 ArmyFormation ⇒ 具名拒，一单位至多一个编制标签、不静默替换；"
                  + "superiorGov 必须存在且带 GovFormation、不得指向自身；同类型重复设置 = 整体替换）"),
          Map.entry(
              "unit.SetArmyFormation",
              "unitId, masterGov?, role"
                  + "（★ role 必填非空白；masterGov 缺省 = 未认主子，给了必须存在且带 GovFormation；"
                  + "既有 GovFormation ⇒ 具名拒，一单位至多一个编制标签、不静默替换；"
                  + "同类型重复设置 = 整体替换）"),
          Map.entry(
              "unit.SetGovPolicy",
              "unitId, grainPerStaffPerTick?, clothPerStaffPerCycle?, moneyPerStaffPerTick?,"
                  + " retirementPerStaff?, staffCap?{SCRIBE|YAMEN|POST:整数}"
                  + "（★ 部分覆盖：未给字段保持原值；staffCap 给 {} = 清空上限、缺省保持原表；"
                  + "四个数值与上限值必须 ≥0，否则 OfficePolicy 构造期具名拒；单位必须是 GOV）"),
          Map.entry(
              "unit.SetGovSuperior",
              "unitId, superiorGov?（缺省/null = 中央）"
                  + "（★ 非空必须存在且带 GovFormation、不得指向自身；沿 superiorGov 上溯不得成环，"
                  + "命中和 seen 重复即拒、最多 64 层；单位本身必须是 GOV）"),
          Map.entry(
              "unit.RecruitStaff",
              "unitId, role(SCRIBE|YAMEN|POST), count(≥1), sources?[{...}...]"
                  + "（★ sources 只校形状：JSON 对象数组、元素字段本层不解析）"
                  + "（★ 裸提只入编不扣人：本命令只加 roster；人员扣减由同批 social.SeedGroups 负责，"
                  + "受支持的调用面是配套工具批/决策令批；staffCap 含该角色且现有+count>cap ⇒ 具名拒、不截断）"),
          Map.entry(
              "unit.DismissStaff",
              "unitId, role(SCRIBE|YAMEN|POST), count(≥1)"
                  + "（★ 只离编不支付：不按 retirementPerStaff 付款、不回写社会，退休待遇/回写由配套工具批/"
                  + "决策令批承担；现有 < count ⇒ 具名拒；减到 0 保留角色键）"),
          Map.entry(
              "unit.SetTaxRate",
              "unitId, regionId, ratePerMille(0..1000)"
                  + "（★ regionId 必须已在该单位的管辖里，否则具名拒并指路先 unit.SetJurisdiction）"),
          Map.entry("map.SetTerrain", "hexes[{q,r}...], terrain"),
          Map.entry("map.CreateRegion", "regionId, name, hexes[{q,r}...], meta?"),
          Map.entry("map.UpdateRegion", "regionId, hexes?, meta?"),
          Map.entry(
              "map.RenameRegion",
              "regionId, name（都是必填非空白；regionId 必须已是当前地图里的 Region——改名不是 upsert，查无 ⇒ 具名拒；"
                  + "只改显示名，不动 hex 内容与边界；★ GM-only：决策人只能提出，实际执行由 GM 走窄工具或直接提交）"),
          Map.entry("map.DeleteRegion", "regionId"),
          Map.entry("map.SetEdge", "kind, edges[字符串...], mode"),
          Map.entry(
              "map.RegisterPathwayGroup", "id, name, color, description?, visible?, properties?"),
          Map.entry("map.RandomizeRegion", "hexes[{q,r}...], terrainA, terrainB, seed"),
          Map.entry(
              "social.SetPopulation",
              "entries[{q,r,population}...]（population ≥ 0；重复坐标后出现者覆盖）,"
                  + " anchorTick?（缺省=世界当前 tick）"),
          Map.entry(
              "social.CreateCity",
              "id, name, at{q,r}, region?（缺省=无归属）, props?"
                  + "（★ population 字段已退役并明确拒收：城的城镇人口是派生量=该城名下各批次之和，"
                  + "改人口请改批次 social.SeedGroups）"),
          Map.entry(
              "social.UpdateCity",
              "id, name?, props?（props 为合并语义）, region?（string 或 null：null=清空归属；缺省=保持原值）"),
          Map.entry(
              "social.SeedGroups",
              "entries[{id, q, r, sex(MALE|FEMALE), count, ageDays, anchorTick?}...]"
                  + "（★ 人口批次的创世入口，R1；同 id 覆盖，anchorTick 缺省=世界当前 tick；"
                  + "批次必须落在**已有农村人口序列**的格上）"),
          Map.entry(
              "social.ClearRegion",
              "regionId（必填；必须在当前 map.regions() 里）"
                  + "（★ GM-only 区域社会数据清空：目标 Region 格集内的 populations 键、groups.residence、"
                  + "cities.at 或 city.region 命中项整条删除；不动其它 Region 与任何地图/单位/GOV/决策人结构）"),
          Map.entry(
              "economy.Seed",
              "mapId, rulesVersion, entries[{q,r,"
                  + "industries[{id,name,regime,cycleDays,"
                  + "capacityPerUnit(每 1 单位规模要多少生产资料;必填),"
                  + "dailyInputPerUnit?,dailyLaborPerUnit?,outputPerUnit?,cycleInputPerUnit?(键=生产资料种类),"
                  + "allocation(@class=split|wage_first),slots[{id,name,laborParticipationPerMille}]}]"
                  + "(★ R3B.2 起 Industry 只留模板：operator/capacity/progress/cycle 改由 units 与 assetShares 承担),"
                  + "units[{id?,industry,operator?{kind,id},modeKey?,progressDays?,cycleLaborMilli?,cycleInputUsedMilli?}],"
                  + "classes[{residence(rural|urban),slot,population,laborMilli,participationPerMille,goods?,"
                  + "money?,naturalNeeds?,effectiveDemand?}],debtContracts?(E4a 只接受空数组),pledges?(同上),"
                  + "allocations[{id,group,household?,actor{kind,id},activity(= unit id),laborMilli,period}],"
                  + "assetShares[{industry,owner{kind,id},operator{kind,id},asset,quantity,kind}]?}]"
                  + "（★ 旧形状 industry 的 operator/capacity/progressDays/cycleLaborMilli/cycleInputUsedMilli 仍可读："
                  + "先合成一条默认 unit + 整额 OWNED 份额；旧 allocations[].activity 活动标签按 actor.id() 产业串对齐到 unit）"
                  + "（★ 旧档 useRights[{activity,holder,...}] 也接受，按 owner=operator=holder、activity=industry "
                  + "一对一翻译；assetShares 与 useRights 同时出现 ⇒ 拒）"
                  + "（★ 一次种一格或多格；meta 空 = 首次播种并打标；meta 非空 = 按格追加，"
                  + "若某格已有产业/阶层行则拒并点名该格坐标）"
                  + "（★ operator 缺省 ⇒ 按 regime 推导："
                  + OPERATOR_HINT
                  + "）"),
          Map.entry(
              "economy.MigrateHousehold",
              "household(家户 id 文本), toHex(目标格 q_r 文本)"
                  + "（★ S3：只搬视图与份额，身份/人口不变，账 location 不搬；"
                  + "家户不存在 / 目标格不在图上 / 目标格=原格 ⇒ 拒）"),
          Map.entry(
              "economy.TransferAssetShare",
              "share(原份额 id 文本), quantity?(缺省=原全部；须 ∈ (0,原 quantity]),"
                  + " toOwner{kind,id}?, toOperator{kind,id}?, kind(OWNED|TENANCY|COMMUNAL)?, reason?"
                  + "（★ 三者至少一项与现值不同；quantity=原值 ⇒ 整条换 id，<原值 ⇒ 拆成两行；"
                  + "份额总量逐 (industry,asset) 守恒；不动商品/货币/债务/劳动；"
                  + "同形现有份额尾段序号最大值+1 生成新 id，尾段不可解析 ⇒ 拒）"),
          Map.entry(
              "economy.SetMarketPrice",
              "q, r, commodity, price(> 0)（★ 该格无市场 ⇒ 用 Silver 计价创建空市场；有市场 ⇒ 只 upsert 该商品价；"
                  + "只写 markets，不造商品/货币）"),
          Map.entry(
              "economy.AddDemand",
              "scope(HOUSEHOLD|HEX), household(scope=HOUSEHOLD 必填), hex{q,r}?(scope=HEX 必填),"
                  + " commodity, kind(RECURRING|ONE_OFF), unit(TOTAL|PER_CAPITA), quantityPerCycle(> 0),"
                  + " createdDay?(缺省 0), expiresDay?(缺省 -1=永久), priority?(缺省 0), source?(缺省 gm), id?(缺省自动生成)"
                  + "（★ 商品在该需求范围对应的市场必须有价，否则拒并指名 economy.SetMarketPrice；"
                  + "只写 demands）"),
          Map.entry("economy.CancelDemand", "demand(需求 id 文本)（★ 不存在 ⇒ 拒；只写 demands）"),
          Map.entry(
              "economy.RegisterCandidate",
              "id, version?(缺省 1；修订须严格更大), name?, output, outputPerUnit{商品:>0 整数},"
                  + " inputPerUnit{商品:≥0 整数}?, requiredAssets{资产种类:≥0 整数}?, laborPerUnit?(缺省 0),"
                  + " buildDays?(缺省 0), cycleDays(≥1), regime, laborSource?(缺省 SELF),"
                  + " acceptedRightKinds[OWNED|TENANCY|COMMUNAL]?, assetSource{kind,id}?"
                  + "（★ (id,version) 已存在 ⇒ 拒；旧 unit 的 modeKey=id@version 不受修订影响；只写 candidates；"
                  + "进入采用算法留 E2b）"),
          Map.entry(
              "economy.SwitchMode",
              "organizationId, toModeId, retainOriginalPerMille(0..1000),"
                  + " effectiveDay?(缺省=当前日；不得早于当前日), reason?"
                  + "（★ GM-only：只登记 PENDING，迁移在日结算自动组织之前执行；"
                  + "普通决策人令 / RegisterEffect 不可嵌入它）"),
          Map.entry(
              "economy.ClearRegion",
              "regionId（必填；必须在当前 map.regions() 里）"
                  + "（★ GM-only 区域经济数据清空：目标 Region 格集内 industries/markets 及可靠可定位的连带记录"
                  + "（units/relations/operatorConditions/assetShares/classes/flows/memberships/allocations/"
                  + "debtContracts/pledges/productionOrganizations/modeTransitions/classShares/classStandings/demands/"
                  + "crisisSignals）整条删除；世界级 classFirst/发行审计/在途货物/laborSupply/制度定义不动）"),
          Map.entry(
              "economy.GmAdjust",
              "adjustment(setMobilityPolicy|setClassFirstLender|forgiveClassFirstDebt"
                  + "|setCollectionPolicy|setProductionParameters|levyStock"
                  + "|forgiveDebt|setLiquidationPolicy), parameters(JSON 对象), reason(必填非空白)"
                  + "（★ GM-only、只改源状态：白名单外/派生读数 ⇒ 拒；"
                  + "class-first 原生六：setMobilityPolicy={modeId?, 任一 MobilityPolicy 标量字段或 absorptionPolicy"
                  + "(PROPORTIONAL|ALL_OR_NOTHING)；schema/bounds/absorptionCapByEdgePerMille/bundleTemplates 给到即拒}；"
                  + "setClassFirstLender={lenderId, interestRatePerMille?/nextDueTick? 至少一项(≥0)；collectionPower 无消费点 ⇒ 拒}；"
                  + "forgiveClassFirstDebt={ownerId, counterpartyId, unit?(缺省=grain), amount?(缺省=全额债务)}"
                  + "（对称清减两条镜像账户，归零 ⇒ SETTLED；不动 interestAccrued/库存）；"
                  + "setCollectionPolicy={collectionThreshold?(≥0), collectionTriggerRatioPerMille?(≥0),"
                  + " collectionRatioPerMille?(0..1000), landPricePerUnit?(≥1) 至少一项；"
                  + "seizurePriority（枚举只有一个取值）/collectorClassPositionId（本阶段固定 LANDLORD）给到即拒}；"
                  + "setProductionParameters={15 个生产/技术标量至少一项：yieldPerLand(>0)/seedPerLand(≥0)/laborPerLand(>0)/"
                  + "toolCapacityPerTool(>0)/rentPerLand(≥0)/wagePerLabor(≥0)/baseRationPerCapita(≥0)/laborRationPerLabor(≥0)/"
                  + "nonEssentialNeedPerMille(≥0)/nonEssentialEfficiencyPenaltyPerMille(≥0)/loanInterestRatePerMille(≥0)/"
                  + "moneyPerGrain(>0)/toolPricePerUnit(≥0)/reserveTicks(≥0)/collectionIntervalTicks(≥1)；"
                  + "只改 meta.config、未给字段保持原值}；"
                  + "levyStock={fromClassPositionId, lenderId, unit(grain|money), amount(≥1 整数) 四字段全必填；"
                  + "grain 上限=stock−protectedGrainReserve(population,labor)（口粮保护储备不可抽）、money 上限=stock；"
                  + "超上限 ⇒ 拒并报 available，不截断；只改源池与目标 lender}；"
                  + "旧表两 kind 仅非空 classFirst 为空的世界可用（classFirst 非空 ⇒ 具名拒绝并指路 class-first 原生 kind）："
                  + "forgiveDebt: debtContractId, amount?(缺省=全额本金，须 ≤ 本金)；"
                  + "setLiquidationPolicy: assetRuleId, maxLiquidatePerMille(0..1000), protectedReserve(≥0),"
                  + " priceSource(MARKET|AGREED|POLICY), policyValuePerUnitMilli(≥0；非 POLICY 必须 0),"
                  + " recipientRule(CREDITOR_FIRST|MARKET_FIRST)；引用不存在的 AssetRule ⇒ 拒）"),
          Map.entry(
              "economy.UnitBorrow",
              "unitId, lenderId, unit(money|grain), principal(≥1), interestRatePerMille(≥0),"
                  + " nextDueTick(> 当前 tick), terms?(缺省 unit-debt)"
                  + "（★ GM-only：地方债借入原语，只改 classFirst.lenders/accounts 两张表——"
                  + "放贷方余额 −principal、owner=unitId/counterparty=lenderId 与镜像两条腿同批落；"
                  + "classFirst 为空 / lender 不存在 / unit 不认 / principal 超可贷量 /"
                  + "已有未结清腿 ⇒ 拒（后者指路 unit.repayDebt）；已结清身份可重开；不碰 actor；"
                  + "唯一受支持的完整调用面是 economy.UnitBorrow + actor.AdjustAccounts 工具批）"),
          Map.entry(
              "economy.UnitRepay",
              "unitId, lenderId, unit(money|grain), amount(≥1)"
                  + "（★ GM-only：地方债还款原语，只改 classFirst.lenders/accounts 两张表——"
                  + "放贷方余额 +amount、借款腿/镜像腿各减 amount，清 0 ⇒ 双腿 SETTLED；"
                  + "classFirst 为空 / lender 不存在 / unit 不认 / 没有未结清的地方债 / amount 超过负债 ⇒ 拒；"
                  + "镜像腿缺失或两腿净额不互为相反数 ⇒ 状态损坏（IllegalStateException）；不碰 actor；"
                  + "唯一受支持的完整调用面是 actor.AdjustAccounts + economy.UnitRepay 工具批）"),
          Map.entry(
              "actor.Seed",
              "mapId, rulesVersion, entries[{q, r, actors[{kind, id, label?}...],"
                  + "goods[{owner{kind,id}, location{q,r}, balances{键:整数}}...]}...]"
                  + "（★ S1 阶段 2：一次种入某地图的 actor 分片，两张表各自挂在**自己那一格**下——"
                  + "goods 的 location 必须等于所在 entry 的 (q,r)，否则拒；"
                  + "owner 必须是载荷里声明的 actors ∪ 现有状态里已有的主体，悬空 owner 拒）"
                  + "（★ H0.5/裁定 S3：产权表 holdings 随 AssetHolding 整块退役 ⇒ 本切片唯一的账是 goods）"),
          Map.entry(
              "actor.AdjustAccounts",
              "entries[{owner{kind,id}, q, r, goods{商品:有符号净增量}?, money{币种:有符号净增量}?}...]"
                  + "（★ 阶段 6：净增量账原语。entries 必填非空；每项 owner/q/r 必填；"
                  + "goods/money 至少一个非空、值不得为 0；同一 (owner,q,r) 不得重复；"
                  + "缺账 + 纯正增量 ⇒ 新建，缺账 + 任何负增量 ⇒ 拒；"
                  + "负增量使余额 < 0 或侵占冻结额（可支配 = 余额 − 冻结）⇒ 拒；整条原子）"),
          Map.entry(
              "actor.RemitGovTreasury",
              "fromUnitId, fromQ, fromR, toUnitId, toQ, toR, grain?, cloth?, money?, reason?"
                  + "（★ R3a：显式 GOV 国库上缴 / 转移，源/目标账键 = "
                  + "(ActorRef(UNIT,unitId), q_r)；两个 unit id 必填非空白、坐标必填 int、from/to 账键不得相同；"
                  + "三个金额可选缺省 0、不得为负、至少一个 > 0；源账必须存在、逐资源走 AvailableStock"
                  + "（可支配 = 余额 − 冻结）判足量；整条原子，任一违例全拒；"
                  + "★ 非 GmOnly：省份决策人可嵌进 sd.IssueDirective；目标必须是源 superiorGov 的规则在 app scope（R3b））"),
          Map.entry(
              "actor.ClearRegion",
              "regionId（必填；必须在当前 map.regions() 里）"
                  + "（★ GM-only 区域 actor 数据清空：目标 Region 格集内 location 命中的 GoodsAccount 整条删除；"
                  + "actors 只删除清账后在任何位置都不再持有账户的主体，仍有别处账户或本来就无账户的主体保留）"),
          Map.entry("sd.CreateNation", "nationId, name, homeRegionId, adminBudgetPerTick"),
          Map.entry(
              "sd.CreateArmy",
              "armyId, masterGovUnitId?, rootUnitId, name"
                  + "（★ masterGovUnitId 缺省 = 未认主子，给了必须存在且带 GovFormation；"
                  + "旧 nationId 键已拒并指路 masterGovUnitId）"),
          Map.entry(
              "sd.SetArmyMasterGov",
              "armyId, masterGovUnitId?"
                  + "（★ 已存在 Army 的主子改派/解除：masterGovUnitId 缺席/null/空串 = 解除认领；"
                  + "给了必须存在且带 GovFormation；armyId 不存在 ⇒ 具名拒；只改 sd 侧，不碰 unit 侧 ArmyFormation；"
                  + "GM-only）"),
          Map.entry(
              "sd.CreateDecisionMaker", "id, affiliation{kind,id}, allowedTools[字符串...], cadence"),
          Map.entry(
              "sd.DeleteDecisionMaker",
              "decisionMakerId（必填；不存在 ⇒ 具名拒；只删决策人身份，不级联删历史 Directive / 文档 / 会话；"
                  + "仍被 Directive 引用时具名拒，不做静默级联；GM-only）"),
          Map.entry(
              "sd.PutInfo",
              "address, key, value, note?, id?（同类型内唯一）, tags[决策人 id…]?,"
                  + " tick?（缺省=世界当前 tick；记在未来 ⇒ 拒）"),
          Map.entry("sd.CreateCombat", "combatId, name, participants[字符串...]"),
          Map.entry(
              "sd.AddCombatStage",
              "combatId, stage{stageId,name,participants?,entry,exit,minDurationTicks?,"
                  + "maxDurationTicks?,outcomes{options[{id,label,weight,casualties?}]}},"
                  + " combatStateId?(首阶段必填), hex{q,r}?(首阶段必填；"
                  + "非首阶段时出现任一键（含显式 null）都会被具名拒)"),
          Map.entry(
              "sd.SetStageOutcomeTable",
              "combatId, stageId, outcomes{options[{id,label,weight,casualties?}]}"),
          Map.entry("sd.CommitCombatOutcome", "combatId, stageId, selectedOutcomeId"),
          Map.entry(
              "sd.RecordCasualties",
              "combatId, stageId, deltas[{unit,personnel,equipment,lossClass(PERMANENT|RECOVERABLE)}]"),
          Map.entry(
              "sd.RegisterEffect",
              "effectId, kind(SCHEDULED|ON_CALL|BE_PREPARED|BRANCH|SEQUEL), trigger, action, createdTick?"),
          Map.entry("sd.CancelEffect", "effectId"),
          Map.entry(
              "sd.IssueDirective",
              "directiveId, decisionMakerId, tick, target?, intentInfo, commands[{type,payloadJson}],"
                  + " effects[字符串...]?"),
          Map.entry(
              "sd.SubmitVerdict",
              "verdictId, breakpoint(D1|D3|D6), subject(sd:combat.*), payload(JSON 文本),"
                  + " meta{model,promptVersion,inputBriefDigest}"),
          Map.entry(
              "sd.SetDecisionMakerAccess",
              "decisionMakerId, allowedTools[]?, accessLimit{命名空间:[前缀…]}?, redactedFields[]?,"
                  + "adjudicationDisclosure(FULL|PERCEPTION_ONLY|WITHHELD)?"
                  + "（★ 四个可选字段：缺省 = 不改动，显式给 = 整份替换；"
                  + "accessLimit 是**额外限制**，与范围函数求交 ⇒ 只能收紧）"),
          Map.entry(
              "sd.ResetDecisionMakerConversation", "decisionMakerId（会话世代 +1：该决策人下一轮从空上下文重开；旧会话不删）"),
          Map.entry("sd.StartDecision", "decisionMakerId, note?"),
          Map.entry("sd.RunDecision", "decisionMakerId"),
          Map.entry("sd.SetDecisionMakerProvider", "decisionMakerId, providerId"),
          Map.entry(
              "sd.SetDirectiveStatus",
              "directiveId, status(EXECUTED|CANCELLED)（★ 只允许 ISSUED → 二者之一，只由"
                  + " sd.AdjudicateTick 内部编排产生；不对外提供窄工具）"),
          Map.entry(
              "army.RecordCombat",
              "id, tick?, hex{q,r}, participants[unitId...]（至少一个、不重复）, text（自然语言过程/结局，非空白）,"
                  + " losses?{自然语义键:非负整数}（★ 阶段 D1 / D-012：单 tick 单场交战记录；"
                  + "tick 缺省 = 当前 tick、不得记在未来；同 id 已存在 ⇒ 具名拒（不可变历史）；"
                  + "参与者不查 unit 切片是否存在；★ GmOnly：决策人不得凭空写战果）"));

  /** {@code regime→种类} 的 ` / ` 连接串（登记表序；见 {@link #OPERATOR_HINT}）。 */
  private static String operatorHint() {
    StringBuilder hint = new StringBuilder();
    for (Map.Entry<String, ActorKind> entry : RegimeOperators.registered().entrySet()) {
      if (!hint.isEmpty()) {
        hint.append(" / ");
      }
      hint.append(entry.getKey()).append("→").append(entry.getValue().name());
    }
    return hint.toString();
  }

  private final List<String> types;
  private final CatalogVisibility visibility;

  /**
   * ★ 单参兼容构造（既有用例/调用点）：catalog 清单与"可嵌入令"白名单**同源**（同一份输入集交给 {@link CatalogVisibility} 与 {@link
   * DirectiveWhitelist} 各自推导）。
   *
   * @param commandTypes 已注册命令类型（与 {@code Shell} 注册的 handler 同源）；本类只读它
   * @throws IllegalArgumentException 有已注册 type 未登记载荷提示（缺项不静默——见 {@link #PAYLOAD_HINTS}）
   */
  public CatalogTool(Set<String> commandTypes) {
    this(commandTypes, commandTypes);
  }

  /**
   * ★★ E6b：清单与"令里可嵌白名单"**拆开** —— catalog 可见的命令类型可以 ⊋ 可嵌入令的白名单。
   *
   * <p>为什么需要它：E6a 起 {@code GmOnlyCommand}（{@code economy.SwitchMode} / {@code economy.GmAdjust}）不得进
   * {@code DirectiveWhitelist}，但 GM 的 catalog 仍要列出它们（GM 能用 {@code simos.command.submit} 直接提交）。 若继续把
   * {@code registered − GmOnly} 喂给 catalog，GM 就看不到这两条；而把完整注册面同时当白名单来源，决策人又会看到 {@code
   * GmOnlyCommand}（它们不在 {@code directiveCommandTypes} 里）。故此处收两份输入：catalog 面取完整注册面，白名单面取 {@code
   * Shell} 已派生的"注册面 − GmOnly"。
   *
   * @param commandTypes catalog 可见的**完整注册面**（含 GM-only）；本类只读它
   * @param embeddableCommandTypes 可嵌入令白名单的**输入集**（{@code Shell} 的 {@code directiveCommandTypes} =
   *     注册面 − GmOnly）；{@link CatalogVisibility} 会照旧经 {@link DirectiveWhitelist} 过滤 {@code sd.*} /
   *     通用写
   * @throws IllegalArgumentException 已注册 type 缺载荷提示，或可嵌入白名单含未注册 type（两处都不静默）
   */
  public CatalogTool(Set<String> commandTypes, Set<String> embeddableCommandTypes) {
    Objects.requireNonNull(commandTypes, "commandTypes");
    Objects.requireNonNull(embeddableCommandTypes, "embeddableCommandTypes");
    List<String> sorted = new ArrayList<>(commandTypes);
    Collections.sort(sorted);
    List<String> missing = new ArrayList<>();
    for (String type : sorted) {
      if (!PAYLOAD_HINTS.containsKey(type)) {
        missing.add(type);
      }
    }
    if (!missing.isEmpty()) {
      throw new IllegalArgumentException("已注册命令类型未登记载荷提示（PAYLOAD_HINTS）: " + missing);
    }
    List<String> notRegistered = new ArrayList<>();
    for (String type : embeddableCommandTypes) {
      if (!commandTypes.contains(type)) {
        notRegistered.add(type);
      }
    }
    if (!notRegistered.isEmpty()) {
      throw new IllegalArgumentException("可嵌入令的白名单含未注册命令类型: " + notRegistered);
    }
    this.types = List.copyOf(sorted);
    // ★ 决策人可见性仍走既有唯一判据 CatalogVisibility（内部照旧用 DirectiveWhitelist 过滤 sd 自指/通用写）：
    //   E6b 只把"输入集"从完整注册面换成已排除 GmOnly 的 directiveCommandTypes ⇒ 决策人行为逐值不变。
    this.visibility = new CatalogVisibility(embeddableCommandTypes);
  }

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public String description() {
    return "列出本世界已注册的命令类型及其载荷字段提示（simos.command.submit 的 type/payloadJson 依据）。"
        + "★ 返回的是**你有途径触发的**那些：管辖者看到全部；决策人只看到能直接调用的类型与**令里可以嵌入**的领域命令"
        + "（令里禁 sd. 自指，故 sd.* 只有能直接调的那两条会出现）";
  }

  @Override
  public Map<String, Object> jsonSchema() {
    return Map.of("type", "object", "properties", Map.of());
  }

  @Override
  public ToolResult execute(ToolContext context) {
    List<String> visible = new ArrayList<>();
    for (String type : types) {
      if (visibility.visible(type, context)) {
        visible.add(type);
      }
    }
    Map<String, Object> view = new LinkedHashMap<>();
    view.put("types", List.copyOf(visible));
    Map<String, Object> hints = new LinkedHashMap<>();
    for (String type : visible) {
      // 构造期已断言本表覆盖全部 type（T10-j），此处不再兜底成空串（缺项不静默）。
      hints.put(type, PAYLOAD_HINTS.get(type));
    }
    view.put("payloadHints", hints);
    return ToolSupport.ok(view);
  }
}
