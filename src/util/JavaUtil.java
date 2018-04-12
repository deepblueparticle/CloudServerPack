package util;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JavaUtil implements Util{
	
	public static void main(String[] args){
		JavaUtil util=new JavaUtil();
		for (InetAddress addr:util.getLocalAddrList()){
			util.log(addr+"");
		}
	}
	
	@Override
	public void log(String msg){
		System.out.println(msg);
	}
	
	@Override
	public InetAddress getLocalAddr(){
//		try {
//			return InetAddress.getLocalHost();
//		} catch (UnknownHostException e) {
//			return null;
//		}
		return getLocalAddr("192.168.1.");
	}

	public InetAddress getLocalAddr(String regix){
		Pattern p=Pattern.compile(regix);
		Matcher m=null;
		for (InetAddress addr:getLocalAddrList()){
			String ip=addr.getHostAddress();
			m=p.matcher(ip);
			if (m.find()) return addr;
		}
		return null;
	}
	
	public List<InetAddress> getLocalAddrList() {
        List<InetAddress> addrList = new ArrayList<InetAddress>();
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            Enumeration<InetAddress> inetAddresses;
            InetAddress inetAddress;
            while (networkInterfaces.hasMoreElements()) {
            	NetworkInterface networkInterface = networkInterfaces.nextElement();
                inetAddresses = networkInterface.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    inetAddress = inetAddresses.nextElement();
                    if (inetAddress != null && inetAddress instanceof Inet4Address) { // IPV4
                        addrList.add(inetAddress);
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return addrList;
    }
}
