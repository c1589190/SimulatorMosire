package io.mosire.simos.culture.contract;

import io.mosire.simos.social.api.id.HouseholdId;
import java.util.Optional;

/**
 * ★★ <b>文化状态的跨模块只读口</b>（D-031 spec §5/§6.2；★ D-032 起只剩标签面）。
 *
 * <pre>
 * CultureLookup.cultureOf(HouseholdId)      → Optional&lt;HouseholdCulture&gt;
 * </pre>
 *
 * <p>★★ <b>D-032（D2 直接删）：{@code rulesFor(CultureId)} 已删除</b>——它返回的是"文化 × 技能 → 生产效率倍率"， 正是用户 U5
 * 要与生产解耦的那条通路（「文化直接决定生产效率太不唯物主义了」）。文化将来只提供需求差异与外部 属性（如识字率），后者经 {@code
 * production.contract.ExternalAttributeFacts} 作为<b>生产可用性条件的事实入参</b> 传入
 * production，文化身份本身不再决定任何生产效率倍率。
 *
 * <p>★ <b>接口与实现都在 {@code simos-culture}</b>：文化标签的唯一写者是文化命令面，读模型就是本模块的状态类型 （{@code CultureData
 * implements CultureLookup}）。消费方只认本接口，不依赖任何实现细节。
 *
 * <p>★ <b>无查得 ≠ 错误</b>：没给家户打过文化标签 ⇒ {@code cultureOf} 返回 empty（那是正常世界状态，不是缺数据）。
 *
 * <p>★ <b>只读、无副作用</b>：查询不产生任何跨域写；culture 不写人口、不碰 Social/economy 状态。
 */
public interface CultureLookup {

  /** 该家户的文化标签；未设置 ⇒ {@link Optional#empty()}。 */
  Optional<HouseholdCulture> cultureOf(HouseholdId household);
}
