/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.test.integration.indices.mapping;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.WriteConsistencyLevel;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.index.query.MatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHits;
import org.elasticsearch.test.integration.AbstractSharedClusterTest;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.Matchers.emptyIterable;

public class ConcurrentDynamicTemplateTests extends AbstractSharedClusterTest {

    private final String mappingType = "test-mapping";

    @Test // see #3544
    public void testConcurrentDynamicMapping() throws Exception {
        final String mapping = "{" + mappingType + ": {" + "\"properties\": {" + "\"an_id\": {"
                + "\"type\": \"string\"," + "\"store\": \"yes\"," + "\"index\": \"not_analyzed\"" + "}" + "}," + "\"dynamic_templates\": ["
                + "{" + "\"participants\": {" + "\"path_match\": \"*\"," + "\"mapping\": {" + "\"type\": \"string\"," + "\"store\": \"yes\","
                + "\"index\": \"analyzed\"," + "\"analyzer\": \"whitespace\"" + "}" + "}" + "}" + "]" + "}" + "}";
        // The 'fieldNames' array is used to help with retrieval of index terms
        // after testing

        final String fieldName = "participants.ACCEPTED";
        int iters = atLeast(5);
        for (int i = 0; i < iters; i++) {
            wipeIndex("test");
            client().admin().indices().prepareCreate("test").addMapping(mappingType, mapping).execute().actionGet();
            ensureYellow();
            int numDocs = atLeast(5);
            final CountDownLatch latch = new CountDownLatch(numDocs);
            final List<Throwable> throwable = new CopyOnWriteArrayList<Throwable>();
            for (int j = 0; j < numDocs; j++) {
                Map<String, Object> source = new HashMap<String, Object>();
                source.put("an_id", UUID.randomUUID().toString());
                source.put(fieldName, "test-user");
                client().prepareIndex("test", mappingType).setSource(source).setConsistencyLevel(WriteConsistencyLevel.QUORUM).execute(new ActionListener<IndexResponse>() {
                    @Override
                    public void onResponse(IndexResponse response) {
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(Throwable e) {
                        throwable.add(e);
                        latch.countDown();
                    }
                });
            }
            latch.await();
            assertThat(throwable, emptyIterable());
            refresh();
            MatchQueryBuilder builder = QueryBuilders.matchQuery(fieldName, "test-user");
            SearchHits sh = client().prepareSearch("test").setQuery(builder).execute().actionGet().getHits();
            assertEquals(sh.getTotalHits(), numDocs);

            assertEquals(client().prepareSearch("test").setQuery(QueryBuilders.matchQuery(fieldName, "test user")).execute().actionGet().getHits().getTotalHits(), 0);

        }
    }

}