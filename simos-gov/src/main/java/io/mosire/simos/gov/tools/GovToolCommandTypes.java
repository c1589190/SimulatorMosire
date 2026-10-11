package io.mosire.simos.gov.tools;

/**
 * 政府工具用到的**跨域命令类型串**（gov 看不见的那三个域的 handler 常量）。
 *
 * <p>★★ <b>为什么必须经端口取</b>：这些字符串的**唯一拼写点**在各域 handler 的 {@code TYPE} 常量里，而那些模块不在 gov 允许面（{@code
 * economy} 实现 / {@code actor} / {@code sd}）⇒ 手抄一份字面量写歪**不会抛异常**，只在运行期变成"未知命令类型" （静默失败）。先例：第五域 unit
 * 的 {@code UnitToolCommandTypes}、第三/四域 social 的 {@code SocialUnitPort} 同款。
 *
 * <p>★ <b>域内可见的命令类型不经这里</b>：{@code unit.*} / {@code social.*} 的常量由工具直接读它们的 handler（unit/social 在
 * gov 允许面内）。★ 另有若干 {@code actor.*} / {@code sd.*} 字面量**本来就拼在 gov 侧**（如 {@code
 * "actor.AdjustAccounts"}、 {@code "sd.PutInfo"} 的历史拼点）——迁移**不新增也不删除**第二处拼法：只有原本读 handler
 * 常量的那几处改走本端口。
 */
public interface GovToolCommandTypes {

  /** {@code economy.SetGovernmentPolicy}（编制/预算政策写口）。 */
  String setGovernmentPolicy();

  /** {@code economy.RegisterGovernment}（政府记录 + 政府家户经济行）。 */
  String registerGovernment();

  /** {@code economy.RegisterHousehold}。 */
  String registerHousehold();

  /** {@code economy.SetHouseholdLabor}。 */
  String setHouseholdLabor();

  /** {@code economy.SetGovServiceCommitment}。 */
  String setGovServiceCommitment();

  /** {@code economy.DefineCurrency}。 */
  String defineCurrency();

  /** {@code economy.RenameCurrency}。 */
  String renameCurrency();

  /** {@code economy.RecordMoneyIssuance}。 */
  String recordMoneyIssuance();

  /** {@code economy.SetOfficialRate}。 */
  String setOfficialRate();

  /** {@code actor.EnsureHouseholdAccount}。 */
  String ensureHouseholdAccount();

  /** {@code actor.RemitGovTreasury}。 */
  String remitGovTreasury();

  /** {@code sd.PutInfo}。 */
  String putInfo();
}
