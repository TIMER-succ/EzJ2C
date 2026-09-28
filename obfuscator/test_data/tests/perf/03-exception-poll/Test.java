import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    static class Holder {
        int f;
    }

    final Object lock = new Object();
    int fin;

    long arith(int n) {
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += i;
        }
        return sum;
    }

    String field() {
        try {
            Holder h = null;
            int v = h.f;
            return "miss" + v;
        } catch (NullPointerException e) {
            return "npe";
        }
    }

    String div() {
        try {
            int v = 1 / 0;
            return "miss" + v;
        } catch (ArithmeticException e) {
            return "ae";
        }
    }

    String array() {
        try {
            int[] a = new int[1];
            int v = a[1];
            return "miss" + v;
        } catch (ArrayIndexOutOfBoundsException e) {
            return "aioobe";
        }
    }

    String cast() {
        try {
            Object o = Integer.valueOf(1);
            String s = (String) o;
            return "miss" + s;
        } catch (ClassCastException e) {
            return "cce";
        }
    }

    int isStr(Object o) {
        return o instanceof String ? 1 : 0;
    }

    void body() {
        Holder h = null;
        try {
            int v = h.f;
            fin += v;
        } finally {
            fin++;
        }
    }

    void boom() {
        synchronized (lock) {
            Holder h = null;
            int v = h.f;
            fin += v;
        }
    }

    int raw() {
        Holder h = null;
        return h.f;
    }

    @NotNative
    void enter() {
        synchronized (lock) {
            System.out.println("entered");
        }
    }

    @NotNative
    public static void main(String[] args) throws Exception {
        Test t = new Test();
        t.arith(1000);
        long start = System.nanoTime();
        long sum = t.arith(5000000);
        System.err.println(System.nanoTime() - start);
        System.out.println(sum);
        System.out.println(t.field());
        System.out.println(t.div());
        System.out.println(t.array());
        System.out.println(t.cast());
        System.out.println(t.isStr(null));
        System.out.println(t.isStr("x"));
        System.out.println(t.isStr(Integer.valueOf(1)));
        try {
            t.body();
            System.out.println("miss");
        } catch (NullPointerException e) {
            System.out.println(t.fin);
            System.out.println("npe");
        }
        final Test target = t;
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                target.enter();
            }
        });
        try {
            t.boom();
            System.out.println("miss");
        } catch (NullPointerException e) {
            th.start();
            th.join(5000);
        }
        try {
            System.out.println(t.raw());
            System.out.println("miss");
        } catch (NullPointerException e) {
            System.out.println("npe");
        }
    }
}
