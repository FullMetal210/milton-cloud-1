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
package io.milton.cloud.server.db;

import io.milton.vfs.db.*;
import io.milton.vfs.db.utils.DbUtils;
import java.io.Serializable;
import java.util.Date;
import java.util.List;
import javax.persistence.*;
import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.criterion.Expression;

/**
 * Applications which can be configured per organisation/website can store
 * settings information in AppSetting records.
 * 
 * Generally each AppSetting should be a single configurable item
 *
 * @author brad
 */
@javax.persistence.Entity
@Table(
uniqueConstraints = {
    @UniqueConstraint(columnNames = {"app_control", "name"})}// item names must be unique within a directory
)
public class AppSetting implements Serializable {
    
    private long id;
    private AppControl appControl;
    private String name; // the name of the setting
    private String propValue;

    @Id
    @GeneratedValue
    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @ManyToOne
    public AppControl getAppControl() {
        return appControl;
    }

    public void setAppControl(AppControl appControl) {
        this.appControl = appControl;
    }

    
    
    @Column(nullable = false)
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPropValue() {
        return propValue;
    }

    public void setPropValue(String value) {
        this.propValue = value;
    }

    
    
    
    
}
