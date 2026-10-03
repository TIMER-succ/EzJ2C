import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    int v = 1;

    static class Box {
        int v;

        Box(int v) {
            this.v = v;
        }
    }

    int sum(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += this.v;
        }
        return s;
    }

    boolean strings() {
        boolean ok = true;
        String kept = "token";
        for (int i = 0; i < 100000; i++) {
            if (!"token".equals("token")) {
                ok = false;
            }
            if (!kept.equals("token")) {
                ok = false;
            }
        }
        return ok;
    }

    int dropped() {
        int last = 0;
        for (int i = 0; i < 10; i++) {
            last = new Box(i).v;
        }
        return last;
    }

    Box make() {
        return new Box(7);
    }

    Box pass(Box b) {
        return b;
    }

    String boom() {
        try {
            Box b = null;
            int value = b.v;
            return "miss" + value;
        } catch (NullPointerException e) {
            return e.getClass().getSimpleName();
        }
    }

    String many() {
        int last = -1;
        for (int i = 0; i < 700; i++) {
            last = new Box(i).v;
        }
        if (last == 699) {
            return "ok";
        }
        return "bad";
    }

    String round() {
        String[] a = new String[2];
        a[0] = "p";
        a[1] = "q";
        return a[0] + a[1];
    }

    @NotNative
    public static void main(String[] args) {
        Test t = new Test();
        t.sum(1000);
        long start = System.nanoTime();
        int hot = t.sum(100000);
        System.err.println(System.nanoTime() - start);
        System.out.println(hot);
        System.out.println(t.strings());
        System.out.println(t.dropped());
        System.out.println(t.make().v);
        Box box = new Box(4);
        System.out.println(t.pass(box).v);
        System.out.println(t.boom());
        System.out.println(t.many());
        System.out.println(t.round());
    }
}
