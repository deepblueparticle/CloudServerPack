package device;

import java.io.IOException;

import message.HeartbeatMessage;
import message.MessageFactory;
import util.Settings;
import util.TickerManager;

import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

//被动接受连接的设备Stub;有前提的线程安全:在调用closeSocket时其它线程会出现不可预知的问题
public class ClientDevice extends Device {
	public SocketChannel sc = null;
	volatile ClientDevice blinker = this;
	final Object sendMutex=new Object(),receiveMutex=new Object();
	TickerManager ticker=new TickerManager();

	public ClientDevice(SocketChannel sc) {
		super();
		this.sc = sc;
	}

	public boolean sendMessage(String msg) {
		synchronized (sendMutex){
			while (blinker == this) {
				if (!maybeValid()) return false;
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
					break;
				}
				return true; // 发送成功
			}
			return false;
		}
	}
	
	public String receiveMessage(){
		synchronized (receiveMutex){
			while (blinker==this){
				if (!maybeValid()) return null;
				// socket不为null,无法判断是否健康,尝试通信
				ByteBuffer buffer = ByteBuffer.allocate(1024);
				buffer.clear();
				int count = -1;
				try {
					count = sc.read(buffer);
					buffer.flip(); //很关键
				} catch (IOException e) {
					// 连接异常断开
					util.log("Connection Closed Unexpectedly: " + this);
				}
				if (count == -1) {
					// 连接已断开,关闭
					util.log("Close Connection: " + this);
					closeSocket();
					break;
				}
				
				byte[] bytes=new byte[count];
				buffer.get(bytes);
				String msg=new String(bytes,charset);
				return msg; // 发送成功
			}
			return null;
		}
	}

	//传输心跳消息,确认连接健康
	private boolean checkValid(){
		if (maybeValid()){
			//传输心跳消息
			String json=null;
			json=MessageFactory.wrapHeartbeatRequestMessage();
			if (!sendMessage(json)){
				util.log("Heartbeat Check Invalid: "+this);
				return false;
			}
			json=receiveMessage();
			if (json==null || "".equals(json))
				return false;
			HeartbeatMessage msg=MessageFactory.unwrapHeartbeatMessage(json);
			if (msg!=null && msg.checkNotNull()){
				util.log("Heartbeat Check Valid: "+this);
				return true;
			}
		}
		return false;
	}
	
	//传输心跳消息,确认连接健康;异步
	public void checkValid(Runnable onSuccess,Runnable onFailure){
		if (!maybeValid()) {
			onFailure.run();
			return;
		}
		//传输心跳消息
		String json=null;
		json=MessageFactory.wrapHeartbeatRequestMessage();
		if (!sendMessage(json)){
			util.log("Heartbeat Check Invalid: "+this);
			onFailure.run();
			return;
		}
		//加入TickerManager等待tick
		ticker.put(onSuccess, onFailure, Settings.SocketTimeout);
	}
	
	
	//通知所有Ticker;用于收到心跳消息后调用
	public void tickAll(){
		ticker.tickAll();
	}
	
	//对于sc状态的查询修改必须线程同步
	public synchronized boolean maybeValid() {
		return (sc != null && blinker == this && sc.isConnected());
	}

	// 断开socket;尽量调用shutdown而不是此方法
	@Override
	public synchronized void closeSocket() {
		try {
			if (sc != null)
				sc.close();
		} catch (IOException e) {
		}
		sc = null;
	}
	
	//停止使用,调用后使用restart方法重新启动
	@Override
	public void close(){
		blinker=null;
		if (ticker!=null) ticker.close();
		closeSocket();
	}
	
	// 重新使用
	@Override
	public void restart() {
		close();
		blinker = this;
	}

}
