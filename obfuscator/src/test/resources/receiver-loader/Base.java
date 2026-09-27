import by.radioegor146.nativeobfuscator.Native;

@Native
public class Base {
    public Object make() {
        return new OnlyChild();
    }

    public Object makeArray() {
        return new OnlyChild[1][1];
    }

    public Object makeRow() {
        return new OnlyChild[1][];
    }
}
