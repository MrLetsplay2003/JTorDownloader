package me.mrletsplay.jtordl._test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import me.mrletsplay.jtordl.JTorDownloader;
import me.mrletsplay.jtordl.circuit.CircuitState;
import me.mrletsplay.jtordl.circuit.TorCircuit;
import me.mrletsplay.mrcore.io.IOUtils;

public class JTorDlMain {

	public static void main(String[] args) throws IOException {
		TorCircuit c = new TorCircuit(new File("/home/mr/Desktop/mytorinstance"));
		c.setVerbose(true);
		
		c.start();
		
		c.awaitState(CircuitState.RUNNING);
		System.out.println("Circuit is running");

		InputStream in = JTorDownloader.createStableInputStream(c, "https://st1x.cdnfile.info/user1342/04040aa13712fde8821ad2591df5555d/EP.1.mp4?token=rYh5b0d7BNJI-mRf0HQ-Ng&expires=1573496918&id=71364&title=(orginalP%20-%20mp4)%20Re%3AZero+kara+Hajimeru+Isekai+Seikatsu+Episode+1");
		System.out.println(new String(IOUtils.readAllBytes(in)));
	}
	
}
