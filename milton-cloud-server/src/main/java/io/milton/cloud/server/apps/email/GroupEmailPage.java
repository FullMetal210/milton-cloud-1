/*
 * Copyright (C) 2012 McEvoy Software Ltd
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.milton.cloud.server.apps.email;

import io.milton.cloud.common.CurrentDateService;
import io.milton.cloud.server.db.EmailItem;
import io.milton.cloud.server.db.EmailSendAttempt;
import io.milton.cloud.server.db.GroupEmailJob;
import io.milton.cloud.server.db.GroupRecipient;
import io.milton.cloud.server.queue.AsynchProcessor;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.milton.vfs.db.Organisation;
import io.milton.cloud.server.web.*;
import io.milton.cloud.server.web.templating.DataBinder;
import io.milton.cloud.server.web.templating.HtmlTemplater;
import io.milton.cloud.server.web.templating.MenuItem;
import io.milton.resource.AccessControlledResource.Priviledge;
import io.milton.http.Auth;
import io.milton.http.FileItem;
import io.milton.http.Range;
import io.milton.http.Response.Status;
import io.milton.principal.Principal;
import io.milton.http.exceptions.BadRequestException;
import io.milton.http.exceptions.ConflictException;
import io.milton.http.exceptions.NotAuthorizedException;
import io.milton.http.exceptions.NotFoundException;
import io.milton.http.values.ValueAndType;
import io.milton.http.webdav.PropFindResponse.NameAndError;
import io.milton.http.webdav.PropertySourcePatchSetter;
import io.milton.property.BeanPropertyResource;
import io.milton.resource.GetableResource;
import io.milton.resource.PostableResource;
import io.milton.vfs.db.utils.SessionManager;
import javax.xml.namespace.QName;
import org.hibernate.Session;
import org.hibernate.Transaction;

import static io.milton.context.RequestContext._;
import io.milton.vfs.db.Group;
import io.milton.vfs.db.Profile;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Iterator;

/**
 *
 * @author brad
 */
@BeanPropertyResource(value = "milton")
public class GroupEmailPage extends AbstractResource implements GetableResource, PostableResource, PropertySourcePatchSetter.CommitableResource {

    private static final Logger log = LoggerFactory.getLogger(GroupEmailPage.class);
    private final CommonCollectionResource parent;
    private final GroupEmailJob job;
    private JsonResult jsonResult;

    public GroupEmailPage(GroupEmailJob job, CommonCollectionResource parent) {
        this.job = job;
        this.parent = parent;
    }

    @Override
    public String processForm(Map<String, String> parameters, Map<String, FileItem> files) throws BadRequestException, NotAuthorizedException, ConflictException {
        Session session = SessionManager.session();
        Transaction tx = session.beginTransaction();

        if (parameters.containsKey("sendMail")) {
            startSendJob(session);
            tx.commit();

            SendMailProcessable processable = new SendMailProcessable(job.getId());
            _(AsynchProcessor.class).enqueue(processable);

        } else {

            if (parameters.containsKey("group")) {
                String groupName = parameters.get("group");
                String sIsRecip = parameters.get("isRecip");
                if ("true".equals(sIsRecip)) {
                    addGroup(groupName);
                } else {
                    removeGroup(groupName);
                }
            }

            try {
                _(DataBinder.class).populate(job, parameters);
            } catch (IllegalAccessException | InvocationTargetException ex) {
                throw new RuntimeException(ex);
            }
            session.save(job);
            tx.commit();
        }
        jsonResult = new JsonResult(true);
        return null;
    }

    @Override
    public void sendContent(OutputStream out, Range range, Map<String, String> params, String contentType) throws IOException, NotAuthorizedException, BadRequestException, NotFoundException {
        if (params.containsKey("status")) {
            jsonResult = buildStatus();
        }
        if (jsonResult != null) {
            jsonResult.write(out);
        } else {
            MenuItem.setActiveIds("menuTalk", "menuEmails", "menuSendEmail");
            _(HtmlTemplater.class).writePage("admin", "email/groupEmailJob", this, params, out);
        }
    }

    private JsonResult buildStatus() {
        SendStatus status = new SendStatus();
        status.setStatusCode(getStatusCode());
        status.setStatusDescription(getStatus());
        if (job.getEmailItems() != null) {
            status.setTotalToSend(job.getEmailItems().size());
            for (EmailItem e : job.getEmailItems()) {
                if (e.getRecipient() instanceof Profile) {
                    if (e.getSendStatus() != null) {
                        switch (e.getSendStatus()) {
                            case "c":
                                status.getSuccessful().add(e.getId());
                                break;
                            case "f":
                                EmailBean p = toBean(e);
                                status.getFailed().add(p);
                                break;
                            case "r":
                                p = toBean(e);
                                status.getRetrying().add(p);
                                break;
                            case "p":
                                p = toBean(e);
                                status.getSending().add(p);
                                break;

                        }
                    }
                }
            }
        }


        JsonResult r = new JsonResult(true);
        r.setData(status);
        return r;
    }

    public GroupEmailJob getJob() {
        return job;
    }

    public Date getStatusDate() {
        return job.getStatusDate();
    }

    public String getTitle() {
        String s = job.getTitle();
        if (s == null || s.trim().length() == 0) {
            s = "Untitled email";
        }
        return s;
    }

    public void setTitle(String s) {
        job.setTitle(s);
    }

    public String getNotes() {
        return job.getNotes();
    }

    public void setNotes(String s) {
        job.setNotes(s);
    }

    public String getFromAddress() {
        return job.getFromAddress();
    }

    public void setFromAddress(String s) {
        job.setFromAddress(s);
    }

    public String getSubject() {
        return job.getSubject();
    }

    public void setSubject(String s) {
        job.setSubject(s);
    }

    public String getHtml() {
        return job.getHtml();
    }

    /**
     * returns the status code or "draft" if null
     *
     * @return
     */
    public String getStatusCode() {
        return job.getStatus();
    }

    public String getStatus() {
        if (job.getStatus() == null || job.getStatus().length() == 0) {
            return "Draft";
        } else {
            switch (job.getStatus()) {
                case "c":
                    return "Completed";
                case "p":
                    return "In progress";
                default:
                    return "Status: " + job.getStatus();
            }
        }
    }

    @Override
    public boolean isDir() {
        return false;
    }

    @Override
    public CommonCollectionResource getParent() {
        return parent;
    }

    @Override
    public String getName() {
        return job.getName();
    }

    @Override
    public Date getModifiedDate() {
        return null;
    }

    @Override
    public Date getCreateDate() {
        return null;
    }

    @Override
    public Map<Principal, List<Priviledge>> getAccessControlList() {
        return null;
    }

    @Override
    public Long getMaxAgeSeconds(Auth auth) {
        return null;
    }

    @Override
    public String getContentType(String accepts) {
        return "text/html";
    }

    @Override
    public Long getContentLength() {
        return null;
    }

    @Override
    public Organisation getOrganisation() {
        return parent.getOrganisation();
    }

    @Override
    public boolean is(String type) {
        if (type.equals("manageRewards")) {
            return true;
        }
        return super.is(type);
    }

    @Override
    public void doCommit(Map<QName, ValueAndType> knownProps, Map<Status, List<NameAndError>> errorProps) throws BadRequestException, NotAuthorizedException {
        Session session = SessionManager.session();
        Transaction tx = session.beginTransaction();
        session.save(job);
        tx.commit();
    }

    public List<Group> getGroupRecipients() {
        List<Group> list = new ArrayList<>();
        if (job.getGroupRecipients() != null) {
            for (GroupRecipient gr : job.getGroupRecipients()) {
                list.add(gr.getRecipient());
            }
        }
        return list;
    }

    public List<Group> getAllGroups() {
        return job.getOrganisation().groups(SessionManager.session());
    }

    public boolean isSelected(Group g) {
        return getGroupRecipients().contains(g);
    }

    private void removeGroup(String groupName) {
        if (job.getGroupRecipients() == null) {
            return;
        }
        Iterator<GroupRecipient> it = job.getGroupRecipients().iterator();
        Session session = SessionManager.session();
        while (it.hasNext()) {
            GroupRecipient gr = it.next();
            Group g = gr.getRecipient();
            if (g.getName().equals(groupName)) {
                System.out.println("removed: " + groupName);
                it.remove();
                session.delete(gr);
            }
        }
    }

    private void addGroup(String groupName) {
        if (job.getGroupRecipients() == null) {
            job.setGroupRecipients(new ArrayList<GroupRecipient>());
        }
        for (GroupRecipient gr : job.getGroupRecipients()) {
            if (gr.getRecipient().getName().equals(groupName)) {
                System.out.println("already in");
                return;
            }
        }
        Session session = SessionManager.session();
        Group g = job.getOrganisation().group(groupName, session);
        if (g == null) {
            log.warn("group not found: " + groupName);
            return;
        }
        GroupRecipient gr = new GroupRecipient();
        gr.setJob(job);
        gr.setRecipient(g);
        session.save(gr);
        job.getGroupRecipients().add(gr);

    }

    private void startSendJob(Session session) {
        job.setStatus("r");
        Date now = _(CurrentDateService.class).getNow();
        job.setStatusDate(now);
        session.save(job);
    }

    private EmailSendAttempt findLastAttempt(EmailItem e) {
        EmailSendAttempt latest = null;
        if (e.getEmailSendAttempts() != null) {
            List<EmailSendAttempt> list = e.getEmailSendAttempts();
            if (!list.isEmpty()) {
                latest = list.get(0);
                for (EmailSendAttempt a : list) {
                    if (a.getStatusDate().after(latest.getStatusDate())) {
                        latest = a;
                    }
                }
            }
        }
        return latest;
    }

    private EmailBean toBean(EmailItem email) {
        Profile p = (Profile) email.getRecipient();
        EmailBean b = new EmailBean();
        b.setEmailId(email.getId());
        b.setProfile(ProfileBean.toBean(p));
        b.setEmail(p.getEmail());
        String fullName = p.getFirstName();
        if (fullName != null) {
            fullName += " ";
        }
        if (p.getSurName() != null) {
            fullName += p.getSurName();
        }
        if (fullName == null) {
            fullName = p.getNickName();
        }
        if (fullName == null) {
            fullName = p.getName();
        }
        b.setRetries(email.getNumAttempts());
        EmailSendAttempt lastAttempt = findLastAttempt(email);
        if (lastAttempt != null) {
            b.setLastError(lastAttempt.getStatus());
        }
        return b;
    }
    
    
    public class SendStatus {

        private String statusCode;
        private String statusDescription;
        private List<Long> successful = new ArrayList<>();
        private List<EmailBean> sending = new ArrayList<>();
        private List<EmailBean> failed = new ArrayList<>();
        private List<EmailBean> retrying = new ArrayList<>();
        private int totalToSend;

        public String getStatusCode() {
            return statusCode;
        }

        public void setStatusCode(String statusCode) {
            this.statusCode = statusCode;
        }

        public String getStatusDescription() {
            return statusDescription;
        }

        public void setStatusDescription(String statusDescription) {
            this.statusDescription = statusDescription;
        }

        /**
         * A list of email IDs of successfully completed email items
         * 
         * @return 
         */
        public List<Long> getSuccessful() {
            return successful;
        }

        public List<EmailBean> getRetrying() {
            return retrying;
        }

        public List<EmailBean> getSending() {
            return sending;
        }

        public List<EmailBean> getFailed() {
            return failed;
        }

        public int getTotalToSend() {
            return totalToSend;
        }

        public void setTotalToSend(int totalToSend) {
            this.totalToSend = totalToSend;
        }
    }

    public class EmailBean {

        private long emailId;
        private ProfileBean profile;
        private String fullName;
        private String email;
        private Integer retries;
        private String lastError;

        public long getEmailId() {
            return emailId;
        }

        public void setEmailId(long emailId) {
            this.emailId = emailId;
        }

        public ProfileBean getProfile() {
            return profile;
        }

        public void setProfile(ProfileBean profile) {
            this.profile = profile;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public Integer getRetries() {
            return retries;
        }

        public void setRetries(Integer retries) {
            this.retries = retries;
        }

        public String getLastError() {
            return lastError;
        }

        public void setLastError(String lastError) {
            this.lastError = lastError;
        }
    }
}
