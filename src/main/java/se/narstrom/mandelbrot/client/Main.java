package se.narstrom.mandelbrot.client;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

public class Main {
	private static boolean done = false;

	public static void main(final String[] args) throws IOException {
		Selector selector = Selector.open();
		HttpClient.open("localhost", 8080, "/MandelbrotServer/mandelbrot/-2/-1.5/1/1.5/1000/1000/1024", selector, Main::doneCallback);
		while(!done) {
			selector.select();
			for(SelectionKey key : selector.selectedKeys()) {
				HttpClient client = (HttpClient)key.attachment();
				client.selected(key);
			}
		}
	}

	private static void doneCallback(HttpClient client) {
		done = true;
	}
}
