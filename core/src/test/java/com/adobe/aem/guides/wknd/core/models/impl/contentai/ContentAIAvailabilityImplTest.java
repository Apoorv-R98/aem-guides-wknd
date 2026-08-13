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

import java.util.Hashtable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

import com.adobe.aem.guides.wknd.core.models.contentai.ContentAIAvailability;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Uses Sling Mocks' own {@code MockConfigurationAdmin} (rather than a raw Mockito mock
 * registered over it) - seeded through its real, supported {@code getConfiguration(pid).update(...)}
 * API, matching how {@link ContentAIAvailabilityImpl} itself reads configuration.
 */
@ExtendWith(AemContextExtension.class)
class ContentAIAvailabilityImplTest {

    private static final String CONTENT_AI_CLIENT_PID =
            "com.adobe.cq.wcm.core.components.internal.services.contentai.ContentAIClientImpl";

    private final AemContext ctx = new AemContext();

    @BeforeEach
    void setUp() {
        ctx.addModelsForClasses(ContentAIAvailabilityImpl.class);
        ctx.create().resource("/content/ai-powered-search",
                "sling:resourceType", "wknd/components/contentaisearch");
    }

    private ContentAIAvailability adapt() {
        ctx.currentResource("/content/ai-powered-search");
        return ctx.request().adaptTo(ContentAIAvailability.class);
    }

    private void seedApiKey(String apiKey) throws Exception {
        ConfigurationAdmin configurationAdmin = ctx.getService(ConfigurationAdmin.class);
        Configuration configuration = configurationAdmin.getConfiguration(CONTENT_AI_CLIENT_PID);
        Hashtable<String, Object> props = new Hashtable<>();
        if (apiKey != null) {
            props.put("apiKey", apiKey);
        }
        configuration.update(props);
    }

    @Test
    void isConfigured_returnsFalse_whenNoConfigurationExists() {
        assertFalse(adapt().isConfigured());
    }

    @Test
    void isConfigured_returnsFalse_whenApiKeyMissing() throws Exception {
        seedApiKey(null);

        assertFalse(adapt().isConfigured());
    }

    @Test
    void isConfigured_returnsFalse_whenApiKeyBlank() throws Exception {
        seedApiKey("   ");

        assertFalse(adapt().isConfigured());
    }

    @Test
    void isConfigured_returnsTrue_whenApiKeyPresent() throws Exception {
        seedApiKey("3a2ede9fd93a47c1b41fa4d73a97b926");

        assertTrue(adapt().isConfigured());
    }
}
