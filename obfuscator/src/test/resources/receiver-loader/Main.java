import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

public class Main {

    static final class Hide extends ClassLoader {
        private final byte[] base;

        Hide(ClassLoader parent, byte[] base) {
            super(parent);
            this.base = base;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.equals("OnlyChild") || name.equals("Sub")) {
                throw new ClassNotFoundException(name);
            }
            if (!name.equals("Base")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineClass(name, base, 0, base.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    static final class Child extends ClassLoader {
        private final byte[] sub;
        private final byte[] onlyChild;

        Child(ClassLoader parent, byte[] sub, byte[] onlyChild) {
            super(parent);
            this.sub = sub;
            this.onlyChild = onlyChild;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals("Sub") && !name.equals("OnlyChild")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = name.equals("Sub") ? sub : onlyChild;
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    private static byte[] read(String name) throws Exception {
        try (InputStream in = Main.class.getResourceAsStream("/" + name + ".class")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    public static void main(String[] args) throws Exception {
        Hide hide = new Hide(Main.class.getClassLoader(), read("Base"));
        Child child = new Child(hide, read("Sub"), read("OnlyChild"));
        Class<?> baseType = hide.loadClass("Base");
        Object base = baseType.getDeclaredConstructor().newInstance();
        try {
            baseType.getMethod("make").invoke(base);
            System.out.println("base-ok");
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof NoClassDefFoundError) && !(e.getCause() instanceof ClassNotFoundException)) {
                throw e;
            }
            System.out.println("base-missing");
        }
        Class<?> subType = child.loadClass("Sub");
        Object sub = subType.getDeclaredConstructor().newInstance();
        Object made = subType.getMethod("make").invoke(sub);
        System.out.println(made);
        try {
            baseType.getMethod("make").invoke(base);
            System.out.println("base-ok");
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof NoClassDefFoundError) && !(e.getCause() instanceof ClassNotFoundException)) {
                throw e;
            }
            System.out.println("base-missing");
        }
        Child child2 = new Child(hide, read("Sub"), read("OnlyChild"));
        Class<?> sub2Type = child2.loadClass("Sub");
        Object made2 = sub2Type.getMethod("make").invoke(sub2Type.getDeclaredConstructor().newInstance());
        System.out.println(made2);
        System.out.println(made.getClass() == made2.getClass() ? "same" : "distinct");
        try {
            baseType.getMethod("makeArray").invoke(base);
            System.out.println("arr-ok");
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof NoClassDefFoundError) && !(e.getCause() instanceof ClassNotFoundException)) {
                throw e;
            }
            System.out.println("arr-missing");
        }
        Object arr = subType.getMethod("makeArray").invoke(sub);
        System.out.println(arr != null && arr.getClass().isArray() ? "arr-ok" : "arr-bad");
        try {
            baseType.getMethod("makeRow").invoke(base);
            System.out.println("row-ok");
        } catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof NoClassDefFoundError) && !(e.getCause() instanceof ClassNotFoundException)) {
                throw e;
            }
            System.out.println("row-missing");
        }
        Object row = subType.getMethod("makeRow").invoke(sub);
        System.out.println(row != null && row.getClass().isArray() ? "row-ok" : "row-bad");
    }
}
