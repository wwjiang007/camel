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
package org.apache.camel.dataformat.csv;

import java.util.List;

import org.apache.camel.Message;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.spring.junit5.CamelSpringTestSupport;
import org.apache.camel.util.CastUtils;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test cases for {@link CsvRecordConverter}.
 */
public class CsvDataFormatCustomRecordConverterTest extends CamelSpringTestSupport {

    @Test
    void unmarshalTest() throws InterruptedException {
        MockEndpoint mock = getMockEndpoint("mock:unmarshaled");
        mock.expectedMessageCount(1);
        template.sendBody("direct:unmarshal", getData());
        mock.assertIsSatisfied();
        Message message = mock.getReceivedExchanges().get(0).getIn();
        List<List<String>> body = CastUtils.cast((List) message.getBody());
        assertNotNull(body);
        assertEquals(1, body.size());
        List<String> row = body.get(0);
        assertEquals(3, row.size());
        assertEquals("[Hello, Again, Democracy]", row.toString());
    }

    private String getData() {
        return String.join(";", "A1", "B1", "C1");
    }

    @Override
    protected ClassPathXmlApplicationContext createApplicationContext() {
        return new ClassPathXmlApplicationContext(
                "org/apache/camel/dataformat/csv/CsvDataFormatCustomRecordConverter.xml");
    }
}
