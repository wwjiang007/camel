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
package org.apache.camel.component.file;

import org.apache.camel.ContextTestSupport;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Unit test for the file sort by expression
 */
@DisabledOnOs(value = { OS.LINUX },
              architectures = { "ppc64le" },
              disabledReason = "This test does not run reliably multiple platforms (see CAMEL-21438)")
public class FileSortByIgnoreCaseExpressionTest extends ContextTestSupport {

    private void prepareFolder(String folder) {
        template.sendBodyAndHeader(fileUri(folder), "Hello Paris", Exchange.FILE_NAME, "report-3.dat");

        template.sendBodyAndHeader(fileUri(folder), "Hello London", Exchange.FILE_NAME, "REPORT-2.txt");

        template.sendBodyAndHeader(fileUri(folder), "Hello Copenhagen", Exchange.FILE_NAME,
                "Report-1.xml");
    }

    @Test
    public void testSortFilesByNameWithCase() throws Exception {
        prepareFolder("a");

        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from(fileUri("a/?sortBy=file:name&initialDelay=250&delay=1000")).convertBodyTo(String.class).to("mock:result");
            }
        });
        context.start();

        MockEndpoint mock = getMockEndpoint("mock:result");
        mock.expectedBodiesReceived("Hello London", "Hello Copenhagen", "Hello Paris");

        assertMockEndpointsSatisfied();
    }

    @Test
    public void testSortFilesByNameNoCase() throws Exception {
        prepareFolder("b");

        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from(fileUri("b/?initialDelay=0&delay=10&sortBy=ignoreCase:file:name")).convertBodyTo(String.class)
                        .to("mock:nocase");
            }
        });
        context.start();

        MockEndpoint nocase = getMockEndpoint("mock:nocase");
        nocase.expectedBodiesReceived("Hello Copenhagen", "Hello London", "Hello Paris");

        assertMockEndpointsSatisfied();
    }

    @Test
    public void testSortFilesByNameNoCaseReverse() throws Exception {
        prepareFolder("c");

        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from(fileUri("c/?initialDelay=0&delay=10&sortBy=reverse:ignoreCase:file:name")).convertBodyTo(String.class)
                        .to("mock:nocasereverse");
            }
        });
        context.start();

        MockEndpoint nocasereverse = getMockEndpoint("mock:nocasereverse");
        nocasereverse.expectedBodiesReceived("Hello Paris", "Hello London", "Hello Copenhagen");

        assertMockEndpointsSatisfied();
    }

}
