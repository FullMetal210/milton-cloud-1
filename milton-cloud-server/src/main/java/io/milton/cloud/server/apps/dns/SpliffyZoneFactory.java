package io.milton.cloud.server.apps.dns;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import edu.emory.mathcs.backport.java.util.Collections;

import io.milton.dns.Zone;
import io.milton.dns.ZoneFactory;
import io.milton.dns.record.AddressRecord;
import io.milton.dns.record.CanonicalNameRecord;
import io.milton.dns.record.MailExchangeRecord;
import io.milton.dns.record.ResourceRecord;
import io.milton.vfs.db.Website;
import io.milton.vfs.db.utils.SessionManager;

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
		
		int numDots = StringUtils.countOccurrencesOf(domain, ".");
		if (numDots < 1) {
			return null;
		}
		
		String queriedDomain = domain.toLowerCase();
		String zoneRootDomain = queriedDomain;
		Map<String, Website> cache = new TreeMap<>();
		Session session = null;
		boolean newSession = false;
		try {
			
			session = SessionManager.session();
			if ( session == null ) {
				newSession = true;
				session = sessionManager.open();
			}
			
			/*
			 * Locate zone: start at domain and walk upwards until we hit a non-null Website.
			 */
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
			Website altWebsite = null;
			if (zoneRootDomain == null && numDots == 1) {
				String altDomain = "www." + queriedDomain;
				altWebsite = findWebsite(altDomain, session);
				if ( altWebsite != null ) {
					zoneRootDomain = queriedDomain;
					queriedDomain = altDomain;
					cache.put(altDomain, altWebsite);
				}
			}
			/*
			 * 	If query was for www.x.y, return a zone rooted at x.y if one or the other exists
			 */
			else if (numDots == 2 && queriedDomain.startsWith("www.")) {
				String altDomain = queriedDomain.substring(4);
				if (queriedDomain.equals(zoneRootDomain)) {
					zoneRootDomain = altDomain;		
				} else if (altDomain.equals(zoneRootDomain)) {
					if (!cache.containsKey(queriedDomain)) {
						altWebsite = cache.get(altDomain);
					}
				}
			}
			
			if (zoneRootDomain == null) {
				return null;
			}
			logger.info("Zone: " + zoneRootDomain + " Bottom cached domain: " + queriedDomain);
			return new SpliffyZone(zoneRootDomain, cache, queriedDomain, altWebsite);
		} finally {
			if (session != null && newSession ) {
				sessionManager.close();
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
		if (numDots < 2) {
			return null;
		}
		int firstDot = domain.indexOf('.');
		return domain.substring(firstDot + 1);
	}
	
	public void setARecord(Inet4Address aRecord) {
		this.aRecord = new ARecord(aRecord);
	}

	public void setAaaaRecord(Inet6Address aaaaRecord) {
		this.aaaaRecord = new ARecord(aaaaRecord);
	}

	public void setMxRecord(String mxRecord) {
		this.mxRecord = new MXRecord(2, mxRecord);
	}

	public void setNsNames(List<String> nsNames) {
		this.nsNames = nsNames;
	}

	public void setPrimaryNs(String primaryNs) {
		this.primaryNs = primaryNs;
	}

	public void setAdminEmail(String adminEmail) {
		this.adminEmail = adminEmail;
	}

	public void setDefaultTtl(int defaultTtl) {
		this.defaultTtl = defaultTtl;
	}

	public class SpliffyZone implements Zone {

		private String rootDomain;
		private Map<String, Website> cache;
		private String bottomCachedDomain;
		private Website altWebsite;
		
		public SpliffyZone(String rootDomain, Map<String, Website> cache,
				String bottomCachedDomain, Website altWebsite ) {
			this.rootDomain = rootDomain;
			this.cache = cache;
			this.bottomCachedDomain = bottomCachedDomain;
			this.altWebsite = altWebsite;
		}
		@Override
		public String getRootDomain() {
			return rootDomain;
		}

		@Override
		public List<ResourceRecord> getRecords(String domain) {
			
			String domainLower = domain.toLowerCase();
			if (!domainLower.endsWith(rootDomain)) {
				return null;
			}
			if (domainLower.indexOf('.') == -1 || domainLower.startsWith("*")) {
				return null;
			}
			
			Session session = null;
			Website website = null;
			boolean newSession = false;
			try {
				
				if (bottomCachedDomain.endsWith(domainLower)) {
					logger.info("Satisfying lookup: " + domainLower + " from cache");
					website = cache.get(domainLower);
				} else {
					session = SessionManager.session();
					if (session == null) {
						newSession = true;
						session = sessionManager.open();
					}
					website = findWebsite(domainLower, session);
				}
				if ( website == null ) {
					if (altWebsite != null) {
						String cname = altWebsite.getDomainName().toLowerCase();
						ResourceRecord rr = new CNAMERecord(cname);
						return Collections.singletonList(rr);
					}
					return null;
				}
				List<ResourceRecord> records = new LinkedList<>();
				if (aRecord != null) records.add(aRecord);
				if (aaaaRecord != null) records.add(aaaaRecord);			
				String mxName = website.getMailServer();
				MXRecord mxRec = mxRecord;
				if (mxName != null) mxRec = new MXRecord(2, mxName);
				if (mxRec != null) records.add(mxRec);
				return records;
			} finally {
				if (session != null && newSession ) {
					sessionManager.close();
				}
			}
		}

		@Override
		public Iterator<String> iterator() {
			return null;
		}

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
