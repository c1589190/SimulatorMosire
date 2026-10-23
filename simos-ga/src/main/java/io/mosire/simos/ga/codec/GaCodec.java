package io.mosire.simos.ga.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.agentlib.decision.CommitReceipt;
import io.mosire.agentlib.decision.DecisionPackageSnapshot;
import io.mosire.simos.ga.GaLog;
import io.mosire.simos.ga.GaLogSource;
import io.mosire.simos.ga.GaSnapshot;
import io.mosire.simos.ga.change.GaChangeSet;
import io.mosire.simos.ga.id.DecisionPacketId;
import io.mosire.simos.ga.id.DirectiveId;
import io.mosire.simos.ga.id.EffectId;
import io.mosire.simos.ga.id.MergedEffectPlanId;
import io.mosire.simos.ga.id.VerdictId;
import io.mosire.simos.ga.model.DecisionRecord;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * ★★ <b>ga 模块的 {@link ModuleCodec} 实现</b>（2026-10-12 批次 3a）。形态与 {@link
 * io.mosire.simos.gov.codec.GovCodec} / {@code ActorCodec} 同制，理由不重复——只记 ga 自己的那点差异。
 *
 * <p>★★ <b>{@link #namespace()} 恒返回字面量 {@code "ga"}</b>：与 {@link
 * io.mosire.simos.ga.GaSnapshot#namespace()} <b>同字面</b>——{@code SimulationState} 构造期校验"modules 的键
 * == snapshot.namespace()"，写歪当场抛（装配期那处有牙）。改一处必须同时改另一处。
 *
 * <p>★★ <b>为什么这些命令必须改名</b>（本批的硬约束）：{@code CommandBus.namespaceOf(type)} 取命令类型的<b>前缀</b>
 * 决定"这条变更集施加到哪个命名空间"⇒ handler 搬进 ga 却仍叫 {@code sd.*} 时，{@code GaChangeSet} 会被拿去喂 {@code
 * SdCodec}，<b>当场炸</b>。名称与切片的绑定不是风格问题。
 *
 * <p>★ <b>树里的自定义键有六个</b>：{@code DecisionPacketId}（{@code decisionPackets} 的键）、{@code
 * MergedEffectPlanId}（{@code mergedEffectPlans} 的键）、{@code DirectiveId}（{@code directives}
 * 的键）、{@code VerdictId}（{@code verdicts} 的键）、{@code DecisionMakerId}（{@code decisionMakers} 的键；批次
 * 3b-2 随该表自 {@code SdCodec} 迁来）与 {@code EffectId}（{@code effects} 的键；★ <b>本批 3b-4 随 {@code effects}
 * 表自 {@code SdCodec} 迁来</b>——那一支的注册在本批<b>整条搬走</b>）。六者都在<b>顶层表</b>上；漏注册任一个，都会在<b>对应表非空</b>时于
 * 解码期炸（表空着时测不到——故 {@code GaRoundTripTest} 的夹具必须让七张表非空）。键反序列化器照裁定 16 在<b>本模块</b>注册， 不进共享基座。
 *
 * <p>★★ <b>{@code info} 表的键是 {@code String}（canonical 地址串），不是自定义 id 类型</b> ⇒ <b>不注册</b> {@code
 * DecisionInfoId} 之类的键反序列化器（{@code DecisionInfoId} 是 {@code DecisionInfoEntry} 的<b>字段</b>，全仓无任何
 * {@code Map} 以它为键）。★ 批 3b-2 的派单书写着"新注册 {@code DecisionMakerId} 与 {@code DecisionInfoId} 两个键读侧"——
 * 后者是**对树形状的误述**（树里没有这个键），本 codec 按实际形状只注册<b>一个</b>，不为不存在的键写凭空注册（该类自 3a 起的既有纪律）。
 *
 * <p>★ <b>决策包值类型是 {@code DecisionRecord}</b>：其中嵌套 AgentLib {@code DecisionPackageSnapshot}， {@code
 * PackageItem.args} 是普通 {@code Map<String,Object>}，{@code DecisionRecord.callMeta} 的键是 {@code
 * Integer} ⇒ 都不需要领域键反序列化器。AgentLib record 上的派生读口由本 codec 的 JsonIgnore mixin 摘出线格式。
 *
 * <p>★ <b>键的（反）序列化走各类型自带的"裸 {@code toString()} + 单参 {@code parse}"配对</b>（裁定 R-48-f）：写侧 Jackson
 * 的默认键序列化器调 {@code toString()} 恰好就对了，故<b>只注册读侧</b>（与 {@code GovCodec} 同口径）。
 *
 * <p>★ <b>字节是内容的纯函数</b>：七张表一律 {@code LinkedHashMap} 保插入序（构造器冻在赋值处）；共享基座没有开 {@code
 * ORDER_MAP_ENTRIES_BY_KEYS} ⇒ 同一份状态编码两次逐字节相同。
 *
 * <p>★ 同时实现 {@link ModuleDiffer}（"一批命令 = 一条 revision"的批量提交需要）：语义委托 {@link GaChangeSet#between}。
 */
public final class GaCodec implements ModuleCodec, ModuleDiffer {

  private static final Logger LOG = GaLog.codec();

  /** 本模块唯一的一台 mapper：共享基座 + 六路键反序列化器 + 上游派生读口的 JsonIgnore mixin。 */
  private static final ObjectMapper MAPPER =
      withDomainMixins(SimosObjectMapper.create(keyModule()));

  /**
   * 把 {@code GaChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code GovCodec} / {@code ActorCodec} 同制）：Jackson
   * 会把 {@code isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。{@code isEmpty} 是派生判断不是状态，
   * <b>不进线格式</b>；领域类型保持零 Jackson 注解。共享基座已有一条同款规则，这里保留更具体的同一句话（改动面最小）。
   */
  private static ObjectMapper withDomainMixins(ObjectMapper mapper) {
    mapper.addMixIn(GaChangeSet.class, GaChangeSetMixin.class);
    // ★ AgentLib 的 record 带有 no-arg 派生读口（DecisionPackageSnapshot#settled、
    //   CommitReceipt#itemResults）。Jackson 会把它当属性写出，而共享 mapper 保持
    //   FAIL_ON_UNKNOWN_PROPERTIES=true ⇒ 写出去的快照读不回来。这里只摘掉这两条派生判断；
    //   不关闭未知字段检查，也不改任何状态字段。
    mapper.addMixIn(DecisionPackageSnapshot.class, DecisionPackageSnapshotMixin.class);
    mapper.addMixIn(CommitReceipt.class, CommitReceiptMixin.class);
    mapper.addMixIn(DecisionRecord.class, DecisionRecordMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class GaChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  /** 只承载注解：{@code view()} 是 DecisionRecord 的只读投影方法，不是持久化字段。 */
  abstract static class DecisionRecordMixin {

    @JsonIgnore
    abstract io.mosire.simos.ga.model.DecisionPacket view();
  }

  /** 只承载注解：{@code settled()} 是 AgentLib 的派生判断，不是快照状态。 */
  abstract static class DecisionPackageSnapshotMixin {

    @JsonIgnore
    abstract boolean settled();
  }

  /** 只承载注解：{@code itemResults()} 是 AgentLib 的派生拉链视图，不是回执状态。 */
  abstract static class CommitReceiptMixin {

    @JsonIgnore
    abstract List<?> itemResults();
  }

  /**
   * 六个顶层表的键 {@code DecisionPacketId} / {@code MergedEffectPlanId} / {@code DirectiveId} / {@code
   * VerdictId} / {@code DecisionMakerId} / {@code EffectId}（读侧注册，见类注）。
   */
  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("ga-json-keys");
    module.addKeyDeserializer(DecisionPacketId.class, keyDeserializer(DecisionPacketId::parse));
    module.addKeyDeserializer(MergedEffectPlanId.class, keyDeserializer(MergedEffectPlanId::parse));
    module.addKeyDeserializer(DirectiveId.class, keyDeserializer(DirectiveId::parse));
    module.addKeyDeserializer(VerdictId.class, keyDeserializer(VerdictId::parse));
    module.addKeyDeserializer(DecisionMakerId.class, keyDeserializer(DecisionMakerId::parse));
    module.addKeyDeserializer(EffectId.class, keyDeserializer(EffectId::parse));
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

  /**
   * 编码一条 {@code ga.ApplyDecisionPackage} 命令载荷。
   *
   * <p>必须与 {@link #decodeApplyDecisionPackageRecord(com.fasterxml.jackson.databind.JsonNode)} 用同一台
   * mapper（含 AgentLib 派生读口的 mixin）；否则工具写出的载荷在 handler 里读不回来。
   */
  public static String encodeApplyDecisionPackagePayload(
      long expectedContentVersion, DecisionRecord record) {
    if (expectedContentVersion < 0L) {
      throw new IllegalArgumentException(
          "expectedContentVersion 必须 ≥ 0: " + expectedContentVersion);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("expectedContentVersion", expectedContentVersion);
    payload.put("record", record);
    return writeJson(payload);
  }

  /** 解码 {@code ga.ApplyDecisionPackage} 载荷里的 {@code record} 对象（同一台 mapper）。 */
  public static DecisionRecord decodeApplyDecisionPackageRecord(
      com.fasterxml.jackson.databind.JsonNode recordNode) {
    if (recordNode == null || !recordNode.isObject()) {
      throw new IllegalArgumentException("ApplyDecisionPackage 载荷缺少 record 对象");
    }
    try {
      return MAPPER.treeToValue(recordNode, DecisionRecord.class);
    } catch (Exception e) {
      throw new IllegalArgumentException("ApplyDecisionPackage.record 解码失败: " + e.getMessage(), e);
    }
  }

  /** 同 {@code GovCodec} 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("ga 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  /** ★★ 恒为 {@code "ga"}：与 {@link io.mosire.simos.ga.GaSnapshot#namespace()} 同字面（两处，改一处必须改另一处）。 */
  @Override
  public String namespace() {
    return "ga";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    GaChangeSet changeSet = readJson(json, GaChangeSet.class);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "GA_CODEC_DECODE_CHANGE_SET",
                  GaLogSource.GA_CODEC,
                  "jsonLength",
                  json == null ? -1 : json.length(),
                  "changedComponents",
                  changedComponents(changeSet)));
    }
    return changeSet;
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    GaChangeSet gaChangeSet = asGaChangeSet(changeSet);
    String json = writeJson(gaChangeSet);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "GA_CODEC_ENCODE_CHANGE_SET",
                  GaLogSource.GA_CODEC,
                  "jsonLength",
                  json.length(),
                  "changedComponents",
                  changedComponents(gaChangeSet)));
    }
    return json;
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    GaSnapshot snapshot = readJson(json, GaSnapshot.class);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "GA_CODEC_DECODE_SNAPSHOT",
                  GaLogSource.GA_CODEC,
                  "jsonLength",
                  json == null ? -1 : json.length(),
                  "decisionPackets",
                  snapshot.state().decisionPackets().size(),
                  "mergedEffectPlans",
                  snapshot.state().mergedEffectPlans().size(),
                  "directives",
                  snapshot.state().directives().size(),
                  "verdicts",
                  snapshot.state().verdicts().size(),
                  "decisionMakers",
                  snapshot.state().decisionMakers().size(),
                  "infoAddresses",
                  snapshot.state().info().size(),
                  "effects",
                  snapshot.state().effects().size()));
    }
    return snapshot;
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    GaSnapshot gaSnapshot = asGaSnapshot(snapshot);
    String json = writeJson(gaSnapshot);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "GA_CODEC_ENCODE_SNAPSHOT",
                  GaLogSource.GA_CODEC,
                  "jsonLength",
                  json.length(),
                  "decisionPackets",
                  gaSnapshot.state().decisionPackets().size(),
                  "mergedEffectPlans",
                  gaSnapshot.state().mergedEffectPlans().size(),
                  "directives",
                  gaSnapshot.state().directives().size(),
                  "verdicts",
                  gaSnapshot.state().verdicts().size(),
                  "decisionMakers",
                  gaSnapshot.state().decisionMakers().size(),
                  "infoAddresses",
                  gaSnapshot.state().info().size(),
                  "effects",
                  gaSnapshot.state().effects().size()));
    }
    return json;
  }

  /** 施加变更集，返回<b>新的</b>快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    GaSnapshot gaBase = asGaSnapshot(base);
    GaChangeSet gaChangeSet = asGaChangeSet(changeSet);
    GaSnapshot next = gaChangeSet.applyTo(gaBase, newMeta);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "GA_CODEC_APPLY",
                  GaLogSource.GA_CODEC,
                  "changedComponents",
                  changedComponents(gaChangeSet),
                  "decisionPackets",
                  next.state().decisionPackets().size(),
                  "mergedEffectPlans",
                  next.state().mergedEffectPlans().size(),
                  "directives",
                  next.state().directives().size(),
                  "verdicts",
                  next.state().verdicts().size(),
                  "decisionMakers",
                  next.state().decisionMakers().size(),
                  "infoAddresses",
                  next.state().info().size(),
                  "effects",
                  next.state().effects().size(),
                  "ref",
                  newMeta.ref()));
    }
    return next;
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link GaChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return GaChangeSet.between(asGaSnapshot(base).state(), asGaSnapshot(target).state());
  }

  /** 变更集里 changed 的组件数（七个组件的只读计数；只产出标量元信息，不读也不打印载荷内容）。 */
  private static int changedComponents(GaChangeSet changeSet) {
    int changed = 0;
    changed += changeSet.decisionPackets().changed() ? 1 : 0;
    changed += changeSet.mergedEffectPlans().changed() ? 1 : 0;
    changed += changeSet.directives().changed() ? 1 : 0;
    changed += changeSet.verdicts().changed() ? 1 : 0;
    changed += changeSet.decisionMakers().changed() ? 1 : 0;
    changed += changeSet.info().changed() ? 1 : 0;
    changed += changeSet.effects().changed() ? 1 : 0;
    return changed;
  }

  /**
   * 切片下转型的唯一入口：<b>先验后转</b>，验不过当场炸（照 {@code GovCodec.asGovSnapshot}：裸 cast 同样会抛，
   * 但那是<b>未确认的下转型</b>，错误信息读不出"这是装配给错了切片"）。
   */
  private static GaSnapshot asGaSnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof GaSnapshot gaSnapshot)) {
      // ★ 切片类型错 = 契约违反 ⇒ ERROR，不降级（reason=wrong-slice-type）。只报类型名，不记载荷。
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "GA_CODEC_SLICE_REJECTED",
                  GaLogSource.GA_CODEC,
                  "reason",
                  "wrong-slice-type",
                  "slice",
                  snapshot == null ? "null" : snapshot.getClass().getName()));
      throw new IllegalStateException(
          "ga codec 的切片不是 GaSnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return gaSnapshot;
  }

  /** 变更集下转型的唯一入口：先验后转，验不过当场炸（同 {@link #asGaSnapshot}）。 */
  private static GaChangeSet asGaChangeSet(ChangeSet changeSet) {
    if (!(changeSet instanceof GaChangeSet gaChangeSet)) {
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "GA_CODEC_SLICE_REJECTED",
                  GaLogSource.GA_CODEC,
                  "reason",
                  "wrong-change-set-type",
                  "changeSet",
                  changeSet == null ? "null" : changeSet.getClass().getName()));
      throw new IllegalStateException(
          "ga codec 的变更集不是 GaChangeSet: "
              + (changeSet == null ? "null" : changeSet.getClass().getName()));
    }
    return gaChangeSet;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("ga 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
