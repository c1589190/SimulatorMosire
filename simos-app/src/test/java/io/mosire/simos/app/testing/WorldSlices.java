package io.mosire.simos.app.testing;

import io.mosire.simos.army.ArmyData;
import io.mosire.simos.ga.GaState;
import io.mosire.simos.gov.GovState;
import java.util.Objects;

/**
 * ★★ <b>2026-10-13 收尾测试第 1 层（机械修复）新增</b>：三个切片的载体。
 *
 * <p><b>为什么需要它</b>：2026-10-12 架构轮（批次 3c/3d-1/乙收尾）之后，原先同住 {@code SdState}/{@code GaState} 的三张表
 * <b>各归其主</b>——国家（{@code nations}）住 {@code gov} 切片、军队身份（{@code armies}）住 {@code army} 切片、决策人（{@code
 * decisionMakers}）住 {@code ga} 切片。测试夹具里"造一个小世界"的辅助方法原本一次返回一个 {@code GaState}（那时国家与军队还在里面），
 * 现在必须一次交回三片。
 *
 * <p>★ 这<b>不是新用例</b>：它只是把被架构轮拆散的三片重新打成一个包，让既有夹具的返回值有地方放（否则夹具体内的国家/军队会被静默丢弃）。
 *
 * <p>★ <b>本层未做的事</b>：归属行（{@code GovState.nationAffiliations()} / {@code
 * ArmyData.armyAffiliations()}）本层<b>一律未写</b> ——决策人 id 由用例稍后决定，写表属于夹具重写（第 2 层）。后果见 {@code
 * ScopeFixtures.nationDm} 的已知红点说明。
 */
public record WorldSlices(GovState gov, ArmyData army, GaState ga) {

  public WorldSlices {
    Objects.requireNonNull(gov, "gov");
    Objects.requireNonNull(army, "army");
    Objects.requireNonNull(ga, "ga");
  }

  public static WorldSlices of(GovState gov, ArmyData army, GaState ga) {
    return new WorldSlices(gov, army, ga);
  }

  /** 只有 {@code ga} 片（{@code gov}/{@code army} 为空）——"这个夹具不关心国家与军队"时的缺省。 */
  public static WorldSlices gaOnly(GaState ga) {
    return new WorldSlices(GovState.empty(), ArmyData.empty(), ga);
  }

  /** 只换 {@code ga} 片（国家/军队原样）——方便既有的 {@code .withDecisionMakers(...)} 一类链式造法。 */
  public WorldSlices withGa(GaState next) {
    return new WorldSlices(gov, army, next);
  }
}
