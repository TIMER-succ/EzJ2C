import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    static class Holder {
        int x;
    }

    String npe() {
        try {
            Holder h = null;
            int v = h.x;
            return "miss" + v;
        } catch (NullPointerException e) {
            return "npe";
        }
    }

    String ae() {
        try {
            int v = 1 / 0;
            return "miss" + v;
        } catch (ArithmeticException e) {
            return "ae";
        }
    }

    String nase() {
        try {
            int[] a = new int[-1];
            return "miss" + a.length;
        } catch (NegativeArraySizeException e) {
            return "nase";
        }
    }

    String cce() {
        try {
            Object o = Integer.valueOf(1);
            String s = (String) o;
            return "miss" + s;
        } catch (ClassCastException e) {
            return "cce";
        }
    }

    @NotNative
    public static void main(String[] args) {
        Test t = new Test();
        System.out.println(t.npe());
        System.out.println(t.ae());
        System.out.println(t.nase());
        System.out.println(t.cce());
    }
}
