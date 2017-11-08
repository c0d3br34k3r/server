package gateway.websocket;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;

import com.google.common.io.ByteStreams;

public abstract class Websocket implements Runnable {

	/**
	 * Override this method to return the OutputStream.
	 * 
	 * @return the OutputStream for writing messages
	 * @throws IOException if an I/O error occurs while getting the OutputStream
	 */
	protected abstract OutputStream getOutputStream() throws IOException;

	/**
	 * Override this method to return the InputStream.
	 * 
	 * @return the InputStream for reading messages.
	 * @throws IOException if an I/O error occurs while getting the InputStream
	 */
	protected abstract InputStream getInputStream() throws IOException;

	/**
	 * This method is called when a close message has been both sent and
	 * received (in either order). This method must close the underlying
	 * connection.
	 * 
	 * @throws IOException
	 */
	protected abstract void closeConnection() throws IOException;

	/**
	 * This method is called when a message containing text is received.
	 * 
	 * @param text the text content of the message
	 */
	protected void onMessage(String text) {}

	/**
	 * This method is called when a message containing binary data is received.
	 * 
	 * @param bytes the binary content of the message
	 */
	protected void onMessage(byte[] bytes) {}

	/**
	 * This method is called when a close message is received.
	 * 
	 * @param code the close code, or 1005 if no close code was present
	 * @param message the close message, or the empty String if no close message
	 *        was present
	 */
	protected void onClose(int code, String message) {}

	protected void onPong(byte[] payload) {}

	// Opcodes
	public static final int CONTINUATION = 0x0;
	public static final int TEXT = 0x1;
	public static final int BINARY = 0x2;
	public static final int CLOSE = 0x8;
	public static final int PING = 0x9;
	public static final int PONG = 0xA;

	private static final int FIN_BIT = 0x80;
	private static final int OPCODE_MASK = 0xF;
	private static final int LENGTH_MASK = 0x7F;

	private static final int MASKS_LENGTH = 4;

	// In case of small length, the code is the length.
	private static final int MID_LENGTH_CODE = 126;
	private static final int LARGE_LENGTH_CODE = 127;

	// No extra bytes used for small message
	private static final int MID_LENGTH_BYTES = 2;
	private static final int LARGE_LENGTH_BYTES = 8;

	private static final int SMALL_MESSAGE_MAX_SIZE = 125;
	private static final int MID_MESSAGE_MAX_SIZE = 65535;
	// No real max for large messages

	private static final int NO_STATUS_CODE = 1005;

	private static final int DEFAULT_BUFFER_SIZE = 0x1000;

	private CharsetOutputStream currentMessage = new CharsetOutputStream();
	private boolean inProgress; // = false
	private int messageType;

	private boolean sentClose; // = false

	private final Object writeLock = new Object();

	@Override
	public void run() {
		for (;;) {
			try {
				InputStream in = getInputStream();
				int finOpcode = in.read();
				if (finOpcode == -1) {
					throw new IOException();
				}
				boolean fin = (finOpcode & FIN_BIT) == FIN_BIT;
				int opcode = finOpcode & OPCODE_MASK;
				int payloadSize = readLength(in);
				byte[] masks = readBytes(in, MASKS_LENGTH);
				byte[] payload = readBytes(in, payloadSize);
				for (int i = 0; i < payloadSize; i++) {
					payload[i] ^= masks[i % MASKS_LENGTH];
				}
				if (!inProgress && opcode == CONTINUATION) {
					throw new WebsocketProtocolException("unstarted continuation");
				}
				if (fin) {
					handleFinished(opcode, payload);
				} else {
					handleUnfinished(opcode, payload);
				}
				if (opcode == CLOSE) {
					break;
				}
			} catch (WebsocketProtocolException e) {
				try {
					sendClose(1002, e.getMessage());
					handleException(e);
				} catch (IOException e1) {
					handleException(e1);
				}
			} catch (IOException e) {
				handleException(e);
			}
		}
	}

	private void handleException(IOException e) {
		// TODO Auto-generated method stub

	}

	private void handleException(WebsocketProtocolException e) {
		// TODO Auto-generated method stub

	}

	/**
	 * Reads a frame from the InputStream, and handles it appropriately,
	 * depending on the opcode and the fin bit.
	 * <ul>
	 * <li>If the frame is a text, binary, or continuation frame with the fin
	 * bit set, {@link #onMessage(String)} or {@link #onMessage(byte[])} is
	 * called appropriately with the message contents.
	 * <li>If the frame is a text, binary, or continuation frame with the fin
	 * bit not set, the payload is written to the message buffer for later use.
	 * <li>If the frame is a close frame, then this Websocket sends a close
	 * frame in response, and calls {@link #onClose(int, String)} with the
	 * received close code and message.
	 * <li>If the frame is a ping frame, then this Websocket sends a pong frame
	 * in response.
	 * <li>If the frame is a pong frame, nothing happens.
	 * </ul>
	 * 
	 * @throws IOException if an I/O error occurs
	 * @throws WebsocketProtocolException if any of the following occur:
	 *         <ul>
	 *         <li>An unknown opcode is received
	 *         <li>A continuation frame is received and no message is in
	 *         progress
	 *         <li>A text or binary frame is received and a message is already
	 *         in progress
	 *         <li>A ping, pong, or close frame without the fin bit set is
	 *         received
	 *         </ul>
	 */
	private final void handleNextMessage() throws IOException {

	}

	private void handleUnfinished(int opcode, byte[] payload) throws IOException {
		switch (opcode) {
			case TEXT:
			case BINARY:
				beginMessage(opcode);
				break;
			case CONTINUATION:
				break;
			default:
				throw new WebsocketProtocolException("bad opcode: " + opcode);
		}
		inProgress = true;
		currentMessage.write(payload);
	}

	private void handleFinished(int opcode, byte[] payload) throws IOException {
		switch (opcode) {
			case CONTINUATION:
				handleFinalContinuation(payload);
				break;
			case TEXT:
				onMessage(new String(payload, UTF_8));
				break;
			case BINARY:
				onMessage(payload);
				break;
			case PING:
				handlePing(payload);
				break;
			case PONG:
				onPong(payload);
				break;
			case CLOSE:
				handleClose(payload);
				break;
			default:
				throw new WebsocketProtocolException("bad opcode: " + opcode);
		}
		inProgress = false;
	}

	private void beginMessage(int type) throws IOException {
		if (inProgress) {
			throw new WebsocketProtocolException("message in progress");
		}
		this.messageType = type;
	}

	private void handleFinalContinuation(byte[] payload) throws IOException {
		currentMessage.write(payload);
		if (messageType == TEXT) {
			onMessage(currentMessage.toString());
		} else {
			onMessage(currentMessage.toByteArray());
		}
		currentMessage.reset();
	}

	private void handleClose(byte[] payload) throws IOException {
		synchronized (writeLock) {
			if (!sentClose) {
				sendClose(payload);
			}
			closed = true;
			closeConnection();
		}
		int code;
		String message;
		if (payload.length >= 2) {
			code = ((payload[0] & 0xFF) << Byte.SIZE) | (payload[1] & 0xFF);
			message = new String(payload, 2, payload.length - 2, UTF_8);
		} else {
			code = NO_STATUS_CODE;
			message = "";
		}
		onClose(code, message);
	}

	private void handlePing(byte[] payload) throws IOException {
		sendMessage(PONG, payload);
	}

	private static int readLength(InputStream in) throws IOException {
		int lengthCode = in.read() & LENGTH_MASK;
		switch (lengthCode) {
			case MID_LENGTH_CODE:
				return readInt(in, MID_LENGTH_BYTES);
			case LARGE_LENGTH_CODE:
				return readInt(in, LARGE_LENGTH_BYTES);
			default:
				return lengthCode;
		}
	}

	private static int readInt(InputStream in, int byteCount) throws IOException {
		int result = 0;
		for (int i = 0; i < byteCount; i++) {
			result |= in.read() << (Byte.SIZE * (byteCount - i - 1));
		}
		return result;
	}

	private static byte[] readBytes(InputStream in, int count) throws IOException {
		byte[] buf = new byte[count];
		ByteStreams.readFully(in, buf);
		return buf;
	}

	// Write methods

	/**
	 * Sends a single-frame text message with the given text.
	 * 
	 * @param message the text content of the message
	 * @throws IOException if an I/O error occurs while sending the message
	 * @throws ConnectionClosedException if the connection is closed
	 */
	public final void send(String message) throws IOException {
		sendMessage(TEXT, message.getBytes(UTF_8));
	}

	/**
	 * Sends a single-frame binary message with the given data.
	 * 
	 * @param message the binary content of the message
	 * @throws IOException if an I/O error occurs while sending the message
	 * @throws ConnectionClosedException if the connection is closed
	 */
	public final void send(byte[] message) throws IOException {
		sendMessage(BINARY, message);
	}

	public final void send(InputStream in) throws IOException {
		PushbackInputStream pushback = new PushbackInputStream(in, 1);
		byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
		boolean fin = sendFrame(pushback, buffer, BINARY);
		while (!fin) {
			fin = sendFrame(pushback, buffer, CONTINUATION);
		}
	}

	private boolean sendFrame(PushbackInputStream pushback, byte[] buffer, int opcode)
			throws IOException {
		int read = pushback.read(buffer);
		int next = pushback.read();
		boolean fin = next == -1;
		if (!fin) {
			pushback.unread(next);
		}
		sendFrame(fin, opcode, buffer, 0, read);
		return fin;
	}

	/**
	 * Sends a close message with no close code and no close message.
	 * 
	 * @throws IOException if an I/O error occurs while sending the close
	 *         message
	 * @throws ConnectionClosedException if the connection is already closed
	 */
	public final void sendClose() throws IOException {
		sendClose(new byte[0]);
	}

	/**
	 * Sends a close message with the given close code and no close message.
	 * 
	 * @param code the close code
	 * @throws IOException if an I/O error occurs while sending the close
	 *         message
	 * @throws ConnectionClosedException if the connection is already closed
	 */
	public final void sendClose(int code) throws IOException {
		sendClose(code, "");
	}

	/**
	 * Sends a close message with the given close code and close message.
	 * 
	 * @param code the close code
	 * @param message the close message
	 * @throws IOException if an I/O error occurs while sending the close
	 *         message
	 * @throws ConnectionClosedException if the connection is already closed
	 */
	public final void sendClose(int code, String message) throws IOException {
		byte[] messageBytes = message.getBytes(UTF_8);
		byte[] payloadBytes = new byte[2 + messageBytes.length];
		payloadBytes[0] = (byte) ((code & 0xFF00) >> Byte.SIZE);
		payloadBytes[1] = (byte) (code & 0xFF);
		System.arraycopy(messageBytes, 0, payloadBytes, 2, messageBytes.length);
		sendClose(payloadBytes);
	}

	private void sendClose(byte[] message) throws IOException {
		sendMessage(CLOSE, message);
	}

	private void sendMessage(int opcode, byte[] message) throws IOException {
		sendFrame(true, opcode, message);
	}

	private void sendFrame(boolean fin, int opcode, byte[] message) throws IOException {
		sendFrame(fin, opcode, message, 0, message.length);
	}

	private void sendFrame(boolean fin, int opcode, byte[] message, int off, int len)
			throws IOException {
		synchronized (writeLock) {
			if (sentClose || closed) {
				throw new ConnectionClosedException();
			}
			OutputStream out = getOutputStream();
			out.write((fin ? FIN_BIT : 0) | opcode);
			int lengthCode;
			int lengthBytes;
			if (message.length <= SMALL_MESSAGE_MAX_SIZE) {
				lengthCode = message.length;
				lengthBytes = 0;
			} else if (message.length <= MID_MESSAGE_MAX_SIZE) {
				lengthCode = MID_LENGTH_CODE;
				lengthBytes = MID_LENGTH_BYTES;
			} else {
				lengthCode = LARGE_LENGTH_CODE;
				lengthBytes = LARGE_LENGTH_BYTES;
			}
			out.write(lengthCode);
			writeAsBytes(out, len, lengthBytes);
			out.write(message, off, len);
			out.flush();
			if (opcode == CLOSE) {
				sentClose = true;
			}
		}
	}

	private void writeAsBytes(OutputStream out, long bytes, int byteCount) throws IOException {
		for (int i = 0; i < byteCount; i++) {
			out.write((int) (bytes >> (Byte.SIZE * (byteCount - i - 1))));
		}
	}

}
