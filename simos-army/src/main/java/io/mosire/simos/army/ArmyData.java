package io.mosire.simos.army;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * army 切片的完整状态树（阶段 D1 / 用户设计 D-012，2026-10-02）：**当前唯一组件**是一张"交战记录 id → 交战记录"的表。
 *
 * <p>★★ <b>为什么交战记录归 Army 而不是 sd</b>（D-012 的动机原话："目前在地图上根本看不到当前 tick 在哪里发生了交战"）：单 tick 单场交战的
 * **具体记录**由 Army 模块持有与处理；Unit 侧只留"当前回合状态 ↔ 状态描述地址"的链接（见 {@code Unit.stateDescriptions}）。 旧的 sd
 * 战斗命令族本阶段**不动**（清理另批），但新记录一律落在这里。
 *
 * <p>★★ <b>跨表同键不变式</b>（照 {@code ActorData}/{@code GovState} 的"键必须等于值内的身份"）：{@code combats}
 * 的每个键必须等于其 {@link CombatRecord#id()}。否则同一场交战就有两处可能不一致的 id，而读侧拿到哪一份全看遍历路径——这条守卫在构造期当场抛。
 *
 * <p>★ <b>缺键 = 空</b>（旧档/旧夹具兼容，fail-closed）：{@code combats} 为 {@code null}（Jackson 对缺失键的 record 缺参）⇒
 * 收成空表，<b>此处不抛</b>——army 切片是阶段 D1 新引入的，这一条让"还没落过 army 的世界"仍能读回。方向是 fail-closed：没提该切片 = 没有任何交战记录。
 *
 * <p>★ <b>保序不可变</b>：{@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结，<b>绝不用
 * {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立。
 */
public record ArmyData(Map<CombatRecordId, CombatRecord> combats) {

  public ArmyData {
    // ★ 缺键（null）⇒ 空表，见类注释（旧档/旧夹具兼容，fail-closed 方向）。
    if (combats == null) {
      combats = Map.of();
    }
    Map<CombatRecordId, CombatRecord> copy = new LinkedHashMap<>();
    for (Map.Entry<CombatRecordId, CombatRecord> entry : combats.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        throw new IllegalArgumentException("combats 的键与值都不得为 null: " + entry.getKey());
      }
      if (!entry.getKey().equals(entry.getValue().id())) {
        throw new IllegalArgumentException(
            "combats 的键必须与 CombatRecord.id 一致：键="
                + entry.getKey()
                + "，值内 id="
                + entry.getValue().id());
      }
      copy.put(entry.getKey(), entry.getValue());
    }
    combats = Collections.unmodifiableMap(copy); // ★ 冻在赋值处
  }

  /** 往返用例的起点：空表（= 本世界还没有任何交战记录）。 */
  public static ArmyData empty() {
    return new ArmyData(Map.of());
  }

  /** 一个组件一个 with（照 {@code ActorData} / {@code GovState} 的形制）。 */
  public ArmyData withCombats(Map<CombatRecordId, CombatRecord> value) {
    return new ArmyData(value);
  }

  /**
   * ★ <b>单条记录的写入口</b>：<b>键从值派生</b>（{@code combat.id()} 就是键）。
   *
   * <p>★ 为什么必须有它：{@link io.mosire.simos.util.state.FieldDelta} 的 key 由 {@code toString()} 产出、重建时用
   * {@code parse} 还原，而"键与值内 id 一致"由构造器判。若只把表暴露成 {@code Map}，调用方就得自己拼键——同一个聚合键就有了第二个拼写点（照 {@code
   * ActorData.withActor} 的同一条理由）。
   *
   * <p>★ 同一个键写两次 = 后写覆盖前写（{@code LinkedHashMap} 的 {@code put} 保留首次插入位置、只换值）；但**命令层对本阶段的新记录
   * 选择"已存在即拒"**（不可变历史），故这条覆盖语义只对"手工拼装修复"开放。
   */
  public ArmyData withCombat(CombatRecord combat) {
    Objects.requireNonNull(combat, "combat");
    Map<CombatRecordId, CombatRecord> next = new LinkedHashMap<>(combats);
    next.put(combat.id(), combat);
    return new ArmyData(next);
  }
}
