cybergarage-upnp
================

Following is an example how you would use this project to forward ports on a router that supports UPnP

    public class UPnPForwarder implements ForwardPortCallback {
    
        private final UPnP upnp;
    
        public UPnPForwarder() {
            super();
            this.upnp = new UPnP();
        }
        
        public startUPnP() throws Exception {
            this.upnp.start();
        }
    
        public void forwardTCP(int port) {
            Set<ForwardPort> _p = new TreeSet<ForwardPort>();
            _p.add(new ForwardPort("YourApplication TCP", false, ForwardPort.PROTOCOL_TCP_IPV4, port));
            this.upnp.onChangePublicPorts(_p, this);
        }
    
        @Override
        public void portForwardStatus(Map<ForwardPort, ForwardPortStatus> statuses) {
            // the status of the ports will be received in this callback
        }
    }

