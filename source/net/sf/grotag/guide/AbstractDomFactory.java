package net.sf.grotag.guide;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import net.sf.grotag.common.AmigaTools;
import net.sf.grotag.common.Tools;
import net.sf.grotag.parse.AbstractItem;
import net.sf.grotag.parse.AbstractTextItem;
import net.sf.grotag.parse.CommandItem;
import net.sf.grotag.parse.NewLineItem;
import net.sf.grotag.parse.SpaceItem;
import net.sf.grotag.parse.Tag;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

/**
 * Abstract factory to create those parts of the DOM that are the same for
 * DocBook XML and HTML.
 * 
 * @author Thomas Aglassinger
 */
abstract public class AbstractDomFactory {
    private enum NodeParserState {
        BEFORE_NODE, INSIDE_NODE, AFTER_NODE;
    }

    private GuidePile pile;
    private Document dom;
    private Logger log;
    private Tools tools;
    private Map<String, String> agNodeToDbNodeMap;
    private AmigaTools amigaTools;

    protected AbstractDomFactory(GuidePile newPile) throws ParserConfigurationException {
        assert newPile != null;

        log = Logger.getLogger(AbstractDomFactory.class.getName());
        tools = Tools.getInstance();
        amigaTools = AmigaTools.getInstance();

        pile = newPile;

        DocumentBuilderFactory domBuilderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder domBuilder = domBuilderFactory.newDocumentBuilder();
        dom = domBuilder.newDocument();

        // Map the Amigaguide node names to DocBook id's that conform to the
        // NCName definition.
        agNodeToDbNodeMap = new HashMap<String, String>();
        int nodeCounter = 1;
        for (Guide guide : pile.getGuides()) {
            for (NodeInfo nodeInfo : guide.getNodeInfos()) {
                String agNodeName = nodeKey(guide, nodeInfo);
                String dbNodeName = "n" + nodeCounter;

                log.log(Level.INFO, "add mapped node {0} from {1}", new Object[] { dbNodeName, agNodeName });

                assert !agNodeToDbNodeMap.containsKey(agNodeName) : "duplicate agNode: " + tools.sourced(agNodeName);
                assert !agNodeToDbNodeMap.containsValue(dbNodeName) : "duplicate dbNode: " + tools.sourced(dbNodeName);

                agNodeToDbNodeMap.put(agNodeName, dbNodeName);
                nodeCounter += 1;
            }
        }
    }

    /**
     * Create node for <code>@amigaguide</code>
     */
    abstract protected Node createAmigaguideNode();

    /**
     * Create node for a link to a node in a guide.
     */
    abstract protected Node createDataLinkNode(File mappedFile, String mappedNode, String linkLabel);

    /**
     * Create node for a link to non guide file (for example an image).
     */
    abstract protected Node createOtherFileLinkNode(File linkedFile, String linkLabel);

    /**
     * Create node for the content of file included using <code>@embed</code>.
     */
    abstract protected Node createEmbeddedFile(File embeddedFile);

    abstract protected Node createLinkToNonGuideNode(File linkedFile, String linkLabel);

    /**
     * Create node to hold the text if a paragraph.
     */
    abstract protected Element createParagraph(Wrap wrap, boolean isProportional);

    /**
     * Create a URL without throwing a checked exception.
     * 
     * @throws IllegalArgumentException
     *                 in case the parameters do not result in a valid URL
     */
    protected URL createUrl(String type, String host, File file) {
        try {
            return new URL("file", "localhost", file.getAbsolutePath());
        } catch (MalformedURLException error) {
            IllegalArgumentException wrappedError = new IllegalArgumentException("cannot create file URL for "
                    + tools.sourced(file), error);
            throw wrappedError;
        }
    }

    private String nodeKey(Guide guideContainingNode, String nodeName) {
        assert guideContainingNode != null;
        assert nodeName != null;
        assert nodeName.equals(nodeName.toLowerCase()) : "nodeName must be lower case but is: "
                + tools.sourced(nodeName);
        assert guideContainingNode.getNodeInfo(nodeName) != null : "guide must contain node " + tools.sourced(nodeName)
                + ": " + guideContainingNode.getSource().getFullName();

        String result = nodeName + "@" + guideContainingNode.getSource().getFullName().replaceAll("\\@", "@@");
        return result;
    }

    private String nodeKey(Guide guideContainingNode, NodeInfo nodeInfo) {
        return nodeKey(guideContainingNode, nodeInfo.getName());
    }

    protected Element createNodeNode(Guide guide, NodeInfo nodeInfo) {
        assert guide != null;
        assert nodeInfo != null;

        Element result = dom.createElement("section");
        String sectionId = agNodeToDbNodeMap.get(nodeKey(guide, nodeInfo));
        String sectionTitle = nodeInfo.getTitle();

        log.log(Level.INFO, "create section with id={0} from node {1}: {2}", new Object[] { tools.sourced(sectionId),
                tools.sourced(nodeInfo.getName()), tools.sourced(sectionTitle) });
        result.setAttribute("id", sectionId);

        // Create title.
        Element title = dom.createElement("title");
        Text titleText = dom.createTextNode(sectionTitle);
        title.appendChild(titleText);
        result.appendChild(title);

        // Traverse node items.
        NodeParserState parserState = NodeParserState.BEFORE_NODE;
        Wrap wrap = nodeInfo.getWrap();
        boolean isProportional = nodeInfo.isProportional();
        Element paragraph = createParagraph(wrap, isProportional);
        String text = "";
        boolean lastTextWasNewLine = false;

        for (AbstractItem item : guide.getItems()) {
            log.log(Level.FINER, "parserState={0}: {1}", new Object[] { parserState, item });
            if (parserState == NodeParserState.BEFORE_NODE) {
                if (item == nodeInfo.getStartNode()) {
                    parserState = NodeParserState.INSIDE_NODE;
                    log.log(Level.FINER, "found start node: {0}", item);
                }
            } else if (parserState == NodeParserState.INSIDE_NODE) {
                if (item == nodeInfo.getEndNode()) {
                    parserState = NodeParserState.AFTER_NODE;
                    log.log(Level.FINER, "found end node: {0}", item);
                } else {
                    boolean flushText = false;
                    boolean flushParagraph = false;
                    Node nodeToAppend = null;
                    Node nodeToAppendAfterParagraph = null;

                    if (item instanceof SpaceItem) {
                        text += ((SpaceItem) item).getSpace();
                        lastTextWasNewLine = false;
                    } else if (item instanceof AbstractTextItem) {
                        text += ((AbstractTextItem) item).getText();
                        lastTextWasNewLine = false;
                    } else if (item instanceof NewLineItem) {
                        if (wrap == Wrap.NONE) {
                            text += "\n";
                        } else if (wrap == Wrap.SMART) {
                            if (lastTextWasNewLine) {
                                flushText = true;
                                flushParagraph = true;
                                lastTextWasNewLine = false;
                            } else {
                                text += "\n";
                                lastTextWasNewLine = true;
                            }
                        } else if (wrap == Wrap.WORD) {
                            flushText = true;
                            flushParagraph = true;
                        } else {
                            assert false : "wrap=" + wrap;
                        }
                    } else if (item instanceof CommandItem) {
                        CommandItem command = (CommandItem) item;
                        String commandName = command.getCommandName();
                        Tag.Name commandTag = Tag.Name.valueOfOrNull(commandName);
                        if (command.isLink()) {
                            // Create and append link.
                            log.log(Level.FINE, "connect link: {0}", command);
                            Link link = pile.getLink(command);
                            String linkLabel = command.getLinkLabel();
                            if (link != null) {
                                if (link.getState() == Link.State.VALID) {
                                    // Valid link to Amigaguide document and
                                    // node.
                                    Link.Type linkType = link.getType();
                                    String targetNode = link.getTargetNode();
                                    File linkedFile = link.getTargetFile();
                                    Guide targetGuide = pile.getGuide(linkedFile);

                                    if (linkType == Link.Type.guide) {
                                        // Assert that target node has been set
                                        // by validateLinks().
                                        assert targetNode != null;
                                    } else {
                                        // Assert that all @{alink}s have been
                                        // changed to @{link}.
                                        assert linkType == Link.Type.link : "linkType=" + linkType;
                                    }

                                    if (targetGuide != null) {
                                        // Link within DocBook document.
                                        String mappedNode = agNodeToDbNodeMap.get(nodeKey(targetGuide, targetNode));

                                        if (link.isDataLink()) {
                                            if (mappedNode != null) {
                                                nodeToAppend = createDataLinkNode(null, mappedNode, linkLabel);
                                            } else {
                                                log.warning("skipped link to unknown node: "
                                                        + command.toPrettyAmigaguide());
                                            }
                                        }
                                    } else if (linkedFile.exists()) {
                                        nodeToAppend = createOtherFileLinkNode(linkedFile, linkLabel);
                                    } else {
                                        log.warning("skipped link to unknown file: " + command.toPrettyAmigaguide());
                                    }
                                } else if (link.getState() == Link.State.VALID_OTHER_FILE) {
                                    // Valid link to non-Amigaguide file.
                                    log.log(Level.FINE, "connect to non-guide: {0}", command);
                                    nodeToAppend = createLinkToNonGuideNode(link.getTargetFile(), link.getLabel());
                                } else {
                                    log.warning("skipped link with state=" + link.getState() + ": "
                                            + command.toPrettyAmigaguide());
                                }
                            } else {
                                log.warning("skipped invalid link: " + command.toPrettyAmigaguide());
                            }

                            // Link was not appended for some reason, so at
                            // least make sure the link label shows up.
                            if (nodeToAppend == null) {
                                text += linkLabel;
                            } else {
                                flushText = true;
                            }
                        } else if (commandTag == Tag.Name.amigaguide) {
                            // Replace @{amigaguide} by text.
                            flushText = true;
                            nodeToAppend = createAmigaguideNode();
                        } else if (commandTag == Tag.Name.embed) {
                            // Include content specified by @embed
                            // FIXME: Add @embed base path.
                            File embeddedFile = amigaTools.getFileFor(command.getOption(0));
                            flushText = true;
                            flushParagraph = true;
                            log.log(Level.INFO, "embed: {0}", tools.sourced(embeddedFile));
                            nodeToAppendAfterParagraph = createEmbeddedFile(embeddedFile);
                        }
                    }
                    if (flushText) {
                        log.log(Level.FINER, "append text: {0}", tools.sourced(text));
                        if (nodeToAppend == null) {
                            text = withoutPossibleTrailingNewLine(text);
                        }
                        if (text.length() > 0) {
                            paragraph.appendChild(dom.createTextNode(text));
                        }
                        text = "";
                    }
                    if (nodeToAppend != null) {
                        paragraph.appendChild(nodeToAppend);
                    }
                    if (flushParagraph) {
                        result.appendChild(paragraph);
                        paragraph = createParagraph(wrap, isProportional);
                    }
                    if (nodeToAppendAfterParagraph != null) {
                        result.appendChild(nodeToAppendAfterParagraph);
                    }
                }
            } else {
                assert parserState == NodeParserState.AFTER_NODE : "parserState=" + parserState;
                // Do nothing, just move on past the end.
            }
        }

        assert parserState != NodeParserState.INSIDE_NODE : "parserState=" + parserState;

        if (text.length() > 0) {
            paragraph.appendChild(dom.createTextNode(withoutPossibleTrailingNewLine(text)));
            result.appendChild(paragraph);
        }

        return result;
    }

    /**
     * Same as <code>some</code> except if the last character is a new line
     * ("\n") in which case it will be removed. This is useful at the end of a
     * paragraph because &lt;literallayout&gt; or &lt;para&gt; insert a newline
     * anyway.
     */
    private String withoutPossibleTrailingNewLine(String some) {
        String result;
        if (some.endsWith("\n")) {
            result = some.substring(0, some.length() - 1);
        } else {
            result = some;
        }
        return result;
    }

    public final GuidePile getPile() {
        return pile;
    }

    protected final Document getDom() {
        return dom;
    }
}
