package message;

public class RegistryMessage implements MessageInterface {
	public final static int type = 1;
	public final static int LOCAL_SERVER = 1; //本地服务器
	public final static int REMOTE_CONTROLLER = 0; //遥控器
	public final static int RESULT = 2; //注册响应消息
	public String name; // 遥控器或本地服务器密钥
	public int deviceType=0xff; 

	@Override
	public boolean checkNotNull() {
		return (type != 0xff && name != null && !"".equals(name) && 
				(deviceType==LOCAL_SERVER || deviceType==REMOTE_CONTROLLER || deviceType==RESULT));
	}
}
