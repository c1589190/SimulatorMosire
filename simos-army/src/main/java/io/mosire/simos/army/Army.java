package io.mosire.simos.army;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.mosire.simos.unit.UnitId;
import java.util.Optional;

/**
 * 军队身份：把 {@link ArmyId} 与一个**单位根**（{@code UnitId}）关联起来，并可选地记一个**认领的 GOV 单位**（{@code
 * masterGovUnitId}）。
 *
 * <p>★★ <b>用户口径（2026-10-12）</b>：「<b>这玩意是啥，单个Army单位的本质不是被Army模块打上自定义数据集的Unit吗</b>」 ——
 * 正是本类型的形状：**它就是一个 Unit（{@link #rootUnit()}）加上军队自己的那点数据**（{@link #name()} 与认领的 GOV）。故它随 {@code
 * armies} 表一起住 {@code simos-army}：军队身份是 army 模块自己的数据（铁律 3）。
 *
 * <p>★★ <b>2026-10-12 批次 3d-1：本类型自 {@code io.mosire.simos.sd.model} <b>整条搬进</b> {@code
 * simos-army}</b>（同 {@link ArmyId}）。record 组件、构造期判据、异常文案<b>逐字未改</b>，只换了包；{@code sd} 侧则把 {@code
 * Affiliation.Army} 收窄成不透明串（见该类型的类注）。
 *
 * <p>★★ <b>Army 不认识 Nation</b>（2026-10-01 用户裁定 6/8）：用户原话「Army 我从来没要求算战力，只是根据 GOV 的这个包对应语义，把 Nation
 * 一起拆除去而已」。国家实体（{@code Nation}）2026-10-12 批次 3c 起住 {@code simos-gov} （"外交是 gov 的事"）；军队只认 GOV
 * 编制，不把国家 id 硬映射成 GOV。
 *
 * <p>★ <b>编制本身仍在 unit</b>（铁律 3）：army 只存**归属关系**，不拥有编制树；{@code rootUnit} 与 {@code masterGovUnitId}
 * 的存在性由命令期经 {@code state.module("unit")} 只读（{@code army.CreateArmy} / {@code
 * army.SetArmyMasterGov}）。{@code masterGovUnitId} 是 {@link Optional} 且 Optional 本身非 null；缺省 = 未认主子。
 *
 * <p>★ <b>旧档兼容（旧 JSON 里的 {@code "nationId"} 键）</b>：类型上的 {@code @JsonIgnoreProperties("nationId")}
 * 只忽略这一个已退役键、**不关掉全局的 fail-on-unknown**，也不把 nationId 映射成 GOV。按 AGENTS §一.11（Alpha
 * 阶段不兼容旧档），这里保留它只是"不要让旧世界的字节把整个世界读崩"这一条最小容忍，不是迁移层。
 *
 * <p>★ <b>不算战力</b>：本类型不持有任何力量/战斗力字段；{@code simos-army} 一期也只做视野派生（裁定 6）。
 *
 * <p>★★ <b>2026-10-13 急停协议新增第 5 组件 {@code contactWatch}</b>（约束设计书 §4.4，口径 2 = (a)「具体条件交给对应模块」）：
 * <b>"GM 是否要求在两军相遇时报警"</b>的启用位住<b>军队自己的记录</b>里。判据与监控职能都在 Army（用户原话：「<b>我首先说的是两支军队相遇， 所以说监控职能甚至应该包给
 * Army 模块</b>」）⇒ 这个位必须与世界状态一起落 revision（重启后 GM 的注册不丢），由窄写工具 {@code simos.army.contactWatch}（命令
 * {@code army.SetArmyContactWatch}）设置。
 *
 * <p>★ <b>加组件配四参兼容构造器</b>（不变量 I7 同款纪律）：既有构造点（{@code army.CreateArmy} / {@code
 * army.SetArmyMasterGov} 与测试夹具）一位不用改，缺省 {@code false} = "没要求过"（与加组件前的行为逐字相同）。
 *
 * @param id 军队 id（非 null）
 * @param masterGovUnitId 认领的 GOV 单位（未认领用 {@code Optional.empty()}；Optional 本身非 null）
 * @param rootUnit 军队根单位（非 null）
 * @param name 展示名（非空白）
 * @param contactWatch ★ 是否启用"两军相遇即报警"（GM 注册位；{@code false} = 未启用 ⇒ 相遇不报、不停推进）
 */
@JsonIgnoreProperties({"nationId"})
public record Army(
    ArmyId id,
    Optional<UnitId> masterGovUnitId,
    UnitId rootUnit,
    String name,
    boolean contactWatch) {

  public Army {
    if (id == null) {
      throw new IllegalArgumentException("id 不得为 null");
    }
    if (masterGovUnitId == null) {
      throw new IllegalArgumentException("masterGovUnitId 不得为 null（未认主子用 Optional.empty()）");
    }
    if (rootUnit == null) {
      throw new IllegalArgumentException("rootUnit 不得为 null");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name 不得为空白");
    }
  }

  /**
   * ★ <b>四参兼容构造器</b>（2026-10-13 急停协议）：{@code contactWatch} 缺省 {@code false}。
   *
   * <p>它让"加了组件"对既有构造点**零影响**（{@code army.CreateArmy} 建出来的军队默认不受监控；要监控由 GM 显式设置）。 ★
   * 反过来，**改写既有记录的代码必须显式带上原值**（{@code army.SetArmyMasterGov} 就是那一处）——否则一次改主子就静默清掉 GM
   * 的注册，而这种丢失在日志里看不出来。
   */
  public Army(ArmyId id, Optional<UnitId> masterGovUnitId, UnitId rootUnit, String name) {
    this(id, masterGovUnitId, rootUnit, name, false);
  }
}
