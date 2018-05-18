import java.util.Date;

public class Driver {

    public static void main(String[] args) throws Exception {

        //runWith( new PmemLogJavaImpl() );

        //runWith( new PmemLogJNIImpl("/mnt/pmem/test/pmemjni", 800*1024*1024) );

        //runWith( new PmemLogJDKImpl("/mnt/pmem/test/pmemjdk", 800*1024*1024) );
    }

    private static void runWith(PmemLog pmemLog) {

        System.out.println(new Date());
        long startNanos = System.nanoTime();

        for(int i = 0; i < 20_000_000; i++) {
            pmemLog.append("This is the "+i+"th string appended");
        }

        long endNanos = System.nanoTime();

        System.out.println(new Date());
        System.out.println(endNanos-startNanos);
    }
}
