package io.mosire.simos.sd.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.sd.change.SdChangeSet;
import io.mosire.simos.sd.id.ArmyId;
import io.mosire.simos.sd.id.CombatId;
import io.mosire.simos.sd.id.CombatStateId;
import io.mosire.simos.sd.id.DecisionMakerId;
import io.mosire.simos.sd.id.DirectiveId;
import io.mosire.simos.sd.id.EffectId;
import io.mosire.simos.sd.id.LossRecordId;
import io.mosire.simos.sd.id.NationId;
import io.mosire.simos.sd.id.VerdictId;
import io.mosire.simos.sd.state.SdSnapshot;
import io.mosire.simos.sd.state.SdState;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.function.Function;

/**
 * sd 模块的 {@link ModuleCodec} 实现（spec §八）：**全仓第 4 个** ModuleCodec。形态与 {@code MapCodec}/{@code
 * UnitCodec} 同制，理由不重复——只记 sd 自己的那点差异。
 *
 * <p>★ 树里的自定义键共有 9 个 ID 类型（{@code nations}/{@code armies}/{@code combats}/{@code
 * combatStates}/{@code decisionMakers}/{@code directives}/{@code effects}/{@code verdicts}/{@code
 * lossRecords} 的键），全住在 simos-sd 自己家里。键反序列化器照裁定 16 在**本模块**注册，不进共享基座。
 *
 * <p>★ **{@code CombatStageId}/{@code CombatOutcomeId} 不作 Map 键**（它们是 {@code Combat.stages} 的值，以及
 * OutcomeOption/CombatState 的字段），因此**不注册**——不给它们写凭空的注册。
 *
 * <p>★ **{@code Address} 作值**（{@code Verdict.subject} / {@code Directive.target} / {@code
 * Action.PutInfo.address}）的绑定 在**共享基座**（{@code SimosObjectMapper.addressValues()}）：{@code Address}
 * 是 util 自己的类型、且是 util 的类型在 util 接上 Jackson 的自然推论（与裁定 38 的键绑定同源）。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 */
public final class SdCodec implements ModuleCodec {

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

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
    module.addKeyDeserializer(NationId.class, keyDeserializer(NationId::parse));
    module.addKeyDeserializer(ArmyId.class, keyDeserializer(ArmyId::parse));
    module.addKeyDeserializer(CombatId.class, keyDeserializer(CombatId::parse));
    module.addKeyDeserializer(CombatStateId.class, keyDeserializer(CombatStateId::parse));
    module.addKeyDeserializer(DecisionMakerId.class, keyDeserializer(DecisionMakerId::parse));
    module.addKeyDeserializer(DirectiveId.class, keyDeserializer(DirectiveId::parse));
    module.addKeyDeserializer(EffectId.class, keyDeserializer(EffectId::parse));
    module.addKeyDeserializer(VerdictId.class, keyDeserializer(VerdictId::parse));
    module.addKeyDeserializer(LossRecordId.class, keyDeserializer(LossRecordId::parse));
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
    return readJson(json, SdChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((SdChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, SdSnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asSdSnapshot(snapshot));
  }

  /** 施加变更集，返回**新的**快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    SdSnapshot sdBase = asSdSnapshot(base);
    SdState next = SdChangeSet.apply((SdChangeSet) changeSet, sdBase.state());
    return new SdSnapshot(newMeta.ref(), newMeta.timestamp(), next);
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
