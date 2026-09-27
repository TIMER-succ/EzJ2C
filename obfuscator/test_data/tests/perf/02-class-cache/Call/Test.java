import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

class Base {
    int special() {
        return 3;
    }
}

@Native
public class Test extends Base {

    int virt() {
        return 1;
    }

    static int stat() {
        return 2;
    }

    int run() {
        System.out.println(virt());
        System.out.println(stat());
        System.out.println(super.special());
        return 0;
    }

    @NotNative
    public static void main(String[] args) {
        new Test().run();
    }
}
