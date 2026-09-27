public class Test {
    public static void main(String[] args) throws Exception {
        try {
            Class.forName("Missing").getMethod("go").invoke(null);
            System.out.println("fail");
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof NoClassDefFoundError) {
                System.out.println("ncde");
            } else {
                throw e;
            }
        }
    }
}
