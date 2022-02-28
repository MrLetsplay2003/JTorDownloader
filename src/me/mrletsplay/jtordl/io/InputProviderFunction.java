package me.mrletsplay.jtordl.io;

import java.io.InputStream;

@FunctionalInterface
public interface InputProviderFunction {
	
	/**
	 * Provides a new {@link InputStream} to read from
	 * @param offset The offset at which the new stream should start
	 * @param forceNewSource Whether to forcefully use a new source (a new Tor circuit)
	 * @return A new stream
	 */
	public InputStream newInput(long offset, boolean forceNewSource);

}
