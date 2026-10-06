package io.mosire.simos.social.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.social.SocialData;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.change.SocialChangeSet;
import io.mosire.simos.social.household.HouseholdBook;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTarget;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code social.TransferHouseholdMembers} 命令的处理器（S3a，2026-10-09 / 架构 §4.1）：把一个成员批次在家户之间转移。
 *
 * <pre>{@code
 * {"from":"hh-1","to":"hh-2","lotId":"hh-1-m","count":12,"reason":"归并"}
 * }</pre>
 *
 * <p>★ <b>语义</b>：只调 {@link HouseholdBook#transferMembers}——整批移动保持 id 不变；拆分批次的迁出部分落派生 id（同一目标重复拆分
 * 自动合并）；两条腿的事件原子写入。源/目标相同、批次不属于源、扣减超量都由域层具名拒。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：2026-10-20 起走跨命名空间 {@link #targetResources}——**from + to
 * 两条都判** （任一越界 ⇒ 整条命令拒；这是用户裁定的双边规则："调人"不能只判一侧）。两侧各自按家户 SocialData 现值解析为 {@code social:<q>_<r>} 或
 * {@code unit:<unitId>}。旧 {@link #targetPaths} 保留为空列表（它看不到 state， 升级前逐字一致）。
 */
public final class TransferHouseholdMembersHandler implements CommandHandler, CommandTargets {

  /** 命令类型（唯一拼写点：Shell 注册、组合工具与 catalog 都从这里取/对齐）。 */
  public static final String TYPE = "social.TransferHouseholdMembers";

  /** from + to 两条目标（保序）；任一侧家户查无 ⇒ 具名 {@link IllegalArgumentException}。 */
  @Override
  public List<CommandTarget> targetResources(
      String commandType, SimulationState state, String mapId, String payloadJson) {
    JsonNode payload = SocialPayloads.parse(payloadJson);
    HouseholdId from = SocialPayloads.requireHouseholdId(payload, "from");
    HouseholdId to = SocialPayloads.requireHouseholdId(payload, "to");
    return HouseholdCommandTargets.bothCurrent(state, from, to);
  }

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    SocialPayloads.parse(payloadJson); // 坏载荷照样在判目标时抛（fail-closed），只是没有目标可给
    return List.of();
  }

  @Override
  public String type() {
    return TYPE;
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    SocialData base = SocialSnapshots.of(state).data(); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = SocialPayloads.parse(payloadJson);
      HouseholdId from = SocialPayloads.requireHouseholdId(payload, "from");
      HouseholdId to = SocialPayloads.requireHouseholdId(payload, "to");
      PeopleLotId lotId = PeopleLotId.parse(SocialPayloads.requireText(payload, "lotId"));
      long count = SocialPayloads.requireLong(payload, "count");
      String reason = SocialPayloads.requireReason(payload);
      SocialData next = HouseholdBook.transferMembers(base, from, to, lotId, count, reason);
      return new HandlerOutcome.Applied(SocialChangeSet.between(base, next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
