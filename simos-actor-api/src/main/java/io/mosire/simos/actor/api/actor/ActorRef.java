package io.mosire.simos.actor.api.actor;

/**
 * 经济主体引用（设计稿 §2）：**种类 + 稳定 id** 两件，跨模块引用任何经济主体而不依赖它所在的切片。
 *
 * <p>★ 为什么是 {@code (ActorKind, String)} 而不是 sealed 的多例（每个引用一个领域 ID record）：那会把共用契约层
 * 绑到各领域模块的内部类型上。设计稿 §2 明确 api 只依赖 util/map，故这里只留不透明的稳定 id 串； 存在性与权限由 app 组合根校验（§10）。
 *
 * <p>构造期校验：{@code kind} 非空、{@code id} 非空白。
 *
 * <p>★★ <b>规范串与它的逆</b>（S1 阶段 2 修复轮）：{@link #toString()} 产出 {@code "<KIND>:<id>"}，{@link
 * #parseCanonical(String)} 是它的<b>逆</b>。★ 二者同住本文件、共用同一个 {@link #KIND_ID_SEPARATOR} —— 这正是本仓"裸值
 * {@code toString()} + {@code static parse}"三件套的形制（{@code SocialClassId} / {@code ClassKey}
 * 同款）：**格式的拼写与它的逆只许有一处**。{@code FieldDelta}（住在 {@code simos-util}，本模块 <b>不依赖</b>它，故此处只用
 * {@code @code} 点名而不 {@code @link}）的键就是靠这条配对工作的 —— 键由 {@code toString()} 产出、重建时用 {@code parse} 还原
 * —— 少了这条配对，每个把本类型用作状态表键的切片 都得自己写一份逆，于是持久化键格式的知识就有了第二处。
 *
 * <p>★ <b>{@code parseCanonical} 是新增 surface，不是改既有契约</b>：两参的 {@link #parse(String, String)}
 * <b>一字不动</b> —— 它<b>不是</b> {@code toString()} 的逆（{@code "UNIT:u-1"} 喂不回去）， 这个不对称是裁定 R4
 * 的<b>故意</b>设计，不许顺手"修好"（那会改读侧契约）。
 */
public record ActorRef(ActorKind kind, String id) {

  /**
   * 规范串里种类与 id 的分隔符 —— <b>只在 {@link #toString()} 与 {@link #parseCanonical(String)} 两处被读</b>
   * （同处一个文件，故"分隔符长什么样"全仓只有这一个拼写点）。
   */
  private static final String KIND_ID_SEPARATOR = ":";

  public ActorRef {
    if (kind == null) {
      throw new IllegalArgumentException("kind 不得为 null");
    }
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("id 不得为空白");
    }
  }

  @Override
  public String toString() {
    return kind + KIND_ID_SEPARATOR + id;
  }

  /** 按文本解析：{@code kindText} 必须是 {@link ActorKind} 词表内的值（词表外即抛并列出合法值），{@code id} 不得为空白。 */
  public static ActorRef parse(String kindText, String id) {
    return new ActorRef(ActorKind.parse(kindText), id);
  }

  /**
   * {@link #toString()} 的<b>逆</b>：{@code parseCanonical(ref.toString())} 恒等于 {@code ref}。
   *
   * <p>★★ <b>按第一个分隔符切</b>：{@code ActorKind} 的词表里一个分隔符都没有，而 {@code id} 里<b>可以</b>有 （例如人口批次的 {@code
   * "rural:0_0:MALE:1"}）⇒ 只有"首个分隔符"这一个切法能还原。
   *
   * <p>★ <b>宁抛不静默</b>（照 {@code ClassKey#parse} 的口径）：{@code null} / 空白 / 没有分隔符 / 分隔符在首尾， 一律 {@link
   * IllegalArgumentException} —— 静默造一个半截的身份，比当场炸难查得多。
   */
  public static ActorRef parseCanonical(String canonical) {
    if (canonical == null || canonical.isBlank()) {
      throw new IllegalArgumentException("非法 actor 规范串: " + canonical);
    }
    int i = canonical.indexOf(KIND_ID_SEPARATOR);
    if (i <= 0 || i == canonical.length() - KIND_ID_SEPARATOR.length()) {
      throw new IllegalArgumentException("非法 actor 规范串: " + canonical);
    }
    return new ActorRef(
        ActorKind.parse(canonical.substring(0, i)),
        canonical.substring(i + KIND_ID_SEPARATOR.length()));
  }
}
