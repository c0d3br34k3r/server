package com.catascopic.gateway;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.io.ByteStreams;

/**
 * Reads HTTP content from an InputStream.
 */
public class HttpReader {

	private InputStream in;

	public HttpReader(InputStream in) {
		this.in = in;
	}

	public String readLine() throws IOException {
		StringBuilder builder = new StringBuilder();
		for (;;) {
			int b = in.read();
			switch (b) {
			case '\r':
				if (in.read() != '\n') {
					throw new IOException();
				}
				return builder.toString();
			case -1:
				throw new EOFException();
			default:
				builder.append((char) b);
			}
		}
	}
	
	public Map<String, String> parseHeaders() throws IOException {
		Builder<String, String> builder = ImmutableMap.builder();
		for (;;) {
			String line = readLine();
			if (line.isEmpty()) {
				break;
			}
			int separator = line.indexOf(':');
			builder.put(line.substring(0, separator),
					line.substring(separator + 1).trim());
		}
		return builder.build();
	}

	public InputStream streamContent(int count) {
		return new ContentInputStream(count);
	}

	public InputStream streamChunked() throws IOException {
		return new ChunkedInputStream();
	}

	public class ContentInputStream extends InputStream {

		private long remaining;

		ContentInputStream(long limit) {
			this.remaining = limit;
		}

		@Override
		public int available() throws IOException {
			return (int) Math.min(in.available(), remaining);
		}

		@Override
		public int read() throws IOException {
			if (remaining == 0) {
				return -1;
			}
			int result = in.read();
			if (result != -1) {
				remaining--;
			}
			return result;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (remaining == 0) {
				return -1;
			}
			int result = in.read(b, off, (int) Math.min(len, remaining));
			if (result != -1) {
				remaining -= result;
			}
			return result;
		}

		@Override
		public long skip(long n) throws IOException {
			long skipped = in.skip(Math.min(n, remaining));
			remaining -= skipped;
			return skipped;
		}

		@Override
		public void close() throws IOException {
			ByteStreams.skipFully(in, remaining);
			remaining = 0;
		}
	}

	private class ChunkedInputStream extends InputStream {

		private long remaining;
		private boolean end; // = false

		public ChunkedInputStream() throws IOException {
			this.remaining = Integer.parseInt(readLine(), 16);
		}

		@Override
		public int read() throws IOException {
			if (end) {
				return -1;
			}
			if (remaining == 0) {
				nextChunk();
				return read();
			}
			remaining--;
			return in.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len == 0) {
				return 0;
			}
			if (end) {
				return -1;
			}
			if (remaining == 0) {
				nextChunk();
				return read(b, off, len);
			}
			int read = in.read(b, off, (int) Math.min(len, remaining));
			remaining -= read;
			return read;
		}

		@Override
		public long skip(long n) throws IOException {
			long skipped = in.skip(Math.min(n, remaining));
			remaining -= skipped;
			return skipped;
		}

		@Override
		public int available() throws IOException {
			return (int) Math.min(in.available(), remaining);
		}

		@Override
		public void close() throws IOException {
			while (!end) {
				ByteStreams.skipFully(in, remaining);
				nextChunk();
			}
		}

		private void nextChunk() throws IOException {
			readCrlf();
			remaining = Integer.parseInt(readLine(), 16);
			if (remaining == 0) {
				readCrlf();
				end = true;
			}
		}

		private void readCrlf() throws IOException {
			int cr = in.read();
			int lf = in.read();
			if (cr != '\r' || lf != '\n') {
				throw new IOException();
			}
		}
	}

}
