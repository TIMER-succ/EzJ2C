import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    public int get() {
        return 1;
    }

    @NotNative
    public static void main(String[] args) {
        Test f = null;
        try {
            f.get();
        } catch (NullPointerException e) {
            System.out.println("npe");
        }
    }
}
