/*******************************************************************************
 * Copyright 2026 Adobe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package com.adobe.aem.guides.wknd.core.internal.servlets.contentaisearch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.adobe.cq.wcm.core.components.models.ContentAISupportedSearch;
import com.adobe.cq.wcm.core.components.services.contentai.ContentAIClient;
import com.adobe.cq.wcm.core.components.services.contentai.ContentAIClientException;
import com.adobe.cq.wcm.core.components.services.contentai.ContentSourceSearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * WKND-owned replacement for the core {@code ContentAISearchResultsServlet}, registered against
 * {@code wknd/components/contentaisearch} instead of the core resourceType so it takes precedence for
 * WKND-authored instances - Sling servlet resolution walks the {@code sling:resourceSuperType} chain the
 * same way script resolution does, and picks the most specific registered resourceType.
 *
 * Ports the results-cap fix from aem-core-wcm-components PR #3068 (GRANITE-70028), which
 * core.wcm.components 2.32.4 predates: caps the merged, deduplicated result list at
 * {@code model.getResultsSize()} (a true final cap across all content sources) and reports
 * {@code totalResults} as the sum across sources, not the max. The equivalent core fix lives in the
 * (OSGi-internal, unexported) {@code ContentSourceSearchMerger} class in
 * {@code com.adobe.cq.wcm.core.components.internal.services.contentai} - not importable from this bundle
 * (confirmed absent from that bundle's {@code Export-Package}) - so the merge/cap/dedup logic below is a
 * WKND-owned reimplementation using only the public
 * {@code com.adobe.cq.wcm.core.components.services.contentai} API surface.
 *
 * Deliberately does not implement cross-request cursor pagination ("Load More") - always reports
 * {@code hasMore: false}. Acceptable for the demo's single-page-of-results scope; out of scope to
 * replicate fully here since this class is temporary (see below).
 *
 * TEMPORARY: delete this class, {@link WkndContentAISearchResponse}, and this OSGi registration once
 * WKND bumps to an aem-core-wcm-components release that includes PR #3068 - see
 * docs/superpowers/specs/2026-08-05-wknd-contentai-semantic-search-demo-design.md §5.2.
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.methods=GET",
        "sling.servlet.resourceTypes=wknd/components/contentaisearch",
        "sling.servlet.selectors=search",
        "sling.servlet.extensions=json"
    }
)
public class WkndContentAISearchResultsServlet extends SlingSafeMethodsServlet {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = LoggerFactory.getLogger(WkndContentAISearchResultsServlet.class);
    private static final String PARAM_QUERY = "q";
    private static final int MAX_QUERY_LENGTH = 512;
    private static final int API_PAGE_SIZE = 25;

    @Reference
    private transient ContentAIClient contentAIClient;

    @Override
    protected void doGet(@NotNull SlingHttpServletRequest request, @NotNull SlingHttpServletResponse response) throws IOException {
        String queryText = request.getParameter(PARAM_QUERY);
        if (StringUtils.isBlank(queryText)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing required parameter: " + PARAM_QUERY);
            return;
        }
        if (queryText.length() > MAX_QUERY_LENGTH) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Parameter " + PARAM_QUERY + " exceeds maximum length of " + MAX_QUERY_LENGTH);
            return;
        }

        ContentAISupportedSearch model = request.adaptTo(ContentAISupportedSearch.class);
        if (model == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }

        List<String> sources = model.getContentSources();
        if (sources.isEmpty()) {
            writeJson(new WkndContentAISearchResponse(), response);
            return;
        }

        int apiLimit = Math.min(API_PAGE_SIZE, Math.max(model.getResultsSize(), 1));
        String contentSourceType = model.getContentSourceType();

        List<ContentSourceSearchResult> partials = new ArrayList<>();
        try {
            for (String source : sources) {
                partials.add(contentAIClient.search(source, contentSourceType, queryText, apiLimit));
            }
        } catch (ContentAIClientException e) {
            LOGGER.error("Content AI search request failed for content sources {}", sources, e);
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "Content AI request failed");
            return;
        }

        writeJson(merge(partials, model.getResultsSize()), response);
    }

    /**
     * Merges partial per-source results into one ranked, deduplicated, capped list - the fix ported from
     * PR #3068's {@code ContentSourceSearchMerger.merge(...)}: dedupe by item id keeping the highest
     * score, sort descending by score, cap at {@code limit} (a true final cap, not a per-source cap), and
     * report {@code totalResults} as the sum of each source's own reported total (never the max).
     *
     * @param partials per-source search responses
     * @param limit    maximum number of merged results to return; {@code <= 0} means uncapped
     * @return the merged, capped response
     */
    @NotNull
    static WkndContentAISearchResponse merge(@NotNull List<ContentSourceSearchResult> partials, int limit) {
        long reportedTotal = 0;
        Map<String, ContentSourceSearchResult.Item> byId = new LinkedHashMap<>();
        for (ContentSourceSearchResult partial : partials) {
            if (partial == null) {
                continue;
            }
            reportedTotal += partial.getTotalResults();
            if (partial.getResults() == null) {
                continue;
            }
            for (ContentSourceSearchResult.Item item : partial.getResults()) {
                if (item == null || StringUtils.isBlank(item.getId())) {
                    continue;
                }
                ContentSourceSearchResult.Item existing = byId.get(item.getId());
                if (existing == null || item.getScore() > existing.getScore()) {
                    byId.put(item.getId(), item);
                }
            }
        }

        List<ContentSourceSearchResult.Item> merged = new ArrayList<>(byId.values());
        merged.sort(Comparator.comparingDouble(ContentSourceSearchResult.Item::getScore).reversed());

        int effectiveLimit = limit > 0 ? limit : merged.size();
        if (merged.size() > effectiveLimit) {
            merged = merged.subList(0, effectiveLimit);
        }

        WkndContentAISearchResponse response = new WkndContentAISearchResponse();
        response.setResults(merged);
        response.setTotalResults(Math.max(reportedTotal, merged.size()));
        response.setHasMore(false);
        return response;
    }

    private void writeJson(Object result, SlingHttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        new ObjectMapper().writeValue(response.getWriter(), result);
    }
}
