package io.mosire.simos.unit;

/**
 * ★★ <b>军官/兵员家户的军职类别词表</b>（S3b / 2026-10-09 用户裁定：军官团可以单独建小家户，基层军官可挂在一个大家户的 Army 配置上）。
 *
 * <p>★ <b>它是具名状态的一部分，不是叙事文本</b>：只要参与战斗、动员、供给、编制人数等计算，键就必须是这里的值之一（或 {@link
 * MilitaryHouseholdDuty#appointment()} 这类受校验的短名）——{@code Info}/{@code stateDescriptions} 只放
 * 描述性、叙事性内容，不得当规则容器。
 *
 * <p>★ <b>词表后置</b>：一期只区分"兵员家户 / 军士家户 / 军官家户 / 主官家户"四档；更细的军衔/兵种词表由 Army 侧后续裁定，本枚举不预造。
 */
public enum MilitaryDutyKind {

  /** 兵员家户：整户或大部分成年男性服役；基层军官也可挂在这样的大家户上（用户裁定原话）。 */
  SOLDIER,

  /** 军士家户：伍长/队正一类的基层骨干；可不单独立户，配置挂在其所属大家户上。 */
  NCO,

  /** 军官家户：百人将及以上；可以单独建小家户，再挂 Army 适用的特殊配置。 */
  OFFICER,

  /** 主官家户：一军/一路的主将家户；{@link MilitaryHouseholdDuty#commandOf()} 通常非空。 */
  COMMANDER
}
