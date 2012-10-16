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
 * Manuarii Stein (manuarii.stein@icescrum.com)
 */

package org.icescrum.core.services

import grails.plugin.fluxiable.Activity
import java.text.SimpleDateFormat
import org.icescrum.core.event.IceScrumStoryEvent
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.transaction.annotation.Transactional
import org.icescrum.core.domain.*
import org.codehaus.groovy.grails.orm.hibernate.cfg.GrailsHibernateUtil
import org.icescrum.core.support.ApplicationSupport

class StoryService {
    def productService
    def taskService
    def springSecurityService
    def clicheService
    def featureService
    def attachmentableService
    def securityService
    def acceptanceTestService
    def g = new org.codehaus.groovy.grails.plugins.web.taglib.ApplicationTagLib()

    static transactional = true

    @PreAuthorize('!archivedProduct(#p)')
    void save(Story story, Product p, User u, Sprint s = null) {

        if (!story.effort)
            story.effort = null

        story.backlog = p
        story.creator = u

        if (story.textAs != '') {
            def actor = Actor.findByBacklogAndName(p, story.textAs)
            if (actor) {
                actor.addToStories(story)
            }
        }

        story.uid = Story.findNextUId(p.id)
        if(!story.suggestedDate)
            story.suggestedDate = new Date()

        if (story.effort > 0) {
            story.state = Story.STATE_ESTIMATED
            if(!story.estimatedDate)
                story.estimatedDate = new Date()
        } else if (story.acceptedDate) {
            story.state = Story.STATE_ACCEPTED
        } else {
            story.state = Story.STATE_SUGGESTED
        }

        if (story.save()) {
            p.addToStories(story)
            story.addFollower(u)
            story.addActivity(u, Activity.CODE_SAVE, story.name)
            broadcast(function: 'add', message: story, channel:'product-'+p.id)
            publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_CREATED))
        } else {
            throw new RuntimeException()
        }
    }

    void delete(Collection<Story> stories, history = true) {
        bufferBroadcast()
        stories.each { story ->

            if (story.actor){
                story.actor.removeFromStories(story)
            }

            if (story.feature){
                story.feature.removeFromStories(story)
            }

            //dependences on the story
            def dependences = story.dependences
            if (dependences){
                dependences.each{
                    notDependsOn(it)
                }
            }
            //precedence on the story
            if (story.dependsOn)
                notDependsOn(story)

            if (story.state >= Story.STATE_PLANNED)
               throw new IllegalStateException('is.story.error.not.deleted.state')

            if (!springSecurityService.isLoggedIn()){
                throw new IllegalAccessException()
            }

            if (!(story.creator.id == springSecurityService.currentUser?.id) && !securityService.productOwner(story.backlog.id, springSecurityService.authentication)) {
                throw new IllegalAccessException()
            }
            story.removeAllAttachments()
            story.removeLinkByFollow(story.id)

            if (story.state != Story.STATE_SUGGESTED)
                resetRank(story)

            def id = story.id
            story.deleteComments()

            def p = story.backlog
            p.removeFromStories(story)

            story.delete(flush:true)

            p.save()
            if (history) {
                p.addActivity(springSecurityService.currentUser, Activity.CODE_DELETE, story.name)
            }
            broadcast(function: 'delete', message: [class: story.class, id: id, state: story.state])
        }
        resumeBufferedBroadcast()
    }

    @PreAuthorize('!archivedProduct(#story.backlog)')
    void delete(Story story, boolean history = true) {
        delete([story], history)
    }

    @PreAuthorize('(productOwner(#story.backlog) or scrumMaster()) and !archivedProduct(#story.backlog)')
    void update(Story story, Sprint sp = null) {
        if (story.textAs != '' && story.actor?.name != story.textAs) {
            def actor = Actor.findByBacklogAndName(story.backlog, story.textAs)
            if (actor) {
                actor.addToStories(story)
            } else if(story.actor) {
                story.actor.removeFromStories(story)
            }
        } else if (story.textAs == '' && story.actor) {
            story.actor.removeFromStories(story)
        }

        if (!sp && !story.parentSprint && (story.state == Story.STATE_ACCEPTED || story.state == Story.STATE_ESTIMATED)) {
            if (story.effort != null) {
                story.state = Story.STATE_ESTIMATED
                story.estimatedDate = new Date()
            }
            if (story.effort == null) {
                story.state = Story.STATE_ACCEPTED
                story.estimatedDate = null
            }
        } else if (story.parentSprint && story.parentSprint.state == Sprint.STATE_WAIT) {
            story.parentSprint.capacity = (Double) story.parentSprint.getTotalEffort()
        }
        if (!story.save())
            throw new RuntimeException()

        User u = (User) springSecurityService.currentUser

        story.addActivity(u, Activity.CODE_UPDATE, story.name)
        broadcast(function: 'update', message: story)
        publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_UPDATED))
    }

    /**
     * Estimate a story (set the effort value)
     * @param story
     * @param estimation
     */
    @PreAuthorize('(teamMember(#story.backlog) or scrumMaster(#story.backlog)) and !archivedProduct(#story.backlog)')
    void estimate(Story story, estimation) {
        def oldState = story.state
        if (story.state < Story.STATE_ACCEPTED || story.state == Story.STATE_DONE)
            throw new IllegalStateException()
        if (!(estimation instanceof Number) && (estimation instanceof String && !estimation.isNumber())) {
            story.state = Story.STATE_ACCEPTED
            story.effort = null
            story.estimatedDate = null
        } else {
            if (story.state == Story.STATE_ACCEPTED)
                story.state = Story.STATE_ESTIMATED
            story.effort = estimation.toInteger()
            story.estimatedDate = new Date()
        }
        if (story.parentSprint && story.parentSprint.state == Sprint.STATE_WAIT) {
            story.parentSprint.capacity = (Double) story.parentSprint.getTotalEffort()
        }
        if (!story.save())
            throw new RuntimeException()

        User u = (User) springSecurityService.currentUser
        story.addActivity(u, Activity.CODE_UPDATE, story.name)

        broadcast(function: 'estimate', message: story)
        if (oldState != story.state && story.state == Story.STATE_ESTIMATED)
            publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_ESTIMATED))
        else if (oldState != story.state && story.state == Story.STATE_ACCEPTED)
            publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_ACCEPTED))
    }

    @PreAuthorize('(productOwner(#sprint.parentProduct) or scrumMaster(#sprint.parentProduct)) and !archivedProduct(#sprint.parentProduct)')
    void plan(Sprint sprint, Collection<Story> stories) {
        stories.each {
            this.plan(sprint, it)
        }
    }

    /**
     * Associate a story in a sprint
     * @param sprint The targeted sprint
     * @param story The story to associate
     * @param user The user performing the action
     */
    @PreAuthorize('(productOwner(#sprint.parentProduct) or scrumMaster(#sprint.parentProduct)) and !archivedProduct(#sprint.parentProduct)')
    void plan(Sprint sprint, Story story) {
        if (story.dependsOn){
            if (story.dependsOn.state < Story.STATE_PLANNED){
                throw new IllegalStateException(g.message(code:'is.story.error.dependsOn.notPlanned',args: [story.name, story.dependsOn.name]).toString())
            }else if(story.dependsOn.parentSprint.startDate > sprint.startDate){
                throw new IllegalStateException(g.message(code:'is.story.error.dependsOn.beforePlanned',args: [story.name, story.dependsOn.name]).toString())
            }
        }
        if (story.dependences){
            def startDate = story.dependences.findAll{ it.parentSprint }?.collect{ it.parentSprint.startDate }?.min()
            if (startDate && sprint.startDate > startDate){
                throw new IllegalStateException(g.message(code:'is.story.error.dependences.beforePlanned', args: [story.name]).toString())
            }
        }
        // It is possible to associate a story if it is at least in the "ESTIMATED" state and not in the "DONE" state
        // It is not possible to associate a story in a "DONE" sprint either
        if (sprint.state == Sprint.STATE_DONE)
            throw new IllegalStateException('is.sprint.error.associate.done')
        if (story.state < Story.STATE_ESTIMATED)
            throw new IllegalStateException('is.sprint.error.associate.story.noEstimated')
        if (story.state == Story.STATE_DONE)
            throw new IllegalStateException('is.sprint.error.associate.story.done')

        // If the story was already in a sprint, it is dissociated beforehand
        if (story.parentSprint != null) {
            //Shift to next Sprint (no delete tasks)
            unPlan(story, false)
        } else {
            resetRank(story)
        }

        User user = (User) springSecurityService.currentUser

        sprint.addToStories(story)

        // Change the story state
        if (sprint.state == Sprint.STATE_INPROGRESS) {
            story.state = Story.STATE_INPROGRESS
            story.inProgressDate = new Date()
            if (!story.plannedDate)
                story.plannedDate = story.inProgressDate

            def autoCreateTaskOnEmptyStory = sprint.parentRelease.parentProduct.preferences.autoCreateTaskOnEmptyStory
            if (autoCreateTaskOnEmptyStory)
                if (autoCreateTaskOnEmptyStory && !story.tasks) {
                    def emptyTask = new Task(name: story.name, state: Task.STATE_WAIT, description: story.description)
                    taskService.saveStoryTask(emptyTask, story, user)
                }
            clicheService.createOrUpdateDailyTasksCliche(sprint)
        } else {
            story.state = Story.STATE_PLANNED
            story.plannedDate = new Date()
        }

        def rank = sprint.stories? sprint.stories.size() : 1

        setRank(story, rank)

        if (!story.save(flush: true))
            throw new RuntimeException()

        // Calculate the velocity of the sprint
        if (sprint.state == Sprint.STATE_WAIT)
            sprint.capacity = (Double) sprint.getTotalEffort()
        sprint.save()

        story.tasks.findAll {it.state == Task.STATE_WAIT}.each {
            it.backlog = sprint
            taskService.update(it, user)
        }

        broadcast(function: 'plan', message: story)

        if (story.state == Story.STATE_INPROGRESS)
            publishEvent(new IceScrumStoryEvent(story, this.class, user, IceScrumStoryEvent.EVENT_INPROGRESS))
        else
            publishEvent(new IceScrumStoryEvent(story, this.class, user, IceScrumStoryEvent.EVENT_PLANNED))
    }

    /**
     * UnPlan the specified backlog item from the specified sprint
     * @param _sprint
     * @param pbi
     * @return
     */
    void unPlan(Story story, Boolean fullUnPlan = true) {

        def sprint = story.parentSprint
        if (!sprint)
            throw new RuntimeException('is.story.error.not.associated')

        if (story.state == Story.STATE_DONE)
            throw new IllegalStateException('is.sprint.error.dissociate.story.done')

        if (fullUnPlan && story.dependences?.find{it.state > Story.STATE_ESTIMATED})
            throw new RuntimeException(g.message(code:'is.story.error.dependences.dissociate',args: [story.name, story.dependences.find{it.state > Story.STATE_ESTIMATED}.name]).toString())

        resetRank(story)

        sprint.removeFromStories(story)
        story.parentSprint = null

        User u = (User) springSecurityService.currentUser

        if (sprint.state == Sprint.STATE_WAIT)
            sprint.capacity =  (Double)sprint.getTotalEffort()?:0

        def tasks = story.tasks.asList()
        tasks.each { Task task ->
            if (task.state == Task.STATE_DONE) {
                task.doneDate = null
                taskService.storyTaskToSprintTask(task, Task.TYPE_URGENT, u)
            } else if (fullUnPlan) {
                taskService.delete(task, u)
            } else {
                task.state = Task.STATE_WAIT
                task.inProgressDate = null
            }
        }

        story.state = Story.STATE_ESTIMATED
        story.inProgressDate = null
        story.plannedDate = null

        setRank(story, 1)
        if (!story.save(flush: true))
            throw new RuntimeException()

        broadcast(function: 'update', message: sprint)
        broadcast(function: 'unPlan', message: story)
        publishEvent(new IceScrumStoryEvent(story, this.class, (User) springSecurityService.currentUser, IceScrumStoryEvent.EVENT_UNPLANNED))
    }

    /**
     * UnPlan all stories from t odo sprints
     * @param spList
     * @param state (optional) If this argument is specified, dissociate only the sprint with the specified state
     */
    def unPlanAll(Collection<Sprint> sprintList, Integer sprintState = null) {
        def spList = sprintList
        bufferBroadcast()
        def storiesUnPlanned = []
        spList.sort { sp1, sp2 -> sp2.orderNumber <=> sp1.orderNumber }.each { sp ->
            if ((!sprintState) || (sprintState && sp.state == sprintState)) {
                def stories = sp.stories.findAll { pbi ->
                    pbi.state != Story.STATE_DONE
                }.sort {st1, st2 -> st2.rank <=> st1.rank }
                stories.each {
                    unPlan(it)
                }
                // Recalculate the sprint estimated velocity (capacite)
                if (sp.state == Sprint.STATE_WAIT)
                    sp.capacity = (Double) sp.stories?.sum { it.effort } ?: 0
                storiesUnPlanned.addAll(stories)
            }
        }
        resumeBufferedBroadcast()
        return storiesUnPlanned
    }

    def autoPlan(Release release, Double capacity) {
        int nbPoints = 0
        int nbSprint = 0
        def product = release.parentProduct
        def sprints = release.sprints.findAll { it.state == Sprint.STATE_WAIT }.sort { it.orderNumber }.asList()
        int maxSprint = sprints.size()

        // Get the list of PBI that have been estimated
        Collection<Story> itemsList = product.stories.findAll { it.state == Story.STATE_ESTIMATED }.sort { it.rank };

        Sprint currentSprint = null

        def plannedStories = []
        // Associate pbi in each sprint
        for (Story pbi: itemsList) {
            if ((nbPoints + pbi.effort) > capacity || currentSprint == null) {
                nbPoints = 0
                if (nbSprint < maxSprint) {
                    currentSprint = sprints[nbSprint++]
                    nbPoints += currentSprint.capacity
                    while (nbPoints + pbi.effort > capacity && currentSprint.capacity > 0) {
                        nbPoints = 0
                        if (nbSprint < maxSprint) {
                            currentSprint = sprints[nbSprint++]
                            nbPoints += currentSprint.capacity
                        }
                        else {
                            nbSprint++
                            break;
                        }
                    }
                    if (nbSprint > maxSprint) {
                        break
                    }
                    this.plan(currentSprint, pbi)
                    plannedStories << pbi
                    nbPoints += pbi.effort

                } else {
                    break
                }
            } else {
                this.plan(currentSprint, pbi)
                plannedStories << pbi
                nbPoints += pbi.effort
            }
        }
        return plannedStories
    }

    void setRank(Story story, int rank) {
        def stories = null
        if (story.state == Story.STATE_ACCEPTED || story.state == Story.STATE_ESTIMATED)
            stories = Story.findAllAcceptedOrEstimated(story.backlog.id).list(order: 'asc', sort: 'rank')
        else if (story.state == Story.STATE_PLANNED || story.state == Story.STATE_INPROGRESS || story.state == Story.STATE_DONE) {
            stories = story.parentSprint?.stories
        }

        rank = checkRankDependencies(story, rank)

        stories?.each { pbi ->
            if (pbi.rank >= rank) {
                pbi.rank++
                pbi.save()
            }
        }
        story.rank = rank
        if (!story.save())
            throw new RuntimeException()
    }

    void resetRank(Story story) {
        def stories = null
        if (story.state == Story.STATE_ACCEPTED || story.state == Story.STATE_ESTIMATED)
            stories = Story.findAllAcceptedOrEstimated(story.backlog.id).list(order: 'asc', sort: 'rank')
        else if (story.state == Story.STATE_PLANNED || story.state == Story.STATE_INPROGRESS || story.state == Story.STATE_DONE) {
            stories = story.parentSprint?.stories
        }
        stories.each { pbi ->
            if (pbi.rank > story.rank) {
                pbi.rank--
                pbi.save()
            }
        }
    }

    @PreAuthorize('(productOwner(#story.backlog) or scrumMaster(#story.backlog))  and !archivedProduct(#story.backlog)')
    boolean rank(Story story, int rank) {

        /*if (story.rank == rank) {
            return false
        }*/
        rank = checkRankDependencies(story, rank)
        if ((story.dependsOn || story.dependences ) && story.rank == rank) {
            return true
        }

        if (story.state in [Story.STATE_SUGGESTED, Story.STATE_DONE])
            return false

        def stories = null

        if (story.state == Story.STATE_ACCEPTED || story.state == Story.STATE_ESTIMATED)
            stories = Story.findAllAcceptedOrEstimated(story.backlog.id).list(order: 'asc', sort: 'rank')
        else if (story.state == Story.STATE_PLANNED || story.state == Story.STATE_INPROGRESS) {
            stories = story.parentSprint.stories
            def maxRankInProgress = stories.findAll {it.state != Story.STATE_DONE}?.size()
            if (story.state == Story.STATE_INPROGRESS && rank > maxRankInProgress) {
                rank = maxRankInProgress
            }
        }

        if (story.rank > rank) {
            stories.each {it ->
                if (it.rank >= rank && it.rank <= story.rank && it != story) {
                    it.rank = it.rank + 1
                    it.save()
                }
            }
        } else {
            stories.each {it ->
                if (it.rank <= rank && it.rank >= story.rank && it != story) {
                    it.rank = it.rank - 1
                    it.save()
                }
            }
        }
        story.rank = rank

        broadcast(function: 'update', message: story)
        return story.save() ? true : false
    }

    private int checkRankDependencies(story, rank){
        if (story.dependsOn){
            if (story.state in [Story.STATE_ACCEPTED, Story.STATE_ESTIMATED]){
                if (rank <= story.dependsOn.rank){
                    rank = story.dependsOn.rank + 1
                }
            }
            else if (story.dependsOn.parentSprint == story.parentSprint){
                if (rank <= story.dependsOn.rank){
                    rank = story.dependsOn.rank + 1
                }
            }
        }

        if (story.dependences){
            if (story.state in [Story.STATE_ACCEPTED, Story.STATE_ESTIMATED]){
                def highestRank = story.dependences.findAll{ it.state in [Story.STATE_ACCEPTED, Story.STATE_ESTIMATED]}?.collect{it.rank}?.min()
                if (highestRank && highestRank <= rank){
                    rank = highestRank - 1
                }
            }
            else if (story.state > Story.STATE_ESTIMATED){
                def highestRank = story.dependences.findAll{ it.parentSprint == story.parentSprint }?.collect{it.rank}?.min()
                if (highestRank && highestRank <= rank){
                    rank = highestRank - 1
                }
            }
        }
        return rank
    }

    @PreAuthorize('productOwner(#story.backlog) and !archivedProduct(#story.backlog)')
    def acceptToBacklog(Story story) {
        return acceptToBacklog([story])
    }

    @PreAuthorize('productOwner(#stories[0].backlog) and !archivedProduct(#stories[0].backlog)')
    def acceptToBacklog(List<Story> stories) {
        def storiesA = []
        bufferBroadcast()
        stories.each { story ->
            if (story.state != Story.STATE_SUGGESTED)
                throw new IllegalStateException('is.story.error.not.state.suggested')

            if (story.dependsOn?.state == Story.STATE_SUGGESTED)
                throw new IllegalStateException(g.message(code:'is.story.error.dependsOn.suggested', args:[story.name, story.dependsOn.name]).toString())

            story.rank = (Story.countAllAcceptedOrEstimated(story.backlog.id)?.list()[0] ?: 0) + 1
            story.state = Story.STATE_ACCEPTED
            story.acceptedDate = new Date()
            if (((Product) story.backlog).preferences.noEstimation) {
                story.estimatedDate = new Date()
                story.effort = 1
                story.state = Story.STATE_ESTIMATED
            }

            if (!story.save(flush: true))
                throw new RuntimeException()

            User u = (User) springSecurityService.currentUser
            storiesA << story

            story.addActivity(u, 'acceptAs', story.name)
            broadcast(function: 'accept', message: story)
            publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_ACCEPTED))
        }
        resumeBufferedBroadcast()
        return storiesA
    }

    @PreAuthorize('productOwner(#story.backlog) and !archivedProduct(#story.backlog)')
    def acceptToFeature(Story story) {
        return acceptToFeature([story])
    }

    @PreAuthorize('productOwner(#stories[0].backlog) and !archivedProduct(#stories[0].backlog)')
    def acceptToFeature(List<Story> stories) {
        def features = []
        bufferBroadcast()
        stories.each { pbi ->
            if (pbi.state != Story.STATE_SUGGESTED)
                throw new IllegalStateException('is.story.error.not.state.suggested')

            User user = (User) springSecurityService.currentUser
            def feature = new Feature(pbi.properties)
            feature.description = (feature.description ?: '') + ' ' + getTemplateStory(pbi)
            feature.validate()
            def i = 1
            while (feature.hasErrors()) {
                if (feature.errors.getFieldError('name')) {
                    i += 1
                    feature.name = feature.name + '_' + i
                    feature.validate()
                } else {
                    throw new RuntimeException()
                }
            }

            featureService.save(feature, (Product) pbi.backlog)

            pbi.followers?.each{
                feature.addFollower(it)
            }

            pbi.attachments.each { attachment ->
                feature.addAttachment(pbi.creator, attachmentableService.getFile(attachment), attachment.filename)
            }

            this.delete(pbi, false)
            features << feature

            feature.addActivity(user, 'acceptAs', feature.name)
            publishEvent(new IceScrumStoryEvent(feature, this.class, user, IceScrumStoryEvent.EVENT_ACCEPTED_AS_FEATURE))
        }
        resumeBufferedBroadcast()
        return features
    }

    @PreAuthorize('productOwner(#story.backlog) and !archivedProduct(#story.backlog)')
    def acceptToUrgentTask(Story story) {
        return acceptToUrgentTask([story])
    }

    @PreAuthorize('productOwner(#stories[0].backlog) and !archivedProduct(#stories[0].backlog)')
    def acceptToUrgentTask(List<Story> stories) {
        def tasks = []
        bufferBroadcast()
        stories.each { pbi ->

            if (pbi.state != Story.STATE_SUGGESTED)
                throw new IllegalStateException('is.story.error.not.state.suggested')

            def task = new Task(pbi.properties)

            task.state = Task.STATE_WAIT
            task.description = (task.description ?: '') + ' ' + getTemplateStory(pbi)

            def sprint = (Sprint) Sprint.findCurrentSprint(pbi.backlog.id).list()
            if (!sprint)
                throw new IllegalStateException('is.story.error.not.acceptedAsUrgentTask')

            task.validate()
            def i = 1
            while (task.hasErrors() && task.errors.getFieldError('name')) {
                i += 1
                task.name = task.name + '_' + i
                task.validate()
            }

            if (pbi.feature)
                task.color = pbi.feature.color

            taskService.saveUrgentTask(task, sprint, pbi.creator)

            pbi.followers?.each{
                task.addFollower(it)
            }

            pbi.attachments.each { attachment ->
                task.addAttachment(pbi.creator, attachmentableService.getFile(attachment), attachment.filename)
            }
            pbi.comments.each {
                comment ->
                task.notes = (task.notes ?: '') + '\n --- \n ' + comment.body + '\n --- \n '
            }
            tasks << task
            this.delete(pbi, false)

            publishEvent(new IceScrumStoryEvent(task, this.class, (User) springSecurityService.currentUser, IceScrumStoryEvent.EVENT_ACCEPTED_AS_TASK))
        }
        resumeBufferedBroadcast()
        return tasks
    }

    private String getTemplateStory(Story story) {
        def textStory = ''
        def tempTxt = [story.textAs, story.textICan, story.textTo]*.trim()
        if (tempTxt != ['null', 'null', 'null'] && tempTxt != ['', '', ''] && tempTxt != [null, null, null]) {
            textStory += g.message(code: 'is.story.template.as') + ' '
            textStory += (story.actor?.name ?: story.textAs ?: '') + ', '
            textStory += g.message(code: 'is.story.template.ican') + ' '
            textStory += (story.textICan ?: '') + ' '
            textStory += g.message(code: 'is.story.template.to') + ' '
            textStory += (story.textTo ?: '')
        }
        textStory
    }


    @PreAuthorize('inProduct(#story.backlog) and !archivedProduct(#story.backlog)')
    void done(Story story) {
        done([story])
    }

    @PreAuthorize('productOwner(#stories[0].backlog) and !archivedProduct(#stories[0].backlog)')
    void done(List<Story> stories) {
        bufferBroadcast()
        stories.each { story ->

            if (story.parentSprint.state != Sprint.STATE_INPROGRESS) {
                throw new IllegalStateException('is.sprint.error.declareAsDone.state.not.inProgress')
            }

            if (story.state != Story.STATE_INPROGRESS) {
                throw new IllegalStateException('is.story.error.declareAsDone.state.not.inProgress')
            }

            //Move story to last rank in sprint
            rank(story, Story.countByParentSprint(story.parentSprint).toInteger())

            story.state = Story.STATE_DONE
            story.doneDate = new Date()
            story.parentSprint.velocity += story.effort


            if (!story.save())
                throw new RuntimeException()

            User u = (User) springSecurityService.currentUser

            broadcast(function: 'done', message: story)
            story.addActivity(u, 'done', story.name)
            publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_DONE))

            // Set all tasks to done (and pbi's estimation to 0)
            story.tasks?.findAll{ it.state != Task.STATE_DONE }?.each { t ->
                t.estimation = 0
                taskService.update(t, u)
            }
        }
        if (stories)
            clicheService.createOrUpdateDailyTasksCliche(stories[0]?.parentSprint)
        resumeBufferedBroadcast()
    }

    @PreAuthorize('productOwner(#story.backlog) and !archivedProduct(#story.backlog)')
    void unDone(Story story) {
        unDone([story])
    }

    @PreAuthorize('productOwner(#stories[0].backlog) and !archivedProduct(#stories[0].backlog)')
    void unDone(List<Story> stories) {
        bufferBroadcast()
        stories.each { story ->

            if (story.state != Story.STATE_DONE) {
                throw new IllegalStateException('is.story.error.declareAsUnDone.state.not.done')
            }

            if (story.parentSprint.state != Sprint.STATE_INPROGRESS) {
                throw new IllegalStateException('is.sprint.error.declareAsUnDone.state.not.inProgress')
            }

            story.state = Story.STATE_INPROGRESS
            story.inProgressDate = new Date()
            story.doneDate = null
            story.parentSprint.velocity -= story.effort

            //Move story to last rank of in progress stories in sprint
            rank(story, Story.countByParentSprintAndState(story.parentSprint, Story.STATE_INPROGRESS).toInteger() + 1)

            if (!story.save())
                throw new RuntimeException()

            User u = (User) springSecurityService.currentUser

            story.addActivity(u, 'unDone', story.name)
            broadcast(function: 'unDone', message: story)
            publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_UNDONE))
        }
        if (stories)
            clicheService.createOrUpdateDailyTasksCliche(stories[0]?.parentSprint)
        resumeBufferedBroadcast()
    }

    void associateFeature(Feature feature, Story story) {
        feature.addToStories(story)
        if (!feature.save(flush:true))
            throw new RuntimeException()

        broadcast(function: 'associated', message: story)
        User u = (User) springSecurityService.currentUser
        publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_FEATURE_ASSOCIATED))
    }

    void notDependsOn(Story story) {
        def oldDepends = story.dependsOn
        story.dependsOn = null
        oldDepends.lastUpdated = new Date()
        oldDepends.save()

        broadcast(function: 'update', message: story)
        broadcast(function: 'notDependsOn', message: oldDepends)
        User u = (User) springSecurityService.currentUser
        publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_NOT_DEPENDS_ON))
    }

    void dependsOn(Story story, Story dependsOn) {

        if (story.dependsOn){
            notDependsOn(story)
        }
        story.dependsOn = dependsOn

        dependsOn.lastUpdated = new Date()
        dependsOn.save()

        broadcast(function: 'update', message: story)
        broadcast(function: 'dependsOn', message: dependsOn)
        User u = (User) springSecurityService.currentUser
        publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_DEPENDS_ON))
    }

    void dissociateFeature(Story story) {
        def feature = story.feature
        feature.removeFromStories(story)
        if (!feature.save(flush:true))
            throw new RuntimeException()

        broadcast(function: 'dissociated', message: story)
        User u = (User) springSecurityService.currentUser
        publishEvent(new IceScrumStoryEvent(story, this.class, u, IceScrumStoryEvent.EVENT_FEATURE_DISSOCIATED))
    }

    @PreAuthorize('inProduct(#story.backlog) and !archivedProduct(#story.backlog)')
    def copy(Story story) {
        copy([story])
    }

    @PreAuthorize('inProduct(#stories[0].backlog) and !archivedProduct(#stories[0].backlog)')
    def copy(List<Story> stories) {
        def copiedStories = []
        bufferBroadcast()
        stories.each { story ->
            def copiedStory = new Story(
                    name: story.name + '_1',
                    state: Story.STATE_SUGGESTED,
                    description: story.description,
                    notes: story.notes,
                    dateCreated: new Date(),
                    type: story.type,
                    textAs: story.textAs,
                    textICan: story.textICan,
                    textTo: story.textTo,
                    backlog: story.backlog,
                    affectVersion: story.affectVersion,
                    origin: story.name,
                    feature: story.feature,
                    actor: story.actor,
                    executionFrequency: story.executionFrequency
            )

            copiedStory.validate()
            def i = 1
            while (copiedStory.hasErrors()) {
                if (copiedStory.errors.getFieldError('name')) {
                    i += 1
                    copiedStory.name = story.name + '_' + i
                    copiedStory.validate()
                } else {
                    throw new RuntimeException()
                }
            }
            save(copiedStory, (Product) story.backlog, (User) springSecurityService.currentUser)
            copiedStories << copiedStory
        }
        resumeBufferedBroadcast()
        return copiedStories
    }

    @Transactional(readOnly = true)
    def unMarshall(def story, Product p = null, Sprint sp = null) {
        try {
            def acceptedDate = null
            if (story.acceptedDate?.text() && story.acceptedDate?.text() != "")
                acceptedDate = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(story.acceptedDate.text()) ?: null

            def estimatedDate = null
            if (story.estimatedDate?.text() && story.estimatedDate?.text() != "")
                estimatedDate = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(story.estimatedDate.text()) ?: null

            def plannedDate = null
            if (story.plannedDate?.text() && story.plannedDate?.text() != "")
                plannedDate = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(story.plannedDate.text()) ?: null

            def inProgressDate = null
            if (story.inProgressDate?.text() && story.inProgressDate?.text() != "")
                inProgressDate = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(story.inProgressDate.text()) ?: null

            def doneDate = null
            if (story.doneDate?.text() && story.doneDate?.text() != "")
                doneDate = new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(story.doneDate.text()) ?: null

            def s = new Story(
                    name: story."${'name'}".text(),
                    description: story.description.text(),
                    notes: story.notes.text(),
                    creationDate: new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(story.creationDate.text()),
                    effort: story.effort.text().isEmpty() ? null : story.effort.text().toInteger(),
                    value: story.value.text().isEmpty() ? null : story.value.text().toInteger(),
                    rank: story.rank.text().toInteger(),
                    state: story.state.text().toInteger(),
                    suggestedDate: new SimpleDateFormat('yyyy-MM-dd HH:mm:ss').parse(story.suggestedDate.text()),
                    acceptedDate: acceptedDate,
                    estimatedDate: estimatedDate,
                    plannedDate: plannedDate,
                    inProgressDate: inProgressDate,
                    doneDate: doneDate,
                    type: story.type.text().toInteger(),
                    executionFrequency: story.executionFrequency.text().toInteger(),
                    textAs: story.textAs.text(),
                    textICan: story.textICan.text(),
                    textTo: story.textTo.text(),
                    affectVersion: story.affectVersion.text(),
                    uid: story.@uid.text()?.isEmpty() ? story.@id.text().toInteger() : story.@uid.text().toInteger(),
                    origin: story.origin.text()
            )

            if (!story.feature?.@uid?.isEmpty() && p) {
                def f = p.features.find { it.uid == story.feature.@uid.text().toInteger() } ?: null
                if (f) {
                    f.addToStories(s)
                }
            }else if(!story.feature?.@id?.isEmpty() && p){
                def f = p.features.find { it.uid == story.feature.@id.text().toInteger() } ?: null
                if (f) {
                    f.addToStories(s)
                }
            }

            if (!story.actor?.@uid?.isEmpty() && p) {
                def a = p.actors.find { it.uid == story.actor.@uid.text().toInteger() } ?: null
                if (a) {
                    a.addToStories(s)
                }
            }else if(!story.actor?.@id?.isEmpty() && p){
                def a = p.actors.find { it.uid == story.actor.@id.text().toInteger() } ?: null
                if (a) {
                    a.addToStories(s)
                }
            }

            if (p) {
                def u
                if (!story.creator?.@uid?.isEmpty())
                    u = ((User) p.getAllUsers().find { it.uid == story.creator.@uid.text() } ) ?: null
                else{
                    u = ApplicationSupport.findUserUIDOldXMl(story,'creator',p.getAllUsers())
                }
                if (u)
                    s.creator = u
                else
                    s.creator = p.productOwners.first()
            }



            story.tasks?.task?.each {
                def t = taskService.unMarshall(it, p)
                if (sp) {
                    t.backlog = sp
                    s.addToTasks(t)
                }
            }

            story.acceptanceTests?.acceptanceTest?.each {
                def at = acceptanceTestService.unMarshall(it, p)
                s.addToAcceptanceTests(at)
            }

            if (p) {
                p.addToStories(s)
            }

            if (sp) {
                sp.addToStories(s)
            }

            return s
        } catch (Exception e) {
            if (log.debugEnabled) e.printStackTrace()
            throw new RuntimeException(e)
        }
    }

}