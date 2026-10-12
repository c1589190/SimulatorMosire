package io.mosire.simos.sd.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogChannel;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.function.Function;

/**
 * sd 模块的 {@link ModuleCodec} 实现（spec §八）：**全仓第 4 个** ModuleCodec。形态与 {@code MapCodec}/{@code
 * UnitCodec} 同制，理由不重复——只记 sd 自己的那点差异。
 *
 * <p>★★ <b>树里的自定义键：13 →（批次 3a）11 →（3b-1）9 →（3b-2）8 →（3c）5 →（3d-1）4 →（<b>本批 3d-2</b>）1</b>。今天注册的 这 1
 * 个就是 {@code effects} 那一张表的顶层键 {@code EffectId}。★ D2 的两个键（{@code DecisionPacketId} / {@code
 * MergedEffectPlanId}）<b>已随那两张表整表搬进 {@code simos-ga}</b>；{@code DirectiveId} / {@code VerdictId}
 * 两个键<b>随批次 3b-1 的两张表同样搬走</b>；{@code DecisionMakerId} <b>随 3b-2 的 {@code decisionMakers}
 * 表搬走</b>；{@code NationId}（{@code nations}）、{@code DiplomaticRelationKey}（{@code
 * diplomaticRelations} 的复合键）、{@code DiplomaticEventId}（{@code diplomaticEvents}）<b>随 3c 的三张表搬进
 * {@code simos-gov}</b>（键读侧注册改在 {@code GovCodec}）；{@code ArmyId} <b>随 3d-1 的 {@code armies} 表搬进
 * {@code simos-army}</b>（注册改在 {@code ArmyCodec}）；<b>本批 3d-2 又删掉三个键</b>——{@code CombatId} / {@code
 * CombatStateId} / {@code LossRecordId}：旧战斗族三张表<b>整族退役</b>（<b>不是</b>搬走，army 自阶段 D1 起就有自己的 {@code
 * CombatRecordId} 键，注册在 {@code ArmyCodec}）⇒ 这里<b>不给它们写凭空的注册</b>（键类型本身也已删除）。★ {@code info} 表的键是
 * {@code String}（canonical 地址串）⇒ 那一支**本来就没有**自定义键可注册 （{@code SdInfoId} 是 {@code SdInfoEntry}
 * 的字段，全仓无 {@code Map} 以它为键）。键反序列化器照裁定 16 在**本模块**注册，不进共享基座。
 *
 * <p>★ **{@code Address} 作值**（{@code Verdict.subject} / {@code Directive.target} / {@code
 * Action.PutInfo.address}）的绑定 在**共享基座**（{@code SimosObjectMapper.addressValues()}）：{@code Address}
 * 是 util 自己的类型、且是 util 的类型在 util 接上 Jackson 的自然推论（与裁定 38 的键绑定同源）。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：委托 {@link
 * SdChangeSet#between(SdState, SdState)}——1 个组件比。
 *
 * <p>★ <b>日志（2026-10-23 L2）</b>：五个入口各一条 DEBUG（{@code SD_CODEC_*}），只记组件 changed
 * 计数/规模/文本长度这类元信息，**绝不打印 JSON 原文/载荷**。★ <b>3c 的日志面变化（如实记）</b>：原来 snapshot 相关的三条会带 {@code nations=}
 * 规模字段，而 {@code nations} 已不在本切片 ⇒ 该字段随之删除（不是"降级"，是那条读数换了家——它的规模现在由 {@code GovCodec} 的 {@code
 * GOV_CODEC_APPLIED} 记）。★ <b>3d-1 同款一处</b>：三条里的 {@code armies=} 规模字段同样删除（其规模现由 {@code ArmyCodec} 的
 * {@code ARMY_CODEC_APPLIED} 记）。★ <b>本批 3d-2 再删两条读数</b>：{@code SD_CODEC_DECODE_SNAPSHOT} / {@code
 * SD_CODEC_ENCODE_SNAPSHOT} / {@code SD_CODEC_APPLY} 里的 {@code combats=} 字段随旧战斗族退役而删除 ——
 * 交战记录的规模<b>不是</b>"换了家"（army 的记录是另一本账、另一套 id），故本批<b>不</b>声称它由别的读数接手。本条只改日志字段，不改任何状态、载荷、拒绝码。
 * 本类没有旧形状迁移路径（{@code SdChangeSet} 的紧凑构造器已无缺键归一），故无迁移 INFO。
 */
public final class SdCodec implements ModuleCodec, ModuleDiffer {

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /** 编解码/施加的 DEBUG 通道（只记元信息；{@link #changedComponents} 是唯一计数点）。 */
  private static final LogChannel CODEC = EventLog.channel(SdLog.codec());

  /**
   * ★ 把 {@code SdChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code MapCodec}/{@code UnitCodec} 同制）：Jackson
   * 会把 {@code isEmpty()} 当成 {@code "empty"} 属性写进字节，严格读入随即炸掉。{@code isEmpty} 是派生判断不是状态，**不进线格式**。
   *
   * <p>★ 共享层的 {@link SimosObjectMapper#create} 已对**所有** {@code ChangeSet} 实现做同一件事（裁定 39），本 mixin
   * 因此是 **冗余**——但**保留**：改动面越小越好，且它是更具体的同一句话（spec §一.3 第 4 点记此冗余为有意）。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(SdChangeSet.class, SdChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class SdChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("sd-json-keys");
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

  /** 同 Map/Unit 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("sd 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  @Override
  public String namespace() {
    return "sd";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    ChangeSet changeSet = readJson(json, SdChangeSet.class);
    if (CODEC.isDebugEnabled()) {
      CODEC.debug(
          LogEvent.of(
              "SD_CODEC_DECODE_CHANGE_SET",
              SdLogSource.SD_CODEC,
              "jsonLength",
              json == null ? -1 : json.length(),
              "changedComponents",
              changedComponents((SdChangeSet) changeSet)));
    }
    return changeSet;
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    String json = writeJson((SdChangeSet) changeSet);
    if (CODEC.isDebugEnabled()) {
      CODEC.debug(
          LogEvent.of(
              "SD_CODEC_ENCODE_CHANGE_SET",
              SdLogSource.SD_CODEC,
              "jsonLength",
              json.length(),
              "changedComponents",
              changedComponents((SdChangeSet) changeSet)));
    }
    return json;
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    SdSnapshot snapshot = readJson(json, SdSnapshot.class);
    if (CODEC.isDebugEnabled()) {
      SdState state = snapshot.state();
      CODEC.debug(
          LogEvent.of(
              "SD_CODEC_DECODE_SNAPSHOT",
              SdLogSource.SD_CODEC,
              "jsonLength",
              json == null ? -1 : json.length(),
              "effects",
              state.effects().size()));
    }
    return snapshot;
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    SdSnapshot sdSnapshot = asSdSnapshot(snapshot);
    String json = writeJson(sdSnapshot);
    if (CODEC.isDebugEnabled()) {
      SdState state = sdSnapshot.state();
      CODEC.debug(
          LogEvent.of(
              "SD_CODEC_ENCODE_SNAPSHOT",
              SdLogSource.SD_CODEC,
              "jsonLength",
              json.length(),
              "effects",
              state.effects().size()));
    }
    return json;
  }

  /** 施加变更集，返回**新的**快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    SdSnapshot sdBase = asSdSnapshot(base);
    SdState next = SdChangeSet.apply((SdChangeSet) changeSet, sdBase.state());
    SdSnapshot applied = new SdSnapshot(newMeta.ref(), newMeta.timestamp(), next);
    if (CODEC.isDebugEnabled()) {
      CODEC.debug(
          LogEvent.of(
              "SD_CODEC_APPLY",
              SdLogSource.SD_CODEC,
              "changedComponents",
              changedComponents((SdChangeSet) changeSet),
              "effects",
              next.effects().size()));
    }
    return applied;
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link SdChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return SdChangeSet.between(asSdSnapshot(base).state(), asSdSnapshot(target).state());
  }

  /** 变更集里 changed 的组件数（1 个组件的只读计数；只产出标量元信息，不读也不打印载荷内容）。 */
  private static int changedComponents(SdChangeSet changeSet) {
    return changeSet.effects().changed() ? 1 : 0;
  }

  /** 切片下转型的唯一入口：**先验后转**，验不过当场炸。 */
  private static SdSnapshot asSdSnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof SdSnapshot sdSnapshot)) {
      throw new IllegalStateException(
          "sd codec 的切片不是 SdSnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return sdSnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("sd 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
