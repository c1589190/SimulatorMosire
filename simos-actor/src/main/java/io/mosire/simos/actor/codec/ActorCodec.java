package io.mosire.simos.actor.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.actor.ActorData;
import io.mosire.simos.actor.ActorSnapshot;
import io.mosire.simos.actor.api.actor.ActorKind;
import io.mosire.simos.actor.api.actor.ActorRef;
import io.mosire.simos.actor.change.ActorChangeSet;
import io.mosire.simos.actor.model.HouseholdAccountKey;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.io.IOException;
import java.util.function.Function;

/**
 * actor 模块的 {@link ModuleCodec} 实现（spec §八）。形态与 {@code LedgerCodec} / {@code EconomyCodec} 同制，理由不重复
 * ——只记 actor 自己的那点差异。
 *
 * <p>★★ <b>{@link #namespace()} 恒返回字面量 {@code "actor"}</b>：与 {@link ActorSnapshot#namespace()}
 * <b>同字面</b> ——{@code SimulationState} 构造期校验"modules 的键 == {@code snapshot.namespace()}"。★
 * 这个字面量全仓<b>三处</b> （上面两处 + Task 9 的 {@code ToolSupport.ACTOR_NAMESPACE}），<b>改一处必须同时改另两处</b>；
 * 三处里只有装配期那一处<b>有牙</b>，故往返测试 真的构造了一次 {@code SimulationState} 来钉它。
 *
 * <p>★★ <b>树里的自定义键有四个</b>：{@code ActorRef}（{@code actors} 的键）、{@code HouseholdAccountKey}（{@code
 * accounts} 的键），以及<b>嵌套</b>在 {@code HouseholdInventory} 里的两个余额表键 —— {@code CommodityId}（{@code
 * balances}）与 {@code CurrencyId}（{@code money}；★ M1.0 补记：钱的键与商品的键同住一层，此前只登记了前者）。 它们都不在 {@code
 * ActorData} 的顶层组件上，漏了任一个会在**解码**时炸。 前两个都住 {@code simos-actor} 本模块，后两个住 {@code
 * simos-economy-api}（契约层，本模块依赖它故够得着， 铁律 3 允许）。键反序列化器照裁定 16 在**本模块**注册，不进共享基座。
 *
 * <p>★★ <b>键的（反）序列化走的就是各类型自带的"裸 {@code toString()} + 单参 {@code parse}"配对</b>（裁定 R-48-f / R-aa） ——★
 * <b>那正是那些配对存在的理由</b>：本仓 {@code FieldDelta} 的键模型假定"各 key 类型自带裸 {@code toString()} + {@code static
 * parse}"。三者都重写了 {@code toString()}（= 裸值）并与各自的 {@code parse} 互为逆 ⇒ <b>只注册读侧</b> （写侧 Jackson
 * 的默认键序列化器调 {@code toString()} 恰好就对了，同 {@code LedgerCodec} 的口径）。
 *
 * <p>★ <b>值类型一个注解都不加</b>：{@code ActorKind} 是 enum，走 Jackson 默认的 {@code name()}；{@code ActorRef} /
 * {@code Actor} / {@code HouseholdInventory} / {@code HexCoord} 是<b>零 Jackson 注解</b>的 record，走默认的 record
 * 序列化 ——<b>本类不引入任何会改格式的注解</b>（本模块的领域类型至今零 Jackson 注解，这条路要保持）。
 *
 * <p>★ <b>字节是内容的纯函数</b>：两张表一律 {@code LinkedHashMap} 保插入序（{@link ActorData} 的构造器冻在赋值处）， 共享基座又**没有**开
 * {@code ORDER_MAP_ENTRIES_BY_KEYS}（台账裁定 11：开了即抛，且打不中靶）⇒ 同一份状态编码两次逐字节相同。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：委托 {@link
 * ActorChangeSet#between(ActorData, ActorData)}。
 */
public final class ActorCodec implements ModuleCodec, ModuleDiffer {

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * ★ 把 {@code ActorChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code LedgerCodec} / {@code EconomyCodec}
   * 同制）： Jackson 会把 {@code isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。{@code isEmpty}
   * 是派生判断不是状态， **不进线格式**；mixin 放本类（mapper 与 mixin 同处一地、谁也丢不了），领域类型保持零 Jackson 注解。
   *
   * <p>★ 共享基座 {@code SimosObjectMapper} 已有一条与领域类型无关的同款规则（{@code Timeline} 那台第四台 mapper 看不见模块级
   * mixin）⇒ 本 mixin 在功能上**冗余**，但保留：它是**更具体的同一句话**，且改动面最小（{@code LedgerCodec} / {@code EconomyCodec}
   * 同样保留）。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(ActorChangeSet.class, ActorChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class ActorChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  /**
   * 四路键反序列化器。<b>只注册读侧</b>：四者都重写了 {@code toString()}（= 裸值），Jackson 的默认键序列化器恰好就调它。
   *
   * <p>★★ <b>前两个是顶层两张表的键，后两个（{@code CommodityId} / {@code CurrencyId}）在嵌套位置</b>（{@code
   * HouseholdInventory.balances} 与 {@code HouseholdInventory.money}）。 前两个**不注册就必炸**：{@code ActorRef} / {@code
   * HouseholdAccountKey} 都是多构件 record， Jackson 推不出键的类型（实测：摘掉任一条 ⇒ 解码当场报 {@code Cannot find a (Map) Key
   * deserializer for type …}）。
   *
   * <p>★★ <b>后两个则在「表空着」时测不到、在「表非空」时才走到</b>——故往返用例的夹具**必须是两张表都非空的** （{@code ActorCodecTest}
   * 的正例正是为此）。★ 而它们的注册**今日与"不注册"行为等价**（实测：两者都是单 {@code String} 构件的 record，Jackson
   * 会退回到"按规范构造器建键"那一档，而它们的构造器与 {@code parse} 的校验一字不差；{@code CurrencyId} 这一条由 M1.0 新增的 {@code
   * moneyRoundTripsThroughTheWireWithZeroKeptAndAbsentDistinct}
   * 在**注册之前**实测为绿）——**仍然显式注册**：本仓的规矩是"键的（反）序列化走各类型自带的 {@code toString()}/{@code parse} 配对"（裁定
   * R-48-f / R-aa），这条规矩要**写出来**，不靠 Jackson 的构造器推断去碰巧满足。★ 尤其**不许**只登记商品键而漏掉货币键：两者同住 {@code
   * HouseholdInventory} 这一层，"登记一半"本身就是一条会漂的规矩。
   */
  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("actor-json-keys");
    // ★★ S1 旧档迁移（actor 侧）：旧三段 HOUSEHOLD id（0_0:rural:poor_peasant）→ 新稳定 id
    //   （legacy-0_0:rural:poor_peasant）。产业型 HOUSEHOLD（weave@0_0）没有冒号 ⇒ 不映射；已是 legacy- ⇒ 幂等。
    module.addKeyDeserializer(
        ActorRef.class, keyDeserializer(ActorCodec::legacyAwareActorRefCanonical));
    module.addKeyDeserializer(
        HouseholdAccountKey.class, keyDeserializer(HouseholdAccountKey::parse));
    module.addKeyDeserializer(CommodityId.class, keyDeserializer(CommodityId::parse));
    module.addKeyDeserializer(CurrencyId.class, keyDeserializer(CurrencyId::parse));
    // ★ 嵌套位置（Actor.ref / HouseholdAccountKey.owner 作为对象构件）走值反序列化器，同一套迁移规则。
    module.addDeserializer(ActorRef.class, new LegacyAwareActorRefDeserializer());
    return module;
  }

  /** ★ S1：旧三段家户 actor 规范串 → 新稳定 actor 规范串；其余原样。 */
  private static ActorRef legacyAwareActorRefCanonical(String canonical) {
    return legacyAwareActorRef(ActorRef.parseCanonical(canonical));
  }

  /**
   * ★★ <b>S1 actor 侧旧档映射</b>：{@code HOUSEHOLD} 且 id 形如旧三段 {@code <q>_<r>:<residence>:<stratum>} ⇒
   * {@code legacy-<旧 id>}。
   *
   * <p>★ <b>只映射家户三段 id</b>（映射规则见 S1.5）：产业型 {@code HOUSEHOLD:weave@0_0} 不含冒号 ⇒ 原样； 已是 {@code
   * legacy-} 前缀 ⇒ 幂等跳过。段数判据只做形状检查（不解析居住/阶层词表，避免 actor 模块反向解释 economy 词表）。
   */
  private static ActorRef legacyAwareActorRef(ActorRef ref) {
    if (ref.kind() != ActorKind.HOUSEHOLD) {
      return ref;
    }
    String id = ref.id();
    if (id.startsWith("legacy-") || id.indexOf('@') >= 0) {
      return ref;
    }
    int first = id.indexOf(':');
    int second = first < 0 ? -1 : id.indexOf(':', first + 1);
    if (first <= 0 || second < 0 || second == first + 1 || second == id.length() - 1) {
      return ref; // 不是旧三段 id：不猜
    }
    return new ActorRef(ActorKind.HOUSEHOLD, "legacy-" + id);
  }

  /** ★ 值反序列化器（{@code Actor.ref} / 嵌套 owner）：收规范串或 {@code {kind,id}} 对象，再走同一映射。 */
  private static final class LegacyAwareActorRefDeserializer extends JsonDeserializer<ActorRef> {

    @Override
    public ActorRef deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      JsonNode node = parser.getCodec().readTree(parser);
      ActorRef ref;
      if (node.isTextual()) {
        ref = ActorRef.parseCanonical(node.asText());
      } else if (node.isObject() && node.hasNonNull("kind") && node.hasNonNull("id")) {
        ref = ActorRef.parse(node.get("kind").asText(), node.get("id").asText());
      } else {
        throw new IllegalStateException("ActorRef 必须是规范串或 {kind,id} 对象: " + node);
      }
      return legacyAwareActorRef(ref);
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
      throw new IllegalStateException("actor 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  /**
   * ★★ 恒为 {@code "actor"}：{@code ModuleCodec} 用它与 {@code Snapshot.namespace()} 对齐（Core
   * 靠它把载荷路由回本模块）， 而 {@code SimulationState} 构造期校验"modules 的键 == {@code snapshot.namespace()}"。★
   * 这个字面量全仓三处同字面， 改这里必须同时改 {@link ActorSnapshot#namespace()} 与 Task 9 的 {@code
   * ToolSupport.ACTOR_NAMESPACE}。
   */
  @Override
  public String namespace() {
    return "actor";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, ActorChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((ActorChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, ActorSnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asActorSnapshot(snapshot));
  }

  /** 施加变更集，返回**新的**快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    ActorSnapshot actorBase = asActorSnapshot(base);
    ActorData next = ActorChangeSet.apply((ActorChangeSet) changeSet, actorBase.data());
    return new ActorSnapshot(newMeta.ref(), newMeta.timestamp(), next);
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link ActorChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return ActorChangeSet.between(asActorSnapshot(base).data(), asActorSnapshot(target).data());
  }

  /**
   * 切片下转型的唯一入口：**先验后转**，验不过当场炸（照 {@code LedgerCodec.asLedgerSnapshot}：裸 cast 同样会抛，但那是
   * **未确认的下转型**，错误信息读不出"这是装配给错了切片"）。
   */
  private static ActorSnapshot asActorSnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof ActorSnapshot actorSnapshot)) {
      throw new IllegalStateException(
          "actor codec 的切片不是 ActorSnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return actorSnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("actor 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
