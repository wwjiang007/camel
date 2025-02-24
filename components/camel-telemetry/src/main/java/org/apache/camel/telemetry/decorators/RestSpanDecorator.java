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
package org.apache.camel.telemetry.decorators;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.telemetry.Span;

public class RestSpanDecorator extends AbstractHttpSpanDecorator {

    @Override
    public String getComponent() {
        return "rest";
    }

    @Override
    public String getComponentClassName() {
        return "org.apache.camel.component.rest.RestComponent";
    }

    @Override
    public String getOperationName(Exchange exchange, Endpoint endpoint) {
        return getPath(endpoint.getEndpointUri());
    }

    @Override
    public void beforeTracingEvent(Span span, Exchange exchange, Endpoint endpoint) {
        super.beforeTracingEvent(span, exchange, endpoint);

        getParameters(getPath(endpoint.getEndpointUri())).forEach(param -> {
            String val = exchange.getIn().getHeader(param, String.class);
            if (val != null) {
                span.setTag(param, val);
            }
        });

    }

    protected static String getPath(String uri) {
        // Obtain the 'path' part of the URI format: rest://method:path[:uriTemplate]?[options]
        String path = null;
        int index = uri.indexOf(':');
        if (index != -1) {
            index = uri.indexOf(':', index + 1);
            if (index != -1) {
                path = uri.substring(index + 1);
                index = path.indexOf('?');
                if (index != -1) {
                    path = path.substring(0, index);
                }
                path = path.replace(":", "");
                path = URLDecoder.decode(path, StandardCharsets.UTF_8);
            }
        }
        return path;
    }

    protected static List<String> getParameters(String path) {
        List<String> parameters = null;

        int startIndex = path.indexOf('{');
        while (startIndex != -1) {
            int endIndex = path.indexOf('}', startIndex);
            if (endIndex != -1) {
                if (parameters == null) {
                    parameters = new ArrayList<>();
                }
                parameters.add(path.substring(startIndex + 1, endIndex));
                startIndex = path.indexOf('{', endIndex);
            } else {
                // Break out of loop as no valid end token
                startIndex = -1;
            }
        }

        return parameters == null ? Collections.emptyList() : parameters;
    }
}
