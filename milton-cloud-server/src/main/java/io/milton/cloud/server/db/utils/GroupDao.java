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
package io.milton.cloud.server.db.utils;

import org.hibernate.Criteria;
import org.hibernate.Session;
import org.hibernate.criterion.Expression;
import io.milton.cloud.server.db.Group;
import io.milton.cloud.server.db.Organisation;

/**
 *
 * @author brad
 */
public class GroupDao {
    
    public Group findGroup(Organisation org, String name, Session session) {
        Criteria crit = session.createCriteria(Group.class);
        crit.add(Expression.and(Expression.eq("organisation", org), Expression.eq("name", name)));
        return (Group) crit.uniqueResult();
    }
    
    
}
