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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.imageio.ImageIO;

import org.apache.camel.dsl.jbang.core.commands.CamelJBangMain;
import org.apache.camel.dsl.jbang.core.common.PathUtils;
import org.apache.camel.support.PatternHelper;
import org.apache.camel.util.json.JsonArray;
import org.apache.camel.util.json.JsonObject;
import org.apache.camel.util.json.Jsoner;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.TerminalGraphics;
import org.jline.terminal.impl.TerminalGraphicsManager;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "route-diagram", description = "Display Camel route diagram in the terminal", sortOptions = false,
         showDefaultValues = true)
public class CamelRouteDiagramAction extends ActionBaseCommand {

    // Render at 2x for crisp text on terminal image protocols
    private static final int SCALE = 2;
    private static final int NODE_WIDTH = 180 * SCALE;
    private static final int NODE_HEIGHT = 32 * SCALE;
    private static final int H_GAP = 30 * SCALE;
    private static final int V_GAP = 40 * SCALE;
    private static final int PADDING = 30 * SCALE;
    private static final int ARC = 14 * SCALE;
    private static final int FONT_SIZE_LABEL = 13 * SCALE;
    private static final int FONT_SIZE_NODE = 12 * SCALE;
    private static final int ARROW_SIZE = 6 * SCALE;
    private static final int MERGE_DOT = 5 * SCALE;

    // Color scheme keys: bg, text, arrow, label, from, to, eip, choice, default
    // Format: "key=#rrggbb:key=#rrggbb:..." or a preset name (dark, light, transparent)
    // Use bg= (empty) for transparent background
    private static final String DARK_COLORS
            = "bg=#1e1e1e:text=#ffffff:arrow=#b4b4b4:label=#c8c8c8:from=#2e7d32:to=#1565c0:eip=#9c27b0:choice=#e65100:default=#455a64";
    private static final String LIGHT_COLORS
            = "bg=#f5f5f5:text=#1e1e1e:arrow=#646464:label=#505050:from=#388e3c:to=#1976d2:eip=#ab47bc:choice=#f57c00:default=#78909c";
    private static final String TRANSPARENT_COLORS
            = "bg=:text=#ffffff:arrow=#b4b4b4:label=#c8c8c8:from=#2e7d32:to=#1565c0:eip=#9c27b0:choice=#e65100:default=#455a64";

    private static final Map<String, String> COLOR_PRESETS = Map.of(
            "dark", DARK_COLORS,
            "light", LIGHT_COLORS,
            "transparent", TRANSPARENT_COLORS);

    static class DiagramColors {
        Color bg, text, arrow, routeLabel;
        Color nodeFrom, nodeTo, nodeEip, nodeChoice, nodeDefault;

        static DiagramColors parse(String spec) {
            // Resolve preset aliases
            String resolved = COLOR_PRESETS.getOrDefault(spec, spec);
            Map<String, String> map = new HashMap<>();
            // Start with dark defaults
            for (String entry : DARK_COLORS.split(":")) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    map.put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
            // Override with user values
            for (String entry : resolved.split(":")) {
                int eq = entry.indexOf('=');
                if (eq > 0) {
                    map.put(entry.substring(0, eq), entry.substring(eq + 1));
                }
            }
            DiagramColors c = new DiagramColors();
            c.bg = parseColor(map.get("bg"));
            c.text = parseColor(map.getOrDefault("text", "#ffffff"));
            c.arrow = parseColor(map.getOrDefault("arrow", "#b4b4b4"));
            c.routeLabel = parseColor(map.getOrDefault("label", "#c8c8c8"));
            c.nodeFrom = parseColor(map.getOrDefault("from", "#2e7d32"));
            c.nodeTo = parseColor(map.getOrDefault("to", "#1565c0"));
            c.nodeEip = parseColor(map.getOrDefault("eip", "#9c27b0"));
            c.nodeChoice = parseColor(map.getOrDefault("choice", "#e65100"));
            c.nodeDefault = parseColor(map.getOrDefault("default", "#455a64"));
            return c;
        }

        private static Color parseColor(String hex) {
            if (hex == null || hex.isEmpty()) {
                return null; // transparent
            }
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            try {
                return new Color(Integer.parseInt(hex, 16));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    // EIP types that create horizontal branches (their direct children are laid out side by side)
    private static final Set<String> BRANCHING_EIPS = Set.of(
            "choice", "multicast", "doTry", "loadBalance", "recipientList");

    @CommandLine.Parameters(description = "Name or pid of running Camel integration", arity = "0..1")
    String name = "*";

    @CommandLine.Option(names = { "--filter" },
                        description = "Filter route by filename or route id")
    String filter;

    @CommandLine.Option(names = { "--width" },
                        description = "Image width in pixels", defaultValue = "0")
    int width;

    @CommandLine.Option(names = { "--output" },
                        description = "Save diagram to a PNG file instead of displaying in terminal")
    String output;

    @CommandLine.Option(names = { "--theme", "--colors" },
                        description = "Color theme preset (dark, light, transparent) or custom colors "
                                      + "(e.g. bg=#1e1e1e:from=#2e7d32:to=#1565c0). Use bg= for transparent.",
                        defaultValue = "dark")
    String theme;

    private DiagramColors colors;

    public CamelRouteDiagramAction(CamelJBangMain main) {
        super(main);
    }

    @Override
    public Integer doCall() throws Exception {
        String colorSpec = System.getenv("DIAGRAM_COLORS");
        colors = DiagramColors.parse(colorSpec != null ? colorSpec : theme);

        List<Long> pids = findPids(name);
        if (pids.isEmpty()) {
            return 1;
        } else if (pids.size() > 1) {
            printer().println("Name or pid " + name + " matches " + pids.size()
                              + " running Camel integrations. Specify a name or PID that matches exactly one.");
            return 1;
        }

        long pid = pids.get(0);

        // Fetch route structure from the running Camel integration
        Path outputFile = prepareAction(Long.toString(pid), "route-structure", root -> {
            root.put("filter", "*");
            root.put("brief", false);
        });

        try {
            JsonObject jo = getJsonObject(outputFile);
            if (jo == null) {
                printer().println("Response from running Camel with PID " + pid + " not received within 5 seconds");
                return 1;
            }

            List<RouteInfo> routes = parseRoutes(jo);
            if (routes.isEmpty()) {
                printer().println("No routes found");
                return 0;
            }

            // Filter routes if needed
            if (filter != null) {
                routes.removeIf(r -> (r.routeId == null || !PatternHelper.matchPattern(r.routeId, filter))
                        && (r.source == null || !PatternHelper.matchPattern(r.source, filter)));
            }

            if (routes.isEmpty()) {
                printer().println("No routes match filter: " + filter);
                return 0;
            }

            // Render diagram
            BufferedImage image = renderDiagram(routes);

            if (output != null) {
                // Save to file
                File file = new File(output);
                ImageIO.write(image, "PNG", file);
                printer().println("Diagram saved to: " + file.getAbsolutePath());
            } else {
                // Display using JLine terminal graphics
                try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
                    Optional<TerminalGraphics> protocol = TerminalGraphicsManager.getBestProtocol(terminal);
                    if (protocol.isPresent()) {
                        TerminalGraphics.ImageOptions opts = new TerminalGraphics.ImageOptions()
                                .preserveAspectRatio(true);
                        if (width > 0) {
                            opts.width(width);
                        }
                        protocol.get().displayImage(terminal, image, opts);
                        terminal.writer().println();
                        terminal.flush();
                    } else {
                        printer().println("Terminal does not support graphics protocols (Kitty, iTerm2, or Sixel).");
                        printer().println(
                                "Try running in a supported terminal: Kitty, iTerm2, WezTerm, Ghostty, or VS Code.");
                        printTextDiagram(routes);
                    }
                }
            }

            return 0;
        } finally {
            PathUtils.deleteFile(outputFile);
        }
    }

    // ------- Parsing -------

    private List<RouteInfo> parseRoutes(JsonObject jo) {
        List<RouteInfo> routes = new ArrayList<>();
        JsonArray arr = (JsonArray) jo.get("routes");
        if (arr == null) {
            return routes;
        }

        for (int i = 0; i < arr.size(); i++) {
            JsonObject o = (JsonObject) arr.get(i);
            RouteInfo route = new RouteInfo();
            route.routeId = o.getString("routeId");
            route.source = CamelRouteStructureAction.extractSourceName(o.getString("source"));

            List<JsonObject> lines = o.getCollection("code");
            if (lines != null) {
                for (JsonObject line : lines) {
                    NodeInfo node = new NodeInfo();
                    node.type = line.getString("type");
                    node.code = Jsoner.unescape(line.getString("code"));
                    node.level = line.getInteger("level");
                    route.nodes.add(node);
                }
            }
            routes.add(route);
        }
        return routes;
    }

    // ------- Tree building -------

    /**
     * Build a tree from the flat node list using level to determine parent-child relationships.
     */
    static TreeNode buildTree(List<NodeInfo> nodes) {
        if (nodes.isEmpty()) {
            return null;
        }
        TreeNode root = new TreeNode(nodes.get(0));
        TreeNode current = root;

        for (int i = 1; i < nodes.size(); i++) {
            NodeInfo ni = nodes.get(i);
            TreeNode tn = new TreeNode(ni);

            if (ni.level > current.info.level) {
                // Child of current
                current.children.add(tn);
                tn.parent = current;
            } else if (ni.level == current.info.level) {
                // Sibling of current
                TreeNode parent = current.parent;
                if (parent != null) {
                    parent.children.add(tn);
                    tn.parent = parent;
                } else {
                    root.children.add(tn);
                    tn.parent = root;
                }
            } else {
                // Walk up to find the right parent
                TreeNode ancestor = current.parent;
                while (ancestor != null && ancestor.info.level >= ni.level) {
                    ancestor = ancestor.parent;
                }
                if (ancestor != null) {
                    ancestor.children.add(tn);
                    tn.parent = ancestor;
                } else {
                    root.children.add(tn);
                    tn.parent = root;
                }
            }
            current = tn;
        }
        return root;
    }

    // ------- Layout (Hawtio-style) -------

    private LayoutRoute layoutRoute(RouteInfo route, int startY) {
        LayoutRoute lr = new LayoutRoute();
        lr.routeId = route.routeId;
        lr.source = route.source;
        lr.labelY = startY;

        TreeNode tree = buildTree(route.nodes);
        if (tree == null) {
            lr.maxX = PADDING + NODE_WIDTH;
            lr.maxY = startY + 24 * SCALE;
            return lr;
        }

        computeSubtreeWidth(tree);
        assignPositions(tree, PADDING, startY + 24 * SCALE, tree.subtreeWidth, lr);

        int maxX = 0;
        for (LayoutNode ln : lr.nodes) {
            maxX = Math.max(maxX, ln.x + NODE_WIDTH);
        }
        lr.maxX = maxX + PADDING;

        return lr;
    }

    /**
     * Compute the width each subtree needs (in pixels) for horizontal layout.
     */
    private int computeSubtreeWidth(TreeNode node) {
        if (node.children.isEmpty()) {
            node.subtreeWidth = NODE_WIDTH;
            return node.subtreeWidth;
        }

        if (isBranchingEip(node.info.type)) {
            // Branches are laid out side by side
            int totalWidth = 0;
            for (int i = 0; i < node.children.size(); i++) {
                if (i > 0) {
                    totalWidth += H_GAP;
                }
                totalWidth += computeSubtreeWidth(node.children.get(i));
            }
            node.subtreeWidth = Math.max(NODE_WIDTH, totalWidth);
        } else {
            // Sequential: the width is the max of all children
            int maxChildWidth = NODE_WIDTH;
            for (TreeNode child : node.children) {
                maxChildWidth = Math.max(maxChildWidth, computeSubtreeWidth(child));
            }
            node.subtreeWidth = maxChildWidth;
        }
        return node.subtreeWidth;
    }

    /**
     * Assign x,y positions to each node in the tree.
     *
     * @param parentWidth the available width from the parent's subtree (used to center sequential children)
     */
    private void assignPositions(TreeNode node, int x, int y, int parentWidth, LayoutRoute lr) {
        int availableWidth = Math.max(node.subtreeWidth, parentWidth);
        int nodeX = x + (availableWidth - NODE_WIDTH) / 2;

        LayoutNode ln = new LayoutNode();
        ln.label = truncateLabel(node.info.code);
        ln.type = node.info.type;
        ln.x = nodeX;
        ln.y = y;
        ln.treeNode = node;
        node.layoutNode = ln;
        lr.nodes.add(ln);

        // Connect to parent
        if (node.parent != null && node.parent.layoutNode != null) {
            TreeNode parentNode = node.parent;
            if (!isBranchingEip(parentNode.info.type)) {
                // Sequential: connect to previous sibling or parent
                int myIndex = parentNode.children.indexOf(node);
                if (myIndex > 0) {
                    TreeNode prevSibling = parentNode.children.get(myIndex - 1);
                    if (isBranchingEip(prevSibling.info.type)) {
                        // Previous sibling was a branching EIP — connect from its merge point
                        ln.connectFromMerge = true;
                        ln.mergeY = findMaxY(prevSibling) + V_GAP / 2;
                        ln.mergeCx = prevSibling.layoutNode.x + NODE_WIDTH / 2;
                        ln.parentNode = prevSibling.layoutNode;
                    } else {
                        ln.parentNode = findLastLayoutNode(prevSibling);
                    }
                } else {
                    ln.parentNode = parentNode.layoutNode;
                }
            } else {
                // Branching: connect directly to parent
                ln.parentNode = parentNode.layoutNode;
            }
        }

        lr.maxY = Math.max(lr.maxY, y + NODE_HEIGHT + V_GAP);

        if (node.children.isEmpty()) {
            return;
        }

        int childY = y + NODE_HEIGHT + V_GAP;

        if (isBranchingEip(node.info.type)) {
            // Lay out children side by side horizontally
            int childX = x + (availableWidth - node.subtreeWidth) / 2;
            for (TreeNode child : node.children) {
                assignPositions(child, childX, childY, child.subtreeWidth, lr);
                childX += child.subtreeWidth + H_GAP;
            }
        } else {
            // Sequential: stack children vertically
            int curY = childY;
            for (int i = 0; i < node.children.size(); i++) {
                TreeNode child = node.children.get(i);
                assignPositions(child, x, curY, availableWidth, lr);
                curY = findMaxY(child) + V_GAP;
                // Extra gap after branching children for merge line
                if (isBranchingEip(child.info.type) && i < node.children.size() - 1) {
                    curY += V_GAP;
                }
            }
        }
    }

    /**
     * Find the last (deepest) layout node in a sequential subtree. For branching EIPs, returns the node itself
     * (branches merge back at the parent).
     */
    private LayoutNode findLastLayoutNode(TreeNode node) {
        if (node.children.isEmpty()) {
            return node.layoutNode;
        }
        if (isBranchingEip(node.info.type)) {
            return node.layoutNode;
        }
        return findLastLayoutNode(node.children.get(node.children.size() - 1));
    }

    private int findMaxY(TreeNode node) {
        int maxY = node.layoutNode != null ? node.layoutNode.y + NODE_HEIGHT : 0;
        for (TreeNode child : node.children) {
            maxY = Math.max(maxY, findMaxY(child));
        }
        return maxY;
    }

    private boolean isBranchingEip(String type) {
        return type != null && BRANCHING_EIPS.contains(type);
    }

    // ------- Rendering -------

    private BufferedImage renderDiagram(List<RouteInfo> routes) {
        List<LayoutRoute> layoutRoutes = new ArrayList<>();
        int currentY = PADDING;

        for (RouteInfo route : routes) {
            LayoutRoute lr = layoutRoute(route, currentY);
            layoutRoutes.add(lr);
            currentY = lr.maxY + V_GAP;
        }

        int imgWidth = layoutRoutes.stream().mapToInt(lr -> lr.maxX).max().orElse(400) + PADDING;
        int imgHeight = currentY + PADDING;

        int imageType = colors.bg == null ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(imgWidth, imgHeight, imageType);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        if (colors.bg != null) {
            g.setColor(colors.bg);
            g.fillRect(0, 0, imgWidth, imgHeight);
        }

        for (LayoutRoute lr : layoutRoutes) {
            drawRoute(g, lr);
        }

        g.dispose();
        return image;
    }

    private void drawRoute(Graphics2D g, LayoutRoute lr) {
        // Route label
        g.setColor(colors.routeLabel);
        g.setFont(new Font("SansSerif", Font.BOLD, FONT_SIZE_LABEL));
        String label = lr.routeId;
        if (lr.source != null && !lr.source.isEmpty()) {
            label += " (" + lr.source + ")";
        }
        g.drawString(label, PADDING, lr.labelY + 14 * SCALE);

        g.setStroke(new BasicStroke(1.5f * SCALE));

        // Draw merge lines for branching nodes
        for (LayoutNode ln : lr.nodes) {
            if (isBranchingEip(ln.type) && ln.treeNode != null && !ln.treeNode.children.isEmpty()) {
                drawMergeLines(g, ln);
            }
        }

        // Draw arrows
        for (LayoutNode ln : lr.nodes) {
            if (ln.parentNode != null) {
                if (ln.connectFromMerge) {
                    drawArrowFromMerge(g, ln);
                } else {
                    drawArrow(g, ln.parentNode, ln);
                }
            }
        }

        // Draw nodes on top
        for (LayoutNode ln : lr.nodes) {
            drawNode(g, ln);
        }
    }

    /**
     * Draw merge lines below all branches of a branching EIP: vertical lines from each branch's last node down to a
     * horizontal merge line, with a dot at center.
     */
    private void drawMergeLines(Graphics2D g, LayoutNode branchingNode) {
        TreeNode tn = branchingNode.treeNode;
        if (tn.children.isEmpty()) {
            return;
        }

        // Only draw merge if there's a next sequential sibling
        TreeNode parentNode = tn.parent;
        if (parentNode == null) {
            return;
        }
        int myIndex = parentNode.children.indexOf(tn);
        if (myIndex < 0 || myIndex >= parentNode.children.size() - 1) {
            return;
        }

        int branchesMaxY = findMaxY(tn);
        int mergeY = branchesMaxY + V_GAP / 2;

        g.setColor(colors.arrow);
        g.setStroke(new BasicStroke(1.5f * SCALE));

        // Draw vertical lines from each branch's last node down to merge Y
        int minCx = Integer.MAX_VALUE;
        int maxCx = Integer.MIN_VALUE;
        for (TreeNode child : tn.children) {
            LayoutNode lastNode = findLastLayoutNode(child);
            if (lastNode != null) {
                int cx = lastNode.x + NODE_WIDTH / 2;
                int by = lastNode.y + NODE_HEIGHT;
                g.drawLine(cx, by, cx, mergeY);
                minCx = Math.min(minCx, cx);
                maxCx = Math.max(maxCx, cx);
            }
        }

        // Draw horizontal merge line
        if (minCx < maxCx) {
            g.drawLine(minCx, mergeY, maxCx, mergeY);
        }

        // Draw merge dot at center
        int mergeCx = branchingNode.x + NODE_WIDTH / 2;
        g.fillOval(mergeCx - MERGE_DOT, mergeY - MERGE_DOT, MERGE_DOT * 2, MERGE_DOT * 2);
    }

    private void drawArrowFromMerge(Graphics2D g, LayoutNode to) {
        g.setColor(colors.arrow);
        g.setStroke(new BasicStroke(1.5f * SCALE));

        int toCx = to.x + NODE_WIDTH / 2;
        int toTy = to.y;
        int mergeCx = to.mergeCx;
        int mergeY = to.mergeY;

        if (mergeCx == toCx) {
            g.drawLine(mergeCx, mergeY, toCx, toTy);
        } else {
            int midY = mergeY + (toTy - mergeY) / 2;
            g.drawLine(mergeCx, mergeY, mergeCx, midY);
            g.drawLine(mergeCx, midY, toCx, midY);
            g.drawLine(toCx, midY, toCx, toTy);
        }
        drawArrowHead(g, toCx, toTy);
    }

    private void drawNode(Graphics2D g, LayoutNode node) {
        Color color = getNodeColor(node.type);

        g.setColor(color);
        g.fillRoundRect(node.x, node.y, NODE_WIDTH, NODE_HEIGHT, ARC, ARC);

        g.setColor(color.brighter());
        g.setStroke(new BasicStroke(1.0f * SCALE));
        g.drawRoundRect(node.x, node.y, NODE_WIDTH, NODE_HEIGHT, ARC, ARC);

        g.setColor(colors.text);
        g.setFont(new Font("SansSerif", Font.PLAIN, FONT_SIZE_NODE));
        FontMetrics fm = g.getFontMetrics();
        String text = node.label;
        while (fm.stringWidth(text) > NODE_WIDTH - 16 * SCALE && text.length() > 3) {
            text = text.substring(0, text.length() - 4) + "...";
        }
        int textX = node.x + (NODE_WIDTH - fm.stringWidth(text)) / 2;
        int textY = node.y + (NODE_HEIGHT + fm.getAscent() - fm.getDescent()) / 2;
        g.drawString(text, textX, textY);
    }

    private void drawArrow(Graphics2D g, LayoutNode from, LayoutNode to) {
        g.setColor(colors.arrow);
        g.setStroke(new BasicStroke(1.5f * SCALE));

        int fromCx = from.x + NODE_WIDTH / 2;
        int fromBy = from.y + NODE_HEIGHT;
        int toCx = to.x + NODE_WIDTH / 2;
        int toTy = to.y;

        if (fromCx == toCx) {
            g.drawLine(fromCx, fromBy, toCx, toTy);
        } else {
            int midY = fromBy + V_GAP / 2;
            g.drawLine(fromCx, fromBy, fromCx, midY);
            g.drawLine(fromCx, midY, toCx, midY);
            g.drawLine(toCx, midY, toCx, toTy);
        }
        drawArrowHead(g, toCx, toTy);
    }

    private void drawArrowHead(Graphics2D g, int x, int y) {
        int[] xPoints = { x - ARROW_SIZE, x, x + ARROW_SIZE };
        int[] yPoints = { y - ARROW_SIZE, y, y - ARROW_SIZE };
        g.fillPolygon(xPoints, yPoints, 3);
    }

    private Color getNodeColor(String type) {
        if (type == null) {
            return colors.nodeDefault;
        }
        return switch (type) {
            case "from" -> colors.nodeFrom;
            case "to", "toD", "wireTap", "enrich", "pollEnrich" -> colors.nodeTo;
            case "choice", "when", "otherwise" -> colors.nodeChoice;
            case "filter", "split", "aggregate", "multicast", "recipientList",
                    "routingSlip", "dynamicRouter", "loadBalance",
                    "circuitBreaker", "saga", "doTry", "doCatch", "doFinally",
                    "onException", "onCompletion", "intercept",
                    "loop", "resequence", "throttle" ->
                colors.nodeEip;
            default -> colors.nodeDefault;
        };
    }

    static String truncateLabel(String code) {
        if (code == null) {
            return "";
        }
        code = code.replaceFirst("^\\.", "");
        if (code.length() > 40) {
            code = code.substring(0, 37) + "...";
        }
        return code;
    }

    private void printTextDiagram(List<RouteInfo> routes) {
        for (RouteInfo route : routes) {
            printer().println();
            printer().printf("Route: %s (%s)%n", route.routeId, route.source);
            printer().println("---");
            for (NodeInfo node : route.nodes) {
                String indent = "  ".repeat(node.level);
                String prefix = node.level == 0 ? "[*] " : " -> ";
                printer().printf("%s%s%s%n", indent, prefix, node.code);
            }
            printer().println();
        }
    }

    // ------- Data classes -------

    private static class RouteInfo {
        String routeId;
        String source;
        List<NodeInfo> nodes = new ArrayList<>();
    }

    static class NodeInfo {
        String type;
        String code;
        int level;
    }

    static class TreeNode {
        final NodeInfo info;
        TreeNode parent;
        List<TreeNode> children = new ArrayList<>();
        int subtreeWidth;
        LayoutNode layoutNode;

        TreeNode(NodeInfo info) {
            this.info = info;
        }
    }

    private static class LayoutRoute {
        String routeId;
        String source;
        int labelY;
        int maxX;
        int maxY;
        List<LayoutNode> nodes = new ArrayList<>();
    }

    private static class LayoutNode {
        String label;
        String type;
        int x;
        int y;
        LayoutNode parentNode;
        TreeNode treeNode;
        boolean connectFromMerge;
        int mergeY;
        int mergeCx;
    }
}
