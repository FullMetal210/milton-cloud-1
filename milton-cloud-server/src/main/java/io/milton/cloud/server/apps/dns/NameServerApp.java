package io.milton.cloud.server.apps.dns;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;

import io.milton.cloud.server.apps.AppConfig;
import io.milton.cloud.server.apps.Application;
import io.milton.cloud.server.apps.LifecycleApplication;
import io.milton.cloud.server.manager.CurrentRootFolderService;
import io.milton.cloud.server.web.SpliffyResourceFactory;
import io.milton.common.Service;
import io.milton.dns.NameServer;
import io.milton.dns.Utils;
import io.milton.vfs.db.Organisation;
import io.milton.vfs.db.Website;
import io.milton.vfs.db.utils.SessionManager;

import java.net.Inet4Address;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

public class NameServerApp implements Application, LifecycleApplication {

    private static Logger log = Logger.getLogger(NameServerApp.class);
    static final String LISTEN = "listenOnList";
    static final String MC_IPV4 = "aRecord";
    static final String MC_IPV6 = "aaaaRecord";
    static final String MXSERVER = "defaultMxRecord";
    static final String NAMESERVERS = "nameServerList";
    static final String PRIMARY_NS = "primaryNameserver";
    static final String ADMIN_EMAIL = "nsAdminEmail";

    private SpliffyZoneFactory zoneFactory;
    private NameServer nameserver;

    @Override
    public String getInstanceId() {
        return "nameserver";
    }

    @Override
    public void init(SpliffyResourceFactory resourceFactory, AppConfig config) throws Exception {
   
    	SessionManager sessionManager = resourceFactory.getSessionManager();
    	zoneFactory = new SpliffyZoneFactory(sessionManager);
    	initZoneData(config);
    	initResourceRecords(config);
    	InetSocketAddress[] addrs = initServerConnection(config);
        nameserver = new NameServer(zoneFactory, addrs);
        nameserver.addFilter(new SessionFilter(sessionManager));
        nameserver.start();
    }

    private void initZoneData(AppConfig config) {
    	
        String nsValue = config.get(NAMESERVERS);
        String primaryNsValue = config.get(PRIMARY_NS);
        String emailValue = config.get(ADMIN_EMAIL);
        
        if ( StringUtils.isBlank(StringUtils.remove(nsValue, ','))) {
        	throw new RuntimeException("Please specify " + NAMESERVERS);
        }
        if ( StringUtils.isBlank(primaryNsValue) ) {
        	log.warn("No primary master nameserver configured");
        }
        if ( StringUtils.isBlank(emailValue) ) {
        	log.warn("No admin email configured");
        	String host = config.getContext().get(CurrentRootFolderService.class).getPrimaryDomain();
        	emailValue = "admin@" + host;
        }

        String[] stringArray = nsValue.split(",");
        List<String> nameServers = new LinkedList<String>();
        for ( String s : stringArray ) {
        	nameServers.add(s.trim());
        }
        zoneFactory.setNsNames(nameServers);
        if ( StringUtils.isBlank(primaryNsValue) ) {
        	primaryNsValue = nameServers.get(0);
        }
        zoneFactory.setPrimaryNs(primaryNsValue.trim());
        zoneFactory.setAdminEmail(emailValue.trim());
    }
    
    private void initResourceRecords(AppConfig config) {
    	
        String serverIp4Value = config.get(MC_IPV4);
        String serverIp6Value = config.get(MC_IPV6);
        String mxValue = config.get(MXSERVER);
        
    	/* Set Milton-Cloud's A Record */
        if ( !StringUtils.isBlank(serverIp4Value) ) {
            try {
            	InetAddress addr = InetAddress.getByName(serverIp4Value);
                if ( addr instanceof Inet4Address ) {
                	zoneFactory.setARecord((Inet4Address) addr);
            	} else {
            		log.warn("Invalid IPv4 address");
            	}
            } catch (UnknownHostException e) {
            	log.warn("Invalid IPv4 address: " + e.getMessage());
            }
    	}
        
        /* Set Milton-Cloud's AAAA Record */
        if ( !StringUtils.isBlank(serverIp6Value) ) {
            try {
                InetAddress addr = InetAddress.getByName(serverIp6Value);
                if ( addr instanceof Inet6Address ) {
                	zoneFactory.setAaaaRecord((Inet6Address) addr);
            	} else {
            		log.warn("Invalid IPv6 address");
            	}
            } catch (UnknownHostException e) {
            	log.warn("Invalid IPv6 address: " + e.getMessage());
            }
        }

        /* Set Milton-Cloud's MX Record */
        if ( !StringUtils.isBlank(mxValue) ) {
        	zoneFactory.setMxRecord(mxValue.trim());
        }
    }
    
    private InetSocketAddress[] initServerConnection(AppConfig config) throws UnknownHostException {
    	
    	String listenOnValue = config.get(LISTEN);
    	log.info("init: listenon=" + listenOnValue);
    	
    	/* Set the IP:port combinations for server to bind to */
        InetSocketAddress[] addrs;
        if ( !StringUtils.isBlank(listenOnValue) ) {
        	 String[] stringArray = listenOnValue.split(",");
        	 addrs = new InetSocketAddress[stringArray.length];
             for (int j = 0; j < stringArray.length; j++) {
            	 String s = stringArray[j];
                 int port = 53;
                 if (s.indexOf(":") != -1) {
                     String[] s2 = s.split(":");
                     s = s2[0].trim();
                     port = Integer.parseInt(s2[1].trim());
                 }
                 InetAddress addr = InetAddress.getByName(s);
                 addrs[j] = new InetSocketAddress(addr, port);
                 log.info("listening on " + addr.getHostAddress());
             }
        } else {
        	addrs = new InetSocketAddress[]{ new InetSocketAddress(53) };
        }
       return addrs;
    }
    
    @Override
    public void shutDown() {
        if (nameserver != null) {
            nameserver.stop();
        }
    }

    @Override
    public void initDefaultProperties(AppConfig config) {
    	
        config.add(LISTEN, "0.0.0.0:53");
        config.add(NAMESERVERS, "ns1.localhost, ns2.localhost");
        config.add(PRIMARY_NS, "ns1.localhost");
        config.add(ADMIN_EMAIL, "admin@localhost");
        config.add(MXSERVER, "mx1.localhost");
        config.add(MC_IPV4, " ");
        config.add(MC_IPV6, " ");
        
        InetAddress addr = Utils.probeIp();
        if (addr != null && addr instanceof Inet4Address ) {
        	config.add(MC_IPV4, addr.getHostAddress());
        } else if (addr !=null && addr instanceof Inet6Address) {
        	config.add(MC_IPV6, addr.getHostAddress());
        } else {
        	config.add(MC_IPV4, "127.0.0.1");
        }
    }

    @Override
    public String getTitle(Organisation organisation, Website website) {
        return "DNS Nameserver";
    }

    @Override
    public String getSummary(Organisation organisation, Website website) {
        return "Runs an authoritative-only nameserver in the background, which can answer queries " +
        		"for any websites that have been created. Requires configuration.";
        		
    }
    
}
