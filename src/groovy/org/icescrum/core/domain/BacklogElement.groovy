/*
 * Copyright (c) 2010 iceScrum Technologies.
 *
 * This file is part of iceScrum.
 *
 * iceScrum is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License.
 *
 * iceScrum is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with iceScrum.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Authors:
 *
 * Vincent Barrier (vbarrier@kagilum.com)
 * Stéphane Maldini (stephane.maldini@icescrum.com)
 */



package org.icescrum.core.domain

import grails.plugin.fluxiable.Fluxiable
import org.icescrum.plugins.attachmentable.interfaces.Attachmentable
import org.grails.comments.Commentable
import org.grails.followable.Followable
import org.grails.taggable.Taggable

abstract class BacklogElement implements Fluxiable, Attachmentable, Commentable, Followable, Serializable, Taggable {

    static final long serialVersionUID = -6800252500987149051L

    String description
    String notes
    String name
    Date dateCreated
    Date lastUpdated
    Date creationDate = new Date()
    int uid

    TimeBox backlog

    static belongsTo = [backlog: TimeBox]

    static constraints = {
        description(maxSize: 3000, nullable: true)
        notes(maxSize: 5000, nullable: true)
        name(blank: false, unique: 'backlog', maxSize: 100)
    }

    static mapping = {
        cache true
        table 'icescrum2_backlogelement'
        description length: 3000
        notes length: 5000
        backlog index: 'be_name_index'
        name index: 'be_name_index'
        tablePerHierarchy false
        backlog lazy: true
    }

    def beforeDelete = {
        withNewSession {
            this.removeAllAttachments()
        }
    }
}
