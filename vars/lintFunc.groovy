import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.XMLFilter;

//
//main lint function
//
def call(path, filename, muleVersion){
    
    echo "---------- ---------- ---------- ---------- ---------- ---------- ---------- ---------- "

    if (! (filename =~ /.*xml/)) {
        echo ("The specified file is not an XML file: ${filename}\n");
        usage();
    }
    
    FileInputStream fis = new FileInputStream(path);
    
    def text = org.codehaus.groovy.runtime.IOGroovyMethods.getText(fis);

    def doc = groovy.xml.DOMBuilder.parse(new StringReader(text))
    def records = doc.documentElement
    DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
    DocumentBuilder db = dbf.newDocumentBuilder();
    InputStream is = new ByteArrayInputStream(text.getBytes("UTF8"));
    Document dom = db.parse(is);
    Element doc2 = dom.getDocumentElement();

	def inst = new LintFuncMain();
	
    def amqpArgs = [false,false,false];
	
    amqpArgs = inst.showTree(doc2,1,doc2.getNodeName(), amqpArgs, muleVersion);
    amqpArgs = inst.globalChecks(amqpArgs);
	
    def issueStr = inst.getLog();

    if (issueStr != null && issueStr != ""){
	    echo issueStr.substring(0, issueStr.size() - 2)
    } else {
        echo "No issues for file."
    }

    echo "---------- ---------- ---------- ---------- ---------- ---------- ---------- ---------- \n"

    return amqpArgs
}
