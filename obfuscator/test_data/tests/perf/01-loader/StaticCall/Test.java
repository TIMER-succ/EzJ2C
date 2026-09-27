import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    static int v = 3;

    public static int get() {
        return v;
    }

    @NotNative
    public static void main(String[] args) {
        System.out.println(get());
    }
}
