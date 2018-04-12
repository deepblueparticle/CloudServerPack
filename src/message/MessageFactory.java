package message;

import com.google.gson.Gson;
import com.google.gson.JsonIOException;
import com.google.gson.JsonParseException;
import util.Settings;
import util.Util;

//包装解封各种消息
public class MessageFactory {
	static Gson gson = new Gson();
	static Util util = Settings.util;

	private MessageFactory() {
	};

	// 心跳消息
	public static String wrapHeartbeatRequestMessage() {
		return wrapHeartbeatMessage(HeartbeatMessage.REQUEST);
	}

	// 心跳消息
	public static String wrapHeartbeatResponseMessage() {
		return wrapHeartbeatMessage(HeartbeatMessage.VALID);
	}

	// 心跳消息
	public static String wrapHeartbeatMessage(int status) {
		HeartbeatMessage msg = new HeartbeatMessage();
		msg.status = status;

		Message base = new Message();
		base.data = gson.toJson(msg);
		base.type = HeartbeatMessage.type;
		return gson.toJson(base);
	}

	// 心跳消息
	public static HeartbeatMessage unwrapHeartbeatMessage(String json) {
		try {
			Message base = gson.fromJson(json, Message.class);
			if (base == null || base.type != HeartbeatMessage.type)
				return null;
			HeartbeatMessage msg = gson.fromJson(base.data, HeartbeatMessage.class);
			return msg;
		} catch (JsonParseException e) {
			util.log("JsonParseException: " + json);
			return null;
		}
	}

	// 纯文本消息
	public static String wrapMessage(String data) {
		Message base = new Message();
		base.data = data;
		base.type = Message.rawType;
		return gson.toJson(base);
	}

	// 纯文本消息
	public static Message unwrapMessage(String json) {
		try {
			Message base = gson.fromJson(json, Message.class);
			if (base == null || !base.checkNotNull())
				return null;
			return base;
		} catch (JsonParseException e) {
			util.log("JsonParseException: " + json);
			return null;
		}
	}

	// 错误
	public static String wrapErrorMessage(int code) {
		ErrorMessage msg = new ErrorMessage();
		msg.code = code;
		return wrapErrorMessage(msg);
	}
	
	//错误
	public static String wrapErrorMessage(ErrorMessage msg) {
		Message base = new Message();
		base.data = gson.toJson(msg);
		base.type = ErrorMessage.type;
		return gson.toJson(base);
	}

	// 错误
	public static ErrorMessage unwrapErrorMessage(String json) {
		try {
			Message base = gson.fromJson(json, Message.class);
			if (base == null || base.type != ErrorMessage.type)
				return null;
			ErrorMessage msg = gson.fromJson(base.data, ErrorMessage.class);
			return msg;
		} catch (JsonParseException e) {
			util.log("JsonParseException: " + json);
			return null;
		}
	}

	// 云服务器上的转发消息
	public static String wrapCloudMessage(CloudMessage msg) {
		Message base = new Message();
		base.data = gson.toJson(msg);
		base.type = CloudMessage.type;
		return gson.toJson(base);
	}

	// 云服务器上的转发消息
	public static String wrapCloudMessage(String fromKey, String toKey, String name, String data) {
		CloudMessage msg = new CloudMessage();
		msg.fromKey = fromKey;
		msg.toKey = toKey;
		msg.info = name;
		msg.data = data;

		Message base = new Message();
		base.data = gson.toJson(msg);
		base.type = CloudMessage.type;
		return gson.toJson(base);
	}

	// 云服务器上的转发消息;控制器根据收到消息包装为响应消息
	public static String wrapRetCloudMessage(CloudMessage src, String dataJson) {
		CloudMessage retCloudMsg=src.copy();
		retCloudMsg.fromKey=src.toKey;
		retCloudMsg.toKey=src.fromKey;
		retCloudMsg.data=dataJson;
		return wrapCloudMessage(retCloudMsg);
	}

	// 云服务器传送消息至本地服务器
	public static CloudMessage unwrapCloudMessage(String json) {
		try {
			Message base = gson.fromJson(json, Message.class);
			if (base == null || base.type != CloudMessage.type)
				return null;
			CloudMessage msg = gson.fromJson(base.data, CloudMessage.class);
			return msg;
		} catch (JsonParseException e) {
			util.log("JsonParseException: " + json);
			return null;
		}
	}

	// 注册
	public static String wrapRegistryMessage(String name, int deviceType) {
		RegistryMessage msg = new RegistryMessage();
		msg.name = name;
		msg.deviceType = deviceType;

		Message base = new Message();
		base.data = gson.toJson(msg);
		base.type = RegistryMessage.type;
		return gson.toJson(base);
	}

	// 注册
	public static RegistryMessage unwrapRegistryMessage(String json) {
		try {
			Message base = gson.fromJson(json, Message.class);
			if (base == null || base.type != RegistryMessage.type)
				return null;
			RegistryMessage msg = gson.fromJson(base.data, RegistryMessage.class);
			return msg;
		} catch (JsonParseException e) {
			util.log("JsonParseException: " + json);
			return null;
		}
	}

}
