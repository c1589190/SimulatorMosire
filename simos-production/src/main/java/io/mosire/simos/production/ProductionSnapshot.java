package io.mosire.simos.production;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.contract.ExperienceTrack;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.HouseholdExperienceKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionEfficiencyState;
import io.mosire.simos.production.contract.ProductionExperienceFeature;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionSkillKey;
import io.mosire.simos.production.contract.ProductionSkillLookup;
import io.mosire.simos.production.contract.ProductionStateLookup;
import io.mosire.simos.social.api.id.HouseholdId;
import io.mosire.simos.util.state.Snapshot;
import io.mosire.simos.util.state.StateRef;
import io.mosire.simos.util.time.SimosTimestamp;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ★★ <b>production 模块的状态切片</b>（D-031 spec §13 Z2；P1 起定义权威）：独立的 record 树，只实现 {@link
 * Snapshot}；同时实现只读定义/技能契约，故经济消费者可只经契约从同一 {@code SimulationState} 解析当刻定义， 而不 import 本实现包。
 *
 * <p>★ {@link #namespace()} 固定返回 {@code "production"}：{@code SimulationState} 构造期校验"modules 的键 ==
 * snapshot.namespace()"。
 *
 * <p>★★ <b>这个字面量全仓有两处同字面</b>：本类与 {@link
 * io.mosire.simos.production.codec.ProductionCodec#namespace()}。改这里必须同时改那边。
 */
public record ProductionSnapshot(StateRef ref, SimosTimestamp timestamp, ProductionData state)
    implements Snapshot, ProductionDefinitionLookup, ProductionSkillLookup, ProductionStateLookup {

  public ProductionSnapshot {
    if (ref == null) {
      throw new IllegalArgumentException("ProductionSnapshot.ref 不得为 null");
    }
    if (timestamp == null) {
      throw new IllegalArgumentException("ProductionSnapshot.timestamp 不得为 null");
    }
    if (state == null) {
      throw new IllegalArgumentException("ProductionSnapshot.state 不得为 null");
    }
  }

  @Override
  public String namespace() {
    return "production";
  }

  @Override
  public Optional<IndustryDefinitionView> industry(IndustryId id) {
    return state.industry(id);
  }

  @Override
  public Optional<ProductionModeDefinitionView> mode(ProductionModeId id) {
    return state.mode(id);
  }

  @Override
  public Map<IndustryId, IndustryDefinitionView> industries() {
    return state.industries();
  }

  @Override
  public Map<ProductionModeId, ProductionModeDefinitionView> modes() {
    return state.modes();
  }

  @Override
  public Optional<ProductionSkillKey> keyOf(IndustryId id) {
    return state.keyOf(id);
  }

  // ── ★★ D-032：实例 / 经验 / 解锁 / 效率 / 覆盖的只读投影 ────────────────────────────────
  //   ★ 快照是实现读契约的"当刻值"形态（同 industry()/modes() 的形制）：economy 经契约只读，
  //     不得写、不得跨 revision 陈旧（I13）。

  /**
   * 当刻全部生产实例（不可变视图）。
   *
   * <p>★ <b>实例侧的结转状态在这里</b>（A′ 批登记面）：{@link ProductionProcess#cycleContributions()} 的 逐 (家户×角色)
   * 加权劳动（不截断）与 {@link #efficiency()} 的三段精度余数。
   */
  @Override
  public Map<ProductionUnitId, ProductionProcess> instances() {
    return state.instances();
  }

  /** 单个实例；不存在 ⇒ empty。 */
  public Optional<ProductionProcess> instance(ProductionUnitId id) {
    return state.instance(id);
  }

  /** 当刻全部经验条定义（不可变视图）。 */
  public Map<ExperienceTrackId, ExperienceTrack> experienceTracks() {
    return state.experienceTracks();
  }

  /**
   * 当刻全部家户经验账行（不可变视图；★ 值是<b>完整经验特征</b> = 主值 + 精度余数）。
   *
   * <p>★★ <b>经验侧的结转状态就是这里的 {@link ProductionExperienceFeature#remainder()}</b> （键 = 家户 × 经验条）：A′
   * 批起同 tick 的实际贡献先按该键归并、再一次性换算经验增量并结转这个余数 （§13.8 末段）—— 迁移/合户/对账必须整条读它，不能只看主值。
   */
  public Map<HouseholdExperienceKey, ProductionExperienceFeature> householdExperience() {
    return state.householdExperience();
  }

  /** 当刻全部解锁行（不可变视图）。 */
  public Map<HouseholdId, Set<IndustryId>> unlocks() {
    return state.unlocks();
  }

  /** 当刻全部效率余数行（不可变视图）。 */
  public Map<ProductionUnitId, ProductionEfficiencyState> efficiency() {
    return state.efficiency();
  }

  /** 当刻全部 GM 产出覆盖（不可变视图）。 */
  @Override
  public Map<IndustryId, Map<CommodityId, Long>> outputQuantityOverrides() {
    return state.outputQuantityOverrides();
  }
}
