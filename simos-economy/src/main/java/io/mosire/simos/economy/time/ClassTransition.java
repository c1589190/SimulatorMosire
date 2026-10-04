package io.mosire.simos.economy.time;

import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.economy.api.id.SocialClassId;
import java.util.Objects;

/**
 * ★★ <b>S3.4 一条具名的阶层写回审计读数</b>：关账日 {@code ClassRow.view} 从 {@code fromClass} 跳到 {@code toClass}
 * 的事实。
 *
 * <p>★ <b>它不是状态</b>：状态真值是 {@code ClassRow.view.stratum}；本记录只服务读口（{@code
 * ApiViews.classTransitions}）与 V 阶段的"写回真的发生了"核对。★ {@code reason} 保留 {@code lastClassReason} 的语义（由
 * {@code HouseholdClassRule.Classification.reason} 给出），不另造一套解释。
 */
public record ClassTransition(
    long day,
    HouseholdId household,
    SocialClassId fromClass,
    SocialClassId toClass,
    String reason) {

  public ClassTransition {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(fromClass, "fromClass");
    Objects.requireNonNull(toClass, "toClass");
    Objects.requireNonNull(reason, "reason");
    if (day < 0L) {
      throw new IllegalArgumentException("ClassTransition.day 不得为负: " + day);
    }
    if (fromClass.equals(toClass)) {
      throw new IllegalArgumentException("ClassTransition 的两端不得相同: " + fromClass);
    }
  }
}
