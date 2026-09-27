import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    int v = 1;

    public int get() {
        return v;
    }

    @NotNative
    public static void main(String[] args) {
        Test t = new Test();
        long sum = 0;
        for (int i = 0; i < 100000; i++) {
            sum += t.get();
        }
        long start = System.nanoTime();
        for (int i = 0; i < 5000000; i++) {
            sum += t.get();
        }
        System.out.println(sum);
        System.err.println(System.nanoTime() - start);
    }
}
