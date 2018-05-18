import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PmemLogJavaImpl implements PmemLog {

    private final List<byte[]> content = new ArrayList<>();

    @Override
    public void append(String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        content.add(data);
    }
}
