package me.mrletsplay.jtordl.circuit;

import java.io.File;
import java.io.IOException;
import java.lang.ProcessBuilder.Redirect;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import me.mrletsplay.mrcore.io.IOUtils;
import me.mrletsplay.mrcore.misc.FriendlyException;

public class TorCircuit {
	
	private static String torPath = "tor";
	
	private File circuitFolder;
	private String host;
	private int port;
	private Proxy httpProxy;
	private HttpClient httpClient;
	private boolean isDefault, verbose, printTorOutput, preferIPv6;
	private CircuitState state;
	private Process instanceProcess;
	private Map<String, String> defaultHeaders;
	
	private TorCircuit(File circuitFolder, String host, int port, boolean isDefault, Consumer<HttpClient.Builder> builderFunction) {
		if(!isDefault && !ensureOpen(host, port)) throw new FriendlyException("Address is not open");
		this.circuitFolder = circuitFolder;
		this.host = host;
		this.port = port;
		this.httpProxy = new Proxy(Type.HTTP, new InetSocketAddress(host, port));
		HttpClient.Builder b = createHttpClientBuilder();
		if(builderFunction != null) builderFunction.accept(b);
		this.httpClient = b.build();
		this.isDefault = isDefault;
		this.state = isDefault ? CircuitState.RUNNING : CircuitState.STOPPED;
		this.defaultHeaders = new LinkedHashMap<>();
	}

	/**
	 * Creates a tor circuit with an HTTP proxy listening on the specified <code>host</code> and <code>port</code>
	 * @param circuitFolder The folder in which to store the <code>torrc</code> file as well as any session-specific files required by Tor
	 * @param host The host for the HTTP proxy to listen on
	 * @param port The port for the HTTP proxy to listen on, set to <code>-1</code> to automatically use a free port
	 * @param builderFunction A {@link Consumer} to further customize the default client provided by {@link #getHttpClient()} before it's built
	 */
	public TorCircuit(File circuitFolder, String host, int port, Consumer<HttpClient.Builder> builderFunction) {
		this(circuitFolder, host, port == -1 ? getFreePort(host) : port, false, builderFunction);
	}
	
	/**
	 * Creates a tor circuit with an HTTP proxy listening on the specified <code>host</code> and <code>port</code>
	 * @param circuitFolder The folder in which to store the <code>torrc</code> file as well as any session-specific files required by Tor
	 * @param host The host for the HTTP proxy to listen on
	 * @param port The port for the HTTP proxy to listen on, set to <code>-1</code> to automatically use a free port
	 */
	public TorCircuit(File circuitFolder, String host, int port) {
		this(circuitFolder, host, port, null);
	}
	
	/**
	 * @deprecated Use {@link #TorCircuit(File, String, int)} with a specific host instead
	 * @param circuitFolder
	 * @param port
	 */
	@Deprecated
	public TorCircuit(File circuitFolder, int port) {
		this(circuitFolder, "127.0.0.1", port);
	}

	/**
	 * @deprecated Use {@link #TorCircuit(File, String, int)} with <code>port</code> set to <code>-1</code> instead
	 * @param circuitFolder
	 * @param port
	 */
	@Deprecated
	public TorCircuit(File circuitFolder, String host) {
		this(circuitFolder, "127.0.0.1", getFreePort(host));
	}

	/**
	 * @deprecated Use {@link #TorCircuit(File, String, int)} with a specific host and <code>port</code> set to <code>-1</code> instead
	 * @param circuitFolder
	 * @param port
	 */
	@Deprecated
	public TorCircuit(File circuitFolder) {
		this(circuitFolder, "127.0.0.1");
	}
	
	public String getHost() {
		return host;
	}
	
	public int getPort() {
		return port;
	}
	
	/**
	 * The proxy is no longer a SOCKS proxy. Use {@link #getHttpProxy()} instead
	 * @return
	 */
	@Deprecated
	public Proxy getSocksProxy() {
		return httpProxy;
	}
	
	public Proxy getHttpProxy() {
		return httpProxy;
	}
	
	public Process getInstanceProcess() {
		return instanceProcess;
	}
	
	public boolean isDefault() {
		return isDefault;
	}
	
	public boolean isRunning() {
		return state.isRunningState();
	}
	
	public boolean isStarting() {
		return state.ordinal() < CircuitState.RUNNING.ordinal();
	}
	
	public CircuitState getState() {
		return state;
	}
	
	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}
	
	public boolean isVerbose() {
		return verbose;
	}
	
	public void setPrintTorOutput(boolean printTorOutput) {
		this.printTorOutput = printTorOutput;
	}
	
	public boolean isPrintTorOutput() {
		return printTorOutput;
	}
	
	public void setPreferIPv6(boolean preferIPv6) {
		this.preferIPv6 = preferIPv6;
	}
	
	public boolean isPreferIPv6() {
		return preferIPv6;
	}
	
	@Deprecated
	public void setDefaultRequestProperties(Map<String, String> defaultRequestProperties) {
		setDefaultHeaders(defaultRequestProperties);
	}

	@Deprecated
	public void addDefaultRequestProperty(String key, String value) {
		addDefaultHeader(key, value);
	}

	@Deprecated
	public Map<String, String> getDefaultRequestProperties() {
		return getDefaultHeaders();
	}
	
	public void setDefaultHeaders(Map<String, String> defaultRequestProperties) {
		this.defaultHeaders = defaultRequestProperties;
	}
	
	public void addDefaultHeader(String key, String value) {
		this.defaultHeaders.put(key, value);
	}
	
	public Map<String, String> getDefaultHeaders() {
		return defaultHeaders;
	}
	
	public void start() {
		if(isDefault) throw new UnsupportedOperationException("Circuit is default circuit");
		if(isRunning() || isStarting()) return;
		state = CircuitState.STARTING;
		new Thread(this::start0, "Start-Tor-Circuit_" + host + "-" + port).start();
	}
	
	private void start0() throws FriendlyException {
		if(state.equals(CircuitState.EXITED)) return;
		Runtime.getRuntime().addShutdownHook(new Thread(() ->  {
			state = CircuitState.EXITED;
			stop0(true);
		}));
		circuitFolder.mkdirs();
		File torRCFile = new File(circuitFolder, "torrc");
		IOUtils.createFile(torRCFile);
		
		try {
			int nTries = 5;
			while(nTries-- > 0) {
				ProcessBuilder pb = new ProcessBuilder(
						torPath,
						"-f",
						torRCFile.getAbsolutePath(),
						"--DataDirectory",
						circuitFolder.getAbsolutePath(),
						"--SocksPort",
						"0",
						"--HTTPTunnelPort",
						String.valueOf(port) + (preferIPv6 ? " PreferIPv6" : "")
					);
			
				if(printTorOutput) {
					pb.redirectOutput(Redirect.INHERIT);
					pb.redirectError(Redirect.INHERIT);
				}
				
				instanceProcess = pb.start();
				
				try {
					Thread.sleep(5000); // Wait for Tor to start
				} catch (InterruptedException e) {
					throw new FriendlyException(e);
				}
				
				int n = 0;
				while(n++ < 5) {
					debugLog("Trying to connect to Tor (Attempt " + n + "/5)");
					if(testConnection()) {
						debugLog("Connected successfully!");
						state = CircuitState.RUNNING;
						return;
					}
					Thread.sleep(1000);
				}
				stop0(false);
				
				debugLog("Restarting Tor");
			}
			state = CircuitState.STOPPED;
			throw new FriendlyException("Failed to start Tor circuit after 5 tries");
		} catch (IOException | InterruptedException e) {
			state = CircuitState.STOPPED;
			throw new FriendlyException("Failed to start Tor circuit", e);
		}
	}
	
	private void debugLog(String message) {
		if(verbose) System.out.println("[" + host + ":" + port + " | " + state + "] " + message);
	}
	
	public void stop() {
		if(isDefault) throw new UnsupportedOperationException("Circuit is default circuit");
		if(!isRunning()) return;
		stop0(true);
		IOUtils.deleteFile(circuitFolder);
	}
	
	private void stop0(boolean deleteFiles) {
		if(!instanceProcess.isAlive()) return;
		instanceProcess.destroy();
		try {
			if(!instanceProcess.waitFor(10, TimeUnit.SECONDS)) instanceProcess.destroyForcibly();
		} catch (InterruptedException e) {
			throw new FriendlyException(e);
		}finally {
			if(deleteFiles) IOUtils.deleteFile(circuitFolder);
		}
	}
	
	public void restart() {
		if(state.equals(CircuitState.EXITED)) return;
		if(isDefault) throw new UnsupportedOperationException("Circuit is default circuit");
		if(isStarting()) return;
		boolean needsStop = isRunning();
		state = CircuitState.RESTARTING;
		if(needsStop) stop0(true);
		new Thread(this::start0, "Restart-Tor-Circuit_" + host + "-" + port).start();
	}
	
	public void awaitState(CircuitState state) {
		if(state.ordinal() < CircuitState.RUNNING.ordinal()) throw new FriendlyException("Can't await pre-RUNNING state");
		while(this.state.ordinal() < state.ordinal()) {
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				throw new FriendlyException(e);
			}
		}
	}
	
	private boolean testConnection() {
		try {
			HttpRequest r = newRequestBuilder(new URI("https://google.com"))
					.timeout(Duration.of(5, ChronoUnit.SECONDS))
					.build();
			httpClient.send(r, HttpResponse.BodyHandlers.discarding());
			return true;
		} catch (Exception e) {
			return false;
		}
	}
	
	@Deprecated
	public boolean connectionTest() {
		try {
			HttpURLConnection con = createConnection("https://google.com");
			con.setConnectTimeout(5000);
			con.connect();
			con.disconnect();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Deprecated
	public HttpURLConnection createConnection(URL url) throws FriendlyException {
		try {
			HttpURLConnection con = (HttpURLConnection) url.openConnection(httpProxy);
			defaultHeaders.forEach(con::setRequestProperty);
			return con;
		} catch (IOException e) {
			throw new FriendlyException("Failed to open connection", e);
		}
	}

	@Deprecated
	public HttpURLConnection createConnection(String url) throws FriendlyException {
		try {
			return createConnection(new URL(url));
		} catch (MalformedURLException e) {
			throw new FriendlyException("Failed to open connection", e);
		}
	}

	@Deprecated
	public HttpClient createHttpClient() {
		return createHttpClientBuilder().build();
	}

	private HttpClient.Builder createHttpClientBuilder() {
		return HttpClient.newBuilder()
				.proxy(new ProxySelector() {
					
					@Override
					public List<Proxy> select(URI uri) {
						return Arrays.asList(getHttpProxy());
					}
					
					@Override
					public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
						throw new FriendlyException("Failed to connect", ioe);
					}
				})
				.followRedirects(HttpClient.Redirect.NORMAL)
				.version(Version.HTTP_2);
	}
	
	public HttpClient getHttpClient() {
		return httpClient;
	}
	
	private HttpRequest.Builder initializeRequestBuilder(HttpRequest.Builder requestBuilder) {
		defaultHeaders.forEach((k, v) -> requestBuilder.header(k, v));
		return requestBuilder;
	}
	
	public HttpRequest.Builder newRequestBuilder() {
		return initializeRequestBuilder(HttpRequest.newBuilder());
	}
	
	public HttpRequest.Builder newRequestBuilder(URI uri) {
		return initializeRequestBuilder(HttpRequest.newBuilder(uri));
	}
	
	public static TorCircuit attachDefault(String host, int port, Consumer<HttpClient.Builder> builderFunction) {
		return new TorCircuit(null, host, port, true, builderFunction);
	}
	
	public static TorCircuit attachDefault(String host, int port) {
		return attachDefault(host, port, null);
	}
	
	/**
	 * Use {@link #attachDefault(String, int)} with a specific host instead
	 * @param port
	 * @return
	 */
	@Deprecated
	public static TorCircuit attachDefault(int port) {
		return attachDefault("127.0.0.1", port);
	}
	
	public static void setTorPath(String torPath) {
		TorCircuit.torPath = torPath;
	}
	
	private static boolean ensureOpen(String host, int port) {
		try(ServerSocket ss = new ServerSocket(port, 1, InetAddress.getByName(host))){
			ss.close();
			return true;
		}catch(Exception e) {
			return false;
		}
	}
	
	private static int getFreePort(String host) {
		try(ServerSocket ss = new ServerSocket(0, 0, InetAddress.getByName(host))) {
			ss.close();
			return ss.getLocalPort();
		}catch(Exception e) {
			throw new FriendlyException("Couldn't get free port", e);
		}
	}

}
