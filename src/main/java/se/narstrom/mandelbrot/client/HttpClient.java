package se.narstrom.mandelbrot.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A single session with the server.
 *
 * @author Rickard Närström &lt;rickard@narstrom.se&gt;
 */
public class HttpClient {
	/**
	 * The state of the connection.
	 */
	private static enum State {
		/** Is establishing a TCP connection */
		CONNECTING,
		/** Is sending HTTP headers to peer */
		SENDING,
		/** Is receiving HTTP headers from peer */
		READING_HEADERS,
		/** Is receiving the payload from peer */
		READING_BODY,
		/** Connection is closed, all data downloaded */
		CLOSED
	}

	/**
	 * Type of callback that will be called when data has been downloaded and the connection is closing.
	 */
	@FunctionalInterface
	public static interface DoneCallback {
		public abstract void done(HttpClient client, List<ByteBuffer> buffers, Object uobj);
	}

	private static final String USER_AGENT = "MandelbrotClient/0.0.1";

	private State state = State.CONNECTING;
	private SocketChannel socket;
	private DoneCallback doneCallback;
	private Object uobj;

	private ByteBuffer bSendBuf;

	private ByteBuffer bRecvBuf;
	private ByteBuffer bHeaderBuf;
	private List<ByteBuffer> bRecvBuffers;

	private int status;
	private String statusMessage;
	private Map<String, String> headers;

	private HttpClient(SocketChannel socket, ByteBuffer bSendBuf, DoneCallback doneCallback, Object uobj) {
		this.socket = socket;
		this.bSendBuf = bSendBuf;
		this.doneCallback = doneCallback;
		this.uobj = uobj;
	}

	/**
	 * Call when this is selected by a call to {@link Selection#select()} or equivalent.
	 *
	 * @param key			the selection key associated with this connection.
	 * @throws IOException	if an error occurred when reading or writing to the socket.
	 */
	public void selected(SelectionKey key) throws IOException {
		if(this.state == State.CONNECTING && key.isConnectable() && socket.finishConnect())
			this.onConnected(key);
		if(this.state == State.SENDING && key.isWritable())
			this.onWrite(key);
		if(this.state == State.READING_HEADERS && key.isReadable())
			this.onReadHeaders(key);
		if(this.state == State.READING_BODY && key.isReadable())
			this.onReadBody(key);
	}

	/**
	 * Creates a new connection and initialise a download with a remote host.
	 *
	 * @param remoteHostName		domain name of the remote host
	 * @param remotePort			TCP port number to connect to on the remote host
	 * @param requestUri			HTTP request URI of the resource to download
	 * @param selector				to use to multiplex this connection with others
	 * @param doneCallback			callback that will be called when resource is completely downloaded
	 * @param uobj					user object reference that are passed to callback.
	 * @return						the HttpClient that handles this download
	 * @throws IOException			if an error occurred opening the socket
	 */
	public static HttpClient open(String remoteHostName, int remotePort, String requestUri, Selector selector, DoneCallback doneCallback, Object uobj) throws IOException {
		SocketChannel socket = SocketChannel.open();
		socket.configureBlocking(false);

		Charset ascii = Charset.forName("US-ASCII");
		CharBuffer chSendBuf = CharBuffer.allocate(4096);
		chSendBuf.put("GET " + requestUri + " HTTP/1.1\r\n");
		if(remotePort == 80)
			chSendBuf.put("Host: ").put(remoteHostName).put("\r\n");
		else
			chSendBuf.put("Host: ").put(remoteHostName).put(':').put(String.valueOf(remotePort)).put("\r\n");
		chSendBuf.put("User-Agent: ").put(HttpClient.USER_AGENT).put("\r\n");
		chSendBuf.put("Connection: close\r\n"); // I'm not willing to support keep-alive
		chSendBuf.put("\r\n");
		chSendBuf.flip();

		HttpClient client = new HttpClient(socket, ascii.encode(chSendBuf), doneCallback, uobj);

		SelectionKey key = socket.register(selector, SelectionKey.OP_CONNECT, client);
		if(socket.connect(new InetSocketAddress(remoteHostName, remotePort)))
			client.onConnected(key);

		return client;
	}

	private void onConnected(SelectionKey key) {
		key.interestOps(SelectionKey.OP_WRITE);
		this.state = State.SENDING;
	}

	private void onWrite(SelectionKey key) throws IOException {
		while(this.bSendBuf.hasRemaining() && this.socket.write(this.bSendBuf) != 0);
		if(!this.bSendBuf.hasRemaining()) {
			key.interestOps(SelectionKey.OP_READ);
			this.state = State.READING_HEADERS;
			this.bSendBuf = null;
			this.bRecvBuf = ByteBuffer.allocate(4096);
			this.bHeaderBuf = bRecvBuf.asReadOnlyBuffer();
		}
	}

	private void onReadHeaders(SelectionKey key) throws IOException {
		while(this.socket.read(bRecvBuf) != 0);
		bHeaderBuf.limit(bRecvBuf.limit());
		while(bHeaderBuf.remaining() >= 4) {
			if(bHeaderBuf.get() == '\r' && bHeaderBuf.get() == '\n' && bHeaderBuf.get() == '\r' && bHeaderBuf.get() == '\n') {
				onHeadersReceived(key);
				return;
			}
		}
	}

	private void onHeadersReceived(SelectionKey key) throws IOException {
		this.state = State.READING_BODY;

		this.bRecvBuf.flip();
		this.bRecvBuf.position(bHeaderBuf.position());
		ByteBuffer newRecvBuf = ByteBuffer.allocate(4096);
		newRecvBuf.put(bRecvBuf);
		this.bRecvBuf = newRecvBuf;

		this.bRecvBuffers = new ArrayList<>();
		this.bRecvBuffers.add(bRecvBuf);

		bHeaderBuf.flip();
		CharBuffer chHeaderBuf = Charset.forName("US-ASCII").decode(bHeaderBuf);
		bHeaderBuf = null;
		this.parseHeaders(chHeaderBuf);
	}

	private void parseHeaders(CharBuffer chHeaderBuf) throws IOException {
		String[] hdrStrings = chHeaderBuf.toString().split("\r\n");
		if(!hdrStrings[0].startsWith("HTTP/1.1 "))
			throw new IOException("Malformed HTTP responce or unsupported HTTP version");
		this.status = Integer.parseInt(hdrStrings[0].substring(9, 12));
		this.statusMessage = hdrStrings[0].substring(13);

		if(this.status != 200)
			throw new IOException("Unexpected HTTP status code: " + this.status + " " + this.statusMessage);

		this.headers = new HashMap<>();
		for(int i = 1; i < hdrStrings.length-1; ++i) {
			String hdrString = hdrStrings[i];
			int colon = hdrString.indexOf(':');
			this.headers.put(hdrString.substring(0, colon), hdrString.substring(colon+1).trim());
		}
		assert hdrStrings[hdrStrings.length-1].equals("");
	}

	private void onReadBody(SelectionKey key) throws IOException {
		int nRecved;
		while((nRecved = this.socket.read(this.bRecvBuf)) > 0);
		if(nRecved == -1) {
			/* Peer closed the socket, we have received all data :) */
			this.state = State.CLOSED;
			key.cancel();
			this.bRecvBuf.flip();
			this.bRecvBuf = null;

			this.doneCallback.done(this, this.bRecvBuffers, this.uobj);
			return;
		}
		if(!this.bRecvBuf.hasRemaining()) {
			this.bRecvBuf.flip();
			this.bRecvBuf = ByteBuffer.allocate(4096);
			this.bRecvBuffers.add(this.bRecvBuf);
		}
	}
}
