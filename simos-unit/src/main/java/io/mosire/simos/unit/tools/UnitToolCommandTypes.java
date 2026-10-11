package io.mosire.simos.unit.tools;

/**
 * 单位工具面的**跨域命令类型口**：两条"新家户登记"命令的类型串，唯一拼写点在**别的模块**的 handler 上。
 *
 * <p>★★ <b>为什么必须经端口取、不许在 unit 里手抄字面量</b>（与 B3 第三/四域 social 的 {@code
 * SocialUnitPort.unitHouseholdsCommandType} 同一条先例）：
 *
 * <ul>
 *   <li>{@link #ensureHouseholdAccountType} 的唯一拼写点是 {@code simos-actor} 的 {@code
 *       EnsureHouseholdAccountHandler.TYPE}——{@code simos-actor} 在 unit 的**禁列**里，unit 结构上够不着；
 *   <li>{@link #registerHouseholdType} 的唯一拼写点是 {@code simos-economy} 的 {@code
 *       EconomyRegisterHouseholdHandler.TYPE}——同理（unit 只看得见 {@code economy-api}，看不见 economy 实现）。
 * </ul>
 *
 * <p>★ <b>手抄一份类型串的代价</b>：写歪一个字**不抛异常**——命令在装配面上路由不到 handler，只在运行期变成一条 "未知命令类型"的拒因；而正确性判据（这两个常量与各
 * handler 的 {@code type()} 同源）没有任何编译期护栏 ⇒ 拼写点只能有一处。
 *
 * <p>★ 本接口只能住在 {@code simos-unit}：它是域自己的声明面（方法只回 {@code String}，实现由组合根给）。
 */
public interface UnitToolCommandTypes {

  /** {@code actor.EnsureHouseholdAccount} 的命令类型（{@code UnitToolCommandTypes} 的唯一拼写点）。 */
  String ensureHouseholdAccountType();

  /** {@code economy.RegisterHousehold} 的命令类型（同上）。 */
  String registerHouseholdType();
}
