package me.mrletsplay.jtordl;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Callable;
import java.util.function.Function;

import me.mrletsplay.jtordl.circuit.CircuitState;
import me.mrletsplay.jtordl.circuit.TorCircuit;
import me.mrletsplay.jtordl.io.RetryingInputStream;
import me.mrletsplay.mrcore.misc.FriendlyException;

public class JTorDownloader {

	public static InputStream createStream(TorCircuit circuit, URL url) throws FriendlyException {
		try {
			circuit.awaitState(CircuitState.RUNNING);
			HttpClient client = circuit.createHttpClient();
			HttpRequest r = HttpRequest.newBuilder(url.toURI()).build();
			return client.send(r, HttpResponse.BodyHandlers.ofInputStream()).body();
		} catch (IOException | URISyntaxException | InterruptedException e) {
			throw new FriendlyException("Failed to create or open connection", e);
		}
	}

	public static InputStream createStream(TorCircuit circuit, String url) throws FriendlyException {
		try {
			return createStream(circuit, new URL(url));
		} catch (MalformedURLException e) {
			throw new FriendlyException(e);
		}
	}
	
	public static InputStream createStream(TorCircuit circuit, URL url, long rangeStart, long rangeEnd) throws FriendlyException {
		try {
			circuit.awaitState(CircuitState.RUNNING);
			HttpClient client = circuit.createHttpClient();
			HttpRequest r = HttpRequest.newBuilder(url.toURI())
					.header("Range", "bytes=" + rangeStart + "-" + (rangeEnd == -1 ? "" : rangeEnd))
					.build();
			return client.send(r, HttpResponse.BodyHandlers.ofInputStream()).body();
		}catch(IOException | URISyntaxException | InterruptedException e) {
			throw new FriendlyException("Failed to create or open connection", e);
		}
	}

	public static InputStream createStream(TorCircuit circuit, String url, long rangeStart, long rangeEnd) throws FriendlyException {
		try {
			return createStream(circuit, new URL(url), rangeStart, rangeEnd);
		} catch (MalformedURLException e) {
			throw new FriendlyException(e);
		}
	}
	
	public static long getContentLength(TorCircuit circuit, URL url) throws FriendlyException {
		try {
			circuit.awaitState(CircuitState.RUNNING);
			HttpClient client = circuit.createHttpClient();	
			HttpRequest r = HttpRequest.newBuilder(url.toURI())
					.method("HEAD", HttpRequest.BodyPublishers.noBody())
					.build();
			HttpHeaders headers = client.send(r, HttpResponse.BodyHandlers.ofInputStream()).headers();
			return Long.parseLong(headers
					.firstValue("content-length").orElseThrow(() -> new FriendlyException("Unknown content length")));
		}catch(IOException | URISyntaxException | InterruptedException e) {
			throw new FriendlyException("Failed to create or open connection", e);
		}
	}
	
	public static long getContentLength(TorCircuit circuit, String url) throws FriendlyException {
		try {
			return getContentLength(circuit, new URL(url));
		} catch (MalformedURLException e) {
			throw new FriendlyException(e);
		}
	}
	
	public static RetryingInputStream createStableInputStream(TorCircuit circuit, URL url) throws FriendlyException {
		InputStream initialInput = createStream(circuit, url);
		Function<Long, InputStream> newInputFct = newInput(circuit, url, 0, -1);
		return new RetryingInputStream(initialInput, newInputFct);
	}
	
	public static RetryingInputStream createStableInputStream(TorCircuit circuit, String url) throws FriendlyException {
		try {
			return createStableInputStream(circuit, new URL(url));
		} catch (MalformedURLException e) {
			throw new FriendlyException(e);
		}
	}
	
	public static RetryingInputStream createStableInputStream(TorCircuit circuit, URL url, long rangeStart, long rangeEnd) throws FriendlyException {
		InputStream initialInput = createStream(circuit, url, rangeStart, rangeEnd);
		Function<Long, InputStream> newInputFct = newInput(circuit, url, rangeStart, rangeEnd);
		return new RetryingInputStream(initialInput, newInputFct);
	}
	
	public static RetryingInputStream createStableInputStream(TorCircuit circuit, String url, long rangeStart, long rangeEnd) throws FriendlyException {
		try {
			return createStableInputStream(circuit, new URL(url), rangeStart, rangeEnd);
		} catch (MalformedURLException e) {
			throw new FriendlyException(e);
		}
	}
	
	private static Function<Long, InputStream> newInput(TorCircuit circuit, URL url, long rangeStart, long rangeEnd) {
		return offset -> {
			try {
				return tryMultiple(() -> createStream(circuit, url, rangeStart + offset, rangeEnd), 5);
			} catch (Exception e) {
				circuit.restart();
				circuit.awaitState(CircuitState.RUNNING);
				try {
					return tryMultiple(() -> createStream(circuit, url, rangeStart + offset, rangeEnd), 5);
				} catch (Exception e1) {
					throw new FriendlyException("Failed to reestablish connection", e1);
				}
			}
		};
	}
	
	public static <T> T tryMultiple(Callable<T> call, int maxTries) throws Exception {
		int n = 0;
		while(n++ < maxTries) {
			try {
				return call.call();
			}catch(Exception e) {
				if(n == maxTries) throw e;
			}
		}
		throw new FriendlyException("This error shouldn't ever happen");
	}
	
}
