package io.mosire.simos.gov;

import io.mosire.simos.unit.UnitId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * gov 切片的完整状态树（阶段 10a，计划 §2.2）：<b>唯一组件</b>是一张"GOV 单位 → 该单位每 tick 行政读数"的表。
 *
 * <p>★ <b>为什么读数不写在 unit 片</b>（计划 §1 的时序约束）：{@code gov} 每 tick 变，而 {@code UnitTimeParticipant} 与 gov
 * 的日结算分属不同写者；同一模块只能有一个写者，否则 {@code TimeProposalResolver} 按同名模块**拒整次推进**。
 * 编制字段（staff/policy/superiorGov/level）是慢变事实，住在 {@code Unit.module}；这里只放每 tick 读数。
 *
 * <p>★★ <b>跨表同键不变式</b>（照 {@code ActorData} 的"actors 的每个键必须等于其 {@code Actor.ref()}"）： {@code
 * offices} 的每个键必须等于其 {@link GovOfficeState#unitId()}。否则同一个 GOV 单位就有两处可能不一致的记录， 而读侧（GUI /
 * 决策人）拿到哪一份全看遍历路径——这条守卫在构造期当场抛。
 *
 * <p>★ <b>缺键 = 空</b>（照 {@code ActorData} 的旧档兼容口径）：{@code offices} 为 {@code null}（Jackson 对缺失键的
 * record 缺参）⇒ 收成空表，<b>此处不抛</b>——gov 切片是阶段 10a 新引入的，这一条让"还没落过 gov 的旧档/夹具"仍能读回， 而不是把旧档整份读死。方向是
 * fail-closed：没提该切片 = 没有任何读数。
 *
 * <p>★ <b>保序不可变</b>：{@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结， <b>绝不用
 * {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立。
 *
 * @param offices GOV 单位 → 每 tick 读数（键 == 值内 {@code unitId}；保序不可变；缺键读成 {@link Map#of()}）
 */
public record GovState(Map<UnitId, GovOfficeState> offices) {

  public GovState {
    // ★ 缺键（null）⇒ 空表，见类注释（旧档/夹具兼容，fail-closed 方向）。
    if (offices == null) {
      offices = Map.of();
    }
    Map<UnitId, GovOfficeState> copy = new LinkedHashMap<>();
    for (Map.Entry<UnitId, GovOfficeState> entry : offices.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("offices 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().unitId())) {
        throw new IllegalArgumentException(
            "offices 的键必须与 GovOfficeState.unitId 一致：键="
                + entry.getKey()
                + "，值内 unitId="
                + entry.getValue().unitId());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    offices = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /** 往返用例的起点：空表（= 本世界还没有任何 GOV 读数）。 */
  public static GovState empty() {
    return new GovState(Map.of());
  }

  /** 一个组件一个 with（照 {@code ActorData} / {@code EconomyData} 的形制）。 */
  public GovState withOffices(Map<UnitId, GovOfficeState> value) {
    return new GovState(value);
  }

  /**
   * ★ <b>单个 GOV 读数的写入口</b>：<b>键从值派生</b>（{@code office.unitId()} 就是键）。
   *
   * <p>★ 为什么必须有它：{@link io.mosire.simos.util.state.FieldDelta} 的 key 由 {@code toString()} 产出、 重建时用
   * {@code parse} 还原，而"键与值内 unitId 一致"由构造器判。若只把表暴露成 {@code Map}，调用方就得自己拼键—— 同一个聚合键就有了第二个拼写点（照
   * {@code ActorData.withActor} 的同一条理由）。
   *
   * <p>★ 同一个键写两次 = 后写覆盖前写（{@code LinkedHashMap} 的 {@code put} 保留首次插入位置、只换值）。
   */
  public GovState withOffice(GovOfficeState office) {
    Objects.requireNonNull(office, "office");
    Map<UnitId, GovOfficeState> next = new LinkedHashMap<>(offices);
    next.put(office.unitId(), office);
    return new GovState(next);
  }
}
