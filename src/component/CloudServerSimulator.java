package component;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;

import device.ClientDevice;
import device.Device;
import device.DeviceManagerInterface;
import message.ErrorMessage;
import message.HeartbeatMessage;
import message.Message;
import message.MessageFactory;
import message.RegistryMessage;
import message.CloudMessage;
import util.Settings;
import util.Util;

//模拟云服务器,用于测试遥控器和本地服务器
public class CloudServerSimulator implements DeviceManagerInterface<ClientDevice>, Closeable {

	int port;
	static final Util util = Settings.util;
	volatile CloudServerSimulator blinker = this;
	Map<String, ClientDevice> devices = new ConcurrentHashMap<String, ClientDevice>(); // 设备池
	Map<String, ProducerService> producers = new ConcurrentHashMap<String, ProducerService>(); // 生产者线程池
	Service service = null;
	ArrayBlockingQueue<Message> tasks = new ArrayBlockingQueue<Message>(1024); // Task消息队列
	ConsumerService consumer = null;
	Handler handler = new Handler();
	Gson gson = new Gson();

	public static void main(String[] args) {
		new CloudServerSimulator(Settings.CloudServerPort).CommandUI();
	}

	// 处理消息队列中的任务
	class Handler {
		public void onInvalid(Message task) {
			util.log("Invalid Task: " + task);
		}

		public void onHeartbeat(ClientDevice device, HeartbeatMessage msg) {
			if (device == null) {
				util.log("onHeartbeat() Error: Device NULL");
				return;
			}
			switch (msg.status) {
			case HeartbeatMessage.REQUEST: {
				// 根据密钥回传
				String responseJson = MessageFactory.wrapHeartbeatResponseMessage();
				util.log("Receive REQUEST HeartbeatMessage from " + device);
				if (!device.sendMessage(responseJson)) {
					util.log("Response HeartbeatMessage Failed to " + device);
					return;
				}
				util.log("Response HeartbeatMessage to " + device.name);
				break;
			}
			case HeartbeatMessage.VALID: {
				// 收到回传,tick
				device.tickAll();
				util.log("Receive VALID HeartbeatMessage from " + device);
				break;
			}
			}
		}

		// 处理来自远程遥控器的请求
		@SuppressWarnings("unused")
		public void onCloud(CloudMessage task) {
			if (!task.checkNotNull()) {
				util.log("CloudMessage Null:" + gson.toJson(task));
				return;
			}
			final int NORMAL = 1, SHUTDOWN_FROMDEVICE = 2, SHUTDOWN_TODEVICE = 3, TOKEY_NOTFOUND = 4,
					SEND_ERRORMESSAGE = 5, TODEVICE_CLOSED = 6;
			int status = NORMAL;
			ClientDevice toDevice = null;
			ClientDevice fromDevice = null;
			String err = null;
			while (blinker == CloudServerSimulator.this) {
				switch (status) {
				case NORMAL: {
					// 查找ToDevice
					toDevice = getDevice(task.toKey);
					if (toDevice == null) {
						// 未找到ToDevice,无效的密钥
						status = TOKEY_NOTFOUND;
						break;
					}
					// 找到本地服务器,转发消息
					util.log("ToDevice Found: " + toDevice.name);
					if (!toDevice.sendMessage(MessageFactory.wrapCloudMessage(task))) {
						status = TODEVICE_CLOSED;
						break;
					}
					// 完成转发
					util.log("Cloud Task Success: " + task);
					status = 0xff;
					return;
				}
				case SEND_ERRORMESSAGE: {
					// 未找到ToDevice或与ToDevice的连接中断,回传错误信息
					if (err == null)
						err = MessageFactory.wrapErrorMessage(ErrorMessage.c5);
					fromDevice = getDevice(task.fromKey);
					if (fromDevice == null) {
						util.log("FromKey not Found, Abandon Task: " + task);
						status = 0xff;
						break;
					}
					CloudMessage retCloudMsg = task.copy();
					retCloudMsg.info = CloudMessage.ErrorMessage; // 更改性质为回传错误消息
					retCloudMsg.data = err;
					if (!fromDevice.sendMessage(MessageFactory.wrapCloudMessage(retCloudMsg))) {
						status = SHUTDOWN_FROMDEVICE;
						break;
					}
					// 回传错误信息成功
					util.log("Send ErrorMessage: Message = " + err + " FromDevice = " + fromDevice.name);
					status = 0xff;
					break;
				}
				case TODEVICE_CLOSED: {
					// 和本地服务器的连接中断
					util.log("ToDevice Closed: " + task.toKey);
					err = MessageFactory.wrapErrorMessage(ErrorMessage.c7);
					status = SHUTDOWN_TODEVICE;
					break;
				}
				case TOKEY_NOTFOUND: {
					// 未找到本地服务器密钥
					util.log("ToKey not Found: " + task.toKey);
					err = MessageFactory.wrapErrorMessage(ErrorMessage.c6);
					status = SEND_ERRORMESSAGE;
					break;
				}
				case SHUTDOWN_TODEVICE: {
					// 关闭ToDevice
					if (toDevice != null) {
						removeDevice(toDevice.name);
					}
					// 回传ErrorMessage
					status = SEND_ERRORMESSAGE;
					break;
				}
				case SHUTDOWN_FROMDEVICE: {
					// 和远程遥控器的连接断开,关闭FromDevice
					util.log("FromDevice Connection Closed, Shutdown: " + fromDevice.name);
					if (fromDevice != null) {
						removeDevice(fromDevice.name);
					}
					status = 0xff;
					break;
				}
				default:
					util.log("Cloud Task Handled: " + task);
					return;
				}
			}
		}

		public void handlerMessage(Message task) {
			switch (task.type) {
			case CloudMessage.type: {
				String json = gson.toJson(task);
				CloudMessage realTask = MessageFactory.unwrapCloudMessage(json);
				if (realTask == null) {
					onInvalid(task);
					break;
				}
				onCloud(realTask);
				break;
			}
			default:
				onInvalid(task);
				return;
			}
		}
	}

	// 消费者线程,循环处理消息队列
	class ConsumerService extends Thread {
		volatile ConsumerService blinker = this;

		@Override
		public void run() {
			while (blinker == this) {
				try {
					Message task = tasks.poll(60, TimeUnit.SECONDS);
					if (task == null)
						continue;
					long startTime = System.currentTimeMillis();
					handler.handlerMessage(task);
					long endTime = System.currentTimeMillis();
					util.log("Handle Task in " + (endTime - startTime) + " Millis, " + tasks.size() + " Tasks Left");
				} catch (InterruptedException e) {
					util.log("Interrupt Message Queue, ConsumerService Closed");
				}
			}
		}

		public void close() {
			this.interrupt();
			blinker = null;
			consumer = null;
		}
	}

	// 生产者线程,循环监听来自遥控器的通信
	class ProducerService extends Thread {
		volatile ProducerService blinker = this;
		ClientDevice device = null;

		public ProducerService(ClientDevice device) {
			super();
			this.device = device;
		}

		@Override
		public void run() {
			while (blinker == this) {
				String json = device.receiveMessage();
				if (json == null || "".equals(json)) {
					// 和遥控器,本地控制器的连接中断
					util.log("Connect Interrupted on ProducerService: " + device + " (Close Device)");
					close();
					return;
				}
				Message msg = MessageFactory.unwrapMessage(json);
				if (msg == null) {
					util.log("Receive Invalid Message on ProducerService: Message = " + json + " Device = " + device);
					continue;
				}
				switch (msg.type) {
				case (HeartbeatMessage.type): {
					// 心跳消息
					HeartbeatMessage heartbeatMsg = MessageFactory.unwrapHeartbeatMessage(json);
					if (heartbeatMsg == null) {
						util.log("Receive Invalid HeartbeatMessage on ProducerService: Message = " + json + " Device = "
								+ device);
						continue;
					}
					handler.onHeartbeat(device, heartbeatMsg);
					break;
				}
				default: {
					// 消息放入消息队列,等待消费者线程处理
					if (!tasks.offer(msg)) {
						util.log("MessageQueue Full, Reject Message: Message = " + json + " Device = " + device);
						// 回传错误信息
						json = MessageFactory.wrapErrorMessage(ErrorMessage.c4);
						if (!device.sendMessage(json)) {
							// 和遥控器,本地控制器的连接中断
							util.log("Connect Interrupted on ProducerService: " + device + " (Close Device)");
							close();
							return;
						}
						continue;
					}
					// 消息成功放入队列
					util.log("Accept Message: Message = " + json + " Device = " + device);
				}
				}
			}
		}

		public void close() {
			blinker = null;
			removeDevice(device.name);
		}

	}

	@Override
	public void close() {
		blinker = null;
		// 关闭服务
		if (service != null) {
			service.close();
			service = null;
		}
		// 关闭消费者线程
		if (consumer != null) {
			consumer.close();
			consumer = null;
		}
		synchronized (this) {
			// 关闭设备和相应生产者线程
			for (String name : devices.keySet())
				removeDevice(name);
			for (Iterator<ProducerService> it = producers.values().iterator(); it.hasNext();) {
				ProducerService producer = it.next();
				producer.close();
				it.remove();
			}
		}
	}

	public void CommandUI() {
		Scanner sc = new Scanner(System.in);
		int status = 0xff;
		circle: for (; blinker == this;) {
			switch (status) {
			case 0: {
				close();
				break circle;
			}
			case 1: {
				// 查看当前注册的所有设备
				StringBuilder sb = new StringBuilder();
				for (Device info : getAllDevices())
					sb.append(info + "\n");
				if ("".equals(sb.toString())) {
					util.log("当前没有设备");
				} else {
					util.log(sb.toString());
				}
				status = 0xff;
				break;
			}
			case 2: {
				// 检测设备可用性
				if (getAllDevices() == null)
					util.log("当前没有设备");
				else
					for (ClientDevice device : getAllDevices()) {
						final ClientDevice deviceFinal = device;
						device.checkValid(new Runnable() {
							@Override
							public void run() {
								StringBuilder sb = new StringBuilder();
								sb.append("可用: ");
								sb.append(deviceFinal + "\n");
								util.log(sb.toString());
							}

						}, new Runnable() {
							@Override
							public void run() {
								removeDevice(deviceFinal.name);
								StringBuilder sb = new StringBuilder();
								sb.append("移除: ");
								sb.append(deviceFinal + "\n");
								util.log(sb.toString());
							}
						});
					}
				status = 0xff;
				break;
			}
			case 3: {
				// 运行停止服务
				try {
					util.log("当前状态: " + (service != null && service.isAlive() ? "运行中" : "未运行"));
					util.log("1 -> 开始运行\n" + "2 -> 停止运行");
					int choice = sc.nextInt();
					if (choice == 1) {
						if (service != null && service.isAlive())
							util.log("云服务器已在运行");
						else {
							// 开始运行
							if (service != null) {
								service.close();
								service = null;
							}
							service = new Service();
							service.start();
						}
					} else {
						while (service != null && service.isAlive()) {
							service.close();
						}
						util.log("服务已停止");
					}
				} finally {
					status = 0xff;
				}
				break;
			}
			case 4: {
				try {
					// 和智能家具通信
					util.log("请输入本地服务器密钥");
					String key = sc.next();
					ClientDevice device = devices.get(key);
					if (device == null) {
						util.log("不存在的本地服务器");
						break;
					}
					util.log("请输入智能家具名");
					String name = sc.next();
					for (;;) {
						util.log("请输入内容(键入exit退出)");
						String data = sc.next();
						if ("exit".equals(data))
							continue circle;
						String json = MessageFactory.wrapCloudMessage("CloudServer", key, name, data);
						if (!device.sendMessage(json)) {
							util.log("和本地服务器连接断开");
							continue circle;
						}
						String result = device.receiveMessage();
						if (result == null) {
							util.log("和本地服务器连接断开");
							continue circle;
						}
						Message retVal = MessageFactory.unwrapMessage(result);
						if (retVal.type == Message.rawType)
							util.log("收到消息: " + retVal.data);
						else if (retVal.type == ErrorMessage.type) {
							ErrorMessage err = MessageFactory.unwrapErrorMessage(result);
							util.log("收到错误消息: " + err.getMessage());
						} else
							util.log("未识别的消息: " + result);
					}

				} finally {
					status = 0xff;
				}
			}
			case 5: {
				try {
					SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss");
					while (blinker == this) {
						if (service != null && service.isAlive()) {
							// util.log("云服务器已在运行");
						} else {
							// 开始运行
							util.log("服务停止,尝试重启[" + format.format(new Date()) + "]");
							if (service != null) {
								service.close();
								service = null;
							}
							service = new Service();
							service.start();
						}
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
			}
			default: {
				String msg = "[云服务器]\n" + "1 -> 查看当前设备列表\n" + "2 -> 检测当前设备可用性\n" + "3 -> 运行/停止服务\n" + "4 -> 和智能家具通信\n"
						+ "5 -> 循环运行\n" + "0 -> 退出\n";
				util.log(msg);
				status = sc.nextInt();
				break;
			}
			}
		}
	}

	public CloudServerSimulator(int port) {
		this.port = port;
	}

	// 接收到新连接后,放入或更新设备
	@Override
	public boolean putDevice(ClientDevice device) {
		int initialStatus = 1;
		return putDevice(device, null, initialStatus);
	}

	public synchronized boolean putDevice(ClientDevice device, String result, int status) {
		while (blinker == this) {
			switch (status) {
			case 1: {
				// 从只包含sc的ClientDevice中获得注册信息
				String json = device.receiveMessage();
				if (json == null || "".equals(json)) {
					try {
						util.log("Connection Refused, no Message Received: " + device.sc.getRemoteAddress());
					} catch (Exception e) {
						util.log("Connection Refused, no Message Received.");
					}
					return false;
				}

				// 解析RegistryMessage
				RegistryMessage registryMessage = MessageFactory.unwrapRegistryMessage(json);
				if (registryMessage == null) {
					try {
						util.log("Connection Refused, no Registry: Message = " + json + " Device = "
								+ device.sc.getRemoteAddress());
					} catch (IOException e) {
						util.log("Connection Refused, no Registry: Message = " + json);
					}
					return false;
				}

				// 根据RegistryMessage完善Device信息,若为遥控器、控制器则创建ProducerDevice
				device.name = registryMessage.name;
				try {
					device.addr = (InetSocketAddress) device.sc.getRemoteAddress();
				} catch (IOException e) {
					util.log("Cannot get RemoteAddress: " + device.name);
				}
				if (registryMessage.deviceType == RegistryMessage.REMOTE_CONTROLLER
						|| registryMessage.deviceType == RegistryMessage.LOCAL_SERVER)
					device = new ProducerDevice(device);

				// 注册成功,添加到设备池;若已存在同名设备,则检测原有连接,若不健康则关闭原有连接并更新,若健康则拒绝新Device注册
				ClientDevice oldDevice = devices.get(device.name);
				if (oldDevice == null || !oldDevice.maybeValid()) {
					// 不存在密钥冲突
					devices.put(device.name, device);
					result = MessageFactory.wrapRegistryMessage(device.name, RegistryMessage.RESULT); // 注册成功
					util.log("Add New " + (registryMessage.deviceType == RegistryMessage.REMOTE_CONTROLLER
							? "RemoteController" : "LocalDeviceManager") + " Device: " + device);
					status = 3;
					break;
				} else {
					final ClientDevice deviceFinal = device;
					final ClientDevice oldDeviceFinal = oldDevice;
					final RegistryMessage registryMessageFinal = registryMessage;
					oldDevice.checkValid(new Runnable() {
						@Override
						public void run() {
							// 存在密钥冲突,且原连接有效
							String result = MessageFactory.wrapRegistryMessage(ErrorMessage.s3, RegistryMessage.RESULT); // 拒绝注册
							util.log("Already Exists Device: " + oldDeviceFinal + " Refuse: " + deviceFinal);
							putDevice(deviceFinal, result, 5); // 回传拒绝注册消息
						}

					}, new Runnable() {
						@Override
						public void run() {
							// 存在密钥冲突,且原连接无效
							devices.put(deviceFinal.name, deviceFinal);
							oldDeviceFinal.close();
							String result = MessageFactory.wrapRegistryMessage(deviceFinal.name,
									RegistryMessage.RESULT); // 注册成功
							util.log("Update New "
									+ (registryMessageFinal.deviceType == RegistryMessage.REMOTE_CONTROLLER
											? "RemoteController" : "LocalDeviceManager")
									+ " Device (Old Device Invalid): " + deviceFinal);
							putDevice(deviceFinal, result, 3); // 启动生产者线程
						}
					});
					return true;
				}
			}
			case 3: {
				// 启动生产者线程
				if (device instanceof ProducerDevice)
					status = 4;
				else
					status = 5;
				break;
			}
			case 4: {
				// 若为遥控器、控制器,则运行此设备的生产者线程
				ProducerService producer = new ProducerService(device);
				producers.put(device.name, producer);
				producer.start();
				status = 5;
				break;
			}
			case 5: {
				// 注册成功,发回echo消息
				if (!device.sendMessage(result)) {
					// echo失败,移除Device
					device.close();
					devices.remove(device.name);
					util.log("Echo Failure, Remove Device: " + device);
					return false;
				}
				util.log("Echo Success: Message = " + result + " Device = " + device);
				return true;
			}
			default:
				return false;
			}
		}
		return true;
	}

	class Service extends Thread {

		volatile Service blinker = this;
		ServerSocketChannel serverChannel = null;

		public void close() {
			blinker = null;
			service = null;
			try {
				// 停止接收新连接
				serverChannel.close();// 很关键
			} catch (IOException e) {
				util.log("ServerChannel.close() Failed");
			}
			// 关闭消费者线程
			if (consumer != null) {
				consumer.close();
				consumer = null;
			}
			synchronized (this) {
				// 关闭设备和相应生产者线程
				for (String name : devices.keySet())
					removeDevice(name);
				for (Iterator<ProducerService> it = producers.values().iterator(); it.hasNext();) {
					ProducerService producer = it.next();
					producer.close();
					it.remove();
				}
			}
		}

		// 主线程循环接收新连接
		@Override
		public void run() {
			// 开启消费者线程
			if (consumer != null)
				consumer.close();
			consumer = new ConsumerService();
			consumer.start();

			try {
				// 开启接收新连接
				serverChannel = ServerSocketChannel.open();
				if (serverChannel == null)
					throw new IOException();
			} catch (IOException e) {
				util.log("ServerSocketChannel.open() Error");
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

			// 循环接收新连接
			while (blinker == this) {
				SocketChannel sc = null;
				try {
					sc = serverChannel.accept();
					if (sc == null)
						throw new IOException();
				} catch (IOException e) {
					if (blinker != null)
						util.log("serverChannel.accept() Error");
					return;
				}

				// 接收注册消息生成ClientDevice,才开启生产者线程(非抢占式注册)
				ClientDevice device = new ClientDevice(sc);
				if (!putDevice(device)) {
					// 注册Device失败,继续监听新连接
					util.log("Device Put Error");
					continue;
				}
			}
		}
	}

	// 远程遥控器设备,close时同时关闭生产者线程;有前提的线程安全:在调用closeSocket时其它线程会出现不可预知的问题
	class ProducerDevice extends ClientDevice {
		public ProducerDevice(ClientDevice device) {
			super(device.sc);
			this.name = device.name;
			this.addr = device.addr;
		}

		@Override
		public synchronized boolean maybeValid() {
			return (super.maybeValid() && producers.containsKey(name) && producers.get(name).isAlive());
		}

		// 尽量调用close方法而不是closeSocket方法
		@Override
		public void close() {
			super.close();
			if (producers.containsKey(name)) {
				producers.get(name).close();
				producers.remove(name);
			}
		}
	}

	@Override
	public synchronized ClientDevice removeDevice(String name) {
		ClientDevice device = devices.remove(name);
		if (device != null)
			device.close();
		return device;
	}

	@Override
	public synchronized Collection<ClientDevice> getAllDevices() {
		return devices.values();
	}

	@Override
	public ClientDevice getDevice(String name) {
		return devices.get(name);
	}

}
