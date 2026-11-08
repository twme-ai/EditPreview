package dev.twme.editpreview;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.session.request.Request;
import com.sk89q.worldedit.world.World;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Runs code inside a WorldEdit "request".
 * <p>
 * Parsed masks and patterns are bound to RequestExtent: reads go to the current request's EditSession, or its world if none.
 * Since WorldEdit 7.4.6 the request is a ScopedValue; outside a command it is unbound and throws NoSuchElementException,
 * so wrap with {@code Request.runWithRequest}; older versions and FAWE use a ThreadLocal: set it directly and reset afterwards.
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

  /** Runs body in a request bound to world and session. */
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
