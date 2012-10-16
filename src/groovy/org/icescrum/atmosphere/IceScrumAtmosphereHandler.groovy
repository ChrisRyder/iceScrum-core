/*
 * Copyright (c) 2011 Kagilum.
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
 */
package org.icescrum.atmosphere

import org.apache.commons.logging.LogFactory
import org.icescrum.core.domain.Product
import org.icescrum.core.domain.Team
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.atmosphere.cpr.*
import org.codehaus.groovy.grails.commons.ApplicationHolder

class IceScrumAtmosphereHandler implements AtmosphereHandler {

    private static final log = LogFactory.getLog(this)

    void onRequest(AtmosphereResource event) throws IOException {
        def conf = ApplicationHolder.application.config.icescrum.push
        if (!conf.enable || event.request.getMethod() == "POST") {
            event.resume()
            return
        }

        event.response.setContentType("text/plain;charset=ISO-8859-1");
        event.response.addHeader("Cache-Control", "private");
        event.response.addHeader("Pragma", "no-cache");
        event.response.addHeader("Access-Control-Allow-Origin", "*");
        event.suspend(60000, true);

        def productID = event.request.getParameterValues("product") ? event.request.getParameterValues("product")[0] : null
        def teamID = event.request.getParameterValues("team") ? event.request.getParameterValues("team")[0] : null
        def user = getUserFromAtmosphereResource(event.request, true)

        def channel = null
        if (productID && productID.isLong()) {
            channel = Product.load(productID.toLong()) ? "product-${productID}" : null

        } else if (teamID && teamID.isLong()) {
            channel = Team.load(teamID.toLong()) ? "team-${teamID}" : null
        }
        channel = channel?.toString()
        if (channel) {
            Class<? extends org.atmosphere.cpr.Broadcaster> bc = (Class<? extends org.atmosphere.cpr.Broadcaster>) ApplicationHolder.application.getClassLoader().loadClass(conf?.broadcaster?:'org.icescrum.atmosphere.ExcludeSessionBroadcaster')
            def broadcaster = BroadcasterFactory.default.lookup(bc, channel)
            if(broadcaster == null){
                broadcaster = bc.newInstance(channel, event.atmosphereConfig)
            }
            broadcaster.addAtmosphereResource(event)
            if (log.isDebugEnabled()) {
                log.debug("add user ${user?.username ?: 'anonymous'} to broadcaster: ${channel}")
            }
        }
        if (user)
            addBroadcasterToFactory(event, (String)user.username)
    }

    void onStateChange(AtmosphereResourceEvent event) throws IOException {

        def user

        if (log.isDebugEnabled()){
            user = getUserFromAtmosphereResource(event.resource.request)
        }

        //Event cancelled
        if (event.cancelled) {
            if (log.isDebugEnabled()) {
                log.debug("user ${user?.username ?: 'anonymous'} disconnected")
            }
            //Help atmosphere to clear old events
            BroadcasterFactory.default.lookupAll().each {
                it.removeAtmosphereResource(event.resource)
            }
            if (!event.message) {
                return
            }
        }

        //No message why I'm here ?
        if (!event.message) {
            return
        }

        if (log.isDebugEnabled()) {
            log.debug("broadcast to user ${user?.username ?: 'anonymous'}")
        }

        //Finally broadcast message to client
        event.resource.response.writer.with {
            write "${event.message}"
            flush()
        }
    }

    void destroy() {

    }

    private def getUserFromAtmosphereResource(def request, def createSession = false) {
        def httpSession = request.getSession(createSession)
        def user = null
        if (httpSession != null) {
            def context = (SecurityContext) httpSession.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
            if (context?.authentication?.isAuthenticated()) {
                user = [fullName:context.authentication.principal.fullName, id:context.authentication.principal.id, username:context.authentication.principal.username]
            }
        }
        user
    }

    private void addBroadcasterToFactory(AtmosphereResource resource, String broadcasterID){
        def conf = ApplicationHolder.application.config.icescrum.push
        Class<? extends org.atmosphere.cpr.Broadcaster> bc = (Class<? extends org.atmosphere.cpr.Broadcaster>) ApplicationHolder.application.getClassLoader().loadClass(conf?.userBroadcaster?:'org.atmosphere.cpr.DefaultBroadcaster')
        Broadcaster singleBroadcaster= BroadcasterFactory.default.lookup(bc, broadcasterID);
        if(singleBroadcaster != null){
            singleBroadcaster.addAtmosphereResource(resource)
            return
        }
        Broadcaster selfBroadcaster = bc.newInstance(broadcasterID, resource.atmosphereConfig);
        selfBroadcaster.addAtmosphereResource(resource)

        BroadcasterFactory.getDefault().add(selfBroadcaster, broadcasterID);
        if (log.isDebugEnabled()) {
            log.debug('new broadcaster for user')
        }
    }
}
