package io.mosire.simos.app;

/**
 * ★ Z6a 测试专用访问桥：{@link ShellMain#seedGenesisIfEmpty(Shell)} 是包内可见（生产调用点只在同包）， 而 Z6 的创世夹具住在 {@code
 * app.testing}。这里只做一次转调，不复制任何判定逻辑——用例仍走真 {@code seedGenesisIfEmpty} ⇒ 真 {@code bootstrapGenesis}。
 */
public final class GovGenesisSeedAccess {

  private GovGenesisSeedAccess() {}

  public static boolean seed(Shell shell) {
    return ShellMain.seedGenesisIfEmpty(shell);
  }
}
