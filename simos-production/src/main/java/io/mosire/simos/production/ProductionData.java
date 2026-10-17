package io.mosire.simos.production;

import io.mosire.simos.economy.api.id.CommodityId;
import io.mosire.simos.economy.api.id.IndustryId;
import io.mosire.simos.economy.api.id.ProductionModeId;
import io.mosire.simos.economy.api.id.ProductionUnitId;
import io.mosire.simos.production.contract.ExperienceState;
import io.mosire.simos.production.contract.ExperienceTrack;
import io.mosire.simos.production.contract.ExperienceTrackId;
import io.mosire.simos.production.contract.HouseholdExperienceKey;
import io.mosire.simos.production.contract.IndustryDefinitionView;
import io.mosire.simos.production.contract.ProductionDefinitionLookup;
import io.mosire.simos.production.contract.ProductionEfficiencyState;
import io.mosire.simos.production.contract.ProductionExperienceFeature;
import io.mosire.simos.production.contract.ProductionModeDefinitionView;
import io.mosire.simos.production.contract.ProductionProcess;
import io.mosire.simos.production.contract.ProductionRecipe;
import io.mosire.simos.production.contract.ProductionSkillKey;
import io.mosire.simos.production.contract.ProductionSkillLookup;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * ★★★ <b>production 切片的完整状态树</b>（D-031 P1 起含生产定义；<b>D-032 起成为"生产"的唯一权威</b>； namespace {@code
 * "production"}）。
 *
 * <p>★★ <b>D-032 的状态归属（本类就是落形；边界裁决 §1）</b>：
 *
 * <ul>
 *   <li>{@code industries}：<b>方案定义</b>（投入/产出/周期/资产需求/劳动/铸币/制度条件/引用的经验条与公式/ 可用性条件）——唯一权威；economy
 *       不得另存一份可独立修改的副本；
 *   <li>{@code modes}：生产方式（制度引用）；
 *   <li>{@code instances}：★ <b>生产实例</b>（方案稳定 ID 引用、经营者引用、地点、进度、周期实际劳动与实际投入 累计）——<b>本批从 {@code
 *       EconomyData.units} 迁出</b>；
 *   <li>{@code experienceTracks}：<b>经验条定义</b>（上限/增长换算/衰减规则/熟练度解释公式）；
 *   <li>{@code householdExperience}：<b>家户经验账</b>（{@code (家户, 经验条) → 经验特征 = 主值 + 精度余数}； ★ A 批按复核裁决
 *       §4 把值从裸 {@code long} 升级为 {@link ProductionExperienceFeature}： "足够精度累计 +
 *       结转余数"必须有地方放，且迁移/合户要能整条携带）；★★ A′ 批（§13.8 末段）： 增长换算按<b>家户 × 经验条</b>归并后一次做（{@code
 *       ProductionRuntimeEngine.execute}）， 该键上的余数就是"结转"的落点 —— 家户经验账因此是<b>经验侧的结转状态</b>（不是每实例一份）；
 *   <li>{@code unlocks}：<b>解锁集合</b>（家户 → 已解锁方案；<b>永久记忆</b>，见下）；
 *   <li>{@code efficiency}：<b>效率精度余数</b>（按新公式重建；★ A′ 批起三段 = 劳动通道两段 + 效率倍率乘算一段，见 {@link
 *       ProductionEfficiencyState}）——本批从 {@code EconomyData.productionEfficiency} 迁出；
 *   <li>{@code outputQuantityOverrides}：<b>GM 产出数值覆盖</b>——本批从 {@code
 *       EconomyData.outputQuantityOverrides} 迁出。
 * </ul>
 *
 * <p>★★ <b>旧注释的口径已按新裁决改写（原文"运行实例（unit/劳动/账户/债务/进度）永不搬入"作废）</b>： D-032 边界裁决 §1 明确「生产实例 {@code
 * ProductionProcess} → production：<b>从 {@code EconomyData.units}
 * 迁出</b>。实例身份、方案引用、经营者引用、地点、生产进度、周期实际劳动与实际投入累计在此维护」。 那条旧注释是 P1 时期"只迁定义不迁实例"的中间口径，**不再是本模块的边界**。
 *
 * <p>★★ <b>仍然不搬入的（边界未变）</b>：实际钱粮库存与货币账户（<b>actor</b>）、家户身份与人口
 * （<b>Social</b>）、市场/价格/需求/债务/工资/租金/发行政策、{@code relations}/{@code operatorConditions}/ {@code
 * allocations}/{@code assetShares}/{@code productionOrganizations}（<b>economy</b>）。
 *
 * <p>★★ <b>解锁是永久记忆（I7；用户 U10「肯定算数」）</b>：{@code unlocks} 只被"授予"写、<b>不被衰减撤销</b>。
 * 经验衰减只降熟练度/加成；可用性的另一侧（资产/劳动/属性条件）仍受可行性约束。
 *
 * <p>★ <b>缺键（null）⇒ 空表</b>，不抛（本模块统一的 JSON 缺键口径；不是旧档迁移——本 namespace 没有旧档）。
 *
 * <p>★ <b>保序不可变</b>：所有表都 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap}
 * 冻结，<b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立。
 *
 * <p>★ <b>读口</b>：本类型直接实现 {@link ProductionDefinitionLookup} 与 {@link ProductionSkillLookup}
 * （状态是定义的权威读模型）。技能以 {@link IndustryDefinitionView#skill()} 为唯一权威输入，不另存一张竞争技能表。
 *
 * <p>★ <b>科技点面已按 D1 删除</b>（用户 2026-10-17「D1 直接删」）：{@code technologies}/{@code masteries} 两个组件与
 * {@code TechnologyLookup} 实现<b>不再存在</b>——科技以"生产方式的可选升级方案"体现（U1）。
 */
public record ProductionData(
    Map<IndustryId, IndustryDefinitionView> industries,
    Map<ProductionModeId, ProductionModeDefinitionView> modes,
    Map<ProductionUnitId, ProductionProcess> instances,
    Map<ExperienceTrackId, ExperienceTrack> experienceTracks,
    Map<HouseholdExperienceKey, ProductionExperienceFeature> householdExperience,
    Map<HouseholdId, Set<IndustryId>> unlocks,
    Map<ProductionUnitId, ProductionEfficiencyState> efficiency,
    Map<IndustryId, Map<CommodityId, Long>> outputQuantityOverrides)
    implements ProductionDefinitionLookup, ProductionSkillLookup {

  public ProductionData {
    // ★★ 各段逐字展开、不抽 helper（照 GovState 的冻结纪律）：EI_EXPOSE_REP 只认它看得见的包装。
    Map<IndustryId, IndustryDefinitionView> industriesCopy = new LinkedHashMap<>();
    if (industries != null) {
      for (Map.Entry<IndustryId, IndustryDefinitionView> entry : industries.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("industries 的键与值都不得为 null: " + entry.getKey());
        }
        if (!entry.getKey().equals(entry.getValue().id())) {
          throw new IllegalArgumentException(
              "industries 的键必须与 IndustryDefinitionView.id 一致：键="
                  + entry.getKey()
                  + "，值内 id="
                  + entry.getValue().id());
        }
        industriesCopy.put(entry.getKey(), entry.getValue());
      }
    }
    industries = Collections.unmodifiableMap(industriesCopy);

    Map<ProductionModeId, ProductionModeDefinitionView> modesCopy = new LinkedHashMap<>();
    if (modes != null) {
      for (Map.Entry<ProductionModeId, ProductionModeDefinitionView> entry : modes.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("modes 的键与值都不得为 null: " + entry.getKey());
        }
        if (!entry.getKey().equals(entry.getValue().id())) {
          throw new IllegalArgumentException(
              "modes 的键必须与 ProductionModeDefinitionView.id 一致：键="
                  + entry.getKey()
                  + "，值内 id="
                  + entry.getValue().id());
        }
        modesCopy.put(entry.getKey(), entry.getValue());
      }
    }
    modes = Collections.unmodifiableMap(modesCopy);

    Map<ProductionUnitId, ProductionProcess> instancesCopy = new LinkedHashMap<>();
    if (instances != null) {
      for (Map.Entry<ProductionUnitId, ProductionProcess> entry : instances.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("instances 的键与值都不得为 null: " + entry.getKey());
        }
        // ★ 键 == 值内 id（同 industries/modes 的口径）：id 是身份，表键只是索引。
        if (!entry.getKey().equals(entry.getValue().id())) {
          throw new IllegalArgumentException(
              "instances 的键必须与 ProductionProcess.id 一致：键="
                  + entry.getKey()
                  + "，行内 id="
                  + entry.getValue().id());
        }
        instancesCopy.put(entry.getKey(), entry.getValue());
      }
    }
    instances = Collections.unmodifiableMap(instancesCopy);

    Map<ExperienceTrackId, ExperienceTrack> tracksCopy = new LinkedHashMap<>();
    if (experienceTracks != null) {
      for (Map.Entry<ExperienceTrackId, ExperienceTrack> entry : experienceTracks.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("experienceTracks 的键与值都不得为 null: " + entry.getKey());
        }
        if (!entry.getKey().equals(entry.getValue().id())) {
          throw new IllegalArgumentException(
              "experienceTracks 的键必须与 ExperienceTrack.id 一致：键="
                  + entry.getKey()
                  + "，值内 id="
                  + entry.getValue().id());
        }
        tracksCopy.put(entry.getKey(), entry.getValue());
      }
    }
    experienceTracks = Collections.unmodifiableMap(tracksCopy);

    Map<HouseholdExperienceKey, ProductionExperienceFeature> experienceCopy = new LinkedHashMap<>();
    if (householdExperience != null) {
      for (Map.Entry<HouseholdExperienceKey, ProductionExperienceFeature> entry :
          householdExperience.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "householdExperience 的键与值都不得为 null: " + entry.getKey());
        }
        // ★ 逐值越域由 ProductionExperienceFeature 的构造期守（主值 ≥ 0、余数 ∈ [0,1000)）；
        //   这里只保证"整条特征"非 null（"只搬主值、扔掉余数"正是复核裁决 §4 第 3 条明禁的）。
        experienceCopy.put(entry.getKey(), entry.getValue());
      }
    }
    householdExperience = Collections.unmodifiableMap(experienceCopy);

    Map<HouseholdId, Set<IndustryId>> unlocksCopy = new LinkedHashMap<>();
    if (unlocks != null) {
      for (Map.Entry<HouseholdId, Set<IndustryId>> entry : unlocks.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("unlocks 的键与值都不得为 null: " + entry.getKey());
        }
        Set<IndustryId> line = new LinkedHashSet<>();
        for (IndustryId unlocked : entry.getValue()) {
          if (unlocked == null) {
            throw new IllegalArgumentException("unlocks 内层集合不得含 null: " + entry.getKey());
          }
          line.add(unlocked);
        }
        unlocksCopy.put(entry.getKey(), Collections.unmodifiableSet(line));
      }
    }
    unlocks = Collections.unmodifiableMap(unlocksCopy);

    Map<ProductionUnitId, ProductionEfficiencyState> efficiencyCopy = new LinkedHashMap<>();
    if (efficiency != null) {
      for (Map.Entry<ProductionUnitId, ProductionEfficiencyState> entry : efficiency.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("efficiency 的键与值都不得为 null: " + entry.getKey());
        }
        efficiencyCopy.put(entry.getKey(), entry.getValue());
      }
    }
    efficiency = Collections.unmodifiableMap(efficiencyCopy);

    Map<IndustryId, Map<CommodityId, Long>> overridesCopy = new LinkedHashMap<>();
    if (outputQuantityOverrides != null) {
      for (Map.Entry<IndustryId, Map<CommodityId, Long>> entry :
          outputQuantityOverrides.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException(
              "outputQuantityOverrides 的键与值都不得为 null: " + entry.getKey());
        }
        Map<CommodityId, Long> line = new LinkedHashMap<>();
        for (Map.Entry<CommodityId, Long> value : entry.getValue().entrySet()) {
          if (value.getKey() == null || value.getValue() == null) {
            throw new IllegalArgumentException(
                "outputQuantityOverrides 内层表的键与值都不得为 null: " + entry.getKey());
          }
          if (value.getValue() < 0L) {
            throw new IllegalArgumentException(
                "outputQuantityOverrides 不得为负：" + entry.getKey() + "/" + value.getKey());
          }
          line.put(value.getKey(), value.getValue());
        }
        overridesCopy.put(entry.getKey(), Collections.unmodifiableMap(line));
      }
    }
    outputQuantityOverrides = Collections.unmodifiableMap(overridesCopy);
  }

  /** 空状态：没有任何方案/生产方式/实例/经验条/经验/解锁/效率/覆盖。 */
  public static ProductionData empty() {
    return new ProductionData(
        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
  }

  // ── 方案定义（industries）─────────────────────────────────────────────────────────────

  /** 仅替换 {@code industries}，保留其余组件（拷贝纪律）。 */
  public ProductionData withIndustries(Map<IndustryId, IndustryDefinitionView> value) {
    return new ProductionData(
        value,
        modes,
        instances,
        experienceTracks,
        householdExperience,
        unlocks,
        efficiency,
        outputQuantityOverrides);
  }

  /** 登记/替换一条方案定义（键从 {@code definition.id()} 派生）；保留其余组件。 */
  public ProductionData withIndustry(IndustryDefinitionView definition) {
    Objects.requireNonNull(definition, "definition");
    Map<IndustryId, IndustryDefinitionView> next = new LinkedHashMap<>(industries);
    next.put(definition.id(), definition);
    return withIndustries(next);
  }

  /** 仅替换 {@code modes}。 */
  public ProductionData withModes(Map<ProductionModeId, ProductionModeDefinitionView> value) {
    return new ProductionData(
        industries,
        value,
        instances,
        experienceTracks,
        householdExperience,
        unlocks,
        efficiency,
        outputQuantityOverrides);
  }

  /** 登记/替换一条生产方式定义（键从 {@code definition.id()} 派生）；保留其余组件。 */
  public ProductionData withMode(ProductionModeDefinitionView definition) {
    Objects.requireNonNull(definition, "definition");
    Map<ProductionModeId, ProductionModeDefinitionView> next = new LinkedHashMap<>(modes);
    next.put(definition.id(), definition);
    return withModes(next);
  }

  // ── 生产实例（instances）──────────────────────────────────────────────────────────────

  /** 仅替换 {@code instances}。 */
  public ProductionData withInstances(Map<ProductionUnitId, ProductionProcess> value) {
    return new ProductionData(
        industries,
        modes,
        value,
        experienceTracks,
        householdExperience,
        unlocks,
        efficiency,
        outputQuantityOverrides);
  }

  /** 登记/替换一个生产实例（键从 {@code instance.id()} 派生）；保留其余组件。 */
  public ProductionData withInstance(ProductionProcess instance) {
    Objects.requireNonNull(instance, "instance");
    Map<ProductionUnitId, ProductionProcess> next = new LinkedHashMap<>(instances);
    next.put(instance.id(), instance);
    return withInstances(next);
  }

  /** 移除一个生产实例（及其效率余数行 —— 实例没了，余数没有主）；保留其余组件。 */
  public ProductionData withoutInstance(ProductionUnitId unit) {
    Objects.requireNonNull(unit, "unit");
    Map<ProductionUnitId, ProductionProcess> nextInstances = new LinkedHashMap<>(instances);
    nextInstances.remove(unit);
    Map<ProductionUnitId, ProductionEfficiencyState> nextEfficiency =
        new LinkedHashMap<>(efficiency);
    nextEfficiency.remove(unit);
    return new ProductionData(
        industries,
        modes,
        nextInstances,
        experienceTracks,
        householdExperience,
        unlocks,
        nextEfficiency,
        outputQuantityOverrides);
  }

  /** 该实例的当刻状态；不存在 ⇒ empty。 */
  public Optional<ProductionProcess> instance(ProductionUnitId id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(instances.get(id));
  }

  // ── 经验条定义与家户经验账 ────────────────────────────────────────────────────────────

  /** 仅替换 {@code experienceTracks}。 */
  public ProductionData withExperienceTracks(Map<ExperienceTrackId, ExperienceTrack> value) {
    return new ProductionData(
        industries,
        modes,
        instances,
        value,
        householdExperience,
        unlocks,
        efficiency,
        outputQuantityOverrides);
  }

  /** 登记/替换一条经验条定义（键从 {@code track.id()} 派生）；保留其余组件。 */
  public ProductionData withExperienceTrack(ExperienceTrack track) {
    Objects.requireNonNull(track, "track");
    Map<ExperienceTrackId, ExperienceTrack> next = new LinkedHashMap<>(experienceTracks);
    next.put(track.id(), track);
    return withExperienceTracks(next);
  }

  /** 仅替换 {@code householdExperience}。 */
  public ProductionData withHouseholdExperience(
      Map<HouseholdExperienceKey, ProductionExperienceFeature> value) {
    return new ProductionData(
        industries,
        modes,
        instances,
        experienceTracks,
        value,
        unlocks,
        efficiency,
        outputQuantityOverrides);
  }

  /**
   * ★ <b>写入一条家户经验特征</b>（键从 {@code household} + {@code track} 派生）；保留其余组件。
   *
   * <p>★ A 批起写的是<b>整条特征</b>（主值 + 余数）——只写主值就是把余数扔掉（复核裁决 §4 第 3 条）。
   */
  public ProductionData withHouseholdExperience(
      HouseholdId household, ExperienceTrackId track, ProductionExperienceFeature feature) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(track, "track");
    Objects.requireNonNull(feature, "feature");
    Map<HouseholdExperienceKey, ProductionExperienceFeature> next =
        new LinkedHashMap<>(householdExperience);
    next.put(new HouseholdExperienceKey(household, track), feature);
    return withHouseholdExperience(next);
  }

  /** 移除一条家户经验（全零特征不进表 ⇒ 与"没有这门经验"逐值等价）。 */
  public ProductionData withoutHouseholdExperience(HouseholdId household, ExperienceTrackId track) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(track, "track");
    Map<HouseholdExperienceKey, ProductionExperienceFeature> next =
        new LinkedHashMap<>(householdExperience);
    next.remove(new HouseholdExperienceKey(household, track));
    return withHouseholdExperience(next);
  }

  /**
   * ★★★ <b>D-032 B3（I10 / §13.13.7 第 6 条）：把 {@code from} 户的<b>完整经验特征</b>按人口加权平均并入 {@code to} 户</b>
   * （返回新状态；新增状态仍由 production 唯一维护 —— 本方法只改既有的 {@code householdExperience} / {@code unlocks}
   * 两个组件，ChangeSet/codec/往返面自动带上）。
   *
   * <p>★★ <b>口径（逐条对应 I10 与 V6 的用户例式；R4 起按"并集"）</b>：
   *
   * <ol>
   *   <li><b>枚举并集</b>（R4 / §15.4）：经验条集合 = <b>来源 ∪ 目标</b>，按 {@link ExperienceTrackId#value()}
   *       排序（确定性、与表迭代序无关）；★ <b>来源缺行与目标缺行都按 0 参与平均</b>——"缺行 = 还没这门经验"是真实世界状态（见 {@link
   *       #experienceFeatureOf} 类注），它必须稀释平均值；把缺行当成"不参与"正是 R4 的缺陷（零经验迁入者不摊薄）；
   *   <li><b>完整携带、不乘比例</b>：搬的是 {@code from} 户的<b>整条特征</b>（主值 + 余数）—— 不按 {@code movedPeople /
   *       源户人口}打折（"只搬主值"或"按比例缩"都会把余数/知识丢掉，复核裁决 §4 第 3 条明禁）；
   *   <li><b>合户按人口加权平均</b>：{@code mergedExact = ⌊(exact_from × movedPeople + exact_to ×
   *       targetPeople) ÷ (movedPeople + targetPeople)⌋}，其中 {@code exact = value × 1000 +
   *       remainder}（千分之一经验，定点精确）； 再拆回 {@code value = ⌊exact ÷ 1000⌋} / {@code remainder = exact
   *       mod 1000} ⇒ <b>余数一起参与平均</b>， 不先截断再加；结果全零 ⇒ 从 {@code to} 删行（"没有这门经验"的规范形态）；
   *   <li><b>解锁是永久记忆</b>（I7）：{@code from} 的解锁<b>并集</b>进 {@code to}（只加不减，本类没有撤销解锁的方法）；
   *   <li><b>源户不减</b>：本方法<b>不</b>删 {@code from} 的行 —— I7 明确"衰减/迁移不撤销已解锁资格"，而"整户迁空后删空户行"
   *       是**人口**的事（由上层在人口清零后处理），不是经验账的事。
   * </ol>
   *
   * <p>★ <b>用户例式自证</b>（V6 的原话例）：来源经验 60、迁出 10 人；目标经验 20、原有 30 人 ⇒ {@code (60×1000×10 + 20×1000×30)
   * ÷ 40 = 30000} ⇒ {@code value = 30}、{@code remainder = 0} ⇒ <b>30</b> ✔。
   *
   * <p>★ <b>R4 的反例自证</b>（§15.4 事实）：目标经验 100、原有 10 人；来源迁入 10 人、<b>没有</b>该经验条（缺行 = 0）⇒ {@code ⌊(0×10
   * + 100×1000×10) ÷ 20⌋ = 50000} ⇒ <b>50</b>（旧写法"只枚举来源已有的条 + 全零跳过"会停在 100）。
   *
   * @param from 迁出户；不得为 null
   * @param movedPeople 随迁人口（> 0；本方法是纯函数，人口真值在 social）
   * @param to 迁入户；不得为 null，且不得等于 {@code from}
   * @param targetPeople 迁入户在合并后的<b>原有</b>人口（≥ 0；0 = 空户，此时结果就是"完整携带"的等比退化）
   */
  public ProductionData mergeExperienceInto(
      HouseholdId from, long movedPeople, HouseholdId to, long targetPeople) {
    Objects.requireNonNull(from, "from");
    Objects.requireNonNull(to, "to");
    if (from.equals(to)) {
      throw new IllegalArgumentException("mergeExperienceInto 的 from 与 to 不得相同: " + from.value());
    }
    if (movedPeople <= 0L) {
      throw new IllegalArgumentException(
          "mergeExperienceInto 的 movedPeople 必须 > 0: " + movedPeople);
    }
    if (targetPeople < 0L) {
      throw new IllegalArgumentException(
          "mergeExperienceInto 的 targetPeople 不得为负: " + targetPeople);
    }
    long denominator = Math.addExact(movedPeople, targetPeople);
    ProductionData next = this;
    // ── 逐经验条（来源 ∪ 目标，按 value() 排序）：缺项按 0 参与人口加权平均（定点精确，余数一起参与）──
    java.util.SortedSet<ExperienceTrackId> tracks =
        new java.util.TreeSet<>(java.util.Comparator.comparing(ExperienceTrackId::value));
    for (HouseholdExperienceKey key : householdExperience.keySet()) {
      if (key.household().equals(from) || key.household().equals(to)) {
        tracks.add(key.track());
      }
    }
    for (ExperienceTrackId track : tracks) {
      // ★★ R4（§15.4）：缺行 = ZERO（"还没这门经验"）⇒ 显式全零行与缺行都按 0 参与平均。
      //   ★ 旧写法的 `sourceFeature.allZero() ⇒ continue` 已删：它让"目标独有条"不被稀释（R4 的复现）。
      ProductionExperienceFeature sourceFeature = next.experienceFeatureOf(from, track);
      ProductionExperienceFeature targetFeature = next.experienceFeatureOf(to, track);
      long exactSource =
          Math.addExact(
              Math.multiplyExact(
                  sourceFeature.value(), ProductionExperienceFeature.REMAINDER_MODULUS),
              sourceFeature.remainder());
      long exactTarget =
          Math.addExact(
              Math.multiplyExact(
                  targetFeature.value(), ProductionExperienceFeature.REMAINDER_MODULUS),
              targetFeature.remainder());
      long numerator =
          Math.addExact(
              Math.multiplyExact(exactSource, movedPeople),
              Math.multiplyExact(exactTarget, targetPeople));
      long mergedExact = Math.floorDiv(numerator, denominator);
      ProductionExperienceFeature merged =
          new ProductionExperienceFeature(
              Math.floorDiv(mergedExact, ProductionExperienceFeature.REMAINDER_MODULUS),
              Math.floorMod(mergedExact, ProductionExperienceFeature.REMAINDER_MODULUS));
      next =
          merged.allZero()
              ? next.withoutHouseholdExperience(to, track)
              : next.withHouseholdExperience(to, track, merged);
    }
    // ── 解锁：并集（只加不减，I7）────────────────────────────────────────────────────────
    for (IndustryId plan : unlocksOf(from)) {
      next = next.withUnlock(to, plan);
    }
    return next;
  }

  /**
   * ★★ <b>该家户在该经验条上的完整经验特征</b>（主值 + 精度余数）；缺 ⇒ {@link ProductionExperienceFeature#ZERO}（"还没这门经验"=
   * 正常世界状态，不是错误）。
   *
   * <p>★ 迁移/合户/对账必须读这个口（而不是只看主值）：余数是完整经验特征的一半。
   */
  public ProductionExperienceFeature experienceFeatureOf(
      HouseholdId household, ExperienceTrackId track) {
    if (household == null || track == null) {
      return ProductionExperienceFeature.ZERO;
    }
    return householdExperience.getOrDefault(
        new HouseholdExperienceKey(household, track), ProductionExperienceFeature.ZERO);
  }

  /** 该家户在该经验条上的经验<b>主值</b>；缺 ⇒ {@code 0}（等价于特征全零）。 */
  public long experienceOf(HouseholdId household, ExperienceTrackId track) {
    return experienceFeatureOf(household, track).value();
  }

  /** 该家户在该经验条上的经验<b>精度余数</b>（千分之一经验）；缺 ⇒ {@code 0}。 */
  public long experienceRemainderOf(HouseholdId household, ExperienceTrackId track) {
    return experienceFeatureOf(household, track).remainder();
  }

  /**
   * 该家户在该经验条上的<b>熟练度</b>（千分制）：经验条定义缺失 ⇒ {@code 0}； ★ 公式求值失败 ⇒ 由 {@link ExperienceTrack#masteryOf}
   * 抛契约故障（不降级）。
   */
  public long masteryOf(HouseholdId household, ExperienceTrackId track) {
    if (track == null) {
      return 0L;
    }
    ExperienceTrack definition = experienceTracks.get(track);
    if (definition == null) {
      return 0L;
    }
    return definition.masteryOf(experienceOf(household, track));
  }

  /**
   * ★★ <b>该家户在该经验条上的读模型</b>（主值 + 余数 + 熟练度三件同源）——契约读口的返回形状。
   *
   * <p>★ 三件一起给的理由：迁移/合户要整条特征、对账要核余数、公式要熟练度；分三次读会让"读的是不是 同一刻"变成调用方的责任。
   */
  public ExperienceState experienceStateOf(HouseholdId household, ExperienceTrackId track) {
    if (track == null) {
      return ExperienceState.EMPTY;
    }
    return new ExperienceState(experienceFeatureOf(household, track), masteryOf(household, track));
  }

  /** 该经验条定义；不存在 ⇒ empty。 */
  public Optional<ExperienceTrack> experienceTrack(ExperienceTrackId id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(experienceTracks.get(id));
  }

  // ── 解锁集合（永久记忆）─────────────────────────────────────────────────────────────

  /** 仅替换 {@code unlocks}。 */
  public ProductionData withUnlocks(Map<HouseholdId, Set<IndustryId>> value) {
    return new ProductionData(
        industries,
        modes,
        instances,
        experienceTracks,
        householdExperience,
        value,
        efficiency,
        outputQuantityOverrides);
  }

  /**
   * ★★ <b>授予一条解锁</b>（只加不减：I7 的"永久记忆"在这里落形——本模块<b>没有</b>撤销解锁的方法）。
   *
   * <p>★ 若该家户已解锁同一方案 ⇒ 返回 {@code this}（幂等，不产生假变更集行）。
   */
  public ProductionData withUnlock(HouseholdId household, IndustryId plan) {
    Objects.requireNonNull(household, "household");
    Objects.requireNonNull(plan, "plan");
    Set<IndustryId> current = unlocks.getOrDefault(household, Set.of());
    if (current.contains(plan)) {
      return this;
    }
    Map<HouseholdId, Set<IndustryId>> next = new LinkedHashMap<>(unlocks);
    Set<IndustryId> line = new LinkedHashSet<>(current);
    line.add(plan);
    next.put(household, line);
    return withUnlocks(next);
  }

  /** 该家户已解锁的方案集合（不可变视图；没有 ⇒ 空集）。 */
  public Set<IndustryId> unlocksOf(HouseholdId household) {
    if (household == null) {
      return Set.of();
    }
    return unlocks.getOrDefault(household, Set.of());
  }

  // ── 效率累计与精度余数 ───────────────────────────────────────────────────────────────

  /** 仅替换 {@code efficiency}。 */
  public ProductionData withEfficiency(Map<ProductionUnitId, ProductionEfficiencyState> value) {
    return new ProductionData(
        industries,
        modes,
        instances,
        experienceTracks,
        householdExperience,
        unlocks,
        value,
        outputQuantityOverrides);
  }

  /** 写入一个实例的效率余数行；{@code state == null} ⇒ 移除该行（缺行与全零行逐值等价）。 */
  public ProductionData withEfficiencyState(
      ProductionUnitId unit, ProductionEfficiencyState state) {
    Objects.requireNonNull(unit, "unit");
    Map<ProductionUnitId, ProductionEfficiencyState> next = new LinkedHashMap<>(efficiency);
    if (state == null || state.allZero()) {
      next.remove(unit);
    } else {
      next.put(unit, state);
    }
    return withEfficiency(next);
  }

  /** 该实例的效率余数行；缺行 ⇒ empty（语义 = 全 0 余数）。 */
  public Optional<ProductionEfficiencyState> efficiencyOf(ProductionUnitId unit) {
    Objects.requireNonNull(unit, "unit");
    return Optional.ofNullable(efficiency.get(unit));
  }

  // ── GM 产出数值覆盖 ─────────────────────────────────────────────────────────────────

  /** 仅替换 {@code outputQuantityOverrides}。 */
  public ProductionData withOutputQuantityOverrides(Map<IndustryId, Map<CommodityId, Long>> value) {
    return new ProductionData(
        industries,
        modes,
        instances,
        experienceTracks,
        householdExperience,
        unlocks,
        efficiency,
        value);
  }

  /** 写入一条（方案, 商品）产出数量覆盖；保留其余组件。 */
  public ProductionData withOutputQuantityOverride(
      IndustryId industry, CommodityId commodity, long quantity) {
    Objects.requireNonNull(industry, "industry");
    Objects.requireNonNull(commodity, "commodity");
    Map<IndustryId, Map<CommodityId, Long>> next = new LinkedHashMap<>(outputQuantityOverrides);
    Map<CommodityId, Long> line = new LinkedHashMap<>(next.getOrDefault(industry, Map.of()));
    line.put(commodity, quantity);
    next.put(industry, line);
    return withOutputQuantityOverrides(next);
  }

  /** 该方案的产出覆盖表；没配 ⇒ 空表（= 用配方默认值）。 */
  public Map<CommodityId, Long> outputQuantityOverrideOf(IndustryId industry) {
    if (industry == null) {
      return Map.of();
    }
    return outputQuantityOverrides.getOrDefault(industry, Map.of());
  }

  // ── 定义/技能读口（ProductionDefinitionLookup / ProductionSkillLookup）──────────────

  @Override
  public Optional<IndustryDefinitionView> industry(IndustryId id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(industries.get(id));
  }

  @Override
  public Optional<ProductionModeDefinitionView> mode(ProductionModeId id) {
    Objects.requireNonNull(id, "id");
    return Optional.ofNullable(modes.get(id));
  }

  @Override
  public Optional<ProductionRecipe> recipeOf(IndustryId id) {
    return industry(id).map(IndustryDefinitionView::recipe);
  }

  @Override
  public Map<IndustryId, IndustryDefinitionView> industries() {
    return industries;
  }

  @Override
  public Map<ProductionModeId, ProductionModeDefinitionView> modes() {
    return modes;
  }

  /** 技能键以方案定义里的 {@code skill} 为唯一权威输入；方案不存在或未绑 ⇒ empty（不解析 id 猜种类）。 */
  @Override
  public Optional<ProductionSkillKey> keyOf(IndustryId id) {
    Objects.requireNonNull(id, "id");
    IndustryDefinitionView definition = industries.get(id);
    return definition == null ? Optional.empty() : definition.skill();
  }

  /** ★ 供日志/读数：本状态的组件规模（不构成状态，不入变更集）。 */
  public List<String> componentSummary() {
    return List.of(
        "industries=" + industries.size(),
        "modes=" + modes.size(),
        "instances=" + instances.size(),
        "experienceTracks=" + experienceTracks.size(),
        "householdExperience=" + householdExperience.size(),
        "unlocks=" + unlocks.size(),
        "efficiency=" + efficiency.size(),
        "outputQuantityOverrides=" + outputQuantityOverrides.size());
  }
}
