package dev.twme.editpreview;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.session.request.Request;
import com.sk89q.worldedit.world.World;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * 在 WorldEdit 的「請求」裡執行。
 * <p>
 * 遮罩與 pattern 解析時綁的是 RequestExtent：讀方塊時找目前請求的 EditSession，沒有就用請求的 world。
 * WorldEdit 7.4.6 起請求是 ScopedValue，指令之外沒有綁定就會丟 NoSuchElementException，
 * 要用 {@code Request.runWithRequest} 包起來；更早的版本與 FAWE 是 ThreadLocal，直接設定、用完 reset。
 */
final class WeRequest {
  private WeRequest() {}

  private static final Method RUN_WITH;

  static {
    Method m;
    try {
      m = Request.class.getMethod("runWithRequest", Runnable.class);
    } catch (NoSuchMethodException e) {
      m = null;
    }
    RUN_WITH = m;
  }

  /** 在綁好 world、session 的請求裡執行 body。 */
  @SuppressWarnings("unchecked")
  static <T> T with(World world, LocalSession session, Supplier<T> body) {
    Object[] out = new Object[1];
    RuntimeException[] err = new RuntimeException[1];
    Runnable run = () -> {
      Request r = Request.request();
      r.setWorld(world);
      r.setSession(session);
      try {
        out[0] = body.get();
      } catch (RuntimeException e) {
        err[0] = e;
      }
    };
    if (RUN_WITH != null) {
      try {
        RUN_WITH.invoke(null, run);
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    } else {
      try {
        run.run();
      } finally {
        Request.reset();
      }
    }
    if (err[0] != null) throw err[0];
    return (T) out[0];
  }
}
