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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.adobe.cq.wcm.core.components.services.contentai.ContentSourceSearchResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WkndContentAISearchResultsServletTest {

    private static ContentSourceSearchResult.Item item(String id, double score) {
        ContentSourceSearchResult.Item item = new ContentSourceSearchResult.Item();
        item.setId(id);
        item.setScore(score);
        return item;
    }

    private static ContentSourceSearchResult partial(long totalResults, ContentSourceSearchResult.Item... items) {
        ContentSourceSearchResult result = new ContentSourceSearchResult();
        result.setTotalResults(totalResults);
        result.setResults(Arrays.asList(items));
        return result;
    }

    @Test
    void mergeCapsAtResultsSizeAcrossAllSources() {
        // Two sources each independently report more matches than the requested cap allows through.
        ContentSourceSearchResult sourceA = partial(10, item("a1", 0.9), item("a2", 0.8), item("a3", 0.7));
        ContentSourceSearchResult sourceB = partial(10, item("b1", 0.95), item("b2", 0.6));

        WkndContentAISearchResponse merged = WkndContentAISearchResultsServlet.merge(
            Arrays.asList(sourceA, sourceB), 3);

        assertEquals(3, merged.getResults().size());
        assertEquals("b1", merged.getResults().get(0).getId());
        assertEquals("a1", merged.getResults().get(1).getId());
        assertEquals("a2", merged.getResults().get(2).getId());
    }

    @Test
    void mergeSumsTotalResultsAcrossSourcesInsteadOfTakingTheMax() {
        ContentSourceSearchResult sourceA = partial(10, item("a1", 0.9));
        ContentSourceSearchResult sourceB = partial(25, item("b1", 0.8));

        WkndContentAISearchResponse merged = WkndContentAISearchResultsServlet.merge(
            Arrays.asList(sourceA, sourceB), 10);

        assertEquals(35, merged.getTotalResults());
    }

    @Test
    void mergeDedupesByIdKeepingTheHighestScore() {
        ContentSourceSearchResult sourceA = partial(1, item("shared", 0.4));
        ContentSourceSearchResult sourceB = partial(1, item("shared", 0.9));

        WkndContentAISearchResponse merged = WkndContentAISearchResultsServlet.merge(
            Arrays.asList(sourceA, sourceB), 10);

        assertEquals(1, merged.getResults().size());
        assertEquals(0.9, merged.getResults().get(0).getScore());
    }

    @Test
    void mergeWithZeroLimitReturnsEverythingUncapped() {
        List<ContentSourceSearchResult> partials = Collections.singletonList(
            partial(2, item("a1", 0.5), item("a2", 0.4)));

        WkndContentAISearchResponse merged = WkndContentAISearchResultsServlet.merge(partials, 0);

        assertEquals(2, merged.getResults().size());
    }
}
