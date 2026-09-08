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
package com.adobe.aem.guides.wknd.core.models;

import java.util.List;
import java.util.Map;

/**
 * Exposes every element of every Content Fragment referenced on the current page as a
 * ready-to-emit schema.org {@code additionalProperty} JSON-LD document.
 *
 * Not scoped to any one content type or Content Fragment model - it reads whatever
 * elements the referenced fragment(s) actually define, so the same markup works for
 * adventure pages, magazine articles, or any future page with a Content Fragment.
 *
 * The full JSON document is built here, in Java, rather than assembled piecemeal in HTL -
 * HTL's {@code data-sly-list} does not reliably iterate inside a {@code <script>} element
 * once its source expression carries an explicit context annotation (required there by the
 * HTL compiler), so template-side JSON assembly is fragile for this case.
 **/
public interface ContentFragmentJsonLd {

    /**
     * @return true if at least one Content Fragment component was found on the page.
     */
    boolean isPresent();

    /**
     * @return the complete JSON-LD document as a string, ready to emit verbatim inside a
     * {@code <script type="application/ld+json">} tag. Empty if {@link #isPresent()} is false.
     */
    String getJsonLd();

    /**
     * @return one entry per Content Fragment element found on the page, each with "name" and
     * "value" keys - for rendering each as its own plain {@code <meta>} tag, since that carrier
     * mechanism is confirmed (empirically) to work for at least one custom field name where
     * JSON-LD did not. Empty if {@link #isPresent()} is false.
     */
    List<Map<String, String>> getAllProperties();
}
