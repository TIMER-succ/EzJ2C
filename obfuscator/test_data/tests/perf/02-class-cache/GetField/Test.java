import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    int v = 3;

    int read() {
        return v;
    }

    @NotNative
    public static void main(String[] args) {
        Test t = new Test();
        System.out.println(t.read());
        System.out.println(t.read());
    }
}
