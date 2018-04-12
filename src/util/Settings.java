package util;

import java.nio.charset.Charset;

public class Settings {
	public static final Util util=new JavaUtil();
	public static final Charset charset=Charset.forName("gb2312");
	public static final String Sign ="KELLES";
	public static final String ToKeyStr ="ToKey"; //遥控器里保存对端密钥
	public static final String ToNameStr ="Name"; //遥控器里保存对端设备名
	public static final String KeyStr ="Key"; //本地设备管理器密钥
	public static final String LocalDeviceNameStr ="LocalName"; //本地智能家具名
	public static final String LocalDeviceAddressStr ="DeviceAddress"; //本地智能家具IP地址
	public static final String LocalDevicePortStr ="DevicePort"; //本地智能家具端口
	public static final String FirstTimeOpenStr ="FirstTime"; //是否第一次打开app
	public static final String TimeManagerStr ="TimeManager"; //是否第一次打开app
	public static final long ConnectTimeout =2000; //使用CheckValid和连接至云服务器时超时
	public static final long SocketTimeout =500; //TickerManager超时
	
	public static final int DeviceWifiSimulatorPort=8000;
	public static final String DeviceWifiSimulatorAddress="192.168.1.233";
	public static final String DeviceWifiSimulatorName="EN-BOYS-DEVICE";

	public static final String CloudServerAddressStr="CloudServerAddress";
	public static final String CloudServerPortStr="CloudServerPort";
	public static final String CloudServerAddress="119.23.51.183";
	public static final int CloudServerPort=8001;
	
	public static final int LocalDeviceManagerPort=8002;
	public static final String LocalDeviceManagerAddress="192.168.1.233";
	public static final String LocalDeviceManagerKey="EN-BOYS-KEY";

}
