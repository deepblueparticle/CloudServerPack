package message;

public class HeartbeatMessage implements MessageInterface{
	public static final int type=4;
	public static final int VALID=0; //回传,表示有效
	public static final int REQUEST=1; //请求回传
	public int status=0xff;
	@Override
	public boolean checkNotNull() {
		return (status==VALID || status==REQUEST);
	}

}
