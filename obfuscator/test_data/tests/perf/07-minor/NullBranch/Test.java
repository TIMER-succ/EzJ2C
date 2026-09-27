import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    String ifNull(Object o) {
        if (o == null) {
            return "null";
        }
        return "nn";
    }

    String ifNonNull(Object o) {
        if (o != null) {
            return "nn2";
        }
        return "null2";
    }

    String same(Object o1, Object o2) {
        if (o1 == o2) {
            return "same";
        }
        return "diff";
    }

    int nullLoop(Object ref, int n) {
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (ref == null) {
                c++;
            }
        }
        return c;
    }

    @NotNative
    public static void main(String[] args) {
        Test t = new Test();
        System.out.println(t.ifNull(null));
        System.out.println(t.ifNull(new Object()));
        System.out.println(t.ifNonNull(null));
        System.out.println(t.ifNonNull(new Object()));
        Object o = new Object();
        System.out.println(t.same(o, o));
        System.out.println(t.same(new Object(), new Object()));
        long start = System.nanoTime();
        int c = t.nullLoop(null, 5000000);
        System.err.println(System.nanoTime() - start);
        System.out.println(c);
    }
}
