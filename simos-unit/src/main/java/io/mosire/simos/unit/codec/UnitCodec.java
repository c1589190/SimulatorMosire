package io.mosire.simos.unit.codec;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.KeyDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.mosire.simos.map.region.RegionId;
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
 * <p>★ 树里的自定义键有 {@code UnitId}（{@code units} 的键）、{@code CommandChainId}（T1 新增的 {@code
 * commandChains} 的键）与 {@code RegionId}（辖区阶段 5 起 {@code Jurisdiction.taxRatePerMilleByRegion}
 * 的键）——前两者住在 simos-unit 自己家里，{@code RegionId} 来自 unit 已依赖的 simos-map。键反序列化器照裁定 16
 * 在**本模块**注册，不进共享基座。 树里的 {@code HexCoord}（{@code position} 序列的值、{@code Route} 的路点）都是**值**不是 Map
 * 键，Jackson 按 record 值处理，**不需要**也不应该注册。
 *
 * <p>★ <b>辖区阶段 5 的线格式</b>：{@code Unit} 最后多一个 {@code "jurisdiction"} 键，值走既有的 jdk8 {@code Optional}
 * 绑定（present ⇒ 对象本体，empty ⇒ JSON {@code null}），**不另造格式**。旧档没有该键 ⇒ Jackson 对 record 缺参给 {@code null}
 * ⇒ {@code Unit} 紧凑构造器归一成 {@link java.util.Optional#empty()}（旧档行为逐字不变）；读侧本来就认 {@code null}。
 *
 * <p>★ <b>阶段 9 的线格式</b>：{@code Unit} 再多一个 {@code "module"} 键（紧接 {@code "jurisdiction"}），值同样是 {@code
 * Optional} 绑定——present ⇒ 对象本体，empty ⇒ JSON {@code null}。对象本体走 {@link
 * io.mosire.simos.unit.UnitModule} 类型上的 Jackson 多态注解（属性 {@code "@class"}：{@code "gov"} ⇒ {@link
 * io.mosire.simos.unit.GovFormation}、{@code "army"} ⇒ {@link
 * io.mosire.simos.unit.ArmyFormation}），**本类不注册任何 mixin / 子类型映射**（与 {@code Affiliation}/{@code
 * Action} 同制：类型信息钉在类型上）。旧档没有该键 ⇒ Jackson 对 record 缺参给 {@code null} ⇒ {@code Unit} 紧凑构造器归一成 {@code
 * Optional.empty()}，行为逐字不变。
 *
 * <p>★★ <b>阶段 D1 的线格式</b>：{@code Unit} 再多一个 {@code "stateDescriptions"} 键（第 17 组件，紧接 {@code
 * "module"}）， 值就是普通 JSON 对象 {@code {状态:canonical 地址}}。★ <b>它的键与值都是 {@link String}</b> ⇒
 * <b>不需要</b>注册任何键反序列化器（Jackson 的默认 {@code String} 键绑定就对了），本类<b>一字未改</b>。旧档没有该键 ⇒ Jackson 对 record
 * 缺参给 {@code null} ⇒ {@code Unit} 紧凑构造器归一成空表，行为逐字不变。
 *
 * <p>★★ <b>阶段 D3a 的线格式</b>：第 5/6 组件从 {@code int member} + {@code Map<String,Integer> equipment} 换成
 * **有序条目列表** {@code List<CompositionEntry> manpower} / {@code equipment}：
 *
 * <ul>
 *   <li>JSON 形态 = 数组 {@code [{"type":"…","amount":…}, …]}；{@code CompositionEntry} 是 record、{@code
 *       type} 是 String，**不需要**键反序列化器（与 D1 的 String 键同款），本类不注册新模块；
 *   <li>D-011/R4 不写兼容层：旧档的 {@code "member"} 是未知字段（严格模式当场拒）、旧 {@code "equipment"} 是对象而不是数组
 *       （类型不符当场拒）⇒ 旧档读不出就让它读不出，不在这里做迁移 shim；
 *   <li>变更集侧同样自动覆盖：{@code UnitChangeSet} 的组件是 {@code FieldDelta<Unit>}（值就是整份 Unit），record 的 {@code
 *       equals} 含新列表组件 ⇒ "新组件不进变更集"这类漂移在这里结构上不可能发生（判别力仍由往返测试的反射枚举把守）。
 * </ul>
 *
 * <p>★★ <b>S3a 的线格式</b>：{@code Unit} 再多一个 {@code "households"} 键（第 18 组件，紧接 {@code
 * "stateDescriptions"}），值 = {@code HouseholdId} 的有序数组（record 值，不需要键反序列化器）。★ <b>不做旧档归一</b>
 * （与 manpower/equipment 同口径）：旧档没有该键 ⇒ Jackson 给 {@code null} ⇒ {@code Unit} 紧凑构造器当场拒——旧世界不迁移
 * （架构 §1）⇒ 让它响亮读不出，而不是静默丢家户。变更集侧同样从 record 组件自动派生（铁律 5）。
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

  /** 三个键类型各接一路 {@code parse}（{@code RegionId} 是辖区阶段 5 新进树的自定义键）。 */
  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("unit-json-keys");
    module.addKeyDeserializer(UnitId.class, keyDeserializer(UnitId::parse));
    module.addKeyDeserializer(CommandChainId.class, keyDeserializer(CommandChainId::parse));
    module.addKeyDeserializer(RegionId.class, keyDeserializer(RegionId::parse));
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
