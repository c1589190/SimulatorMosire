package io.mosire.simos.sd.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.sd.SdLog;
import io.mosire.simos.sd.SdLogSource;
import io.mosire.simos.sd.change.SdChangeSet;
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

/**
 * sd 模块的 {@link ModuleCodec} 实现（spec §八）：**全仓第 4 个** ModuleCodec。形态与 {@code MapCodec}/{@code
 * UnitCodec} 同制，理由不重复——只记 sd 自己的那点差异。
 *
 * <p>★★ <b>树里的自定义键：13 →（批次 3a）11 →（3b-1）9 →（3b-2）8 →（3c）5 →（3d-1）4 →（3d-2）1 → （<b>本批
 * 3b-4</b>）<b>0</b></b>。最后那 1 个是 {@code effects} 表的顶层键 {@code EffectId}——它随 {@code effects} 整表<b>搬进
 * {@code simos-ga}</b>（注册改在 {@code GaCodec} 的 {@code keyModule()}）， <b>不是被删除</b>。★ D2 的两个键（{@code
 * DecisionPacketId} / {@code MergedEffectPlanId}）已随那两张表整表搬进 {@code simos-ga}；{@code DirectiveId} /
 * {@code VerdictId} 随 3b-1、{@code DecisionMakerId} 随 3b-2； {@code NationId}（{@code nations}）、{@code
 * DiplomaticRelationKey}（{@code diplomaticRelations} 的复合键）、 {@code DiplomaticEventId}（{@code
 * diplomaticEvents}）随 3c 搬进 {@code simos-gov}；{@code ArmyId} 随 3d-1 搬进 {@code simos-army}；{@code
 * CombatId} / {@code CombatStateId} / {@code LossRecordId} 随 3d-2 的旧战斗族 <b>整族退役</b>。★ <b>本批起本 codec
 * 不再注册任何自定义键</b>（树里没有 {@code Map} 了）——这不是"漏注册"， 是空树的形状；{@code info} 表的键是 {@code String}（canonical
 * 地址串）那一支本来就没有自定义键。
 *
 * <p>★ **{@code Address} 作值**（{@code Verdict.subject} / {@code Directive.target} / {@code
 * Action.PutInfo.address}）的绑定 在**共享基座**（{@code SimosObjectMapper.addressValues()}）：{@code Address}
 * 是 util 自己的类型、且是 util 的类型在 util 接上 Jackson 的自然推论（与裁定 38 的键绑定同源）。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：委托 {@link
 * SdChangeSet#between(SdState, SdState)}——本批起 0 个组件比（恒"全 Unchanged"）。
 *
 * <p>★ <b>日志（2026-10-23 L2）</b>：五个入口各一条 DEBUG（{@code SD_CODEC_*}），只记组件 changed
 * 计数/规模/文本长度这类元信息，**绝不打印 JSON 原文/载荷**。★ <b>本批 3b-4 的日志面变化（如实记）</b>：三条 snapshot/apply 读数里的 {@code
 * effects=} 规模字段删除——它不是"降级"，而是那条读数换了家：{@code effects} 的规模现在由 {@code GaCodec} 的 {@code
 * GA_CODEC_APPLIED} 等三条记（与 3c 的 {@code nations=}、3d-1 的 {@code armies=} 同款处置）。 本类没有旧形状迁移路径（{@code
 * SdChangeSet} 的紧凑构造器已无缺键归一），故无迁移 INFO。
 */
public final class SdCodec implements ModuleCodec, ModuleDiffer {

  /** 本模块唯一的一台 mapper：共享基座（本批起本模块没有自定义键反序列化器可注册）。 */
  private static final ObjectMapper MAPPER = withChangeSetMixin(SimosObjectMapper.create());

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
      CODEC.debug(
          LogEvent.of(
              "SD_CODEC_DECODE_SNAPSHOT",
              SdLogSource.SD_CODEC,
              "jsonLength",
              json == null ? -1 : json.length()));
    }
    return snapshot;
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    SdSnapshot sdSnapshot = asSdSnapshot(snapshot);
    String json = writeJson(sdSnapshot);
    if (CODEC.isDebugEnabled()) {
      CODEC.debug(
          LogEvent.of(
              "SD_CODEC_ENCODE_SNAPSHOT", SdLogSource.SD_CODEC, "jsonLength", json.length()));
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
              changedComponents((SdChangeSet) changeSet)));
    }
    return applied;
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link SdChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return SdChangeSet.between(asSdSnapshot(base).state(), asSdSnapshot(target).state());
  }

  /** 变更集里 changed 的组件数（**本批起 0 组件 ⇒ 恒 0**；只产出标量元信息，不读也不打印载荷内容）。 */
  private static int changedComponents(SdChangeSet changeSet) {
    return 0;
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
