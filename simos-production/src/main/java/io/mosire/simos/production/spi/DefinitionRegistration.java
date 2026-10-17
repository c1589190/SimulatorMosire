package io.mosire.simos.production.spi;

import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.production.ProductionData;
import io.mosire.simos.production.change.ProductionChangeSet;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * ★★ <b>按需产业定义的 production 侧唯一登记投影</b>（D-031 P1 §9.1/§9.2；app 只转接）。
 *
 * <p>★★ <b>为什么必须住在 production</b>：日结算在段内按需创建产业（跑商 {@code trade} / 候选模板），这些定义必须与经济实例在 <b>同一次
 * Advance</b> 原子落账。合并/冲突/校验规则是生产定义的领域规则 ⇒ 归 production；app 两个日循环 （{@code
 * PopulationEconomyTimeParticipant} 与备用 {@code EconomyOwnershipTimeParticipant}） 只把候选态与基态传进来、取回
 * ChangeSet，不各抄一份合并逻辑。
 *
 * <p>★ <b>语义</b>：候选态 id 与基态逐值相同 = 幂等 no-op；同 id 不同值 = {@link IllegalStateException}（装配/一致性故障，
 * 不静默覆盖）；候选之间同 id 不同值 = 同上。返回从两整份状态派生的 {@link ProductionChangeSet}（铁律 5）。
 */
public final class DefinitionRegistration {

  private DefinitionRegistration() {}

  /** 登记投影：目标状态 + 派生变更集。 */
  public record Projection(ProductionData projected, ProductionChangeSet changeSet) {}

  /**
   * 把本次 Advance 的按需候选定义合入 production 基态，返回目标状态与派生变更集。
   *
   * @param base 本段 Advance 起点的 production 状态；不得为 null
   * @param created 本段按需创建的定义（键 == 值内 id；不得为 null）
   * @throws IllegalStateException 同 id 与基态/彼此不同值（冲突，不静默覆盖）
   */
  public static Projection registerCreated(
      ProductionData base, Map<IndustryId, IndustryDefinitionView> created) {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(created, "created");
    if (created.isEmpty()) {
      return new Projection(base, ProductionChangeSet.between(base, base));
    }
    Map<IndustryId, IndustryDefinitionView> merged = new LinkedHashMap<>(base.industries());
    for (Map.Entry<IndustryId, IndustryDefinitionView> entry : created.entrySet()) {
      IndustryId id = Objects.requireNonNull(entry.getKey(), "created 的键");
      IndustryDefinitionView definition = Objects.requireNonNull(entry.getValue(), "created 的值");
      if (!id.equals(definition.id())) {
        throw new IllegalStateException("按需定义的键必须与值内 id 一致：键=" + id + "，值内 id=" + definition.id());
      }
      IndustryDefinitionView existing = merged.get(id);
      if (existing == null) {
        merged.put(id, definition);
        continue;
      }
      if (!existing.equals(definition)) {
        throw new IllegalStateException("按需定义与 production 当刻权威冲突（同 id 不同值，拒绝静默覆盖）: " + id.value());
      }
      // 同 id 同值：幂等 no-op（不重写、不报冲突）。
    }
    ProductionData projected = base.withIndustries(merged);
    return new Projection(projected, ProductionChangeSet.between(base, projected));
  }
}
