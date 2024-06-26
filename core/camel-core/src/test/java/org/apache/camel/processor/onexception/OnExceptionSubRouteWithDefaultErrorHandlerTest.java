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
package org.apache.camel.processor.onexception;

import org.apache.camel.builder.RouteBuilder;

/**
 * Unit test inspired by user forum.
 */
public class OnExceptionSubRouteWithDefaultErrorHandlerTest extends OnExceptionRouteWithDefaultErrorHandlerTest {

    @Override
    protected RouteBuilder createRouteBuilder() {
        return new RouteBuilder() {
            @Override
            public void configure() {
                // here we start the routing with the consumer
                from("direct:start").onException(MyTechnicalException.class).maximumRedeliveries(0).handled(true).end()
                        .onException(MyFunctionalException.class)
                        .maximumRedeliveries(0).handled(true).to("bean:myOwnHandler").end()

                        .choice().when().xpath("//type = 'myType'").to("bean:myServiceBean").end().to("mock:result");
            }
        };
    }

}
