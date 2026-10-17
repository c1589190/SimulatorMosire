package io.mosire.simos.production.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.CurrencyId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.ProductionLog;
import io.mosire.simos.production.ProductionLogSource;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.HouseholdExperienceKey;
import io.mosire.simos.production.contract.ProductionParticipantKey;
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
 * ★★ <b>production 模块的 {@link ModuleCodec} 实现</b>（D-031 spec §13 Z2）。形态与 {@code GovCodec} / {@code
 * EconomyCodec} 同制，理由不重复——只记 production 自己的那点差异。
 *
 * <p>★★ <b>{@link #namespace()} 恒返回字面量 {@code "production"}</b>：与 {@link
 * ProductionSnapshot#namespace()} <b>同字面</b>——{@code SimulationState} 构造期校验"modules 的键 ==
 * snapshot.namespace()"，写歪当场抛。改一处必须同时改另一处。
 *
 * <p>★★ <b>D-032：树里的自定义 Map 键有七类</b>（{@code IndustryId} / {@code ProductionModeId} / {@code
 * ProductionUnitId} / {@code ExperienceTrackId} / {@code HouseholdId} / {@code
 * HouseholdExperienceKey} / {@code ProductionParticipantKey}）+ 定义内层表的 {@code CommodityId} / {@code
 * CurrencyId}。它们都重写了裸 {@code toString()}，与各自的 {@code static parse} 互逆 ⇒ <b>只注册读侧</b> （写侧 Jackson
 * 默认键序列化器调 {@code toString()} 恰好就对）。漏注册任一个，都会在<b>对应表非空</b>时于解码期炸 （表空着时测不到——故往返夹具必须让每张表非空）。
 *
 * <p>★ <b>值类型不靠 mapper mixin</b>：{@code ProductionData} / {@code IndustryDefinitionView} / {@code
 * ProductionProcess} / {@code ExperienceTrack} / {@code ProductionEfficiencyState} 都是
 * record；多态子形状（如 {@code RolePayment} / {@code ProductionAvailabilityCondition}）把类型注解钉在 sealed 接口上。
 * {@code Jdk8Module} 已在共享基座 {@link SimosObjectMapper#create} 里注册（{@code Optional} 字段走它）。 D1 新增的
 * {@code roles} 等字段随 {@code IndustryDefinitionView} 的 record 组件自动读写，没有第二份 wire shape。
 *
 * <p>★ <b>字节是内容的纯函数</b>：各表一律 {@code LinkedHashMap} 保插入序（冻在状态构造器赋值处）；共享基座没有开 {@code
 * ORDER_MAP_ENTRIES_BY_KEYS} ⇒ 同一份状态编码两次逐字节相同。
 *
 * <p>★ 同时实现 {@link ModuleDiffer}（"一批命令 = 一条 revision"的批量提交需要）：语义委托 {@link
 * ProductionChangeSet#between}。
 */
public final class ProductionCodec implements ModuleCodec, ModuleDiffer {

  private static final Logger LOG = ProductionLog.codec();

  /** 本模块唯一的一台 mapper：共享基座 + 本模块两路键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * 把 {@code ProductionChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code GovCodec} 同制）：Jackson 会把 {@code
   * isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。{@code isEmpty} 是派生判断、不是状态， <b>不进线格式</b>；领域类型保持零
   * Jackson 注解。
   *
   * <p>★ 共享基座已有一条全局同款规则；本 mixin 是更具体的同一句话，改动面最小。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(ProductionChangeSet.class, ProductionChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class ProductionChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  /**
   * ★★ <b>键（反）序列化器</b>（D-032 起）：{@code IndustryId}、{@code ProductionModeId}、 {@code
   * ProductionUnitId}（instances / efficiency 两表的键）、{@code ExperienceTrackId}（经验条表键）、 {@code
   * HouseholdId}（unlocks 表键）、{@code HouseholdExperienceKey}（家户经验账的复合键）与定义内层表的 {@code CommodityId} /
   * {@code CurrencyId}。★ {@code AssetKind} 是 enum，走 Jackson 默认键（name/valueOf）， 不注册。
   *
   * <p>★ <b>写侧不注册</b>：这些 id 都重写了裸 {@code toString()}，Jackson 默认键序列化器调它恰好就对； 只有读侧需要 {@code parse}。★
   * 漏注册任一个，都会在<b>对应表非空</b>时于解码期炸（表空着时测不到 —— 故往返夹具必须让每张表非空）。
   *
   * <p>★ 科技点面的两个键（{@code TechnologyId}/{@code MasteryScope}）已按 D1 随组件一起删除。
   */
  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("production-json-keys");
    module.addKeyDeserializer(IndustryId.class, keyDeserializer(IndustryId::parse));
    module.addKeyDeserializer(ProductionModeId.class, keyDeserializer(ProductionModeId::parse));
    module.addKeyDeserializer(ProductionUnitId.class, keyDeserializer(ProductionUnitId::parse));
    module.addKeyDeserializer(ExperienceTrackId.class, keyDeserializer(ExperienceTrackId::parse));
    module.addKeyDeserializer(HouseholdId.class, keyDeserializer(HouseholdId::parse));
    module.addKeyDeserializer(
        HouseholdExperienceKey.class, keyDeserializer(HouseholdExperienceKey::parse));
    module.addKeyDeserializer(
        ProductionParticipantKey.class, keyDeserializer(ProductionParticipantKey::parse));
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

  /** 同 {@code GovCodec} 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("production 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  /** ★★ 恒为 {@code "production"}：与 {@link ProductionSnapshot#namespace()} 同字面（两处，改一处必须改另一处）。 */
  @Override
  public String namespace() {
    return "production";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, ProductionChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((ProductionChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, ProductionSnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asProductionSnapshot(snapshot));
  }

  /** 施加变更集，返回<b>新的</b>快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    ProductionSnapshot productionBase = asProductionSnapshot(base);
    ProductionSnapshot next = ((ProductionChangeSet) changeSet).applyTo(productionBase, newMeta);
    if (LOG.isDebugEnabled()) {
      // ★★ D-032：组件面从 4 个扩到 8 个（industries/modes/instances/experienceTracks/
      //   householdExperience/unlocks/efficiency/outputQuantityOverrides）；科技点两个组件已随 D1 删除。
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "PRODUCTION_CODEC_APPLIED",
                  ProductionLogSource.PRODUCTION_CODEC,
                  "industries",
                  next.state().industries().size(),
                  "modes",
                  next.state().modes().size(),
                  "instances",
                  next.state().instances().size(),
                  "experienceTracks",
                  next.state().experienceTracks().size(),
                  "householdExperience",
                  next.state().householdExperience().size(),
                  "unlocks",
                  next.state().unlocks().size(),
                  "efficiency",
                  next.state().efficiency().size(),
                  "outputQuantityOverrides",
                  next.state().outputQuantityOverrides().size(),
                  "ref",
                  newMeta.ref()));
    }
    return next;
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link ProductionChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return ProductionChangeSet.between(
        asProductionSnapshot(base).state(), asProductionSnapshot(target).state());
  }

  /**
   * 切片下转型的唯一入口：<b>先验后转</b>，验不过当场炸（照 {@code GovCodec.asGovSnapshot}：裸 cast 同样会抛，
   * 但那是未确认的下转型，错误信息读不出"这是装配给错了切片"）。
   */
  private static ProductionSnapshot asProductionSnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof ProductionSnapshot productionSnapshot)) {
      // ★ 切片类型错 = 契约违反 ⇒ ERROR，不降级（reason=wrong-slice-type）。只报类型名，不记载荷。
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "PRODUCTION_CODEC_SLICE_REJECTED",
                  ProductionLogSource.PRODUCTION_CODEC,
                  "reason",
                  "wrong-slice-type",
                  "slice",
                  snapshot == null ? "null" : snapshot.getClass().getName()));
      throw new IllegalStateException(
          "production codec 的切片不是 ProductionSnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return productionSnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("production 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
