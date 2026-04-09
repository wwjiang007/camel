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
package org.apache.camel.dsl.jbang.core.commands.action;

import java.awt.Color;
import java.util.List;

import org.apache.camel.dsl.jbang.core.commands.action.CamelRouteDiagramAction.DiagramColors;
import org.apache.camel.dsl.jbang.core.commands.action.CamelRouteDiagramAction.NodeInfo;
import org.apache.camel.dsl.jbang.core.commands.action.CamelRouteDiagramAction.TreeNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CamelRouteDiagramActionTest {

    // ------- buildTree tests -------

    @Test
    void testBuildTreeEmpty() {
        assertNull(CamelRouteDiagramAction.buildTree(List.of()));
    }

    @Test
    void testBuildTreeSingleNode() {
        List<NodeInfo> nodes = List.of(node("from", "timer:tick", 0));
        TreeNode root = CamelRouteDiagramAction.buildTree(nodes);

        assertNotNull(root);
        assertEquals("from", root.info.type);
        assertTrue(root.children.isEmpty());
    }

    @Test
    void testBuildTreeSequential() {
        List<NodeInfo> nodes = List.of(
                node("from", "timer:tick", 0),
                node("to", "log:a", 1),
                node("to", "log:b", 1));
        TreeNode root = CamelRouteDiagramAction.buildTree(nodes);

        assertNotNull(root);
        assertEquals(2, root.children.size());
        assertEquals("log:a", root.children.get(0).info.code);
        assertEquals("log:b", root.children.get(1).info.code);
    }

    @Test
    void testBuildTreeBranching() {
        // from -> choice -> when (level 2) -> to (level 3)
        //                -> otherwise (level 2) -> to (level 3)
        List<NodeInfo> nodes = List.of(
                node("from", "timer:tick", 0),
                node("choice", "choice()", 1),
                node("when", "when(simple(...))", 2),
                node("to", "log:a", 3),
                node("otherwise", "otherwise()", 2),
                node("to", "log:b", 3));
        TreeNode root = CamelRouteDiagramAction.buildTree(nodes);

        assertNotNull(root);
        assertEquals(1, root.children.size()); // choice
        TreeNode choice = root.children.get(0);
        assertEquals("choice", choice.info.type);
        assertEquals(2, choice.children.size()); // when + otherwise
        assertEquals("when", choice.children.get(0).info.type);
        assertEquals("otherwise", choice.children.get(1).info.type);
        assertEquals(1, choice.children.get(0).children.size()); // to under when
        assertEquals(1, choice.children.get(1).children.size()); // to under otherwise
    }

    @Test
    void testBuildTreeWalkUpMultipleLevels() {
        // from (0) -> choice (1) -> when (2) -> to (3)
        //          -> to (1)  <-- walks back up from level 3 to level 1
        List<NodeInfo> nodes = List.of(
                node("from", "timer:tick", 0),
                node("choice", "choice()", 1),
                node("when", "when(...)", 2),
                node("to", "log:deep", 3),
                node("to", "log:after-choice", 1));
        TreeNode root = CamelRouteDiagramAction.buildTree(nodes);

        assertNotNull(root);
        assertEquals(2, root.children.size()); // choice + to
        assertEquals("choice", root.children.get(0).info.type);
        assertEquals("log:after-choice", root.children.get(1).info.code);
    }

    // ------- DiagramColors tests -------

    @Test
    void testColorPresetDark() {
        DiagramColors colors = DiagramColors.parse("dark");
        assertNotNull(colors.bg);
        assertNotNull(colors.text);
        assertNotNull(colors.nodeFrom);
        assertEquals(new Color(0x1e1e1e), colors.bg);
    }

    @Test
    void testColorPresetLight() {
        DiagramColors colors = DiagramColors.parse("light");
        assertEquals(new Color(0xf5f5f5), colors.bg);
        assertEquals(new Color(0x1e1e1e), colors.text);
    }

    @Test
    void testColorPresetTransparent() {
        DiagramColors colors = DiagramColors.parse("transparent");
        assertNull(colors.bg); // transparent = null bg
        assertNotNull(colors.text);
    }

    @Test
    void testColorCustomOverride() {
        DiagramColors colors = DiagramColors.parse("bg=#ff0000:from=#00ff00");
        assertEquals(new Color(0xff0000), colors.bg);
        assertEquals(new Color(0x00ff00), colors.nodeFrom);
        // Other colors should fall back to dark defaults
        assertNotNull(colors.text);
        assertNotNull(colors.nodeTo);
    }

    @Test
    void testColorInvalidHexFallsBack() {
        DiagramColors colors = DiagramColors.parse("bg=notacolor");
        assertNull(colors.bg); // invalid hex returns null
    }

    // ------- truncateLabel tests -------

    @Test
    void testTruncateLabelNull() {
        assertEquals("", CamelRouteDiagramAction.truncateLabel(null));
    }

    @Test
    void testTruncateLabelShort() {
        assertEquals("log:hello", CamelRouteDiagramAction.truncateLabel("log:hello"));
    }

    @Test
    void testTruncateLabelLeadingDot() {
        assertEquals("to(\"log:a\")", CamelRouteDiagramAction.truncateLabel(".to(\"log:a\")"));
    }

    @Test
    void testTruncateLabelLong() {
        String longLabel = "to(\"http://very-long-endpoint-url-that-exceeds-forty-characters\")";
        String result = CamelRouteDiagramAction.truncateLabel(longLabel);
        assertTrue(result.length() <= 40);
        assertTrue(result.endsWith("..."));
    }

    @Test
    void testTruncateLabelExactly40() {
        String label40 = "a".repeat(40);
        assertEquals(label40, CamelRouteDiagramAction.truncateLabel(label40));
    }

    // ------- helpers -------

    private static NodeInfo node(String type, String code, int level) {
        NodeInfo n = new NodeInfo();
        n.type = type;
        n.code = code;
        n.level = level;
        return n;
    }
}
