package io.milton.cloud.server.apps.dns;

import io.milton.dns.Zone;
import io.milton.dns.ZoneFactory;
import io.milton.dns.ZoneInfo;
import io.milton.dns.record.AddressRecord;
import io.milton.dns.record.MailExchangeRecord;
import io.milton.dns.record.ResourceRecord;
import io.milton.vfs.db.Website;
import io.milton.vfs.db.utils.SessionManager;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.*;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Nick
 *
 */
public class SpliffyZoneFactory implements ZoneFactory {

	private static final Logger logger = LoggerFactory.getLogger(SpliffyZoneFactory.class);
	
    private Inet4Address ipv4Address;
    private Inet6Address ipv6Address;
    private String primaryNs;
    private String adminEmail;
    private List<String> nsNames;
    private String defaultMx;
    private int defaultTtl = 7200;
    private SessionManager sessionManager;

    public SpliffyZoneFactory(SessionManager sessionManager) {
        if (sessionManager == null) {
            throw new RuntimeException("null sessionManager");
        }
        this.sessionManager = sessionManager;
   
    }
    
	@Override
	public Zone findBestZone(String domain) {
		
		String queryDomain = domain.toLowerCase(); 
		domain = domain.toLowerCase();
		Session session = null;
		try {
			
			session = sessionManager.open();
			Website bottomWebsite =null; 
			while (domain != null) {
				bottomWebsite = Website.findByDomainNameDirect(domain, session);
				if ( bottomWebsite != null ) {
					break;
				}
				domain = parent(domain);
			}
			
			Map<String, Website> cache = new TreeMap<>();
			Website topWebsite = bottomWebsite;
			String parentDomain = domain;
			while (topWebsite != null) {
				logger.info("caching Website for " + parentDomain);
				cache.put(parentDomain, topWebsite);
				domain = parentDomain;
				parentDomain = parent(domain);
				if ( parentDomain == null ) {
					break;
				}
				topWebsite = Website.findByNameDirect(parentDomain, session);
			}
			if (domain == null ) {
				return null;
			}
			logger.info("domain: " + domain + ", queryDomain: " + queryDomain);
			return new SpliffyZone(domain, cache, queryDomain);
		} finally {
			if ( session != null && session.isOpen() ) {
				session.close();
			}
		}
	}
	
	static String parent(String domain) {
		int firstDot = domain.indexOf('.');
        if (firstDot <= 0 || firstDot == domain.length() - 1) {
            return null;
        }
        return domain.substring(firstDot + 1);
	}
	
	static String child(String domain, String subdomain) {
		return subdomain + "." + domain;
	}
	

    public String getDefaultMx() {
        return defaultMx;
    }

    public void setDefaultMx(String defaultMx) {
        this.defaultMx = defaultMx;
    }

    public int getDefaultTtl() {
        return defaultTtl;
    }

    public void setDefaultTtl(int defaultTtl) {
        this.defaultTtl = defaultTtl;
    }

    public Inet4Address getIpv4() {
        return ipv4Address;
    }

    public void setIpv4(Inet4Address ipv4) {
        this.ipv4Address = ipv4;
    }

    public Inet6Address getIpv6() {
        return ipv6Address;
    }

    public void setIpv6(Inet6Address ipv6) {
        this.ipv6Address = ipv6;
    }

    public List<String> getNsNames() {
        return nsNames;
    }

    public void setNsNames(List<String> nsNames) {
        this.nsNames = nsNames;
    }

    public String getPrimaryMaster() {
        return primaryNs;
    }

    public void setPrimaryMaster(String primaryDomain) {
        this.primaryNs = primaryDomain;
    }
    
    public String getAdminEmail() {
    	return adminEmail;
    }
    
    public void setAdminEmail(String adminEmail) {
    	this.adminEmail = adminEmail;
    }

    /**
     * 
     * 
     */
    public class SpliffyZone implements Zone {

    	private String rootDomain;
    	private ZoneInfo info;
    	private Map<String, Website> cache;
    	private String queryDomain;
    	
    	SpliffyZone(String rootDomain, Map<String, Website> cache, String queryDomain) {
    		this.rootDomain = rootDomain;
    		this.info = new SpliffyZoneInfo();
    		this.cache = cache;
    		this.queryDomain = queryDomain;
    	}
    	
		@Override
		public String getRootDomain() {
			return rootDomain;
		}

		@Override
		public ZoneInfo getInfo() {
			return info;
		}

		@Override
		public Iterator<String> iterator() {
			return null;
		}

		@Override
		public List<ResourceRecord> getDomainRecords(String domain) {
			
			String rootLower = rootDomain.toLowerCase();
			String domainLower = domain.toLowerCase();
			String queryLower = queryDomain.toLowerCase();
			
			if ( !domainLower.endsWith(rootLower) ) {
				return null;
			}
			if ( parent(domain) == null || domain.startsWith("*")) {
				return null;
			}
			
			Session session = null;
			Website website;
			try {
				if ( queryLower.endsWith(domainLower) ) {
					website = cache.get(domainLower);
				} else {
					session = sessionManager.open();
					website = Website.findByDomainName(domainLower, session);
				}
			} finally {
				if ( session!=null && session.isOpen()) {
					session.close();
				}
			}
			
			if ( website == null ) {
				return null;
			}
			
			List<ResourceRecord> records = new LinkedList<>();
			if ( ipv4Address !=null ) {
            	records.add(new ARecord(ipv4Address));
            }
            if ( ipv6Address != null ) {
            	records.add(new ARecord(ipv6Address));
            }
            String mxName = website.getMailServer();
            if (mxName == null) {
                mxName = defaultMx;
            }
            if (mxName != null) {
                records.add(new MXRecord(2, mxName));
            }
            return records;
		}
    }
    
    public class SpliffyZoneInfo implements ZoneInfo {

		@Override
		public List<String> getNameservers() {
			return nsNames;
		}

		@Override
		public String getPrimaryMaster() {
			return primaryNs;
		}

		@Override
		public String getAdminEmail() {
			return adminEmail;
		}

		@Override
		public long getZoneSerialNumber() {
			return 1;
		}

		@Override
		public long getRefresh() {
			return 3600;
		}

		@Override
		public long getRetry() {
			return 600;
		}

		@Override
		public long getExpire() {
			return 86400;
		}

		@Override
		public long getMinimum() {
			return 3600;
		}

		@Override
		public int getTtl() {
			return 10800;
		}
    }
    
   
    public class AbstractRecord implements ResourceRecord {
    	
        public AbstractRecord() {
   
        }
        @Override
        public int getTtl() {
            return defaultTtl;
        }
    }

    public class ARecord extends AbstractRecord implements AddressRecord {

        private final InetAddress address;

        public ARecord(InetAddress address) {
            super();
            if (address == null) {
                throw new RuntimeException("Address is null");
            }
            this.address = address;
        }

        @Override
        public InetAddress getAddress() {
            return address;
        }
    }


    public class MXRecord extends AbstractRecord implements MailExchangeRecord {

        private final int ordinal;
        private final String target;

        public MXRecord(int ordinal, String target) {
            super();
            this.ordinal = Math.min(ordinal, 6553);
            if (target == null) {
                throw new RuntimeException("Target name is null");
            }
            this.target = target;
        }

        @Override
        public int getPriority() {
            return ordinal * 10;
        }

        @Override
        public String getMailserver() {
            return target;
        }
    }



}
