package device;

import java.io.Closeable;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;

import util.Settings;
import util.Util;

public class Device implements DeviceInterface,Closeable{
	static final Util util=Settings.util;
	static final Charset charset = Settings.charset;
	public InetSocketAddress addr;
	public String name;

	@Override
	public String toString() {
		return "[" + name + "]" + addr;
	}

	@Override
	public String receiveMessage(){
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean sendMessage(String msg) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void closeSocket() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void close() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void restart() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean maybeValid() {
		// TODO Auto-generated method stub
		return false;
	}
	

}