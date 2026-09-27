import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    int v = 1;

    long sum(int n) {
        long s = 0;
        for (int i = 0; i < n; i++) {
            s += v;
        }
        return s;
    }

    @NotNative
    public static void main(String[] args) {
        Test t = new Test();
        long sum = t.sum(100000);
        long start = System.nanoTime();
        sum += t.sum(5000000);
        System.out.println(sum);
        System.err.println(System.nanoTime() - start);
    }
}
