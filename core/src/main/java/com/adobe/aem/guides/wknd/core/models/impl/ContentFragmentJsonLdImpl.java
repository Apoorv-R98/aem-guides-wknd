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
package com.adobe.aem.guides.wknd.core.models.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.models.annotations.DefaultInjectionStrategy;
import org.apache.sling.models.annotations.Model;
import org.apache.sling.models.annotations.injectorspecific.Self;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.aem.guides.wknd.core.models.ContentFragmentJsonLd;
import com.adobe.cq.dam.cfm.ContentElement;
import com.adobe.cq.dam.cfm.ContentFragment;
import com.adobe.cq.dam.cfm.FragmentData;
import com.fasterxml.jackson.databind.ObjectMapper;

@Model(
        adaptables = {SlingHttpServletRequest.class},
        adapters = {ContentFragmentJsonLd.class},
        defaultInjectionStrategy = DefaultInjectionStrategy.OPTIONAL
)
public class ContentFragmentJsonLdImpl implements ContentFragmentJsonLd {

    private static final Logger LOG = LoggerFactory.getLogger(ContentFragmentJsonLdImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Deliberately not resourceType-based: Core Components' contentfragment component is
    // versioned in its sling:resourceSuperType (e.g. ".../v1/contentfragment", future ".../v2/..."),
    // and WKND's own wrapper only ever sets fragmentPath - checking for that property directly is
    // both simpler and doesn't need updating every time a new component version ships.
    private static final String FRAGMENT_PATH_PROPERTY = "fragmentPath";

    @Self
    private SlingHttpServletRequest request;

    private final List<ContentFragment> fragments = new ArrayList<>();

    @PostConstruct
    protected void init() {
        Resource currentPageContentResource = request.getResource();
        ResourceResolver resourceResolver = request.getResourceResolver();
        Set<String> seenFragmentPaths = new LinkedHashSet<>();
        findContentFragments(currentPageContentResource, resourceResolver, seenFragmentPaths);
    }

    private void findContentFragments(Resource root, ResourceResolver resourceResolver, Set<String> seenFragmentPaths) {
        for (Resource child : root.getChildren()) {
            String fragmentPath = child.getValueMap().get(FRAGMENT_PATH_PROPERTY, String.class);
            // Several components can reference the same fragment (e.g. one per tab) - only emit it once.
            if (fragmentPath != null && seenFragmentPaths.add(fragmentPath)) {
                Resource fragmentResource = resourceResolver.getResource(fragmentPath);
                ContentFragment fragment = fragmentResource != null
                        ? fragmentResource.adaptTo(ContentFragment.class)
                        : null;
                if (fragment != null) {
                    fragments.add(fragment);
                } else {
                    LOG.warn("Content Fragment component at {} references '{}', but it could not be resolved "
                            + "or adapted - skipping it for JSON-LD purposes.", child.getPath(), fragmentPath);
                }
            }
            findContentFragments(child, resourceResolver, seenFragmentPaths);
        }
    }

    @Override
    public boolean isPresent() {
        return !fragments.isEmpty();
    }

    @Override
    public String getJsonLd() {
        if (fragments.isEmpty()) {
            return "";
        }
        // Flat, top-level keys - not the schema.org additionalProperty/PropertyValue pattern.
        // Empirically confirmed (via a live Content AI acquisition run against this exact output):
        // Content AI's JSON-LD extraction reads the top-level @type but does not unpack a nested
        // additionalProperty array into named fields. Flat keys are what its extractor actually
        // picks up, so that's what this emits, even though it's a looser use of schema.org
        // vocabulary than additionalProperty would have been.
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("@context", "https://schema.org");
        document.put("@type", determineSchemaType());
        for (ContentFragment fragment : fragments) {
            Iterator<ContentElement> elements = fragment.getElements();
            while (elements.hasNext()) {
                ContentElement element = elements.next();
                // Each element is read independently: a single element that fails to resolve
                // (e.g. an unsupported data type, a corrupt value) must not take down every
                // other element on the page, let alone the page render itself.
                try {
                    Object value = valueOf(element);
                    if (value == null) {
                        continue;
                    }
                    // Later fragments win on a name collision (e.g. two different Content Fragment
                    // models on the same page both defining a "title" element) - acceptable for a
                    // flat merge; a real collision would need a per-fragment namespace, not needed today.
                    document.put(element.getName(), value);
                } catch (Exception e) {
                    LOG.warn("Failed to read Content Fragment element '{}' - skipping it.", element.getName(), e);
                }
            }
        }
        try {
            String json = MAPPER.writeValueAsString(document);
            // This gets emitted with an 'unsafe' HTL context (no HTML-escaping) directly inside a
            // <script> element, so a fragment value containing a literal "</script" would otherwise
            // terminate the script block early. "<" is not meaningful JSON syntax anywhere, so
            // escaping it as < is always safe and defuses that regardless of case/whitespace.
            return json.replace("<", "\\u003c");
        } catch (Exception e) {
            LOG.warn("Failed to serialize Content Fragment JSON-LD", e);
            return "";
        }
    }

    /**
     * Maps a Content Fragment model's title to the schema.org @type its pages should be
     * tagged with - lets Content AI's jsonld_type extraction (confirmed to work, unlike
     * arbitrary custom field names) actually distinguish content kinds instead of everything
     * defaulting to the generic, unhelpful "WebPage". Extend this map as new CF models are
     * added; unrecognized models fall back to "WebPage" rather than guessing.
     */
    private static final Map<String, String> SCHEMA_TYPE_BY_MODEL_TITLE = new LinkedHashMap<>();
    static {
        SCHEMA_TYPE_BY_MODEL_TITLE.put("Adventure", "TouristTrip");
    }
    private static final String DEFAULT_SCHEMA_TYPE = "WebPage";

    private String determineSchemaType() {
        for (ContentFragment fragment : fragments) {
            String modelTitle = fragment.getTemplate() != null ? fragment.getTemplate().getTitle() : null;
            String schemaType = modelTitle != null ? SCHEMA_TYPE_BY_MODEL_TITLE.get(modelTitle) : null;
            if (schemaType != null) {
                return schemaType;
            }
        }
        return DEFAULT_SCHEMA_TYPE;
    }

    @Override
    public List<Map<String, String>> getAllProperties() {
        List<Map<String, String>> properties = new ArrayList<>();
        for (ContentFragment fragment : fragments) {
            Iterator<ContentElement> elements = fragment.getElements();
            while (elements.hasNext()) {
                ContentElement element = elements.next();
                try {
                    Object value = valueOf(element);
                    if (value == null) {
                        continue;
                    }
                    Map<String, String> property = new LinkedHashMap<>();
                    property.put("name", element.getName());
                    property.put("value", String.valueOf(value));
                    properties.add(property);
                } catch (Exception e) {
                    LOG.warn("Failed to read Content Fragment element '{}' - skipping it.", element.getName(), e);
                }
            }
        }
        return properties;
    }

    /**
     * Prefers the element's typed value (so numbers/booleans/multi-value lists serialize as real
     * JSON numbers/booleans/arrays) and falls back to the rendered string content only if no
     * typed value is available.
     */
    private Object valueOf(ContentElement element) {
        FragmentData data = element.getValue();
        Object typedValue = data != null ? data.getValue() : null;
        return typedValue != null ? typedValue : element.getContent();
    }
}
