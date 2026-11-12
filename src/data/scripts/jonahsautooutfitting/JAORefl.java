package data.scripts.jonahsautooutfitting;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection without naming java.lang.reflect (the game's script loader refuses classes that refer to it): Method is
 * held as a plain Object and getMethod, getMethods, getName and invoke are reached through method handles, the same way
 * Auto Colonies' CARefl does it. Only for reading the game's own UI tree (JAOScreenButton).
 */
final class JAORefl {
    static final MethodHandle GET_METHOD, GET_METHODS, INVOKE, NAME;
    static final Object NONE = new Object();
    static final Map<String, Object> METHODS = new HashMap<>();
    static final Map<Class<?>, Boolean> SYNC = new HashMap<>();

    static {
        try {
            Class<?> method = Class.forName("java.lang.reflect.Method", false, null);
            Class<?> methods = Class.forName("[Ljava.lang.reflect.Method;", false, null);
            // a full lookup: getMethod and invoke are caller-sensitive, which publicLookup refuses
            MethodHandles.Lookup l = MethodHandles.lookup();
            GET_METHOD = l.findVirtual(Class.class, "getMethod", MethodType.methodType(method, String.class, Class[].class)).asFixedArity();
            GET_METHODS = l.findVirtual(Class.class, "getMethods", MethodType.methodType(methods));
            INVOKE = l.findVirtual(method, "invoke", MethodType.methodType(Object.class, Object.class, Object[].class)).asFixedArity();
            NAME = l.findVirtual(method, "getName", MethodType.methodType(String.class));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private JAORefl() { }

    /** A public no-argument method by name, cached per class (NONE when there is none). */
    static Object method(Class<?> c, String name) {
        String key = c.getName() + "#" + name;
        Object m = METHODS.get(key);
        if (m == null) {
            try { m = (Object) GET_METHOD.invoke(c, name, new Class<?>[0]); } catch (Throwable t) { m = NONE; }
            METHODS.put(key, m);
        }
        return m;
    }

    /** target.name(), or null when it has no such method or the call fails. */
    static Object call(Object target, String name) {
        if (target == null) return null;
        Object m = method(target.getClass(), name);
        if (m == NONE) return null;
        try { return (Object) INVOKE.invoke(m, target, new Object[0]); } catch (Throwable t) { return null; }
    }

    /** A UI panel's children (getChildrenCopy), empty for anything else. */
    static List<?> children(Object c) {
        Object kids = call(c, "getChildrenCopy");
        return kids instanceof List ? (List<?>) kids : Collections.emptyList();
    }

    /** True if the class has a public syncWithCurrentVariant method with any parameters: the refit panel (as LunaLib finds it). */
    static boolean isRefitPanel(Class<?> c) {
        Boolean b = SYNC.get(c);
        if (b == null) {
            b = false;
            try {
                for (Object m : (Object[]) GET_METHODS.invoke(c)) {
                    if ("syncWithCurrentVariant".equals((String) NAME.invoke(m))) { b = true; break; }
                }
            } catch (Throwable ignored) { }
            SYNC.put(c, b);
        }
        return b;
    }
}
