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
package org.apache.camel.component.leveldb;

import java.io.File;
import java.util.Date;

import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.test.junit5.params.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.apache.camel.test.junit5.TestSupport.deleteDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisabledOnOs({ OS.AIX, OS.OTHER })
public class LevelDBExchangeSerializationTest extends LevelDBTestSupport {

    private LevelDBFile levelDBFile;

    @Override
    public void doPostSetup() {
        deleteDirectory("target/data");
        File file = new File("target/data/leveldb.dat");
        levelDBFile = new LevelDBFile();
        levelDBFile.setFile(file);
        levelDBFile.start();
    }

    @Override
    public void doPostTearDown() {
        levelDBFile.stop();
    }

    @Test
    public void testExchangeSerialization() {
        LevelDBAggregationRepository repo = getRepo();
        repo.setLevelDBFile(levelDBFile);
        repo.setRepositoryName("repo1");

        Exchange exchange = new DefaultExchange(context);
        exchange.getIn().setBody("Hello World");
        exchange.getIn().setHeader("name", "Claus");
        exchange.getIn().setHeader("number", 123);
        exchange.setProperty("quote", "Camel rocks");

        Date now = new Date();
        exchange.getIn().setHeader("date", now);

        repo.add(context, "foo", exchange);

        Exchange actual = repo.get(context, "foo");
        assertEquals("Hello World", actual.getIn().getBody());
        assertEquals("Claus", actual.getIn().getHeader("name"));
        assertEquals(123, actual.getIn().getHeader("number"));
        Date date = actual.getIn().getHeader("date", Date.class);
        assertNotNull(date);
        assertEquals(now.getTime(), date.getTime());
        // we do not serialize properties to avoid storing all kind of not needed information
        assertNull(actual.getProperty("quote"));
        assertSame(context, actual.getContext());

        // change something
        exchange.getIn().setBody("Bye World");
        exchange.getIn().setHeader("name", "Hiram");
        exchange.getIn().removeHeader("date");

        repo.add(context, "foo", exchange);

        actual = repo.get(context, "foo");
        assertEquals("Bye World", actual.getIn().getBody());
        assertEquals("Hiram", actual.getIn().getHeader("name"));
        assertEquals(123, actual.getIn().getHeader("number"));
        date = actual.getIn().getHeader("date", Date.class);
        assertNull(date);
        assertSame(context, actual.getContext());
    }

}
