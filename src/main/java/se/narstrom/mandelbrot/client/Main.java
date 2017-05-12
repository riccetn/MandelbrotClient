package se.narstrom.mandelbrot.client;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

public class Main {
	private static List<HttpClient> clients = new ArrayList<>();
	private static WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, 1000, 1000, 1, null);

	public static void main(final String[] args) throws IOException {
		Selector selector = Selector.open();
		clients.add(HttpClient.open("localhost", 8080, "/MandelbrotServer/mandelbrot/-2/-1.5/1/0/1000/500/1024", selector, Main::doneCallback, 0));
		clients.add(HttpClient.open("localhost", 8080, "/MandelbrotServer/mandelbrot/-2/0/1/1.5/1000/500/1024", selector, Main::doneCallback, 1));
		while(!clients.isEmpty()) {
			selector.select();
			for(SelectionKey key : selector.selectedKeys()) {
				HttpClient client = (HttpClient)key.attachment();
				client.selected(key);
			}
		}

		BufferedImage img = new BufferedImage(1000, 1000, BufferedImage.TYPE_BYTE_GRAY);
		img.setData(raster);
		ImageIO.write(img, "png", new File("mandelbrot.png"));
	}

	private static void doneCallback(HttpClient client, List<ByteBuffer> buffers, Object uobj) {
		clients.remove(client);

		int index = (Integer)uobj;

		Charset ascii = Charset.forName("US-ASCII");
		StringBuilder sb = new StringBuilder();
		for(ByteBuffer bBuf : buffers)
			sb.append(ascii.decode(bBuf));

		String strs[] = sb.toString().split("\\s+");
		if(!strs[0].equals("P2"))
			throw new RuntimeException("Unrecognized data returned by server");
		int width = Integer.parseInt(strs[1]);
		int height = Integer.parseInt(strs[2]);
		if(width != 1000 || height != 500)
			throw new RuntimeException("Wrong resolution returned by server");
		if(!strs[3].equals("256"))
			throw new RuntimeException("Unrecognized data returned by server");

		int rowStride = 1000;
		for(int row = 0; row < 500; ++row) {
			for(int col = 0; col < 1000; ++col) {
				raster.setSample(col, index*500 + row, 0, Integer.parseInt(strs[4 + row*rowStride + col]));
			}
		}
	}
}
