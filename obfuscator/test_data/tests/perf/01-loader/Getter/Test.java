import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    int v;

    public int get() {
        return v;
    }

    public void set(int n) {
        v = n;
    }

    @NotNative
    public static void main(String[] args) {
        Test t = new Test();
        System.out.println(t.get());
        t.set(7);
        System.out.println(t.get());
    }
}
