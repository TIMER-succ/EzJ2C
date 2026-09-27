import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    static {
        System.out.println("init");
    }

    public static void run() {
        System.out.println("run");
    }

    @NotNative
    public static void main(String[] args) {
        run();
    }
}
