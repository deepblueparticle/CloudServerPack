package message;

import com.google.gson.Gson;

public class Message implements MessageInterface{
	public static final int rawType=0; //type为0时,回传纯文本信息
	static Gson gson=new Gson();
	public int type=0xff; 
	public String data;
	@Override
	public boolean checkNotNull() {
		return (type!=0xff && data!=null && !"".equals(data));
	}
	@Override
	public String toString() {
		return gson.toJson(this);
	}
	
}
