package message;

public class ErrorMessage implements MessageInterface{
	
	public static final int type=3;
	
	public static final int c1=1;
	public static final String s1="找到本地服务器,但未发现指定设备";
	
	public static final int c2=2;
	public static final String s2="找到本地服务器和指定设备,但与设备的连接中断";
	
	public static final int c3=3;
	public static final String s3="注册失败,当前密钥已存在";
	
	public static final int c4=4;
	public static final String s4="消息队列已满,无法处理请求";
	
	public static final int c5=5;
	public static final String s5="无效的消息";
	
	public static final int c6=6;
	public static final String s6="未找到本地服务器,无效的密钥";
	
	public static final int c7=7;
	public static final String s7="找到本地服务器,但与服务器的连接中断";
	
	public static final int c8=0xff;
	public static final String s8=null;
	public static final int c9=0xff;
	public static final String s9=null;
	
	public int code=0xff;
	
	public String getMessage(){
		if (code==c1) return s1;
		if (code==c2) return s2;
		if (code==c3) return s3;
		if (code==c4) return s4;
		if (code==c5) return s5;
		if (code==c6) return s6;
		if (code==c7) return s7;
		if (code==c8) return s8;
		if (code==c9) return s9;
		return null;
	}

	@Override
	public boolean checkNotNull() {
		return (code!=0xff);
	}
}
