package io.mosire.simos.actor.api.actor;

/**
 * 经济主体引用（设计稿 §2）：**种类 + 稳定 id** 两件，跨模块引用任何经济主体而不依赖它所在的切片。
 *
 * <p>★ 为什么是 {@code (ActorKind, String)} 而不是 sealed 的多例（每个引用一个领域 ID record）：那会把共用契约层
 * 绑到各领域模块的内部类型上。设计稿 §2 明确 api 只依赖 util/map，故这里只留不透明的稳定 id 串； 存在性与权限由 app 组合根校验（§10）。
 *
 * <p>构造期校验：{@code kind} 非空、{@code id} 非空白。
 */
public record ActorRef(ActorKind kind, String id) {

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
    return kind + ":" + id;
  }

  /** 按文本解析：{@code kindText} 必须是 {@link ActorKind} 词表内的值（词表外即抛并列出合法值），{@code id} 不得为空白。 */
  public static ActorRef parse(String kindText, String id) {
    return new ActorRef(ActorKind.parse(kindText), id);
  }
}
