package io.mosire.simos.culture.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.culture.CultureLog;
import io.mosire.simos.culture.CultureLogSource;
import io.mosire.simos.culture.CultureSnapshot;
import io.mosire.simos.culture.change.CultureChangeSet;
import io.mosire.simos.social.api.id.HouseholdId;
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
 * ★★ <b>culture 模块的 {@link ModuleCodec} 实现</b>（D-031 spec §13 Z1）。形态与 {@code ProductionCodec} /
 * {@code GovCodec} 同制，理由不重复——只记 culture 自己的那点差异。
 *
 * <p>★★ <b>{@link #namespace()} 恒返回字面量 {@code "culture"}</b>：与 {@link CultureSnapshot#namespace()}
 * <b>同字面</b>——{@code SimulationState} 构造期校验"modules 的键 == snapshot.namespace()"，写歪当场抛。
 *
 * <p>★ <b>树里的自定义 Map 键只剩一个</b>：{@code HouseholdId}（{@code householdCultures} 顶层键）。它重写了裸 {@code
 * toString()}，与 {@code static parse} 互逆 ⇒ <b>只注册读侧</b>（写侧 Jackson 默认键序列化器调 {@code toString()}
 * 恰好就对）。漏注册会在<b>该表非空</b>时于解码期炸（表空着时测不到——故往返夹具必须让表非空）。 ★ D-032（D2 直接删）：{@code CultureId}（{@code
 * aptitudeRules} 外层键）与 {@code ProductionSkillKey} （内层键）两个键反序列化器已随该组件一起删除。
 *
 * <p>★ <b>值类型一个注解都不加</b>：{@code CultureData} / {@code HouseholdCulture} / {@code CultureTag} 都是零
 * Jackson 注解的 record。
 *
 * <p>★ <b>字节是内容的纯函数</b>：表一律 {@code LinkedHashMap} 保插入序（冻在状态构造器赋值处）；共享基座没有开 {@code
 * ORDER_MAP_ENTRIES_BY_KEYS} ⇒ 同一份状态编码两次逐字节相同。
 *
 * <p>★ 同时实现 {@link ModuleDiffer}（"一批命令 = 一条 revision"的批量提交需要）：语义委托 {@link
 * CultureChangeSet#between}。
 */
public final class CultureCodec implements ModuleCodec, ModuleDiffer {

  private static final Logger LOG = CultureLog.codec();

  /** 本模块唯一的一台 mapper：共享基座 + 本模块三路键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * 把 {@code CultureChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code ProductionCodec} 同制）：Jackson 会把 {@code
   * isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。{@code isEmpty} 是派生判断、不是状态， <b>不进线格式</b>；领域类型保持零
   * Jackson 注解。
   *
   * <p>★ 共享基座已有一条全局同款规则；本 mixin 是更具体的同一句话，改动面最小。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(CultureChangeSet.class, CultureChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class CultureChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  /**
   * 键（反）序列化器：{@code HouseholdId}（{@code householdCultures} 顶层键）。 ★ D-032（D2 直接删）：{@code CultureId}
   * / {@code ProductionSkillKey} 两个键已随 {@code aptitudeRules} 删除。
   */
  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("culture-json-keys");
    module.addKeyDeserializer(HouseholdId.class, keyDeserializer(HouseholdId::parse));
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

  /** 同 {@code ProductionCodec} 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("culture 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  /** ★★ 恒为 {@code "culture"}：与 {@link CultureSnapshot#namespace()} 同字面（两处，改一处必须改另一处）。 */
  @Override
  public String namespace() {
    return "culture";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, CultureChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((CultureChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, CultureSnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asCultureSnapshot(snapshot));
  }

  /** 施加变更集，返回<b>新的</b>快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    CultureSnapshot cultureBase = asCultureSnapshot(base);
    CultureSnapshot next = ((CultureChangeSet) changeSet).applyTo(cultureBase, newMeta);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "CULTURE_CODEC_APPLIED",
                  CultureLogSource.CULTURE_CODEC,
                  "householdCultures",
                  next.state().householdCultures().size(),
                  "ref",
                  newMeta.ref()));
    }
    return next;
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link CultureChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return CultureChangeSet.between(
        asCultureSnapshot(base).state(), asCultureSnapshot(target).state());
  }

  /**
   * 切片下转型的唯一入口：<b>先验后转</b>，验不过当场炸（照 {@code ProductionCodec.asProductionSnapshot}：裸 cast
   * 同样会抛，但那是未确认的下转型，错误信息读不出"这是装配给错了切片"）。
   */
  private static CultureSnapshot asCultureSnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof CultureSnapshot cultureSnapshot)) {
      // ★ 切片类型错 = 契约违反 ⇒ ERROR，不降级（reason=wrong-slice-type）。只报类型名，不记载荷。
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "CULTURE_CODEC_SLICE_REJECTED",
                  CultureLogSource.CULTURE_CODEC,
                  "reason",
                  "wrong-slice-type",
                  "slice",
                  snapshot == null ? "null" : snapshot.getClass().getName()));
      throw new IllegalStateException(
          "culture codec 的切片不是 CultureSnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return cultureSnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("culture 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
