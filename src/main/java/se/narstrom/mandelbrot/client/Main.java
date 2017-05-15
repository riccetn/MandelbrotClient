package se.narstrom.mandelbrot.client;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Application that divides the requested Mandelbrot set into smaller chunks and parallelise the generation of
 * each chunk on a separate server session, and then reassemble the chunks back into a single image.
 *
 * @author Rickard Närström &lt;rickard@narstrom.se&gt;
 */
public class Main {
	private static List<HttpClient> clients = new ArrayList<>();
	private static WritableRaster raster;

	/**
	 * Application entry point.
	 *
	 * @param args				command line arguments
	 * @throws IOException		an unrecoverable I/O error that should cause the application to terminate
	 */
	public static void main(String[] args) throws IOException {
		if(args.length < 8) {
			System.err.println("Usage: java -jar MandelbrotClient.jar real_min real_max imag_min imag_max nMaxIterations width height divisions appRootUri output");
			System.exit(1);
		}
		double realMin = Double.parseDouble(args[0]);
		double realMax = Double.parseDouble(args[1]);
		if(realMin >= realMax) {
			System.err.println("!(realMin < realMax)");
			System.exit(1);
		}
		double imagMin = Double.parseDouble(args[2]);
		double imagMax = Double.parseDouble(args[3]);
		if(imagMin >= imagMax) {
			System.err.println("!(imagMin < imagMax)");
			System.exit(1);
		}
		int nMaxIterations = Integer.parseInt(args[4]);
		int width = Integer.parseInt(args[5]);
		int height = Integer.parseInt(args[6]);
		int devisions = Integer.parseInt(args[7]);
		if(nMaxIterations < 0 || width < 0 || height < 0 || devisions < 0) {
			System.err.println("nMaxIterations < 0 || width < 0 || height < 0 || devisions < 0");
			System.exit(1);
		}
		URI appRoot = URI.create(args[8]);
		String output = args[9];

		raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE, width, height, 1, null);

		Selector selector = Selector.open();

		for(int row = 0; row < devisions; ++row) {
			for(int col = 0; col < devisions; ++col) {
				double segmentRealMin = realMin + col*(realMax - realMin)/devisions;
				double segmentRealMax = segmentRealMin + (realMax - realMin)/devisions;
				double segmentImagMax = imagMax - row*(imagMax - imagMin)/devisions;
				double segmentImagMin = segmentImagMax - (imagMax - imagMin)/devisions;
				int segmentWidth = width/devisions;
				int segmentHeight = height/devisions;
				URI segmentUri = appRoot.resolve(
						"mandelbrot/" +
						segmentRealMin + "/" +
						segmentRealMax + "/" +
						segmentImagMin + "/" +
						segmentImagMax + "/" +
						segmentWidth + "/" +
						segmentHeight + "/" +
						nMaxIterations);
				Offsets offsets = new Offsets(row*height/devisions, col*width/devisions);
				System.out.println(segmentUri + " " + offsets);
				clients.add(HttpClient.open(segmentUri.getHost(),
						segmentUri.getPort(),
						segmentUri.getPath(),
						selector,
						Main::doneCallback,
						offsets));
			}
		}

		while(!clients.isEmpty()) {
			selector.select();
			for(SelectionKey key : selector.selectedKeys())
				((HttpClient)key.attachment()).selected(key);
		}

		BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
		img.setData(raster);
		ImageIO.write(img, "png", new File(output));
	}

	private static void doneCallback(HttpClient client, List<ByteBuffer> buffers, Object uobj) {
		clients.remove(client);

		Offsets offsets = (Offsets)uobj;

		Charset ascii = Charset.forName("US-ASCII");
		StringBuilder sb = new StringBuilder();
		for(ByteBuffer bBuf : buffers)
			sb.append(ascii.decode(bBuf));

		String strs[] = sb.toString().split("\\s+");
		if(!strs[0].equals("P2"))
			throw new RuntimeException("Unrecognized data returned by server");
		int width = Integer.parseInt(strs[1]);
		int height = Integer.parseInt(strs[2]);
		if(!strs[3].equals("256"))
			throw new RuntimeException("Unrecognized data returned by server");

		System.out.println(offsets + ": " + width + " " + height);
		for(int row = 0; row < width; ++row) {
			for(int col = 0; col < height; ++col) {
				raster.setSample(offsets.left + col, offsets.top + row, 0, Integer.parseInt(strs[4 + row*width + col]));
			}
		}
	}
}
