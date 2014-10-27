package org.cybergarage.forwarder;

public class ForwardPort implements Comparable<ForwardPort> {

	/** Name of the interface e.g. "opennet" */
	public final String name;
	/** IPv4 vs IPv6? */
	public final boolean isIP6;
	/** Protocol number. See constants. */
	public final int protocol;
	public static final int PROTOCOL_UDP_IPV4 = 17;
	public static final int PROTOCOL_TCP_IPV4 = 6;
	/** Port number to forward */
	public final int internalPort;
	public final int externalPort;
	
	// We don't currently support binding to a specific internal interface.
	// It would be complicated: Different interfaces may be on different LANs,
	// and an IGD is normally on only one LAN.
	private final int hashCode;
	
	public ForwardPort(String name, boolean isIP6, int protocol, int internalPort, int externalPort) {
		this.name = name;
		this.isIP6 = isIP6;
		this.protocol = protocol;
		this.internalPort = internalPort;
		this.externalPort = externalPort;
		hashCode = name.hashCode() | (isIP6 ? 1 : 0) | protocol | internalPort | externalPort;
	}
	
	@Override
	public int hashCode() {
		return hashCode;
	}
	
	@Override
	public boolean equals(Object o) {
		if(o == this) return true;
		if(!(o instanceof ForwardPort)) return false;
		ForwardPort f = (ForwardPort) o;
		return (f.name.equals(name)) && f.isIP6 == isIP6 && f.protocol == protocol && f.internalPort == internalPort && f.externalPort == externalPort;
	}

	@Override
	public int compareTo(ForwardPort arg0) {
		if (this.equals(arg0))
			return 0;
		return this.name.compareTo(arg0.name);
	}
}
