package component;

import java.io.Closeable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.google.gson.Gson;

import device.Device;
import device.DeviceManagerInterface;
import device.ServerDevice;
import message.ErrorMessage;
import message.HeartbeatMessage;
import message.Message;
import message.MessageFactory;
import message.RegistryMessage;
import message.CloudMessage;
import util.Settings;
import util.TickerManager;
import util.Util;

//线程安全
public class LocalDeviceManager implements DeviceManagerInterface<ServerDevice>,Closeable {

	static Util util = Settings.util;
	Map<String, ServerDevice> devices = new HashMap<String, ServerDevice>();
	ServerDevice cloudServer;
	volatile LocalDeviceManager blinker = this;
	Gson gson = new Gson();
	String key; // 本地服务器密钥
	Service service = null;
	Object serviceMutex=new Object();
	public Api api=new Api();
	ScheduledThreadPoolExecutor scheduledThreadPool=new ScheduledThreadPoolExecutor(3);
	volatile boolean onStarting=false; //避免重复启动

	//线程安全
	public class Api {

		//发送心跳消息,确认可以和服务器通信;异步方式
		public void CheckValid(Runnable onSuccess,Runnable onFailure){
			if (service==null || !service.isAlive()){
				onFailure.run();
				return;
			}
			service.checkValid(onSuccess,onFailure);
		}


		//设置本地设备管理器密钥
		public boolean SetKey(String key){
			if (key==null || "".equals(key)) return false;
			LocalDeviceManager.this.key=key;
			return true;
		}

		//设置智能家具;设置成功返回true,失败返回false
		public boolean AddLocalDevice(String name, String addr, int port){
			if (name==null || "".equals(name) || addr==null || "".equals(addr)) return false;
			if (!Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").matcher(addr).matches()) {
				util.log("智能家具IP地址格式错误: "+addr);
				return false;
			}
			if (port<1 || port>65535){
				util.log("智能家具端口错误: "+port);
				return false;
			}
			if (getDevice(name)!=null) return true; //已存在
			return putDevice(name, addr, port);
		}

		// 设置云服务器
		public boolean SetCloudServer(String addr, int port) {
			if (addr==null || "".equals(addr)) return false;
			if (!Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").matcher(addr).matches()) {
				util.log("云服务器IP地址格式错误: "+addr);
				return false;
			}
			if (port<1 || port>65535){
				util.log("云服务器端口错误: "+port);
				return false;
			}
			return LocalDeviceManager.this.setCloudServer(addr, port);
		}

		// 开启
		public void Start() {
			startService();
		}

		// 停止
		public void Stop() {
			stopService();
		}
	}

	//运行服务
	public void startService(){
		//防止重复启动
		if (onStarting){
			System.out.println("服务正在启动");
			return;
		}
		onStarting=true;
		scheduledThreadPool.schedule(new Runnable() {
			@Override
			public void run() {
				onStarting=false;
			}
		}, Settings.ConnectTimeout, TimeUnit.MILLISECONDS);

		int initialStatus=1;
		startService(initialStatus);
	}
	void startService(int status){
		synchronized (serviceMutex){
			while (blinker==LocalDeviceManager.this){
				switch (status){
					case 1:
						if (service==null || !service.isAlive()){
							status=2;
							break;
						}
						service.checkValid(new Runnable() {
							@Override
							public void run() {
								util.log("本地服务器已在运行");
							}
						}, new Runnable() {
							@Override
							public void run() {
								util.log("服务连接失效,断开");
								stopService(); //可能的问题:在超时的两秒内,手动重启了服务,此时服务将再次关闭
								startService(2);
							}
						});
						return;
					case 2:
						//开启服务
						service = new Service();
						service.start();
						util.log("启动服务");
						return;
					default:
						return;
				}
			}
			return;
		}
	}

	//停止服务
	public void stopService(){
		synchronized (serviceMutex){
			while (service != null && service.isAlive()) {
				service.close();
				service=null;
			}
			service=null; //很关键
			util.log("服务已停止");
		}
	}


	// 配置好云服务器和本地智能家具后,运行Service
	class Service extends Thread {

		volatile Service blinker = this;
		static final boolean retryAfterCloseUnhealthySocket = false; // 是否允许断线重连;开启时,若同时建立两个本地服务器,会出现互相抢占注册的情况
		static final int retryTimes = 1; // 尝试建立连接并注册次数
		Handler handler = new Handler();
		TickerManager heartbeatTicker=new TickerManager();

		// 发送心跳消息,查看连接是否健康;异步方式
		public synchronized void checkValid(Runnable onSuccess,Runnable onFailure) {
			if (cloudServer == null || !cloudServer.maybeValid())
				if (onFailure!=null) onFailure.run();
			// 传输心跳消息
			String json = null;
			json = MessageFactory.wrapHeartbeatRequestMessage();
			if (!cloudServer.sendMessage(json)) {
				util.log("Heartbeat Check Invalid: " + this);
				if (onFailure!=null) onFailure.run();
				return;
			}
			//加入TickerManager
			heartbeatTicker.put(onSuccess,onFailure,Settings.ConnectTimeout);
			//在循环接收线程中等待消息回传并处理
			util.log("Waitting for HeartbeatMessage.");
		}

		// 向云服务器注册指定密钥
		public synchronized boolean register(String key) {
			if (cloudServer == null)
				return false;
			// 注册前,先断开原来连接
			cloudServer.close();
			cloudServer.restart();
			// 开始注册
			String json = MessageFactory.wrapRegistryMessage(key, RegistryMessage.LOCAL_SERVER);
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

		// 处理来自云服务器的消息
		class Handler {
			// 云服务器和本地智能家具通信;返回true表示成功处理(无论是否有效),返回false表示和云服务器的连接中断
			public boolean onCloud(CloudMessage msg) {
				if (msg == null || !msg.checkNotNull())
					return true;
				int status = CloudMessage.ErrorMessage.equals(msg.info)?4:5;
				ServerDevice device = null;
				String result = null;
				while (blinker == Service.this) {
					switch (status) {
					case 4:{
						//服务器回传错误信息
						util.log("Receive ErrorMessage via CloudMessage");
						ErrorMessage err=MessageFactory.unwrapErrorMessage(msg.data);
						if (err==null) {
							onInvalid(msg.data);
							return true;
						}
						onError(err);
						return true;
					}
					case 5: {
						// 从设备池中查找设备
						device = devices.get(msg.info);
						if (device == null) {
							// 未找到本地设备
							util.log("Local Device not Found: " + msg.info);
							String err=MessageFactory.wrapErrorMessage(ErrorMessage.c1);
							result = MessageFactory.wrapRetCloudMessage(msg, err);
							status = 7;
							break;
						}
						// 找到本地设备
						util.log("Local Device Found: " + msg.info);
						status = 6;
						break;
					}
					case 6: {
						// 和设备通信
						if (!device.sendMessage(msg.data)) {
							String err=MessageFactory.wrapErrorMessage(ErrorMessage.c2);
							result = MessageFactory.wrapRetCloudMessage(msg, err);
							util.log("Connect to LocalDevice Failed: " + device);
							status = 7;
							break;
						}
						String retData = device.receiveMessage();
						if (retData == null || "".equals(retData)) {
							String err=MessageFactory.wrapErrorMessage(ErrorMessage.c2);
							result = MessageFactory.wrapRetCloudMessage(msg, err);
							util.log("Connect to LocalDevice Failed: " + device);
							status = 7;
							break;
						}
						// 通信成功
						String retDataMsg=MessageFactory.wrapMessage(retData);
						result = MessageFactory.wrapRetCloudMessage(msg,retDataMsg);
						status = 7;
						break;
					}
					case 7: {
						// 回传Result给云服务器
						if (!cloudServer.sendMessage(result)) {
							util.log("Connect and Send Message back Failed: " + cloudServer);
							return false;
						}
						// 回传成功
						util.log("Handle ToLocalDeviceMessage Successfully: Message = " + msg + " Result = " + result);
						return true;
					}
					}
				}
				return false;
			}

			//收到心跳消息;true表示处理,false表示连接中断
			public boolean onHeartbeat(HeartbeatMessage msg){
				if (msg==null || !msg.checkNotNull()) return true;
				switch (msg.status){
					case HeartbeatMessage.REQUEST:
						//回传心跳消息请求
						String responseJson = MessageFactory.wrapHeartbeatResponseMessage();
						util.log("Receive HeartbeatMessage");
						if (!cloudServer.sendMessage(responseJson)) {
							util.log("Response HeartbeatMessage Failed");
							return false;
						}
						util.log("Response HeartbeatMessage");
						return true;
					case HeartbeatMessage.VALID:
						//接收到心跳消息
						util.log("Receive HeartbeatMessage");
						heartbeatTicker.tickAll();
						return true;
				}
				return true;
			}
			
			//接收到错误消息
			public void onError(ErrorMessage err){
				util.log("Error Message Received: " + err.getMessage());
			}

			//接收到无效消息
			public void onInvalid(String json){
				util.log("Received Invalid Message from CloudServer: Json = " + json);
			}

			// 返回true表示有消息接收（无论是否有效）,返回false表示和云服务器的连接断开
			public boolean handleMessage(String json) {
				if (json == null || "".equals(json))
					return false;
				Message base = MessageFactory.unwrapMessage(json);
				if (base == null) {
					// 不包含格式的无效消息
					onInvalid(json);
					return true;
				}
				switch (base.type) {
				case HeartbeatMessage.type: {
					// 心跳消息
					HeartbeatMessage heartbeatMessage=MessageFactory.unwrapHeartbeatMessage(json);
					if (heartbeatMessage==null && !heartbeatMessage.checkNotNull()){
						onInvalid(json);
						return true;
					}
					return onHeartbeat(heartbeatMessage);
				}
				case CloudMessage.type: {
					// 云服务器和本地智能家具通信
					CloudMessage msg = MessageFactory.unwrapCloudMessage(json);
					if (msg == null || !msg.checkNotNull()) {
						util.log("Receive Message from CloudServer Error (Cannot Convert from Json)" + json);
						return true;
					}
					return onCloud(msg);
				}
				case Message.rawType:
					util.log("Received Text Message from CloudServer: " + base.data);
					return true;
				default:
					onInvalid(json);
					return true;
				}
			}
		}

		public void close() {
			blinker = null;
			cloudServer.close();
			for (String name : devices.keySet()){
				ServerDevice device=getDevice(name);
				if (device!=null) device.close();
			}
			if (heartbeatTicker!=null) heartbeatTicker.close();
			util.log("Stop Service");
		}


		@Override
		public void run() {
			// Check CloudServer not Null
			if (cloudServer == null || key == null || "".equals(key)) {
				util.log("CloudServer or Key not Set.");
				close();
				return;
			}

			// Setting
			cloudServer.restart(); // 重启CloudServer
			for (String name:devices.keySet()){
				//重启智能家具
				ServerDevice device=devices.get(name);
				if (device!=null) device.restart();
			}
			int retryTime = Service.retryTimes;
			int status = 3;
			ServerDevice device = null;
			String result = null;
			CloudMessage msg = null;
			util.log("Service Started: Key = " + key + " CloudServer = " + cloudServer);

			// Start Loop
			while (blinker == this) {
				switch (status) {
				case 3: {
					// 向云服务器注册自身密钥
					if (retryTime-- > 0) {
						if (!register(key))
							break;
						// 注册成功
						status = 4; // 开始循环接收云服务器消息
					} else
						status = 0xff;
					break;
				}
				case 4: {
					// 循环接收来自云服务器的消息,并交给Handler处理
					String json = cloudServer.receiveMessage();
					if (json == null || "".equals(json)) {
						if (blinker == null)
							util.log("Receive Message from CloudServer Error (Service Closed)");
						else {
							util.log("Receive Message from CloudServer Error (no Message Received)");
							if (retryAfterCloseUnhealthySocket) {
								util.log("Retry Registry");
								status = 3;
								break;
							}
						}
						status = 0xff;
						break;
					}
					if (!handler.handleMessage(json)) {
						// 通信过程中和云服务器的连接中断
						if (blinker == null)
							util.log("Receive Message from CloudServer Error (Service Closed)");
						else {
							util.log("Receive Message from CloudServer Error (Connection Closed Unexpectedly)");
							if (retryAfterCloseUnhealthySocket) {
								util.log("Retry Registry");
								status = 3;
								break;
							}
						}
						status = 0xff;
						break;
					}
					// 回传成功,跳转至循环接收云服务器消息
					status = 4;
					break;
				}
				default: {
					close();
					return;
				}
				}
			}
		}
	}

	// 设置云服务器地址,端口号,并转化成一台ServerDevice
	public synchronized boolean setCloudServer(String strAddr, int port) {
		InetAddress inetAddr = null;
		try {
			inetAddr = InetAddress.getByName(strAddr);
		} catch (UnknownHostException e) {
			return false;
		}

		InetSocketAddress addr = new InetSocketAddress(inetAddr, port);
		cloudServer = new ServerDevice();
		cloudServer.name = "CloudServer";
		cloudServer.addr = addr;
		util.log("Cloud Server Set: " + cloudServer.addr);
		return true;
	}

	public static void main(String[] args) {
		LocalDeviceManager manager = new LocalDeviceManager();
		manager.putDevice(Settings.DeviceWifiSimulatorName, Settings.DeviceWifiSimulatorAddress, Settings.DeviceWifiSimulatorPort);
		manager.setCloudServer(Settings.CloudServerAddress, Settings.CloudServerPort);
		manager.key = Settings.LocalDeviceManagerKey;
		manager.CommandUI();
	}

	// 控制台界面
	public void CommandUI() {
		Scanner sc = new Scanner(System.in);
		int status = 0xff;
		circle: for (;;) {
			switch (status) {
			case 0:
				close();
				break circle;
			case 1:
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
			case 2:
				try {
					util.log("输入设备IP地址");
					String addr = sc.next();
					if (!Pattern.compile("[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}").matcher(addr).matches()) {
						util.log("IP地址格式错误");
						break;
					}
					util.log("输入设备IP端口");
					int port = sc.nextInt();
					util.log("给设备命名(将覆盖已有设备)");
					String name = sc.next();
					if (name == null || "".equals(name)) {
						util.log("名字输入有误");
						break;
					}
					putDevice(name, addr, port);
					util.log("添加成功");
				} finally {
					status = 0xff;
				}
				break;
			case 3:
				try {
					util.log("输入设备名");
					String name = sc.next();
					if (devices.remove(name) != null)
						util.log("删除成功");
					else
						util.log("不存在的设备名");
				} finally {
					status = 0xff;
				}
				break;
			case 4:
				// 发送接收消息
				util.log("输入设备名进行消息发送\n");
				String name = sc.next();
				ServerDevice device = null;
				try {
					device = devices.get(name);
					if (device == null) {
						util.log("不存在的设备名");
						break;
					}
					for (;;) {
						util.log("输入要发送的消息(键入exit则退出)");
						String msg = sc.next();
						if ("exit".equals(msg))
							break;
						if (device.sendMessage(msg)) {
							msg = device.receiveMessage();
							if (msg == null) {
								util.log("消息接收失败");
								break;
							}
							util.log("收到消息: " + msg);
						} else {
							util.log("消息发送失败");
							break;
						}
					}
				} finally {
					status = 0xff;
				}
				break;
			case 5:
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
			case 6:
				try {
					util.log("1 -> 开始运行\n" + "2 -> 停止运行\n"+"3 -> 检测");
					int choice = sc.nextInt();
					if (choice == 1) {
						startService();
					} else if (choice==2){
						stopService();
					} else if (choice==3){
						api.CheckValid(new Runnable() {
                            @Override
                            public void run() {
                            	util.log("服务正在运行");
                            }
                        }, new Runnable() {
                            @Override
                            public void run() {
                            	util.log("服务已停止");
                            }
                        });
					}
				} finally {
					status = 0xff;
				}
				break;
			case 7:
				try {
					SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss");
					while (blinker == this) {
						startService();
						try {
							// 每5秒进行一次检测,断开则重启服务
							Thread.sleep(5000);
						} catch (InterruptedException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				} finally {
					status = 0xff;
				}
				break;
			default:
				// 显示菜单
				String msg = "[本地设备管理器]\n" + "1 -> 查看当前设备列表\n" + "2 -> 添加一台智能家居\n" + "3 -> 删除一台智能家居\n" + "4 -> 发送消息\n"
						+ "5 -> 设置云服务器地址\n" + "6 -> 开始/停止运行" + "\n" + "7 -> 循环运行\n" + "0 -> 退出\n";
				util.log(msg);
				status = sc.nextInt();
				break;
			}
		}
	}

	// 根据名字放入或更新一台智能家居终端设备
	public synchronized boolean putDevice(String name, String strAddr, int port) {
		InetAddress inetAddr = null;
		try {
			inetAddr = InetAddress.getByName(strAddr);
		} catch (UnknownHostException e) {
			return false;
		}

		InetSocketAddress addr = new InetSocketAddress(inetAddr, port);
		ServerDevice device = new ServerDevice();
		device.addr = addr;
		device.name = name;
//		device.retryAfterCloseUnhealthySocket = true;
		return putDevice(device);
	}

	@Override
	public synchronized boolean putDevice(ServerDevice device) {
		devices.put(device.name, device);
		return true;
	}

	@Override
	public synchronized Collection<ServerDevice> getAllDevices() {
		return devices.values();
	}

	@Override
	// 根据名字删除一台智能家居终端设备,并关闭连接
	public synchronized ServerDevice removeDevice(String name) {
		ServerDevice device = devices.remove(name);
		if (device != null)
			device.close();
		return device;
	}

	@Override
	public void close() {
		blinker = null;
		if (service != null) {
			service.close();
			service = null;
		}
		if (scheduledThreadPool!=null)
			scheduledThreadPool.shutdown();
		synchronized (this) {
			if (cloudServer != null)
				cloudServer.close();
			for (String name : devices.keySet()){
				ServerDevice device=getDevice(name);
				if (device!=null) device.close();
			}
		}
	}

	@Override
	public ServerDevice getDevice(String name) {
		return devices.get(name);
	}

}
