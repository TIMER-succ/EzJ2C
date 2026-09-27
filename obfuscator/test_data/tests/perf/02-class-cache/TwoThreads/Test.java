import by.radioegor146.nativeobfuscator.Native;
import by.radioegor146.nativeobfuscator.NotNative;

@Native
public class Test {

    int v = 7;

    int read() {
        return v;
    }

    @NotNative
    public static void main(String[] args) throws Exception {
        Test t = new Test();
        int[] got = new int[2];
        Thread a = new Thread(new Worker(t, got, 0));
        Thread b = new Thread(new Worker(t, got, 1));
        a.start();
        b.start();
        a.join();
        b.join();
        System.out.println(got[0]);
        System.out.println(got[1]);
    }

    static class Worker implements Runnable {
        final Test t;
        final int[] got;
        final int index;

        Worker(Test t, int[] got, int index) {
            this.t = t;
            this.got = got;
            this.index = index;
        }

        public void run() {
            got[index] = t.read();
        }
    }
}
