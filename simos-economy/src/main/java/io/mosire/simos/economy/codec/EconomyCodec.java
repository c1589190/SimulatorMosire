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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.EconomySnapshot;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.DebtId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.LaborAllocationId;
import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.economy.api.id.ShipmentId;
import io.mosire.simos.economy.api.relation.Basis;
import io.mosire.simos.economy.api.relation.CompensationRule;
import io.mosire.simos.economy.api.relation.RuleType;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.RegimeRelations;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.io.IOException;
import java.util.function.Function;

/**
 * economy 模块的 {@link ModuleCodec} 实现（spec §八）。形态与 {@code LedgerCodec} 同制，理由不重复——只记 economy 自己的那点差异。
 *
 * <p>★ 树里的自定义键有七个：{@code IndustryId}（{@code industries} 的键）、{@code CohortKey}（{@code
 * classes}/{@code flows} 的键，H0 起 = <b>家户身份</b>）、{@code DebtId}（{@code debts} 的键）与 {@code
 * CommodityId}（{@code Industry.outputPerUnit} / {@code ClassRow.naturalNeeds} / {@code
 * effectiveDemand} / {@code FlowRow.consumed} / {@code AllocationRule.WageFirst.ownerResidual}
 * 的键），以及 **R2 的两个**：{@code PeopleLotId} （{@code laborSupply} 的键）与 {@code LaborAllocationId}（{@code
 * allocations} 的键）。六者都住在 {@code simos-economy-api}（H0 起 {@code CohortKey} 也在那里；本模块 {@code model}
 * 里那个两段式的旧键已按裁定 K2 删除）， economy 依赖它故够得着（铁律 3 允许）。键反序列化器照裁定 16 在**本模块** 注册，不进共享基座。
 *
 * <p>★★ <b>第七个键是 H4 的市场表键 {@code HexCoord}</b>（{@code 0_0}）：它住在 {@code simos-map}，本模块此前从没把它当过**键**
 * —— 漏注册的症状是"读档时 {@code markets} 的键解析不出来"（Jackson 会去调 {@code HexCoord} 的构造器或报 {@code no
 * String-argument constructor}）。★ 而 {@code HexCoord.toString()} 与 {@code HexCoord.parse} 互逆，
 * 故只需读侧（同上面六个）。
 *
 * <p>★ {@code AssetKind} 作键（{@code dailyInputPerUnit}/{@code capacity}）走 Jackson **默认的枚举键** 绑定（按
 * {@code name()}），无需自定义；其余 ID/键类型都重写了 {@code toString()}（= 裸值）并与各自的 {@code parse} 互为逆，故只需读侧。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：委托 {@link
 * EconomyChangeSet#between(EconomyData, EconomyData)}。
 */
public final class EconomyCodec implements ModuleCodec, ModuleDiffer {

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * ★★ <b>一台"不带本模块兼容层"的 mapper</b>（H2）：新形状的补偿规则交给它 —— 走 Jackson 的默认 record 绑定 （{@code Optional}
   * 由共享基座的 {@code Jdk8Module} 管、{@code Recipient} 的多态注解跟着类型走）。
   *
   * <p>★ 为什么不让 {@link CompensationRuleDeserializer} 自己手写每个字段：那样"一条规则怎么从 JSON 造出来"就有了
   * <b>第二处</b>拼写点（兼容层与默认绑定各一份，迟早漂开）。兼容层只做一件事：<b>把旧节点整形成新节点</b>。
   */
  private static final ObjectMapper PLAIN = SimosObjectMapper.create();

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
    module.addKeyDeserializer(CohortKey.class, keyDeserializer(CohortKey::parse));
    module.addKeyDeserializer(DebtId.class, keyDeserializer(DebtId::parse));
    module.addKeyDeserializer(CommodityId.class, keyDeserializer(CommodityId::parse));
    // ★ R2 起是两张新表的键：laborSupply（PeopleLotId → LaborSupply）与 allocations（LaborAllocationId
    //   → LaborAllocation）。两者都重写了 toString()（= 裸值）并与各自的 parse 互为逆，故只需读侧。
    module.addKeyDeserializer(PeopleLotId.class, keyDeserializer(PeopleLotId::parse));
    module.addKeyDeserializer(LaborAllocationId.class, keyDeserializer(LaborAllocationId::parse));
    // ★★ H4：市场表的键 = **格**（{@code 0_0}）—— 本模块第一次把 HexCoord 当键用（见类注）。
    module.addKeyDeserializer(HexCoord.class, keyDeserializer(HexCoord::parse));
    // ★★ M2.4：在途批次表的键 = ShipmentId（{@code sh-<day>-<seq>}）—— 与上面同一条口径：toString/parse 互逆，只需读侧。
    module.addKeyDeserializer(ShipmentId.class, keyDeserializer(ShipmentId::parse));
    // ★★ H2：补偿规则的**旧档兼容**（旧线格式是单个 `basis`，H2 拆成 `pool` + `weight`）——见下面那个反序列化器。
    module.addDeserializer(CompensationRule.class, new CompensationRuleDeserializer());
    return module;
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
