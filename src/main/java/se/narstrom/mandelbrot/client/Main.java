package se.narstrom.mandelbrot.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;

public class Main {
	public static void main(final String[] args) throws IOException {
		//Selector selector = Selector.open();
		SocketChannel socket = SocketChannel.open(new InetSocketAddress("localhost", 8080));
		Charset ascii = Charset.forName("US-ASCII");
		{
			CharBuffer chBuf = CharBuffer.allocate(4096);
			chBuf.put("GET /MandelbrotServer/mandelbrot/-2/-1.5/1/1.5/500/500/1024 HTTP/1.1\r\n");
			chBuf.put("Host: localhost:8080\r\n");
			chBuf.put("\r\n");
			chBuf.flip();
			ByteBuffer bBuf = ascii.encode(chBuf);
			socket.write(bBuf);
		}

		{
			ByteBuffer bBuf = ByteBuffer.allocate(4096);
			socket.read(bBuf);
			bBuf.flip();
			ByteBuffer bHdrBuf = bBuf.duplicate();
			while(!(bBuf.get() == '\r' && bBuf.get() == '\n' && bBuf.get() == '\r' && bBuf.get() == '\n'));
			bHdrBuf.limit(bBuf.position());

			CharBuffer chHdrBuf = ascii.decode(bHdrBuf);
			System.out.print(chHdrBuf);
		}
	}
}
