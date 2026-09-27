import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    static int v = 4;

    static int read() {
        return v;
    }

    @NotNative
    public static void main(String[] args) {
        System.out.println(read());
    }
}
