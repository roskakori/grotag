package net.sf.grotag.guide;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.stream.ImageInputStream;
import javax.xml.parsers.ParserConfigurationException;

import net.sf.grotag.common.AmigaTools;
import net.sf.grotag.common.Tools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.Text;

import com.twelvemonkeys.imageio.oldplugins.iff.IFFImageReaderSpi;

public class HtmlDomFactory extends AbstractDomFactory {
    private GuidePile pile;
    private File pileBaseFolder;
    private File pileTargetFolder;
    private Tools tools;
    private Map<String, File> targetFileMap;
    private File styleFile;
    private Logger log;

    public HtmlDomFactory(GuidePile newPile, File newPileTargetFolder) throws ParserConfigurationException {
        super(newPile);
        assert newPileTargetFolder != null;

        log = Logger.getLogger(HtmlDomFactory.class.getName());
        tools = Tools.getInstance();

        pile = newPile;
        pileTargetFolder = newPileTargetFolder;

        List<Guide> guides = pile.getGuides();
        assert guides != null;
        assert guides.size() > 0;
        Guide baseGuide = guides.get(0);
        pileBaseFolder = baseGuide.getSourceFile().getParentFile();
        targetFileMap = createTargetFileMap();
        styleFile = new File(pileTargetFolder, "amigaguide.css");
    }

    public File getTargetFileFor(Guide guide, NodeInfo nodeInfo) {
        File result = targetFileMap.get(nodeKey(guide, nodeInfo));
        assert result != null;
        return result;
    }

    private Map<String, File> createTargetFileMap() {
        Map<String, File> result = new HashMap<String, File>();

        for (Guide guide : pile.getGuides()) {
            Map<String, String> nodeToFileNameMap = new HashMap<String, String>();
            Set<String> fileNameSet = new HashSet<String>();
            File guideFile = guide.getSourceFile();
            String relativeGuideFolder = tools.getRelativePath(pileBaseFolder, guideFile);
            File htmlTargetFolder = new File(pileTargetFolder, tools.getWithoutLastSuffix(relativeGuideFolder));

            for (NodeInfo nextNodeInfo : guide.getNodeInfos()) {
                String nodeName = nextNodeInfo.getName();
                assert nodeName.equals(nodeName.toLowerCase());

                // Make sure the "main" node ends up in the HTML file "index".
                if (nodeName.equals("main")) {
                    nodeName = "index";
                } else if (nodeName.equals("index")) {
                    nodeName = "list";
                }
                String fileName = nodeName;
                int uniqueCounter = 0;
                while (fileNameSet.contains(fileName)) {
                    uniqueCounter += 1;
                    fileName = nodeName + "." + uniqueCounter;
                }
                fileNameSet.add(fileName);
                nodeToFileNameMap.put(nodeName, fileName);
                File htmlTargetFile = new File(htmlTargetFolder, fileName + ".html");
                result.put(nodeKey(guide, nextNodeInfo), htmlTargetFile);
            }
        }
        return result;
    }

    @Override
    protected Node createAmigaguideNode() {
        Element result = getDom().createElement("span");
        result.setAttribute("class", "b");
        result.appendChild(getDom().createTextNode("Amigaguide\u00ae"));
        return result;
    }

    @Override
    protected Node createLinkToGuideNode(Guide sourceGuide, File linkedFile, String linkedNode, String linkLabel) {
        Element result = getDom().createElement("a");
        NodeInfo anySourceNode = sourceGuide.getNodeInfos().get(0);
        File sourceHtmlFile = getTargetFileFor(sourceGuide, anySourceNode);
        Guide targetGuide = pile.getGuide(linkedFile);
        NodeInfo targetNodeInfo = targetGuide.getNodeInfo(linkedNode);
        File targetFile = getTargetFileFor(targetGuide, targetNodeInfo);
        String relativeTargetUrl = tools.getRelativeUrl(sourceHtmlFile, targetFile);

        result.setAttribute("href", relativeTargetUrl);
        result.appendChild(getDom().createTextNode(linkLabel));
        return result;
    }

    @Override
    // TODO: Consolidate and use createEmbeddedFailed instead.
    protected Node createEmbeddedFile(File embeddedFile) {
        Element result = createParagraph(Wrap.NONE, false);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(embeddedFile),
                    AmigaTools.ENCODING));
            try {
                String textLine = reader.readLine();
                while (textLine != null) {
                    result.appendChild(getDom().createTextNode(textLine + "\n"));
                    textLine = reader.readLine();
                }
            } finally {
                reader.close();
            }
        } catch (UnsupportedEncodingException error) {
            throw new IllegalStateException("Amiga encoding must be supported", error);
        } catch (IOException error) {
            result = createParagraph(Wrap.SMART, true);
            result.appendChild(getDom().createTextNode("Missing embedded file"));
            result.appendChild(getDom().createTextNode(
                    "Cannot find file to @embed: " + tools.sourced(embeddedFile) + "."));
            result.appendChild(getDom().createTextNode("Reason: " + error.getMessage() + "."));
        }

        return result;
    }

    public Document createNodeDocument(Guide guide, NodeInfo nodeInfo) throws ParserConfigurationException, IOException {
        createDom();
        Element html = getDom().createElement("html");
        Element head = createHead(guide, nodeInfo);
        html.appendChild(head);
        Element body = createNodeBody(guide, nodeInfo);
        appendNodeContent(body, guide, nodeInfo);
        getDom().appendChild(html);
        html.appendChild(body);
        return getDom();
    }

    private boolean isIffImageFile(File possibleImageFile) throws IOException {
        boolean result;
        ImageReaderSpi iffSpi = new IFFImageReaderSpi();
        ImageInputStream in = ImageIO.createImageInputStream(possibleImageFile);
        try {
            result = iffSpi.canDecodeInput(in);
        } finally {
            in.close();
        }
        return result;
    }

    @Override
    protected Node createLinkToNonGuideNode(Guide sourceGuide, File linkedFile, String linkLabel) throws IOException {
        Element result = getDom().createElement("a");
        NodeInfo anySourceNode = sourceGuide.getNodeInfos().get(0);
        File sourceHtmlFile = getTargetFileFor(sourceGuide, anySourceNode);
        String relativeLinkedFile = tools.getRelativePath(sourceGuide.getSourceFile().getParentFile(), linkedFile);
        File targetBaseFolder = sourceHtmlFile.getParentFile();
        File targetFile;

        if (isIffImageFile(linkedFile)) {
            relativeLinkedFile = tools.getWithoutLastSuffix(relativeLinkedFile) + ".png";
            targetFile = new File(targetBaseFolder, relativeLinkedFile);
            targetFile.getParentFile().mkdirs();
            log.log(Level.INFO, "convert {0} to {1}", new Object[] { tools.sourced(linkedFile),
                    tools.sourced(targetFile) });
            BufferedImage image = ImageIO.read(linkedFile);
            ImageIO.write(image, "png", targetFile);
        } else {
            targetFile = new File(targetBaseFolder, relativeLinkedFile);
            log.log(Level.INFO, "copy {0} to {1}",
                    new Object[] { tools.sourced(linkedFile), tools.sourced(targetFile) });
            tools.copyFile(linkedFile, targetFile);
        }

        String relativeTargetUrl = tools.getRelativeUrl(sourceHtmlFile, targetFile);

        result.setAttribute("href", relativeTargetUrl);
        result.appendChild(getDom().createTextNode(linkLabel));
        return result;
    }

    private Element createMetaElement(String name, String content) {
        assert name != null;
        assert name.length() > 0;
        assert content != null;
        assert content.length() > 0;

        Element result = getDom().createElement("meta");
        result.setAttribute("name", name);
        result.setAttribute("content", content);
        return result;
    }

    private String toHtmlRelation(Relation relation) {
        String result;
        if (relation == Relation.previous) {
            result = "prev";
        } else {
            result = relation.toString();
        }
        return result;
    }

    private void attemptToAppendRelation(Element parent, Guide sourceGuide, NodeInfo sourceNodeInfo, Relation relation) {
        assert parent != null;
        assert sourceGuide != null;
        assert sourceNodeInfo != null;
        assert relation != null;
        Link relationLink = sourceNodeInfo.getRelation(relation);
        if (relationLink != null) {
            log.info("append relation: " + relation + "=" + relationLink);
            // FIXME: Currently only works if relation link is a guide and node.
            File linkedFile = relationLink.getLocalTargetFile();
            String linkedNode = relationLink.getTargetNodeName();
            Guide targetGuide = pile.getGuide(linkedFile);
            if (targetGuide != null) {
                NodeInfo targetNodeInfo = targetGuide.getNodeInfo(linkedNode);
                File targetHtmlFile = getTargetFileFor(targetGuide, targetNodeInfo);
                NodeInfo anySourceNode = sourceGuide.getNodeInfos().get(0);
                File sourceHtmlFile = getTargetFileFor(sourceGuide, anySourceNode);
                String relativeTargetUrl = tools.getRelativeUrl(sourceHtmlFile, targetHtmlFile);

                Element linkElement = getDom().createElement("link");
                linkElement.setAttribute("href", relativeTargetUrl);
                linkElement.setAttribute("rel", toHtmlRelation(relation));
                parent.appendChild(linkElement);
            }
        }
    }

    /**
     * Create <code>&lt;head&gt;</code> including meta elements according to
     * <a href="http://dublincore.org/">Dublin Core</a>.
     */
    private Element createHead(Guide guide, NodeInfo nodeInfo) {
        assert guide != null;
        assert nodeInfo != null;

        Element result = getDom().createElement("head");
        result.setAttribute("profile", "http://dublincore.org/documents/2008/08/04/dc-html/");

        // Append title.
        Element title = getDom().createElement("title");
        DatabaseInfo dbInfo = guide.getDatabaseInfo();
        title.appendChild(getDom().createTextNode(dbInfo.getName()));
        result.appendChild(title);
        result.appendChild(createMetaElement("DC.title", dbInfo.getName()));

        // Append relations.
        for (Relation relation : nodeInfo.getRelationLinkMap().keySet()) {
            attemptToAppendRelation(result, guide, nodeInfo, relation);
        }

        // Append style sheet.
        String styleUrl = tools.getRelativeUrl(targetFileMap.get(nodeKey(guide, nodeInfo)), getStyleFile());
        Element style = getDom().createElement("link");
        style.setAttribute("rel", "stylesheet");
        style.setAttribute("type", "text/css");
        style.setAttribute("href", styleUrl);
        result.appendChild(style);

        // Append author.
        String author = dbInfo.getAuthor();
        if (author != null) {
            result.appendChild(createMetaElement("DC.creator", author));
        }

        // Append copyright.
        String copyright = dbInfo.getCopyright();
        if (copyright != null) {
            result.appendChild(createMetaElement("DC.rights", copyright));
        }

        // Append keywords.
        boolean isFirstNodeInGuide = (guide.getNodeInfos().get(0) == nodeInfo);
        if (isFirstNodeInGuide) {
            // TODO: Add keywords.
        }

        return result;
    }

    @Override
    protected Node createOtherFileLinkNode(Guide sourceGuide, File linkedFile, String linkLabel) {
        // TODO: Implement proper link.
        Text label = getDom().createTextNode(linkLabel);
        return label;
    }

    @Override
    protected Element createParagraph(Wrap wrap, boolean isProportional) {
        Element result;
        if (wrap == Wrap.NONE) {
            result = getDom().createElement("pre");
        } else {
            assert (wrap == Wrap.SMART) || (wrap == Wrap.WORD) : "wrap=" + wrap;
            result = getDom().createElement("p");
            if (!isProportional) {
                result.setAttribute("class", "monospace");
            }
        }
        return result;
    }

    @Override
    protected Element createNodeBody(Guide guide, NodeInfo nodeInfo) {
        Element result = getDom().createElement("body");
        return result;
    }

    @Override
    protected Element createNodeHeading(String heading) {
        assert heading != null;
        Element result = getDom().createElement("h1");
        result.appendChild(getDom().createTextNode(heading));
        return result;
    }

    public void copyStyleFile() throws IOException {
        // FIXME: Obtain style file from JAR or settings folder.
        File cssFile = new File("source", "amigaguide.css");
        tools.copyFile(cssFile, getStyleFile());
    }

    /**
     * The CSS style file used by all HTML files generated by this factory.
     */
    public File getStyleFile() {
        return styleFile;
    }
}
