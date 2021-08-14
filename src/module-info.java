module jtordownloader {
	exports me.mrletsplay.jtordl;
	exports me.mrletsplay.jtordl.circuit;
	exports me.mrletsplay.jtordl.io;

	requires transitive mrcore;
	requires transitive java.net.http;
}