package io.mosire.simos.culture;

import io.mosire.simos.culture.contract.CultureLookup;
import io.mosire.simos.culture.contract.CultureTag;
import io.mosire.simos.culture.contract.CustomTag;
import io.mosire.simos.culture.contract.HouseholdCulture;
import io.mosire.simos.economy.api.culture.HouseholdCultureKey;
import io.mosire.simos.economy.api.culture.HouseholdCultureKeyLookup;
import io.mosire.simos.social.api.id.HouseholdId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ★★ <b>culture 切片的完整状态树</b>（D-031 spec §5/§6.2；namespace {@code "culture"}；★ D-032 起为<b>占位</b>）。
 *
 * <p>★★ <b>D-032（D2 直接删）：{@code aptitudeRules} 组件已删除</b>。用户口径：「文化模块和生产效率模块解耦，
 * 文化直接决定生产效率太不唯物主义了」（U5）、「D2 直接删，文化部分目前都没咋做，留着模块占位置就行」（U7）。 随之退役的有：{@code
 * ProductionAptitudeRule}、{@code ProductionAptitudeComposer}、{@code AptitudeBlend}、 两条 {@code
 * culture.UpsertAptitudeRule}/{@code RemoveAptitudeRule} 命令、economy-api 的 {@code
 * ProductionEfficiencyModifier} 与 economy 侧的接收链、app 的 {@code CultureAptitudeBridge} / {@code
 * ProductionAptitudeAssembly}。
 *
 * <p>★★ <b>保留的是什么</b>（U7「留着模块占位置就行」）：本模块本体、文化标签面 （{@link HouseholdId} → {@link HouseholdCulture}，写口
 * {@code culture.SetHouseholdCulture}）与契约包。 文化将来只提供<b>需求差异</b>与<b>外部属性</b>（如识字率，经 {@code
 * production.contract.ExternalAttributeFacts} 作为生产可用性条件的事实入参），<b>不再</b>直接决定生产效率倍率。
 *
 * <p>★ <b>缺键（null）⇒ 空表</b>，不抛（本模块统一的 JSON 缺键口径；<b>不是</b>旧档迁移——本 namespace 没有旧档）。
 *
 * <p>★ <b>保序不可变</b>：表 {@code LinkedHashMap} 拷贝 + 在赋值处 {@code Collections.unmodifiableMap} 冻结，
 * <b>绝不用 {@code Map.copyOf}</b>——它的迭代序不是内容的纯函数，字节级往返因此不成立。
 *
 * <p>★ <b>读口</b>：本类型直接实现 {@link CultureLookup} 与 economy-api 的 {@link HouseholdCultureKeyLookup}
 * （状态是文化标签的权威读模型）；{@code cultureOf(...)} / {@code cultureKeyOf(...)} 与 record 访问器不同签名， 不构成 Jackson
 * getter 冲突。economy 只认 economy-api 的 key/lookup，不 import culture。
 *
 * @param householdCultures 家户文化标签表（键 == 值内 household；保序不可变；缺键读成空表）
 */
public record CultureData(Map<HouseholdId, HouseholdCulture> householdCultures)
    implements CultureLookup, HouseholdCultureKeyLookup {

  public CultureData {
    // ★ 逐字展开、不抽 helper（照 GovState/ProductionData 的冻结纪律）：EI_EXPOSE_REP 只认它看得见的包装。
    Map<HouseholdId, HouseholdCulture> householdCulturesCopy = new LinkedHashMap<>();
    if (householdCultures != null) {
      for (Map.Entry<HouseholdId, HouseholdCulture> entry : householdCultures.entrySet()) {
        if (entry.getKey() == null || entry.getValue() == null) {
          throw new IllegalArgumentException("householdCultures 的键与值都不得为 null: " + entry.getKey());
        }
        if (!entry.getKey().equals(entry.getValue().household())) {
          throw new IllegalArgumentException(
              "householdCultures 的键必须与 HouseholdCulture.household 一致：键="
                  + entry.getKey()
                  + "，值内 household="
                  + entry.getValue().household());
        }
        householdCulturesCopy.put(entry.getKey(), entry.getValue());
      }
    }
    householdCultures = Collections.unmodifiableMap(householdCulturesCopy);
  }

  /** 本模块当前起点的空状态：还没有任何家户文化标签（★ D2 后不再有熟练度规则表）。 */
  public static CultureData empty() {
    return new CultureData(Map.of());
  }

  /** 仅替换 {@code householdCultures}（拷贝纪律）。 */
  public CultureData withHouseholdCultures(Map<HouseholdId, HouseholdCulture> value) {
    return new CultureData(value);
  }

  /** 设置/覆盖一条家户文化标签（键从 {@code household()} 派生，避免第二个拼写点）。 */
  public CultureData withHouseholdCulture(HouseholdCulture value) {
    Objects.requireNonNull(value, "value");
    Map<HouseholdId, HouseholdCulture> next = new LinkedHashMap<>(householdCultures);
    next.put(value.household(), value);
    return new CultureData(next);
  }

  @Override
  public Optional<HouseholdCulture> cultureOf(HouseholdId household) {
    Objects.requireNonNull(household, "household");
    return Optional.ofNullable(householdCultures.get(household));
  }

  /**
   * ★ RC1（D-032 v4 §9）：把 typed {@link HouseholdCulture} 映射成 economy-api 的不透明只读 key。
   *
   * <p>missing household / 未设置文化 ⇒ {@link Optional#empty()}（不伪造、不抛）。{@code customs} 的排序/去重/冻结由
   * {@link HouseholdCultureKey} 构造期唯一执行，这里只做 typed → 字符串的忠实搬运。
   */
  @Override
  public Optional<HouseholdCultureKey> cultureKeyOf(HouseholdId household) {
    Objects.requireNonNull(household, "household");
    HouseholdCulture culture = householdCultures.get(household);
    if (culture == null) {
      return Optional.empty();
    }
    CultureTag tag = culture.tag();
    List<String> customs = new ArrayList<>(tag.customs().size());
    for (CustomTag custom : tag.customs()) {
      customs.add(custom.value());
    }
    return Optional.of(
        new HouseholdCultureKey(
            tag.culture().value(), tag.religion().value(), customs, culture.label()));
  }
}
