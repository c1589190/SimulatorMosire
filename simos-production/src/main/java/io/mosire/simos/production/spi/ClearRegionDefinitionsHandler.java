package io.mosire.simos.production.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.economy.api.id.IndustryHexKeys;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.ProductionLog;
import io.mosire.simos.production.ProductionLogSource;
import io.mosire.simos.production.ProductionSnapshot;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionConsumerPort;
import io.mosire.simos.production.contract.ProductionLocation;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.util.log.EventLog;
import io.mosire.simos.util.log.LogEvent;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;

/**
 * ★★ <b>{@code production.ClearRegionDefinitions}：退役本区域的生产实例，并清除本区域无人引用的产业定义</b> （D-031 P1 spec
 * §3.3；★★ B2c §13.12.3 裁定九加宽）。
 *
 * <pre>{@code {"hexes":["0_0","1_0"]}}</pre>
 *
 * <p>★★ <b>B2c（裁定九）：本命令在同一个命令内、按固定次序做两件事</b>：
 *
 * <ol>
 *   <li><b>先退役本区域的生产实例</b>（{@link ProductionData#instances()}，判据 = {@link
 *       ProductionProcess#hexKey()}：先认实例的显式地点 {@code ProductionProcess.location()}，地点是 {@code
 *       UNSPECIFIED} 占位时按 {@link ProductionLocation#ofPlan} 把方案 id 的格段读成地点）；退役数进具名读数 {@code
 *       instancesRemoved}；★ <b>B4（§13.14.4）</b>：地点判不出来的实例<b>不再是"跳过 + 计数"</b>——B3 起四处构造点全部
 *       fail-closed，"地点没落定"的实例不可构造 ⇒ 该形态是<b>契约故障</b>（ERROR 不降级、当场抛），原来的 {@code
 *       instancesSkippedUnknownLocation} 兜底读数已删除（它会把状态自相矛盾静默记成"少删了几个"）；
 *   <li><b>再清该区域无人引用的产业定义</b>（经 economy 端口确认零引用；被引用的保留，绝不删悬空）。 ★ 加宽前只做这一步 ⇒ "该区有实例时定义删不掉"是死路（只有具名
 *       INFO {@code industriesRemoved=0}）； 加宽后实例先退役，引用随之为零，定义才删得掉。
 * </ol>
 *
 * <p>★ <b>清区顺序冻结</b>：economy 侧先摘引用行（{@code economy.ClearRegion}）→ 本命令退役实例 → 删定义 —— 三段在同一条 {@code
 * submitBatch} 里，批序由 {@code RegionClearPlan} 保证。本命令<b>不新开</b>， 也不放宽任何引用守卫（仍被引用的定义照旧保留）。
 *
 * <p>★ 无变动 ⇒ 空 ChangeSet（幂等）；planOnly 由 GM 命令层保证不写。
 */
public final class ClearRegionDefinitionsHandler implements CommandHandler {

  public static final String TYPE = "production.ClearRegionDefinitions";

  private static final Logger LOG = ProductionLog.command();

  /** ★★ B4：跨切片引用计数端口（实现在组合根；构造期 requireNonNull ⇒ 装配错在启动期当场炸）。 */
  private final ProductionConsumerPort consumers;

  public ClearRegionDefinitionsHandler(ProductionConsumerPort consumers) {
    this.consumers =
        Objects.requireNonNull(consumers, "consumers（组合根必须注入 ProductionConsumerPort 实现）");
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    ProductionData base = ProductionSnapshots.of(state);
    try {
      JsonNode payload = ProductionPayloads.parse(payloadJson);
      Set<String> targetHexKeys = new LinkedHashSet<>();
      JsonNode hexes = payload.get("hexes");
      if (hexes == null || !hexes.isArray()) {
        throw new IllegalArgumentException("字段 hexes 必须是数组: " + payload);
      }
      for (JsonNode hex : hexes) {
        if (!hex.isTextual() || hex.asText().isBlank()) {
          throw new IllegalArgumentException("字段 hexes 的元素必须是非空字符串: " + payload);
        }
        targetHexKeys.add(hex.asText());
      }
      // ── ① 先退役本区域的实例（裁定九）──────────────────────────────────────────────────
      Map<ProductionUnitId, ProductionProcess> instances = new LinkedHashMap<>(base.instances());
      List<ProductionUnitId> retired = new ArrayList<>();
      for (Map.Entry<ProductionUnitId, ProductionProcess> entry : base.instances().entrySet()) {
        // ★ B4（§13.14.4）：地点判不出来 = 状态自相矛盾（B3 起不可构造）⇒ 契约故障 ERROR 不降级，不静默跳过。
        String hexKey = requireRegionHexKey(entry.getValue());
        if (targetHexKeys.contains(hexKey)) {
          retired.add(entry.getKey());
        }
      }
      for (ProductionUnitId unit : retired) {
        instances.remove(unit);
      }
      ProductionData staged = base.withInstances(instances);
      // ── ② 再清该区域无人引用的产业定义（原语义不变）──────────────────────────────────────
      //   ★★ B4（§13.14.5 第 1 条 / §13.12.3 裁定九）：引用计数必须问**退役之后的候选态**——
      //   端口的 units 维读的是 production 的实例投影；若问退役前的 state，这里的实例仍在，
      //   计数恒 > 0 ⇒ 定义永远删不掉（"该区有实例时定义删不掉"那条死路会原样回来）。
      //   ⇒ 造一份只读候选态：同一 meta/info/切片，只把 production 切片换成本命令的 staged 结果。
      //   ★ 它只服务本次只读查询：既不进 ChangeSet、也不落盘（写面仍是下面那条 ProductionChangeSet）。
      SimulationState stagedState = stagedState(state, staged);
      Map<IndustryId, IndustryDefinitionView> industries = new LinkedHashMap<>(staged.industries());
      int removed = 0;
      for (IndustryId id : staged.industries().keySet()) {
        boolean inRegion = IndustryHexKeys.hexKeyOf(id).filter(targetHexKeys::contains).isPresent();
        if (!inRegion) {
          continue;
        }
        if (!consumers.industryReferences(stagedState, id).isEmpty()) {
          continue; // 仍被引用 ⇒ 不删悬空（实例已由上面的退役段清掉；其余引用者留给它们自己的域）
        }
        industries.remove(id);
        removed++;
      }
      ProductionData target = staged.withIndustries(industries);
      ProductionChangeSet changeSet = ProductionChangeSet.between(base, target);
      EventLog.channel(LOG)
          .info(
              LogEvent.of(
                  "PRODUCTION_REGION_DEFINITIONS_CLEARED",
                  ProductionLogSource.PRODUCTION_COMMAND,
                  "hexes",
                  targetHexKeys.size(),
                  "instancesRemoved",
                  retired.size(),
                  "industriesRemoved",
                  removed,
                  "changed",
                  !changeSet.isEmpty()));
      return new HandlerOutcome.Applied(changeSet);
    } catch (IllegalArgumentException e) {
      ProductionPayloads.logRejected(
          LOG,
          ProductionLogSource.PRODUCTION_COMMAND,
          "PRODUCTION_CLEAR_REGION_REJECTED",
          e.getMessage());
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /**
   * ★★ <b>本命令的"退役之后"候选态</b>（只读查询用；见 ② 的说明）。
   *
   * <p>★ 同一个 {@code meta} / {@code info} / 其余切片，只把 production 切片换成 {@code staged} 那份 {@link
   * ProductionData}（ref/timestamp 沿用当刻切片）⇒ 端口读到的 instances 就是"实例已退役"的视图。
   */
  private static SimulationState stagedState(SimulationState state, ProductionData staged) {
    Snapshot slice =
        state
            .module("production")
            .orElseThrow(() -> new IllegalStateException("state 里没有 production 切片（装配故障）"));
    Map<String, Snapshot> modules = new LinkedHashMap<>(state.modules());
    if (!(slice instanceof ProductionSnapshot productionSlice)) {
      throw new IllegalStateException(
          "state 的 production 切片不是 ProductionSnapshot: " + slice.getClass().getName());
    }
    modules.put(
        "production",
        new ProductionSnapshot(productionSlice.ref(), productionSlice.timestamp(), staged));
    return new SimulationState(state.meta(), modules, state.info());
  }

  /**
   * ★★ <b>这个实例属于哪一格</b>：判据本身只有一处拼写点 —— {@link ProductionProcess#hexKey()}（先认实例的显式地点；地点是 {@code
   * UNSPECIFIED} 占位时按方案 id 的格段读）。
   *
   * <p>★★ <b>B4（§13.14.4）收口</b>：判不出来 ⇒ <b>契约故障</b>（ERROR 不降级、当场抛），不再"跳过 + 单独计数"。 理由：B3 已把四处构造点全部
   * fail-closed（播种/载荷 = 具名拒 {@code location-unknown}；迁移建档/企业建档 = 契约故障） ⇒
   * "地点没落定"的实例<b>不可构造</b>；留一条"静默少删几个"的路只会把"状态自相矛盾"记成"清区成功"。
   */
  private static String requireRegionHexKey(ProductionProcess process) {
    Optional<String> hexKey = process.hexKey();
    if (hexKey.isEmpty()) {
      EventLog.channel(LOG)
          .error(
              LogEvent.of(
                  "PRODUCTION_REGION_DEFINITIONS_CONTRACT_FAULT",
                  ProductionLogSource.PRODUCTION_COMMAND,
                  "reason",
                  "instance-location-unknown",
                  "unit",
                  process.id().value(),
                  "plan",
                  process.plan().value()));
      throw new IllegalStateException(
          TYPE
              + " 的实例地点不可判（状态自相矛盾，契约故障）: unit="
              + process.id().value()
              + ", plan="
              + process.plan().value());
    }
    return hexKey.get();
  }
}
