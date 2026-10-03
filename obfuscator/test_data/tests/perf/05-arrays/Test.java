import by.radioegor146.nativeobfuscator.Native;

@Native
public class Test {

    static int[] escaped;

    static int intSum(int[] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i];
        }
        return s;
    }

    static int intFill(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) {
            a[i] = i;
        }
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += a[i];
        }
        return s;
    }

    static String bounds(int n) {
        int[] a = new int[n];
        try {
            int s = 0;
            for (int i = -1; i < n; i++) {
                s += a[i];
            }
            return "miss" + s;
        } catch (ArrayIndexOutOfBoundsException e) {
            try {
                int s = 0;
                for (int i = 0; i <= n; i++) {
                    s += a[i];
                }
                return "miss" + s;
            } catch (ArrayIndexOutOfBoundsException e2) {
                return "aioobe";
            }
        }
    }

    static String nullArray(int[] a) {
        try {
            int s = 0;
            for (int i = 0; i < 2; i++) {
                s += a[i];
            }
            return "miss" + s;
        } catch (NullPointerException e) {
            return "npe";
        }
    }

    static int booleanTrunc(int n) {
        boolean[] a = new boolean[n];
        for (int i = 0; i < n; i++) {
            a[i] = (i & 1) == 0;
        }
        int s = 0;
        for (int i = 0; i < n; i++) {
            if (a[i]) {
                s++;
            }
        }
        return s;
    }

    static int byteOnce() {
        byte[] a = new byte[1];
        a[0] = -1;
        return a[0];
    }

    static int byteSign(int n) {
        byte[] a = new byte[n];
        for (int i = 0; i < n; i++) {
            a[i] = -1;
        }
        int s = 0;
        for (int i = 0; i < n; i++) {
            s += a[i];
        }
        return s;
    }

    static int charShort(int n) {
        char[] c = new char[n];
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            c[i] = 65535;
            s[i] = -1;
        }
        int sum = 0;
        for (int i = 0; i < n; i++) {
            sum += c[i];
            sum += s[i];
        }
        return sum;
    }

    static long longFloatDouble() {
        long[] l = new long[]{3L, 4L};
        long ls = 0;
        for (int i = 0; i < l.length; i++) {
            ls += l[i];
        }
        float[] f = new float[1];
        double[] d = new double[1];
        for (int i = 0; i < 1; i++) {
            f[i] = 1.5f;
            d[i] = 2.5d;
        }
        return ls * 100000L + Float.floatToIntBits(f[0]) + Double.doubleToLongBits(d[0]);
    }

    static int at(int[] a, int i) {
        return a[i];
    }

    static int callInLoop(int[] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i];
            s += at(a, i);
        }
        return s;
    }

    static int escape(int n) {
        int[] a = new int[n];
        int s = 0;
        for (int i = 0; i < n; i++) {
            a[i] = i + 1;
            escaped = a;
            s += a[i];
        }
        return s + escaped[0];
    }

    static int tryCatch(int n) {
        int[] a = new int[n];
        try {
            for (int i = 0; i < n + 1; i++) {
                a[i] = i + 1;
            }
            return -1;
        } catch (ArrayIndexOutOfBoundsException e) {
            int s = 0;
            for (int i = 0; i < n; i++) {
                s += a[i];
            }
            return s;
        }
    }

    static int multi(int[][] a) {
        int s = 0;
        for (int i = 0; i < a.length; i++) {
            int[] row = a[i];
            for (int j = 0; j < row.length; j++) {
                s += row[j];
            }
        }
        return s;
    }

    public static void main(String[] args) throws Exception {
        int[] a = new int[8];
        for (int i = 0; i < a.length; i++) {
            a[i] = i + 1;
        }
        System.out.println("sum " + intSum(a));
        System.out.println("fill " + intFill(8));
        System.out.println("bounds " + bounds(4));
        System.out.println("null " + nullArray(null));
        System.out.println("bool " + booleanTrunc(4));
        boolean[] raw = new boolean[4];
        Class<?> rawClass = Class.forName("BoolRaw");
        int once = ((Integer) rawClass.getMethod("once", boolean[].class).invoke(null, raw)).intValue();
        int fill = ((Integer) rawClass.getMethod("fill", boolean[].class, int.class).invoke(null, raw, Integer.valueOf(4))).intValue();
        System.out.println("raw " + once + " " + fill);
        System.out.println("byte " + byteOnce() + " " + byteSign(3));
        System.out.println("cs " + charShort(2));
        System.out.println("lfd " + longFloatDouble());
        System.out.println("call " + callInLoop(a));
        System.out.println("escape " + escape(4));
        System.out.println("try " + tryCatch(4));
        int[][] m = new int[][]{{1, 2}, {3}};
        System.out.println("multi " + multi(m));

        int n = 10000;
        int reps = 5000;
        int[] bench = new int[n];
        for (int i = 0; i < n; i++) {
            bench[i] = i;
        }
        long t0 = System.nanoTime();
        long checksum = 0;
        for (int r = 0; r < reps; r++) {
            checksum += intSum(bench);
        }
        long ms = (System.nanoTime() - t0) / 1000000L;
        System.err.println("bench " + ms);
        System.out.println("bench " + checksum);

        int[] small = new int[64];
        for (int i = 0; i < small.length; i++) {
            small[i] = i;
        }
        long c0 = System.nanoTime();
        int callSum = 0;
        for (int r = 0; r < 200; r++) {
            callSum += callInLoop(small);
        }
        System.err.println("callbench " + ((System.nanoTime() - c0) / 1000000L));
        System.out.println("callbench " + callSum);
    }
}
