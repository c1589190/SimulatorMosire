package io.mosire.simos.unit.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.unit.CommandChainId;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.ModuleCodec;
import io.mosire.simos.util.spi.ModuleDiffer;
import io.mosire.simos.util.state.ChangeSet;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateMeta;
import java.util.function.Function;

/**
 * unit 模块的 {@link ModuleCodec} 实现（spec §八）。形态与 {@code MapCodec} 同制，理由不重复——只记 unit 自己的那点差异。
 *
 * <p>★ 树里的自定义键有 {@code UnitId}（{@code units} 的键）与 {@code CommandChainId}（T1 新增的 {@code
 * commandChains} 的键），都住在 simos-unit 自己家里。键反序列化器照裁定 16 在**本模块**注册，不进共享基座。 树里的 {@code
 * HexCoord}（{@code position} 序列的值、{@code Route} 的路点）都是**值**不是 Map 键，Jackson 按 record
 * 值处理，**不需要**也不应该注册。
 *
 * <p>★ {@link #apply} 的 cast 在模块自己的地盘（C26）：Core 从不 cast。
 *
 * <p>★ **同时实现 {@link ModuleDiffer}**（"一批命令 = 一条 revision" 的原子批量提交需要）：委托 {@link
 * UnitChangeSet#between(UnitState, UnitState)}。
 */
public final class UnitCodec implements ModuleCodec, ModuleDiffer {

  /** 本模块唯一的一台 mapper：共享基座 + 本模块的键反序列化器。 */
  private static final ObjectMapper MAPPER =
      withChangeSetMixin(SimosObjectMapper.create(keyModule()));

  /**
   * ★ **把 {@code UnitChangeSet.isEmpty()} 摘出 JSON 形态**（M4 Task 3 实测新发现，探针只往返过快照、没往返过变更集）：Jackson 会把
   * {@code isEmpty()} 当成 {@code "empty"} 属性写进字节，而严格读入（{@code FAIL_ON_UNKNOWN_PROPERTIES}
   * 保持默认）随即炸掉—— **严格恰好在这里立了功**，把它从"静默写脏存档"变成了"当场响"。{@code isEmpty} 是派生判断不是状态，**不进线格式**；mixin 放本类
   * （mapper 与 mixin 同处一地、谁也丢不了），领域类型保持零 Jackson 注解。与 {@code MapCodec} 同制，理由不重复。
   */
  private static ObjectMapper withChangeSetMixin(ObjectMapper mapper) {
    mapper.addMixIn(UnitChangeSet.class, UnitChangeSetMixin.class);
    return mapper;
  }

  /** 只承载注解，方法体永不执行。 */
  abstract static class UnitChangeSetMixin {

    @JsonIgnore
    abstract boolean isEmpty();
  }

  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("unit-json-keys");
    module.addKeyDeserializer(UnitId.class, keyDeserializer(UnitId::parse));
    module.addKeyDeserializer(CommandChainId.class, keyDeserializer(CommandChainId::parse));
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

  /** 同 {@code MapCodec} 的口径：编码失败是契约故障，以 {@link IllegalStateException} 出面。 */
  private static String writeJson(Object value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("unit 侧 JSON 编码失败: " + value.getClass(), e);
    }
  }

  @Override
  public String namespace() {
    return "unit";
  }

  @Override
  public ChangeSet decodeChangeSet(String json) {
    return readJson(json, UnitChangeSet.class);
  }

  @Override
  public String encodeChangeSet(ChangeSet changeSet) {
    return writeJson((UnitChangeSet) changeSet);
  }

  @Override
  public Snapshot decodeSnapshot(String json) {
    return readJson(json, UnitSnapshot.class);
  }

  @Override
  public String encodeSnapshot(Snapshot snapshot) {
    return writeJson(asUnitSnapshot(snapshot));
  }

  /** 施加变更集，返回**新的**快照：ref/timestamp 来自 {@code newMeta}（C28），不是 base 的。 */
  @Override
  public Snapshot apply(ChangeSet changeSet, Snapshot base, StateMeta newMeta) {
    UnitSnapshot unitBase = asUnitSnapshot(base);
    UnitState next = UnitChangeSet.apply((UnitChangeSet) changeSet, unitBase.state());
    return new UnitSnapshot(newMeta.ref(), newMeta.timestamp(), next);
  }

  /** 从两个切片派生变更集（{@link ModuleDiffer}，铁律 5）：语义委托 {@link UnitChangeSet#between}。 */
  @Override
  public ChangeSet diff(Snapshot base, Snapshot target) {
    return UnitChangeSet.between(asUnitSnapshot(base).state(), asUnitSnapshot(target).state());
  }

  /**
   * 切片下转型的唯一入口：**先验后转**，验不过当场炸。
   *
   * <p>★ 原先这里是裸 {@code (UnitSnapshot) base}。它合法，但那是**未确认的下转型**——{@code Snapshot} 有 map/social/unit
   * 三个实现，转错只在下游落成 {@code ClassCastException}，读不出"这是装配给错了切片"。改成 {@code instanceof} 之后连 cast
   * 都不存在，错误信息指名道姓。
   */
  private static UnitSnapshot asUnitSnapshot(Snapshot snapshot) {
    if (!(snapshot instanceof UnitSnapshot unitSnapshot)) {
      throw new IllegalStateException(
          "unit codec 的切片不是 UnitSnapshot: "
              + (snapshot == null ? "null" : snapshot.getClass().getName()));
    }
    return unitSnapshot;
  }

  private static <T> T readJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (Exception e) {
      throw new IllegalStateException("unit 侧 JSON 解码失败: " + type.getSimpleName(), e);
    }
  }
}
