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
import java.net.ServerSocket;
import java.net.URL;
import java.util.concurrent.TimeUnit;

import me.mrletsplay.mrcore.io.IOUtils;
import me.mrletsplay.mrcore.misc.FriendlyException;

public class TorCircuit {
	
	private static String torPath = "tor";
	
	private File circuitFolder;
	private String host;
	private int port;
	private Proxy socksProxy;
	private boolean isDefault, verbose;
	private CircuitState state;
	private Process instanceProcess;
	
	private TorCircuit(File circuitFolder, String host, int port, boolean isDefault) {
		if(!isDefault && !ensureOpen(host, port)) throw new FriendlyException("Address is not open");
		this.circuitFolder = circuitFolder;
		this.host = host;
		this.port = port;
		this.socksProxy = new Proxy(Type.SOCKS, new InetSocketAddress(host, port));
		this.isDefault = isDefault;
		this.state = CircuitState.STOPPED;
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
	
	public void start() {
		if(isDefault) throw new UnsupportedOperationException("Circuit is default circuit");
		if(isRunning() || isStarting()) return;
		state = CircuitState.STARTING;
		new Thread(this::start0, "Start-Tor-Circuit_" + host + "-" + port).start();
	}
	
	private void start0() throws FriendlyException {
		circuitFolder.mkdirs();
		File torRCFile = new File(circuitFolder, "torrc");
		IOUtils.createFile(torRCFile);
		ProcessBuilder pb = new ProcessBuilder(
					torPath,
					"-f",
					torRCFile.getAbsolutePath(),
					"--DataDirectory",
					circuitFolder.getAbsolutePath(),
					"--SocksPort",
					String.valueOf(port)
				);
		
		if(verbose) {
			pb.redirectOutput(Redirect.INHERIT);
			pb.redirectError(Redirect.INHERIT);
		}
		
		try {
			int nTries = 5;
			while(nTries-- > 0) {
				instanceProcess = pb.start();
				
				try {
					Thread.sleep(5000); // Wait for Tor to start
				} catch (InterruptedException e) {
					throw new FriendlyException(e);
				}
				
				if(!connectionTest()) {
					stop0();
				}else {
					Runtime.getRuntime().addShutdownHook(new Thread(this::stop0));
					state = CircuitState.RUNNING;
					return;
				}
				
			}
			state = CircuitState.STOPPED;
			throw new FriendlyException("Failed to start tor circuit after 5 tries");
		} catch (IOException e) {
			state = CircuitState.STOPPED;
			throw new FriendlyException("Failed to start tor circuit", e);
		}
	}
	
	public void stop() {
		if(isDefault) throw new UnsupportedOperationException("Circuit is default circuit");
		if(!isRunning()) return;
		stop0();
		IOUtils.deleteFile(circuitFolder);
	}
	
	private void stop0() {
		if(!instanceProcess.isAlive()) return;
		instanceProcess.destroy();
		try {
			if(!instanceProcess.waitFor(10, TimeUnit.SECONDS)) instanceProcess.destroyForcibly();
		} catch (InterruptedException e) {
			throw new FriendlyException(e);
		}finally {
			IOUtils.deleteFile(circuitFolder);
		}
	}
	
	public void restart() {
		if(isDefault) throw new UnsupportedOperationException("Circuit is default circuit");
		if(isStarting()) return;
		boolean needsStop = isRunning();
		state = CircuitState.RESTARTING;
		if(needsStop) stop0();
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
	
	public boolean connectionTest() {
		try {
			HttpURLConnection con = createConnection("https://google.com");
			con.connect();
			con.disconnect();
			return true;
		} catch (Exception e) {
			e.printStackTrace();
			return false;
		}
	}
	
	public HttpURLConnection createConnection(URL url) throws FriendlyException {
		try {
			return (HttpURLConnection) url.openConnection(socksProxy);
		} catch (IOException e) {
			throw new FriendlyException("Failed to open connection", e);
		}
	}
	
	public HttpURLConnection createConnection(String url) throws FriendlyException {
		try {
			return createConnection(new URL(url));
		} catch (MalformedURLException e) {
			throw new FriendlyException("Failed to open connection", e);
		}
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
