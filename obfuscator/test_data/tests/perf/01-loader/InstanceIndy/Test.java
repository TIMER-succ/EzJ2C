import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

import java.util.stream.Stream;

@Native
public class Test {

    public int run() {
        return Stream.of(1).map(x -> x + 1).findFirst().get();
    }

    @NotNative
    public static void main(String[] args) {
        System.out.println(new Test().run());
    }
}
