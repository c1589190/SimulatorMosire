package io.mosire.simos.social.api.household;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.mosire.simos.map.hex.HexCoord;

/**
 * 家户位置（2026-10-09 家户/人口架构 §3.1 / §4.1）：只有 {@code HEX | UNIT} 两档。
 *
 * <p>★ <b>为什么 Unit 用不透明字符串</b>：契约层只依赖 map，不能引用 {@code simos-unit} 的 {@code UnitId} ——unit
 * 的存在性/解析由持有 {@code simos-unit} 的组合根校验；这里只存"哪个 unit"这个稳定串。
 *
 * <p>★ <b>两档都是 sealed</b>：新增位置类型必须在 {@code permits} 里显式登记，调用方的 {@code switch} 不会 静默漏档（feasible
 * 的穷尽性由编译器兜底）。
 *
 * <p>★ <b>端点线格式的 Jackson 类型信息在这里声明</b>（2026-10-09 集成缺陷修复）：{@code Timeline} 的全局
 * {@code CHANGESET_MAPPER} 看不见 social 模块自己的 codec，而 {@link io.mosire.simos.util.state.ChangeSet}
 * 的嵌套字段里会出现本接口 ⇒ 必须在类型本身上给出 {@code @type} 子类型分派，否则含家户位置的任何 revision
 * 在 {@code replay} 时都会因“abstract types either need to be mapped to concrete types”炸掉。
 * 本注解只服务**跨模块全局 mapper**；{@code SocialCodec} 自己的 mapper 仍可注册更具体的反序列化器。
 *
 * <p>★ {@link #toString()} 是日志用的规范短串（架构 §6 示例 {@code location=HEX:1_0} / {@code UNIT:...}），
 * 不是线格式的解析契约。
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "@type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = HouseholdLocation.Hex.class, name = "hex"),
  @JsonSubTypes.Type(value = HouseholdLocation.Unit.class, name = "unit")
})
public sealed interface HouseholdLocation permits HouseholdLocation.Hex, HouseholdLocation.Unit {

  /** 挂在某一格上的家户。 */
  record Hex(HexCoord hex) implements HouseholdLocation {

    public Hex {
      if (hex == null) {
        throw new IllegalArgumentException("HouseholdLocation.Hex 的 hex 不得为 null");
      }
    }

    @Override
    public String toString() {
      return "HEX:" + hex;
    }
  }

  /** 挂在某个 unit 上的家户；{@code unitId} 非空白。 */
  record Unit(String unitId) implements HouseholdLocation {

    public Unit {
      if (unitId == null || unitId.isBlank()) {
        throw new IllegalArgumentException("HouseholdLocation.Unit 的 unitId 不得为空白");
      }
    }

    @Override
    public String toString() {
      return "UNIT:" + unitId;
    }
  }
}
