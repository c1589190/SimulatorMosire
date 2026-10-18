package io.mosire.simos.economy.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomyLog;
import io.mosire.simos.economy.EconomyLogSource;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.ClassPositionId;
import io.mosire.simos.economy.api.id.ClassShareId;
import io.mosire.simos.economy.api.id.ClassStructureId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CrisisSignalId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.DebtContractId;
import io.mosire.simos.economy.api.id.DemandId;
import io.mosire.simos.economy.api.id.GovernmentId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.InstrumentId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.HouseholdLaborCommitment;
import io.mosire.simos.economy.api.labor.LaborCommitmentKind;
import io.mosire.simos.economy.api.market.MarketMandateId;
import io.mosire.simos.economy.api.market.MarketOrderKind;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.api.stock.PeriodicHouseholdAdjustmentId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AssetOccupancyGrantId;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.HouseholdDebtReference;
import io.mosire.simos.economy.model.HouseholdEconomy;
import io.mosire.simos.economy.model.InventoryReservationId;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.economy.model.WageAnchorKey;
import io.mosire.simos.economy.model.WageCarryKey;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;

/** economy codec 的共享 mapper / 值类型兼容层 / 入口编解码 helper（P0.5-C2）。 */
final class EconomyCoreCodecs {

  /**
   * ★★ Z1 载入守卫的日志口（唯一 logger 拼写仍取自 {@link EconomyLog}；来源 = {@link
   * EconomyLogSource#ECONOMY_PRODUCTION_EFFICIENCY} 的契约故障事件）。载入守卫失败是**契约/一致性故障**（§8： {@code
   * PRODUCTION_EFFICIENCY_CONTRACT} ERROR），不是业务拒绝。
   */
  static final Logger LOG = EconomyLog.command();

  /**
   * ★★ <b>"纯绑定" mapper</b>：只带键反序列化器（含旧 {@code CohortKey} 键 → {@link HouseholdId} 的识别），
   * <b>不带值类型兼容层</b> —— 兼容层把旧节点整形成新节点后交给它绑定（避免"兼容层再进兼容层"的递归）。
   *
   * <p>★ 先例：H2 的补偿规则兼容层就是"整形后交给 {@code PLAIN}"；S1 的旧档迁移沿用同一分工。 ⇒ "一条记录怎么从 JSON
   * 造出来"永远只有<b>一处</b>拼写点（Jackson 的 record 绑定），兼容层只负责改节点。
   */
  static final ObjectMapper PLAIN = withEconomyMixins(SimosObjectMapper.create(keyModule()));

  /** 本模块唯一的一台 mapper：共享基座 + 键反序列化器 + S1/H2 的值兼容层。 */
  static final ObjectMapper MAPPER =
      withEconomyMixins(SimosObjectMapper.create(keyModule(), compatModule()));

  /**
   * ★ 把 {@code EconomyChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code LedgerCodec} 同制）：Jackson 会把 {@code
   * isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。 {@code isEmpty} 是派生判断不是状态，**不进线格式**；mixin
   * 放本类（mapper 与 mixin 同处一地、谁也丢不了），领域类型保持零 Jackson 注解（{@code AllocationRule} 的 sealed
   * 多态注解除外——那是往返的硬前提；★ 2026-10-09 选项 A 追加第二个例外：{@code HouseholdEconomy} 上的
   * {@code @JsonIgnoreProperties("debts")} —— 退役字段的具名兼容必须落在**类型**上，因为重放走 Core 的第四台 mapper、
   * 不经过本层，见那个注解的注释；★ 2026-10-11 W1f 追加第三个例外：{@link
   * io.mosire.simos.economy.model.MarketTable#isEmpty()} 上的 {@code @JsonIgnore} —— W1 新引入的记录把
   * {@code isEmpty()} 当成属性 {@code empty} 写了出去（本类这层 mixin 够不着它：它不是 {@code ChangeSet}，而第四台 mapper 与裸
   * mapper 同样看不见本层的 mixin），故只能落在类型上，见该方法的线格式契约注）。
   */
  static ObjectMapper withEconomyMixins(ObjectMapper mapper) {
    mapper.addMixIn(EconomyChangeSet.class, EconomyChangeSetMixin.class);
    mapper.addMixIn(EconomyMeta.class, EconomyMetaMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class EconomyChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  /**
   * ★★ P10.1：{@link EconomyMeta#isCurrentRuntimeVersion()} 是**派生判据**（不是状态），Jackson 会把 {@code
   * isXxx()} 当属性写出，严格读入随即因未知键炸 ⇒ 与 {@code isEmpty()} 同款在 mixin 里摘掉。
   */
  abstract static class EconomyMetaMixin {

    @JsonIgnore
    abstract boolean isCurrentRuntimeVersion();
  }

  static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("economy-json-keys");
    module.addKeyDeserializer(IndustryId.class, keyDeserializer(IndustryId::parse));
    // ★★ S1：classes/flows 的键 = HouseholdId。旧档的键是 CohortKey 规范串 ⇒ 这里做一次"旧视图 → ofLegacy"
    //   识别（新档 id 的 parse 是恒等）。识别器同时注册为**值**反序列化器（HouseholdEconomy.id；旧 Debt.debtor/creditor
    // 由旧档迁移层手工解析）。
    module.addKeyDeserializer(
        HouseholdId.class, keyDeserializer(EconomyCoreCodecs::legacyAwareHouseholdId));
    module.addDeserializer(HouseholdId.class, new HouseholdIdDeserializer());
    // ★★ S1：HouseholdId 迁入无 Jackson 注解的 simos-social-api ⇒ 值侧必须显式写回裸字符串（旧 @JsonValue
    //   行为），否则 HouseholdEconomy.id / HouseholdLaborCommitment.household 等值会变成
    // {"value":…}，而上面的值反序列化器
    //   只认字符串（写出来的档自己读不回）。键侧仍走 toString/parse 配对。
    module.addSerializer(HouseholdId.class, new HouseholdIdSerializer());
    // ★★ E4a：债务合同表的新键（toString/parse 互逆，只需读侧）。
    module.addKeyDeserializer(DebtContractId.class, keyDeserializer(DebtContractId::parse));
    module.addKeyDeserializer(CommodityId.class, keyDeserializer(CommodityId::parse));
    // ★★ E4c：FlowRow.repaidMoney 的键 = CurrencyId（逐币种偿还读数）—— 与 CommodityId 同形，只需读侧。
    module.addKeyDeserializer(CurrencyId.class, keyDeserializer(CurrencyId::parse));
    // ★ R2 起是两张新表的键：laborSupply（PeopleLotId → LaborSupply）与 allocations（LaborAllocationId
    //   → HouseholdLaborCommitment）。两者都重写了 toString()（= 裸值）并与各自的 parse 互为逆，故只需读侧。
    //   ★ Z1b：allocations 的值新增 kind（PRODUCTION/GOV_SERVICE）；旧档缺该键由 compatModule 的
    //   LegacyHouseholdLaborCommitmentDeserializer 与快照/变更集两条读侧整形统一补 PRODUCTION（闭枚举，未知值
    // fail-closed）。
    module.addKeyDeserializer(PeopleLotId.class, keyDeserializer(PeopleLotId::parse));
    module.addKeyDeserializer(LaborAllocationId.class, keyDeserializer(LaborAllocationId::parse));
    // ★★ S1：assetShares 表的键（R3B.1 起 = AssetShareId；旧 use-… 串 opaque 可读）。
    module.addKeyDeserializer(AssetShareId.class, keyDeserializer(AssetShareId::parse));
    // ★★ R3B.2：units 表的键 = ProductionUnitId；同时注册值侧反序列化器（旧档/手写可能写裸字符串）。
    module.addKeyDeserializer(ProductionUnitId.class, keyDeserializer(ProductionUnitId::parse));
    module.addDeserializer(
        ProductionUnitId.class, new EconomyProductionCodecs.ProductionUnitIdDeserializer());
    // ★★★ D-032 C2：ProductionRoleId 是 D2 §5.1 的稳定身份，线格式 = 裸字符串（与其余稳定 id 同制）。
    //   生产名册的 participant.role、HouseholdLaborCommitment.productionRole 都走这一对读写；
    //   注册在 keyModule 里 ⇒ PLAIN / MAPPER 同源，写出去的档自己读得回。
    module.addKeyDeserializer(ProductionRoleId.class, keyDeserializer(ProductionRoleId::parse));
    module.addDeserializer(
        ProductionRoleId.class, new EconomyProductionCodecs.ProductionRoleIdDeserializer());
    module.addSerializer(
        ProductionRoleId.class, new EconomyProductionCodecs.ProductionRoleIdSerializer());
    // ★★ R3B.1：OwnershipStake 值内的 id 可能是本 codec 旧字节的 {"value":"use-…"}，也可能是裸字符串
    //   （手写/外部工具/旧别名）⇒ 值侧显式收两种（keyModule 同时供 PLAIN 使用）；其它形状仍 fail-closed。
    module.addDeserializer(AssetShareId.class, new AssetShareIdDeserializer());
    // ★★ H4：市场表的键 = **格**（{@code 0_0}）—— 本模块第一次把 HexCoord 当键用（见类注）。
    module.addKeyDeserializer(HexCoord.class, keyDeserializer(HexCoord::parse));
    // ★★ M2.4：在途批次表的键 = ShipmentId（{@code sh-<day>-<seq>}）—— 与上面同一条口径：toString/parse 互逆，只需读侧。
    module.addKeyDeserializer(ShipmentId.class, keyDeserializer(ShipmentId::parse));
    // ★★ R4-E2：demands 表的键（opaque 裸值，与 parse 互为逆，只需读侧）。
    //   ★★ B2c：`candidates` 表已随候选面退役删除 ⇒ 它的键反序列化器一并删除。
    module.addKeyDeserializer(DemandId.class, keyDeserializer(DemandId::parse));
    // ★★ E1：modes / classStructures / classPositions / classStandings（键 = HouseholdId）四张新表，
    //   以及 ClassStructure.positions / defaultSharesPerMille 与
    // HouseholdClassMembership.retainedShares
    //   三个**嵌套** ClassPositionId 键表 —— 都必须在这里注册键反序列化器（写侧走各自 toString）。
    module.addKeyDeserializer(ProductionModeId.class, keyDeserializer(ProductionModeId::parse));
    module.addKeyDeserializer(ClassStructureId.class, keyDeserializer(ClassStructureId::parse));
    module.addKeyDeserializer(ClassPositionId.class, keyDeserializer(ClassPositionId::parse));
    // ★★ E2：productionOrganizations / assetRules 两张新表的键（opaque 裸值，与各自 parse 互为逆，只需读侧）。
    //   ★ E2 的 productionOrganizations 与 P10.1 的 merchantFirms 曾共用本注册点；merchantFirms 已于 M-A1 退役
    //     ⇒ 现在只有 productionOrganizations 用它（键注册点保留，不是"没人用的注册"）。
    module.addKeyDeserializer(
        ProductionOrganizationId.class, keyDeserializer(ProductionOrganizationId::parse));
    module.addKeyDeserializer(AssetRuleId.class, keyDeserializer(AssetRuleId::parse));
    // ★★ E3：governments / moneyIssuances 两张新表的键（opaque 裸值，与各自 parse 互为逆，只需读侧）。
    module.addKeyDeserializer(GovernmentId.class, keyDeserializer(GovernmentId::parse));
    module.addKeyDeserializer(MoneyIssuanceId.class, keyDeserializer(MoneyIssuanceId::parse));
    // ★★ E5a：crisisSignals 的键 = CrisisSignalId（{@code crisis-<q>_<r>-<KIND>}，与 parse 互为逆，只需读侧）。
    module.addKeyDeserializer(CrisisSignalId.class, keyDeserializer(CrisisSignalId::parse));
    // ★★ E6a：modeTransitions / classShares 两张新表的键（opaque 裸值，与各自 parse 互为逆，只需读侧）。
    module.addKeyDeserializer(ModeTransitionId.class, keyDeserializer(ModeTransitionId::parse));
    module.addKeyDeserializer(ClassShareId.class, keyDeserializer(ClassShareId::parse));
    // ★★ P4a：periodicAdjustments（第 31 个组件）的键 = PeriodicHouseholdAdjustmentId；
    //   规则值 HouseholdPeriodicAdjustment 走 Jackson record 绑定，字段不变量由构造期守卫判，
    //   旧档缺该组件键 ⇒ EconomyData 构造期归一成空表。
    module.addKeyDeserializer(
        PeriodicHouseholdAdjustmentId.class, keyDeserializer(PeriodicHouseholdAdjustmentId::parse));
    // ★★ A1：currencies / moneyInstruments 两张新表的键（opaque 裸值，与各自 parse 互为逆，只需读侧）。
    //   值 CurrencyDef / MoneyInstrument 走 Jackson 的 record 绑定；旧档缺这两个组件键 ⇒ EconomyData 构造期
    //   归一到旧世界默认词表（silver / silver-specie），读出来与 A1 之前逐值相同。
    module.addKeyDeserializer(CurrencyId.class, keyDeserializer(CurrencyId::parse));
    module.addKeyDeserializer(InstrumentId.class, keyDeserializer(InstrumentId::parse));
    // ★★ B2（2026-10-08）marketZones（第 36 个组件）的键（opaque 裸值，与 parse 互为逆，只需读侧）。
    //   值 MarketZone 走 Jackson 的 record 绑定；成员格集合是 Set<HexCoord> ⇒ 值侧走 HexCoord 的 record 绑定
    //   （q/r 两个 int），迭代序由 MarketZone 构造期的规范序（(q,r) 升序）保证 ⇒ 字节级往返稳定。
    //   旧档缺该组件键 ⇒ EconomyData 构造期归一成空表（市场区退回派生路径，既有世界逐值不变）。
    module.addKeyDeserializer(MarketZoneId.class, keyDeserializer(MarketZoneId::parse));
    // ★★ 2026-10-09 选项 A：householdDebtRefs（第 37 个组件）的键 = HouseholdDebtReference（{@code 家户@合同}
    //   复合键，toString/parse 互逆）。值 = 标记位 Boolean，走 Jackson 的原生布尔绑定。
    //   旧档缺该组件键 ⇒ EconomyData 构造期归一成空表，再由 DebtReferenceReconciler 从 debtContracts 重建
    //   （旧世界逐值不变）；旧档行内那份 {@code classes[].debts} 由 {@code HouseholdEconomy} 上的
    //   具名 {@code @JsonIgnoreProperties("debts")} 吞掉（★ 必须落在类型上：重放走 Core 的第四台 mapper，
    //   不经过本 codec 的整形层 —— 见那个注解的注释）。
    module.addKeyDeserializer(
        HouseholdDebtReference.class, keyDeserializer(HouseholdDebtReference::parse));
    module.addKeyDeserializer(WageCarryKey.class, keyDeserializer(WageCarryKey::parse));
    // ★★★ D-032 P4c3：U20 工资锚 productionWageAnchors 的键 = WageAnchorKey。
    //   三字段长度前缀编码（toString/parse 互逆）；值 ProductionWageAnchor 走 Jackson record 绑定。
    module.addKeyDeserializer(WageAnchorKey.class, keyDeserializer(WageAnchorKey::parse));
    // ★★★ D-032 D4-Ib1：第 36/37 组件的键。两者 toString()/parse() 互逆；写侧默认 toString，
    //   读侧在此注册（与其余稳定 id 同制，不引入旧档兼容/键归一）。
    module.addKeyDeserializer(
        AssetOccupancyGrantId.class, keyDeserializer(AssetOccupancyGrantId::parse));
    module.addKeyDeserializer(
        InventoryReservationId.class, keyDeserializer(InventoryReservationId::parse));
    // ★★ R1（2026-10-09）govMarketMandates（第 39 个组件）的键 = MarketMandateId（opaque 裸值，与 parse 互逆，
    //   只需读侧）。值 GovernmentMarketMandate 走 Jackson 的 record 字段显式绑定；旧档缺该组件键 ⇒ EconomyData
    //   构造期归一成空表（= 没有任何授权 ⇒ 国库户在商品市场上零订单，既有世界逐值不变）。
    module.addKeyDeserializer(MarketMandateId.class, keyDeserializer(MarketMandateId::parse));
    // ★★ 追加裁定 12（2026-10-12 §21.9 D-1）：Government.portPolicy 的 currencyRules 是
    //   "币种 → 挂单类型 → 四元组"的**嵌套**表 ⇒ 内层键 = MarketOrderKind。它是**枚举**，Jackson 对枚举键的
    //   默认读写都走 name()（LENDING），而本仓的规范字面量是小写下划线（lending，与载荷/读数同字面）⇒
    //   **读写两侧都注册**（写侧 value() / 读侧 MarketOrderKind#parse），让线格式与载荷逐字一致、写出来的档自己读得回。
    //   （这一段与 gov 侧 GovCodec 原有的同款注册等价；政策搬进 economy 后那段随 gov 的删除一并消失。）
    module.addKeyDeserializer(MarketOrderKind.class, keyDeserializer(MarketOrderKind::parse));
    module.addKeySerializer(MarketOrderKind.class, EconomyMarketCodecs.orderKindKeySerializer());
    return module;
  }

  /** ★★ S1/H2/R3B.1 的值类型兼容层：旧形状整形成新形状之后交给 {@link #PLAIN} 绑定（避免递归）。 */
  static SimpleModule compatModule() {
    SimpleModule module = new SimpleModule("economy-json-legacy-values");
    // ★★ H2：补偿规则的旧线格式（单个 `basis` → `pool` + `weight`）。
    module.addDeserializer(CompensationRule.class, new CompensationRuleDeserializer());
    // ★★ S1：HouseholdEconomy/FlowRow 的旧键 `key`（CohortKey）→ `id` + `view`；HouseholdLaborCommitment 缺
    // household。
    module.addDeserializer(HouseholdEconomy.class, new LegacyHouseholdEconomyDeserializer());
    module.addDeserializer(FlowRow.class, new LegacyFlowRowDeserializer());
    module.addDeserializer(
        HouseholdLaborCommitment.class, new LegacyHouseholdLaborCommitmentDeserializer());
    // ★★ R3B.1：旧档组件键 `useRights` → `assetShares`，旧值 `activity/holder` → `industry/owner+operator`。
    //   两条都在 codec 边缘做**显式节点整形**，领域类型保持零 Jackson 注解；Delegate 给 PLAIN 以免递归。
    module.addDeserializer(EconomyData.class, new LegacyEconomyDataDeserializer());
    module.addDeserializer(EconomyChangeSet.class, new LegacyEconomyChangeSetDeserializer());
    return module;
  }

  /**
   * ★ 旧 {@code CohortKey} 规范串（含 {@code |}、且非 legacy- 前缀）⇒ {@code HouseholdIds.ofLegacy}；其余原样 parse。
   */
  static HouseholdId legacyAwareHouseholdId(String text) {
    if (text != null && !text.startsWith(HouseholdIds.LEGACY_PREFIX) && text.indexOf('|') >= 0) {
      return HouseholdIds.ofLegacy(CohortKey.parse(text));
    }
    return HouseholdId.parse(text);
  }

  /**
   * ★★ <b>一条补偿规则的读侧兼容层</b>（H2；裁定 D5-B 的"旧档迁移在 codec 边缘"）。
   *
   * <p>★★ <b>它为什么必须存在</b>：关系表住在 {@code EconomyData} 里 ⇒ <b>每一条已落盘的 revision</b> 里都存着 {@code
   * {"basis":"GROSS_OUTPUT", …}}。H2 把它拆成两个字段之后，默认的 record 绑定会当场炸（{@code basis} 是未知属性 + {@code pool}
   * 缺失 ⇒ 构造期守卫抛）—— 那等于<b>历史 revision 全部读不回来</b>。
   *
   * <p>★★ <b>翻译而不是猜</b>：旧字面量经 {@code Basis.pool()} / {@code Basis.weight()} 无损映射（映射表在 {@code Basis}
   * 的类注里）；★ <b>同时给了 {@code basis} 与 {@code pool}/{@code weight} ⇒ 抛</b>（同一件事的两处拼写不一致时， 没有哪一处能判谁对）。★
   * 货币档缺 {@code currency} 键 ⇒ 补<b>出厂货币</b>（旧档没有这一维；唯一拼写点在 {@code
   * RegimeRelations.DEFAULT_CURRENCY}）。
   *
   * <p>★ <b>新形状不在这里手写</b>：把节点整形成新形状之后交给一台<b>不带本兼容层</b>的 mapper（{@link #PLAIN}）—— 于是"一条规则怎么从 JSON
   * 造出来"只有<b>一处</b>拼写点（Jackson 的 record 绑定），兼容层只负责改节点。
   */
  static final class CompensationRuleDeserializer extends JsonDeserializer<CompensationRule> {

    @Override
    public CompensationRule deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!(raw instanceof ObjectNode node)) {
        throw new IllegalStateException("补偿规则必须是 JSON 对象: " + raw);
      }
      return decodeRule(node);
    }
  }

  /**
   * ★ <b>旧档 → 新档的节点整形</b>（唯一读侧翻译点；见 {@link CompensationRuleDeserializer}）：
   *
   * <ol>
   *   <li>有 {@code basis} ⇒ 拆成 {@code pool} + {@code weight}（并把 {@code basis} 摘掉，否则严格读入会因为未知属性炸）；
   *   <li>货币档缺 {@code currency} ⇒ 补出厂货币（旧档的"1000 毫钱"没有说是哪种钱）；
   *   <li>其余照旧交给 {@link #PLAIN}（Jackson 的默认 record 绑定 + 契约自己的构造期守卫）。
   * </ol>
   */
  static CompensationRule decodeRule(ObjectNode node) {
    if (node.hasNonNull("basis")) {
      if (node.hasNonNull("pool") || node.hasNonNull("weight")) {
        throw new IllegalStateException(
            "补偿规则不得同时给 basis 与 pool/weight（H2 起 pool+weight 是新档、basis 是旧档）: " + node);
      }
      Basis basis = Basis.parse(node.get("basis").asText());
      ObjectNode migrated = node.deepCopy();
      migrated.remove("basis");
      migrated.put("pool", basis.pool().name());
      migrated.put("weight", basis.weight().name());
      node = migrated;
    }
    if (RuleType.parse(textOf(node, "type")).money() && !node.hasNonNull("currency")) {
      ObjectNode withCurrency = node.deepCopy();
      withCurrency.put("currency", RegimeRelations.DEFAULT_CURRENCY.value());
      node = withCurrency;
    }
    try {
      return PLAIN.treeToValue(node, CompensationRule.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("补偿规则解码失败: " + node, e);
    }
  }

  /** 必填文本字段（缺键 / 非文本 ⇒ 抛；本层只服务旧档整形，故消息直说"补偿规则的字段"）。 */
  static String textOf(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalStateException("补偿规则的字段 " + field + " 必须是非空文本: " + node);
    }
    return value.asText();
  }

  /** ★★ S1：{@link HouseholdId} 的值反序列化（旧 {@code CohortKey} 串 ⇒ {@code ofLegacy}；新档 ⇒ parse）。 */
  static final class HouseholdIdDeserializer extends JsonDeserializer<HouseholdId> {

    @Override
    public HouseholdId deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!raw.isTextual()) {
        throw new IllegalStateException("HouseholdId 必须是字符串: " + raw);
      }
      return legacyAwareHouseholdId(raw.asText());
    }
  }

  /**
   * ★★ S1：{@link HouseholdId} 的值序列化——写裸值字符串（与 {@link HouseholdIdDeserializer} 严格互补）。
   *
   * <p>旧 {@code economy-api} 的 {@code HouseholdId} 带 {@code @JsonValue}，迁移到无 Jackson 注解的 {@code
   * simos-social-api} 后注解随类消失；本序列化器把它逐字节补回来，线格式不变。
   */
  static final class HouseholdIdSerializer extends JsonSerializer<HouseholdId> {

    @Override
    public void serialize(
        HouseholdId value, JsonGenerator generator, SerializerProvider serializers)
        throws IOException {
      generator.writeString(value.value());
    }
  }

  /**
   * ★★ S1：旧档 {@code HouseholdEconomy} 的整形（旧键 {@code key} = CohortKey 规范串，没有 {@code id}/{@code
   * view}） ⇒ 新形状（{@code id = HouseholdIds.ofLegacy(key)}、{@code view = key}）。
   *
   * <p>★ 新形状原样交给 {@link #PLAIN}；**缺 {@code id} 且缺 {@code key} ⇒ 抛**（不猜"大概是哪个家户"）。
   */
  static final class LegacyHouseholdEconomyDeserializer extends JsonDeserializer<HouseholdEconomy> {

    @Override
    public HouseholdEconomy deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!(raw instanceof ObjectNode node)) {
        throw new IllegalStateException("ClassRow 必须是 JSON 对象: " + raw);
      }
      if (!node.hasNonNull("id")) {
        if (!node.hasNonNull("key")) {
          throw new IllegalStateException("ClassRow 既没有新键 id、也没有旧键 key: " + node);
        }
        CohortKey view = CohortKey.parse(node.get("key").asText());
        ObjectNode migrated = node.deepCopy();
        migrated.remove("key");
        migrated.put("id", HouseholdIds.ofLegacy(view).value());
        migrated.put("view", view.toString());
        node = migrated;
      }
      try {
        return PLAIN.treeToValue(node, HouseholdEconomy.class);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("ClassRow 解码失败: " + node, e);
      }
    }
  }

  /**
   * ★★ S1：旧档 {@code FlowRow} 的整形（{@code key = CohortKey} ⇒ {@code id =
   * HouseholdIds.ofLegacy(key)}）。
   */
  static final class LegacyFlowRowDeserializer extends JsonDeserializer<FlowRow> {

    @Override
    public FlowRow deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!(raw instanceof ObjectNode node)) {
        throw new IllegalStateException("FlowRow 必须是 JSON 对象: " + raw);
      }
      if (!node.hasNonNull("id")) {
        if (!node.hasNonNull("key")) {
          throw new IllegalStateException("FlowRow 既没有新键 id、也没有旧键 key: " + node);
        }
        CohortKey view = CohortKey.parse(node.get("key").asText());
        ObjectNode migrated = node.deepCopy();
        migrated.remove("key");
        migrated.put("id", HouseholdIds.ofLegacy(view).value());
        node = migrated;
      }
      try {
        return PLAIN.treeToValue(node, FlowRow.class);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("FlowRow 解码失败: " + node, e);
      }
    }
  }

  /**
   * ★★ S1/Z1b：旧档 {@code HouseholdLaborCommitment} 的整形（Z1b 缺 {@code kind} ⇒ 补 {@link
   * LaborCommitmentKind#PRODUCTION}）。
   *
   * <p>★★ <b>B2c（§13.12.5）：缺 {@code household} 不再造 {@code legacy-pending-} 占位 —— 当场
   * fail-closed</b>。 理由：旧档家户归属迁移器（{@code LegacyHouseholdMigration}）B2b 已整段删除，占位<b>没有消费者</b>；留着它等于让
   * 旧档继续定义新状态（Alpha：旧档作废、读不回来不是缺陷）。
   *
   * <p>★ 缺 {@code kind} 是**旧档事实**（Z1b 之前没有这一维），不是坏数据；未知 {@code kind} 值仍交给 Jackson 的枚举绑定
   * fail-closed（闭枚举，不猜）。
   */
  static final class LegacyHouseholdLaborCommitmentDeserializer
      extends JsonDeserializer<HouseholdLaborCommitment> {

    @Override
    public HouseholdLaborCommitment deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!(raw instanceof ObjectNode node)) {
        throw new IllegalStateException("LaborAllocation 必须是 JSON 对象: " + raw);
      }
      if (!node.hasNonNull("household")) {
        // ★★ B2c：旧档缺 household ⇒ 当场 fail-closed（不再造 pending 占位；见方法注）。
        throw new IllegalStateException(
            "LaborAllocation 缺 household（B2c 起不再有旧档 pending 占位补齐）: " + node);
      }
      if (!node.hasNonNull("kind")) {
        node = node.deepCopy();
        node.put("kind", LaborCommitmentKind.PRODUCTION.name());
      }
      try {
        return PLAIN.treeToValue(node, HouseholdLaborCommitment.class);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("LaborAllocation 解码失败: " + node, e);
      }
    }
  }

  // ── R3B.1：旧档 useRights → assetShares 的读侧整形 ──────────────────────────────────────

  /**
   * ★★ <b>旧档经济状态的读侧整形</b>（R3B.1）：顶层组件键 {@code useRights} → {@code assetShares}，并把值节点的旧形状 {@code
   * {id, activity, holder, asset, quantity, kind}} 整成新形状 {@code {id, industry, owner, operator,
   * asset, quantity, kind}}（{@code activity→industry}、{@code holder} 同时填 {@code owner} 与 {@code
   * operator}）。
   *
   * <p>★ <b>为什么必须显式整形、而不是关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}</b>：关掉会把所有真实漂移字段一起吞掉 （铁律 5
   * 的守卫拆一半）；这里只认两个具名旧键、逐字段翻译，其余未知字段照旧 fail-closed。
   *
   * <p>★ <b>fail-closed</b>：同一对象同时出现 {@code useRights} 与 {@code assetShares} ⇒ 抛（同一件事两处拼写，
   * 没有哪一处能判谁对）；旧值节点同时出现 {@code activity/holder} 与 {@code industry/owner/operator} ⇒ 抛；旧键只给一半 ⇒ 抛。
   */
  static ObjectNode migrateLegacyOwnershipStakeComponent(ObjectNode node) {
    boolean hasOld = node.has("useRights");
    boolean hasNew = node.has("assetShares");
    if (hasOld && hasNew) {
      throw new IllegalStateException(
          "经济状态不得同时给 useRights 与 assetShares（R3B.1 起新键是 assetShares，旧档键是 useRights）: " + node);
    }
    if (hasOld) {
      JsonNode legacy = node.remove("useRights");
      reshapeLegacyOwnershipStakeNodes(legacy);
      node.set("assetShares", legacy);
    } else if (hasNew) {
      // 新键下混入旧值形状也整形（幂等）：逐组件增量落盘可能只换了键名、没换完值。
      reshapeLegacyOwnershipStakeNodes(node.get("assetShares"));
    }
    return node;
  }

  /**
   * ★★ <b>撤销维度的旧档读侧兼容：F 批（{@code f3366545}）的 {@code commodityFreightPerMille}（商品运费<b>系数</b>，
   * ‰，乘在整条费率上）</b>。
   *
   * <p>★★ <b>为什么不翻译、直接摘掉</b>：那个维度的量纲是"费率的千分乘数"，与纠正后的"每件每程的基础运费（毫）"不同量纲、 也不同方向（费率维 tool &lt;
   * grain，面值维 tool &gt; grain），**没有一对一的迁移**（硬凑 1500 ⇒ 基础费 1500 毫/单位 会凭空把运费放大三个量级）。用户 2026-10-09
   * 裁定「甲」= 撤销该维、把基础费搬进状态表 ⇒ 旧档里那一维<b>整体作废</b>： 摘掉它、记 INFO（"谁的值被丢了、为什么"），基础费回落到现行硬编码分档（⇒ 该商品的运费逐值回到
   * F 批之前的读数）。
   *
   * <p>★ <b>不炸</b>：{@code EconomyData} 的严格绑定（{@code FAIL_ON_UNKNOWN_PROPERTIES} 保持开启，见 {@link
   * #migrateLegacyOwnershipStakeComponent} 的口径）会在看到未知组件时 fail-closed —— 所以旧名必须在这里被**具名**摘掉，
   * 而不是靠关掉严格绑定把真实漂移一起吞掉。
   *
   * <p>★ <b>fail-closed</b>：同一对象同时出现旧键与新键 {@code commodityFreightBaseMilli} ⇒ 抛（同一件事两处拼写， 没有哪一处能判谁对
   * —— 与 {@code useRights}/{@code assetShares} 同款）。★ 幂等：新形状再跑一遍没有旧键 ⇒ 原样返回。
   */
  static ObjectNode dropRevokedCommodityFreightPerMilleComponent(ObjectNode node, String what) {
    boolean hasRevoked = node.has("commodityFreightPerMille");
    if (!hasRevoked) {
      return node;
    }
    if (node.has("commodityFreightBaseMilli")) {
      throw new IllegalStateException(
          what
              + " 不得同时给 commodityFreightPerMille（已撤销的费率乘数维，F 批 f3366545）与"
              + " commodityFreightBaseMilli（基础费表）：同一件事两处拼写，无法判谁对");
    }
    JsonNode revoked = node.remove("commodityFreightPerMille");
    // ★ 被摘掉的那一维到底带没带数据：快照形态 = 表的行数；变更集形态 = {@code upsert.entries} 的行数。
    //   ★ 级别按"有没有东西被丢"分：**有数据被作废 ⇒ INFO**（操作者必须看得见 GM 设过的值没了）；
    //   **空表/Unchanged ⇒ DEBUG**（什么都没丢，不必刷屏 —— 旧 revision 行会被逐行重放，每条都 INFO 会淹掉日志）。
    long droppedRows = 0L;
    if (revoked instanceof ObjectNode revokedObject) {
      if (revokedObject.has("@class")) {
        JsonNode entries = revokedObject.get("entries");
        droppedRows = entries instanceof ObjectNode entriesObject ? entriesObject.size() : 0L;
      } else {
        droppedRows = revokedObject.size();
      }
    }
    LogEvent event =
        LogEvent.of(
            "ECONOMY_LEGACY_COMMODITY_FREIGHT_PER_MILLE_DROPPED",
            EconomyLogSource.ECONOMY_COMMODITY_FREIGHT,
            "where",
            what,
            "revokedComponent",
            "commodityFreightPerMille",
            "keptComponent",
            "commodityFreightBaseMilli",
            "note",
            "F 批的费率乘数维已撤销（用户 2026-10-09 裁定甲）：旧值不翻译（量纲不同），" + "该商品基础费回落现行硬编码分档",
            "revokedRows",
            droppedRows);
    if (droppedRows > 0L) {
      EventLog.channel(LOG).info(event);
    } else {
      EventLog.channel(LOG).debug(event);
    }
    return node;
  }

  /** 递归整形一整个 {@code assetShares} / {@code useRights} 子树里的旧值节点（只认具名旧字段，其余原样）。 */
  static void reshapeLegacyOwnershipStakeNodes(JsonNode node) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isArray()) {
      for (JsonNode child : node) {
        reshapeLegacyOwnershipStakeNodes(child);
      }
      return;
    }
    if (!node.isObject()) {
      return;
    }
    ObjectNode object = (ObjectNode) node;
    boolean hasActivity = object.has("activity");
    boolean hasHolder = object.has("holder");
    if (hasActivity || hasHolder) {
      if (!hasActivity || !hasHolder) {
        throw new IllegalStateException("旧资产份额必须同时有 activity 与 holder 两个键（缺一个就无法一对一迁移）: " + object);
      }
      if (object.has("industry") || object.has("owner") || object.has("operator")) {
        throw new IllegalStateException(
            "资产份额不得同时给旧 activity/holder 与新 industry/owner/operator（拒绝同一件事的两处拼写）: " + object);
      }
      JsonNode industry = object.remove("activity");
      JsonNode holder = object.remove("holder");
      // ★ 旧别名可能把 activity 写成裸字符串（本 codec 旧字节是 {"value":…}）⇒ 统一整成 IndustryId 的
      //   record 对象形态；两种形态都能被 PLAIN 绑定，裸字符串不行（Jackson 不把单参 record 当 delegating）。
      object.set("industry", normalizeIndustryIdNode(industry));
      object.set("owner", holder);
      object.set("operator", holder);
    }
    List<String> names = new ArrayList<>();
    object.fieldNames().forEachRemaining(names::add);
    for (String name : names) {
      reshapeLegacyOwnershipStakeNodes(object.get(name));
    }
  }

  /**
   * ★ 把旧 {@code activity} 的两种形态归一到 {@link IndustryId} 的 record 对象形态：裸字符串 ⇒ {@code
   * {"value":"…"}}；已是对象 ⇒ 原样。★ 不猜空/数字等坏形状（交给后续绑定 fail-closed）。
   */
  static JsonNode normalizeIndustryIdNode(JsonNode industry) {
    if (industry != null && industry.isTextual()) {
      ObjectNode wrapped = JsonNodeFactory.instance.objectNode();
      wrapped.put("value", industry.asText());
      return wrapped;
    }
    return industry;
  }

  /**
   * ★★ 2026-10-09：class-first 经济档已退役 —— 快照/变更集里的顶层 {@code classFirst} 组件不再存在； 明确 fail-closed（而不是让
   * Jackson 以"未知字段"兜底），旧 class-first 世界需按 production-runtime 重建。
   */
  static void rejectRetiredClassFirst(ObjectNode node, String what) {
    if (node.has("classFirst")) {
      throw new IllegalStateException(
          what
              + " 里出现已退役的 classFirst 组件：class-first 线格式自 2026-10-09 起不再受支持，"
              + "请用 production-runtime（政府内置）重建世界");
    }
  }

  /**
   * ★★ R3B.1：{@link EconomyData} 的旧档读侧兼容（组件键 {@code useRights} 与旧值形状）。
   *
   * <p>★ <b>翻译而不是猜</b>：旧 {@code holder} 同时填 {@code owner} 与 {@code operator}（一对一的旧档事实），
   * 不凭空拆出地主/佃户；旧 id 字符串原样保留（{@link AssetShareId#parse(String)} 是 opaque 的）。
   *
   * <p>★ 新形状原样交给 {@link #PLAIN}；不在本层手写新形状绑定，保持"一条记录怎么从 JSON 造出来"只有一处拼写点。
   */
  static final class LegacyEconomyDataDeserializer extends JsonDeserializer<EconomyData> {

    @Override
    public EconomyData deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!(raw instanceof ObjectNode node)) {
        throw new IllegalStateException("EconomyData 必须是 JSON 对象: " + raw);
      }
      rejectRetiredClassFirst(node, "EconomyData");
      node = migrateLegacyOwnershipStakeComponent(node);
      node = EconomyProductionCodecs.migrateLegacyProductionComponents(node);
      node = EconomyDebtCodecs.migrateLegacyDebtSnapshotComponent(node);
      node = dropRevokedCommodityFreightPerMilleComponent(node, "EconomyData");
      // ★★ Z1b：旧档 allocations 缺 kind ⇒ 在交给 PLAIN 绑定前补 PRODUCTION（快照侧；变更集侧见 ChangeSet 反序列化器）。
      //   放在这里而不是 migrateLegacyProductionComponents 内：那条路径在"没有 industries 键"时会提前返回，
      //   而旧档只要有 allocations 就必须补。
      EconomyProductionCodecs.defaultLaborCommitmentKindsInSnapshot(node.get("allocations"));
      EconomyData data;
      try {
        data = PLAIN.treeToValue(node, EconomyData.class);
      } catch (JsonProcessingException e) {
        // ★★ Z1（§3.1）：构造期对 outputQuantityOverrides 的值域/结构守卫抛出的 IAE 会被 Jackson 包进
        //   JsonProcessingException 的 cause 链；载入边界必须把它记成契约 ERROR（而不是悄悄吞掉）。
        String contractViolation = outputQuantityContractViolation(e);
        if (contractViolation != null) {
          throw outputQuantityContractError(contractViolation, "outputQuantityOverrides 构造期守卫");
        }
        // ★★ 商品基础运费表的值域守卫（≥ 0）同样在构造期 fail-closed —— 载入边界记契约 ERROR，
        //   不降级、不静默丢弃这一行（缺该组件键的旧档走的是"空表"路径，与这里无关）。
        String freightViolation = commodityFreightContractViolation(e);
        if (freightViolation != null) {
          throw commodityFreightContractError(freightViolation);
        }
        throw new IllegalStateException("EconomyData 解码失败: " + node, e);
      }
      // ★★ Z1（§3.1）：载入边界的跨表守卫 —— 产业必须存在、商品必须是该产业 recipe().outputPerUnit() 的键；
      //   违反 ⇒ 契约 ERROR + fail-closed（旧档缺节点 = null ⇒ 构造期已归一成空表，直接通过）。
      requireOutputQuantityOverridesValid(data);
      return data;
    }
  }

  /**
   * ★★ <b>Z1 载入守卫（§3.1）：{@code outputQuantityOverrides} 的每一层键都必须指向现存产业与配方产出商品</b>。
   *
   * <p>违反 = 契约/一致性故障 ⇒ {@code PRODUCTION_EFFICIENCY_CONTRACT} ERROR + {@link IllegalStateException}
   * fail-closed（不静默丢弃、不放行悬空引用）。旧档缺该节点 ⇒ 空表（中性），本方法 no-op。
   */
  static void requireOutputQuantityOverridesValid(EconomyData data) {
    // ★★ D-031 P1（设计 §3.3/§9）：`outputQuantityOverrides` 是既有 GM 运行期覆盖，但"产业存在"与"商品在该产业
    //   配方产出键里"是**跨域**完整性 —— 定义权威在 production，纯 codec 只验证本片固有约束（键/值非空、值域由
    //   EconomyData 构造期判），跨域校验在命令/执行边界显式做（见 EconomyGmAdjustments.setOutputQuantity）。
    //   旧档作废、不做兼容 ⇒ 本方法只保留"本域无额外要做"的具名空实现。
  }

  /**
   * ★★ Z1 契约故障的唯一发射点：先记 {@code PRODUCTION_EFFICIENCY_CONTRACT} ERROR（§8：契约故障不降级）， 再返回 {@link
   * IllegalStateException} 供调用方 fail-closed。{@code reason} 只含稳定 id/数量，不含载荷明文。
   */
  static IllegalStateException outputQuantityContractError(String reason, String where) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "PRODUCTION_EFFICIENCY_CONTRACT",
                EconomyLogSource.ECONOMY_PRODUCTION_EFFICIENCY,
                "where",
                where,
                "reason",
                reason));
    return new IllegalStateException(
        EconomyData.OUTPUT_QUANTITY_OVERRIDE_CONTRACT_PREFIX + where + "：" + reason);
  }

  /**
   * 在 Jackson 的 cause 链里找 {@link EconomyData#OUTPUT_QUANTITY_OVERRIDE_CONTRACT_PREFIX} 标记的构造期违约； 找到
   * ⇒ 返回可读原因（调用方记 ERROR + fail-closed），否则 null（走通用解码失败路径）。
   */
  static String outputQuantityContractViolation(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof IllegalArgumentException
          && cause.getMessage() != null
          && cause.getMessage().startsWith(EconomyData.OUTPUT_QUANTITY_OVERRIDE_CONTRACT_PREFIX)) {
        return cause
            .getMessage()
            .substring(EconomyData.OUTPUT_QUANTITY_OVERRIDE_CONTRACT_PREFIX.length());
      }
    }
    return null;
  }

  /**
   * 在 Jackson 的 cause 链里找 {@link EconomyData#COMMODITY_FREIGHT_CONTRACT_PREFIX} 标记的构造期违约； 找到 ⇒
   * 返回可读原因（调用方记 ERROR + fail-closed），否则 null（走通用解码失败路径）。
   */
  static String commodityFreightContractViolation(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof IllegalArgumentException
          && cause.getMessage() != null
          && cause.getMessage().startsWith(EconomyData.COMMODITY_FREIGHT_CONTRACT_PREFIX)) {
        return cause.getMessage().substring(EconomyData.COMMODITY_FREIGHT_CONTRACT_PREFIX.length());
      }
    }
    return null;
  }

  /**
   * ★★ 商品基础运费契约故障的唯一发射点（照 Z1 {@code outputQuantityContractError}）：先记 {@code
   * ECONOMY_COMMODITY_FREIGHT_CONTRACT} ERROR（契约故障不降级），再返回 {@link IllegalStateException} 供调用方
   * fail-closed。{@code reason} 只含稳定 id/数量，不含载荷明文。
   */
  static IllegalStateException commodityFreightContractError(String reason) {
    EventLog.channel(LOG)
        .error(
            LogEvent.of(
                "ECONOMY_COMMODITY_FREIGHT_CONTRACT",
                EconomyLogSource.ECONOMY_COMMODITY_FREIGHT,
                "where",
                "commodityFreightBaseMilli 构造期守卫（值域 ≥ 0）",
                "reason",
                reason));
    return new IllegalStateException(EconomyData.COMMODITY_FREIGHT_CONTRACT_PREFIX + reason);
  }

  /**
   * ★★ R3B.1：{@link EconomyChangeSet} 的同款旧档读侧兼容（组件键 {@code useRights} → {@code assetShares}， 以及
   * {@code FieldDelta} 各变体里旧值节点的整形）。
   *
   * <p>★ {@link io.mosire.simos.util.state.FieldDelta} 的 diff/rebuild 机制一字不动：本层只把节点整成新形状， 键解析仍由
   * {@code EconomyChangeSet.apply} 的 {@code AssetShareId::parse} 一处负责。
   */
  static final class LegacyEconomyChangeSetDeserializer extends JsonDeserializer<EconomyChangeSet> {

    @Override
    public EconomyChangeSet deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!(raw instanceof ObjectNode node)) {
        throw new IllegalStateException("EconomyChangeSet 必须是 JSON 对象: " + raw);
      }
      rejectRetiredClassFirst(node, "EconomyChangeSet");
      node = migrateLegacyOwnershipStakeComponent(node);
      node = EconomyProductionCodecs.migrateLegacyProductionChangeSetComponents(node);
      node = EconomyDebtCodecs.migrateLegacyDebtChangeSetComponent(node);
      node = dropRevokedCommodityFreightPerMilleComponent(node, "EconomyChangeSet");
      // ★★ Z1b：旧档 allocations 增量缺 kind ⇒ 在交给 PLAIN 绑定前补 PRODUCTION（快照侧同款，见 EconomyData 反序列化器）。
      EconomyProductionCodecs.defaultLaborCommitmentKindsInDelta(node.get("allocations"));
      try {
        return PLAIN.treeToValue(node, EconomyChangeSet.class);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("EconomyChangeSet 解码失败: " + node, e);
      }
    }
  }

  // ── E4a：旧 `debts` 节点 → `debtContracts`（snapshot 与 changeset 两版）──────────────────

  /**
   * ★★ <b>E4a snapshot 迁移：旧 {@code debts} 表 → 新 {@code debtContracts} 表</b>。
   *
   * <p>迁移是**手工 JsonNode 整形**（旧 {@code Debt} 已不在生产状态，若为了反序列化把它复活会在状态树里留下 第二个债务形状；这里只在 codec 边缘按旧
   * JSON 字段读值）：
   *
   * <ol>
   *   <li>逐旧条构造 {@link DebtContract}：{@code (debtor, creditor, unit, terms)} 派生稳定 id；旧 {@code
   *       commodity} 空 ⇒ 银币货币债；旧 {@code ratePerMillePerCycle} 进 legacy terms； {@code dueCycle}
   *       落在合同滚动字段（取被合并条的最大值）；旧 {@code defaulted = true} ⇒ {@link DebtStatus#DEFAULTED}，否则本金 0 ⇒
   *       {@code SETTLED}、本金 &gt; 0 ⇒ {@code NORMAL}；
   *   <li>同新 id 的旧条**合并**：{@code principal = Math.addExact(a, b)}（逐值守恒）；status 取 {@code DEFAULTED}
   *       优先，否则按合并后本金给 {@code NORMAL/SETTLED}；{@code dueCycle} 取最大；
   *   <li>旧 {@code HouseholdEconomy.debts} 里的旧 id 引用改写成新合同 id（去重、保序）——这一步只是把派生索引 搬到新键；最终权威仍由 {@code
   *       EconomyData} 构造期的 {@code DebtReferenceReconciler} 从新表重建；
   *   <li>同时出现 {@code debts} 与 {@code debtContracts} ⇒ 抛（同一件事两处拼写）。
   * </ol>
   *
   * <p>★ <b>幂等</b>：新形状再跑一遍时没有 {@code debts} 键 ⇒ 原样返回。
   */
  /**
   * ★★ R3B.1：{@link AssetShareId} 的**值侧**读入（Map 键走上面的 {@code KeyDeserializer}，两者独立）。
   *
   * <p>★ <b>为什么收两种形态</b>：本 codec 旧版把 {@code UseRightId} 按 record 默认写成 {@code {"value":"use-…"}} （与
   * {@link #PLAIN} 的写出形态一致）；而旧档别名/手写夹具/外部工具可能写裸字符串。两种都必须能读， 否则"旧档可读"只成立于本 codec 自产的字节。★
   * 只认这两种：其它形状（数字/数组/缺 value）⇒ 抛，不静默造 id。
   */
  static final class AssetShareIdDeserializer extends JsonDeserializer<AssetShareId> {

    @Override
    public AssetShareId deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (raw.isTextual()) {
        return AssetShareId.parse(raw.asText());
      }
      if (raw.isObject() && raw.hasNonNull("value") && raw.get("value").isTextual()) {
        return AssetShareId.parse(raw.get("value").asText());
      }
      throw new IllegalStateException("AssetShareId 必须是字符串或 {value:\"…\"}: " + raw);
    }
  }

  static <K> KeyDeserializer keyDeserializer(Function<String, K> parse) {
    return new KeyDeserializer() {
      @Override
      public Object deserializeKey(String key, DeserializationContext context) {
        return parse.apply(key);
      }
    };
  }

  /** 同 {@code LedgerCodec} 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("economy 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("economy 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }

  /** 供 debt codec 用与原始 {@code MAPPER.valueToTree} 相同的一台 mapper 序列化节点。 */
  static JsonNode valueToTree(Object value) {
    return MAPPER.valueToTree(value);
  }
}
