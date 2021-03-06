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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import me.mrletsplay.mrcore.io.IOUtils;
import me.mrletsplay.mrcore.misc.FriendlyException;

public class TorCircuit {
	
	private static String torPath = "tor";
	
	private File circuitFolder;
	private String host;
	private int port;
	private Proxy socksProxy;
	private boolean isDefault, verbose, printTorOutput;
	private CircuitState state;
	private Process instanceProcess;
	private Map<String, String> defaultRequestProperties;
	
	private TorCircuit(File circuitFolder, String host, int port, boolean isDefault) {
		if(!isDefault && !ensureOpen(host, port)) throw new FriendlyException("Address is not open");
		this.circuitFolder = circuitFolder;
		this.host = host;
		this.port = port;
		this.socksProxy = new Proxy(Type.SOCKS, new InetSocketAddress(host, port));
		this.isDefault = isDefault;
		this.state = isDefault ? CircuitState.RUNNING : CircuitState.STOPPED;
		this.defaultRequestProperties = new LinkedHashMap<>();
	}
	
	public TorCircuit(File circuitFolder, String host, int port) {
		this(circuitFolder, host, port, false);
	}
	
	public TorCircuit(File circuitFolder, int port) {
		this(circuitFolder, "127.0.0.1", port);
	}
	
	public TorCircuit(File circuitFolder, String host) {
		this(circuitFolder, "127.0.0.1", getFreePort(host));
	}
	
	public TorCircuit(File circuitFolder) {
		this(circuitFolder, "127.0.0.1");
	}
	
	public String getHost() {
		return host;
	}
	
	public int getPort() {
		return port;
	}
	
	public Proxy getSocksProxy() {
		return socksProxy;
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
	
	public void setDefaultRequestProperties(Map<String, String> defaultRequestProperties) {
		this.defaultRequestProperties = defaultRequestProperties;
	}
	
	public void addDefaultRequestProperty(String key, String value) {
		this.defaultRequestProperties.put(key, value);
	}
	
	public Map<String, String> getDefaultRequestProperties() {
		return defaultRequestProperties;
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
						String.valueOf(port)
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
					if(connectionTest()) {
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
			HttpURLConnection con = (HttpURLConnection) url.openConnection(socksProxy);
			defaultRequestProperties.forEach(con::setRequestProperty);
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

	public HttpClient createHttpClient() {
		HttpClient client = HttpClient.newBuilder()
				.proxy(new ProxySelector() {
					
					@Override
					public List<Proxy> select(URI uri) {
						return Arrays.asList(getSocksProxy());
					}
					
					@Override
					public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
						throw new FriendlyException("Failed to connect", ioe);
					}
				})
				.followRedirects(java.net.http.HttpClient.Redirect.ALWAYS)
				.version(Version.HTTP_2)
				.build();
		
		return client;
	}
	
	public static TorCircuit attachDefault(String host, int port) {
		return new TorCircuit(null, host, port, true);
	}
	
	public static TorCircuit attachDefault(int port) {
		return attachDefault("127.0.0.1", port);
	}
	
	public static void setTorPath(String torPath) {
		TorCircuit.torPath = torPath;
	}
	
	private static boolean ensureOpen(String host, int port) {
		try(ServerSocket ss = new ServerSocket(port, 0, InetAddress.getByName(host))){
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
