package io.milton.cloud.server.web;

import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.resource.Resource;

import java.util.Date;

/**
 *
 * @author brad
 */
public abstract class AbstractCollectionResource extends AbstractResource implements SpliffyCollectionResource {

    private Date modDate;
    private Date createdDate;

    public AbstractCollectionResource(Services services) {
        super(services);
    }

    public AbstractCollectionResource(Services services, Date createDate, Date modDate) {
        super(services);
        this.createdDate = createDate;
        this.modDate = modDate;
    }

    @Override
    public boolean isDir() {
        return true;
    }

    @Override
    public Date getModifiedDate() {
        return modDate;
    }

    @Override
    public Date getCreateDate() {
        return createdDate;
    }

    /**
     * Simple implementation which just traverses the getChildren collection
     * looking for a matching name. Override if you need better peformance, eg
     * for large lists of children
     *
     * @param childName
     * @return
     * @throws NotAuthorizedException
     * @throws BadRequestException
     */
    @Override 
    public Resource child(String childName) throws NotAuthorizedException, BadRequestException {
        Resource r = services.getApplicationManager().getPage(this, childName);
        if (r != null) {
            return r;
        }
        return Utils.childOf(getChildren(), childName);
    }

    @Override
    public boolean is(String type) {
        if( type.equals("folder") || type.equals("collection")) {
            return true;
        }
        return super.is(type);
    }
    
    
}
