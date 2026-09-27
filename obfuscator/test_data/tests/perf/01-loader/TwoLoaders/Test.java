import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

public class Test {

    static final class Isolated extends ClassLoader {
        private final byte[] worker;
        private final byte[] someLib;

        Isolated(ClassLoader parent, byte[] worker, byte[] someLib) {
            super(parent);
            this.worker = worker;
            this.someLib = someLib;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals("Worker") && !name.equals("SomeLib")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    byte[] bytes = name.equals("Worker") ? worker : someLib;
                    if (bytes == null) {
                        throw new ClassNotFoundException(name);
                    }
                    loaded = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
    }

    private static byte[] read(String resource) throws Exception {
        try (InputStream in = Test.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(resource);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static boolean failed(Class<?> type, String method) throws Exception {
        try {
            type.getMethod(method).invoke(null);
            return false;
        } catch (InvocationTargetException e) {
            return true;
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] worker = read("/Worker.class");
        byte[] someLib = read("/SomeLib.class");
        ClassLoader parent = Test.class.getClassLoader();
        Isolated a = new Isolated(parent, worker, null);
        Isolated b = new Isolated(parent, worker, someLib);
        Class<?> ca = a.loadClass("Worker");
        Class<?> cb = b.loadClass("Worker");
        ca.getMethod("ping").invoke(null);
        cb.getMethod("ping").invoke(null);
        System.out.println(failed(ca, "need"));
        System.out.println(!failed(cb, "need"));
    }
}
