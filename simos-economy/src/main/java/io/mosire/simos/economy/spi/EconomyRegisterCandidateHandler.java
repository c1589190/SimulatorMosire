package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.actor.api.asset.AssetKind;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.id.CandidateId;
import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.RegimeId;
import io.mosire.simos.economy.api.relation.LaborSource;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.AssetShare;
import io.mosire.simos.economy.model.ProductionCandidate;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * ★★ {@code economy.RegisterCandidate}（R4-E2）：GM 登记/修订一条候选生产方式（预设）。
 *
 * <pre>{@code
 * {"id":"cand-wool","version":1,"name":"牧羊","output":"wool",
 *  "outputPerUnit":{"wool":1000},"inputPerUnit":{},"requiredAssets":{"LAND":100},
 *  "laborPerUnit":500,"buildDays":30,"cycleDays":120,"regime":"tenant",
 *  "laborSource":"TENANT","acceptedRightKinds":["OWNED","TENANCY"],
 *  "assetSource":{"kind":"HOUSEHOLD","id":"hh-…"}?}
 * }</pre>
 *
 * <p>★★ <b>版本规则</b>：{@code (id,version)} 已存在 ⇒ 拒；同一 id 已有旧版本 ⇒ 新 version 必须**严格大于**当前版本
 * （修订必须往前走，不回滚）；表里每个 id 只保留**当前版本**，但 {@code modeKey = id@version} 由 {@link
 * ProductionCandidate#modeKeyOf} 唯一拼写，既有 unit 的 modeKey 一字不动（旧 unit 不会悄悄跟随新版本）。
 *
 * <p>★ <b>本命令只登记、不采用</b>：不建 unit、不改关系/资产/劳动/市场；进入算法留 E2b。不实现 {@code CommandTargets} （同 {@code
 * economy.MigrateHousehold}：GM {@code simos.command.submit} 可用，directive 内会被 fail-closed 拒）。
 */
public final class EconomyRegisterCandidateHandler implements CommandHandler {

  private static final String COMMAND = "economy.RegisterCandidate";

  @Override
  public String type() {
    return COMMAND;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    try {
      JsonNode payload = EconomyCommandPayloads.parseObject(COMMAND, payloadJson);
      CandidateId candidateId =
          CandidateId.parse(EconomyCommandPayloads.requireText(COMMAND, payload, "id"));
      int version = EconomyCommandPayloads.optionalInt(COMMAND, payload, "version", 1);
      ProductionCandidate existing = base.candidates().get(candidateId);
      if (existing != null) {
        if (version == existing.version()) {
          return new HandlerOutcome.Rejected(
              "候选 (id,version) 已存在，拒绝覆盖: "
                  + candidateId.value()
                  + "@"
                  + version
                  + "（修订必须用新 version）");
        }
        if (version < existing.version()) {
          return new HandlerOutcome.Rejected(
              "候选修订必须使用严格更大的 version（当前 "
                  + existing.version()
                  + "，收到 "
                  + version
                  + "）: "
                  + candidateId.value());
        }
      }
      CommodityId output =
          CommodityId.parse(EconomyCommandPayloads.requireText(COMMAND, payload, "output"));
      Map<CommodityId, Long> outputPerUnit =
          EconomyCommandPayloads.optionalCommodityMap(COMMAND, payload, "outputPerUnit", true);
      Map<CommodityId, Long> inputPerUnit =
          EconomyCommandPayloads.optionalCommodityMap(COMMAND, payload, "inputPerUnit", false);
      Map<AssetKind, Long> requiredAssets =
          EconomyCommandPayloads.optionalAssetMap(COMMAND, payload, "requiredAssets");
      long laborPerUnit = EconomyCommandPayloads.optionalLong(COMMAND, payload, "laborPerUnit", 0L);
      long buildDays = EconomyCommandPayloads.optionalLong(COMMAND, payload, "buildDays", 0L);
      long cycleDays = EconomyCommandPayloads.requireLong(COMMAND, payload, "cycleDays");
      RegimeId regime =
          RegimeId.parse(EconomyCommandPayloads.requireText(COMMAND, payload, "regime"));
      LaborSource laborSource =
          EconomyCommandPayloads.optionalLaborSource(
              COMMAND, payload, "laborSource", LaborSource.SELF);
      Set<AssetShare.RightKind> acceptedRightKinds =
          EconomyCommandPayloads.optionalRightKinds(COMMAND, payload, "acceptedRightKinds");
      String name =
          EconomyCommandPayloads.optionalText(COMMAND, payload, "name", candidateId.value());
      ProductionCandidate candidate =
          new ProductionCandidate(
              candidateId,
              version,
              output,
              outputPerUnit,
              inputPerUnit,
              requiredAssets,
              laborPerUnit,
              buildDays,
              cycleDays,
              regime,
              laborSource,
              acceptedRightKinds,
              EconomyCommandPayloads.optionalActor(COMMAND, payload, "assetSource"),
              name);
      Map<CandidateId, ProductionCandidate> candidates = new LinkedHashMap<>(base.candidates());
      candidates.put(candidateId, candidate);
      return new HandlerOutcome.Applied(
          EconomyChangeSet.between(base, base.withCandidates(candidates)));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
