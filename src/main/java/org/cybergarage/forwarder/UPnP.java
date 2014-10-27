package org.cybergarage.forwarder;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.TreeSet;

import org.cybergarage.upnp.Action;
import org.cybergarage.upnp.ActionList;
import org.cybergarage.upnp.Argument;
import org.cybergarage.upnp.ArgumentList;
import org.cybergarage.upnp.ControlPoint;
import org.cybergarage.upnp.Device;
import org.cybergarage.upnp.DeviceList;
import org.cybergarage.upnp.Service;
import org.cybergarage.upnp.ServiceList;
import org.cybergarage.upnp.ServiceStateTable;
import org.cybergarage.upnp.StateVariable;
import org.cybergarage.upnp.device.DeviceChangeListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UPnP extends ControlPoint implements DeviceChangeListener {

	public static final Logger log = LoggerFactory.getLogger(UPnP.class);
	
	/** some schemas */
	private static final String ROUTER_DEVICE = "urn:schemas-upnp-org:device:InternetGatewayDevice:1";
	private static final String WAN_DEVICE = "urn:schemas-upnp-org:device:WANDevice:1";
	private static final String WANCON_DEVICE = "urn:schemas-upnp-org:device:WANConnectionDevice:1";
	private static final String WAN_IP_CONNECTION = "urn:schemas-upnp-org:service:WANIPConnection:1";
	private static final String WAN_PPP_CONNECTION = "urn:schemas-upnp-org:service:WANPPPConnection:1";

	private Device _router;
	private Service _service;
	private boolean isDisabled = false; // We disable the plugin if more than
										// one IGD is found
	private final Object lock = new Object();
	// FIXME: detect it for real and deal with it! @see #2524
	private volatile boolean thinksWeAreDoubleNatted = false;

	/** List of ports we want to forward */
	private Set<ForwardPort> portsToForward;
	/** List of ports we have actually forwarded */
	private Set<ForwardPort> portsForwarded;
	/** Callback to call when a forward fails or succeeds */
	private ForwardPortCallback forwardCallback;

	public UPnP() {
		super();
		portsForwarded = new HashSet<ForwardPort>();
		addDeviceChangeListener(this);
	}

	public void terminate() {
		unregisterPortMappings();
		super.stop();
	}

	public DetectedIP[] getAddress() {
		log.debug("UP&P.getAddress() is called \\o/");
		if (isDisabled) {
			log.debug("Plugin has been disabled previously, ignoring request.");
			return null;
		} else if (!isNATPresent()) {
			log.debug("No UP&P device found, detection of the external ip address using the plugin has failed");
			return null;
		}

		DetectedIP result = null;
		final String natAddress = getNATAddress();
		try {
			InetAddress detectedIP = InetAddress.getByName(natAddress);
			short status = DetectedIP.NOT_SUPPORTED;
			thinksWeAreDoubleNatted = !IPUtil.isValidAddress(detectedIP, false);
			// If we have forwarded a port AND we don't have a private address
			if ((portsForwarded.size() > 1) && (!thinksWeAreDoubleNatted))
				status = DetectedIP.FULL_INTERNET;

			result = new DetectedIP(detectedIP, status);

			log.debug("Successful UP&P discovery :" + result);

			return new DetectedIP[] { result };
		} catch (UnknownHostException e) {
			log.info("UP&P discovery has failed: unable to resolve " + result);
			return null;
		}
	}

	public void deviceAdded(Device dev) {
		synchronized (lock) {
			if (isDisabled) {
				log.debug("Plugin has been disabled previously, ignoring new device.");
				return;
			}
		}
		if (!ROUTER_DEVICE.equals(dev.getDeviceType()) || !dev.isRootDevice())
			return; // Silently ignore non-IGD devices
		else if (isNATPresent()) {
			log.info("The UP&P plugin has found more than one IGD on the network, as a result it will be disabled");
			isDisabled = true;

			synchronized (lock) {
				_router = null;
				_service = null;
			}

			stop();
			return;
		}

		log.debug("UP&P IGD found : " + dev.getFriendlyName() + " " + dev.getLocation() + " " + dev.getHTTPPort());
		synchronized (lock) {
			_router = dev;
		}

		discoverService();
		// We have found the device we need: stop the listener thread
		stop();
		synchronized (lock) {
			if (_service == null) {
				log.info("The IGD device we got isn't suiting our needs, let's disable the plugin");
				isDisabled = true;
				_router = null;
				return;
			}
		}
		registerPortMappings();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void registerPortMappings() {
		Set ports;
		synchronized (lock) {
			ports = portsToForward;
		}
		if (ports == null)
			return;
		registerPorts(ports);
	}

	/**
	 * Traverses the structure of the router device looking for the port mapping
	 * service.
	 */
	@SuppressWarnings("rawtypes")
	private void discoverService() {
		synchronized (lock) {
			for (Iterator iter = _router.getDeviceList().iterator(); iter
					.hasNext();) {
				Device current = (Device) iter.next();
				if (!current.getDeviceType().equals(WAN_DEVICE))
					continue;

				DeviceList l = current.getDeviceList();
				for (int i = 0; i < current.getDeviceList().size(); i++) {
					Device current2 = l.getDevice(i);
					if (!current2.getDeviceType().equals(WANCON_DEVICE))
						continue;

					_service = current2.getService(WAN_PPP_CONNECTION);
					if (_service == null) {
						log.debug(_router.getFriendlyName()
										+ " doesn't seems to be using PPP; we won't be able to extract bandwidth-related informations out of it.");
						_service = current2.getService(WAN_IP_CONNECTION);
						if (_service == null) {
							log.debug(_router.getFriendlyName()
											+ " doesn't export WAN_IP_CONNECTION either: we won't be able to use it!");
						} else {
							log.debug("SCDPURL: " + _service.getSCPDURL());
						}
					}

					return;
				}
			}
		}
	}

	public boolean tryAddMapping(String protocol, int internal, int external, String description,
			ForwardPort fp) {
		log.info("UPnP: Registering a port mapping for " + internal + " -> " + external + " " + protocol);
		int nbOfTries = 0;
		boolean isPortForwarded = false;
		while (nbOfTries++ < 5) {
			isPortForwarded = addMapping(protocol, internal, external, "Olive " + description, fp);
			if (isPortForwarded)
				break;
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
			}
		}
		log.info("UPnP: "
						+ (isPortForwarded ? "Mapping is successful!"
								: "Mapping has failed!") + " (" + nbOfTries
						+ " tries)");
		return isPortForwarded;
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void unregisterPortMappings() {
		Set ports;
		synchronized (lock) {
			ports = portsForwarded;
		}
		this.unregisterPorts(ports);
	}

	public void deviceRemoved(Device dev) {
		synchronized (lock) {
			if (_router == null)
				return;
			if (_router.equals(dev)) {
				_router = null;
				_service = null;
			}
		}
	}

	/**
	 * @return whether we are behind an UPnP-enabled NAT/router
	 */
	public boolean isNATPresent() {
		return _router != null && _service != null;
	}

	/**
	 * @return the external address the NAT thinks we have. Blocking. null if we
	 *         can't find it.
	 */
	public String getNATAddress() {
		if (!isNATPresent())
			return null;

		Action getIP = _service.getAction("GetExternalIPAddress");
		if (getIP == null || !getIP.postControlAction())
			return null;

		return (getIP.getOutputArgumentList()
				.getArgument("NewExternalIPAddress")).getValue();
	}

	/**
	 * @return the reported upstream bit rate in bits per second. -1 if it's not
	 *         available. Blocking.
	 */
	public int getUpstramMaxBitRate() {
		if (!isNATPresent() || thinksWeAreDoubleNatted)
			return -1;

		Action getIP = _service.getAction("GetLinkLayerMaxBitRates");
		if (getIP == null || !getIP.postControlAction())
			return -1;

		return Integer.valueOf(getIP.getOutputArgumentList()
				.getArgument("NewUpstreamMaxBitRate").getValue());
	}

	/**
	 * @return the reported downstream bit rate in bits per second. -1 if it's
	 *         not available. Blocking.
	 */
	public int getDownstreamMaxBitRate() {
		if (!isNATPresent() || thinksWeAreDoubleNatted)
			return -1;

		Action getIP = _service.getAction("GetLinkLayerMaxBitRates");
		if (getIP == null || !getIP.postControlAction())
			return -1;

		return Integer.valueOf(getIP.getOutputArgumentList()
				.getArgument("NewDownstreamMaxBitRate").getValue());
	}

	private void listStateTable(Service serv, StringBuilder sb) {
		ServiceStateTable table = serv.getServiceStateTable();
		sb.append("<div><small>");
		for (int i = 0; i < table.size(); i++) {
			StateVariable current = table.getStateVariable(i);
			sb.append(current.getName() + " : " + current.getValue() + "<br>");
		}
		sb.append("</small></div>");
	}

	private void listActionsArguments(Action action, StringBuilder sb) {
		ArgumentList ar = action.getArgumentList();
		for (int i = 0; i < ar.size(); i++) {
			Argument argument = ar.getArgument(i);
			if (argument == null)
				continue;
			sb.append("<div><small>argument (" + i + ") :" + argument.getName()
					+ "</small></div>");
		}
	}

	private void listActions(Service service, StringBuilder sb) {
		ActionList al = service.getActionList();
		for (int i = 0; i < al.size(); i++) {
			Action action = al.getAction(i);
			if (action == null)
				continue;
			sb.append("<div>action (" + i + ") :" + action.getName());
			listActionsArguments(action, sb);
			sb.append("</div>");
		}
	}

	private String toString(String action, String Argument, Service serv) {
		Action getIP = serv.getAction(action);
		if (getIP == null || !getIP.postControlAction())
			return null;

		Argument ret = getIP.getOutputArgumentList().getArgument(Argument);
		return ret.getValue();
	}

	// TODO: extend it! RTFM
	private void listSubServices(Device dev, StringBuilder sb) {
		ServiceList sl = dev.getServiceList();
		for (int i = 0; i < sl.size(); i++) {
			Service serv = sl.getService(i);
			if (serv == null)
				continue;
			sb.append("<div>service (" + i + ") : " + serv.getServiceType()
					+ "<br>");
			if ("urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1"
					.equals(serv.getServiceType())) {
				sb.append("WANCommonInterfaceConfig");
				sb.append(" status: "
						+ toString("GetCommonLinkProperties",
								"NewPhysicalLinkStatus", serv));
				sb.append(" type: "
						+ toString("GetCommonLinkProperties",
								"NewWANAccessType", serv));
				sb.append(" upstream: "
						+ toString("GetCommonLinkProperties",
								"NewLayer1UpstreamMaxBitRate", serv));
				sb.append(" downstream: "
						+ toString("GetCommonLinkProperties",
								"NewLayer1DownstreamMaxBitRate", serv) + "<br>");
			} else if ("urn:schemas-upnp-org:service:WANPPPConnection:1"
					.equals(serv.getServiceType())) {
				sb.append("WANPPPConnection");
				sb.append(" status: "
						+ toString("GetStatusInfo", "NewConnectionStatus", serv));
				sb.append(" type: "
						+ toString("GetConnectionTypeInfo",
								"NewConnectionType", serv));
				sb.append(" upstream: "
						+ toString("GetLinkLayerMaxBitRates",
								"NewUpstreamMaxBitRate", serv));
				sb.append(" downstream: "
						+ toString("GetLinkLayerMaxBitRates",
								"NewDownstreamMaxBitRate", serv) + "<br>");
				sb.append(" external IP: "
						+ toString("GetExternalIPAddress",
								"NewExternalIPAddress", serv) + "<br>");
			} else if ("urn:schemas-upnp-org:service:Layer3Forwarding:1"
					.equals(serv.getServiceType())) {
				sb.append("Layer3Forwarding");
				sb.append("DefaultConnectionService: "
						+ toString("GetDefaultConnectionService",
								"NewDefaultConnectionService", serv));
			} else if (WAN_IP_CONNECTION.equals(serv.getServiceType())) {
				sb.append("WANIPConnection");
				sb.append(" status: "
						+ toString("GetStatusInfo", "NewConnectionStatus", serv));
				sb.append(" type: "
						+ toString("GetConnectionTypeInfo",
								"NewConnectionType", serv));
				sb.append(" external IP: "
						+ toString("GetExternalIPAddress",
								"NewExternalIPAddress", serv) + "<br>");
			} else if ("urn:schemas-upnp-org:service:WANEthernetLinkConfig:1"
					.equals(serv.getServiceType())) {
				sb.append("WANEthernetLinkConfig");
				sb.append(" status: "
						+ toString("GetEthernetLinkStatus",
								"NewEthernetLinkStatus", serv) + "<br>");
			} else
				sb.append("~~~~~~~ " + serv.getServiceType());
			listActions(serv, sb);
			listStateTable(serv, sb);
			sb.append("</div>");
		}
	}

	private void listSubDev(String prefix, Device dev, StringBuilder sb) {
		sb.append("<div><p>Device : " + dev.getFriendlyName() + " - "
				+ dev.getDeviceType() + "<br>");
		listSubServices(dev, sb);

		DeviceList dl = dev.getDeviceList();
		for (int j = 0; j < dl.size(); j++) {
			Device subDev = dl.getDevice(j);
			if (subDev == null)
				continue;

			sb.append("<div>");
			listSubDev(dev.getFriendlyName(), subDev, sb);
			sb.append("</div></div>");
		}
		sb.append("</p></div>");
	}

	private boolean addMapping(String protocol, int internal, int external, String description, ForwardPort fp) {
		if (isDisabled || !isNATPresent() || _router == null)
			return false;

		// Just in case...
		removeMapping(protocol, internal, external, fp, true);

		Action add = _service.getAction("AddPortMapping");
		if (add == null) {
			log.debug("Couldn't find AddPortMapping action!");
			return false;
		}

		add.setArgumentValue("NewRemoteHost", "");
		add.setArgumentValue("NewExternalPort", external);
		add.setArgumentValue("NewInternalClient", _router.getInterfaceAddress());
		add.setArgumentValue("NewInternalPort", internal);
		add.setArgumentValue("NewProtocol", protocol);
		add.setArgumentValue("NewPortMappingDescription", description);
		add.setArgumentValue("NewEnabled", "1");
		add.setArgumentValue("NewLeaseDuration", 0);

		if (add.postControlAction()) {
			synchronized (lock) {
				portsForwarded.add(fp);
			}
			return true;
		} else
			return false;
	}

	private boolean removeMapping(String protocol, int internal, int external, ForwardPort fp,
			boolean noLog) {
		if (isDisabled || !isNATPresent())
			return false;

		Action remove = _service.getAction("DeletePortMapping");
		if (remove == null) {
			log.debug("Couldn't find DeletePortMapping action!");
			return false;
		}

		// remove.setArgumentValue("NewRemoteHost", "");
		remove.setArgumentValue("NewExternalPort", external);
		remove.setArgumentValue("NewProtocol", protocol);

		boolean retval = remove.postControlAction();
		synchronized (lock) {
			portsForwarded.remove(fp);
		}

		if (!noLog)
			log.info("UPnP: Removed mapping for external " + fp.name + " "
					+ external + " / " + protocol);
		return retval;
	}

	public void onChangePublicPorts(Set<ForwardPort> ports,
			ForwardPortCallback cb) {
		Set<ForwardPort> portsToDumpNow = null;
		Set<ForwardPort> portsToForwardNow = null;
		log.info("UP&P Forwarding " + ports.size() + " ports...");
		synchronized (lock) {
			if (forwardCallback != null && forwardCallback != cb && cb != null) {
				log.debug("ForwardPortCallback changed from "
						+ forwardCallback + " to " + cb
						+ " - using new value, but this is very strange!");
			}
			forwardCallback = cb;
			if (portsToForward == null || portsToForward.isEmpty()) {
				portsToForward = ports;
				portsToForwardNow = ports;
				portsToDumpNow = null;
			} else if (ports == null || ports.isEmpty()) {
				portsToDumpNow = portsToForward;
				portsToForward = ports;
				portsToForwardNow = null;
			} else {
				// Some ports to keep, some ports to dump
				// Ports in ports but not in portsToForwardNow we must forward
				// Ports in portsToForwardNow but not in ports we must dump
				for (ForwardPort port : ports) {
					if (portsToForward.contains(port)) {
						// We have forwarded it, and it should be forwarded,
						// cool.
					} else {
						// Needs forwarding
						if (portsToForwardNow == null)
							portsToForwardNow = new HashSet<ForwardPort>();
						portsToForwardNow.add(port);
					}
				}
				for (ForwardPort port : portsToForward) {
					if (ports.contains(port)) {
						// Should be forwarded, has been forwarded, cool.
					} else {
						// Needs dropping
						if (portsToDumpNow == null)
							portsToDumpNow = new HashSet<ForwardPort>();
						portsToDumpNow.add(port);
					}
				}
				portsToForward = ports;
			}
			if (_router == null) {
				log.info("UP&P _router == null, when one is found, we will do the forwards");
				return; // When one is found, we will do the forwards
			}
		}
		if (portsToDumpNow != null)
			unregisterPorts(portsToDumpNow);
		if (portsToForwardNow != null)
			registerPorts(portsToForwardNow);
	}

	private void registerPorts(Set<ForwardPort> portsToForwardNow) {
		log.debug("UP&P registerPorts - " + portsToForwardNow.size());
		for (ForwardPort port : portsToForwardNow) {
			String proto;
			if (port.protocol == ForwardPort.PROTOCOL_UDP_IPV4)
				proto = "UDP";
			else if (port.protocol == ForwardPort.PROTOCOL_TCP_IPV4)
				proto = "TCP";
			else {
				HashMap<ForwardPort, ForwardPortStatus> map = new HashMap<ForwardPort, ForwardPortStatus>();
				map.put(port, new ForwardPortStatus(
						ForwardPortStatus.DEFINITE_FAILURE,
						"Protocol not supported", port.externalPort));
				forwardCallback.portForwardStatus(map);
				continue;
			}
			if (tryAddMapping(proto, port.internalPort, port.externalPort, port.name, port)) {
				HashMap<ForwardPort, ForwardPortStatus> map = new HashMap<ForwardPort, ForwardPortStatus>();
				map.put(port, new ForwardPortStatus(
						ForwardPortStatus.MAYBE_SUCCESS,
						"Port apparently forwarded by UPnP", port.externalPort));
				forwardCallback.portForwardStatus(map);
				continue;
			} else {
				HashMap<ForwardPort, ForwardPortStatus> map = new HashMap<ForwardPort, ForwardPortStatus>();
				map.put(port, new ForwardPortStatus(
						ForwardPortStatus.PROBABLE_FAILURE,
						"UPnP port forwarding apparently failed",
						port.externalPort));
				forwardCallback.portForwardStatus(map);
				continue;
			}
		}
	}

	private void unregisterPorts(Set<ForwardPort> portsToForwardNow) {
		
		Set<ForwardPort> rem = new TreeSet<ForwardPort>(portsToForwardNow);
		
		for (ForwardPort port : rem) {
			String proto;
			if (port.protocol == ForwardPort.PROTOCOL_UDP_IPV4)
				proto = "UDP";
			else if (port.protocol == ForwardPort.PROTOCOL_TCP_IPV4)
				proto = "TCP";
			else {
				// Ignore, we've already complained about it
				continue;
			}
			removeMapping(proto, port.internalPort, port.externalPort, port, false);
		}
	}

	@SuppressWarnings("unchecked")
	public static void main(String[] args) throws Exception {
		UPnP upnp = new UPnP();
		upnp.start();
		upnp.search();
		
		ControlPoint cp = new ControlPoint();
		log.debug("Searching for up&p devices:");
	
		while (true) {
			DeviceList list = cp.getDeviceList();
			log.debug("Found " + list.size() + " devices!");
			StringBuilder sb = new StringBuilder();
			Iterator<Device> it = list.iterator();
			while (it.hasNext()) {
				Device device = it.next();
				upnp.listSubDev(device.toString(), device, sb);
				log.debug("Here is the listing for "
						+ device.toString() + " :");
				log.debug(sb.toString());
				sb = new StringBuilder();
			}
			log.debug("End");
			Thread.sleep(2000);
		}
	}
}