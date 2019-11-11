package me.mrletsplay.jtordl.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.function.Function;

public class RetryingInputStream extends InputStream {

	private InputStream in;
	private Function<Long, InputStream> retryMethod;
	private long offset;
	
	public RetryingInputStream(InputStream initialInput, Function<Long, InputStream> retryMethod) {
		this.in = initialInput;
		this.retryMethod = retryMethod;
	}
	
	@Override
	public synchronized int read(byte[] b, int off, int len) throws IOException {
		byte[] oldBytes = Arrays.copyOf(b, b.length);
		try {
			int read = in.read(b, off, len);
			if(read > 0) offset += read;
			return read;
		}catch(IOException e) {
			System.out.println("Retrying... (1)");
			in = retryMethod.apply(offset);
			System.out.println("Done (1)");
			return read(oldBytes, off, len);
		}
	}

	@Override
	public synchronized int read() throws IOException {
		try {
			int read = in.read();
			if(read > 0) offset++;
			return read;
		}catch(IOException e) {
			System.out.println("Retrying... (2)");
			in = retryMethod.apply(offset);
			System.out.println("Done (2)");
			return read();
		}
	}
	
}
