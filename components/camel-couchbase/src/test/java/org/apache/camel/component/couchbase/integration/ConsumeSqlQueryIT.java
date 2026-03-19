/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.couchbase.integration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.manager.bucket.BucketSettings;
import com.couchbase.client.java.manager.bucket.BucketType;
import com.couchbase.client.java.manager.query.CreatePrimaryQueryIndexOptions;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.infra.common.TestUtils;
import org.apache.camel.test.infra.couchbase.services.CouchbaseService;
import org.apache.camel.test.infra.couchbase.services.CouchbaseServiceFactory;
import org.apache.camel.test.junit6.CamelTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.extension.RegisterExtension;

@DisabledIfSystemProperty(named = "ci.env.name", matches = ".*",
                          disabledReason = "Too resource intensive for most systems to run reliably")
@Tags({ @Tag("couchbase-71") })
public class ConsumeSqlQueryIT extends CamelTestSupport {

    @RegisterExtension
    public static CouchbaseService service = CouchbaseServiceFactory.createSingletonService();

    protected static String bucketName;
    protected static Cluster cluster;

    @BeforeAll
    static void setUpCouchbase() {
        bucketName = "sqlBucket" + TestUtils.randomWithRange(0, 100);
        cluster = Cluster.connect(service.getConnectionString(), service.getUsername(), service.getPassword());

        // Create bucket without specifying storage backend (uses server default — Magma on 8.0+)
        cluster.buckets().createBucket(
                BucketSettings.create(bucketName).bucketType(BucketType.COUCHBASE).flushEnabled(true));

        cluster.bucket(bucketName).waitUntilReady(Duration.ofSeconds(30));

        // Create primary index for SQL++ queries
        cluster.queryIndexes().createPrimaryIndex(bucketName,
                CreatePrimaryQueryIndexOptions.createPrimaryQueryIndexOptions().ignoreIfExists(true));
    }

    @BeforeEach
    public void waitForStarted() {
        cluster.bucket(bucketName).waitUntilReady(Duration.ofSeconds(30));
    }

    @BeforeEach
    public void addToBucket() {
        for (int i = 0; i < 15; i++) {
            cluster.bucket(bucketName).defaultCollection().upsert("DocumentID_" + i, "message" + i);
        }
    }

    @Test
    public void testConsumeWithSqlQuery() throws Exception {
        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedMessageCount(10);

        MockEndpoint.assertIsSatisfied(context, 30, TimeUnit.SECONDS);
    }

    @AfterEach
    public void cleanBucket() {
        cluster.buckets().flushBucket(bucketName);
    }

    @AfterAll
    public static void tearDownCouchbase() {
        cluster.buckets().dropBucket(bucketName);
        cluster.disconnect();
    }

    public String getConnectionUri() {
        return String.format("couchbase:http://%s:%d?bucket=%s&username=%s&password=%s", service.getHostname(),
                service.getPort(), bucketName, service.getUsername(), service.getPassword());
    }

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            @Override
            public void configure() {
                String query = "SELECT META().id AS __id, * FROM `" + bucketName + "` LIMIT 10";
                from(getConnectionUri() + "&statement=" + query)
                        .log("message received via SQL++")
                        .to("mock:result");
            }
        };
    }
}
