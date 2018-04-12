package device;


public interface DeviceInterface {
	String receiveMessage();
	boolean sendMessage(String msg);
	void closeSocket();
	void close();
	void restart();
	boolean maybeValid();
}
