package me.mrletsplay.jtordl.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.function.Function;

public class RetryingInputStream extends InputStream {

	private InputStream in;
	private Function<Long, InputStream> retryMethod;
	private long offset;
	private boolean closed;
	
	public RetryingInputStream(InputStream initialInput, Function<Long, InputStream> retryMethod) {
		this.in = initialInput;
		this.retryMethod = retryMethod;
	}
	
	@Override
	public synchronized int read(byte[] b, int off, int len) throws IOException {
		if(closed) throw new IllegalStateException("Stream is closed");
		byte[] oldBytes = Arrays.copyOf(b, b.length);
		try {
			int read = in.read(b, off, len);
			if(read > 0) offset += read;
			return read;
		}catch(IOException e) {
			in = retryMethod.apply(offset);
			return read(oldBytes, off, len);
		}
	}

	@Override
	public synchronized int read() throws IOException {
		if(closed) throw new IllegalStateException("Stream is closed");
		try {
			int read = in.read();
			if(read > 0) offset++;
			return read;
		}catch(IOException e) {
			in = retryMethod.apply(offset);
			return read();
		}
	}
	
	@Override
	public void close() throws IOException {
		in.close();
		closed = true;
	}
	
}
