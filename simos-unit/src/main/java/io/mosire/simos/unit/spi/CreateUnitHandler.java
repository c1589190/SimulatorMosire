package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.unit.CompositionEntry;
import io.mosire.simos.unit.RelativeOffset;
import io.mosire.simos.unit.Unit;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.UnitStatus;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.time.Segment;
import io.mosire.simos.util.time.SegmentedSeries;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code unit.CreateUnit} 命令的处理器（spec §四）。自反序列化 payload、构造 {@link Unit}、调 {@link
 * UnitOperations#create}、返回 {@link HandlerOutcome.Applied}。
 *
 * <p>★ **初始段时刻 = base 状态时间戳**（{@code state.meta().timestamp()}）：信封不带时刻（C22/裁定 35）， {@code parent} 与
 * {@code position} 两条时态序列的 anchor 段都落在这一刻——与其余时间命令同一口径。
 *
 * <p>★★ **{@code position} 与 {@code parent} 的关系（编制 v2，2026-09-24）**：跟随取消后，{@code position} 不再是"可省略
 * ⇒ 向父取"，而是**每个单位自己的位置**。两条规则：
 *
 * <ol>
 *   <li><b>无 {@code parent} ⇒ 它是顶层</b>：{@code attached=false}，{@code position} **必填**（无父又无位置 =
 *       不在图上，坏输入）。
 *   <li><b>有 {@code parent} ⇒ 它编入那一支</b>：{@code attached=true}；若同时给了 {@code position}，**必须与父同格**
 *       （裁定：「有且只有单位在同一格子时生效」）——不同格 ⇒ 拒。
 * </ol>
 *
 * <p>★ 因此"同一支编制里的单位都同格"是**创建期就成立**的不变量（`attach`/`merge` 两条路也各自判同格）。
 */
public final class CreateUnitHandler implements CommandHandler, CommandTargets {

  /** 命令类型（信封上的 {@code type}，也是 catalog / 窄工具引用的唯一拼写点）。 */
  public static final String TYPE = "unit.CreateUnit";

  /** ★ 目标资源（第 3 波第 2 步，{@link CommandTargets}）：按载荷里点名的**新**单位 id 判（建之后它才存在，见契约的创建型口径）。 */
  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    var payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "id"));
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "id"));
      String name = UnitPayloads.requireText(payload, "name");
      // ★ position 可选：省略/null ⇒ 无自身位置（跟随父）；但那时必须给 parent（否则单位不在图上，是坏输入）。
      Optional<HexCoord> position = UnitPayloads.optionalHex(payload, "position");
      UnitPayloads.rejectRetiredManpower(payload);
      List<CompositionEntry> equipment = UnitPayloads.requireComposition(payload, "equipment");
      // ★ S3b：创建期即可给家户（生产路径 RaiseUnit 用）；缺席 ⇒ 空表（旧调用点）。
      List<HouseholdId> households =
          UnitPayloads.optionalHouseholdIds(payload, "households").orElse(List.of());
      int speed = UnitPayloads.requireInt(payload, "speed");
      int mobilityPerMille = UnitPayloads.requireInt(payload, "mobilityPerMille");
      Optional<UnitId> parent = UnitPayloads.optionalId(payload, "parent");
      SimosTimestamp at = state.meta().timestamp();
      if (position.isEmpty() && parent.isEmpty()) {
        throw new IllegalArgumentException(
            "字段 position 缺失且未给 parent：无父又无自身位置的单位不在图上（要么给 position，要么给 parent）");
      }
      // ★ 编制 v2：有父 ⇒ 编入那一支（attached=true），且给位置时必须与父同格（"只有同格才能一同移动"）。
      boolean attached = parent.isPresent();
      if (parent.isPresent() && position.isPresent()) {
        Optional<HexCoord> parentHex = snapshot.state().effectivePosition(parent.get(), at);
        if (parentHex.isEmpty() || !parentHex.get().equals(position.get())) {
          throw new IllegalArgumentException(
              "单位 "
                  + id
                  + " 的位置 "
                  + position.get()
                  + " 与父 "
                  + parent.get()
                  + " 的 "
                  + parentHex.map(HexCoord::toString).orElse("(不可确定)")
                  + " 不同格：只有同格的单位才能编入同一支编制");
        }
      }
      UnitStatus status = UnitPayloads.optionalStatus(payload, "status").orElse(UnitStatus.MOVING);
      Unit unit =
          new Unit(
              id,
              name,
              new SegmentedSeries<>(List.of(new Segment<>(at, parent)), List.of(), null),
              new SegmentedSeries<>(List.of(new Segment<>(at, position)), List.of(), null),
              equipment,
              speed,
              mobilityPerMille,
              Optional.empty(),
              status,
              new SegmentedSeries<>(List.of(new Segment<>(at, attached)), List.of(), null),
              new SegmentedSeries<>(
                  List.of(new Segment<>(at, Optional.<RelativeOffset>empty())), List.of(), null),
              Optional.empty(),
              // ★ 创建（不是拷贝）：视野半径取缺省 1 圈——载荷里没有该字段，不凭空发明输入（spec §4.1 只要求"缺省 1"）。
              Unit.DEFAULT_VISION_RADIUS,
              // ★ 创建（不是拷贝）：新单位尚无管辖 ⇒ Optional.empty()（辖区阶段 5）。
              Optional.empty(),
              // ★ 创建（不是拷贝）：新单位尚无编制模块 ⇒ Optional.empty()（阶段 9；编制是后续命令/创建批的输入，
              //   不凭空发明）。
              Optional.empty(),
              // ★ 创建（不是拷贝）：新单位尚无"状态 ↔ 状态描述地址"链接 ⇒ 空表（阶段 D1 / D-012）。
              Map.<String, String>of(),
              // ★ S3b：创建期给的家户（缺席 ⇒ 空表）；家户在 Social 侧的位置一致性由组合工具/生产路径保证。
              households);
      UnitState next = UnitOperations.create(snapshot.state(), unit);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
