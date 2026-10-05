package io.mosire.simos.unit.spi;

import com.fasterxml.jackson.databind.JsonNode;
import io.mosire.simos.unit.StaffRole;
import io.mosire.simos.unit.UnitId;
import io.mosire.simos.unit.UnitSnapshot;
import io.mosire.simos.unit.UnitState;
import io.mosire.simos.unit.change.UnitChangeSet;
import io.mosire.simos.unit.ops.UnitOperations;
import io.mosire.simos.util.spi.CommandHandler;
import io.mosire.simos.util.spi.CommandTargets;
import io.mosire.simos.util.spi.HandlerOutcome;
import io.mosire.simos.util.state.SimulationState;
import java.util.List;
import java.util.Objects;

/**
 * {@code unit.RecruitStaff} 命令的处理器（阶段 10b-i，2026-10-01）：{@code unitId, role(SCRIBE|YAMEN|POST),
 * count, sources?}。
 *
 * <pre>{@code
 * {"unitId":"gov-province-1","role":"SCRIBE","count":3,
 *  "sources":[{"kind":"social_group","id":"g-1","count":2},{"kind":"unit","id":"u-team","count":1}]}
 * }</pre>
 *
 * <p>★★ <b>这是一条"只入编、不扣人"的裸命令，不是独立可用的招募入口</b>：本 handler 只把 {@code GovernmentFormation.staff[role] +=
 * count}；它不读社会批次、不扣人口、不按 {@code sources} 扣任何来源。 <b>裸提只入编不扣人</b>；受支持的调用面是 10b-ii 的配套工具批（{@code
 * social.SeedGroups} 扣人 + 本命令入编 + {@code sd.PutInfo} 记录，一批一条 revision）或决策令批——人员扣减、来源守恒与审计都在那一层。
 *
 * <p>★ <b>{@code sources} 只校形状、不解析域对象</b>：缺省 / JSON {@code null} 合法；给了必须是 JSON
 * <b>对象数组</b>（本层不读元素里的字段，也不校验来源总数与 {@code count} 的关系）。示例里的 {@code kind}/{@code id} 只是 10b-ii
 * 的约定样例，不是本层契约。
 *
 * <p>★ <b>拒因</b>（边界只折 {@code Rejected}）：单位不存在；单位不是 GOV；{@code role} 未知； {@code count < 1}；{@code
 * staffCap} 含该角色且 {@code 现有 + count > cap}（消息带现有 / 上限 / 请求三个数字， 不截断）；{@code sources} 不是 JSON 对象数组。
 *
 * <p>★ <b>目标声明</b>（{@link CommandTargets}）：只按载荷点名的 unitId 判；本命令只写 unit 命名空间。
 */
public final class RecruitStaffHandler implements CommandHandler, CommandTargets {

  @Override
  public List<String> targetPaths(String mapId, String payloadJson) {
    JsonNode payload = UnitPayloads.parse(payloadJson);
    return List.of(UnitPayloads.requireText(payload, "unitId"));
  }

  @Override
  public String type() {
    return "unit.RecruitStaff";
  }

  @Override
  public HandlerOutcome handle(SimulationState state, String payloadJson) {
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(payloadJson, "payloadJson");
    UnitSnapshot snapshot = UnitSnapshots.of(state); // 装配故障当场炸，不走拒绝路径
    try {
      JsonNode payload = UnitPayloads.parse(payloadJson);
      UnitId id = UnitId.parse(UnitPayloads.requireText(payload, "unitId"));
      StaffRole role = UnitPayloads.requireStaffRole(payload, "role");
      long count = UnitPayloads.requireLong(payload, "count");
      UnitPayloads.validateOptionalSourceArray(payload, "sources"); // ★ 只校形状，不解析来源域对象。
      UnitState next = UnitOperations.recruitStaff(snapshot.state(), id, role, count);
      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));
    } catch (IllegalArgumentException e) {
      return new HandlerOutcome.Rejected(e.getMessage());
    }
  }
}
