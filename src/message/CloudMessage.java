package message;

public class CloudMessage implements MessageInterface {
	public final static int type = 2;
	public final static String ErrorMessage="ErrorMessage";
	public String fromKey; // 遥控器密钥
	public String toKey; // 本地服务器密钥
	public String info; // 附带信息(一般为设备名,info为Error时,表示服务器回传错误消息)
	public String data; // 实际传输数据

	@Override
	public boolean checkNotNull() {
		//允许name为 
		return (fromKey != null && !"".equals(fromKey) 
				&& toKey != null && !"".equals(toKey) 
				&& info!=null && !"".equals(info)
				&& data != null && !"".equals(data));
	}

	@Override
	public String toString() {
		return "fromKey = "+fromKey+" toKey = "+toKey+" name = " + info + " data = " + data;
	}

	public CloudMessage copy(){
		CloudMessage copyMsg=new CloudMessage();
		copyMsg.fromKey=fromKey;
		copyMsg.toKey=toKey;
		copyMsg.info=info;
		copyMsg.data=data;
		return copyMsg;
	}
	

}
