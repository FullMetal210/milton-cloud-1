package io.milton.cloud.server.apps.dns;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import edu.emory.mathcs.backport.java.util.Collections;

import io.milton.dns.Domain;
import io.milton.dns.DomainFactory;
import io.milton.dns.ForeignDomainException;
import io.milton.dns.Zone;
import io.milton.dns.record.AddressRecord;
import io.milton.dns.record.CanonicalNameRecord;
import io.milton.dns.record.MailExchangeRecord;
import io.milton.dns.record.ResourceRecord;
import io.milton.vfs.db.Website;
import io.milton.vfs.db.utils.SessionManager;

public class SpliffyDomainFactory implements DomainFactory {

	private static final Logger logger = LoggerFactory.getLogger(SpliffyDomainFactory.class);

	private ARecord aRecord;
	private ARecord aaaaRecord;
	private MXRecord mxRecord;
	private List<String> nsNames;
	private String primaryNs;
	private String adminEmail;
	private int defaultTtl = 7200;
	private SessionManager sessionManager;

	public SpliffyDomainFactory(SessionManager sessionManager) {
		if (sessionManager == null) {
			throw new RuntimeException("null sessionManager");
		}
		this.sessionManager = sessionManager;
	}

	@Override
	public Domain getDomain(String domainName) throws ForeignDomainException {

		int numDots = StringUtils.countOccurrencesOf(domainName, ".");
		if ( numDots < 1 || domainName.startsWith("*")) {
			return null;
		}
		
		Session session = null;
		Website website = null;
		try {
			session = sessionManager.open();
			domainName = domainName.toLowerCase();
			website = findWebsite(domainName, session);

			/*
			 * Determine zone root: continue walking upwards until we hit a null
			 * Website.
			 */
			Website topWebsite = website;
			String zoneRootDomain = domainName;
			String parentDomain = zoneRootDomain;
			while (topWebsite != null) {
				logger.info("Caching Website: " + parentDomain);
				zoneRootDomain = parentDomain;
				parentDomain = parent(zoneRootDomain);
				if (parentDomain == null) {
					break;
				}
				topWebsite = findWebsite(parentDomain, session);
			}

			Website altWebsite = null;
			String altDomain = null;
			/*
			 * If the query was for non existing x.y, check for a Website at
			 * www.x.y. Set the zone root to x.y
			 */
			if (numDots == 1) {
				zoneRootDomain = domainName;
				if (website == null) {
					altDomain = "www." + domainName;
					altWebsite = findWebsite(altDomain, session);
				}
			}
			/*
			 * If query was for non existing www.x.y, check for x.y. Set the
			 * zone root to x.y
			 */
			else if (numDots == 2 && domainName.startsWith("www.")) {
				altDomain = domainName.substring(4);
				zoneRootDomain = altDomain;
				if (website == null) {
					altWebsite = findWebsite(altDomain, session);
				}
			}

			if (website == null && altWebsite == null) {
				return null;
			}

			List<ResourceRecord> records;
			if (altWebsite != null) {
				ResourceRecord cnamerr = new CNAMERecord(altDomain);
				records = Collections.singletonList(cnamerr);
			} else {
				records = new LinkedList<ResourceRecord>();
				if (aRecord != null) records.add(aRecord);
				if (aaaaRecord != null) records.add(aaaaRecord);
				String mxName = website.getMailServer();
				MXRecord mxRec = mxRecord;
				if (mxName != null) mxRec = new MXRecord(2, mxName);
				if (mxRec != null) records.add(mxRec);
				
			}
			Zone spliffyZone = new SpliffyZone(zoneRootDomain, nsNames,primaryNs, adminEmail);
			return new SpliffyDomain(domainName, spliffyZone, records);

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
		if (numDots < 2) {
			return null;
		}
		int firstDot = domain.indexOf('.');
		return domain.substring(firstDot + 1);
	}

	public void setARecord(Inet4Address ipv4) {
		this.aRecord = new ARecord(ipv4);
	}

	public void setAaaaRecord(Inet6Address ipv6) {
		this.aaaaRecord = new ARecord(ipv6);
	}

	public void setMxRecord(String mxName) {
		this.mxRecord = new MXRecord(1, mxName);
	}

	public void setNsNames(List<String> nsNames) {
		this.nsNames = nsNames;
	}

	public void setPrimaryMaster(String primaryNs) {
		this.primaryNs = primaryNs;
	}

	public void setAdminEmail(String adminEmail) {
		this.adminEmail = adminEmail;
	}

	public void setDefaultTtl(int defaultTtl) {
		this.defaultTtl = defaultTtl;
	}

	public static class SpliffyDomain implements Domain {

		String name;
		Zone zone;
		List<ResourceRecord> records;

		SpliffyDomain(String name, Zone zone, List<ResourceRecord> records) {
			this.name = name;
			this.zone = zone;
			this.records = records;
		}

		@Override
		public Zone getZone() {
			return zone;
		}

		@Override
		public List<ResourceRecord> getRecords() {
			return records;
		}
	}

	public static class SpliffyZone implements Zone {

		String rootDomain;
		List<String> nsNames;
		String primaryNs;
		String adminEmail;

		SpliffyZone(String rootDomain, List<String> nsNames, String primaryNs,
				String adminEmail) {
			this.rootDomain = rootDomain;
			this.nsNames = nsNames;
			this.primaryNs = primaryNs;
			this.adminEmail = adminEmail;
		}

		@Override
		public String getRootDomain() {
			return rootDomain;
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

	class AbstractRecord implements ResourceRecord {

		public AbstractRecord() {

		}

		@Override
		public int getTtl() {
			return defaultTtl;
		}
	}

	class ARecord extends AbstractRecord implements AddressRecord {

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

	class MXRecord extends AbstractRecord implements MailExchangeRecord {

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

	class CNAMERecord extends AbstractRecord implements CanonicalNameRecord {

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
