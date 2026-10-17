package io.mosire.simos.economy.time;

import io.mosire.simos.economy.EconomyDayView;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.MarketZoneId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.economy.api.production.ProductionRoleId;
import io.mosire.simos.economy.model.ProductionWageDefault;
import io.mosire.simos.economy.model.ProductionWageDefaultLookup;
import io.mosire.simos.economy.model.ProductionWagePosting;
import io.mosire.simos.economy.model.ProductionWageQuote;
import io.mosire.simos.economy.model.WageDefaultKey;
import io.mosire.simos.economy.model.WagePostingKey;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★★ <b>W2a 的真实工资读口</b>（D-032 D3 §2.2.1/§2.3）。
 *
 * <p>★★ <b>两类读源严格分开</b>：
 *
 * <ul>
 *   <li>已存在实例的执行期报价：只读 {@code productionWagePostings}（{@link ProductionWageQuoteLookup}）；
 *   <li>实例存在前的候选估价：只读 {@code productionWageDefaults}（{@link ProductionWageDefaultLookup}）。
 * </ul>
 *
 * <p>★ <b>缺 = empty、0 = 合法</b>：本类不使用 {@code getOrDefault(..., 0)}；显式 0 报价/默认会原样返回。 ★ 构造期按当刻视图
 * 冻结两张表的引用，服务一个 tick 的报价快照；U20/命令改价只影响后续 tick。
 */
public final class ProductionWageBook
    implements ProductionWageQuoteLookup, ProductionWageDefaultLookup {

  private final Map<WagePostingKey, ProductionWagePosting> postings;
  private final Map<WageDefaultKey, ProductionWageDefault> defaults;

  /** 从当刻日视图构造一个 tick 的只读快照。 */
  public ProductionWageBook(EconomyDayView view) {
    Objects.requireNonNull(view, "view");
    this.postings = freeze(view.productionWagePostings(), "productionWagePostings");
    this.defaults = freeze(view.productionWageDefaults(), "productionWageDefaults");
  }

  /** 显式以两张只读表构造（测试/装配用；表必须在别处冻结）。 */
  public ProductionWageBook(
      Map<WagePostingKey, ProductionWagePosting> postings,
      Map<WageDefaultKey, ProductionWageDefault> defaults) {
    this.postings = freeze(postings, "postings");
    this.defaults = freeze(defaults, "defaults");
  }

  @Override
  public Optional<ProductionWageQuote> quoteFor(ProductionUnitId unit, ProductionRoleId role) {
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(role, "role");
    ProductionWagePosting posting = postings.get(new WagePostingKey(unit, role));
    return posting == null
        ? Optional.empty()
        : Optional.of(new ProductionWageQuote(posting.currency(), posting.wagePerLaborMilli()));
  }

  @Override
  public Optional<ProductionWageDefault> defaultFor(
      IndustryId plan, ProductionRoleId role, MarketZoneId zone) {
    Objects.requireNonNull(plan, "plan");
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(zone, "zone");
    return Optional.ofNullable(defaults.get(new WageDefaultKey(plan, role, zone)));
  }

  private static <K, V> Map<K, V> freeze(Map<K, V> source, String name) {
    Objects.requireNonNull(source, name);
    return Collections.unmodifiableMap(new LinkedHashMap<>(source));
  }
}
