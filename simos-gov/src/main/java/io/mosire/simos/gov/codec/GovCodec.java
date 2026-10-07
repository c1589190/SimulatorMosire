package io.mosire.simos.gov.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.gov.GovLog;
import io.mosire.simos.gov.GovLogSource;
import io.mosire.simos.gov.GovSnapshot;
import io.mosire.simos.gov.change.GovChangeSet;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * gov 模块的 {@link ModuleCodec} 实现（阶段 10a，计划 §2.2）。形态与 {@link io.mosire.simos.actor.codec.ActorCodec}
 * / {@code EconomyCodec} 同制，理由不重复——只记 gov 自己的那点差异。
 *
 * <p>★★ <b>{@link #namespace()} 恒返回字面量 {@code "gov"}</b>：与 {@link
 * io.mosire.simos.gov.GovSnapshot#namespace()} <b>同字面</b>——{@code SimulationState} 构造期校验"modules 的键
 * == snapshot.namespace()"，写歪当场抛（装配期那处有牙）。改一处必须同时改另一处。
 *
 * <p>★ <b>树里的自定义键有三个</b>：{@code UnitId}（{@code offices}/{@code administrationPlans}/{@code
 * budgetPolicies}/{@code remittanceStates} 四个顶层表的键）、{@code CommodityId} 与 {@code CurrencyId}（{@link
 * io.mosire.simos.gov.GovOfficeState} 六张读数表的键）。三者都在 <b>嵌套位置</b>或顶层表上；
 * 漏注册任一个，都会在<b>对应表非空</b>时于解码期炸（表空着时测不到——故往返夹具必须让读数表非空）。键反序列化器照裁定 16 在 <b>本模块</b>注册，不进共享基座。
 *
 * <p>★ <b>键的（反）序列化走各类型自带的"裸 {@code toString()} + 单参 {@code parse}"配对</b>（裁定 R-48-f）： 写侧 Jackson
 * 的默认键序列化器调 {@code toString()} 恰好就对了，故<b>只注册读侧</b>（与 {@code ActorCodec} 同口径）。
 *
 * <p>★ <b>值类型一个注解都不加</b>：{@code GovOfficeState}/{@code GovSnapshot} 都是零 Jackson 注解的 record，{@code
 * com.fasterxml.jackson.datatype.jdk8.Jdk8Module} 已在共享基座 {@link SimosObjectMapper#create} 里注册（阶段
 * 10a 的 状态树里没有 {@code Optional}，但键/值绑定与 jdk8 能力同源，仍走这台 mapper）。
 *
 * <p>★ <b>字节是内容的纯函数</b>：{@code offices} 与六张读数表一律 {@code LinkedHashMap} 保插入序（构造器冻在赋值处）； 共享基座没有开
 * {@code ORDER_MAP_ENTRIES_BY_KEYS} ⇒ 同一份状态编码两次逐字节相同。
 *
 * <p>★ 同时实现 {@link ModuleDiffer}（"一批命令 = 一条 revision"的批量提交需要）：语义委托 {@link GovChangeSet#between}。
 */
public final class GovCodec implements ModuleCodec, ModuleDiffer {

  private static final Logger LOG = GovLog.codec();

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的三路键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * 把 {@code GovChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code ActorCodec} / {@code LedgerCodec} 同制）：
   * Jackson 会把 {@code isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。{@code isEmpty} 是派生判断
   * 不是状态，<b>不进线格式</b>；领域类型保持零 Jackson 注解。共享基座已有一条同款规则，这里保留更具体的同一句话（改动面最小）。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(GovChangeSet.class, GovChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class GovChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  /** 三路键反序列化器：{@code UnitId} + 两张嵌套读数表的键 {@code CommodityId} / {@code CurrencyId}。 */
  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("gov-json-keys");
    module.addKeyDeserializer(UnitId.class, keyDeserializer(UnitId::parse));
    module.addKeyDeserializer(CommodityId.class, keyDeserializer(CommodityId::parse));
    module.addKeyDeserializer(CurrencyId.class, keyDeserializer(CurrencyId::parse));
    return module;
  }

  private static <K> KeyDeserializer keyDeserializer(Function<String, K> parse) {
    return new KeyDeserializer() {
      @Override
      public Object deserializeKey(String key, DeserializationContext context) {
        return parse.apply(key);
      }
    };
  }

  /** 同 {@code ActorCodec} 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("gov 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  /**
   * ★★ 恒为 {@code "gov"}：与 {@link io.mosire.simos.gov.GovSnapshot#namespace()} 同字面（两处，改一处必须改另一处）。
   */
  @Override
  public String namespace() {
    return "gov";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, GovChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((GovChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, GovSnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asGovSnapshot(snapshot));
  }

  /** 施加变更集，返回<b>新的</b>快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    GovSnapshot govBase = asGovSnapshot(base);
    GovSnapshot next = ((GovChangeSet) changeSet).applyTo(govBase, newMeta);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "GOV_CODEC_APPLIED",
                  GovLogSource.GOV_CODEC,
                  "offices",
                  next.state().offices().size(),
                  "ref",
                  newMeta.ref()));
    }
    return next;
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link GovChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return GovChangeSet.between(asGovSnapshot(base).state(), asGovSnapshot(target).state());
  }

  /**
   * 切片下转型的唯一入口：<b>先验后转</b>，验不过当场炸（照 {@code ActorCodec.asActorSnapshot}：裸 cast 同样会抛，
   * 但那是<b>未确认的下转型</b>，错误信息读不出"这是装配给错了切片"）。
   */
  private static GovSnapshot asGovSnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof GovSnapshot govSnapshot)) {
      // ★ §4.2：切片类型错 = 契约违反 ⇒ ERROR，不降级（reason=wrong-slice-type）。只报类型名，不记载荷。
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "GOV_CODEC_SLICE_REJECTED",
                  GovLogSource.GOV_CODEC,
                  "reason",
                  "wrong-slice-type",
                  "slice",
                  snapshot == null ? "null" : snapshot.getClass().getName()));
      throw new IllegalStateException(
          "gov codec 的切片不是 GovSnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return govSnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("gov 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
