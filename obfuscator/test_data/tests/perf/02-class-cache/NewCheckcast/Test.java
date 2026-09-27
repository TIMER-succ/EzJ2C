import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    void run() {
        Object o = new Foo();
        System.out.println("new");
        System.out.println(o instanceof Foo ? "yes" : "no");
        Foo f = (Foo) o;
        System.out.println(f == o ? "cast" : "bad");
        try {
            Bar b = (Bar) o;
            System.out.println(b);
        } catch (ClassCastException e) {
            System.out.println("cce");
        }
    }

    @NotNative
    public static void main(String[] args) {
        new Test().run();
    }
}

class Foo {
}

class Bar {
}
