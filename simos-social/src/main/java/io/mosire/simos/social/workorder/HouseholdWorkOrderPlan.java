package io.mosire.simos.social.workorder;

import io.mosire.simos.social.api.household.HouseholdLocation;
import io.mosire.simos.social.api.household.HouseholdProfile;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.social.api.population.HouseholdVitalRates;
import io.mosire.simos.social.api.population.Sex;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * {@code social.SubmitHouseholdWorkOrder} 的 <b>计划</b>：一组<b>有序</b>操作，逐条对应 {@code HouseholdBook}
 * 的一个纯函数写口。
 *
 * <p>★ <b>为什么有序</b>：同一批里的后一条操作看到的是前一条已生效的工作副本（例如先 {@code CREATE_HOUSEHOLD} 再 {@code
 * ADD_MEMBERS}），顺序是语义的一部分（与 {@code CommandBus.submitBatch} 同口径）。
 *
 * <p>★ <b>失败语义</b>：整单要么全成、要么整单具名拒——{@link HouseholdWorkOrderBook} 在接收方的私有工作副本上顺序应用， 任一步抛 {@link
 * IllegalArgumentException} 就带着"第几步 + 操作名"整单拒，<b>不部分生效</b>（base 从不被改）。
 *
 * <p>★ <b>本类型不做状态校验</b>：成员保序冻结 + 逐条非 null；人数/份额/存在性等域规则留给 {@code HouseholdBook}
 * （不在两条路上各写一份）。计划本身是契约形状，能被 app/后续调用方直接构造（"单条人口转移"见 {@link #transfer}）；JSON 载荷的解析在 {@code
 * simos-social} 的 spi 层。
 *
 * <p>★ <b>与旧命令的关系</b>：既有 {@code social.CreateHousehold} 等逐操作命令与本工单入口并存；本类型不改变它们的语义。
 */
public record HouseholdWorkOrderPlan(List<Step> steps) {

  public HouseholdWorkOrderPlan {
    Objects.requireNonNull(steps, "steps");
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("工单计划 plan 不得为空（空计划没有可受理的更改）");
    }
    List<Step> copy = new ArrayList<>(steps.size());
    for (Step step : steps) {
      if (step == null) {
        throw new IllegalArgumentException("工单计划 plan 不得含 null 操作");
      }
      copy.add(step);
    }
    steps = List.copyOf(copy);
  }

  /** 便捷构造（保序；含 null 元素/空数组由规范构造器具名拒）。 */
  public static HouseholdWorkOrderPlan of(Step... steps) {
    Objects.requireNonNull(steps, "steps");
    return new HouseholdWorkOrderPlan(Arrays.asList(steps));
  }

  /**
   * ★ <b>单条人口转移的窄封装</b>：只含一条 {@link TransferMembers} 的计划。
   *
   * <p>窄入口的正式调用仍走既有命令 {@code social.TransferHouseholdMembers}；本方法服务"以工单形态封装一条转移"的调用方
   * （单条转移不需要手工拼操作数组，也不可能把顺序写错）。
   */
  public static HouseholdWorkOrderPlan transfer(
      HouseholdId from, HouseholdId to, PeopleLotId lotId, long count) {
    return new HouseholdWorkOrderPlan(List.of(new TransferMembers(from, to, lotId, count)));
  }

  /** 计划是否点名了某个家户（作为 {@code household}/{@code from}/{@code to}）——工单 target 必须被计划引用。 */
  public boolean references(HouseholdId householdId) {
    Objects.requireNonNull(householdId, "householdId");
    for (Step step : steps) {
      if (step.references(householdId)) {
        return true;
      }
    }
    return false;
  }

  /** 一条计划操作：操作名 + "点名了哪些家户"（工单 target 校验用）。 */
  public sealed interface Step
      permits CreateHousehold,
          SetLocation,
          AddMembers,
          RemoveMembers,
          TransferMembers,
          AdjustPopulation,
          SetVitalRates {

    /** 稳定操作名（进载荷/日志/拒绝理由；常量拼写即线格式）。 */
    String op();

    /** 本操作显式点名的家户（创建/改位置/成员操作的 household，转移的 from/to）。 */
    boolean references(HouseholdId householdId);
  }

  /** {@code CREATE_HOUSEHOLD}：对应 {@code HouseholdBook.create}（成员表为空；vitalRates 空表合法）。 */
  public record CreateHousehold(
      HouseholdId householdId,
      HouseholdLocation location,
      HouseholdProfile profile,
      HouseholdVitalRates vitalRates)
      implements Step {

    public CreateHousehold {
      requireHousehold(householdId, "CREATE_HOUSEHOLD");
      Objects.requireNonNull(location, "CREATE_HOUSEHOLD.location");
      Objects.requireNonNull(profile, "CREATE_HOUSEHOLD.profile");
      Objects.requireNonNull(vitalRates, "CREATE_HOUSEHOLD.vitalRates");
    }

    @Override
    public String op() {
      return "CREATE_HOUSEHOLD";
    }

    @Override
    public boolean references(HouseholdId id) {
      return householdId.equals(id);
    }
  }

  /** {@code SET_LOCATION}：对应 {@code HouseholdBook.setLocation}（HEX ↔ UNIT 都合法）。 */
  public record SetLocation(HouseholdId householdId, HouseholdLocation location) implements Step {

    public SetLocation {
      requireHousehold(householdId, "SET_LOCATION");
      Objects.requireNonNull(location, "SET_LOCATION.location");
    }

    @Override
    public String op() {
      return "SET_LOCATION";
    }

    @Override
    public boolean references(HouseholdId id) {
      return householdId.equals(id);
    }
  }

  /** {@code ADD_MEMBERS}：对应 {@code HouseholdBook.addMembers}（新建一个成员批次并挂进家户）。 */
  public record AddMembers(
      HouseholdId householdId,
      PeopleLotId lotId,
      Sex sex,
      long count,
      long ageAtAnchorDays,
      long anchorTick)
      implements Step {

    public AddMembers {
      requireHousehold(householdId, "ADD_MEMBERS");
      Objects.requireNonNull(lotId, "ADD_MEMBERS.lotId");
      Objects.requireNonNull(sex, "ADD_MEMBERS.sex");
    }

    @Override
    public String op() {
      return "ADD_MEMBERS";
    }

    @Override
    public boolean references(HouseholdId id) {
      return householdId.equals(id);
    }
  }

  /** {@code REMOVE_MEMBERS}：对应 {@code HouseholdBook.removeMembers}（扣到 0 删批次）。 */
  public record RemoveMembers(HouseholdId householdId, PeopleLotId lotId, long count)
      implements Step {

    public RemoveMembers {
      requireHousehold(householdId, "REMOVE_MEMBERS");
      Objects.requireNonNull(lotId, "REMOVE_MEMBERS.lotId");
    }

    @Override
    public String op() {
      return "REMOVE_MEMBERS";
    }

    @Override
    public boolean references(HouseholdId id) {
      return householdId.equals(id);
    }
  }

  /** {@code TRANSFER_MEMBERS}：对应 {@code HouseholdBook.transferMembers}（源/目标两条腿原子落账）。 */
  public record TransferMembers(HouseholdId from, HouseholdId to, PeopleLotId lotId, long count)
      implements Step {

    public TransferMembers {
      requireHousehold(from, "TRANSFER_MEMBERS.from");
      requireHousehold(to, "TRANSFER_MEMBERS.to");
      Objects.requireNonNull(lotId, "TRANSFER_MEMBERS.lotId");
    }

    @Override
    public String op() {
      return "TRANSFER_MEMBERS";
    }

    @Override
    public boolean references(HouseholdId id) {
      return from.equals(id) || to.equals(id);
    }
  }

  /** {@code ADJUST_POPULATION}：对应 {@code HouseholdBook.adjustPopulation}（delta 可正可负、不得为 0）。 */
  public record AdjustPopulation(HouseholdId householdId, Sex sex, String ageBracketId, long delta)
      implements Step {

    public AdjustPopulation {
      requireHousehold(householdId, "ADJUST_POPULATION");
      Objects.requireNonNull(sex, "ADJUST_POPULATION.sex");
      if (ageBracketId == null || ageBracketId.isBlank()) {
        throw new IllegalArgumentException("ADJUST_POPULATION.ageBracketId 不得为空白");
      }
      if (delta == 0L) {
        throw new IllegalArgumentException(
            "ADJUST_POPULATION.delta 不得为 0（没有可调整的人数；空改动不落 revision）");
      }
    }

    @Override
    public String op() {
      return "ADJUST_POPULATION";
    }

    @Override
    public boolean references(HouseholdId id) {
      return householdId.equals(id);
    }
  }

  /** {@code SET_VITAL_RATES}：对应 {@code HouseholdBook.setVitalRates}（空率表 = 清空）。 */
  public record SetVitalRates(HouseholdId householdId, HouseholdVitalRates vitalRates)
      implements Step {

    public SetVitalRates {
      requireHousehold(householdId, "SET_VITAL_RATES");
      Objects.requireNonNull(vitalRates, "SET_VITAL_RATES.vitalRates");
    }

    @Override
    public String op() {
      return "SET_VITAL_RATES";
    }

    @Override
    public boolean references(HouseholdId id) {
      return householdId.equals(id);
    }
  }

  private static void requireHousehold(HouseholdId householdId, String op) {
    if (householdId == null) {
      throw new IllegalArgumentException(op + ".household 不得为 null");
    }
  }
}
