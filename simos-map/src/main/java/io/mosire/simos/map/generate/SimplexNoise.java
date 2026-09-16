package io.mosire.simos.map.generate;

/**
 * 名字叫 Simplex、**实为 value noise** 的二维噪声：每个整数格点的梯度方向由"坐标 + 种子"的散列决定，格内做平滑插值。
 *
 * <p>★ **逐字移植自 GSimulator 的 {@code com.gsim.map.service.SimplexNoise}（53 行）**，一个字符都没换 ——
 * 生成器的契约是"同一份参数、同一张图"，而本类决定了图上每一个高度值，改动任何一行都会换一张图。名字与实现的错位也照抄（它没有真 Simplex/Perlin
 * 的梯度查表，改名的收益只有一个更好听的名字）。
 *
 * <p>★ **无状态**（种子在构造期定死，{@link #noise2} 是纯函数）：同一种子的两次调用必然同值，这是 L7 可复现的前提之一。
 *
 * <p>包私有：它是 {@link MapGenerator} 的实现细节，不是参数面的一部分（{@code GenerationSpec} 只持有频率/幅度， 不持有算法）。
 */
final class SimplexNoise {

  private final long seed;

  SimplexNoise(long seed) {
    this.seed = seed;
  }

  /** 单点采样，值域约 {@code [-1, 1]}（不是硬保证）。 */
  double noise2(double x, double y) {
    int xi = (int) Math.floor(x);
    int yi = (int) Math.floor(y);
    double xf = x - xi;
    double yf = y - yi;
    double n00 = dotGrid(xi, yi, xf, yf);
    double n10 = dotGrid(xi + 1, yi, xf - 1, yf);
    double n01 = dotGrid(xi, yi + 1, xf, yf - 1);
    double n11 = dotGrid(xi + 1, yi + 1, xf - 1, yf - 1);
    double u = smooth(xf);
    double v = smooth(yf);
    return lerp(lerp(n00, n10, u), lerp(n01, n11, u), v);
  }

  private double dotGrid(int ix, int iy, double dx, double dy) {
    long h = hash(ix, iy);
    double angle = (h & 0xFFFF) * (2.0 * Math.PI / 65536.0);
    return Math.cos(angle) * dx + Math.sin(angle) * dy;
  }

  private long hash(int x, int y) {
    long h = seed;
    h = h * 6364136223846793005L + x;
    h = h * 6364136223846793005L + y;
    h = (h ^ (h >>> 33)) * 0xFF51AFD7ED558CCDL;
    h = (h ^ (h >>> 33)) * 0xC4CEB9FE1A85EC53L;
    return h ^ (h >>> 33);
  }

  private static double smooth(double t) {
    return t * t * t * (t * (t * 6 - 15) + 10);
  }

  private static double lerp(double a, double b, double t) {
    return a + t * (b - a);
  }
}
