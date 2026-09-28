package io.mosire.simos.economy.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.AssetShareId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.MembershipId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.labor.LaborAllocation;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.economy.model.FlowRow;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * economy 模块的 {@link ModuleCodec} 实现（spec §八）。形态与 {@code LedgerCodec} 同制，理由不重复——只记 economy 自己的那点差异。
 *
 * <p>★ <b>树里的自定义键（读侧注册；写侧靠各自的 {@code toString()}）</b>：{@code IndustryId}（{@code industries} /
 * {@code relations} / {@code operatorConditions}）、{@code HouseholdId}（{@code classes} / {@code
 * flows}；旧档的 {@code CohortKey} 规范串由 {@code HouseholdIdDeserializer} 识别并映射成 {@code ofLegacy}）、{@code
 * DebtId} （{@code debts}）、{@code CommodityId}（产业产出/投入、行需求、流水与规则里的商品键）、{@code PeopleLotId} （{@code
 * laborSupply}）、{@code LaborAllocationId}（{@code allocations}）、{@code MembershipId} （{@code
 * memberships}）、{@code AssetShareId}（{@code assetShares}；旧档的 {@code use-…} 键字符串由 {@link
 * AssetShareId#parse(String)} opaque 原样读入）、{@code HexCoord}（{@code markets}， 住在 {@code
 * simos-map}）、{@code ShipmentId}（{@code shipments}）。★ 它们都重写了 {@code toString()} 并与 各自的 {@code
 * parse} 互为逆，故只需读侧；键反序列化器照裁定 16 在**本模块**注册，不进共享基座。★ 漏注册的症状是"读档时键 解析不出来"（Jackson 会去调构造器或报 {@code no
 * String-argument constructor}）。
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

  /**
   * ★★ <b>"纯绑定" mapper</b>：只带键反序列化器（含旧 {@code CohortKey} 键 → {@link HouseholdId} 的识别），
   * <b>不带值类型兼容层</b> —— 兼容层把旧节点整形成新节点后交给它绑定（避免"兼容层再进兼容层"的递归）。
   *
   * <p>★ 先例：H2 的补偿规则兼容层就是"整形后交给 {@code PLAIN}"；S1 的旧档迁移沿用同一分工。 ⇒ "一条记录怎么从 JSON
   * 造出来"永远只有<b>一处</b>拼写点（Jackson 的 record 绑定），兼容层只负责改节点。
   */
  private static final ObjectMapper PLAIN = SimosObjectMapper.create(keyModule());

  /** 本模块唯一的一台 mapper：共享基座 + 键反序列化器 + S1/H2 的值兼容层。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule(), compatModule()));

  /**
   * ★ 把 {@code EconomyChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code LedgerCodec} 同制）：Jackson 会把 {@code
   * isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。{@code isEmpty} 是派生判断不是状态，**不进线格式**； mixin
   * 放本类（mapper 与 mixin 同处一地、谁也丢不了），领域类型保持零 Jackson 注解（{@code AllocationRule} 的 sealed
   * 多态注解除外——那是往返的硬前提）。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(EconomyChangeSet.class, EconomyChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class EconomyChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("economy-json-keys");
    module.addKeyDeserializer(IndustryId.class, keyDeserializer(IndustryId::parse));
    // ★★ S1：classes/flows 的键 = HouseholdId。旧档的键是 CohortKey 规范串 ⇒ 这里做一次"旧视图 → ofLegacy"
    //   识别（新档 id 的 parse 是恒等）。识别器同时注册为**值**反序列化器（Debt.debtor/creditor、ClassRow.id）。
    module.addKeyDeserializer(
        HouseholdId.class, keyDeserializer(EconomyCodec::legacyAwareHouseholdId));
    module.addDeserializer(HouseholdId.class, new HouseholdIdDeserializer());
    module.addKeyDeserializer(DebtId.class, keyDeserializer(DebtId::parse));
    module.addKeyDeserializer(CommodityId.class, keyDeserializer(CommodityId::parse));
    // ★ R2 起是两张新表的键：laborSupply（PeopleLotId → LaborSupply）与 allocations（LaborAllocationId
    //   → LaborAllocation）。两者都重写了 toString()（= 裸值）并与各自的 parse 互为逆，故只需读侧。
    module.addKeyDeserializer(PeopleLotId.class, keyDeserializer(PeopleLotId::parse));
    module.addKeyDeserializer(LaborAllocationId.class, keyDeserializer(LaborAllocationId::parse));
    // ★★ S1：memberships / assetShares 两张新表的键（R3B.1 起后者 = AssetShareId；旧 use-… 串 opaque 可读）。
    module.addKeyDeserializer(MembershipId.class, keyDeserializer(MembershipId::parse));
    module.addKeyDeserializer(AssetShareId.class, keyDeserializer(AssetShareId::parse));
    // ★★ R3B.1：AssetShare 值内的 id 可能是本 codec 旧字节的 {"value":"use-…"}，也可能是裸字符串
    //   （手写/外部工具/旧别名）⇒ 值侧显式收两种（keyModule 同时供 PLAIN 使用）；其它形状仍 fail-closed。
    module.addDeserializer(AssetShareId.class, new AssetShareIdDeserializer());
    // ★★ H4：市场表的键 = **格**（{@code 0_0}）—— 本模块第一次把 HexCoord 当键用（见类注）。
    module.addKeyDeserializer(HexCoord.class, keyDeserializer(HexCoord::parse));
    // ★★ M2.4：在途批次表的键 = ShipmentId（{@code sh-<day>-<seq>}）—— 与上面同一条口径：toString/parse 互逆，只需读侧。
    module.addKeyDeserializer(ShipmentId.class, keyDeserializer(ShipmentId::parse));
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
   * ★ 旧 {@code CohortKey} 规范串（含 {@code |}、且非 legacy- 前缀）⇒ {@code HouseholdId.ofLegacy}；其余原样 parse。
   */
  private static HouseholdId legacyAwareHouseholdId(String text) {
    if (text != null && !text.startsWith(HouseholdId.LEGACY_PREFIX) && text.indexOf('|') >= 0) {
      return HouseholdId.ofLegacy(CohortKey.parse(text));
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
   * ★★ S1：旧档 {@code ClassRow} 的整形（旧键 {@code key} = CohortKey 规范串，没有 {@code id}/{@code view}） ⇒
   * 新形状（{@code id = HouseholdId.ofLegacy(key)}、{@code view = key}）。
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
        migrated.put("id", HouseholdId.ofLegacy(view).value());
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
   * ★★ S1：旧档 {@code FlowRow} 的整形（{@code key = CohortKey} ⇒ {@code id = HouseholdId.ofLegacy(key)}）。
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
        migrated.put("id", HouseholdId.ofLegacy(view).value());
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
   * ★★ S1：旧档 {@code LaborAllocation} 的整形（缺 {@code household}）⇒ 造 {@link HouseholdId#pendingLegacy}
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
        node.put("household", HouseholdId.pendingLegacy(node.get("id").asText()).value());
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
   * ★★ <b>旧档经济状态的读侧整形</b>（R3B.1）：顶层组件键 {@code useRights} → {@code assetShares}，并把值节点的旧形状
   * {@code {id, activity, holder, asset, quantity, kind}} 整成新形状 {@code {id, industry, owner, operator, asset,
   * quantity, kind}}（{@code activity→industry}、{@code holder} 同时填 {@code owner} 与 {@code operator}）。
   *
   * <p>★ <b>为什么必须显式整形、而不是关掉 {@code FAIL_ON_UNKNOWN_PROPERTIES}</b>：关掉会把所有真实漂移字段一起吞掉
   * （铁律 5 的守卫拆一半）；这里只认两个具名旧键、逐字段翻译，其余未知字段照旧 fail-closed。
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
        throw new IllegalStateException(
            "旧资产份额必须同时有 activity 与 holder 两个键（缺一个就无法一对一迁移）: " + object);
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
      node = migrateLegacyAssetShareComponent(node);
      try {
        return PLAIN.treeToValue(node, EconomyData.class);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("EconomyData 解码失败: " + node, e);
      }
    }
  }

  /**
   * ★★ R3B.1：{@link EconomyChangeSet} 的同款旧档读侧兼容（组件键 {@code useRights} → {@code assetShares}，
   * 以及 {@code FieldDelta} 各变体里旧值节点的整形）。
   *
   * <p>★ {@link io.mosire.simos.util.state.FieldDelta} 的 diff/rebuild 机制一字不动：本层只把节点整成新形状，
   * 键解析仍由 {@code EconomyChangeSet.apply} 的 {@code AssetShareId::parse} 一处负责。
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
      node = migrateLegacyAssetShareComponent(node);
      try {
        return PLAIN.treeToValue(node, EconomyChangeSet.class);
      } catch (JsonProcessingException e) {
        throw new IllegalStateException("EconomyChangeSet 解码失败: " + node, e);
      }
    }
  }

  /**
   * ★★ R3B.1：{@link AssetShareId} 的**值侧**读入（Map 键走上面的 {@code KeyDeserializer}，两者独立）。
   *
   * <p>★ <b>为什么收两种形态</b>：本 codec 旧版把 {@code UseRightId} 按 record 默认写成 {@code {"value":"use-…"}}
   * （与 {@link #PLAIN} 的写出形态一致）；而旧档别名/手写夹具/外部工具可能写裸字符串。两种都必须能读， 否则"旧档可读"只成立于本 codec 自产的字节。★
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
