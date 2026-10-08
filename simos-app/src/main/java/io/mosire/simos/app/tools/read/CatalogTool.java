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
              "id, name, position{q,r}?, households?[家户 id], equipment[{type,amount}], speed,"
                  + " mobilityPerMille, parent?（省略 position ⇒ 无自身位置、跟随父，此时 parent 必填；"
                  + "★ S3b：manpower 已退役（非空具名拒，人员属于 Social 家户）；equipment 必填、空数组合法；"
                  + "households 给的 Social 家户必须存在，位置不一致时下一轮推进自动同步到 UNIT(本单位)）"),
          Map.entry("unit.ReparentUnit", "id, parent?（null=清根）"),
          Map.entry(
              "unit.SetComposition",
              "id, equipment[{type,amount}]"
                  + "（★ S3b：manpower 已退役、非空具名拒，人员属于 Social 家户；装备整表复写——整体取代旧表、不是增量；"
                  + "未知 type 合法（给什么就是什么）；amount ≥ 0、同表 type 不重复，越界由 Unit 构造期拒。"
                  + "旧 unit.SetStrength 已按 D-011 删除，不留兼容）"),
          Map.entry("unit.PlaceAt", "id, hex{q,r}?（null=撤销位置）"),
          Map.entry("unit.PlanRoute", "id, waypoints[{q,r}...]"),
          Map.entry("unit.CancelRoute", "id"),
          Map.entry("unit.DisbandUnit", "id"),
          Map.entry("unit.SetStatus", "id, status(MOVING|RESTING|ENGAGED)"),
          Map.entry(
              "unit.SetVisionRadius",
              "id, visionRadius（整数 ≥ 0；0 = 只看自身格。"
                  + "★ P1.2 / A6：字段早已存在，本命令补写路径；当前读者是 ArmyScope 的可见范围函数；"
                  + "非 GmOnly，可嵌进决策令，目标就是载荷点名的那个单位）"),
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
          Map.entry(
              "unit.ApplyCasualties",
              "id, equipment[{type,amount≤0}]"
                  + "（★ S3b：manpower 已退役、非空具名拒，人员战损要落 Social 家户命令；装备 delta 只扣提及的 type，"
                  + "未提及的 type 保持不变；提及不存在的 type ⇒ 具名拒（P14，不视作 0）；|Δ| ≤ 当前值）"),
          Map.entry(
              "unit.AdjustComposition",
              "id, equipment[{type,amount(有符号)}]"
                  + "（★ S3b：manpower 已退役、非空具名拒，人员属于 Social 家户；GM 调试直改原语："
                  + "正增量可新建 type（追加表尾），负增量要求 type 已存在且 |Δ| ≤ 当前值；"
                  + "同表 type 不重复、零增量合法 no-op；非 GmOnly）"),
          Map.entry(
              "unit.SetUnitHouseholds",
              "unitId, households[家户 id...]（必填数组；空数组=清空、保序；不得重复）, reason"
                  + "（★ S3a：整体替换 unit 容纳的家户列表；unit 必须存在；同一家户不得同时属于两个 unit、"
                  + "unit id 不得与 household id 撞名（UnitState 构造期具名拒）；UNIT 家户位置的一致性由 app 组合工具同批保证，"
                  + "生产路径（推进参与者）还会在每轮推进前把 Social 位置自动同步到 unit.households）"),
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
                  + " householdPosts?[{household,role,level,head?}],"
                  + " staff?{SCRIBE|YAMEN|POST:整数}, policy?{grainPerStaffPerTick?,"
                  + " clothPerStaffPerCycle?, moneyPerStaffPerTick?, retirementPerStaff?,"
                  + " staffCap?{SCRIBE|YAMEN|POST:整数}}"
                  + "（★ staff 缺省空表、policy 缺省 OfficePolicy.defaults() 且可给部分字段；"
                  + "★ 2026-10-09 唯一列表裁定：载荷不再有 households 键——域层立编制时把政府家户 "
                  + "hh-gov-<unitId> 编入 Unit.households，其余家户先走 unit.SetUnitHouseholds（GOV 单位须保留该政府家户）；"
                  + "householdPosts 缺省 = 保持既有领导配置；S3b 起 householdPosts 是以 HouseholdId 为键的"
                  + "领导层家户具名配置（键必须在本单位 households 里；非空时 staff 只是家户人口投影、"
                  + "unit.RecruitStaff/DismissStaff 具名拒）；"
                  + "既有 ArmyFormation ⇒ 具名拒，一单位至多一个编制标签、不静默替换；"
                  + "superiorGov 必须存在且带 GovernmentFormation、不得指向自身；同类型重复设置 = 整体替换）"),
          Map.entry(
              "unit.SetArmyFormation",
              "unitId, masterGov?, role,"
                  + " householdDuties?[{household,kind(SOLDIER|NCO|OFFICER|COMMANDER),appointment,commandOf?}],"
                  + " militaryPayPolicy?{periodDays,phaseDay,startsOnDay,expiresOnDay?,"
                  + "grainPerHouseholdPerCycle?{家户:整数},clothPerHouseholdPerCycle?,moneyPerHouseholdPerCycle?,enabled?}"
                  + "（★ S3b：householdDuties 是以 HouseholdId 为键的军官/军职家户具名配置"
                  + "（键必须在本单位 households 里）；缺省 = 保持既有配置；"
                  + "★ P4b：militaryPayPolicy 缺省 = 保持既有军俸政策、给了 = 整体替换同一个组件；"
                  + "role 必填非空白；masterGov 缺省 = 未认主子，给了必须存在且带 GovernmentFormation；"
                  + "既有 GovernmentFormation ⇒ 具名拒，一单位至多一个编制标签、不静默替换；"
                  + "同类型重复设置 = 整体替换（未提及的 householdDuties/militaryPayPolicy 保持原值））"),
          Map.entry(
              "unit.SetArmyPayPolicy",
              "unitId, periodDays(>0), phaseDay([0,periodDays)), startsOnDay(≥0), expiresOnDay?(null=永久),"
                  + " grainPerHouseholdPerCycle?{家户 id:整数}, clothPerHouseholdPerCycle?,"
                  + " moneyPerHouseholdPerCycle?, enabled?"
                  + "（★ P4b：三张逐家户表缺省 = 空表；三表全空 = disabled()（允许，表示停发；"
                  + "此时排期字段可整组省略，最短停发载荷只给 unitId）；逐值必须 > 0、"
                  + "列出的家户必须在 Unit.households 里，否则域层具名拒；"
                  + "可选 enabled=true + 三表全空 ⇒ 具名拒；单位必须存在且带 ArmyFormation）"),
          Map.entry(
              "unit.SetGovPolicy",
              "unitId, grainPerStaffPerTick?, clothPerStaffPerCycle?, moneyPerStaffPerTick?,"
                  + " retirementPerStaff?, staffCap?{SCRIBE|YAMEN|POST:整数}"
                  + "（★ 部分覆盖：未给字段保持原值；staffCap 给 {} = 清空上限、缺省保持原表；"
                  + "四个数值与上限值必须 ≥0，否则 OfficePolicy 构造期具名拒；单位必须是 GOV）"),
          Map.entry(
              "unit.SetGovSuperior",
              "unitId, superiorGov?（缺省/null = 中央）"
                  + "（★ 非空必须存在且带 GovernmentFormation、不得指向自身；沿 superiorGov 上溯不得成环，"
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
          // ── S3a（2026-10-09）：家户生命周期七条命令的载荷提示（本表构造期要求覆盖全注册面）。──
          Map.entry(
              "social.CreateHousehold",
              "householdId, location{type:HEX|UNIT, hex{q,r}|unitId}, profile{name, description?, metadata?},"
                  + " vitalRates?[{bracketId, sex(MALE|FEMALE), birthRatePerMillionPerTick?,"
                  + " deathRatePerMillionPerTick?}], reason"
                  + "（★ 新建家户成员表为空；id 已存在 ⇒ 拒；vitalRates 缺省空表；UNIT 的 unit 侧同步由 app 组合工具同批保证）"),
          Map.entry(
              "social.SetHouseholdLocation",
              "householdId, location{type:HEX|UNIT, hex{q,r}|unitId}, reason"
                  + "（★ 只动 Social 位置；HEX↔UNIT 都合法；UNIT 的 unit 侧同步由 app 组合工具同批保证）"),
          Map.entry(
              "social.AddHouseholdMembers",
              "householdId, lotId?, sex(MALE|FEMALE), count(>0), ageAtAnchorDays?, anchorTick?, reason"
                  + "（★ lotId 缺省确定性生成 gm-add:<householdId>；批次 id 已存在 ⇒ 拒；anchorTick 缺省=世界当前 tick）"),
          Map.entry(
              "social.RemoveHouseholdMembers",
              "householdId, lotId, count(>0), reason" + "（★ 批次必须属于该家户；扣到 0 删批次；超量 ⇒ 具名拒）"),
          Map.entry(
              "social.TransferHouseholdMembers",
              "from, to, lotId, count(>0), reason"
                  + "（★ 源≠目标；整批移动保 id、拆分落派生 id <lotId>@<to>；两条腿事件原子写入）"),
          Map.entry(
              "social.SetHouseholdVitalRates",
              "householdId, rates[{bracketId, sex(MALE|FEMALE), birthRatePerMillionPerTick?,"
                  + " deathRatePerMillionPerTick?}], reason"
                  + "（★ 整体替换率表；rates 缺失/null=清空；两个率缺省 0；负数/重复 (bracketId,sex) ⇒ 拒）"),
          Map.entry(
              "social.SetGlobalVitalRates",
              "rates[{bracketId, sex(MALE|FEMALE), birthRatePerMillionPerTick?,"
                  + " deathRatePerMillionPerTick?}], reason"
                  + "（★ GM-only D4：整体替换 Social 全局默认生死率表；rates 缺失/null=空表；两个率缺省 0；"
                  + "负数/重复 (bracketId,sex) ⇒ 拒；家户覆盖不受影响）"),
          Map.entry(
              "social.AdjustHouseholdPopulation",
              "householdId, sex(MALE|FEMALE), ageBracketId(如 0-14|15-59|60+), delta(非 0，可负), reason"
                  + "（★ GM 直调；负不得使人数 < 0；未知年龄档 id ⇒ 拒）"),
          Map.entry(
              "social.SetDemandCoefficient",
              "householdId?, ageBracket(0-14|15-59|60+), sex(MALE|FEMALE), commodity(如 grain|cloth),"
                  + " amountMilli?, period?(PER_CYCLE_DAYS|PER_CALENDAR_YEAR), cycleDays?, reason"
                  + "（★ GM-only：householdId 缺席=改全局默认、给了=改家户覆盖（家户必须存在）；amountMilli 给了=upsert、"
                  + "缺席=删除该家户覆盖键（全局默认不允许删键 ⇒ 拒）；period/cycleDays 同时缺席则按该商品全局口径推断"
                  + "（找不到 ⇒ 拒），家户覆盖显式口径必须与全局一致；结果经 SocialData.withProvisioning 写回）"),
          Map.entry(
              "social.SetLaborCoefficient",
              "householdId?, ageBracket(0-14|15-59|60+), sex(MALE|FEMALE), milliHoursPerTick?, reason"
                  + "（★ GM-only：householdId 缺席=改全局默认、给了=改家户覆盖（家户必须存在）；milliHoursPerTick 给了=upsert、"
                  + "缺席=删除该家户覆盖键（全局默认不允许删键 ⇒ 拒）；结果经 SocialData.withProvisioning 写回）"),
          Map.entry(
              "social.SubmitHouseholdWorkOrder",
              "orderId?, target(家户 id), reason(必填非空白), source{module, commandId?, actorId?},"
                  + " dryRun?(缺省 false；true ⇒ 具名拒),"
                  + " plan[{op:CREATE_HOUSEHOLD|SET_LOCATION|ADD_MEMBERS|REMOVE_MEMBERS|TRANSFER_MEMBERS|"
                  + "ADJUST_POPULATION|SET_VITAL_RATES, household|householdId?, from|to?, lotId?, count?,"
                  + " location?, profile?, vitalRates|rates?, sex?, ageAtAnchorDays?, anchorTick?, ageBracketId?, delta?}...]"
                  + "（★ 唯一家户人口变更受理口：从 base 顺序应用为一个工作副本，任一步失败 ⇒ 整单具名拒、不部分生效；"
                  + "成功 ⇒ SocialChangeSet.between 一条 revision；target 必须被 plan 引用；"
                  + "orderId 给定时为幂等键（重复提交 ⇒ 具名拒，标记事件 id=work-order:<orderId>）；"
                  + "ADD_MEMBERS 缺 lotId 时用 orderId 确定性派生 work-order:<orderId>:add:<序号>，两者都缺 ⇒ 拒；"
                  + "旧逐操作 social.* 命令保留并存）"),
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
              "economy.UpsertIndustry",
              "id(<kind>@<q>_<r>；新版本写进 kind 后缀：office_v2@0_0), name, regime, cycleDays(≥1),"
                  + " capacityPerUnit(非空且逐值>0), dailyInputPerUnit?, dailyLaborPerUnit?(≥0),"
                  + " laborPerUnit?(≥0), outputPerUnit?(空 map = 无商品产出), cycleInputPerUnit?,"
                  + " slots[{id,name,laborParticipationPerMille}],"
                  + " allocation(@class=split；两权重各≥0 且和=1000)"
                  + "（★ Z1a：id 不存在 ⇒ 创建（格必须已有已激活经济状态；同格同 kind 版本不得倒退/撞号）；"
                  + "id 已存在 ⇒ 仅当无 units/assetShares/relations 引用时原地全量替换，被引用 ⇒ 具名拒并指路"
                  + "新版本 id；逐值相同的重放 = 幂等 no-op；老 unit 引用老 id 零影响；GM-only）"),
          Map.entry(
              "economy.UpsertGovUnit",
              "govUnitId(必填；家户 = hh-gov-<govUnitId>), industryId(必填；必须已存在且 base kind=office，"
                  + "office/office_v2…), assets({AssetKind:数量}；逐值≥0，且逐 recipe capacityPerUnit kind 至少覆盖 1 单位规模),"
                  + " modeKey?(缺省 gov_service，稳定键), reason(必填)"
                  + "（★ Z1c：为 GOV 单位创建/补齐行政服务生产 unit——operator=HOUSEHOLD:hh-gov-<govUnitId>，"
                  + "unitId=ProductionUnitId.idOf(industryId, operator)；一次写 units(progress=0/cycle 空) + "
                  + "assetShares(OWNED 补足 owner=operator) + relations(空规则/residualOwner=operator/laborSource=WAGE)；不写"
                  + " operatorConditions/ProductionEnterprise。同载荷重放 = 幂等 no-op（空变更集）；已存在 unit/关系/份额"
                  + "与载荷字段冲突（modeKey 不一致、既有可用资产超过载荷量、既有工资规则…）⇒ 具名拒，不静默覆盖。"
                  + "守卫：economy 已激活；GOV 已在 economy.RegisterGovernment 登记（经济侧 governments 派生存 + "
                  + "classes 的 HouseholdEconomy 行）；hh-gov 在 Social 家户表；industry 存在且 base kind=office；"
                  + "产业格已激活；assets 覆盖 capacityPerUnit 1 单位规模。★ economy handler 因模块边界看不见 unit，"
                  + "app 的 GM 工具 simos.economy.upsertGovUnit 在 preview/apply 另做 unit+GovernmentFormation 预检；"
                  + "GM-only，无 CommandTargets/决策人版）"),
          Map.entry(
              "economy.SetGovServiceCommitment",
              "govUnitId(必填；家户 = hh-gov-<govUnitId>), householdId(必填；出劳动的家户，economy classes + "
                  + "Social 家户表都要有), laborMilli(必填 ≥ 0；0 = release 删除该承诺), activity?(可选；缺省按 "
                  + "operator=hh-gov-<id> 且 industry base kind=office 唯一解析该行政服务 unit；0/多个 ⇒ 具名拒并要求显式给), "
                  + "reason(必填)"
                  + "（★ Z3a：写/改/清一条 kind=GOV_SERVICE 的 HouseholdLaborCommitment；id 约定 "
                  + "gov-service:<activity>:<household>；同 (household, activity) 同量重放 = 幂等 no-op（空变更集）；"
                  + "已有 PRODUCTION 承诺的同 (household, activity) ⇒ upsert 具名冲突拒（先释放生产承诺，禁止静默改写 kind）；"
                  + "写入后 Σ全部承诺 ≤ 家户 HouseholdEconomy.laborMilli 预检失败 ⇒ 具名拒。守卫：GOV 已在经济侧登记"
                  + "（governments 派生存 treasury=hh-gov + hh-gov 的 classes 行 + Social 家户表）；activity 是该 GOV 的"
                  + "office unit；承诺家户在 economy classes + Social 家户表；0 人口家户写非 0 ⇒ 拒（0 可用于释放）。"
                  + "★ GM-only，无 CommandTargets；决策人工具/审批链/窄工具归 Z3c。★ GOV_SERVICE 不可缩、最高优先级"
                  + "（不参与死亡/预算比例缩；越预算 ⇒ LABOR_COMMITMENT_CONTRACT ERROR fail-closed））"),
          Map.entry(
              "unit.AssignGovPost",
              "unitId(必填), household(必填；必须在 Unit.households 里), role(SCRIBE|YAMEN|POST),"
                  + " tierId?(缺省空串 = legacy/未指派；非空必须命中 GovAdministrationPlan.postTiers，跨切片校验在 app 侧),"
                  + " level?(缺省 = 该 GOV 编制自身层级 CENTRAL|PROVINCE), head?(缺省 false)"
                  + "（★ Z4：只写 governmentPostsOfHousehold（同键整条替换）、绝不写 staff；"
                  + "岗位户不在 Unit.households ⇒ 具名拒并指路 unit.SetUnitHouseholds；"
                  + "决策人窄工具 simos.gov.assignPosts 直接提交、另走审批链）"),
          Map.entry(
              "unit.AssignExternalGovPost",
              "govUnitId(必填), householdId(必填；不得在 Unit.households 里，也不得在内部 householdPosts 里),"
                  + " role?(新建必填 SCRIBE|YAMEN|POST；改派既有外部岗位缺省沿用其角色),"
                  + " tierId?(缺省=改派时沿用既有/新建时空串 legacy；非空必须命中 GovAdministrationPlan.postTiers，"
                  + "跨切片校验在 app 侧), level?(缺省=改派时沿用既有/新建时该 GOV 编制层级 CENTRAL|PROVINCE),"
                  + " headOfGovernment?(缺省=改派时沿用既有/新建时 false), reason(必填非空白)"
                  + "（★ Z3d：只写 GovernmentFormation.externalPosts（同键整条替换）、绝不碰 Unit.households / Social"
                  + " 位置 / staff；外部户保留原单位/位置，只承接行政任务。unit 模块看不见 Social/economy ⇒"
                  + " \"家户存在（Social/economy 行）\"由 app 工具 simos.gov.openPostsToMarket 预检；GM 裸"
                  + " simos.command.submit 可绕过该预检（已记录的残余边界）。内外岗位互斥；家户在 Unit.households ⇒"
                  + " 具名拒并指路 unit.AssignGovPost。决策人窄工具 simos.gov.openPostsToMarket 直接提交、另走审批链）"),
          Map.entry(
              "gov.SetAdministrationPlan",
              "unitId(必填；GOV 单位 id), securityPlannedLaborMilli?, paperworkPlannedLaborMilli?,"
                  + " postTiers?[{tierId,securityWeightPerMille,paperworkWeightPerMille}](恰 3 档),"
                  + " securitySupplyStaticModifierPerMille?, paperworkSupplyStaticModifierPerMille?,"
                  + " securityDemandStaticModifierPerMille?, paperworkDemandStaticModifierPerMille?,"
                  + " supernumerarySqrtCoefficient?(k；≥0)"
                  + "（★ Z2：整体替换；缺省展开 计划0/默认3档/修正1000‰/k=1；单位必须存在且带 GovernmentFormation；"
                  + "逐值相同 = 幂等 no-op；★ GmOnly 标记只挡住令/RegisterEffect/决策人 catalog 三条路径，不拦命令总线 ——"
                  + "决策人窄工具 simos.gov.setEstablishment 直接提交同一命令并另走审批链）"),
          Map.entry(
              "gov.SetBudgetPolicy",
              "unitId(必填), orderedCategories?[{category(ADMIN_STIPEND|MILITARY_STIPEND|ADMIN_SALARY|"
                  + "DEBT_SERVICE|OTHER),minPerCycle?,capPerCycle?}](顺序即预算优先级),"
                  + " officialSalaryRule?{grainMilliPerCommittedHour?,silverMilliPerCommittedHour?},"
                  + " remittancePerMilleToSuperior?(0..1000‰；Z7c：周期末按本周期实收税上缴"
                  + " superiorGov 国库，0=抗税/不转移；不足只告警),"
                  + " mode?(PATCH|REPLACE，缺省 PATCH：缺省字段保留现值——只改 remittance 不清预算，orderedCategories:[] 才清空；"
                  + "REPLACE=旧整表替换：缺省 = 空表/0/0/0)"
                  + "（★ Z7e-3：双模；逐值相同 = 幂等 no-op；capPerCycle 缺省 = 不封顶；"
                  + "★ GmOnly 标记只挡住令/RegisterEffect/决策人 catalog 三条路径，不拦命令总线 ——"
                  + "决策人窄工具 simos.gov.setBudgetPolicy 直接提交同一命令并另走审批链）"),
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
              "economy.SetHouseholdClass",
              "household(家户 id), position(已存在的 ClassPositionId), originalPosition?, reason?, day?(≥0),"
                  + " at{q,r}?（★ P2-B：只改 classStandings 的当前职业；position 必须已在 classPositions；"
                  + "家户没有 standing ⇒ 新建（original=current=position）；追加参与集合走 "
                  + "economy.SetHouseholdParticipation；at 给了必须等于家户当刻居住格）"),
          Map.entry(
              "economy.SetHouseholdParticipation",
              "household, positions[ClassPositionId]?, modes[ProductionModeId]?, reason?, day?(≥0), at{q,r}?"
                  + "（★ P2-B：至少给 positions 或 modes；两者取并集，空数组 = 清空追加集合；"
                  + "位置与所属 mode 都必须已存在；当前位置自动并入、不能借本命令改当前职业；"
                  + "家户没有 standing ⇒ 先按旧 stratum 播种；at 给了必须等于家户当刻居住格）"),
          Map.entry(
              "economy.SetHouseholdLabor",
              "household, laborMilli?(≥0，毫小时/ tick), participationPerMille?(0..1000), at{q,r}?"
                  + "（★ P2-B：至少给一个；只改 ClassRow 的这两个字段；"
                  + "laborMilli 的常规来源是 Social 成员逐 tick 投影，下一轮推进可能覆盖；"
                  + "要持久改劳动时间需同时编辑 Social 成员；at 给了必须等于家户当刻居住格）"),
          Map.entry(
              "economy.UpdateDemand",
              "demand(需求 id), scope(HOUSEHOLD|HEX)?, household?, hex{q,r}?, commodity?, kind(RECURRING|ONE_OFF)?,"
                  + " unit(TOTAL|PER_CAPITA)?, quantityPerCycle?(>0), createdDay?(≥0), expiresDay?, priority?(≥0),"
                  + " source?, at{q,r}?（★ P2-B：部分更新，缺省字段逐值沿用；scope 换档必须给新档属主、"
                  + "不得同时给另一档；更新后属主格必须有该商品市价，否则拒并指名 economy.SetMarketPrice；"
                  + "只写 demands）"),
          Map.entry(
              "economy.RegisterGovernment",
              "govUnitId(必填), governmentId?(须逐字等于 gov-unit-<govUnitId>), household?(须逐字等于 hh-gov-<govUnitId>),"
                  + " nationRef(必填非空白), q, r(economy 落点), residence?(缺省 urban；大小写敏感；既有行缺席=保持), stratum?(缺省 official；既有行缺席=保持),"
                  + " population?/laborMilli?/participationPerMille?, classPosition?(须已存在), issuable?[币种],"
                  + " seignioragePerCycle?(≥0), debtIssuePerCycle?(≥0), reason?"
                  + "（★ P2-C：一个 GOV 单位恰一份政府 + 恰一个政府家户；身份由 govUnitId 派生；"
                  + "写 classes/classStandings/governments 三张表；缺省字段新建取 0/空集、重复登记逐值保留；"
                  + "要求 economy 已激活；GM-only；账户由同批 actor.EnsureHouseholdAccount 补）"),
          Map.entry(
              "economy.RegisterHousehold",
              "household(家户 id 文本), q, r(economy 落点视图 hex；必填), residence?(缺省 urban；大小写敏感),"
                  + " stratum?(缺省 landless_laborer), participationPerMille?(缺省 0；行已存在时只按显式值更新),"
                  + " reason?（★ P3：给任意 Social 家户补一条 HouseholdEconomy 经济行；行不存在 ⇒ 新建"
                  + " 0 人口/0 劳动/0 钱/空债/空需求；行已存在 ⇒ q/r/residence/stratum 与既有 view 不一致即拒、"
                  + "一致则幂等并只改显式参与率；不建 FlowRow/成员归属/政府/账户；GM-only；"
                  + "账户由同批 actor.EnsureHouseholdAccount 补）"),
          Map.entry(
              "economy.UpsertHouseholdPeriodicAdjustment",
              "id, payer(家户 id), payee?(缺省/null = sink), goodsPerCycle?{商品:>0 整数},"
                  + " moneyPerCycle?{币种:>0 整数}, reason(DeductionReason 规范小写字面量),"
                  + " periodDays(>0), phaseDay([0,periodDays)), startsOnDay(≥0),"
                  + " expiresOnDay?(缺省/null = 永久；给了 ≥ startsOnDay), policySource(非空白)"
                  + "（★ P4a：同 id 全量 upsert、不静默合并；goods/money 至少一腿；payer≠payee；GM-only）"),
          Map.entry(
              "economy.RemoveHouseholdPeriodicAdjustment",
              "id, reason?（★ P4a：按 id 删除；不存在 ⇒ 具名拒，不静默成功；GM-only）"),
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
                  + "crisisSignals）整条删除；世界级发行审计/在途货物/laborSupply/制度定义不动）"),
          Map.entry(
              "economy.UnitBorrow",
              "unitId, borrowerHousehold, lenderHousehold, unit(money|grain), principal(≥1),"
                  + " interestRatePerMille(≥0), nextDueTick(>当前 tick), terms?, reason?"
                  + "（★ P2-D / GM-only：单位向放贷方借入的**债务腿**；放贷方与借款方都必须是已知家户"
                  + "（有账户主体）——不再有 class-first ExternalLender。资金腿必须同批提"
                  + " actor.TransferAccounts {from:{household:lenderHousehold},"
                  + " to:{household:borrowerHousehold}}；单提本命令 = 有债无钱。nextDueTick 落"
                  + " DebtTerms.dueDay（合同身份/审计维），本批无到期催收）"),
          Map.entry(
              "economy.UnitRepay",
              "unitId, borrowerHousehold, lenderHousehold, unit(money|grain), amount(≥1), debtId?, reason?"
                  + "（★ P2-D / GM-only：单位向放贷方偿还的**债务腿**；同 (借款人,放贷方,unit) 有多条未结清"
                  + "合同时必须用 debtId 指明；amount ≤ 未结清本金、不超付。资金腿必须同批提"
                  + " actor.TransferAccounts {from:{household:borrowerHousehold},"
                  + " to:{household:lenderHousehold}}）"),
          Map.entry(
              "economy.GmAdjust",
              "adjustment(forgiveDebt|setLiquidationPolicy"
                  + "|upsertProductionMode|deactivateProductionMode|upsertClassStructure|upsertClassPosition"
                  + "|upsertProductionRelation|upsertAssetRule|upsertProductionOrganization|upsertCandidate"
                  + "|setOutputQuantity|clearOutputQuantity),"
                  + " parameters(JSON 对象), reason(必填非空白)"
                  + "（★ GM-only、只改源状态：白名单外/派生读数 ⇒ 拒；"
                  + "旧表两：forgiveDebt: debtContractId, amount?(缺省=全额本金，须 ≤ 本金)；"
                  + "setLiquidationPolicy: assetRuleId, maxLiquidatePerMille(0..1000), protectedReserve(≥0),"
                  + " priceSource(MARKET|AGREED|POLICY), policyValuePerUnitMilli(≥0；非 POLICY 必须 0),"
                  + " recipientRule(CREDITOR_FIRST|MARKET_FIRST)；引用不存在的 AssetRule ⇒ 拒；"
                  + "P7 生产方式编辑八 kind："
                  + "upsertProductionMode={id,name,version?,classStructureId}（version 必须推进；classStructureId 须已存在）；"
                  + "deactivateProductionMode={id}（被 classStructures/classPositions/productionOrganizations/"
                  + "assetRules/modeTransitions/pledges 任一引用 ⇒ 具名拒绝）；"
                  + "upsertClassStructure={id,modeId,positions?,defaultSharesPerMille?}（至少一项；位置 upsert 并同步全局 "
                  + "classPositions 与所有结构副本；份额给到即整体替换；新建必须给非空 positions）；"
                  + "upsertClassPosition={id,modeId,name?,relationToMeans?,laborRole?,surplusRole?,ruleExtensions?,"
                  + "classStructureId?}（位置不属于任何结构时必须给 classStructureId；既有位置的 modeId 不可改）；"
                  + "upsertProductionRelation={activity,operator?,inputSupplier?,rules?,residualOwner?,laborSource?}"
                  + "（activity 必须对应已存在 unit；operator 必须与 unit.operator 一致）；"
                  + "upsertAssetRule={modeId,assetKind,id?,isCoreMeans?,pledgeable?,liquidationPriority?,rentRule?,"
                  + "transferRule?}（id 须与 AssetRuleId.idOf 派生值一致；新建后四字段必填）；"
                  + "upsertProductionOrganization={id?,modeId,classPositionId,unitId?,organizer,laborSources?,"
                  + "assetSources?,inputSources?,outputOwnership,relationTemplateRef?,status,statusReason?}"
                  + "（ACTIVE/EXITING 必须有 unitId；SHORTAGE 必须具名 reason；引用 fail-closed）；"
                  + "upsertCandidate={id,version?,output?,outputPerUnit?,inputPerUnit?,requiredAssets?,laborPerUnit?,"
                  + "buildDays?,cycleDays?,regime?,laborSource?,acceptedRightKinds?,assetSource?,name?}"
                  + "（新建 output/outputPerUnit/cycleDays/regime 必填；修订须推进 version；"
                  + "★ ProductionCandidate 没有 modeId 字段，显式 modeId ⇒ 具名拒绝）；"
                  + "Z1 产品产出数量覆盖两 kind（只写 outputQuantityOverrides）："
                  + "setOutputQuantity={industryId,commodityId,quantity(0..1000000 整数；值=商品数量/单位规模)}"
                  + "（industryId 须已存在；commodityId 须在该产业 recipe().outputPerUnit() 产出键里、不开新商品；"
                  + "quantity 缺失/非整数/越界 ⇒ QUANTITY_OUT_OF_RANGE；缺省覆盖=配方默认）；"
                  + "clearOutputQuantity={industryId,commodityId}"
                  + "（industryId 须已存在；commodityId 须在配方产出键里；无既有覆盖 ⇒ NO_OVERRIDE_TO_CLEAR，"
                  + "不做静默幂等；回落配方默认））"),
          // ★★ A1（2026-10-08 汇率阶段 2 §3.1-3）：货币身份三件套 —— 注册面新增三个 type ⇒ 本表必须同批
          //   登记（构造成员守卫会逐条比对注册面，缺项当场抛）。三条都标 GmOnlyCommand：GM 的
          //   simos.command.submit 与窄工具照常可用，令 / RegisterEffect / 决策人命令目录三条路不放大。
          Map.entry(
              "economy.DefineCurrency",
              "govUnitId, currencyId, scale(≥0 最小单位精度), displayName(非空白), reason?"
                  + "（★ GM-only：一次写三处 —— 币种进 currencies、工具 <currencyId>-specie 进"
                  + " moneyInstruments、该 GOV 的 issuable 加它；币种 id 已存在 ⇒ currency-already-defined，"
                  + "已被别的政府发行 ⇒ currency-already-issued；币种 id 一经建立不可改（改名走"
                  + " economy.RenameCurrency）；同一条命令不能改别人已发行的币种（一币一发行人））"),
          Map.entry(
              "economy.RenameCurrency",
              "govUnitId, currencyId, displayName(非空白), reason?"
                  + "（★ GM-only：只改 currencies 里的显示名 —— 币种 id / 精度 / 工具表 / 任何余额、流水、"
                  + "债务、市场键都不动（不变量 I16）；不是该币种的发行政府 ⇒ not-issuer 具名拒；"
                  + "新显示名与现值逐字相同 ⇒ 拒，不做静默幂等）"),
          Map.entry(
              "economy.RecordMoneyIssuance",
              "govUnitId, currency, amountMilli(>0), kind?(缺省 FISCAL_ISSUE；创世给 INITIAL_ENDOWMENT), reason?"
                  + "（★ GM-only 裸审计原语：只写 moneyIssuances 一张表、**不动任何余额** —— 「国库余额增加」"
                  + "是 actor.AdjustAccounts 的活，两者必须同批提交（一批 = 一条 revision）；"
                  + "currency 不在该 GOV 的 issuable 里 ⇒ currency-not-issuable 具名拒；"
                  + "记录 id 是确定性派生 gov-issue-<政府>-<日>-<币种>-<序号>，不用随机 UUID）"),
          Map.entry(
              "actor.Seed",
              "mapId, rulesVersion, entries[{q, r, actors[{kind, id, label?}...],"
                  + "goods[{household, balances{键:整数}, money?, frozenBalances?, frozenMoney?}...]}...]"
                  + "（★ P2-A §13.3：账户主体只有家户、一本账——goods 行的 household 必须是载荷/现有家户集里的家户，"
                  + "悬空家户拒；位置从 Household.location 派生，不再写 location；"
                  + "旧 (owner,location) 键的账户随旧世界报废，不做迁移）"),
          Map.entry(
              "actor.AdjustAccounts",
              "entries[{household, goods{商品:有符号净增量}?, money{币种:有符号净增量}?}...]"
                  + "（★ P2-A：净增量账原语，账户主体只有家户。entries 必填非空；每项 household 必填；"
                  + "goods/money 至少一个非空、值不得为 0；同一 household 不得重复；"
                  + "缺账 + 纯正增量 ⇒ 新建，缺账 + 任何负增量 ⇒ 拒；"
                  + "负增量使余额 < 0 或侵占冻结额（可支配 = 余额 − 冻结）⇒ 拒；整条原子）"),
          Map.entry(
              "actor.DeductHouseholdStock",
              "entries[{household, goods{商品:>0}?, money{币种:>0}?, reason, detail?, toHousehold?}...]"
                  + "（★ 2026-10-09 通用扣除：reason 走封闭词表 military_salary|jurisdiction_tax|"
                  + "admin_upkeep|corvee；goods/money 至少一个非空、值必须 > 0；"
                  + "toHousehold 缺席 = 明确 sink、给出 = 原子转移；"
                  + "整条原子按载荷序应用；家户不存在 / 账户不存在 / 余额不足 / 侵占冻结（可支配 = 余额 − 冻结）"
                  + "/ reason 非法 ⇒ 全拒；GmOnly）"),
          Map.entry(
              "actor.RemitGovTreasury",
              "fromHousehold, toHousehold, grain?, cloth?, money?, reason?"
                  + "（★ P2-A §13.3：GOV 国库 = 政府家户账户；源/目标都是家户 id，两个不得相同；"
                  + "三个金额可选缺省 0、不得为负、至少一个 > 0；源账必须存在、逐资源走 AvailableStock"
                  + "（可支配 = 余额 − 冻结）判足量；整条原子，任一违例全拒；"
                  + "★ P2-C：决策令路径的层级校验已重建 = 只能从自己 GOV 的政府家户上缴给 superiorGov 的政府家户，"
                  + "且双方位置须在出令决策人 actor 可达面内；GM 工具（simos.gov.remit/pay）不受层级限制）"),
          Map.entry(
              "actor.EnsureHouseholdAccount",
              "household(家户 id 文本), reason?"
                  + "（★ P2-C：给家户补一本零余额账户，幂等；只动 actor.accounts，不碰 actors/meta/余额；"
                  + "GM-only；供 GOV 组合工具在 social.CreateHousehold + economy.RegisterGovernment 之后开户）"),
          Map.entry(
              "actor.ClearRegion",
              "regionId（必填；必须在当前 map.regions() 里）"
                  + "（★ GM-only；★★ P2-A 具名缺口：账户键不再带 location ⇒ 本命令不再能映射"
                  + "「目标 Region 的账本」，本批只校验 region 存在性、不改账本/主体；"
                  + "区域清账改由组合根按家户集协调（P2-F））"),
          Map.entry("sd.CreateNation", "nationId, name, homeRegionId, adminBudgetPerTick"),
          Map.entry(
              "sd.CreateArmy",
              "armyId, masterGovUnitId?, rootUnitId, name"
                  + "（★ masterGovUnitId 缺省 = 未认主子，给了必须存在且带 GovernmentFormation；"
                  + "旧 nationId 键已拒并指路 masterGovUnitId）"),
          Map.entry(
              "sd.SetArmyMasterGov",
              "armyId, masterGovUnitId?"
                  + "（★ 已存在 Army 的主子改派/解除：masterGovUnitId 缺席/null/空串 = 解除认领；"
                  + "给了必须存在且带 GovernmentFormation；armyId 不存在 ⇒ 具名拒；只改 sd 侧，不碰 unit 侧 ArmyFormation；"
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
          Map.entry(
              "sd.SetDiplomaticRelation",
              "from, to, kind?, text, tick?（★ D5 / D-003：upsert 一条有向外交关系边；from≠to 且两端必须是已存在的"
                  + " Nation；kind 自由文本可空——「称臣纳贡」只是它的一个取值，本命令不解释、不写死贡额/周期/违约；"
                  + " text 非空白自然语言、谈判状态记这里；tick 缺省=世界当前 tick、不得记在未来；同 (from,to) 再调一次=更新）"),
          Map.entry(
              "sd.RecordDiplomaticEvent",
              "eventId?, tick?, participants[字符串...], text（★ D5 / D-005：追加一条外交事件记录；"
                  + "participants ≥2 且不得重复；text 非空白自然语言；tick 缺省=世界当前 tick、不得记在未来；"
                  + "eventId 缺省按 tick 合成（diplomatic-event:<tick>#<该 tick 已有事件数>），撞车 ⇒ 拒）"),
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
              "sd.UpsertDecisionPacket",
              "id, branch, tick, proposerId, status(DRAFT|PENDING|APPROVED|REJECTED|MERGED|PARTIALLY_APPROVED),"
                  + " intent?, createdAtRevision, decidedBy?, decidedAtRevision?, reasonInfoId?, decisionNote?,"
                  + " calls[{callIndex, toolName, argsJson, targets[{namespace,path}], previewJson, draftChecks[],"
                  + " status(PENDING|APPROVED|REJECTED|MERGED), mergedPlanId?, outcomeJson?}]"
                  + "（★ D2/D3：整包 upsert；同 id 幂等替换；不对外窄工具，由 simos.sd.propose / intent /"
                  + " 决策效果执行器内部提交）"),
          Map.entry(
              "sd.SubmitDecisionPacket",
              "id, proposerId（★ D2：DRAFT → PENDING；只被 simos.sd.packet.submit 调用）"),
          Map.entry(
              "sd.DecideDecisionPacket",
              "id, decision(APPROVE|DENY|MERGE), decidedBy, note?, callIndexes[]?, mergedPlanId?"
                  + "（★ D2/D3：GM 整包/逐 call true-positive 裁决；decidedBy 由 simos.gm.packet.decide 从身份派生；"
                  + "MERGE 必带 mergedPlanId 且目标 plan 必须已存在，callIndexes 与 MERGE 互斥）"),
          Map.entry(
              "sd.UpsertMergedEffectPlan",
              "id, tick, participantIds[决策人 id…]?, orderedEffects[{toolName, argsJson, sourceCallRefs[]?}…],"
                  + " sources[字符串…]?, reasonInfoId?, outcome?"
                  + "（★ D3：合并效果计划整包 upsert，同 id 幂等替换；不对外窄工具，由 simos.gm.mergedPlan.upsert /"
                  + " 决策效果执行器 outcome 回写内部提交）"),
          Map.entry(
              "sd.SetDirectiveStatus",
              "directiveId, status(EXECUTED|CANCELLED)（★ 只允许 ISSUED → 二者之一，只由"
                  + " sd.AdjudicateTick 内部编排产生；不对外提供窄工具）"),
          Map.entry(
              "actor.TransferAccounts",
              "from{household}, to{household}, goods{商品:>0}?, money{币种:>0}?, reason?"
                  + "（★ P2-A：两个**家户**账户间原子转移（账户键 = 家户身份，不再带格）；"
                  + "源账必须存在且逐资源可支配足够；目标缺失 ⇒ 按转入量新建；冻结额不动；"
                  + "至少一个维度非空、0 不得出现；GmOnly。★ actor.MoveAccount 已随 P2-A 退役）"),
          Map.entry(
              "social.MoveCity",
              "id, at{q,r}, region?"
                  + "（★ P1.2：改城市落点；region 缺席=保持原归属、null=清空、字符串=设值。"
                  + "城市身份不变 ⇒ urban:<cityId>: 批次的城镇人口归属不变；"
                  + "物理人口随迁请另发 social.MovePopulationLots；GmOnly）"),
          Map.entry(
              "social.DeleteCity",
              "id, deletePopulation?（缺省 false）"
                  + "（★ P1.2：城市仍挂着 urban:<id>: 城镇批次时，缺省严格拒绝；"
                  + "deletePopulation=true 才连带删除这些批次及其在所有家户 memberLots 里的成员关系（破坏性清理）；GmOnly）"),
          Map.entry(
              "social.MovePopulationLots",
              "fromHouseholdId? 或 from{q,r}; toHouseholdId? 或 to{q,r}; lots?[批次 id…]; reason"
                  + "（★ P1.2：源/目标两类各必须二选一。lots 缺省=源范围全部批次；"
                  + "目标格未给家户时：恰一个 HEX 家户 ⇒ 并入，零个 ⇒ 新建合成家户，多个 ⇒ 拒；"
                  + "整条原子、批次 id 不变、只换所属家户；GmOnly）"),
          Map.entry(
              "map.MergeRegions",
              "targetRegionId, sourceRegionIds[regionId…]"
                  + "（★ P1.2：目标保留身份/名称/meta，hexes 取并集，源区域删除；"
                  + "只改 map.regions；jurisdiction/城市/税率/编制由 app 组合根 submitBatch 协调；GmOnly）"),
          Map.entry(
              "map.SplitRegion",
              "sourceRegionId, keepSource?, parts[{regionId,name,hexes[{q,r}…],meta?}…]"
                  + "（★ P1.2：keepSource=false（缺省）时 parts 必须恰好覆盖源全部格、源删除；"
                  + "true 时 parts 是源的真子集、源保留剩余格；新 id 不得已存在/重复；GmOnly）"),
          Map.entry(
              "map.ReassignHexes",
              "toRegionId, fromRegionIds[regionId…], hexes[{q,r}…]"
                  + "（★ P1.2：逐格从所有 fromRegions 删除、往目标加入；每个 hex 必须至少属于一个源；"
                  + "任一源被划空 ⇒ 拒并指向 Merge/Split/Delete；重叠不报错；GmOnly）"),
          Map.entry(
              "sd.DeleteNation",
              "nationId, clearDiplomaticReferences?"
                  + "（★ P1.2：缺省 false = 仍有决策人绑定 / 外交关系边 / 外交事件 / map 上 nation:<id> tag ⇒ "
                  + "逐类具名拒绝，不静默级联；true = 连该国的外交关系与外交事件一起删，"
                  + "但决策人与 map tag 仍须先清；GM-only）"),
          Map.entry(
              "army.RecordCombat",
              "id, kind（自定义交战状态自由文本，如野战/轰城）, tick?, hex{q,r}, participants[unitId...]"
                  + "（至少一个、不重复）, text（自然语言过程，非空白）,"
                  + " initialStage?{id,name,participants?,text,outcomes?[{id,label,weight,losses?["
                  + "{unit,manpower?[{type,amount(有符号)}],equipment?[{type,amount(有符号)}]}]}]}}"
                  + "（★ 阶段 D4 / D-009 补裁 + D-010：单 tick 单场交战记录 + 初始阶段；initialStage 缺省 ="
                  + " handler 合成 id=start/name=初始阶段/participants=记录级/text=记录级/outcomes 空表；"
                  + "tick 缺省 = 当前 tick、不得记在未来；同 id 已存在 ⇒ 具名拒（记录 id 是一次性身份，阶段演进走"
                  + " army.AppendCombatStage / army.ResolveCombatStage）；weight 必须 > 0、阶段/结局 id 不得重复；"
                  + "参与者不查 unit 切片是否存在；★ GmOnly：决策人不得凭空写战果）"),
          Map.entry(
              "army.AppendCombatStage",
              "combatId, stage{id,name,participants?,text,outcomes?[{id,label,weight,losses?["
                  + "{unit,manpower?[{type,amount(有符号)}],equipment?[{type,amount(有符号)}]}]}]}}"
                  + "（★ 阶段 D4：给已存在的交战记录追加一个阶段——同 id 记录整条替换、落新 revision；"
                  + "阶段 id 已存在 ⇒ 具名拒；participants 缺省 = 记录级；outcomes 缺省 = 空表；"
                  + "阶段载荷不得携带 selectedOutcomeId/rollSeed（判定走 army.ResolveCombatStage）；★ GmOnly）"),
          Map.entry(
              "army.ResolveCombatStage",
              "combatId, stageId, outcomeId?, seed?"
                  + "（★ 阶段 D4：给一个阶段投骰判定并写进记录；只给 outcomeId = 不投骰（记录 rollSeed 空）；"
                  + "只给 seed = 用该 seed 投骰；都不给 = 由 combatId+stageId+tick+概率表 确定性派生 seed；"
                  + "两者同给 = 按 seed 复核 outcome（不一致 ⇒ 具名拒）；已判定的阶段不可重复判定；"
                  + "本命令只写记录、不动单位——损失写进单位由组合工具 simos.army.resolveCombat 用同批"
                  + " unit.AdjustComposition 完成；★ GmOnly）"));

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
