import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

class Box {
    int v;

    Box(int v) {
        this.v = v;
    }
}

class Base {
    int special(int x) {
        return x + 3;
    }
}

interface Face {
    int face(int x);
}

class Callee extends Base implements Face {
    static int sink;

    int noargs() {
        return 7;
    }

    int mix(int a, long b, Object c) {
        return a + (int) b + (c == null ? 0 : ((Box) c).v);
    }

    int narrow(boolean z, byte b, char c, short s, float f, double d) {
        return (z ? 1 : 0) + b + c + s + (int) f + (int) d;
    }

    void voids(int x) {
        sink = x;
    }

    static int stat(int x) {
        return x + 5;
    }

    Box obj(int x) {
        return new Box(x);
    }

    int boom() {
        throw new NullPointerException();
    }

    public int face(int x) {
        return x + 9;
    }

    int ten(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j) {
        return a + b + c + d + e + f + g + h + i + j;
    }
}

@Native
public class Test extends Base {

    int runSpecial() {
        return super.special(4);
    }

    void run() {
        Callee c = new Callee();
        System.out.println(c.noargs());
        System.out.println(c.mix(1, 2L, new Box(11)));
        System.out.println(c.narrow(true, (byte) -2, (char) 3, (short) 4, 5.5f, 6.5d));
        c.voids(8);
        System.out.println(Callee.sink);
        System.out.println(Callee.stat(6));
        System.out.println(runSpecial());
        Face f = c;
        System.out.println(f.face(1));
        System.out.println(c.obj(4).v);
        try {
            c.boom();
            System.out.println("miss");
        } catch (NullPointerException e) {
            System.out.println("npe");
        }
    }

    int bench(int n) {
        Callee c = new Callee();
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += c.ten(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        }
        return s;
    }

    @NotNative
    public static void main(String[] args) {
        Test t = new Test();
        t.run();
        long start = System.nanoTime();
        int s = t.bench(1000000);
        System.err.println(System.nanoTime() - start);
        System.out.println(s);
    }
}
