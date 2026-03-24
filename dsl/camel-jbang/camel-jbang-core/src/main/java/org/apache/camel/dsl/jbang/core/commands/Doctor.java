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
package org.apache.camel.dsl.jbang.core.commands;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;

import org.apache.camel.catalog.CamelCatalog;
import org.apache.camel.catalog.DefaultCamelCatalog;
import org.apache.camel.dsl.jbang.core.common.VersionHelper;
import picocli.CommandLine.Command;

@Command(name = "doctor", description = "Checks the environment and reports potential issues",
         sortOptions = false, showDefaultValues = true)
public class Doctor extends CamelCommand {

    public Doctor(CamelJBangMain main) {
        super(main);
    }

    @Override
    public Integer doCall() throws Exception {
        printer().println("Camel JBang Doctor");
        printer().println("==================");
        printer().println();

        checkJava();
        checkJBang();
        checkCamelVersion();
        checkMavenCentral();
        checkDocker();
        checkCommonPorts();
        checkDiskSpace();

        return 0;
    }

    private void checkJava() {
        String version = System.getProperty("java.version");
        String vendor = System.getProperty("java.vendor", "");
        int major = Runtime.version().feature();
        String status = major >= 21 ? "OK" : "WARN (21+ required)";
        printer().printf("  Java:        %s (%s) [%s]%n", version, vendor, status);
    }

    private void checkJBang() {
        String version = VersionHelper.getJBangVersion();
        if (version != null) {
            printer().printf("  JBang:       %s (OK)%n", version);
        } else {
            printer().printf("  JBang:       not detected%n");
        }
    }

    private void checkCamelVersion() {
        CamelCatalog catalog = new DefaultCamelCatalog();
        String version = catalog.getCatalogVersion();
        printer().printf("  Camel:       %s%n", version);
    }

    private void checkMavenCentral() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create("https://repo1.maven.org/maven2/")
                    .toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("HEAD");
            int code = conn.getResponseCode();
            conn.disconnect();
            printer().printf("  Maven:       %s%n", code == 200 ? "reachable (OK)" : "returned " + code);
        } catch (Exception e) {
            printer().printf("  Maven:       unreachable (%s)%n", e.getMessage());
        }
    }

    private void checkDocker() {
        try {
            Process p = new ProcessBuilder("docker", "info")
                    .redirectErrorStream(true)
                    .start();
            // drain output to prevent blocking
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            int exit = p.waitFor();
            printer().printf("  Docker:      %s%n", exit == 0 ? "running (OK)" : "not running");
        } catch (Exception e) {
            printer().printf("  Docker:      not found%n");
        }
    }

    private void checkCommonPorts() {
        StringBuilder conflicts = new StringBuilder();
        for (int port : new int[] { 8080, 8443, 9090 }) {
            if (isPortInUse(port)) {
                if (!conflicts.isEmpty()) {
                    conflicts.append(", ");
                }
                conflicts.append(port);
            }
        }
        if (!conflicts.isEmpty()) {
            printer().printf("  Ports:       in use: %s%n", conflicts);
        } else {
            printer().printf("  Ports:       8080, 8443, 9090 free (OK)%n");
        }
    }

    private static boolean isPortInUse(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private void checkDiskSpace() {
        File tmpDir = new File(System.getProperty("java.io.tmpdir"));
        long free = tmpDir.getFreeSpace();
        long mb = free / (1024 * 1024);
        String status = mb > 500 ? "OK" : "LOW";
        printer().printf("  Disk space:  %d MB free in temp (%s)%n", mb, status);
    }
}
