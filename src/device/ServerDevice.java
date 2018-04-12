package device;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;


//可主动构建连接的设备Stub;有前提的线程安全:在调用closeSocket时其它线程可能会出现不可预知的问题
public class ServerDevice extends Device {

	SocketChannel sc = null;
	volatile ServerDevice blinker = this;
	public boolean retryAfterCloseUnhealthySocket = false; // 是否允许断线重连;不允许,未包含重连注册逻辑
	public int retryTimes = 1; // 尝试建立连接次数
	final Object sendMutex=new Object(),receiveMutex=new Object();

	// 发送信息,如果Socket未连接则连接
	@Override
	public boolean sendMessage(String msg) {
		synchronized (sendMutex){
			int status = (maybeValid() ? 1 : 0), retryTime = retryTimes; // 尝试连接次数
			for (; blinker == this;) {
				switch (status) {
				case 0:
					// socket为null,开始连接,计数
					if (retryTime-- > 0) {
						if (connectSocket()) {
							// 连接成功,转入通信状态
							status = 1;
							break;
						}
					} else
						status = 0xff; // 重连次数用尽,消息发送失败
					break;
				case 1:
					// socket不为null,无法判断是否健康,尝试通信
					ByteBuffer buffer = ByteBuffer.wrap(msg.getBytes(charset));
					int count = -1;
					try {
						count = sc.write(buffer);
					} catch (IOException e) {
						// 连接异常断开
						util.log("Connection Closed Unexpectedly: " + this);
					}
					if (count == -1) {
						// 连接已断开,关闭
						util.log("Close Connection: " + this);
						closeSocket();
						if (retryAfterCloseUnhealthySocket)
							status = 0; // 转入重连状态
						else
							status = 0xff; // 结束通信
						break;
					}
					return true; // 发送成功
				default:
					return false;
				}
			}
			return false;
		}
	}

	// 接收信息,如果Socket未连接则连接
	@Override
	public String receiveMessage() {
		synchronized (receiveMutex){
			int status = sc == null ? 0 : 1, retryTime = retryTimes; // 尝试连接次数
			for (; blinker == this;) {
				switch (status) {
				case 0:
					// socket为null,开始连接,计数
					if (retryTime-- > 0) {
						if (connectSocket()) {
							// 连接成功,转入通信状态
							status = 1;
							break;
						}
					} else
						status = 0xff; // 重连次数用尽,消息发送失败
					break;
				case 1:
					// socket不为null,无法判断是否健康,尝试通信
					ByteBuffer buffer = ByteBuffer.allocate(1024);
					buffer.clear();
					int count = -1;
					try {
						count = sc.read(buffer);
						buffer.flip(); // 很关键
					} catch (IOException e) {
						// 连接异常断开
						util.log("Connection Closed Unexpectedly: " + this);
					}
					if (count == -1) {
						// 连接已断开,关闭
						util.log("Close Connection: " + this);
						closeSocket();
						if (retryAfterCloseUnhealthySocket)
							status = 0; // 转入重连状态
						else
							status = 0xff; // 结束通信
						break;
					}

					byte[] bytes = new byte[count];
					buffer.get(bytes);
					String msg = new String(bytes, charset);
					return msg; // 发送成功
				default:
					return null;
				}
			}
			return null;
		}
	}

	//对于sc状态的查询修改必须线程同步
	public synchronized boolean maybeValid() {
		return (sc != null && blinker == this && sc.isConnected());
	}

	// 发起Socket连接,socket不为null时重置
	synchronized boolean connectSocket() {
		if (sc != null)
			closeSocket();
		try {
			sc = SocketChannel.open();
			sc.connect(addr);
			util.log("Socket Connected: " + "[" + name + "]" + sc.getRemoteAddress());
		} catch (IOException e) {
			util.log("Socket Connect Error: " + this);
			return false;
		}
		return true;
	}

	// 断开socket,尽量选择调用shutdown而不是此方法
	@Override
	public synchronized void closeSocket() {
		try {
			if (sc != null)
				sc.close();
		} catch (IOException e) {
		}
		sc = null;
	}

	// 停止使用
	@Override
	public void close() {
		blinker = null;
		closeSocket();
	}

	// 重新使用
	@Override
	public void restart() {
		close();
		blinker = this;
	}

}
