package io.milton.cloud.server.apps.dns;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.milton.dns.filter.Filter;
import io.milton.dns.filter.FilterChain;
import io.milton.dns.filter.Request;
import io.milton.dns.filter.Response;
import io.milton.vfs.db.utils.SessionManager;

public class SessionFilter implements Filter{

	static Logger logger = LoggerFactory.getLogger(SessionFilter.class);
	SessionManager sessionManager;
    
	public SessionFilter(SessionManager sessionManager) {
		this.sessionManager = sessionManager;
	}
	
	@Override
	public void process(FilterChain chain, Request request, Response response) {
		try {
			logger.info("Opening session");
			sessionManager.open();
			chain.process(request, response);
		} finally {
			logger.info("Closing session");
			sessionManager.close();
		}
	}
}
