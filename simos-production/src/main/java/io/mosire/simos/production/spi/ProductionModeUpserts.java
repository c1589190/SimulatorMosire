package io.mosire.simos.production.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>{@code production.UpsertProductionMode} / {@code production.DeactivateProductionMode}
 * 的唯一语义落点</b> （D-031 P1；原 {@code economy.GmAdjust.upsertProductionMode/deactivateProductionMode}
 * 迁入）。
 *
 * <p>★★ <b>版本语义冻结</b>（控制方 §9；原 {@code EconomyGmAdjustments:322}）：新 id 缺省 = 1；既有 id 缺省 = 沿用现有
 * version（仅当其余字段逐值相同 ⇒ 幂等 no-op，否则要求显式推进）；显式给时必须 ≥ 现有 version+1（同 version 的逐值重放仍幂等 no-op，同 version
 * 的改值 ⇒ 具名拒绝）。<b>不</b>自行禁止被引用 mode 升级。
 *
 * <p>★★ <b>停用守卫冻结</b>（原 {@code :396} 的六类真实引用）：classStructures / classPositions /
 * productionOrganizations / assetRules / modeTransitions / pledges；由 economy 端口报告。
 */
public final class ProductionModeUpserts {

  /** 命令类型（与 {@link UpsertProductionModeHandler#TYPE} 同一拼写点）。 */
  public static final String UPSERT_COMMAND = UpsertProductionModeHandler.TYPE;

  /** 命令类型（与 {@link DeactivateProductionModeHandler#TYPE} 同一拼写点）。 */
  public static final String DEACTIVATE_COMMAND = DeactivateProductionModeHandler.TYPE;

  private ProductionModeUpserts() {}

  /** upsert 投影。 */
  public record UpsertProjection(
      ProductionModeDefinitionView mode,
      ProductionModeDefinitionView previous,
      boolean created,
      ProductionData projected,
      ProductionChangeSet changeSet) {}

  /** deactivate 投影。 */
  public record DeactivateProjection(
      ProductionModeDefinitionView removed,
      ProductionData projected,
      ProductionChangeSet changeSet) {}

  /** 业务拒绝（版本倒退 / 未显式推进 / 被引用等）。 */
  public static final class Rejection extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    public Rejection(String message) {
      super(Objects.requireNonNull(message, "message"));
    }
  }

  /** upsert 纯函数。 */
  public static UpsertProjection upsert(
      ProductionData base, ProductionConsumerPort port, JsonNode parameters) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(port, "port");
    ProductionDefinitionPayloads.ModePayload payload =
        ProductionDefinitionPayloads.mode(parameters);
    ProductionModeId id = payload.id();
    String label = UPSERT_COMMAND;
    // ★★ D-031 P1 §9.3：classStructureId 的存在性**不在这里前置** —— 两边同时全新时，用两条普通 upsert 无论何顺序都会
    //   互相卡住。批内中间态允许前向引用，落库前的候选态 validator（economy 领域校验）做最终闭环；单条缺结构的 mode
    //   仍由最终 validator 具名拒绝。不允许用户改走 Seed，也不放宽最终引用完整性。
    ProductionModeDefinitionView before = base.modes().get(id);
    int version =
        payload.version() != null ? payload.version() : (before == null ? 1 : before.version());
    if (version < 1) {
      throw new IllegalArgumentException(label + " 的 version 必须 ≥ 1: " + version);
    }
    ProductionModeDefinitionView after =
        new ProductionModeDefinitionView(id, payload.name(), version, payload.classStructureId());
    if (after.equals(before)) {
      return new UpsertProjection(
          after, before, false, base, ProductionChangeSet.between(base, base));
    }
    if (before != null) {
      if (version < before.version()) {
        throw new Rejection(label + " 的 version 不得倒退：当前 " + before.version() + "，收到 " + version);
      }
      if (payload.version() == null) {
        throw new Rejection(
            label
                + " 修改既有 mode 必须显式给 version ≥ 现有 version+1（当前 "
                + before.version()
                + "，未给 version）；逐值相同的重放可省略 version");
      }
      if (version == before.version()) {
        throw new Rejection(
            label
                + " 修订必须推进 version：当前 "
                + before.version()
                + "，收到同值 "
                + version
                + "（同 version 只允许逐值相同的幂等重放）");
      }
    }
    Map<ProductionModeId, ProductionModeDefinitionView> modes = new LinkedHashMap<>(base.modes());
    modes.put(id, after);
    ProductionData projected = base.withModes(modes);
    return new UpsertProjection(
        after, before, before == null, projected, ProductionChangeSet.between(base, projected));
  }

  /** deactivate 纯函数（六类引用者经 {@code port} 读当刻 {@code state}）。 */
  public static DeactivateProjection deactivate(
      ProductionData base,
      SimulationState state,
      ProductionConsumerPort port,
      JsonNode parameters) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(port, "port");
    ProductionModeId id = ProductionModeId.parse(ProductionPayloads.requireText(parameters, "id"));
    String label = DEACTIVATE_COMMAND;
    ProductionModeDefinitionView before = base.modes().get(id);
    if (before == null) {
      throw new Rejection(label + " 的生产方式不存在: " + id.value());
    }
    ProductionConsumerPort.ModeReferenceReport references = port.modeReferences(state, id);
    if (!references.isEmpty()) {
      throw new Rejection(label + " 的生产方式仍被引用，不能停用: " + id.value() + "；" + references.describe());
    }
    Map<ProductionModeId, ProductionModeDefinitionView> modes = new LinkedHashMap<>(base.modes());
    modes.remove(id);
    ProductionData projected = base.withModes(modes);
    return new DeactivateProjection(
        before, projected, ProductionChangeSet.between(base, projected));
  }
}
