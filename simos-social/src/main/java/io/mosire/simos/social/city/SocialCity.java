package io.mosire.simos.social.city;

import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.map.region.RegionId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 城市节点（social 侧的"城市专属机制"承载者）。
 *
 * <p>★ **与 map 侧 {@code City} 的分工**：map 的 {@code City} 是**地图维**的城市（落点、区域归属、名称），本类型是**社会维**的城市节点
 * ——承载 {@code props} 扩展口与稳定身份。同一座城在两处由**同一个** {@link CityId} 串起（铁律 1：复用身份类型， **不新造**）。
 *
 * <p>★★ **R1（T5）起，城市人口不再是本类型的字段**：它是**派生量** = 该城名下各 {@link
 * io.mosire.simos.social.population.PopulationGroup} 之和（{@link
 * io.mosire.simos.social.SocialData#urbanPopulationAt(CityId)} 现算）。原先那个 {@code population}
 * 字段是**第三份人口账** （农村序列一份、城市一份、经济侧一份），它只会漂移；降级成派生量之后，"城里有多少人"仍然可从 social 自己的数据算出来， 而**不需要**任何地方存它 ——
 * 与 {@link io.mosire.simos.social.SocialData} 类注里那句明令同一条纪律（"要算总量，请**现算**，别找地方存"）。
 *
 * <p>★ {@code region} **允许为空**（{@code Optional.empty()}）="这座城不在任何区域内"。这不是缺失，是合法状态（城市落在无归属的格上）；map 侧
 * {@code City.region} 用裸 {@code null} 表达同一件事，此处按 social 侧既有形制用 {@code Optional}（显式、可 JSON 化）。
 *
 * <p>★ {@code props} 是"**后面要支持专属于城市的额外机制**"的扩展口（初期放生成器的审计量：{@code tier}/{@code
 * catchmentHexes}/{@code localSurplus}/…）。它**保序不可变**：{@code LinkedHashMap} + {@code
 * unmodifiableMap}， **绝不用 {@code Map.copyOf}**——它的迭代序不是内容的纯函数（M2 实测），props
 * 要落盘、要进变更集，迭代序漂移会让同一份数据产生不同字节。 键与值都不得为 null。
 *
 * <p>★ **本类型没有 {@code isXxx()} 形式的实例方法**：Jackson 的 bean 内省会把它们当成 property getter 写进
 * JSON，多出一个字段，令严格读入 当场炸、变更集 JSON 非法（本仓真发生过一次）。派生判断要么不要，要么走静态方法。
 *
 * @param id 稳定身份（与 map 侧同用一个 {@link CityId}）；不得为 null
 * @param name 显示名；**空白即抛**
 * @param at 所在格；不得为 null
 * @param region 所属区域；**可为空**（{@code Optional.empty()} = 无归属）
 * @param props 城市自己的属性表；保序不可变，键值都不得为 null
 */
public record SocialCity(
    CityId id, String name, HexCoord at, Optional<RegionId> region, Map<String, Object> props) {

  public SocialCity {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
    if (at == null) {
      throw new IllegalArgumentException("at 不得为 null");
    }
    if (region == null) {
      throw new IllegalArgumentException("region 不得为 null（无归属用 Optional.empty()）");
    }
    if (props == null) {
      throw new IllegalArgumentException("props 不得为 null");
    }
    Map<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : props.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("props 的键与值都不得为 null: " + entry.getKey());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    props = Collections.unmodifiableMap(copy); // ★ 冻在赋值处（SpotBugs 的 EI_EXPOSE_REP 只认它看得见的）
  }

  /** 改显示名（身份、落点、region、props 都不动）。空白名由构造器拒。 */
  public SocialCity withName(String value) {
    return new SocialCity(id, value, at, region, props);
  }

  /** 换整份 props（**不是合并**；合并语义是调用方的事，见 {@code social.UpdateCity}）。 */
  public SocialCity withProps(Map<String, Object> value) {
    return new SocialCity(id, name, at, region, value);
  }

  /**
   * 换区域归属（身份、显示名、落点、props 都不动）。
   *
   * <p>★ {@link Optional#empty()} = **清空归属**（合法状态，见类注）；{@code null} 由构造器守卫拒 —— "无归属"必须显式写成 {@code
   * Optional.empty()}，不能拿 null 冒充。
   */
  public SocialCity withRegion(Optional<RegionId> value) {
    return new SocialCity(id, name, at, value, props);
  }
}
