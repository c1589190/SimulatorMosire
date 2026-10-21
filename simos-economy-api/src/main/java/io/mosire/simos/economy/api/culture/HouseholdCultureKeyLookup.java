package io.mosire.simos.economy.api.culture;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Optional;

/**
 * ★★ <b>文化 key 的跨模块只读口</b>（D-032 v4 §9；RC1 共享契约）。
 *
 * <p>economy 只依赖本接口与 {@link HouseholdCultureKey}，<b>不 import {@code simos-culture}</b>；culture 模块的
 * {@code CultureData} 实现本接口，由 app 在装配时把当前 culture 切片作为 lookup 传给 economy（接线在 RC4/RC6）。
 *
 * <p>★ <b>无查得 ≠ 错误</b>：missing household / missing culture ⇒ {@link Optional#empty()}，不伪造 key、不抛。
 */
public interface HouseholdCultureKeyLookup {

  /**
   * ★ RC4-A：没有 culture 切片/未装配真实读口时的<b>显式 unavailable 适配器</b>。
   *
   * <p>它永远返回 {@link Optional#empty()}，不伪造 key；调用方必须把 empty 映射为 {@link
   * HouseholdCultureKey#missing()}（并在需要时记 INFO），不得猜文化。
   */
  static HouseholdCultureKeyLookup unavailable() {
    return household -> Optional.empty();
  }

  /** 该家户的文化属性 key；未设置 ⇒ {@link Optional#empty()}。 */
  Optional<HouseholdCultureKey> cultureKeyOf(HouseholdId household);
}
