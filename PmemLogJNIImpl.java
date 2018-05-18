import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class PmemLogJNIImpl implements PmemLog {

    static {
        System.loadLibrary("pmemlogjni");
    }

    private final long plp;

    public PmemLogJNIImpl(String name, int size) throws IOException {

        byte[] path = name.getBytes(StandardCharsets.UTF_8);
        plp = pmemlogCreate(path, size);
    }

    @Override
    public void append(String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        pmemlogAppend(plp, data);
    }

    private native long pmemlogCreate(byte[] path, int size);

    private native void pmemlogAppend(long plp, byte[] data);
}
