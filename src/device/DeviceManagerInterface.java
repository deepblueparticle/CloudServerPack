package device;

import java.util.Collection;

public interface DeviceManagerInterface<DeviceImpl extends Device> {
	boolean putDevice(DeviceImpl device);

	DeviceImpl removeDevice(String name);

	Collection<DeviceImpl> getAllDevices();

	DeviceImpl getDevice(String name);
}
