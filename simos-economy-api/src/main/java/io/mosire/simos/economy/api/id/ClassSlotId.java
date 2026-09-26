package io.mosire.simos.economy.api.id;

/**
 * 阶层槽位 ID（新经济设计 §3.1）：一个产业内**同一制度允许的一个阶层槽位**（贫农/中农/地主…）的稳定身份。
 *
 * <p>{@code ClassKey = (IndustryId, ClassSlotId)} 组合出阶层行的身份（§3.2）；槽位是"制度允许哪些阶层"的清单， 不是人的永久属性（§5
 * 末条：改参数 = 改 {@code rulesVersion}）。裸值 {@code toString()} + {@code static parse} 三件套（铁律 1）。
 *
 * <p>★★ **【2026-09-26 · S1 阶段 1】本类型将被 {@link SocialClassId} 取代**：槽位是"**一个产业内**该制度允许的一个 角色"，而 S1
 * spec §2.6 要的是**产业无关**的人口身份。四模板**当前**恰好同构（靠共用一对全局数组）， 但那是**填充习惯、不是类型约束** ——
 * 换装后"谁都可以往槽位里填别的值"在类型上被堵死。 **本类型在 S1 阶段 1 的换装那一步删除**（调查已确认：73 处用法全是经济侧自我引用，无第三方）。
 */
public record ClassSlotId(String value) {

  public ClassSlotId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ClassSlotId 不得为空白");
    }
  }

  @Override
  public String toString() {
    return value;
  }

  /** 只校验非空白，不做格式约束（分配器属命令层）。 */
  public static ClassSlotId parse(String text) {
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("ClassSlotId 不得为空白: " + text);
    }
    return new ClassSlotId(text);
  }
}
