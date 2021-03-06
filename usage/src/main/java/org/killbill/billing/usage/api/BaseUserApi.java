/*
 * Copyright 2014-2019 Groupon, Inc
 * Copyright 2014-2019 The Billing Project, LLC
 *
 * The Billing Project licenses this file to you under the Apache License, version 2.0
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

package org.killbill.billing.usage.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import org.joda.time.LocalDate;
import org.killbill.billing.osgi.api.OSGIServiceRegistration;
import org.killbill.billing.usage.plugin.api.UsagePluginApi;
import org.killbill.billing.util.callcontext.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BaseUserApi {

    private static final Logger logger = LoggerFactory.getLogger(BaseUserApi.class);

    private final OSGIServiceRegistration<UsagePluginApi> pluginRegistry;

    public BaseUserApi(final OSGIServiceRegistration<UsagePluginApi> pluginRegistry) {
        this.pluginRegistry = pluginRegistry;
    }

    //
    // * If no plugin were registered or if plugins do not serve usage data for this tenant/account this returns null
    //   and the usage module will look for data inside its own table.
    // * If not, a possibly empty (or not) list should be returned (and the usage module will *not* look for data inside its own table)
    //
    protected List<RawUsageRecord> getAccountUsageFromPlugin(final LocalDate startDate, final LocalDate endDate, final TenantContext tenantContext) {
        return getUsageFromPlugin(null, startDate, endDate, tenantContext);
    }

    protected List<RawUsageRecord> getSubscriptionUsageFromPlugin(final UUID subscriptionId, final LocalDate startDate, final LocalDate endDate, final TenantContext tenantContext) {
        return getUsageFromPlugin(subscriptionId, startDate, endDate, tenantContext);
    }

    private List<RawUsageRecord> getUsageFromPlugin(@Nullable final UUID subscriptionId, final LocalDate startDate, final LocalDate endDate, final TenantContext tenantContext) {

        final Set<String> allServices = pluginRegistry.getAllServices();
        // No plugin registered
        if (allServices.isEmpty()) {
            return null;
        }

        for (final String service : allServices) {
            final UsagePluginApi plugin = pluginRegistry.getServiceForName(service);

            final List<RawUsageRecord> result = subscriptionId != null ?
                                                plugin.getUsageForSubscription(subscriptionId, startDate, endDate, tenantContext) :
                                                plugin.getUsageForAccount(startDate, endDate, tenantContext);
            // First plugin registered, returns result -- could be empty List if no usage was recorded.
            if (result != null) {

                for (final RawUsageRecord cur : result) {
                    if (cur.getDate().compareTo(startDate) < 0 || cur.getDate().compareTo(endDate) >= 0) {
                        logger.warn("Usage plugin returned usage data with date {}, not in the specified range [{} -> {}[",
                                    cur.getDate(), startDate, endDate);
                    }
                }
                return result;
            }
        }
        // All registered plugins returned null
        return null;
    }


}
