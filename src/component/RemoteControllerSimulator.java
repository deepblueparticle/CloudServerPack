package component;

import java.io.Closeable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.regex.Pattern;

import device.Device;
import device.DeviceManagerInterface;
import device.ServerDevice;
import message.CloudMessage;
import message.ErrorMessage;
import message.HeartbeatMessage;
import message.Message;
import message.MessageFactory;
import message.RegistryMessage;
import util.Settings;
import util.Util;

//远程遥控器
public class RemoteControllerSimulator implements DeviceManagerInterface<Device>,Closeable {
	RemoteControllerSimulator blinker = this;
	ServerDevice cloudServer;
	String key = String.valueOf(new Random().nextInt(899999) + 100000);
	CloudService service = new CloudService();
	Map<String, Map<String, TerminalDevice>> toKeyMap = new HashMap<String, Map<String, TerminalDevice>>();
	static Util util = Settings.util;
	Handler handler = new Handler();
	public Api api=new Api(); //对外接口

	//有条件的线程安全:对云服务器的操作安全
	public class Api{
		//设置云服务器
		public boolean SetCloudServer(String addr, int port){
			if (!Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").matcher(addr).matches()) {
				util.log("IP地址格式错误");
				return false;
			}
			if (port<1 || port>65535){
				util.log("端口错误");
				return false;
			}
			RemoteControllerSimulator.this.setCloudServer(addr, port);
			return true;
		}
		//测试云服务器是否可用
		public synchronized boolean CheckValid(){
			if (cloudServer == null) {
				util.log("未设置云服务器");
				return false;
			}
			if (!cloudServer.maybeValid()) {
				// 注册
				if (!service.register(key)) {
					util.log("云服务器不可用 (注册失败): " + cloudServer);
					return false;
				}
			}
			if (service.checkValid()){
				util.log("云服务器可用: " + cloudServer);
				return true;
			}
			else{
				util.log("云服务器不可用 (心跳消息接收失败): " + cloudServer);
				return false;
			}
		}
		//向远程智能家具发送消息
		public boolean SendMessage(String toKey, String name, String msg){
			if (toKey==null || "".equals(toKey) || name==null || "".equals(name) || msg==null || "".equals(msg)) return false;
			putDevice(toKey,name);
			TerminalDevice device = getDevice(toKey,name);
			if (device==null) return false;
			if (!device.sendMessage(msg)) {
				util.log("发送消息出错,无法连接云服务器");
				return false;
			}
			return true;
		}
		//从云服务器接收消息
		public String ReceiveMessage(String toKey, String name){
			TerminalDevice device = getDevice(toKey,name);
			String result = device.receiveMessage();
			if (result == null || "".equals(result)) {
				util.log("接收消息出错");
				return null;
			}
			return result;
		}
	}

	public static void main(String[] args) {
		RemoteControllerSimulator controller = new RemoteControllerSimulator();
		controller.setCloudServer(Settings.CloudServerAddress, Settings.CloudServerPort);
		controller.putDevice(Settings.LocalDeviceManagerKey, Settings.DeviceWifiSimulatorName);
		controller.CommandUI();
	}

	public TerminalDevice removeDevice(String toKey, String name) {
		if (toKey == null || name == null || "".equals(toKey) || "".equals(name))
			return null;
		Map<String, TerminalDevice> deviceMap = toKeyMap.get(toKey);
		if (deviceMap == null)
			return null;
		TerminalDevice device = deviceMap.remove(name);
		if (deviceMap.isEmpty())
			toKeyMap.remove(toKey);
		return device;
	}

	public TerminalDevice getDevice(String toKey, String name) {
		if (toKey == null || name == null || "".equals(toKey) || "".equals(name))
			return null;
		Map<String, TerminalDevice> deviceMap = toKeyMap.get(toKey);
		if (deviceMap == null)
			return null;
		return deviceMap.get(name);
	}

	public boolean putDevice(String toKey, String name) {
		if (toKey == null || name == null || "".equals(toKey) || "".equals(name))
			return false;
		TerminalDevice device = new TerminalDevice();
		device.toKey = toKey;
		device.name = name;
		return putDevice(device);
	}

	public boolean putDevice(TerminalDevice device) {
		if (device == null)
			return false;
		Map<String, TerminalDevice> deviceMap = toKeyMap.get(device.toKey);
		if (deviceMap == null) {
			deviceMap = new HashMap<String, TerminalDevice>();
			toKeyMap.put(device.toKey, deviceMap);
		}
		deviceMap.put(device.name, device);
		return true;
	}

	// 设置云服务器地址,端口号,并转化成一台ServerDevice
	public synchronized void setCloudServer(String strAddr, int port) {
		InetAddress inetAddr = null;
		try {
			inetAddr = InetAddress.getByName(strAddr);
		} catch (UnknownHostException e) {
			return;
		}

		InetSocketAddress addr = new InetSocketAddress(inetAddr, port);
		cloudServer = new ServerDevice();
		cloudServer.name = "CloudServer";
		cloudServer.addr = addr;
		util.log("Cloud Server Set: " + cloudServer.addr);
	}

	public void CommandUI() {
		Scanner sc = new Scanner(System.in);
		int status = 0xff;
		circle: for (; blinker == this;) {
			switch (status) {
			case 0:
				close();
				break circle;
			case 1:
				try {
					util.log("输入云服务器IP地址");
					String addr = sc.next();
					if (!Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").matcher(addr).matches()) {
						util.log("IP地址格式错误");
						break;
					}
					util.log("输入云服务器IP端口");
					int port = sc.nextInt();
					setCloudServer(addr, port);
					util.log("设置密钥");
					key = sc.next();
					util.log("设置成功");
				} finally {
					status = 0xff;
				}
				break;
			case 2:
				try {
					util.log("输入本地服务器密钥");
					String toKey = sc.next();
					util.log("输入智能家具名");
					String name = sc.next();
					if (putDevice(toKey, name))
						util.log("添加成功");
					else
						util.log("添加失败");
				} finally {
					status = 0xff;
				}
				break;
			case 3:
				try {
					util.log("输入本地服务器密钥");
					String toKey = sc.next();
					if (!toKeyMap.containsKey(toKey)) {
						util.log("不存在的本地服务器密钥");
						break;
					}
					util.log("输入智能家具名");
					String name = sc.next();
					if (removeDevice(toKey, name) != null)
						util.log("删除成功");
					else
						util.log("不存在的设备");
				} finally {
					status = 0xff;
				}
				break;
			case 4:
				StringBuilder sb = new StringBuilder();
				if (cloudServer != null)
					sb.append(cloudServer + "\n");
				for (Device info : getAllDevices())
					sb.append(info + "\n");
				if ("".equals(sb.toString())) {
					util.log("当前没有设备");
				} else {
					util.log(sb.toString());
				}
				status = 0xff;
				break;
			case 5: {
				try {
					List<TerminalDevice> devices = new ArrayList<TerminalDevice>();
					Collection<Device> rawDevices = getAllDevices();
					if (rawDevices == null || rawDevices.size() == 0) {
						util.log("当前没有设备");
						break;
					}
					int i = 0;
					for (Iterator<Device> it = rawDevices.iterator(); it.hasNext(); i++) {
						Device device = it.next();
						if (device instanceof TerminalDevice) {
							TerminalDevice tDevice = (TerminalDevice) device;
							util.log(i + " -> " + tDevice);
							devices.add(tDevice);
						}
					}
					int choice = sc.nextInt();
					if (choice < devices.size() && choice >= 0) {
						TerminalDevice device = devices.get(choice);
						for (;;) {
							util.log("输入要发送的消息(键入exit退出)");
							String msg = sc.next();
							if ("exit".equals(msg))
								continue circle;
							if (!device.sendMessage(msg)) {
								util.log("发送消息出错,无法连接云服务器");
								continue circle;
							}
							String result = device.receiveMessage();
							if (result == null || "".equals(result)) {
								util.log("接收消息出错");
								continue circle;
							}
							handler.handleMessage(result);
						}

					} else {
						util.log("不存在的智能家具");
						continue circle;
					}
				} finally {
					status = 0xff;
				}
			}
			case 6:
				try {
					if (cloudServer == null) {
						util.log("未设置云服务器");
						break;
					}
					if (!cloudServer.maybeValid()) {
						// 注册
						if (!service.register(key)) {
							util.log("云服务器不可用 (注册失败): " + cloudServer);
							cloudServer.close();
							cloudServer.restart();
							break;
						}
					}
					if (service.checkValid())
						util.log("云服务器可用: " + cloudServer);
					else{
						util.log("云服务器不可用 (心跳消息接收失败): " + cloudServer);
						cloudServer.close();
						cloudServer.restart();
					}
				} finally {
					status = 0xff;
				}
				break;
			default:
				String msg = "[远程遥控器]\n" + "1 -> 配置云服务器\n" + "2 -> 添加智能家具\n" + "3 -> 删除智能家具\n" + "4 -> 查看当前设备列表\n"
						+ "5 -> 向远程智能家具发送消息\n" + "6 -> 测试云服务器是否可用\n" + "0 -> 退出\n";
				util.log(msg);
				status = sc.nextInt();
				break;
			}
		}
	}

	// 和云服务器进行通信的服务;线程安全
	class CloudService {
		// 向云服务器注册指定密钥
		public synchronized boolean register(String key) {
			if (cloudServer == null)
				return false;
			// 注册前,先断开原来连接
			cloudServer.close();
			cloudServer.restart();
			// 开始注册
			String json = MessageFactory.wrapRegistryMessage(key, RegistryMessage.REMOTE_CONTROLLER);
			if (json == null || "".equals(json)) {
				util.log("Registry Failure (Cannot convert to Json)");
				return false;
			}
			if (!cloudServer.sendMessage(json)) {
				util.log("Registry Failure (Cannot Connect to CloudServer)");
				return false;
			}
			// 服务器回传RegitryMessage,且内容一致
			json = cloudServer.receiveMessage();
			RegistryMessage retVal = MessageFactory.unwrapRegistryMessage(json);
			if (retVal == null || !retVal.checkNotNull()) {
				util.log("Registry Failure (Cannot Convert from Json): " + json);
				return false;
			}
			if (!key.equals(retVal.name)) {
				if (ErrorMessage.s3.equals(retVal.name))
					util.log("Registry Failure (Same Key Already Exists): " + key);
				else
					util.log("Registry Failure: Key = " + key + " CloudServer = " + cloudServer + " Received Key = "
							+ retVal.name);
				return false;
			}
			util.log("Registry Success: Key = " + key + " CloudServer = " + cloudServer);
			return true;
		}

		// 发送心跳消息,查看连接是否健康
		public synchronized boolean checkValid() {
			if (cloudServer == null || !cloudServer.maybeValid())
				return false;
			// 传输心跳消息
			String json = null;
			json = MessageFactory.wrapHeartbeatRequestMessage();
			if (!cloudServer.sendMessage(json)) {
				util.log("Heartbeat Check Invalid (Cannot Send Message)");
				return false;
			}
			json = cloudServer.receiveMessage();
			if (json == null || "".equals(json)){
				util.log("Heartbeat Check Invalid (Cannot Receive Message)");
				return false;
			}
			HeartbeatMessage msg = MessageFactory.unwrapHeartbeatMessage(json);
			if (msg!=null && msg.checkNotNull()) {
				util.log("Heartbeat Check Valid");
				return true;
			}
			else{
				util.log("Heartbeat Cannot Convert from Json: "+json);
				return false;
			}
		}
	}

	class Handler {

		public void onReceived(String data) {
			util.log("Message Received: " + data);
		}

		public void onError(ErrorMessage msg) {
			util.log("Error Message Received: " + msg.getMessage());
		}

		public void onInvalid(String json) {
			util.log("Receive Invalid Message: " + json);
		}

		public void onCloud(CloudMessage msg){
			util.log("Receive CloudMessage: "+msg);
			handleMessage(msg.data);
		}
		
		public void handleMessage(String json) {
			if (json == null || "".equals(json)) {
				util.log("No Message to Handle, Null Json");
				return;
			}
			Message msg = MessageFactory.unwrapMessage(json);
			if (msg == null) {
				onInvalid(json);
				return;
			}
			switch (msg.type) {
			case CloudMessage.type:
				CloudMessage cloudMsg=MessageFactory.unwrapCloudMessage(json);
				if (cloudMsg==null){
					onInvalid(json);
					return;
				}
				onCloud(cloudMsg);
				break;
			case Message.rawType:
				onReceived(msg.data);
				break;
			case ErrorMessage.type:
				ErrorMessage err = MessageFactory.unwrapErrorMessage(json);
				if (err == null) {
					onInvalid(json);
					return;
				}
				onError(err);
				break;
			default:
				onInvalid(json);
				return;
			}
		}
	}

	// 智能家具设备;线程安全
	class TerminalDevice extends Device {

		TerminalDevice blinker = this;
		String toKey; // 远程服务器
		public boolean retryAfterCloseUnhealthySocket = true; // 是否允许断线重连;可以允许,包含了重连注册逻辑
		public int retryTimes = 1; // 尝试建立连接次数
		Object sendMutex = new Object(), receiveMutex = new Object();

		@Override
		public boolean maybeValid() {
			if (!checkNotNull())
				return false;
			synchronized (cloudServer) {
				return cloudServer.maybeValid();
			}
		}

		public boolean checkNotNull() {
			boolean notNull = (blinker == this && key != null && !"".equals(key) && toKey != null && !"".equals(toKey)
					&& name != null && !"".equals(name) && cloudServer != null);
			return notNull;
		}

		@Override
		public String receiveMessage() {
			synchronized (receiveMutex) {
				if (cloudServer == null)
					return null;
				String json = cloudServer.receiveMessage();
				if (json == null || "".equals(json)) {
					util.log("Receive Message Failed (Connection to CloudServer Closed)");
					return null;
				}
				return json;
			}
		}

		// 当发送消息失败时,尝试重新注册
		@Override
		public boolean sendMessage(String msg) {
			synchronized (sendMutex) {
				if (!checkNotNull())
					return false;
				int status = (maybeValid() ? 1 : 0), retryTime = retryTimes; // 尝试连接次数
				while (blinker == this) {
					switch (status) {
					case 0: {
						// 向云服务器注册
						if (retryTime-- > 0) {
							if (service.register(key)) {
								// 注册成功,转入通信状态
								status = 1;
								break;
							}
						} else
							status = 0xff; // 重连次数用尽,消息发送失败
						break;
					}
					case 1: {
						// socket不为null,无法判断是否健康,尝试通信
						String json = MessageFactory.wrapCloudMessage(key, toKey, name, msg);
						if (!cloudServer.sendMessage(json)) {
							if (blinker == null) {
								util.log("Send Message from CloudServer Error (Service Closed)");
								status = 0xff;
								break;
							} else {
								util.log("Send Message from CloudServer Error (Connection Closed)");
								if (retryAfterCloseUnhealthySocket) {
									util.log("Retry Registry");
									status = 0;
									break;
								}
								status = 0xff;
								break;
							}
						}
						// 消息发送成功,返回
						util.log("Send Message Success: " + json);
						return true;
					}
					default:
						return false;
					}
				}

				return false;
			}
		}

		@Override
		public String toString() {
			return "[" + name + "]fromKey = " + key + " toKey=" + toKey;
		}
	}

	@Override
	public Device removeDevice(String name) {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Collection<Device> getAllDevices() {
		Collection<Device> devices = new ArrayList<Device>();
		for (Map<String, TerminalDevice> deviceMap : toKeyMap.values()) {
			devices.addAll(deviceMap.values());
		}
		return devices;
	}

	@Override
	public boolean putDevice(Device device) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void close() {
		blinker = null;
		if (cloudServer!=null) cloudServer.close();
	}

	@Override
	public Device getDevice(String name) {
		// TODO Auto-generated method stub
		return null;
	}

}
