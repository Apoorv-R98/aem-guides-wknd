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

import java.util.ArrayList;
import java.util.List;

import com.adobe.cq.wcm.core.components.services.contentai.ContentSourceSearchResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * JSON response shape for {@link WkndContentAISearchResultsServlet}, matching the field names the
 * shipped {@code contentaisearch.js} clientlib (Task 5) reads: {@code results}, {@code hasMore}.
 *
 * WKND OVERLAY (GRANITE-71500): delete this class alongside {@link WkndContentAISearchResultsServlet}
 * once WKND bumps to an aem-core-wcm-components release that includes PR #3068 - see
 * docs/superpowers/specs/2026-08-05-wknd-contentai-semantic-search-demo-design.md §5.2.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class WkndContentAISearchResponse {

    private List<ContentSourceSearchResult.Item> results = new ArrayList<>();
    private long totalResults;
    private boolean hasMore;

    public List<ContentSourceSearchResult.Item> getResults() {
        return results;
    }

    public void setResults(List<ContentSourceSearchResult.Item> results) {
        this.results = results;
    }

    public long getTotalResults() {
        return totalResults;
    }

    public void setTotalResults(long totalResults) {
        this.totalResults = totalResults;
    }

    public boolean isHasMore() {
        return hasMore;
    }

    public void setHasMore(boolean hasMore) {
        this.hasMore = hasMore;
    }
}
