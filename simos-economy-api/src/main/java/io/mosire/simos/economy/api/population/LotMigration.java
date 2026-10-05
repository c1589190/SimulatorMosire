package io.mosire.simos.economy.api.population;

import io.mosire.simos.economy.api.cohort.ResidenceKind;
import io.mosire.simos.social.api.id.PeopleLotId;
import io.mosire.simos.map.CityId;
import io.mosire.simos.map.hex.HexCoord;

/**
 * ★★ <b>一笔跨居住地的批次迁移（P8 契约）</b>：{@code count} 个属性相同的人从 {@link #sourceLot()} 拆出、并入 {@link
 * #targetLot()}，并换到 {@link #toResidence()} 居住。
 *
 * <pre>
 * sourceLot  ──拆出 count 人──→ targetLot
 * from       ──换居住地──────→ toResidence（属于 toCityId 这座城）
 * </pre>
 *
 * <p>★★ <b>迁移不是出生/死亡</b>：{@link LotChange} 用 {@code births}/{@code deaths} 两条独立落账表达人口增减，
 * 而本记录表达的是<b>同一批人换了居住地</b> —— 总量不变、身份按调用方规则拆/合。把它硬塞进 {@code LotChange} 会让
 * “死了一个人”与“一个人搬了家”在流水里长得一样（P8 计划明文禁止改 {@code LotChange} 的语义）。
 *
 * <p>★★ <b>本记录不是第二份人口账</b>：它只带稳定身份、起止居住地、人数与具名原因；<b>不</b>存“目标家户有多少人 / 多少劳动 / 多少债”。这些按调用方的当前状态现算：
 *
 * <ul>
 *   <li><b>目标 lot 可以不存在</b>⇒ 由调用方创建；目标 lot 已存在 ⇒ 由调用方合并（拆/合是两步，本记录只描述一步）；
 *   <li><b>劳动随行比例</b>由调用方按 {@code HouseholdEconomy} 现算（本记录不写“第二份劳动账”）；
 *   <li><b>债务随行比例</b>由调用方按债务表现算（逐合同取整；唯一写口是 {@code DebtContractBook}）；
 *   <li><b>社会侧人口真值源</b>仍是 {@code PopulationGroup}（换 {@code residence}、{@code id} 不变），本记录是两侧
 *       执行同一个计划的桥。
 * </ul>
 *
 * <p>★ <b>量纲</b>：{@code count} 是人（整数，{@code &gt; 0}）—— “迁移 0 个人”不是一种迁移，是空操作，调用方不该落记录。
 *
 * @param sourceLot 迁出的源批次；不得为 null
 * @param targetLot 迁入的目标批次；不得为 null（可以尚不存在，由调用方创建/合并）
 * @param from 源批次迁出前的居住格；不得为 null
 * @param toCityId 迁入城市（稳定身份，不是它的坐标）；不得为 null
 * @param toResidence 迁入后的居住格（通常 = 该城的 {@code at()}）；不得为 null
 * @param count 本笔迁出人数（人）；必须 {@code &gt; 0}
 * @param reason 具名原因（审计/读口用）；不得为空白
 */
public record LotMigration(
    PeopleLotId sourceLot,
    PeopleLotId targetLot,
    HexCoord from,
    CityId toCityId,
    HexCoord toResidence,
    long count,
    String reason) {

  public LotMigration {
    if (sourceLot == null) {
      throw new IllegalArgumentException("LotMigration.sourceLot 不得为 null");
    }
    if (targetLot == null) {
      throw new IllegalArgumentException("LotMigration.targetLot 不得为 null（不存在时由调用方创建/合并）");
    }
    if (from == null) {
      throw new IllegalArgumentException("LotMigration.from 不得为 null");
    }
    if (toCityId == null) {
      throw new IllegalArgumentException("LotMigration.toCityId 不得为 null");
    }
    if (toResidence == null) {
      throw new IllegalArgumentException("LotMigration.toResidence 不得为 null");
    }
    if (count <= 0L) {
      throw new IllegalArgumentException("LotMigration.count 必须 > 0（迁移 0 人是空操作，不落记录）: " + count);
    }
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("LotMigration.reason 不得为空白");
    }
  }

  /**
   * ★ 只读派生判断：目标批次是不是<b>城镇</b>批次（按批次 id 前缀判，拼写点只有 {@link ResidenceKind#ofLot} 一处）。
   *
   * <p>它服务读口/审计（“这笔是不是城市化迁移”），<b>不</b>参与任何写入或守恒计算。
   */
  public boolean movedToUrban() {
    return ResidenceKind.ofLot(targetLot) == ResidenceKind.URBAN;
  }
}
