import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    String name() {
        return Foo.class.getName();
    }

    @NotNative
    public static void main(String[] args) {
        System.out.println(new Test().name());
    }
}

class Foo {
}
