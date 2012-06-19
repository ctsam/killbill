/*
 * Copyright 2010-2011 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.ning.billing.util.notificationq;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

import org.joda.time.DateTime;
import org.skife.jdbi.v2.sqlobject.mixins.Transmogrifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ning.billing.config.NotificationConfig;
import com.ning.billing.util.clock.Clock;
import com.ning.billing.util.notificationq.NotificationQueueService.NotificationQueueHandler;
import com.ning.billing.util.queue.PersistentQueueEntryLifecycle.PersistentQueueEntryLifecycleState;

public class MockNotificationQueue extends NotificationQueueBase implements NotificationQueue {
    private final TreeSet<Notification> notifications;

    ObjectMapper objectMapper = new ObjectMapper();
    
    public MockNotificationQueue(final Clock clock, final String svcName, final String queueName, final NotificationQueueHandler handler, final NotificationConfig config) {
        super(clock, svcName, queueName, handler, config);
        notifications = new TreeSet<Notification>(new Comparator<Notification>() {
            @Override
            public int compare(final Notification o1, final Notification o2) {
                if (o1.getEffectiveDate().equals(o2.getEffectiveDate())) {
                    return o1.getNotificationKey().compareTo(o2.getNotificationKey());
                } else {
                    return o1.getEffectiveDate().compareTo(o2.getEffectiveDate());
                }
            }
        });
    }

    @Override
    public void recordFutureNotification(final DateTime futureNotificationTime, final NotificationKey notificationKey) throws IOException  {
        final String json = objectMapper.writeValueAsString(notificationKey);
        final Notification notification = new DefaultNotification("MockQueue", hostname, notificationKey.getClass().getName(), json, futureNotificationTime);
        synchronized (notifications) {
            notifications.add(notification);
        }
    }

    @Override
    public void recordFutureNotificationFromTransaction(
            final Transmogrifier transactionalDao, final DateTime futureNotificationTime,
            final NotificationKey notificationKey) throws IOException  {
        recordFutureNotification(futureNotificationTime, notificationKey);
    }

    public List<Notification> getPendingEvents() {
        final List<Notification> result = new ArrayList<Notification>();

        for (final Notification notification : notifications) {
            if (notification.getProcessingState() == PersistentQueueEntryLifecycleState.AVAILABLE) {
                result.add(notification);
            }
        }
        return result;
    }

    @Override
    public int doProcessEvents() {

        int result = 0;

        final List<Notification> processedNotifications = new ArrayList<Notification>();
        final List<Notification> oldNotifications = new ArrayList<Notification>();

        final List<Notification> readyNotifications = new ArrayList<Notification>();
        synchronized (notifications) {
            final Iterator<Notification> it = notifications.iterator();
            while (it.hasNext()) {
                final Notification cur = it.next();
                if (cur.isAvailableForProcessing(clock.getUTCNow())) {
                    readyNotifications.add(cur);
                }
            }
        }

        result = readyNotifications.size();
        for (final Notification cur : readyNotifications) {
            
            
            NotificationKey key = deserializeEvent(cur.getNotificationKeyClass(), cur.getNotificationKey()); 
            handler.handleReadyNotification(key, cur.getEffectiveDate());
            final DefaultNotification processedNotification = new DefaultNotification(-1L, cur.getId(), hostname, hostname, "MockQueue", clock.getUTCNow().plus(CLAIM_TIME_MS), PersistentQueueEntryLifecycleState.PROCESSED,
                    cur.getNotificationKeyClass(), cur.getNotificationKey(), cur.getEffectiveDate());
            oldNotifications.add(cur);
            processedNotifications.add(processedNotification);
        }
        synchronized (notifications) {
            if (oldNotifications.size() > 0) {
                notifications.removeAll(oldNotifications);
            }

            if (processedNotifications.size() > 0) {
                notifications.addAll(processedNotifications);
            }
        }
        return result;
    }

    @Override
    public void removeNotificationsByKey(final NotificationKey key) {
        final List<Notification> toClearNotifications = new ArrayList<Notification>();
        for (final Notification notification : notifications) {
            if (notification.getNotificationKey().equals(key.toString())) {
                toClearNotifications.add(notification);
            }
        }
        synchronized (notifications) {
            if (toClearNotifications.size() > 0) {
                notifications.removeAll(toClearNotifications);
            }
        }

    }
}
