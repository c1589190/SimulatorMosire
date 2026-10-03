package io.mosire.simos.economy.model;

/**
 * ★★ <b>城市土地/承载的纯值记录（P6）</b>：把探针 {@code ProbeEconomy.CityState} 的“承载 + 慢速扩建 + 城区占地”口径 翻成不可变值类型，作为
 * {@code City.props}/{@code SocialCity.props} 的只读映射目标。
 *
 * <p>★★ <b>它不是状态、也不反查 map/social</b>：本类只存值；读口在 {@link CityLandBook}，由组合根/调用方把 {@code City.props()}
 * 或 {@code SocialCity.props()} 传进来。P6 不新增 {@code EconomyData} 组件，也不接地图写路径。
 *
 * <p>★★ <b>逐值对照探针公式（{@code ProbeEconomy.updateCitiesAndRuralMerchants}）</b>：
 *
 * <pre>
 * used = 人口 / {@link #POPULATION_PER_CAPACITY_POINT}
 *      + 作坊数 × {@link #CAPACITY_USE_PER_WORKSHOP}
 *      + 商人城区当量
 *      + 累计贸易量 / {@link #TRADE_VOLUME_PER_CAPACITY_POINT}
 * 压力 = used × {@link #PER_MILLE} ≥ capacity × {@link #CITY_EXPANSION_PRESSURE_PER_MILLE}
 *   ⇒ expansionProgress += {@link #CITY_EXPANSION_SPEED}
 *   ⇒ progress ≥ {@link #CITY_EXPANSION_THRESHOLD} 时：
 *        capacity += {@link #CITY_EXPANSION_STEP}
 *        expansionProgress = 0
 *        expansionCount += 1
 *        builtAreaPerMille += {@link #CITY_BUILT_AREA_STEP_PER_MILLE}
 * </pre>
 *
 * <p>★★ <b>城区占地辐射（逐值对照探针 {@code cityBuiltAreaMu}）</b>：城市把它的 {@code 城市可耕地 × builtAreaPerMille/1000}
 * 按“离城越近权重越大”分摊给半径内各格：
 *
 * <pre>
 * weight(d) = radiusHex − d + 1            （d ≤ radiusHex；越界 ⇒ 0）
 * share     = ⌊totalBuiltAreaMu × weight(d) ÷ max(1, weightSum)⌋
 * </pre>
 *
 * <p>因此本记录的 {@link #builtAreaMu()} 语义是<b>“本格分摊到的城区占地”</b>（由调用方按上式算出后写入）， {@link
 * #builtAreaTotalMu(long)} 才是该城的总占地；{@link #availableArableMu(long)} 扣的就是本格这一份。
 *
 * <p>★ <b>单位不写死在类型里</b>：探针用“亩/整数商品”，正式运行时的 {@code landMilliMuOf} 用“毫亩” （1 亩 = 1000
 * 毫亩）。本记录的算式对单位是线性的；调用方必须让 {@code arableMu}/{@code builtAreaMu} 与 {@code capacity}/{@code
 * tradeVolume} 各自保持同一单位、并在跨层比较前按既有换算点折算。
 *
 * <p>★ <b>不可变</b>：record 全部组件是 long；每个 {@code withX} 都返回新值；构造期把所有字段判成非负。 零值承载（{@code capacity ==
 * 0}）读作“尚未初始化城市土地”，{@link #advance()} 对它不凭空扩建 —— 探针世界承载恒 {@code ≥ 10}，该守卫不改变任何探针读数；显式写入 {@code
 * capacity > 0} 后公式逐值生效。
 *
 * @param capacity 城市承载（探针 {@code CityState.capacity}）；不得为负
 * @param usedCapacity 已占承载（探针 {@code CityState.usedCapacity}）；不得为负
 * @param expansionProgress 扩建进度点（探针 {@code CityState.expansionProgress}）；不得为负
 * @param expansionCount 累计扩建次数（探针 {@code CityState.expansionCount}）；不得为负
 * @param builtAreaPerMille 城区占城市可耕地的千分比（探针 {@code CityState.builtAreaPerMille}）；不得为负
 * @param builtAreaMu 本格分摊到的城区占地（探针 {@code cityBuiltAreaMu(hex)} 的结果）；不得为负
 * @param radiusHex 城市辐射半径（探针 {@code CityState.radiusHex}）；不得为负
 * @param lastTradeVolume 本轮的贸易量（探针 {@code CityState.lastTradeVolume}）；不得为负
 * @param cumulativeTradeVolume 累计贸易量（探针 {@code CityState.cumulativeTradeVolume}）；不得为负
 */
public record CityLand(
    long capacity,
    long usedCapacity,
    long expansionProgress,
    long expansionCount,
    long builtAreaPerMille,
    long builtAreaMu,
    long radiusHex,
    long lastTradeVolume,
    long cumulativeTradeVolume) {

  /** 探针 {@code CITY_EXPANSION_PRESSURE_PER_MILLE = 800}：占用达到承载的 800‰ 才开始推进扩建。 */
  public static final long CITY_EXPANSION_PRESSURE_PER_MILLE = 800L;

  /** 探针 {@code CITY_EXPANSION_SPEED = 1}：满足压力时每轮进度 +1。 */
  public static final long CITY_EXPANSION_SPEED = 1L;

  /** 探针 {@code CITY_EXPANSION_THRESHOLD = 200}：进度到 200 触发一次扩建。 */
  public static final long CITY_EXPANSION_THRESHOLD = 200L;

  /** 探针 {@code CITY_EXPANSION_STEP = 20}：每次扩建增加的承载。 */
  public static final long CITY_EXPANSION_STEP = 20L;

  /** 探针 {@code CITY_BUILT_AREA_STEP_PER_MILLE = 20}：每次扩建增加的城区比例（‰）。 */
  public static final long CITY_BUILT_AREA_STEP_PER_MILLE = 20L;

  /** 千分比口径常量（探针 {@code PER_MILLE}）。 */
  public static final long PER_MILLE = 1000L;

  /** 探针 {@code used += population / 100} 的分母：每 100 人占 1 点承载。 */
  public static final long POPULATION_PER_CAPACITY_POINT = 100L;

  /** 探针 {@code used += 2}（每座作坊占 2 点承载）。 */
  public static final long CAPACITY_USE_PER_WORKSHOP = 2L;

  /** 探针 {@code used += cumulativeTradeVolume / 1000} 的分母：每 1000 累计贸易量占 1 点承载。 */
  public static final long TRADE_VOLUME_PER_CAPACITY_POINT = 1000L;

  public CityLand {
    if (capacity < 0L) {
      throw new IllegalArgumentException("CityLand.capacity 不得为负: " + capacity);
    }
    if (usedCapacity < 0L) {
      throw new IllegalArgumentException("CityLand.usedCapacity 不得为负: " + usedCapacity);
    }
    if (expansionProgress < 0L) {
      throw new IllegalArgumentException("CityLand.expansionProgress 不得为负: " + expansionProgress);
    }
    if (expansionCount < 0L) {
      throw new IllegalArgumentException("CityLand.expansionCount 不得为负: " + expansionCount);
    }
    if (builtAreaPerMille < 0L) {
      throw new IllegalArgumentException("CityLand.builtAreaPerMille 不得为负: " + builtAreaPerMille);
    }
    if (builtAreaMu < 0L) {
      throw new IllegalArgumentException("CityLand.builtAreaMu 不得为负: " + builtAreaMu);
    }
    if (radiusHex < 0L) {
      throw new IllegalArgumentException("CityLand.radiusHex 不得为负: " + radiusHex);
    }
    if (lastTradeVolume < 0L) {
      throw new IllegalArgumentException("CityLand.lastTradeVolume 不得为负: " + lastTradeVolume);
    }
    if (cumulativeTradeVolume < 0L) {
      throw new IllegalArgumentException(
          "CityLand.cumulativeTradeVolume 不得为负: " + cumulativeTradeVolume);
    }
  }

  /**
   * ★ 探针 {@code updateCitiesAndRuralMerchants} 的占用算式（唯一拼写点）：先加人口份、再加作坊份、再商人当量、
   * 最后加累计贸易量份；除法与探针同为整数向下取整，加/乘用 {@link Math#addExact}/{@link Math#multiplyExact} 在溢出处判死（探针用普通
   * {@code +=}，本类型不静默回绕）。
   *
   * @param population 城市人口；不得为负
   * @param workshopCount 城市作坊数；不得为负
   * @param merchantDistrictUse 商人城区占用当量（{@code MerchantTier.districtUse} 之和）；不得为负
   * @param cumulativeTradeVolume 累计贸易量（应已含本轮）；不得为负
   * @return 已占承载
   */
  public static long usedCapacityOf(
      long population, long workshopCount, long merchantDistrictUse, long cumulativeTradeVolume) {
    if (population < 0L) {
      throw new IllegalArgumentException("CityLand.usedCapacityOf population 不得为负: " + population);
    }
    if (workshopCount < 0L) {
      throw new IllegalArgumentException(
          "CityLand.usedCapacityOf workshopCount 不得为负: " + workshopCount);
    }
    if (merchantDistrictUse < 0L) {
      throw new IllegalArgumentException(
          "CityLand.usedCapacityOf merchantDistrictUse 不得为负: " + merchantDistrictUse);
    }
    if (cumulativeTradeVolume < 0L) {
      throw new IllegalArgumentException(
          "CityLand.usedCapacityOf cumulativeTradeVolume 不得为负: " + cumulativeTradeVolume);
    }
    long used = population / POPULATION_PER_CAPACITY_POINT;
    used = Math.addExact(used, Math.multiplyExact(workshopCount, CAPACITY_USE_PER_WORKSHOP));
    used = Math.addExact(used, merchantDistrictUse);
    used = Math.addExact(used, cumulativeTradeVolume / TRADE_VOLUME_PER_CAPACITY_POINT);
    return used;
  }

  /** 换承载（其余不动）。 */
  public CityLand withCapacity(long value) {
    return new CityLand(
        value,
        usedCapacity,
        expansionProgress,
        expansionCount,
        builtAreaPerMille,
        builtAreaMu,
        radiusHex,
        lastTradeVolume,
        cumulativeTradeVolume);
  }

  /** 换已占承载（其余不动）。 */
  public CityLand withUsedCapacity(long value) {
    return new CityLand(
        capacity,
        value,
        expansionProgress,
        expansionCount,
        builtAreaPerMille,
        builtAreaMu,
        radiusHex,
        lastTradeVolume,
        cumulativeTradeVolume);
  }

  /** 换扩建进度（其余不动）。 */
  public CityLand withExpansionProgress(long value) {
    return new CityLand(
        capacity,
        usedCapacity,
        value,
        expansionCount,
        builtAreaPerMille,
        builtAreaMu,
        radiusHex,
        lastTradeVolume,
        cumulativeTradeVolume);
  }

  /** 换累计扩建次数（其余不动）。 */
  public CityLand withExpansionCount(long value) {
    return new CityLand(
        capacity,
        usedCapacity,
        expansionProgress,
        value,
        builtAreaPerMille,
        builtAreaMu,
        radiusHex,
        lastTradeVolume,
        cumulativeTradeVolume);
  }

  /** 换城区比例（其余不动）。 */
  public CityLand withBuiltAreaPerMille(long value) {
    return new CityLand(
        capacity,
        usedCapacity,
        expansionProgress,
        expansionCount,
        value,
        builtAreaMu,
        radiusHex,
        lastTradeVolume,
        cumulativeTradeVolume);
  }

  /** 换本格分摊的城区占地（其余不动）。 */
  public CityLand withBuiltAreaMu(long value) {
    return new CityLand(
        capacity,
        usedCapacity,
        expansionProgress,
        expansionCount,
        builtAreaPerMille,
        value,
        radiusHex,
        lastTradeVolume,
        cumulativeTradeVolume);
  }

  /** 换辐射半径（其余不动）。 */
  public CityLand withRadiusHex(long value) {
    return new CityLand(
        capacity,
        usedCapacity,
        expansionProgress,
        expansionCount,
        builtAreaPerMille,
        builtAreaMu,
        value,
        lastTradeVolume,
        cumulativeTradeVolume);
  }

  /** 换本轮贸易量（其余不动）。 */
  public CityLand withLastTradeVolume(long value) {
    return new CityLand(
        capacity,
        usedCapacity,
        expansionProgress,
        expansionCount,
        builtAreaPerMille,
        builtAreaMu,
        radiusHex,
        value,
        cumulativeTradeVolume);
  }

  /** 换累计贸易量（其余不动）。 */
  public CityLand withCumulativeTradeVolume(long value) {
    return new CityLand(
        capacity,
        usedCapacity,
        expansionProgress,
        expansionCount,
        builtAreaPerMille,
        builtAreaMu,
        radiusHex,
        lastTradeVolume,
        value);
  }

  /**
   * ★ 把本轮贸易量滚进累计量，并把本轮读数清零（探针每轮开始会清 {@code lastTradeVolume}；滚入发生在轮末，
   * 两种次序在“下一轮开始前累计量已含上一轮”这一点上等价）。本轮量为 0 ⇒ 原样返回。
   */
  public CityLand rollTradeVolume() {
    if (lastTradeVolume == 0L) {
      return this;
    }
    return withCumulativeTradeVolume(Math.addExact(cumulativeTradeVolume, lastTradeVolume))
        .withLastTradeVolume(0L);
  }

  /**
   * ★★ <b>探针扩建段逐值复刻</b>（不含占用计算；调用方先通过 {@link #withUsedCapacity(long)} 或 {@link #advance(long)}
   * 写入已占承载）：
   *
   * <pre>
   * if (capacity &gt; 0 &amp;&amp; used × 1000 ≥ capacity × 800) {
   *   expansionProgress += 1;
   *   if (expansionProgress ≥ 200) { capacity += 20; progress = 0; count += 1; builtAreaPerMille += 20; }
   * }
   * </pre>
   *
   * <p>★ {@code capacity ≤ 0} = 尚未初始化城市土地（缺键默认值/显式置零）⇒ 不凭空扩建；探针世界承载恒 {@code ≥ 10}，这个边界不改变任何探针读数。
   */
  public CityLand advance() {
    if (capacity <= 0L) {
      return this;
    }
    long pressure = Math.multiplyExact(usedCapacity, PER_MILLE);
    long trigger = Math.multiplyExact(capacity, CITY_EXPANSION_PRESSURE_PER_MILLE);
    if (pressure < trigger) {
      return this;
    }
    long progress = Math.addExact(expansionProgress, CITY_EXPANSION_SPEED);
    if (progress < CITY_EXPANSION_THRESHOLD) {
      return withExpansionProgress(progress);
    }
    return new CityLand(
        Math.addExact(capacity, CITY_EXPANSION_STEP),
        usedCapacity,
        0L,
        Math.addExact(expansionCount, 1L),
        Math.addExact(builtAreaPerMille, CITY_BUILT_AREA_STEP_PER_MILLE),
        builtAreaMu,
        radiusHex,
        lastTradeVolume,
        cumulativeTradeVolume);
  }

  /**
   * ★★ <b>一轮城市土地推进（探针 {@code updateCitiesAndRuralMerchants} 的次序）</b>：
   *
   * <pre>
   * ① cumulativeTradeVolume += lastTradeVolume; lastTradeVolume = 0
   * ② usedCapacity = usedCapacityExcludingTradeVolume + cumulativeTradeVolume / 1000
   * ③ advance()
   * </pre>
   *
   * <p>{@code usedCapacityExcludingTradeVolume} = 人口/100 + 作坊数×2 + 商人城区当量（不含累计贸易量那一份）。
   *
   * @param usedCapacityExcludingTradeVolume 不含累计贸易量压力的已占承载；不得为负
   */
  public CityLand advance(long usedCapacityExcludingTradeVolume) {
    if (usedCapacityExcludingTradeVolume < 0L) {
      throw new IllegalArgumentException(
          "CityLand.advance usedCapacityExcludingTradeVolume 不得为负: "
              + usedCapacityExcludingTradeVolume);
    }
    CityLand rolled = rollTradeVolume();
    long used =
        Math.addExact(
            usedCapacityExcludingTradeVolume,
            rolled.cumulativeTradeVolume / TRADE_VOLUME_PER_CAPACITY_POINT);
    return rolled.withUsedCapacity(used).advance();
  }

  /**
   * ★ 城市城区<b>总</b>占地（探针 {@code cityBuiltAreaMu} 里的 {@code builtTotal}）： {@code 城市可耕地 ×
   * builtAreaPerMille / 1000}（整数向下取整，与探针同序）。
   *
   * @param arableMuAtCity 城市本格的可耕地；不得为负
   */
  public long builtAreaTotalMu(long arableMuAtCity) {
    if (arableMuAtCity < 0L) {
      throw new IllegalArgumentException("CityLand.builtAreaTotalMu 不得为负: " + arableMuAtCity);
    }
    return Math.multiplyExact(arableMuAtCity, builtAreaPerMille) / PER_MILLE;
  }

  /**
   * ★ 本城城区总占地按半径内距离权重分摊给“距城 {@code distanceHex} 格”的格。
   *
   * @param arableMuAtCity 城市本格的可耕地；不得为负
   * @param distanceHex 目标格到城市本格的 hex 距离；不得为负
   * @param weightSum 半径内全部格的权重和（探针 {@code weightSum}；由调用方按实际格集算）；{@code ≤ 0} 时按 {@code max(1,
   *     weightSum)} 处理（与探针一致）
   * @return 该格分摊到的城区占地；距离超出本城半径 ⇒ 0
   */
  public long radiatedBuiltAreaMu(long arableMuAtCity, long distanceHex, long weightSum) {
    return radiatedBuiltAreaMu(builtAreaTotalMu(arableMuAtCity), radiusHex, distanceHex, weightSum);
  }

  /** 半径 {@code radiusHex} 内距城 {@code distanceHex} 格的权重（探针 {@code radiusHex - d + 1}；越界 ⇒ 0）。 */
  public static long builtAreaWeight(long radiusHex, long distanceHex) {
    if (radiusHex < 0L) {
      throw new IllegalArgumentException("CityLand.builtAreaWeight radiusHex 不得为负: " + radiusHex);
    }
    if (distanceHex < 0L) {
      throw new IllegalArgumentException(
          "CityLand.builtAreaWeight distanceHex 不得为负: " + distanceHex);
    }
    if (distanceHex > radiusHex) {
      return 0L;
    }
    return radiusHex - distanceHex + 1L;
  }

  /**
   * ★★ <b>探针 {@code cityBuiltAreaMu} 的辐射分配纯函数</b>：
   *
   * <pre>
   * share = totalBuiltAreaMu × weight(radiusHex, distanceHex) / max(1, weightSum)
   * </pre>
   *
   * @param totalBuiltAreaMu 城市城区总占地（先用 {@link #builtAreaTotalMu(long)} 从城市可耕地算得）；不得为负
   * @param radiusHex 城市辐射半径；不得为负
   * @param distanceHex 目标格到城市本格的 hex 距离；不得为负
   * @param weightSum 半径内全部格的权重和；{@code ≤ 0} 时按 {@code max(1, weightSum)} 处理（与探针一致）
   * @return 该格分摊到的城区占地；距离超出半径 ⇒ 0
   */
  public static long radiatedBuiltAreaMu(
      long totalBuiltAreaMu, long radiusHex, long distanceHex, long weightSum) {
    if (totalBuiltAreaMu < 0L) {
      throw new IllegalArgumentException(
          "CityLand.radiatedBuiltAreaMu totalBuiltAreaMu 不得为负: " + totalBuiltAreaMu);
    }
    long weight = builtAreaWeight(radiusHex, distanceHex);
    if (weight == 0L) {
      return 0L;
    }
    return Math.multiplyExact(totalBuiltAreaMu, weight) / Math.max(1L, weightSum);
  }

  /**
   * ★ 探针 {@code availableArableMu = max(0, arableMu − cityBuiltAreaMu)} 的形式化：本格可耕地减去 {@link
   * #builtAreaMu()}（本格分摊到的城区占地），不为负。
   *
   * @param arableMu 本格可耕地（与 {@link #builtAreaMu()} 同单位）；不得为负
   */
  public long availableArableMu(long arableMu) {
    if (arableMu < 0L) {
      throw new IllegalArgumentException("CityLand.availableArableMu 不得为负: " + arableMu);
    }
    return Math.max(0L, Math.subtractExact(arableMu, builtAreaMu));
  }
}
