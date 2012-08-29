package io.milton.cloud.server.apps.dns;

import io.milton.dns.Zone;
import io.milton.dns.ZoneFactory;
import io.milton.dns.ZoneInfo;
import io.milton.dns.record.AddressRecord;
import io.milton.dns.record.CanonicalNameRecord;
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
import org.springframework.util.StringUtils;

/**
 * 
 * @author Nick
 * 
 */
public class SpliffyZoneFactory implements ZoneFactory {

	private static final Logger logger = LoggerFactory.getLogger(SpliffyZoneFactory.class);

	private ARecord aRecord;
	private ARecord aaaaRecord;
	private MXRecord mxRecord;
	private List<String> nsNames;
	private String primaryNs;
	private String adminEmail;
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

		String queriedDomain = domain.toLowerCase();
		String zoneRootDomain = queriedDomain;
		Map<String, Website> cache = new TreeMap<>();
		Session session = null;
		try {

			/*
			 * Locate zone: start at domain and walk upwards until we hit a non-null Website.
			 */
			session = sessionManager.open();
			Website bottomWebsite = null;
			while (zoneRootDomain != null) {
				bottomWebsite = findWebsite(zoneRootDomain, session);
				if (bottomWebsite != null) {
					break;
				}
				zoneRootDomain = parent(zoneRootDomain);
			}
			/*
			 * Determine zone root: continue walking upwards until we hit a null Website.
			 * Cache Websites we encounter along the way.
			 */
			Website topWebsite = bottomWebsite;
			String parentDomain = zoneRootDomain;
			while (topWebsite != null) {
				logger.info("Caching Website: " + parentDomain);
				cache.put(parentDomain, topWebsite);
				zoneRootDomain = parentDomain;
				parentDomain = parent(zoneRootDomain);
				if (parentDomain == null) {
					break;
				}
				topWebsite = findWebsite(parentDomain, session);
			}
			/*
			 * If the query was for non existing x.y, check for a Website at www.x.y. If one
			 * is found, return a zone rooted at x.y.
			 */
			int numDots = StringUtils.countOccurrencesOf(queriedDomain, ".");
			if (zoneRootDomain == null && numDots == 1) {
				String altDomain = "www." + queriedDomain;
				Website altWebsite = findWebsite(altDomain, session);
				if ( altWebsite != null ) {
					zoneRootDomain = queriedDomain;
					queriedDomain = altDomain;
					cache.put(altDomain, altWebsite);
				}
			}
			/*
			 * 	If query was for existing www.x.y, return a zone rooted at x.y
			 */
			else if (numDots == 2 && queriedDomain.startsWith("www.")) {
				if (queriedDomain.equals(zoneRootDomain)) {
					String altDomain = queriedDomain.substring(4);
					zoneRootDomain = altDomain;		
				}
			}
			
			if (zoneRootDomain == null) {
				return null;
			}
			logger.info("Zone: " + zoneRootDomain + " Bottom cached domain: " + queriedDomain);
			return new SpliffyZone(zoneRootDomain, cache, queriedDomain);
		} finally {
			if (session != null && session.isOpen()) {
				session.close();
			}
		}
	}
	

	private static Website findWebsite(String domain, Session session) {
		long t0 = System.currentTimeMillis();
		Website website = Website.findByDomainNameDirect(domain, session);
		long t1 = System.currentTimeMillis();
		logger.info("DB fetch: " + domain + " Time: " + (t1 - t0));
		return website;
	}
	
	private static String parent(String domain) {
		int numDots = StringUtils.countOccurrencesOf(domain, ".");
		if ( numDots < 2 ) {
			return null;
		}
		int firstDot = domain.indexOf('.');
		return domain.substring(firstDot + 1);
	}

	private static String child(String domain, String subdomain) {
		return subdomain + "." + domain;
	}

	public void setDefaultMx(String defaultMx) {
		this.mxRecord = new MXRecord(2, defaultMx);
	}

	public void setDefaultTtl(int defaultTtl) {
		this.defaultTtl = defaultTtl;
	}

	public void setIpv4(Inet4Address ipv4) {
		this.aRecord = new ARecord(ipv4);
	}

	public void setIpv6(Inet6Address ipv6) {
		this.aaaaRecord = new ARecord(ipv6);
	}

	public void setNsNames(List<String> nsNames) {
		this.nsNames = nsNames;
	}

	public void setPrimaryMaster(String primaryDomain) {
		this.primaryNs = primaryDomain;
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
		private String bottomCachedDomain;

		SpliffyZone(String rootDomain, Map<String, Website> cache,
				String queryDomain) {
			this.rootDomain = rootDomain.toLowerCase();
			this.info = new SpliffyZoneInfo();
			this.cache = cache;
			this.bottomCachedDomain = queryDomain.toLowerCase();
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

			/*
			 * Immediately rule out wildcard and top level domains, as well as domains
			 * external to this zone.
			 */
			String domainLower = domain.toLowerCase();
			if (!domainLower.endsWith(rootDomain)) {
				return null;
			}
			if (domainLower.indexOf('.') == -1 || domainLower.startsWith("*")) {
				return null;
			}

			Website website = queryWebsite(domainLower);
			if ( website == null ) {
				/*
				 * If query for x.y turns up nothing, check www.x.y, and vice versa. If found,
				 * return a CNAME to it.
				 */
				String altDomain = findEquivalent(domainLower);
				logger.info("No website for " + domainLower + ", checking " + altDomain );
				if ( altDomain != null ) {
					website = queryWebsite(altDomain);
					if ( website != null ) {
						cache.put(altDomain, website);
						if ( domainLower.equals(bottomCachedDomain) && 
								altDomain.startsWith(domainLower) ) {
							bottomCachedDomain = altDomain;
						}
						logger.info(altDomain + " found, returning CNAME");
						ResourceRecord rr = new CNAMERecord(altDomain);
						return Collections.singletonList(rr);
					}
				}
				return null;
			}

			List<ResourceRecord> records = new LinkedList<>();
			if (aRecord != null) {
				records.add(aRecord);
			}
			if (aaaaRecord != null) {
				records.add(aaaaRecord);
			}
			String mxName = website.getMailServer();
			MXRecord mxRec = mxRecord;
			if (mxName != null) {
				mxRec = new MXRecord(2, mxName);
			}
			if (mxRec != null) {
				records.add(mxRec);
			}
			return records;
		}
		
		private Website queryWebsite(String domainLower) {
			Session session = null;
			Website website;
			try {
				if (bottomCachedDomain.endsWith(domainLower)) {
					logger.info("Satisfying lookup: " + domainLower + " from cache");
					website = cache.get(domainLower);
				} else {
					session = sessionManager.open();
					website = findWebsite(domainLower, session);
				}
				return website;
			} finally {
				if (session != null && session.isOpen()) {
					session.close();
				}
			}
		}
		
		
		private String findEquivalent(String domain) {
			int numDots = StringUtils.countOccurrencesOf(domain, ".");
			if ( numDots == 1 ) {
				return "www." + domain;
			}
			if ( numDots == 2 && domain.startsWith("www.")) {
				return domain.substring(4);
			}
			return null;
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

	public class CNAMERecord extends AbstractRecord implements CanonicalNameRecord {

		private final String target;
		
		public CNAMERecord(String target) {
			if (target == null) {
				throw new RuntimeException("Target name is null");
			}
			this.target = target;
		}
		@Override
		public String getCanonicalName() {
			return target;
		}
		
	}
}
