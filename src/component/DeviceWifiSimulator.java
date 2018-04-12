package component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import util.Settings;
import util.Util;

//智能家居终端设备模拟器(Wifi版本,不可用于Android),本质是一个echo服务器
public class DeviceWifiSimulator {

	public static void main(String[] args) {
		new DeviceWifiSimulator(Settings.DeviceWifiSimulatorPort).run();
	}

	int port;
	volatile DeviceWifiSimulator blinker = this;
	static final Charset charset = Settings.charset;
	static final Util util = Settings.util;

	public void close() {
		blinker = null;
	}

	public DeviceWifiSimulator(int port) {
		super();
		this.port = port;
	}

	public void run() {
		ServerSocketChannel serverChannel = null;
		try {
			// 打开ServerChannel
			serverChannel = ServerSocketChannel.open();
			serverChannel.configureBlocking(true);
		} catch (IOException e) {
			util.log("ServerSocketChannel.open() Failed");
			return;
		}

		// 获取本机IP地址
		InetAddress localAddr = util.getLocalAddr();
		if (localAddr == null) {
			util.log("getLocalAddr() Failed");
			return;
		}
		InetSocketAddress socketAddr = new InetSocketAddress(localAddr, port);
		try {
			// 绑定本机IP到ServerChannel
			serverChannel.bind(socketAddr);
			util.log("Server Started: " + serverChannel.getLocalAddress());
		} catch (IOException e) {
			util.log("serverChannel.bind(socketAddr) Failed");
			return;
		}

		// 循环等待接收连接
		try {
			for (; blinker == this;) {
				SocketChannel socketChannel = serverChannel.accept();
				util.log("Connection Accepted: " + socketChannel.getRemoteAddress());
				new ClientThread(socketChannel).start();
			}
		} catch (IOException e) {
			util.log("SocketChannel clientChannel=serverChannel.accept() Failed");
			return;
		}

	}

	static class ClientThread extends Thread {
		SocketChannel socketChannel;
		volatile ClientThread blinker = this;

		public ClientThread(SocketChannel socketChannel) {
			super();
			this.socketChannel = socketChannel;
		}

		@Override
		public void run() {
			ByteBuffer buffer = ByteBuffer.allocate(1024);
			try {
				// 循环接收控制
				for (; blinker == this;) {
					// 接收消息
					buffer.clear();
					int readCount = -1;
					try {
						readCount = socketChannel.read(buffer);
					} catch (IOException e) {
						// 连接强行断开时抛出异常
						util.log("Close Connection Unexpectedly: " + socketChannel.getRemoteAddress());
						break;
					}
					if (readCount == -1) {
						// 连接正常断开时readCount为-1
						util.log("Close Connection: " + socketChannel.getRemoteAddress());
						break;
					}
					buffer.flip();
					byte[] bytes = new byte[buffer.remaining()];
					buffer.get(bytes);
					String message = new String(bytes, charset);
					util.log("Message Received: " + message);

					// 回传消息
					int writeCount = -1;
					buffer.position(0);
					try {
						writeCount = socketChannel.write(buffer);
					} catch (IOException e) {
						// 连接强行断开时抛出异常
						util.log("Close Connection Unexpectedly: " + socketChannel.getRemoteAddress());
						break;
					}
					if (writeCount == -1) { // 连接正常断开时readCount为-1
						util.log("Close Connection: " + socketChannel.getRemoteAddress());
						break;
					}
					util.log("Echo: " + message);
				}
			} catch (IOException e) {
			} finally {
				close();
			}
		}

		public void close() {
			blinker = null;
			try {
				socketChannel.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

}
