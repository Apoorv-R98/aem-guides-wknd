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
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import com.adobe.aem.guides.wknd.core.models.ContentFragmentJsonLd;
import com.adobe.cq.dam.cfm.ContentElement;
import com.adobe.cq.dam.cfm.ContentFragment;
import com.adobe.cq.dam.cfm.FragmentData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith({AemContextExtension.class, MockitoExtension.class})
class ContentFragmentJsonLdImplTest {

    private static final String FRAGMENT_PATH = "/content/dam/wknd-shared/en/adventures/bali-surf-camp/bali-surf-camp";

    private final AemContext ctx = new AemContext(ResourceResolverType.JCR_MOCK);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ctx.load().json("/com/adobe/aem/guides/wknd/core/models/impl/ContentFragmentJsonLdImplTest.json", "/content");
        ctx.addModelsForClasses(ContentFragmentJsonLdImpl.class);
    }

    private void stubFragmentAt(String path, ContentFragment fragment) {
        Function<Resource, ContentFragment> adapterFunction =
                resource -> path.equals(resource.getPath()) ? fragment : null;
        ctx.registerAdapter(Resource.class, ContentFragment.class, adapterFunction);
    }

    private ContentElement elementOf(String name, Object typedValue) {
        ContentElement element = mock(ContentElement.class);
        when(element.getName()).thenReturn(name);
        FragmentData data = mock(FragmentData.class);
        when(data.getValue()).thenReturn(typedValue);
        when(element.getValue()).thenReturn(data);
        return element;
    }

    @Test
    void isPresent_falseWhenNoContentFragmentOnPage() {
        ctx.currentResource("/content/no-fragment-page/jcr:content");

        ContentFragmentJsonLd cf = ctx.request().adaptTo(ContentFragmentJsonLd.class);

        assertFalse(cf.isPresent());
        assertEquals("", cf.getJsonLd());
    }

    @Test
    void isPresent_falseWhenFragmentPathDoesNotResolve() {
        // /content/dam/wknd-shared/en/adventures/does-not-exist/does-not-exist is not a resource
        // in the mocked repo at all - getResource() returns null, so this must be skipped, not thrown.
        ctx.currentResource("/content/broken-reference-page/jcr:content");

        ContentFragmentJsonLd cf = ctx.request().adaptTo(ContentFragmentJsonLd.class);

        assertFalse(cf.isPresent());
    }

    @Test
    void isPresent_trueAndDedupesMultipleComponentsReferencingTheSameFragment() {
        // Fixture has two contentfragment component instances (one under "container", one under
        // a tab) both pointing at the same FRAGMENT_PATH - getElements() must be called once, not twice.
        ContentElement difficulty = elementOf("difficulty", "Beginner");
        ContentFragment fragment = mock(ContentFragment.class);
        when(fragment.getElements()).thenReturn(Collections.singletonList(difficulty).iterator());
        stubFragmentAt(FRAGMENT_PATH, fragment);

        ctx.currentResource("/content/adventure-page/jcr:content");
        ContentFragmentJsonLd cf = ctx.request().adaptTo(ContentFragmentJsonLd.class);

        assertTrue(cf.isPresent());
        // getElements() is only invoked when the JSON-LD is actually assembled - this is where
        // dedup would show up as a bug (called twice = the same fragment emitted twice).
        cf.getJsonLd();
        verifyGetElementsCalledExactlyOnce(fragment);
    }

    private void verifyGetElementsCalledExactlyOnce(ContentFragment fragment) {
        org.mockito.Mockito.verify(fragment, org.mockito.Mockito.times(1)).getElements();
    }

    @Test
    void getJsonLd_emitsTypedValuesAndEscapesScriptCloseTag() throws Exception {
        List<ContentElement> elements = new ArrayList<>();
        elements.add(elementOf("difficulty", "Beginner"));
        elements.add(elementOf("groupSize", 6L));
        elements.add(elementOf("price", 5000.0));
        // A value containing a literal "</script>" must not be able to break out of the
        // <script type="application/ld+json"> block this gets emitted into with an 'unsafe' HTL context.
        elements.add(elementOf("description", "</script><script>alert(1)</script>"));

        ContentFragment fragment = mock(ContentFragment.class);
        when(fragment.getElements()).thenAnswer(invocation -> elements.iterator());
        stubFragmentAt(FRAGMENT_PATH, fragment);

        ctx.currentResource("/content/adventure-page/jcr:content");
        ContentFragmentJsonLd cf = ctx.request().adaptTo(ContentFragmentJsonLd.class);

        String json = cf.getJsonLd();

        assertFalse(json.contains("</script>"), "raw '</script>' must not survive into the serialized document");

        // The escaped form must still be valid, parseable JSON with the original value intact.
        // Flat top-level keys - not nested in an additionalProperty array - since that's the
        // shape Content AI's extraction layer was empirically confirmed to actually read.
        JsonNode root = mapper.readTree(json);
        assertEquals("https://schema.org", root.get("@context").asText());
        assertTrue(root.get("groupSize").isNumber(), "groupSize should serialize as a JSON number, not a string");
        assertEquals(6L, root.get("groupSize").asLong());
        assertTrue(root.get("price").isNumber(), "price should serialize as a JSON number, not a string");
        assertEquals(5000.0, root.get("price").asDouble());
        assertTrue(root.get("description").asText().contains("</script>"),
                "the parsed value itself should still contain the real text");
    }

    @Test
    void getJsonLd_skipsOneBadElementWithoutFailingTheOthers() {
        ContentElement badElement = mock(ContentElement.class);
        when(badElement.getName()).thenReturn("broken");
        when(badElement.getValue()).thenThrow(new RuntimeException("simulated CFM failure"));

        List<ContentElement> elements = new ArrayList<>();
        elements.add(elementOf("difficulty", "Beginner"));
        elements.add(badElement);
        elements.add(elementOf("price", 5000.0));

        ContentFragment fragment = mock(ContentFragment.class);
        when(fragment.getElements()).thenAnswer(invocation -> elements.iterator());
        stubFragmentAt(FRAGMENT_PATH, fragment);

        ctx.currentResource("/content/adventure-page/jcr:content");
        ContentFragmentJsonLd cf = ctx.request().adaptTo(ContentFragmentJsonLd.class);

        // Must not throw, and must still emit the two good elements.
        String json = cf.getJsonLd();
        assertTrue(json.contains("difficulty"));
        assertTrue(json.contains("price"));
        assertFalse(json.contains("broken"));
    }

    @Test
    void getAllProperties_returnsOneEntryPerElementSkippingBadOnes() {
        ContentElement badElement = mock(ContentElement.class);
        when(badElement.getName()).thenReturn("broken");
        when(badElement.getValue()).thenThrow(new RuntimeException("simulated CFM failure"));

        List<ContentElement> elements = new ArrayList<>();
        elements.add(elementOf("difficulty", "Beginner"));
        elements.add(badElement);
        elements.add(elementOf("groupSize", 6L));

        ContentFragment fragment = mock(ContentFragment.class);
        when(fragment.getElements()).thenAnswer(invocation -> elements.iterator());
        stubFragmentAt(FRAGMENT_PATH, fragment);

        ctx.currentResource("/content/adventure-page/jcr:content");
        ContentFragmentJsonLd cf = ctx.request().adaptTo(ContentFragmentJsonLd.class);

        List<java.util.Map<String, String>> properties = cf.getAllProperties();

        assertEquals(2, properties.size(), "the broken element should be skipped, not included as a blank entry");
        assertEquals("difficulty", properties.get(0).get("name"));
        assertEquals("Beginner", properties.get(0).get("value"));
        assertEquals("groupSize", properties.get(1).get("name"));
        assertEquals("6", properties.get(1).get("value"));
    }
}
