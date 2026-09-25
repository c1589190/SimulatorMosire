package io.mosire.simos.social.time;

import io.mosire.simos.economy.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.SocialSnapshot;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.util.address.Address;
import io.mosire.simos.util.address.Entity;
import io.mosire.simos.util.address.Namespace;
import io.mosire.simos.util.spi.TimeParticipant;
import io.mosire.simos.util.spi.TimeProposal;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.time.TimeRange;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ **social 侧的时间推进参与者**（R1 的 T6）：让 social **出现在推进链里**，并给后续轮次留下落点。
 *
 * <p>★★ **R1 里它一条字段都不改，这不是"空转"，而是契约要求的形态**：{@link io.mosire.simos.util.spi.WorldTimeProposal}
 * 的原文是"**无事的参与者应交不变变更集**，不是交空"——交空提案等于让"这一参与者是否真的参与了本次推进"从事件里彻底消失。 故本参与者每次推进都交一份 {@link
 * SocialChangeSet#between}(base, base)（三个组件全 {@code Unchanged}）。
 *
 * <p>★★ **"变老"为什么不需要改状态**：年龄是 {@link
 * io.mosire.simos.social.population.PopulationGroup#ageDaysAt(long)} 的**现算值**（锚点年龄 +
 * 时间差），不是每天写一次的字段（设计稿 §三）。于是"人口在推进中变老"这件事在 R1 里**已经成立**， 代价是零 —— 这正是"年龄用逐日精度 + 锚点"而不是"每天改字段"换来的。
 *
 * <p>★ **实现的是单切片形态**（{@link #simulate}）：本参与者只改 {@code social} 一个模块。按 {@link TimeParticipant}
 * 的契约，**单切片与多切片二选一**：{@code simulateWorld} 的默认实现会把本方法的结果包成单模块提案，故这里**不得**再实现 {@code
 * simulateWorld}（同时实现两者会让"到底走哪条路"变成未定义）。
 *
 * <p>★ **两个边界**（照 {@code EconomyTimeParticipant} 与 {@code UnitTimeParticipant} 的先例）：
 *
 * <ul>
 *   <li>{@code range.to} 缺省（无上界推进）⇒ 交不变提案、不抛（该推进随后必被 Core 的 Validate 拒绝）；
 *   <li>状态里没有 social 切片 ⇒ **装配故障当场炸**（静默兜底会把装配错误伪装成"这一天无事"）。
 * </ul>
 *
 * <p>★ **读写集是**整个切片的足迹（canonical 地址，{@link Address} AST 构造）：R1 里一个地址都不会真的改，但读写集声明的是
 * **能力边界**、不是"今天改了什么"的日志 —— 现在就把 {code group} 的地址写进来，R2/R4 真的开始改批次时，冲突检测与 留痕不必再"追认"。
 */
public final class SocialTimeParticipant implements TimeParticipant {

  private static final String NAMESPACE = "social";

  private final String mapId;

  public SocialTimeParticipant(String mapId) {
    this.mapId = Objects.requireNonNull(mapId, "mapId");
  }

  @Override
  public String namespace() {
    return NAMESPACE;
  }

  @Override
  public TimeProposal simulate(SimulationState state, TimeRange range) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(range, "range");
    SocialData base = dataOf(state);

    Set<String> addresses = new LinkedHashSet<>();
    addresses.add(rootAddress());
    for (HexCoord hex : base.populations().keySet()) {
      addresses.add(hexAddress(hex));
    }
    for (CityId city : base.cities().keySet()) {
      addresses.add(cityAddress(city));
    }
    for (PeopleLotId lot : base.groups().keySet()) {
      addresses.add(groupAddress(lot));
    }

    // ★ 不变变更集：组件全 Unchanged（不是空提案 —— 见类注的契约原文）。
    return new TimeProposal(NAMESPACE, SocialChangeSet.between(base, base), addresses, addresses);
  }

  /** 切片只能从 social 模块拿（铁律 3/4）：缺席或类型不对都是装配故障，当场炸。 */
  private static SocialData dataOf(SimulationState state) {
    Snapshot snapshot =
        state
            .module(NAMESPACE)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "state 里没有 social 切片（装配故障：日推进要求切片在场，静默兜底会把装配错误伪装成无事）"));
    if (!(snapshot instanceof SocialSnapshot socialSnapshot)) {
      throw new IllegalStateException(
          "state 的 social 切片不是 SocialSnapshot: " + snapshot.getClass().getName());
    }
    return socialSnapshot.data();
  }

  // ── canonical 地址（一律由 Address AST 构造后调 canonical()：加引规则不在这里重实现）──────────

  private String rootAddress() {
    return new Address(List.of(new Namespace(NAMESPACE), Entity.of(mapId))).canonical();
  }

  private String hexAddress(HexCoord hex) {
    return entityAddress("hex", hex.toString());
  }

  private String cityAddress(CityId city) {
    return entityAddress("city", city.value());
  }

  /** ★ 批次地址的 kind 用 {@code group}（与 {@code social.SeedGroups} 命令同名同指：一批人 = 一个 group）。 */
  private String groupAddress(PeopleLotId lot) {
    return entityAddress("group", lot.value());
  }

  private String entityAddress(String kind, String localId) {
    return new Address(
            List.of(new Namespace(NAMESPACE), Entity.of(mapId), Entity.of(kind, localId)))
        .canonical();
  }
}
