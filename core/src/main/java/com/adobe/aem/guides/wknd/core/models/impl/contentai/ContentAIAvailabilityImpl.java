/*
 *  Copyright 2026 Adobe Systems Incorporated
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.adobe.aem.guides.wknd.core.models.impl.contentai;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.OSGiService;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.io.IOException;

import com.adobe.aem.guides.wknd.core.models.contentai.ContentAIAvailability;

@Model(
        adaptables = {SlingHttpServletRequest.class},
        adapters = {ContentAIAvailability.class},
        resourceType = {ContentAIAvailabilityImpl.RESOURCE_TYPE},
        defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL
)
public class ContentAIAvailabilityImpl implements ContentAIAvailability {

    protected static final String RESOURCE_TYPE = "wknd/components/contentaisearch";

    /**
     * Same PID AEM Core WCM Components' {@code ContentAIClientImpl} is configured under
     * (see {@code ui.config/.../config.author|publish/com.adobe.cq.wcm.core.components.internal.services.contentai.ContentAIClientImpl.cfg.json}).
     */
    private static final String CONTENT_AI_CLIENT_PID =
            "com.adobe.cq.wcm.core.components.internal.services.contentai.ContentAIClientImpl";

    private static final String API_KEY_PROPERTY = "apiKey";

    private static final Logger LOGGER = LoggerFactory.getLogger(ContentAIAvailabilityImpl.class);

    @OSGiService
    private ConfigurationAdmin configurationAdmin;

    private boolean configured;

    @PostConstruct
    protected void init() {
        this.configured = resolveConfigured();
    }

    @Override
    public boolean isConfigured() {
        return configured;
    }

    /**
     * Looks up the {@code ContentAIClientImpl} configuration via {@code getConfiguration(pid)} -
     * the primary, universally-implemented {@code ConfigurationAdmin} method (unlike the
     * filter-based {@code listConfigurations}, which not every implementation supports). Its
     * documented side effect - creating an empty configuration for the PID if one doesn't exist
     * yet - is safe regardless of deploy ordering (i.e. even if this runs before a real
     * {@code ContentAIClientImpl.cfg.json} has landed): per OSGi/Felix semantics, a
     * {@code Configuration} obtained this way is never persisted and never fires
     * {@code ManagedService#updated} unless {@code update(...)} is actually called on it - so the
     * phantom object this creates is inert and cannot interfere with a later real config
     * deployment, which calls {@code getConfiguration(pid).update(props)} on that same PID.
     */
    private boolean resolveConfigured() {
        if (configurationAdmin == null) {
            return false;
        }
        try {
            Configuration configuration = configurationAdmin.getConfiguration(CONTENT_AI_CLIENT_PID);
            Object apiKey = configuration.getProperties() == null
                    ? null
                    : configuration.getProperties().get(API_KEY_PROPERTY);
            return apiKey instanceof String && StringUtils.isNotBlank((String) apiKey);
        } catch (IOException e) {
            LOGGER.warn("Unable to determine whether Content AI is configured (PID {})", CONTENT_AI_CLIENT_PID, e);
            return false;
        } catch (RuntimeException e) {
            // Defensive: an unexpected ConfigurationAdmin implementation failure should degrade
            // to "not configured", never break the whole component's rendering via @PostConstruct.
            LOGGER.warn("Unexpected failure determining whether Content AI is configured (PID {})", CONTENT_AI_CLIENT_PID, e);
            return false;
        }
    }
}
