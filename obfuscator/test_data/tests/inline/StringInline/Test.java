import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {
    public static String run() {
        StringBuilder out = new StringBuilder();
        String empty = "";
        String a = "a";
        String e = "\u00e9";
        String nul = "\u0000";
        String pair = "\uD83D\uDE00";
        out.append(empty.length()).append('\n');
        out.append(a.length()).append('\n');
        out.append(e.length()).append('\n');
        out.append(nul.length()).append('\n');
        out.append(pair.length()).append('\n');
        out.append(empty.isEmpty()).append('\n');
        out.append(a.isEmpty()).append('\n');
        out.append(a.equals(a)).append('\n');
        out.append(a.equals(new String("a"))).append('\n');
        out.append(a.equals("b")).append('\n');
        out.append(a.equals("ab")).append('\n');
        out.append(a.equals(null)).append('\n');
        out.append(a.equals(new Object())).append('\n');
        out.append(a.equals(new StringBuilder("a"))).append('\n');
        out.append(empty.equals("")).append('\n');
        out.append(e.equals("\u00e9")).append('\n');
        out.append(e.equals("e")).append('\n');
        out.append(nul.equals("\u0000")).append('\n');
        out.append(nul.equals("a")).append('\n');
        out.append(pair.equals("\uD83D\uDE00")).append('\n');
        out.append(pair.equals("\uD83D")).append('\n');
        Object asObject = a;
        out.append(asObject.equals("a")).append('\n');
        out.append(asObject.equals("b")).append('\n');
        CharSequence cs = "ab";
        out.append(cs.length()).append('\n');
        out.append(new StringBuilder("ab").length()).append('\n');
        out.append(a.getClass() == String.class).append('\n');
        out.append(cs.getClass().getName()).append('\n');
        out.append(new Test().getClass() == Test.class).append('\n');
        out.append(npeLen()).append('\n');
        out.append(npeEmpty()).append('\n');
        out.append(npeEq()).append('\n');
        out.append(npeClass()).append('\n');
        return out.toString();
    }

    private static String npeLen() {
        try {
            String s = null;
            int n = s.length();
            return "no" + n;
        } catch (NullPointerException ex) {
            return "npe";
        }
    }

    private static String npeEmpty() {
        try {
            String s = null;
            boolean n = s.isEmpty();
            return "no" + n;
        } catch (NullPointerException ex) {
            return "npe";
        }
    }

    private static String npeEq() {
        try {
            String s = null;
            boolean n = s.equals("a");
            return "no" + n;
        } catch (NullPointerException ex) {
            return "npe";
        }
    }

    private static String npeClass() {
        try {
            Object s = null;
            Class<?> n = s.getClass();
            return "no" + n;
        } catch (NullPointerException ex) {
            return "npe";
        }
    }

    @NotNative
    public static void main(String[] args) {
        System.out.print(run());
    }
}
