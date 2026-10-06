package io.mosire.simos.social.population;

/**
 * ★★ **城乡二分的人数**（R1.5 的读侧派生量）：某格（或某城）的人里，城镇几个、农村几个。
 *
 * <p>★★ **它是派生量，不是字段**（设计稿 §二/§三 的明令）：{@link PopulationGroup} 的形状由设计稿定死（id / residence / sex /
 * count / age / anchor），**不许多一个"城乡"字段** —— 那正是把 {@code HouseholdEconomy} 的老毛病（拿标签当主键）换个地方重演。
 * "城里的人"这条关系在 R2+ 由 {@code Relation}/{@code HouseholdLaborCommitment} **显式**表达；本轮它由 lot id
 * 的**前缀**判定 （{@link PopulationLots#isUrban(PopulationGroup)}，唯一的拼写点）。
 *
 * <p>★ {@link #total()} = 该格（该城）的人口总量 —— 它就是 R1 那句"人口查询从批次求和"的落点：读口把它与**经济侧**逐行求和并排
 * 发出来，"两侧人口一致"才**读得出来**（R1 只有构造性相等 + 测试断言）。
 *
 * @param urban 城镇人数；不得为负
 * @param rural 农村人数；不得为负
 */
public record UrbanRural(long urban, long rural) {

  public UrbanRural {
    if (urban < 0L) {
      throw new IllegalArgumentException("urban 必须 ≥ 0: " + urban);
    }
    if (rural < 0L) {
      throw new IllegalArgumentException("rural 必须 ≥ 0: " + rural);
    }
  }

  /** 城镇 + 农村（= 该格该城的人口总量）。 */
  public long total() {
    return urban + rural;
  }
}
