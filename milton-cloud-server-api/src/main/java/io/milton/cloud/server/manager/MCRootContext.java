/*
 * Copyright 2012 McEvoy Software Ltd.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.milton.cloud.server.manager;

import io.milton.cloud.common.CurrentDateService;
import io.milton.cloud.common.DefaultCurrentDateService;
import io.milton.cloud.server.apps.Application;
import io.milton.cloud.server.apps.ApplicationManager;
import io.milton.cloud.server.web.SpliffyResourceFactory;
import io.milton.cloud.server.web.SpliffySecurityManager;
import io.milton.cloud.server.web.templating.*;
import io.milton.common.ContentTypeService;
import io.milton.context.RootContext;
import io.milton.event.EventManager;
import java.util.List;
import javax.servlet.ServletContext;
import org.hashsplit4j.api.BlobStore;
import org.hashsplit4j.api.HashStore;

/**
 *
 * @author brad
 */
public class MCRootContext extends RootContext {

    private final SpliffyResourceFactory resourceFactory;
    private final HashStore hashStore;
    private final BlobStore blobStore;
    private final HtmlTemplater htmlTemplater;
    private final TextTemplater textTemplater;
    private final SpliffySecurityManager securityManager;
    private final ApplicationManager applicationManager;
    private HtmlTemplateParser templateParser;
    private final CurrentDateService currentDateService;
    private final CommentService commentService;
    private final DataBinder dataBinder;
    
    public MCRootContext(ServletContext servletContext, SpliffyResourceFactory resourceFactory, HashStore hashStore, BlobStore blobStore, SpliffySecurityManager securityManager, ApplicationManager applicationManager, EventManager eventManager, ContentTypeService contentTypeService, List beans) {
        super();
        this.resourceFactory = resourceFactory;                
        this.hashStore = hashStore;
        this.blobStore = blobStore;        
        this.securityManager = securityManager;
        this.applicationManager = applicationManager;
        applicationManager.setRootContext(this);
        templateParser = new HtmlTemplateParser();
        this.textTemplater = new TextTemplater(securityManager, servletContext);
        currentDateService = new DefaultCurrentDateService(); // todo: make pluggable to support testing
        Formatter formatter = new Formatter(currentDateService);
        put(formatter);
        this.htmlTemplater = new HtmlTemplater(applicationManager, formatter, securityManager, servletContext);
        commentService = new CommentService(currentDateService);        
        this.dataBinder = new DataBinder();
        
        put(dataBinder);        
        put(hashStore, blobStore, securityManager, securityManager.getUserDao(), applicationManager, templateParser, textTemplater, currentDateService, htmlTemplater, commentService);
        put(securityManager.getPasswordManager());
        put(resourceFactory);
        put(contentTypeService);
        
        for( Application a : applicationManager.getApps()) {
            put(a);
        }
        for( Object bean : beans ) {
            put(bean);
        }
        
    }
    
    
}
