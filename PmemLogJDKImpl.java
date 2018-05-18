import sun.nio.ch.FileChannelImpl;

import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;

public class PmemLogJDKImpl implements PmemLog {

    private final FileChannel fileChannel;
    private final MappedByteBuffer mappedByteBuffer;

    private final int HEADER_BYTES = 4; // overhead for the writeOffset pointer
    private volatile int writeOffset;

    public PmemLogJDKImpl(String name, int size) throws IOException {
        File file = new File(name);

        boolean isNew = !file.exists();

        this.fileChannel = (FileChannel) Files
                .newByteChannel(file.toPath(), EnumSet.of(
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.CREATE));

        FileChannelImpl fcImpl = (FileChannelImpl)fileChannel;

        mappedByteBuffer = fcImpl.map(FileChannel.MapMode.READ_WRITE, 0, HEADER_BYTES+size, true);

        if(isNew) {
            writeOffset = HEADER_BYTES;
            persistHeader();
        } else {
            writeOffset = mappedByteBuffer.getInt(0);
        }
    }

    private void persistHeader() {
        mappedByteBuffer.position(0);
        mappedByteBuffer.putInt(writeOffset);
        mappedByteBuffer.force(0, mappedByteBuffer.position());
    }

    @Override
    public void append(String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);

        synchronized (mappedByteBuffer) {
            int from = writeOffset;
            mappedByteBuffer.position(writeOffset);
            mappedByteBuffer.putInt(data.length);
            mappedByteBuffer.put(data);
            mappedByteBuffer.force(from, mappedByteBuffer.position());
            writeOffset = mappedByteBuffer.position();

            persistHeader();
        }
    }
}
