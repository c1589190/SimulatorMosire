package io.mosire.simos.army.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.mosire.simos.army.ArmyId;
import io.mosire.simos.army.ArmyLog;
import io.mosire.simos.army.ArmyLogSource;
import io.mosire.simos.army.ArmySnapshot;
import io.mosire.simos.army.CombatRecordId;
import io.mosire.simos.army.change.ArmyChangeSet;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.DecisionMakerId;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.function.Function;
import org.slf4j.Logger;

/**
 * army 模块的 {@link ModuleCodec} 实现（阶段 D1 / 用户设计 D-012，2026-10-02）。形态与 {@code ActorCodec} / {@code
 * GovCodec} 同制，理由不重复——只记 army 自己的那点差异。
 *
 * <p>★★ <b>{@link #namespace()} 恒返回字面量 {@code "army"}</b>：与 {@link
 * io.mosire.simos.army.ArmySnapshot#namespace()} <b>同字面</b>——{@code SimulationState} 构造期校验"modules
 * 的键 == snapshot.namespace()"，写歪当场抛（装配期那处有牙）。改一处必须同时改另一处。
 *
 * <p>★ <b>树里的自定义键有<b>三个</b>（乙收尾起）</b>：{@code ArmyId}（{@code armies} 的键）、{@code
 * CombatRecordId}（{@code combats} 的键）与 {@code DecisionMakerId}（{@code armyAffiliations} 的键）。{@code
 * CombatRecord} 里的 {@code HexCoord}（交战格）、{@code UnitId}（参与单位）、阶段/结局/损失 id（{@code CombatStageId} /
 * {@code CombatOutcomeId}）都是**值** 不是 Map 键 ⇒ 按 record 值绑定，**不需要**也不应该注册。★ {@code armies}
 * 的键随军队身份（{@link ArmyId}）自 {@code SdState} 搬入，读侧注册随之从 {@code SdCodec}
 * 移到这里（**唯一拼写点**，不是两份）。键反序列化器照裁定 16 在**本模块**注册，不进共享基座。
 *
 * <p>★ <b>键的（反）序列化走各类型自带的"裸 {@code toString()} + 单参 {@code parse}"配对</b>（裁定 R-48-f）：写侧 Jackson
 * 的默认键序列化器调 {@code toString()} 恰好就对了，故<b>只注册读侧</b>（与 {@code ActorCodec} 同口径）。
 *
 * <p>★ <b>值类型一个注解都不加</b>：{@code CombatRecord} / {@code CombatStage} / {@code CombatOutcome} /
 * {@code CombatUnitLoss} / {@code HexCoord} / {@code UnitId} / {@code CompositionDelta} 都是零 Jackson
 * 注解的 record，走默认的 record 序列化；两个 {@link java.util.Optional} 组件（{@code selectedOutcomeId} / {@code
 * rollSeed}）由共享基座的 {@code Jdk8Module} 承载（与 unit 快照同一条依赖，见 {@code SimosObjectMapper} 的类注）。
 *
 * <p>★ <b>字节是内容的纯函数</b>：{@code combats} 是 {@code LinkedHashMap} 保插入序、各记录的 {@code
 * participants/stages/outcomes/losses} 一律保序 {@code List.copyOf}（构造器冻在赋值处）；共享基座没有开 {@code
 * ORDER_MAP_ENTRIES_BY_KEYS} ⇒ 同一份状态编码两次逐字节相同。
 *
 * <p>★ 同时实现 {@link ModuleDiffer}（"一批命令 = 一条 revision"的批量提交需要）：语义委托 {@link
 * ArmyChangeSet#between(io.mosire.simos.army.ArmyData, io.mosire.simos.army.ArmyData)}。
 */
public final class ArmyCodec implements ModuleCodec, ModuleDiffer {

  private static final Logger LOG = ArmyLog.codec();

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * 把 {@code ArmyChangeSet.isEmpty()} 摘出 JSON 形态（与 {@code ActorCodec} / {@code GovCodec}
   * 同制）：Jackson 会把 {@code isEmpty()} 当成属性 {@code "empty"} 写进字节，而严格读入随即炸掉。{@code isEmpty}
   * 是派生判断不是状态，<b>不进线格式</b>； mixin 放本类（mapper 与 mixin 同处一地、谁也丢不了），领域类型保持零 Jackson 注解。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(ArmyChangeSet.class, ArmyChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class ArmyChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  /**
   * 一路键反序列化器一型：{@code armies} 的键 {@code ArmyId} + {@code combats} 的键 {@code CombatRecordId} +
   * <b>乙收尾新增的 {@code armyAffiliations} 的键 {@code DecisionMakerId}</b>（漏注册 ⇒ 那张表一旦非空就解码炸）。
   */
  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("army-json-keys");
    module.addKeyDeserializer(ArmyId.class, keyDeserializer(ArmyId::parse));
    module.addKeyDeserializer(CombatRecordId.class, keyDeserializer(CombatRecordId::parse));
    module.addKeyDeserializer(DecisionMakerId.class, keyDeserializer(DecisionMakerId::parse));
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
      throw new IllegalStateException("army 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  /**
   * ★★ 恒为 {@code "army"}：与 {@link io.mosire.simos.army.ArmySnapshot#namespace()}
   * 同字面（两处，改一处必须同时改另一处）。
   */
  @Override
  public String namespace() {
    return "army";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, ArmyChangeSet.class);
  }

  /**
   * ★★ <b>{@code BC_UNCONFIRMED_CAST} 的两处精确豁免（2026-10-12 B3 第二域下放时补）</b>：{@link ChangeSet}
   * 是**标记接口** （util 只给接口，字段清单由各模块自己派生）⇒ 从它到本模块的 {@link ArmyChangeSet} 的向下转型在 SpotBugs 眼里"不保证成立"。
   * 但本仓的 {@link ModuleCodec} 契约（C26）保证了它：`encodeChangeSet` / `apply` 只会被 core 用**本 namespace 自己的**
   * 变更集调用（`CommandBus.applyToCandidate` 的类注原文：「`ModuleCodec#apply` 的 cast 在模块自己的地盘（C26）」）。
   *
   * <p>★ 为什么本批才加：这两条 finding 是**潜伏**的——SpotBugs 的 BC 检测器在本模块"只有状态类型、没有工具实现"时不报，
   * 一旦模块里出现较丰富的工具代码（实测：`army.tools.write.StartCombatTool`）就翻出来；同一形态的 7 个兄弟 codec
   * （map/social/unit/sd/economy/actor/gov）目前都没报。⇒ 豁免是把"检测器的环境敏感 + 契约已保证"这件事写明白，
   * 不是掩盖缺陷。**行为零变化**（注解不进字节码语义）。
   */
  @SuppressFBWarnings(
      value = "BC_UNCONFIRMED_CAST",
      justification =
          "ModuleCodec 契约（C26）：encodeChangeSet 只收本 namespace 自己的变更集（ArmyChangeSet），与 8 个兄弟 codec 同形；ChangeSet 是标记接口 ⇒ 检测器无法证明，属误报")
  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((ArmyChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, ArmySnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asArmySnapshot(snapshot));
  }

  /** 施加变更集，返回<b>新的</b>快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @SuppressFBWarnings(
      value = "BC_UNCONFIRMED_CAST",
      justification =
          "ModuleCodec 契约（C26）：apply 只收本 namespace 自己的变更集（ArmyChangeSet），见 encodeChangeSet 的同一条豁免")
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    ArmySnapshot armyBase = asArmySnapshot(base);
    ArmySnapshot next = ((ArmyChangeSet) changeSet).applyTo(armyBase, newMeta);
    if (LOG.isDebugEnabled()) {
      EventLog.channel(LOG)
          .debug(
              LogEvent.of(
                  "ARMY_CODEC_APPLIED",
                  ArmyLogSource.ARMY_CODEC,
                  "armies",
                  next.data().armies().size(),
                  "combats",
                  next.data().combats().size(),
                  "armyAffiliations",
                  next.data().armyAffiliations().size(),
                  "ref",
                  newMeta.ref()));
    }
    return next;
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link ArmyChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return ArmyChangeSet.between(asArmySnapshot(base).data(), asArmySnapshot(target).data());
  }

  /**
   * 切片下转型的唯一入口：<b>先验后转</b>，验不过当场炸（照 {@code ActorCodec.asActorSnapshot}：裸 cast 同样会抛，
   * 但那是<b>未确认的下转型</b>，错误信息读不出"这是装配给错了切片"）。
   */
  private static ArmySnapshot asArmySnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof ArmySnapshot armySnapshot)) {
      throw new IllegalStateException(
          "army codec 的切片不是 ArmySnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return armySnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("army 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
