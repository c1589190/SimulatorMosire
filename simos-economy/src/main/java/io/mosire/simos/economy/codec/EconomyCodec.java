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
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.cohort.HouseholdIds;
import io.mosire.simos.economy.api.debt.DebtStatus;
import io.mosire.simos.economy.api.debt.DebtTerms;
import io.mosire.simos.economy.api.debt.DebtUnit;
import io.mosire.simos.economy.api.id.AssetRuleId;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CandidateId;
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
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.ModeTransitionId;
import io.mosire.simos.economy.api.id.MoneyIssuanceId;
import io.mosire.simos.economy.api.id.PledgeId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionOrganizationId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.money.MoneyVocabulary;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.DebtContract;
import io.mosire.simos.economy.model.EconomyMeta;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.RegimeOperators;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Function;

/**
 * economy 模块的 {@link ModuleCodec} 实现（spec §八）。形态与 {@code LedgerCodec} 同制，理由不重复——只记 economy 自己的那点差异。
 *
 * <p>★ <b>树里的自定义键（读侧注册；写侧靠各自的 {@code toString()}）</b>：{@code IndustryId}（{@code industries} /
 * {@code relations} / {@code operatorConditions}）、{@code HouseholdId}（{@code classes} / {@code
 * flows}；旧档的 {@code CohortKey} 规范串由 {@code HouseholdIdDeserializer} 识别并映射成 {@code ofLegacy}）、{@code
 * DebtContractId} （{@code debtContracts}；旧键 {@code debts} 由 {@link
 * #migrateLegacyDebtSnapshotComponent} / {@link #migrateLegacyDebtChangeSetComponent} 手工迁移）、{@code
 * PledgeId} （{@code pledges}）、{@code CommodityId}（产业产出/投入、行需求、流水与规则里的商品键）、{@code PeopleLotId}
 * （{@code laborSupply}）、{@code LaborAllocationId}（{@code allocations}）、{@code MembershipId} （{@code
 * memberships}）、{@code AssetShareId}（{@code assetShares}；旧档的 {@code use-…} 键字符串由 {@link
 * AssetShareId#parse(String)} opaque 原样读入）、{@code HexCoord}（{@code markets}， 住在 {@code
 * simos-map}）、{@code ShipmentId}（{@code shipments}）。★ 它们都重写了 {@code toString()} 并与 各自的 {@code
 * parse} 互为逆，故只需读侧；键反序列化器照裁定 16 在**本模块**注册，不进共享基座。★ 漏注册的症状是"读档时键 解析不出来"（Jackson 会去调构造器或报 {@code no
 * String-argument constructor}）。★★ E1 追加 {@code ProductionModeId}（{@code modes}）、{@code
 * ClassStructureId}（{@code classStructures}）与 {@code ClassPositionId}（{@code classPositions}，以及
 * {@code ClassStructure.positions} / {@code ClassStructure.defaultSharesPerMille} / {@code
 * ClassStanding.retainedShares} 三个嵌套键表）；{@code classStandings} 的键仍是上面已注册的 {@code HouseholdId}。
 * 旧档缺这四个键 ⇒ 快照侧收成空表、变更集侧收成 {@code Unchanged}，见各自的构造器兜底。
 *
 * <p>★ <b>P10.1</b>：{@code merchantFirms}（第 30 个组件）的键复用已注册的 {@code ProductionOrganizationId}；值
 * {@link io.mosire.simos.economy.model.MerchantFirm} 按 record 组件字段显式绑定， 缺键 ⇒ 空表（{@code EconomyData}
 * 构造期归一），写侧按构造期 {@code LinkedHashMap} 的插入序保序。
 *
 * <p>★ {@code AssetKind} 作键（{@code dailyInputPerUnit}/{@code capacity}）走 Jackson **默认的枚举键** 绑定（按
 * {@code name()}），无需自定义；其余 ID/键类型都重写了 {@code toString()}（= 裸值）并与各自的 {@code parse} 互为逆，故只需读侧。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：委托 {@link
 * EconomyChangeSet#between(EconomyData, EconomyData)}。
 *
 * <p>★★ <b>M2.7 的类结构增量与旧档兼容</b>：{@code ClassRow.cycleNaturalNeedMilli} 是追加在记录末尾的原始 {@code long} ——
 * Jackson 的记录绑定对<b>缺失的原始组件</b>取默认值 {@code 0}（本批实测过：把该键从 JSON 里删掉仍能读出 0，不抛），这就是"旧档缺该键 ⇒ 0 =
 * 还没开始累计"的兜底；载荷边缘的 {@code EconomyPayloads.classRow} 另有一条 {@code optionalLong(..., 0L)}
 * 的同款兜底。两条都<b>不新增迁移代码</b>：缺键的方向本来就是 fail-closed 的 0。
 */
public final class EconomyCodec implements ModuleCodec, ModuleDiffer {

  /** {@link ProductionUnitId#idOf} 生成的新档 unit id 前缀；只用于**旧档变更集整形**的幂等判别。 */
  private static final String PRODUCTION_UNIT_ID_PREFIX = "unit-";

  /**
   * ★★ <b>"纯绑定" mapper</b>：只带键反序列化器（含旧 {@code CohortKey} 键 → {@link HouseholdId} 的识别），
   * <b>不带值类型兼容层</b> —— 兼容层把旧节点整形成新节点后交给它绑定（避免"兼容层再进兼容层"的递归）。
   *
   * <p>★ 先例：H2 的补偿规则兼容层就是"整形后交给 {@code PLAIN}"；S1 的旧档迁移沿用同一分工。 ⇒ "一条记录怎么从 JSON
   * 造出来"永远只有<b>一处</b>拼写点（Jackson 的 record 绑定），兼容层只负责改节点。
   */
  private static final ObjectMapper PLAIN =
      withEconomyMixins(SimosObjectMapper.create(keyModule()));

  /** 本模块唯一的一台 mapper：共享基座 + 键反序列化器 + S1/H2 的值兼容层。 */
  private static final ObjectMapper MAPPER =
      withEconomyMixins(SimosObjectMapper.create(keyModule(), compatModule()));

  /**
   * ★ 把 {@code EconomyChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code
   * LedgerCodec} 同制）：Jackson 会把 {@code isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。 {@code
   * isEmpty} 是派生判断不是状态，**不进线格式**；mixin 放本类（mapper 与 mixin 同处一地、谁也丢不了），领域类型保持零 Jackson 注解（{@code
   * AllocationRule} 的 sealed 多态注解除外——那是往返的硬前提）。
   */
  private static ObjectMapper withEconomyMixins(ObjectMapper mapper) {
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

  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("economy-json-keys");
    module.addKeyDeserializer(IndustryId.class, keyDeserializer(IndustryId::parse));
    // ★★ S1：classes/flows 的键 = HouseholdId。旧档的键是 CohortKey 规范串 ⇒ 这里做一次"旧视图 → ofLegacy"
    //   识别（新档 id 的 parse 是恒等）。识别器同时注册为**值**反序列化器（ClassRow.id；旧 Debt.debtor/creditor 由旧档迁移层手工解析）。
    module.addKeyDeserializer(
        HouseholdId.class, keyDeserializer(EconomyCodec::legacyAwareHouseholdId));
    module.addDeserializer(HouseholdId.class, new HouseholdIdDeserializer());
    // ★★ S1：HouseholdId 迁入无 Jackson 注解的 simos-social-api ⇒ 值侧必须显式写回裸字符串（旧 @JsonValue
    //   行为），否则 ClassRow.id / LaborAllocation.household 等值会变成 {"value":…}，而上面的值反序列化器
    //   只认字符串（写出来的档自己读不回）。键侧仍走 toString/parse 配对。
    module.addSerializer(HouseholdId.class, new HouseholdIdSerializer());
    // ★★ E4a：债务合同表 / 质押表的新键（toString/parse 互逆，只需读侧）。
    module.addKeyDeserializer(DebtContractId.class, keyDeserializer(DebtContractId::parse));
    module.addKeyDeserializer(PledgeId.class, keyDeserializer(PledgeId::parse));
    module.addKeyDeserializer(CommodityId.class, keyDeserializer(CommodityId::parse));
    // ★★ E4c：FlowRow.repaidMoney 的键 = CurrencyId（逐币种偿还读数）—— 与 CommodityId 同形，只需读侧。
    module.addKeyDeserializer(CurrencyId.class, keyDeserializer(CurrencyId::parse));
    // ★ R2 起是两张新表的键：laborSupply（PeopleLotId → LaborSupply）与 allocations（LaborAllocationId
    //   → LaborAllocation）。两者都重写了 toString()（= 裸值）并与各自的 parse 互为逆，故只需读侧。
    module.addKeyDeserializer(PeopleLotId.class, keyDeserializer(PeopleLotId::parse));
    module.addKeyDeserializer(LaborAllocationId.class, keyDeserializer(LaborAllocationId::parse));
    // ★★ S1：memberships / assetShares 两张新表的键（R3B.1 起后者 = AssetShareId；旧 use-… 串 opaque 可读）。
    module.addKeyDeserializer(MembershipId.class, keyDeserializer(MembershipId::parse));
    module.addKeyDeserializer(AssetShareId.class, keyDeserializer(AssetShareId::parse));
    // ★★ R3B.2：units 表的键 = ProductionUnitId；同时注册值侧反序列化器（旧档/手写可能写裸字符串）。
    module.addKeyDeserializer(ProductionUnitId.class, keyDeserializer(ProductionUnitId::parse));
    module.addDeserializer(ProductionUnitId.class, new ProductionUnitIdDeserializer());
    // ★★ R3B.1：AssetShare 值内的 id 可能是本 codec 旧字节的 {"value":"use-…"}，也可能是裸字符串
    //   （手写/外部工具/旧别名）⇒ 值侧显式收两种（keyModule 同时供 PLAIN 使用）；其它形状仍 fail-closed。
    module.addDeserializer(AssetShareId.class, new AssetShareIdDeserializer());
    // ★★ H4：市场表的键 = **格**（{@code 0_0}）—— 本模块第一次把 HexCoord 当键用（见类注）。
    module.addKeyDeserializer(HexCoord.class, keyDeserializer(HexCoord::parse));
    // ★★ M2.4：在途批次表的键 = ShipmentId（{@code sh-<day>-<seq>}）—— 与上面同一条口径：toString/parse 互逆，只需读侧。
    module.addKeyDeserializer(ShipmentId.class, keyDeserializer(ShipmentId::parse));
    // ★★ R4-E2：demands / candidates 两张新表的键（opaque 裸值，与各自 parse 互为逆，只需读侧）。
    module.addKeyDeserializer(DemandId.class, keyDeserializer(DemandId::parse));
    module.addKeyDeserializer(CandidateId.class, keyDeserializer(CandidateId::parse));
    // ★★ E1：modes / classStructures / classPositions / classStandings（键 = HouseholdId）四张新表，
    //   以及 ClassStructure.positions / defaultSharesPerMille 与 ClassStanding.retainedShares
    //   三个**嵌套** ClassPositionId 键表 —— 都必须在这里注册键反序列化器（写侧走各自 toString）。
    module.addKeyDeserializer(ProductionModeId.class, keyDeserializer(ProductionModeId::parse));
    module.addKeyDeserializer(ClassStructureId.class, keyDeserializer(ClassStructureId::parse));
    module.addKeyDeserializer(ClassPositionId.class, keyDeserializer(ClassPositionId::parse));
    // ★★ E2：productionOrganizations / assetRules 两张新表的键（opaque 裸值，与各自 parse 互为逆，只需读侧）。
    //   ★ P10.1：merchantFirms（第 30 个组件）的键 = ProductionOrganizationId，复用下面这一个注册点；
    //     值 MerchantFirm 走 Jackson 的 record 字段显式绑定（无自定义 compatibility 层），缺键 ⇒ EconomyData
    //     构造期归一成空表，写侧按构造期 LinkedHashMap 的插入序保序。
    module.addKeyDeserializer(
        ProductionOrganizationId.class, keyDeserializer(ProductionOrganizationId::parse));
    module.addKeyDeserializer(AssetRuleId.class, keyDeserializer(AssetRuleId::parse));
    // ★★ E3：governments / moneyIssuances 两张新表的键（opaque 裸值，与各自 parse 互为逆，只需读侧）。
    module.addKeyDeserializer(GovernmentId.class, keyDeserializer(GovernmentId::parse));
    module.addKeyDeserializer(MoneyIssuanceId.class, keyDeserializer(MoneyIssuanceId::parse));
    // ★★ E5a：crisisSignals 的键 = CrisisSignalId（{@code crisis-<q>_<r>-<KIND>}，与 parse 互为逆，只需读侧）。
    //   liquidationPolicies 的键 = AssetRuleId，上面 E2 已注册。
    module.addKeyDeserializer(CrisisSignalId.class, keyDeserializer(CrisisSignalId::parse));
    // ★★ E6a：modeTransitions / classShares 两张新表的键（opaque 裸值，与各自 parse 互为逆，只需读侧）。
    module.addKeyDeserializer(ModeTransitionId.class, keyDeserializer(ModeTransitionId::parse));
    module.addKeyDeserializer(ClassShareId.class, keyDeserializer(ClassShareId::parse));
    return module;
  }

  /** ★★ S1/H2/R3B.1 的值类型兼容层：旧形状整形成新形状之后交给 {@link #PLAIN} 绑定（避免递归）。 */
  private static SimpleModule compatModule() {
    SimpleModule module = new SimpleModule("economy-json-legacy-values");
    // ★★ H2：补偿规则的旧线格式（单个 `basis` → `pool` + `weight`）。
    module.addDeserializer(CompensationRule.class, new CompensationRuleDeserializer());
    // ★★ S1：ClassRow/FlowRow 的旧键 `key`（CohortKey）→ `id` + `view`；LaborAllocation 缺 household。
    module.addDeserializer(ClassRow.class, new LegacyClassRowDeserializer());
    module.addDeserializer(FlowRow.class, new LegacyFlowRowDeserializer());
    module.addDeserializer(LaborAllocation.class, new LegacyLaborAllocationDeserializer());
    // ★★ R3B.1：旧档组件键 `useRights` → `assetShares`，旧值 `activity/holder` → `industry/owner+operator`。
    //   两条都在 codec 边缘做**显式节点整形**，领域类型保持零 Jackson 注解；Delegate 给 PLAIN 以免递归。
    module.addDeserializer(EconomyData.class, new LegacyEconomyDataDeserializer());
    module.addDeserializer(EconomyChangeSet.class, new LegacyEconomyChangeSetDeserializer());
    return module;
  }

  /**
   * ★ 旧 {@code CohortKey} 规范串（含 {@code |}、且非 legacy- 前缀）⇒ {@code HouseholdIds.ofLegacy}；其余原样 parse。
   */
  private static HouseholdId legacyAwareHouseholdId(String text) {
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
  private static final class CompensationRuleDeserializer
      extends JsonDeserializer<CompensationRule> {

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
  private static CompensationRule decodeRule(ObjectNode node) {
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
  private static String textOf(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalStateException("补偿规则的字段 " + field + " 必须是非空文本: " + node);
    }
    return value.asText();
  }

  /** ★★ S1：{@link HouseholdId} 的值反序列化（旧 {@code CohortKey} 串 ⇒ {@code ofLegacy}；新档 ⇒ parse）。 */
  private static final class HouseholdIdDeserializer extends JsonDeserializer<HouseholdId> {

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
   * <p>旧 {@code economy-api} 的 {@code HouseholdId} 带 {@code @JsonValue}，迁移到无 Jackson 注解的
   * {@code simos-social-api} 后注解随类消失；本序列化器把它逐字节补回来，线格式不变。
   */
  private static final class HouseholdIdSerializer extends JsonSerializer<HouseholdId> {

    @Override
    public void serialize(HouseholdId value, JsonGenerator generator, SerializerProvider serializers)
        throws IOException {
      generator.writeString(value.value());
    }
  }

  /**
   * ★★ S1：旧档 {@code ClassRow} 的整形（旧键 {@code key} = CohortKey 规范串，没有 {@code id}/{@code view}） ⇒
   * 新形状（{@code id = HouseholdIds.ofLegacy(key)}、{@code view = key}）。
   *
   * <p>★ 新形状原样交给 {@link #PLAIN}；**缺 {@code id} 且缺 {@code key} ⇒ 抛**（不猜"大概是哪个家户"）。
   */
  private static final class LegacyClassRowDeserializer extends JsonDeserializer<ClassRow> {

    @Override
    public ClassRow deserialize(JsonParser parser, DeserializationContext context)
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
        return PLAIN.treeToValue(node, ClassRow.class);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("ClassRow 解码失败: " + node, e);
      }
    }
  }

  /**
   * ★★ S1：旧档 {@code FlowRow} 的整形（{@code key = CohortKey} ⇒ {@code id = HouseholdIds.ofLegacy(key)}）。
   */
  private static final class LegacyFlowRowDeserializer extends JsonDeserializer<FlowRow> {

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
   * ★★ S1：旧档 {@code LaborAllocation} 的整形（缺 {@code household}）⇒ 造 {@link HouseholdIds#pendingLegacy}
   * 占位；真正的家户归属由 {@code LegacyHouseholdMigration} 在 {@code EconomyData} 构造期按行人口拆出。
   */
  private static final class LegacyLaborAllocationDeserializer
      extends JsonDeserializer<LaborAllocation> {

    @Override
    public LaborAllocation deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!(raw instanceof ObjectNode node)) {
        throw new IllegalStateException("LaborAllocation 必须是 JSON 对象: " + raw);
      }
      if (!node.hasNonNull("household")) {
        if (!node.hasNonNull("id")) {
          throw new IllegalStateException("LaborAllocation 缺 household 且没有旧键 id，无法定位占位: " + node);
        }
        node = node.deepCopy();
        node.put("household", HouseholdIds.pendingLegacy(node.get("id").asText()).value());
      }
      try {
        return PLAIN.treeToValue(node, LaborAllocation.class);
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
  private static ObjectNode migrateLegacyAssetShareComponent(ObjectNode node) {
    boolean hasOld = node.has("useRights");
    boolean hasNew = node.has("assetShares");
    if (hasOld && hasNew) {
      throw new IllegalStateException(
          "经济状态不得同时给 useRights 与 assetShares（R3B.1 起新键是 assetShares，旧档键是 useRights）: " + node);
    }
    if (hasOld) {
      JsonNode legacy = node.remove("useRights");
      reshapeLegacyAssetShareNodes(legacy);
      node.set("assetShares", legacy);
    } else if (hasNew) {
      // 新键下混入旧值形状也整形（幂等）：逐组件增量落盘可能只换了键名、没换完值。
      reshapeLegacyAssetShareNodes(node.get("assetShares"));
    }
    return node;
  }

  /** 递归整形一整个 {@code assetShares} / {@code useRights} 子树里的旧值节点（只认具名旧字段，其余原样）。 */
  private static void reshapeLegacyAssetShareNodes(JsonNode node) {
    if (node == null || node.isNull()) {
      return;
    }
    if (node.isArray()) {
      for (JsonNode child : node) {
        reshapeLegacyAssetShareNodes(child);
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
      reshapeLegacyAssetShareNodes(object.get(name));
    }
  }

  /**
   * ★ 把旧 {@code activity} 的两种形态归一到 {@link IndustryId} 的 record 对象形态：裸字符串 ⇒ {@code
   * {"value":"…"}}；已是对象 ⇒ 原样。★ 不猜空/数字等坏形状（交给后续绑定 fail-closed）。
   */
  private static JsonNode normalizeIndustryIdNode(JsonNode industry) {
    if (industry != null && industry.isTextual()) {
      ObjectNode wrapped = JsonNodeFactory.instance.objectNode();
      wrapped.put("value", industry.asText());
      return wrapped;
    }
    return industry;
  }

  /**
   * ★★ 2026-10-09：class-first 经济档已退役 —— 快照/变更集里的顶层 {@code classFirst} 组件不再存在；
   * 明确 fail-closed（而不是让 Jackson 以"未知字段"兜底），旧 class-first 世界需按 production-runtime 重建。
   */
  private static void rejectRetiredClassFirst(ObjectNode node, String what) {
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
  private static final class LegacyEconomyDataDeserializer extends JsonDeserializer<EconomyData> {

    @Override
    public EconomyData deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!(raw instanceof ObjectNode node)) {
        throw new IllegalStateException("EconomyData 必须是 JSON 对象: " + raw);
      }
      rejectRetiredClassFirst(node, "EconomyData");
      node = migrateLegacyAssetShareComponent(node);
      node = migrateLegacyProductionComponents(node);
      node = migrateLegacyDebtSnapshotComponent(node);
      try {
        return PLAIN.treeToValue(node, EconomyData.class);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("EconomyData 解码失败: " + node, e);
      }
    }
  }

  /**
   * ★★ R3B.1：{@link EconomyChangeSet} 的同款旧档读侧兼容（组件键 {@code useRights} → {@code assetShares}， 以及
   * {@code FieldDelta} 各变体里旧值节点的整形）。
   *
   * <p>★ {@link io.mosire.simos.util.state.FieldDelta} 的 diff/rebuild 机制一字不动：本层只把节点整成新形状， 键解析仍由
   * {@code EconomyChangeSet.apply} 的 {@code AssetShareId::parse} 一处负责。
   */
  private static final class LegacyEconomyChangeSetDeserializer
      extends JsonDeserializer<EconomyChangeSet> {

    @Override
    public EconomyChangeSet deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (!(raw instanceof ObjectNode node)) {
        throw new IllegalStateException("EconomyChangeSet 必须是 JSON 对象: " + raw);
      }
      rejectRetiredClassFirst(node, "EconomyChangeSet");
      node = migrateLegacyAssetShareComponent(node);
      node = migrateLegacyProductionChangeSetComponents(node);
      node = migrateLegacyDebtChangeSetComponent(node);
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
   *   <li>旧 {@code ClassRow.debts} 里的旧 id 引用改写成新合同 id（去重、保序）——这一步只是把派生索引 搬到新键；最终权威仍由 {@code
   *       EconomyData} 构造期的 {@code DebtReferenceReconciler} 从新表重建；
   *   <li>同时出现 {@code debts} 与 {@code debtContracts} ⇒ 抛（同一件事两处拼写）。
   * </ol>
   *
   * <p>★ <b>幂等</b>：新形状再跑一遍时没有 {@code debts} 键 ⇒ 原样返回。
   */
  private static ObjectNode migrateLegacyDebtSnapshotComponent(ObjectNode node) {
    boolean hasOld = node.has("debts");
    boolean hasNew = node.has("debtContracts");
    if (hasOld && hasNew) {
      throw new IllegalStateException(
          "经济状态不得同时给 debts 与 debtContracts（E4a 起新键是 debtContracts，旧档键是 debts）: " + node);
    }
    if (!hasOld) {
      return node;
    }
    JsonNode legacy = node.remove("debts");
    ObjectNode newTable = JsonNodeFactory.instance.objectNode();
    node.set("debtContracts", newTable);
    if (legacy == null || legacy.isNull()) {
      rewriteLegacyClassRowDebtReferences(node, Map.of());
      return node;
    }
    if (!(legacy instanceof ObjectNode oldTable)) {
      throw new IllegalStateException("旧 debts 必须是 {旧债务id: Debt} 对象: " + legacy);
    }
    Map<String, DebtContract> merged = new LinkedHashMap<>();
    Map<String, String> oldToNew = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : iterableFields(oldTable)) {
      DebtContract contract = legacyDebtContract(entry.getKey(), entry.getValue());
      oldToNew.put(entry.getKey(), contract.id().value());
      merged.merge(contract.id().value(), contract, EconomyCodec::mergeDebtContracts);
    }
    for (Map.Entry<String, DebtContract> entry : merged.entrySet()) {
      newTable.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
    }
    rewriteLegacyClassRowDebtReferences(node, oldToNew);
    return node;
  }

  /**
   * ★★ E4a changeset 迁移：旧 {@code debts} 的 {@code FieldDelta} → 新 {@code debtContracts} 的 {@code
   * FieldDelta}。
   *
   * <p>四个变体逐档翻译：{@code unchanged} 原样换名；{@code upsert} 的每个旧值按 snapshot 同一套 {@link
   * #legacyDebtContract} 翻译并按新 id 合并（{@code principal} 用 {@code Math.addExact} 求和， 本金逐值守恒）；{@code
   * remove} 的旧键按旧格式解析出四元组后换新 id；{@code patch} 两侧分别翻译。
   *
   * <p>★★ <b>如实记的边界</b>：旧变更集里 {@code upsert.entries} 的值是<b>完整新值</b>（FieldDelta 语义）， 而迁移后同一新 id
   * 是跨旧周期合并的余额；因此“把旧 revision 链从新 checkpoint 继续重放”在
   * 同一四元组于旧基态已有多条周期合同、且中间变更集只更新其中一条时，无法只靠本方法恢复被合并条的明细 （那需要旧基态的逐条本金，旧线格式里没有）。本阶段按“变更集节点 principal
   * 守恒”实现，并把该重放边界 记入交付报告的已知缺口；最终回放必须用同一 revision 的完整 snapshot，或等 E4b 的显式债务 breakdown。
   *
   * <p>★ <b>幂等</b>：新形状再跑一遍时没有 {@code debts} 键 ⇒ 原样返回。
   */
  private static ObjectNode migrateLegacyDebtChangeSetComponent(ObjectNode root) {
    JsonNode legacy = root.get("debts");
    boolean hasNew = root.has("debtContracts");
    if (legacy != null && hasNew) {
      throw new IllegalStateException(
          "经济变更集不得同时给 debts 与 debtContracts（E4a 起新键是 debtContracts，旧档键是 debts）: " + root);
    }
    if (legacy == null) {
      return root;
    }
    root.remove("debts");
    if (legacy.isNull()) {
      root.set("debtContracts", unchangedDeltaNode());
      return root;
    }
    if (!(legacy instanceof ObjectNode delta)) {
      throw new IllegalStateException("旧 debts 变更集必须是 FieldDelta 对象: " + legacy);
    }
    root.set("debtContracts", legacyDebtDelta(delta));
    return root;
  }

  /** 旧 debts 的一个 {@link FieldDelta} 变体 → 新 debtContracts 的同变体（键/值都换新）。 */
  private static ObjectNode legacyDebtDelta(ObjectNode delta) {
    String kind = delta.path("@class").asText("");
    switch (kind) {
      case "", "unchanged":
        return unchangedDeltaNode();
      case "upsert":
        return legacyDebtUpsert(deltaEntries(delta));
      case "remove":
        return legacyDebtRemove(delta.get("keys"));
      case "patch":
        JsonNode upserts = delta.get("upserts");
        JsonNode removals = delta.get("removals");
        if (!(upserts instanceof ObjectNode upsertsObject)
            || !(removals instanceof ObjectNode removalsObject)) {
          throw new IllegalStateException("旧 debts patch 必须同时有 upserts 与 removals 对象: " + delta);
        }
        ObjectNode patch = JsonNodeFactory.instance.objectNode();
        patch.put("@class", "patch");
        patch.set("upserts", legacyDebtDelta(upsertsObject));
        patch.set("removals", legacyDebtDelta(removalsObject));
        return patch;
      default:
        throw new IllegalStateException("旧 debts 变更集的 FieldDelta 变体不认识: " + kind);
    }
  }

  /**
   * 旧 debts 的 {@code upsert.entries} → 新 debtContracts 的 {@code upsert.entries}（同新 id 合并
   * principal）。
   */
  private static ObjectNode legacyDebtUpsert(ObjectNode entries) {
    if (entries == null) {
      throw new IllegalStateException("旧 debts upsert 缺 entries 对象");
    }
    Map<String, DebtContract> merged = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : iterableFields(entries)) {
      DebtContract contract = legacyDebtContract(entry.getKey(), entry.getValue());
      merged.merge(contract.id().value(), contract, EconomyCodec::mergeDebtContracts);
    }
    ObjectNode newEntries = JsonNodeFactory.instance.objectNode();
    for (Map.Entry<String, DebtContract> entry : merged.entrySet()) {
      newEntries.set(entry.getKey(), MAPPER.valueToTree(entry.getValue()));
    }
    ObjectNode upsert = JsonNodeFactory.instance.objectNode();
    upsert.put("@class", "upsert");
    upsert.set("entries", newEntries);
    return upsert;
  }

  /** 旧 debts 的 {@code remove.keys} → 新 debtContracts 的 {@code remove.keys}（旧键解析四元组后换新 id）。 */
  private static ObjectNode legacyDebtRemove(JsonNode keys) {
    if (keys == null || !keys.isArray()) {
      throw new IllegalStateException("旧 debts remove 缺 keys 数组: " + keys);
    }
    Set<String> newKeys = new LinkedHashSet<>();
    for (JsonNode key : keys) {
      String oldId = key.isTextual() ? key.asText() : textOfId(key);
      if (oldId == null) {
        throw new IllegalStateException("旧 debts remove 的 key 不是字符串/{\"value\":…}: " + key);
      }
      newKeys.add(legacyDebtContractIdFromOldKey(oldId).value());
    }
    var array = JsonNodeFactory.instance.arrayNode();
    for (String newKey : newKeys) {
      array.add(newKey);
    }
    ObjectNode remove = JsonNodeFactory.instance.objectNode();
    remove.put("@class", "remove");
    remove.set("keys", array);
    return remove;
  }

  /** 旧 {@code Debt} 节点 → 新 {@link DebtContract}（缺字段/坏形状 fail-closed，不猜）。 */
  private static DebtContract legacyDebtContract(String oldId, JsonNode value) {
    if (!(value instanceof ObjectNode debt)) {
      throw new IllegalStateException("旧债务值必须是对象: " + value);
    }
    String declaredId = textOfId(debt.get("id"));
    if (declaredId != null && !declaredId.equals(oldId)) {
      throw new IllegalStateException("旧债务的键与值内 id 不一致：键=" + oldId + "，值内 id=" + declaredId);
    }
    String debtorText = textOfId(debt.get("debtor"));
    String creditorText = textOfId(debt.get("creditor"));
    if (debtorText == null || creditorText == null) {
      throw new IllegalStateException("旧债务必须给 debtor/creditor: " + debt);
    }
    JsonNode rateNode = debt.get("ratePerMillePerCycle");
    if (rateNode == null || !rateNode.isNumber()) {
      throw new IllegalStateException("旧债务必须给数字 ratePerMillePerCycle: " + debt);
    }
    int rate = rateNode.intValue();
    if (rate < 0) {
      throw new IllegalStateException("旧债务 ratePerMillePerCycle 不得为负: " + debt);
    }
    JsonNode principalNode = debt.get("principal");
    if (principalNode == null || !principalNode.isNumber()) {
      throw new IllegalStateException("旧债务必须给数字 principal: " + debt);
    }
    long principal = principalNode.longValue();
    if (principal < 0L) {
      throw new IllegalStateException("旧债务 principal 不得为负: " + debt);
    }
    OptionalLong dueCycle = OptionalLong.empty();
    JsonNode dueNode = debt.get("dueCycle");
    if (dueNode != null && dueNode.isNumber()) {
      long due = dueNode.longValue();
      if (due < 0L) {
        throw new IllegalStateException("旧债务 dueCycle 不得为负: " + debt);
      }
      dueCycle = OptionalLong.of(due);
    }
    boolean defaulted = debt.path("defaulted").asBoolean(false);
    DebtStatus status =
        defaulted
            ? DebtStatus.DEFAULTED
            : (principal == 0L ? DebtStatus.SETTLED : DebtStatus.NORMAL);
    HouseholdId debtor = HouseholdId.parse(debtorText);
    HouseholdId creditor = HouseholdId.parse(creditorText);
    DebtUnit unit = legacyDebtUnit(debt.get("commodity"));
    DebtTerms terms = DebtTerms.legacyDefault(rate);
    return new DebtContract(
        DebtContractId.idOf(debtor, creditor, unit, terms),
        debtor,
        creditor,
        unit,
        terms,
        principal,
        0L,
        OptionalLong.empty(),
        dueCycle,
        status);
  }

  /** 旧 {@code commodity} 空 ⇒ 银币货币债；有值 ⇒ 实物商品债（旧 Optional 线格式的两种形态都收）。 */
  private static DebtUnit legacyDebtUnit(JsonNode commodityNode) {
    if (commodityNode == null || commodityNode.isNull()) {
      return DebtUnit.money(MoneyVocabulary.SILVER_CURRENCY);
    }
    String commodity = textOfId(commodityNode);
    if (commodity == null || commodity.isBlank()) {
      throw new IllegalStateException("旧债务 commodity 形状不可识别: " + commodityNode);
    }
    return DebtUnit.commodity(new CommodityId(commodity));
  }

  /** 同新 id 的旧条合并：principal 逐值相加、status/defaulted 优先、dueCycle 取最大、openedDay 取最早。 */
  private static DebtContract mergeDebtContracts(DebtContract first, DebtContract second) {
    if (!first.id().equals(second.id())) {
      throw new IllegalStateException("合并旧债务时新合同 id 不一致: " + first.id() + " vs " + second.id());
    }
    long principal = Math.addExact(first.principal(), second.principal());
    DebtStatus status =
        (first.status() == DebtStatus.DEFAULTED || second.status() == DebtStatus.DEFAULTED)
            ? DebtStatus.DEFAULTED
            : (principal > 0L ? DebtStatus.NORMAL : DebtStatus.SETTLED);
    return new DebtContract(
        first.id(),
        first.debtor(),
        first.creditor(),
        first.unit(),
        first.terms(),
        principal,
        Math.min(first.openedDay(), second.openedDay()),
        maxOptionalLong(first.lastInterestDay(), second.lastInterestDay()),
        maxOptionalLong(first.dueCycle(), second.dueCycle()),
        status);
  }

  private static OptionalLong maxOptionalLong(OptionalLong first, OptionalLong second) {
    if (first.isEmpty()) {
      return second;
    }
    if (second.isEmpty()) {
      return first;
    }
    return OptionalLong.of(Math.max(first.getAsLong(), second.getAsLong()));
  }

  /** 把旧 {@code ClassRow.debts} 数组里的旧债务 id 换成迁移后的新合同 id（去重、保序）。 */
  private static void rewriteLegacyClassRowDebtReferences(
      ObjectNode node, Map<String, String> oldToNew) {
    ObjectNode classes = objectField(node, "classes");
    if (classes == null) {
      return;
    }
    for (Map.Entry<String, JsonNode> entry : iterableFields(classes)) {
      if (!(entry.getValue() instanceof ObjectNode row)) {
        continue;
      }
      JsonNode refs = row.get("debts");
      if (refs == null || refs.isNull()) {
        continue;
      }
      if (!refs.isArray()) {
        throw new IllegalStateException("ClassRow.debts 必须是数组: " + row);
      }
      var rewritten = JsonNodeFactory.instance.arrayNode();
      Set<String> seen = new LinkedHashSet<>();
      for (JsonNode ref : refs) {
        String oldId = textOfId(ref);
        if (oldId == null) {
          throw new IllegalStateException("ClassRow.debts 的元素必须是旧债务 id: " + ref);
        }
        String newId = oldToNew.get(oldId);
        if (newId == null) {
          throw new IllegalStateException("ClassRow.debts 引用了旧 debts 表里不存在的债务: " + oldId);
        }
        if (seen.add(newId)) {
          rewritten.add(newId);
        }
      }
      row.set("debts", rewritten);
    }
  }

  /**
   * 旧 {@code remove} 键解析：{@code debt-c<周期>-<债务人>><债权人>-<商品>} ⇒ 新合同 id。
   *
   * <p>★ <b>边界如实记</b>：旧格式没有转义，商品名/主体 id 含 {@code "-"}/{@code ">"} 时没有唯一解析； 本方法只覆盖既有生产格式（商品段取最后一个
   * {@code "-"}、主体按 {@code ">>"} 分）。旧生产路径从不删除 债务（还清只把本金写成 0），故该分支主要服务手工/外部变更集。
   */
  private static DebtContractId legacyDebtContractIdFromOldKey(String oldId) {
    String prefix = "debt-c";
    if (!oldId.startsWith(prefix)) {
      throw new IllegalStateException("旧 debts remove 键不是已知格式: " + oldId);
    }
    int cycleEnd = oldId.indexOf('-', prefix.length());
    if (cycleEnd < 0) {
      throw new IllegalStateException("旧 debts remove 键缺周期段: " + oldId);
    }
    String cycleText = oldId.substring(prefix.length(), cycleEnd);
    if (cycleText.isEmpty()) {
      throw new IllegalStateException("旧 debts remove 键周期段为空: " + oldId);
    }
    try {
      Long.parseLong(cycleText);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("旧 debts remove 键周期段不是数字: " + oldId, e);
    }
    String rest = oldId.substring(cycleEnd + 1);
    int sides = rest.indexOf(">>");
    if (sides < 0) {
      throw new IllegalStateException("旧 debts remove 键缺债务人与债权人的 >> 分隔: " + oldId);
    }
    int commodityDash = rest.lastIndexOf('-');
    if (commodityDash <= sides) {
      throw new IllegalStateException("旧 debts remove 键缺商品段: " + oldId);
    }
    String debtorText = rest.substring(0, sides);
    String creditorText = rest.substring(sides + 2, commodityDash);
    String commodityText = rest.substring(commodityDash + 1);
    if (debtorText.isBlank() || creditorText.isBlank() || commodityText.isBlank()) {
      throw new IllegalStateException("旧 debts remove 键有空白段: " + oldId);
    }
    DebtUnit unit =
        "money".equals(commodityText)
            ? DebtUnit.money(MoneyVocabulary.SILVER_CURRENCY)
            : DebtUnit.commodity(new CommodityId(commodityText));
    return DebtContractId.idOf(
        HouseholdId.parse(debtorText),
        HouseholdId.parse(creditorText),
        unit,
        DebtTerms.legacyDefault());
  }

  private static ObjectNode unchangedDeltaNode() {
    ObjectNode unchanged = JsonNodeFactory.instance.objectNode();
    unchanged.put("@class", "unchanged");
    return unchanged;
  }

  /**
   * ★★ R3B.1：{@link AssetShareId} 的**值侧**读入（Map 键走上面的 {@code KeyDeserializer}，两者独立）。
   *
   * <p>★ <b>为什么收两种形态</b>：本 codec 旧版把 {@code UseRightId} 按 record 默认写成 {@code {"value":"use-…"}} （与
   * {@link #PLAIN} 的写出形态一致）；而旧档别名/手写夹具/外部工具可能写裸字符串。两种都必须能读， 否则"旧档可读"只成立于本 codec 自产的字节。★
   * 只认这两种：其它形状（数字/数组/缺 value）⇒ 抛，不静默造 id。
   */
  private static final class AssetShareIdDeserializer extends JsonDeserializer<AssetShareId> {

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

  // ── R3B.2：旧档 production 键/值 reshape（snapshot 与 changeset 两版）──────────────────────

  /**
   * ★★ <b>R3B.2 旧档 {@link EconomyData} 的生产部分整形</b>（在 {@link #migrateLegacyAssetShareComponent}
   * 之后跑）：
   *
   * <ol>
   *   <li>旧 {@code industries.<id>} 节点带 {@code operator/capacity/progressDays/cycleLaborMilli/
   *       cycleInputUsedMilli} ⇒ 合成一条默认 {@code units.<unitId>}（{@code idOf(industry, operator)}、
   *       {@code modeKey = industryId}、进度/劳动/投入原样），并从 {@code capacity}（含 0 值）合成整额 OWNED {@code
   *       assetShares}；然后把这些旧键从 industry 节点摘掉（新 {@code Industry} 模板没有它们，不摘会被严格读入拒）；
   *   <li>{@code relations} 的键与 {@code activity}（旧 = industry 串）按 {@code (industry, operator)} 对齐到
   *       unit； 解析不到 unit 的条目丢弃（没有 unit ⇒ 没有生产活动，等价于旧档产能 0）；
   *   <li>{@code operatorConditions} 的键按值内 {@code industry} 对齐到唯一 unit（0 条丢弃、多条 fail-closed）；
   *   <li>{@code allocations[].activity} 从旧活动标签/旧 industry 串改写为 unit id（解析不到 ⇒ 原样，自由家户劳动）。
   * </ol>
   *
   * <p>★ <b>幂等</b>：新形状（industry 模板 + units + unit 键关系）再跑一遍不改任何节点。
   */
  private static ObjectNode migrateLegacyProductionComponents(ObjectNode node) {
    ObjectNode industries = objectField(node, "industries");
    if (industries == null) {
      return node;
    }
    ObjectNode assetShares = ensureObjectField(node, "assetShares");
    ObjectNode units = ensureObjectField(node, "units");
    Map<String, JsonNode> legacyOperators = new LinkedHashMap<>();
    List<String> industryKeys = new ArrayList<>();
    industries.fieldNames().forEachRemaining(industryKeys::add);
    for (String industryKey : industryKeys) {
      JsonNode value = industries.get(industryKey);
      if (!(value instanceof ObjectNode industry) || !isLegacyIndustry(industry)) {
        continue;
      }
      JsonNode operatorNode = industry.get("operator");
      if (operatorNode == null || !operatorNode.isObject()) {
        ActorRef fallback =
            RegimeOperators.defaultOperator(
                new RegimeId(textOfId(industry.get("regime"))), new IndustryId(industryKey));
        operatorNode = actorNode(fallback.kind().name(), fallback.id());
      }
      ActorRef operator = actorOf(operatorNode);
      ProductionUnitId unitId = ProductionUnitId.idOf(new IndustryId(industryKey), operator);
      legacyOperators.put(industryKey, operatorNode.deepCopy());
      if (!hasShareForIndustry(assetShares, industryKey)) {
        synthesizeOwnedShares(assetShares, industryKey, operatorNode, industry.get("capacity"));
        if (!hasShareForIndustry(assetShares, industryKey)) {
          // capacity 与 capacityPerUnit 都空 ⇒ 没有任何实物可登记 ⇒ 不造 unit（旧档规模恒 0 的等价路径）
          industry.remove("operator");
          industry.remove("capacity");
          industry.remove("progressDays");
          industry.remove("cycleLaborMilli");
          industry.remove("cycleInputUsedMilli");
          continue;
        }
      }
      JsonNode inputUsed = industry.get("cycleInputUsedMilli");
      ObjectNode unitNode = JsonNodeFactory.instance.objectNode();
      unitNode.put("id", unitId.value());
      unitNode.set("industry", idNode(industryKey));
      unitNode.set("operator", operatorNode.deepCopy());
      unitNode.put("modeKey", industryKey);
      unitNode.put("progressDays", longOf(industry.get("progressDays"), 0L));
      unitNode.put("cycleLaborMilli", longOf(industry.get("cycleLaborMilli"), 0L));
      unitNode.set(
          "cycleInputUsedMilli",
          inputUsed != null && inputUsed.isObject()
              ? inputUsed.deepCopy()
              : JsonNodeFactory.instance.objectNode());
      units.putIfAbsent(unitId.value(), unitNode);
      industry.remove("operator");
      industry.remove("capacity");
      industry.remove("progressDays");
      industry.remove("cycleLaborMilli");
      industry.remove("cycleInputUsedMilli");
    }
    // 把（既有 + 新合成的）unit 收集成 industry → 候选表，供关系/条件/配额对齐。
    Map<String, List<UnitRef>> unitsByIndustry = unitsByIndustry(units);
    rewriteRelationKeys(unitsByIndustry, industries, legacyOperators, node);
    rewriteConditionKeys(unitsByIndustry, node);
    rewriteAllocationActivities(unitsByIndustry, node);
    return node;
  }

  /**
   * ★★ <b>R3B.2 旧档 {@link EconomyChangeSet} 的生产部分整形</b>：与 snapshot 同一套翻译，但组件是 {@code FieldDelta}
   * 节点（{@code @class} + {@code entries}/{@code upserts}/{@code keys}）。
   *
   * <p>★ <b>做</b>：旧 industry 增量里的 {@code operator/capacity/progress/cycleLabor/cycleInputUsed}
   * 摘掉，并合成 {@code units} 增量（进度/劳动/投入带过去）+ 按需合成 {@code assetShares} 增量（capacity 整额 OWNED）；关系增量 的键与
   * {@code activity} 按关系自己的 operator 重写（自足，不依赖 industries 组件在同一条变更集里）。
   *
   * <p>★ <b>不做（交给 {@code EconomyData} 构造期的迁移器）</b>：{@code operatorConditions} 与 {@code allocations}
   * 的键/activity 对齐 —— 它们需要 base 里的 units 才能判唯一，构造期已有那份状态。
   */
  private static ObjectNode migrateLegacyProductionChangeSetComponents(ObjectNode root) {
    ObjectNode industries = deltaEntries(root.get("industries"));
    Map<String, UnitRef> deltaUnits = new LinkedHashMap<>();
    Map<String, ObjectNode> deltaShareNodes = new LinkedHashMap<>();
    if (industries != null) {
      List<String> keys = new ArrayList<>();
      industries.fieldNames().forEachRemaining(keys::add);
      for (String industryKey : keys) {
        JsonNode value = industries.get(industryKey);
        if (!(value instanceof ObjectNode industry) || !isLegacyIndustry(industry)) {
          continue;
        }
        JsonNode operatorNode = industry.get("operator");
        JsonNode capacityNode = industry.get("capacity");
        ObjectNode sharesInDelta = deltaEntries(root.get("assetShares"));
        boolean hasCapacity = capacityNode instanceof ObjectNode cap && !cap.isEmpty();
        boolean hasSharesInDelta =
            sharesInDelta != null && hasShareForIndustry(sharesInDelta, industryKey);
        if (!hasCapacity && !hasSharesInDelta) {
          // capacity 与同批 assetShares 都没有实物 ⇒ 不造 unit（旧档规模恒 0 的等价路径）；
          // 旧字段仍要摘掉，否则新 Industry 模板绑定会被未知属性拒。
          industry.remove("operator");
          industry.remove("capacity");
          industry.remove("progressDays");
          industry.remove("cycleLaborMilli");
          industry.remove("cycleInputUsedMilli");
          continue;
        }
        if (operatorNode == null || !operatorNode.isObject()) {
          ActorRef fallback =
              RegimeOperators.defaultOperator(
                  new RegimeId(textOfId(industry.get("regime"))), new IndustryId(industryKey));
          operatorNode = actorNode(fallback.kind().name(), fallback.id());
        }
        ActorRef operator = actorOf(operatorNode);
        ProductionUnitId unitId = ProductionUnitId.idOf(new IndustryId(industryKey), operator);
        ObjectNode unitNode = JsonNodeFactory.instance.objectNode();
        unitNode.put("id", unitId.value());
        unitNode.set("industry", idNode(industryKey));
        unitNode.set("operator", operatorNode.deepCopy());
        unitNode.put("modeKey", industryKey);
        unitNode.put("progressDays", longOf(industry.get("progressDays"), 0L));
        unitNode.put("cycleLaborMilli", longOf(industry.get("cycleLaborMilli"), 0L));
        JsonNode inputUsed = industry.get("cycleInputUsedMilli");
        unitNode.set(
            "cycleInputUsedMilli",
            inputUsed != null && inputUsed.isObject()
                ? inputUsed.deepCopy()
                : JsonNodeFactory.instance.objectNode());
        deltaUnits.put(
            unitId.value(),
            new UnitRef(unitId.value(), operatorNode.deepCopy(), industryKey, unitNode));
        if (capacityNode instanceof ObjectNode capNode && !capNode.isEmpty()) {
          ObjectNode synthetic = JsonNodeFactory.instance.objectNode();
          synthesizeOwnedShares(synthetic, industryKey, operatorNode, capNode);
          for (Map.Entry<String, JsonNode> share : iterableFields(synthetic)) {
            if (share.getValue() instanceof ObjectNode shareNode) {
              deltaShareNodes.putIfAbsent(share.getKey(), shareNode);
            }
          }
        }
        industry.remove("operator");
        industry.remove("capacity");
        industry.remove("progressDays");
        industry.remove("cycleLaborMilli");
        industry.remove("cycleInputUsedMilli");
      }
    }
    if (!deltaUnits.isEmpty()) {
      upsertUnitsDelta(root, deltaUnits);
      // ★★ 旧档的**首播/按格追加**变更集把产能总量放在 Industry.capacity 上（没有 assetShares 增量）⇒ 从这里
      //   合成整额 OWNED 份额 upsert，否则重放出"unit 没有份额"的非法中间态（单位守卫当场拒）。
      //   ★ 只在这一批确实是"播种批"时做：assetShares 组件没有增量（unchanged/缺席，即旧形状）**且** relations
      //     组件有增量（播种批一定写 relations；旧代码的日结算只改 industries/meta，relations 不动）。
      boolean sharesUntouched = deltaEntries(root.get("assetShares")) == null;
      boolean looksLikeSeedingBatch = deltaEntries(root.get("relations")) != null;
      if (sharesUntouched && looksLikeSeedingBatch && !deltaShareNodes.isEmpty()) {
        upsertAssetSharesDelta(root, deltaShareNodes);
      }
    }
    ObjectNode relations = deltaEntries(root.get("relations"));
    if (relations != null) {
      // ★★ 幂等判别（与 snapshot 路径的 {@code rewriteRelationKeys} 同义）：关系键若已经是 unit 身份，
      //   不得再按"旧 industry 串"包一层 —— 否则 `unit-farm-…` 会被二次拼成 `unit-unit-farm-…`。
      Set<String> knownUnitIds = new LinkedHashSet<>(deltaUnits.keySet());
      ObjectNode unitsInDelta = deltaEntries(root.get("units"));
      if (unitsInDelta != null) {
        unitsInDelta.fieldNames().forEachRemaining(knownUnitIds::add);
      }
      List<String> keys = new ArrayList<>();
      relations.fieldNames().forEachRemaining(keys::add);
      for (String key : keys) {
        JsonNode value = relations.get(key);
        if (!(value instanceof ObjectNode relation)) {
          continue;
        }
        JsonNode operatorNode = relation.get("operator");
        if (operatorNode == null || !operatorNode.isObject()) {
          continue; // 拿不到 operator：留给构造期迁移器按 actor/唯一 unit 对齐
        }
        // 键已在同批 units 增量里 ⇒ 已是 unit 口径；或键与 activity 逐字相等且形如 {@code unit-…}
        // （新档 unit id 的唯一拼写点见 {@link ProductionUnitId#idOf}）⇒ 已是 unit 口径。
        String activity = textOfId(relation.get("activity"));
        if (knownUnitIds.contains(key)
            || (key.equals(activity) && key.startsWith(PRODUCTION_UNIT_ID_PREFIX))) {
          continue;
        }
        ActorRef operator = actorOf(operatorNode);
        try {
          ProductionUnitId unitId = ProductionUnitId.idOf(new IndustryId(key), operator);
          relations.remove(key);
          relation.put("activity", unitId.value());
          relation.set("operator", operatorNode.deepCopy());
          relations.set(unitId.value(), relation);
        } catch (IllegalArgumentException ignored) {
          // key 不是合法产业 id（混合态）：留给构造期守卫 fail-closed，不在 codec 里猜
        }
      }
    }
    return root;
  }

  /**
   * ★★ 旧 industry 节点判据（R3B.2b 起**按值**判，不再按"键是否出现"）：B.2b 给新模板的 canonical record 末尾加回了 5 个旧档 兼容位 ⇒
   * 新档序列化会写出中性值（{@code operator:null / progressDays:0 / capacity:{} / cycleLaborMilli:0 /
   * cycleInputUsedMilli:{}}）。若仍按"键出现"判，<b>新档会被误当成旧档</b>（凭空合成 unit/份额、破坏新形状往返）。判据与 {@code
   * EconomyData.hasLegacyProductionBits} 逐字一致：任一位非中性才算旧形状。
   */
  private static boolean isLegacyIndustry(ObjectNode industry) {
    return industry.hasNonNull("operator")
        || longOf(industry.get("progressDays"), 0L) > 0L
        || longOf(industry.get("cycleLaborMilli"), 0L) > 0L
        || hasEntries(industry.get("capacity"))
        || hasEntries(industry.get("cycleInputUsedMilli"));
  }

  /** 非空对象（键值表）判据：缺席 / null / 空对象 / 非对象 ⇒ false。 */
  private static boolean hasEntries(JsonNode node) {
    return node instanceof ObjectNode object && !object.isEmpty();
  }

  /** 一格的 capacity 表（旧形状 {@code {"LAND":3100000}}）⇒ 逐项整额 OWNED 份额；空表 ⇒ 不造。 */
  private static void synthesizeOwnedShares(
      ObjectNode assetShares, String industryKey, JsonNode operatorNode, JsonNode capacity) {
    if (!(capacity instanceof ObjectNode capacityNode) || capacityNode.isEmpty()) {
      return;
    }
    List<String> assets = new ArrayList<>();
    capacityNode.fieldNames().forEachRemaining(assets::add);
    for (String assetName : assets) {
      JsonNode quantity = capacityNode.get(assetName);
      if (!quantity.isNumber()) {
        throw new IllegalStateException("旧档 capacity." + assetName + " 必须是整数: " + quantity);
      }
      addOwnedShare(assetShares, industryKey, operatorNode, assetName, quantity.longValue());
    }
  }

  /** 生成一条整额 OWNED 份额节点（id 走 {@link AssetShare#idOf}，sequence 恒 0：capacity 每项至多一条）。 */
  private static void addOwnedShare(
      ObjectNode assetShares,
      String industryKey,
      JsonNode operatorNode,
      String assetName,
      long quantity) {
    AssetKind asset;
    try {
      asset = AssetKind.valueOf(assetName);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("旧档 capacity 的键不是生产资料种类: " + assetName, e);
    }
    ActorRef owner = actorOf(operatorNode);
    AssetShareId shareId =
        AssetShare.idOf(
            new IndustryId(industryKey), asset, owner, owner, AssetShare.RightKind.OWNED, 0L);
    ObjectNode share = JsonNodeFactory.instance.objectNode();
    share.put("id", shareId.value());
    share.set("industry", idNode(industryKey));
    share.put("asset", assetName);
    share.set("owner", operatorNode.deepCopy());
    share.set("operator", operatorNode.deepCopy());
    share.put("quantity", quantity);
    share.put("kind", AssetShare.RightKind.OWNED.name());
    assetShares.putIfAbsent(shareId.value(), share);
  }

  /** 某个 industry 在 {@code assetShares} 节点里是否已有份额行（值内 industry 命中）。 */
  private static boolean hasShareForIndustry(ObjectNode assetShares, String industryKey) {
    for (JsonNode value : assetShares) {
      if (value instanceof ObjectNode share
          && industryKey.equals(textOfId(share.get("industry")))) {
        return true;
      }
    }
    return false;
  }

  /** 把 {@code units} 节点收成 industry → 候选 unit 表（按 units 插入序）。 */
  private static Map<String, List<UnitRef>> unitsByIndustry(ObjectNode units) {
    Map<String, List<UnitRef>> byIndustry = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : iterableFields(units)) {
      JsonNode value = entry.getValue();
      if (!(value instanceof ObjectNode unit)) {
        continue;
      }
      String industryKey = textOfId(unit.get("industry"));
      JsonNode operatorNode = unit.get("operator");
      if (industryKey == null || operatorNode == null || !operatorNode.isObject()) {
        continue;
      }
      byIndustry
          .computeIfAbsent(industryKey, ignored -> new ArrayList<>())
          .add(new UnitRef(entry.getKey(), operatorNode, industryKey, value));
    }
    return byIndustry;
  }

  /** 关系键/activity 对齐：按 relation.operator + 旧 industry 串找唯一 unit；0 条丢弃、多条 fail-closed。 */
  private static void rewriteRelationKeys(
      Map<String, List<UnitRef>> unitsByIndustry,
      ObjectNode industries,
      Map<String, JsonNode> legacyOperators,
      ObjectNode root) {
    ObjectNode relations = objectField(root, "relations");
    if (relations == null) {
      return;
    }
    List<String> keys = new ArrayList<>();
    relations.fieldNames().forEachRemaining(keys::add);
    for (String key : keys) {
      JsonNode value = relations.get(key);
      if (!(value instanceof ObjectNode relation)) {
        continue;
      }
      JsonNode operatorNode = relation.get("operator");
      if (operatorNode == null || !operatorNode.isObject()) {
        operatorNode = legacyOperators.get(key);
      }
      ActorRef operator =
          operatorNode != null && operatorNode.isObject() ? actorOf(operatorNode) : null;
      // ★ 幂等：键已是现存 unit（新形状/多 unit）⇒ 只把值内 activity 对齐到键，不做 industry 解析（避免误判歧义）。
      UnitRef byKey = findUnitById(unitsByIndustry, key);
      if (byKey != null) {
        continue; // 幂等：键已是现存 unit ⇒ 键与值内 activity 是否一致交给 EconomyData 守卫判（不在这里静默改写）
      }
      String industryKey = industryKeyOfRelation(relation, key);
      UnitRef resolved = resolveUnit(unitsByIndustry, industryKey, operator);
      if (resolved == null) {
        relations.remove(key); // 没有 unit ⇒ 这条关系没有生产活动可结算（旧档产能 0 等价）
        continue;
      }
      if (resolved.id().equals(key) && resolved.id().equals(textOfId(relation.get("activity")))) {
        continue; // 已经是 unit 口径（幂等）
      }
      relations.remove(key);
      relation.put("activity", resolved.id());
      relations.set(resolved.id(), relation);
    }
  }

  /** 条件键对齐：值内只有 industry（没有 operator）⇒ 该产业唯一 unit；0 条丢弃、多条 fail-closed。 */
  private static void rewriteConditionKeys(
      Map<String, List<UnitRef>> unitsByIndustry, ObjectNode root) {
    ObjectNode conditions = objectField(root, "operatorConditions");
    if (conditions == null) {
      return;
    }
    List<String> keys = new ArrayList<>();
    conditions.fieldNames().forEachRemaining(keys::add);
    for (String key : keys) {
      JsonNode value = conditions.get(key);
      if (!(value instanceof ObjectNode condition)) {
        continue;
      }
      // ★ 幂等：键已是现存 unit 且 industry 匹配 ⇒ 原样（多 unit 产业的新形状不会被误判成歧义）。
      UnitRef byKey = findUnitById(unitsByIndustry, key);
      if (byKey != null && byKey.industryKey().equals(textOfId(condition.get("industry")))) {
        continue;
      }
      List<UnitRef> candidates =
          unitsByIndustry.getOrDefault(textOfId(condition.get("industry")), List.of());
      if (candidates.isEmpty()) {
        conditions.remove(key);
        continue;
      }
      if (candidates.size() > 1) {
        throw new IllegalStateException(
            "旧档 operatorConditions 的 "
                + key
                + " 指向产业 "
                + textOfId(condition.get("industry"))
                + "，但该产业有多条 unit ⇒ 说不清是哪一个（拒绝猜）: "
                + candidates);
      }
      String unitId = candidates.get(0).id();
      if (unitId.equals(key)) {
        continue;
      }
      conditions.remove(key);
      conditions.set(unitId, condition);
    }
  }

  /** 配额 activity 对齐：仍不是 unit id 的，按 actor.id() 当产业串找唯一 unit；解析不到 ⇒ 原样。 */
  private static void rewriteAllocationActivities(
      Map<String, List<UnitRef>> unitsByIndustry, ObjectNode root) {
    JsonNode allocations = root.get("allocations");
    if (allocations == null || !allocations.isObject()) {
      return;
    }
    for (Map.Entry<String, JsonNode> entry : iterableFields((ObjectNode) allocations)) {
      JsonNode value = entry.getValue();
      if (!(value instanceof ObjectNode allocation)) {
        continue;
      }
      String activity = textOf(allocation.get("activity"));
      if (activity != null && unitIdExists(unitsByIndustry, activity)) {
        continue;
      }
      JsonNode actor = allocation.get("actor");
      if (actor == null || !actor.isObject()) {
        continue;
      }
      String actorId = textOf(actor.get("id"));
      if (actorId == null) {
        continue;
      }
      List<UnitRef> candidates = unitsByIndustry.getOrDefault(actorId, List.of());
      if (candidates.size() == 1) {
        allocation.put("activity", candidates.get(0).id());
      }
    }
  }

  /** root 的 {@code assetShares} 增量节点：把合成的整额份额 upsert 合并进去（变体落点同 {@link #upsertUnitsDelta}）。 */
  private static void upsertAssetSharesDelta(ObjectNode root, Map<String, ObjectNode> shareNodes) {
    ObjectNode delta =
        root.has("assetShares") && root.get("assetShares").isObject()
            ? (ObjectNode) root.get("assetShares")
            : JsonNodeFactory.instance.objectNode();
    String kind = delta.path("@class").asText("");
    if ("remove".equals(kind)) {
      throw new IllegalStateException(
          "旧档变更集同时要删 assetShares 又要从旧 capacity 合成份额（同一件事两处拼写）：" + root.get("assetShares"));
    }
    ObjectNode entries;
    if (delta.isEmpty() || "unchanged".equals(kind)) {
      delta.removeAll();
      delta.put("@class", "upsert");
      entries = JsonNodeFactory.instance.objectNode();
      delta.set("entries", entries);
      root.set("assetShares", delta);
    } else if ("upsert".equals(kind)) {
      entries = (ObjectNode) delta.get("entries");
    } else if ("patch".equals(kind)) {
      entries = (ObjectNode) delta.path("upserts").path("entries");
    } else {
      throw new IllegalStateException("无法识别的 assetShares 增量变体: " + delta);
    }
    for (Map.Entry<String, ObjectNode> share : shareNodes.entrySet()) {
      entries.putIfAbsent(share.getKey(), share.getValue());
    }
  }

  /** root 的 {@code units} 增量节点：把 unit upsert 合并进 units 组件增量（三种变体各自落点）。 */
  private static void upsertUnitsDelta(ObjectNode root, Map<String, UnitRef> unitRefs) {
    ObjectNode delta =
        root.has("units") && root.get("units").isObject()
            ? (ObjectNode) root.get("units")
            : JsonNodeFactory.instance.objectNode();
    String kind = delta.path("@class").asText("");
    if ("remove".equals(kind)) {
      throw new IllegalStateException(
          "旧档变更集同时要删 units 又要从旧 industry 合成 unit（同一件事两处拼写）：" + root.get("units"));
    }
    ObjectNode entries;
    if (delta.isEmpty()) {
      delta.put("@class", "upsert");
      entries = JsonNodeFactory.instance.objectNode();
      delta.set("entries", entries);
      root.set("units", delta);
    } else if ("upsert".equals(kind)) {
      entries = (ObjectNode) delta.get("entries");
    } else if ("patch".equals(kind)) {
      entries = (ObjectNode) delta.path("upserts").path("entries");
    } else if ("unchanged".equals(kind)) {
      delta.removeAll();
      delta.put("@class", "upsert");
      entries = JsonNodeFactory.instance.objectNode();
      delta.set("entries", entries);
    } else {
      throw new IllegalStateException("无法识别的 units 增量变体: " + delta);
    }
    for (UnitRef ref : unitRefs.values()) {
      if (ref.unitNode() instanceof ObjectNode withState) {
        entries.putIfAbsent(ref.id(), withState.deepCopy());
        continue;
      }
      ObjectNode unitNode = JsonNodeFactory.instance.objectNode();
      unitNode.put("id", ref.id());
      unitNode.set("industry", idNode(ref.industryKey()));
      unitNode.set("operator", ref.operatorNode().deepCopy());
      unitNode.put("modeKey", ref.industryKey());
      unitNode.put("progressDays", 0L);
      unitNode.put("cycleLaborMilli", 0L);
      unitNode.set("cycleInputUsedMilli", JsonNodeFactory.instance.objectNode());
      entries.putIfAbsent(ref.id(), unitNode);
    }
  }

  /** 关系值内 {@code activity} / 键 → 旧 industry 串（两种形态都可：{"value":…} 或裸串）。 */
  private static String industryKeyOfRelation(ObjectNode relation, String fallbackKey) {
    String activity = textOfId(relation.get("activity"));
    return activity != null ? activity : fallbackKey;
  }

  /** 从 units 索引里解析唯一候选：优先 operator 相等；否则候选只有一个时用它；多个抛。 */
  private static UnitRef resolveUnit(
      Map<String, List<UnitRef>> unitsByIndustry, String industryKey, ActorRef operator) {
    List<UnitRef> candidates = unitsByIndustry.getOrDefault(industryKey, List.of());
    if (candidates.isEmpty()) {
      return null;
    }
    List<UnitRef> matching = new ArrayList<>();
    for (UnitRef candidate : candidates) {
      if (operator != null && actorOf(candidate.operatorNode()).equals(operator)) {
        matching.add(candidate);
      }
    }
    if (matching.size() == 1) {
      return matching.get(0);
    }
    if (candidates.size() == 1) {
      return candidates.get(0);
    }
    throw new IllegalStateException(
        "旧档关系/条件指名的产业 "
            + industryKey
            + " 有多条 unit，operator="
            + operator
            + " ⇒ 无法确定是哪一条（拒绝猜）: "
            + candidates);
  }

  /** 按 id 找 unit（跨产业索引线性扫；reshape 期规模小，且只在读档一次）。 */
  private static UnitRef findUnitById(Map<String, List<UnitRef>> unitsByIndustry, String unitId) {
    for (List<UnitRef> refs : unitsByIndustry.values()) {
      for (UnitRef ref : refs) {
        if (ref.id().equals(unitId)) {
          return ref;
        }
      }
    }
    return null;
  }

  private static boolean unitIdExists(Map<String, List<UnitRef>> unitsByIndustry, String unitId) {
    for (List<UnitRef> refs : unitsByIndustry.values()) {
      for (UnitRef ref : refs) {
        if (ref.id().equals(unitId)) {
          return true;
        }
      }
    }
    return false;
  }

  /** 一个 unit 的 reshape 中间表示（id + operator 节点 + 产业串 + 完整 unit 节点，后者可空）。 */
  private record UnitRef(String id, JsonNode operatorNode, String industryKey, JsonNode unitNode) {}

  /** 读顶层组件的对象字段；缺席/非对象 ⇒ null（调用方按"没有该组件"处理）。 */
  private static ObjectNode objectField(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    return value instanceof ObjectNode object ? object : null;
  }

  /** 读顶层组件为对象；缺席 ⇒ 新建并挂上。 */
  private static ObjectNode ensureObjectField(ObjectNode node, String field) {
    JsonNode value = node.get(field);
    if (value instanceof ObjectNode object) {
      return object;
    }
    ObjectNode created = JsonNodeFactory.instance.objectNode();
    node.set(field, created);
    return created;
  }

  /**
   * {@code FieldDelta} 节点的"增量值表"：{@code upsert.entries} 或 {@code patch.upserts.entries}；其余变体 ⇒
   * null。
   */
  private static ObjectNode deltaEntries(JsonNode delta) {
    if (!(delta instanceof ObjectNode object)) {
      return null;
    }
    String kind = object.path("@class").asText("");
    if ("upsert".equals(kind)) {
      return objectField(object, "entries");
    }
    if ("patch".equals(kind)) {
      ObjectNode upserts = objectField(object, "upserts");
      return upserts == null ? null : objectField(upserts, "entries");
    }
    return null;
  }

  /** 保序遍历对象字段（Map.entry 形态；Jackson 的 fields() 也能用，这里只为读起来一致）。 */
  private static Iterable<Map.Entry<String, JsonNode>> iterableFields(ObjectNode node) {
    Map<String, JsonNode> fields = new LinkedHashMap<>();
    node.fields().forEachRemaining(entry -> fields.put(entry.getKey(), entry.getValue()));
    return fields.entrySet();
  }

  /** id 值的两种形态：{@code {"value":…}} 或裸字符串。 */
  private static String textOfId(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isTextual()) {
      return node.asText();
    }
    if (node.isObject() && node.hasNonNull("value") && node.get("value").isTextual()) {
      return node.get("value").asText();
    }
    return null;
  }

  /** 必填文本（缺键/空白 ⇒ null；调用方决定 fail-closed 还是走缺省）。 */
  private static String textOf(JsonNode node) {
    return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
  }

  /** long 取值（非数字/缺席 ⇒ fallback）。 */
  private static long longOf(JsonNode node, long fallback) {
    return node != null && node.isNumber() ? node.longValue() : fallback;
  }

  /** {@code {"value":<id>}} 形态的 id 节点。 */
  private static ObjectNode idNode(String value) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("value", value);
    return node;
  }

  /** {@code {"kind":…,"id":…}} 形态的主体节点。 */
  private static ObjectNode actorNode(String kind, String id) {
    ObjectNode node = JsonNodeFactory.instance.objectNode();
    node.put("kind", kind);
    node.put("id", id);
    return node;
  }

  /** 一个主体节点 → {@link ActorRef}（kind 走字段原文；缺失 ⇒ fail-closed）。 */
  private static ActorRef actorOf(JsonNode node) {
    String kind = textOf(node.get("kind"));
    String id = textOf(node.get("id"));
    if (kind == null || id == null) {
      throw new IllegalStateException("主体节点必须给非空 kind/id: " + node);
    }
    return new ActorRef(io.mosire.simos.actor.api.actor.ActorKind.parse(kind), id);
  }

  /** {@link ProductionUnitId} 的值侧读入：裸字符串或 {@code {"value":…}}（与 AssetShareId 同款）。 */
  private static final class ProductionUnitIdDeserializer
      extends JsonDeserializer<ProductionUnitId> {

    @Override
    public ProductionUnitId deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode raw = parser.getCodec().readTree(parser);
      if (raw.isTextual()) {
        return ProductionUnitId.parse(raw.asText());
      }
      if (raw.isObject() && raw.hasNonNull("value") && raw.get("value").isTextual()) {
        return ProductionUnitId.parse(raw.get("value").asText());
      }
      throw new IllegalStateException("ProductionUnitId 必须是字符串或 {value:\"…\"}: " + raw);
    }
  }

  private static <K> KeyDeserializer keyDeserializer(Function<String, K> parse) {
    return new KeyDeserializer() {
      @Override
      public Object deserializeKey(String key, DeserializationContext context) {
        return parse.apply(key);
      }
    };
  }

  /** 同 {@code LedgerCodec} 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("economy 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  @Override
  public String namespace() {
    return "economy";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, EconomyChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((EconomyChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, EconomySnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asEconomySnapshot(snapshot));
  }

  /** 施加变更集，返回**新的**快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    EconomySnapshot economyBase = asEconomySnapshot(base);
    EconomyData next = EconomyChangeSet.apply((EconomyChangeSet) changeSet, economyBase.data());
    return new EconomySnapshot(newMeta.ref(), newMeta.timestamp(), next);
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link EconomyChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return EconomyChangeSet.between(
        asEconomySnapshot(base).data(), asEconomySnapshot(target).data());
  }

  /**
   * 切片下转型的唯一入口：**先验后转**，验不过当场炸（照 {@code LedgerCodec.asLedgerSnapshot}：裸 cast 同样会
   * 抛，但那是**未确认的下转型**，错误信息读不出"这是装配给错了切片"）。
   */
  private static EconomySnapshot asEconomySnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof EconomySnapshot economySnapshot)) {
      throw new IllegalStateException(
          "economy codec 的切片不是 EconomySnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return economySnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("economy 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
