package me.mrletsplay.jtordl.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

public class RetryingInputStream extends InputStream {

	public static final long DEFAULT_SLOW_THRESHOLD_SPEED = 1024; // 1 KiB/s
	public static final long DEFAULT_SLOW_THRESHOLD_TIME = 60 * 1000; // 60 s

	private InputStream in;
	private InputProviderFunction providerFunction;
	private long offset;
	private boolean closed;

	private boolean trackTransferSpeed;
	private long
		bytesLastSecond = -1,
		thisSecond = 0,
		bytesThisSecond = 0,
		timeOfLastByte = -1;

	private boolean retryIfSlow;
	private long
		slowThresholdSpeed = DEFAULT_SLOW_THRESHOLD_SPEED,
		slowThresholdTime = DEFAULT_SLOW_THRESHOLD_TIME;
	private long lastFastTime = -1;

	public RetryingInputStream(InputStream initialInput, InputProviderFunction providerFunction) {
		this.in = initialInput;
		this.providerFunction = providerFunction;
		this.thisSecond = System.currentTimeMillis();
	}

	/**
	 * Sets whether this stream should keep track of the transfer speed while reading.<br>
	 * May be used in combination with {@link #setRetryIfSlow(boolean)} to automatically retry if the transfer speed is too slow
	 * @param trackTransferSpeed Whether to track the transfer speed
	 */
	public void setTrackTransferSpeed(boolean trackTransferSpeed) {
		this.trackTransferSpeed = trackTransferSpeed;
	}

	/**
	 * Sets whether this stream should automatically retry if the transfer speed is too slow over a longer period of time.<br>
	 * The threshold for "too slow" can be set using {@link #setSlowThreshold(long, long)}<br>
	 * {@link #setTrackTransferSpeed(boolean)} must be set to <code>true</code> for this to work<br>
	 * <br>
	 * Notes:<br>
	 * &nbsp;&nbsp;- This only works if you're constantly reading from the stream, otherwise the transfer speed will sink and might be falsely interpreted as slow<br>
	 * &nbsp;&nbsp;- Reading too large chunks at a time will reduce the accuracy of the reported transfer speed.
	 * @param retryIfSlow Whether to retry if the transfer speed is too slow
	 * @see #setTrackTransferSpeed(boolean)
	 * @see #setSlowThreshold(long, long)
	 */
	public void setRetryIfSlow(boolean retryIfSlow) {
		this.retryIfSlow = retryIfSlow;
	}

	/**
	 * Sets the slow threshold to be used if {@link #setRetryIfSlow(boolean)} is enabled.<br>
	 * The threshold specifies a minimum transfer speed (in bytes/s) that must be met. If the stream fails to provide this speed over a longer period of time (the speed is lower than the minimum for at least <code>slowThresholdTime</code> ms), it will retry using a new source.<br>
	 * The default values for this are {@link #DEFAULT_SLOW_THRESHOLD_SPEED} and {@link #DEFAULT_SLOW_THRESHOLD_TIME}
	 * @param slowThresholdSpeed The threshold speed to use
	 * @param slowThresholdTime The time for the speed to have been below the threshold for
	 * @see #setTrackTransferSpeed(boolean)
	 * @see #setRetryIfSlow(boolean)
	 */
	public void setSlowThreshold(long slowThresholdSpeed, long slowThresholdTime) {
		this.slowThresholdSpeed = slowThresholdSpeed;
		this.slowThresholdTime = slowThresholdTime;
	}

	/**
	 * Returns the current download speed (in bytes/s). This value might stay the same for up to a full second.<br>
	 * Returns <code>-1</code> If the speed was not yet measured or if this stream is not set to track the transfer speed
	 * @return The current transfer speed, or -1
	 * @see #setTrackTransferSpeed(boolean)
	 */
	public long getTransferSpeedBytes() {
		return trackTransferSpeed ? bytesLastSecond : -1;
	}

	/**
	 * Returns the timestamp when the last byte was read from the stream.<br>
	 * Returns <code>-1</code> if no bytes were read yet or if this stream is not set to track the transfer speed
	 * @return The time of the last byte
	 * @see #setTrackTransferSpeed(boolean)
	 */
	public long getTimeOfLastByte() {
		return trackTransferSpeed ? timeOfLastByte : -1;
	}

	/**
	 * Returns the time (in milliseconds) since the last byte was read from the stream.<br>
	 * Returns <code>-1</code> if no bytes were read yet or if this stream is not set to track the transfer speed
	 * @return The time since the last byte
	 * @see #setTrackTransferSpeed(boolean)
	 */
	public long getTimeSinceLastByte() {
		return trackTransferSpeed ? (timeOfLastByte == -1 ? -1 : System.currentTimeMillis() - timeOfLastByte) : -1;
	}

	@Override
	public synchronized int read(byte[] b, int off, int len) throws IOException {
		if(closed) throw new IllegalStateException("Stream is closed");
		byte[] oldBytes = Arrays.copyOf(b, b.length);
		try {
			if(trackTransferSpeed && thisSecond != System.currentTimeMillis() / 1000) {
				bytesLastSecond = bytesThisSecond;
				bytesThisSecond = 0;
				thisSecond = System.currentTimeMillis() / 1000;
			}

			int read = in.read(b, off, len);
			if(read > 0) offset += read;
			if(trackTransferSpeed) {
				bytesThisSecond += read;
				timeOfLastByte = System.currentTimeMillis();
				if(retryIfSlow) {
					if(getTransferSpeedBytes() > slowThresholdSpeed || lastFastTime == -1) lastFastTime = System.currentTimeMillis();
					if(lastFastTime != -1 && System.currentTimeMillis() - lastFastTime > slowThresholdTime) {
						in = providerFunction.newInput(offset, true);
						lastFastTime = System.currentTimeMillis();
					}
				}
			}
			return read;
		}catch(IOException e) {
			in = providerFunction.newInput(offset, false);
			return read(oldBytes, off, len);
		}
	}

	@Override
	public synchronized int read() throws IOException {
		if(closed) throw new IllegalStateException("Stream is closed");
		try {
			if(trackTransferSpeed &&thisSecond != System.currentTimeMillis() / 1000) {
				bytesLastSecond = bytesThisSecond;
				bytesThisSecond = 0;
				thisSecond = System.currentTimeMillis() / 1000;
			}

			int read = in.read();
			if(read != -1) offset++;
			if(trackTransferSpeed) {
				bytesThisSecond++;
				timeOfLastByte = System.currentTimeMillis();
			}
			return read;
		}catch(IOException e) {
			in = providerFunction.newInput(offset, false);
			return read();
		}
	}

	@Override
	public void close() throws IOException {
		in.close();
		closed = true;
	}

}
