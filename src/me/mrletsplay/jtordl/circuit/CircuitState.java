package me.mrletsplay.jtordl.circuit;

public enum CircuitState {

	RESTARTING(true),
	STARTING(true),
	RUNNING(true),
	STOPPING(false),
	STOPPED(false),
	CRASHED(false),
	EXITED(false),
	;
	
	private final boolean isRunningState;
	
	private CircuitState(boolean isRunningState) {
		this.isRunningState = isRunningState;
	}
	
	public boolean isRunningState() {
		return isRunningState;
	}

}
