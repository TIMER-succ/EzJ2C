import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
class Same {
    static int add(int a, int b) {
        return a + b;
    }

    static int inc(int x) {
        return x + 1;
    }

    static boolean flag(int x) {
        return x > 0;
    }

    static long wide(long a, long b) {
        return a + b;
    }

    static String name() {
        return "ok";
    }

    static long bench() {
        long s = 0;
        for (int i = 0; i < 5000000; i++) {
            s += inc(i);
        }
        return s;
    }
}

@Native
class Other {
    static int add(int a, int b) {
        return a + b;
    }
}

@Native
class Fact {
    static long fact(long n) {
        if (n <= 1) {
            return 1;
        }
        return n * fact(n - 1);
    }
}

@Native
class SuperS {
    String f() {
        return "super";
    }
}

@Native
class SubS extends SuperS {
    String f() {
        return "sub";
    }

    String run() {
        return super.f();
    }
}

@Native
class SuperV {
    String f() {
        return "super";
    }
}

@Native
class SubV extends SuperV {
    String f() {
        return "sub";
    }

    String run() {
        return f();
    }
}

@Native
class Priv {
    private int secret(int x) {
        return x + 4;
    }

    int run() {
        return secret(3);
    }
}

@Native
class B {
    static {
        System.out.println("init");
    }

    static void run() {
        System.out.println("run");
    }
}

@Native
class A {
    static void go() {
        B.run();
    }
}

@Native
class Boom {
    static void boom() {
        throw new NullPointerException();
    }

    static void go() {
        try {
            boom();
            System.out.println("miss");
        } catch (NullPointerException e) {
            System.out.println("npe");
        }
    }
}

@Native
class Sync {
    static synchronized int add(int a, int b) {
        return a + b;
    }

    static int run() {
        return add(2, 2);
    }
}

@Native
class Box {
    int v;

    Box(int v) {
        this.v = v;
    }

    static int run() {
        return new Box(9).v;
    }
}

@Native
class Ext {
    @NotNative
    static int plain(int x) {
        return x + 1;
    }

    static int run() {
        return Math.max(3, 8) + plain(1);
    }
}

@Native
class Touch {
    static String touch(String s) {
        if (s == null) {
            return "null";
        }
        return s;
    }

    static String box(int i) {
        return "v" + i;
    }

    static int sum(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += box(i).length();
        }
        return s;
    }
}

@Native
class Arr {
    static int sum(int[] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i];
        }
        return s;
    }

    static int run() {
        return sum(new int[]{1, 2, 3});
    }
}

@Native
public class Test {
    static void run() {
        System.out.println(Same.add(2, 3));
        System.out.println(Other.add(3, 4));
        System.out.println(Fact.fact(20));
        System.out.println(new SubS().run());
        System.out.println(new SubV().run());
        System.out.println(new Priv().run());
        A.go();
        Boom.go();
        System.out.println(Sync.run());
        System.out.println(Box.run());
        System.out.println(Ext.run());
        System.out.println(Same.flag(1) ? 1 : 0);
        System.out.println(Same.name());
        System.out.println(Same.wide(10, 20));
        String kept = "kept";
        System.out.println(Touch.touch(kept));
        System.out.println(kept);
        System.out.println(Touch.sum(1000));
        System.out.println(Arr.run());
    }

    @NotNative
    public static void main(String[] args) {
        long started = System.nanoTime();
        long sum = Same.bench();
        System.err.println(System.nanoTime() - started);
        System.out.println(sum);
        run();
    }
}
