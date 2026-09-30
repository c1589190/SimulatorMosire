package io.mosire.simos.economy.spi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.mosire.simos.economy.EconomyData;
import io.mosire.simos.economy.api.cohort.CohortKey;
import io.mosire.simos.economy.api.id.HouseholdId;
import io.mosire.simos.economy.change.EconomyChangeSet;
import io.mosire.simos.economy.model.ClassRow;
import io.mosire.simos.map.MapSnapshot;
import io.mosire.simos.map.hex.HexCoord;
import io.mosire.simos.util.json.SimosObjectMapper;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import io.mosire.simos.util.state.Snapshot;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>S3.3 {@code economy.MigrateHousehold} 的最小合法入口</b>（计划 §S3.5 命令面）：
 * 把一条家户行从原格搬到目标格，<b>身份不变</b>（{@link HouseholdId} 仍是同一把键）、 <b>人口不变</b>（只搬 {@code
 * ClassRow.view}，不新增/删除任何人）、 <b>成员份额不变</b>（{@code Membership} 只挂 household + lot，不含格，故原样带过）、
 * <b>资产份额不变</b>（{@code AssetShare.industry/owner/operator} 不含居住格；人迁走而份额留在原产业是合法形态， 如不在村地主）。
 *
 * <pre>{@code
 * {"household":"hh-0_0-rural-poor_peasant","toHex":"1_0"}
 * }</pre>
 *
 * <p>★★ <b>账本 location 不随视图搬</b>（S1.3 的既有口径）：actor 账按 {@code (actor, location)} 键存放， 迁移命令只改 economy
 * 侧的视图；app 的 {@code OwnershipBooks.loadAccountSession} 以 actor 身份回找唯一一本账 （见那里的 S3 注释）。⇒ 本命令不需要
 * actor 命名空间的第二条命令，也不会把同一笔粮变成两本账。
 *
 * <p>★ <b>fail-closed</b>：家户不存在 / 目标格不在图上 / 目标格就是原格 ⇒ {@code Rejected}（不产生半截 revision）。
 *
 * <p>★★ <b>class-first 世界拒绝</b>：{@link EconomyData#classFirst()} 非空时本命令由 {@link
 * ClassFirstCommandGuard} 在读取 base 后立即具名拒绝 —— class-first 结算<b>不读</b> {@code classes.view}
 * （它只是投影，class-first 家户无格维权威；真值在世界级 {@code classFirst.classPools} / {@code
 * classFirst.householdAccounts} 池与账户投影）；迁移只改投影映射、本版不开放，对应工具未接（后续阶段）。 {@code classFirst}
 * 为空（旧档/未播种）时本命令行为逐字不变。
 */
public final class EconomyMigrateHouseholdHandler implements CommandHandler {

  private static final String COMMAND = "economy.MigrateHousehold";

  /** class-first 拒绝的理由主体（不读什么 + 真值在哪 + 指路）。 */
  private static final String CLASS_FIRST_GUIDANCE =
      "class-first 结算不读 classes.view（它只是投影，class-first 家户无格维权威；"
          + "真值在世界级 classFirst.classPools / classFirst.householdAccounts 池与账户投影）；"
          + "迁移只改投影映射、本版不开放，对应工具未接（后续阶段）";

  private static final ObjectMapper MAPPER = SimosObjectMapper.create();

  @Override
  public String type() {
    return COMMAND;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    EconomyData base = EconomySnapshots.of(state).data();
    Optional<HandlerOutcome> classFirstRejection =
        ClassFirstCommandGuard.rejectIfClassFirst(COMMAND, base, CLASS_FIRST_GUIDANCE);
    if (classFirstRejection.isPresent()) {
      return classFirstRejection.get();
    }
    try {
      JsonNode payload = MAPPER.readTree(payloadJson);
      HouseholdId household = HouseholdId.parse(requireText(payload, "household"));
      HexCoord to = HexCoord.parse(requireText(payload, "toHex"));
      ClassRow row = base.classes().get(household);
      if (row == null) {
        return new HandlerOutcome.Rejected("家户不存在: " + household.value());
      }
      if (row.view().hex().equals(to)) {
        return new HandlerOutcome.Rejected("目标格与原格相同，迁移无事可做: " + to);
      }
      Snapshot mapModule =
          state
              .module("map")
              .orElseThrow(() -> new IllegalStateException("state 里没有 map 切片（装配故障）"));
      if (!(mapModule instanceof MapSnapshot mapSnapshot)) {
        throw new IllegalStateException(
            "state 的 map 切片不是 MapSnapshot: " + mapModule.getClass().getName());
      }
      if (!mapSnapshot.map().terrainIndex().containsKey(to)) {
        return new HandlerOutcome.Rejected("目标格不在图上: " + to);
      }
      ClassRow moved =
          new ClassRow(
              row.id(),
              // ★ S3 审计：迁移只换格，**阶层原样保留当前 view**（可能是 landless_laborer/artisan/official）；
              //   不从旧四档反推，也不改 participationPerMille（EconomyData 守卫对派生阶层不施加创世槽位上限）。
              new CohortKey(to, row.view().residence(), row.view().stratum()),
              row.population(),
              row.laborMilli(),
              row.participationPerMille(),
              row.money(),
              row.debts(),
              row.naturalNeeds(),
              row.effectiveDemand(),
              row.cycleNaturalNeedMilli());
      Map<HouseholdId, ClassRow> classes = new LinkedHashMap<>(base.classes());
      classes.put(household, moved);
      return new HandlerOutcome.Applied(EconomyChangeSet.between(base, base.withClasses(classes)));
    } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }

  /** 载荷里的必填文本（缺/空白/类型不对 ⇒ 抛，由 handle 折成 Rejected）。 */
  private static String requireText(JsonNode payload, String field) {
    JsonNode node = payload.get(field);
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new IllegalArgumentException(COMMAND + " 缺少非空文本字段: " + field);
    }
    return node.asText();
  }
}
