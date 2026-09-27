import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
interface Face {
    default int get() {
        return value();
    }

    int value();
}

public class Test implements Face {

    int v = 7;

    public int value() {
        return v;
    }

    @NotNative
    public static void main(String[] args) {
        System.out.println(new Test().get());
    }
}
