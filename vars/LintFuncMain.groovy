//package com.holx.lint

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

//import groovy.util.XmlParser

/**
* One class to lintify our mule code.
*
* This requires the install of the groovy 2.4 compiler to set groovy_home in Studio
* Groovy - http://dist.springsource.org/snapshot/GRECLIPSE/e4.7/   << can't get it working in studio 7.... use ant
*
* History
* 2021-02-20 ALAACK Modified for Mule4
* 2020-02-23 Alex P Added to Jenkins
* 2020-01-22 ALAACK rewrote original that was in Perl.
*            WHY: Perl is line oriented but XML is not. This unnecessarily restricted the formatting used in XML.
*                 Groovy is Java-ish which more people know. It has built-in regular expressions so conversion is easy.
*                 Groovy is already installed via Studio. Perl/cygwin install required extra steps.
*                 Groovy is easier to run and debug.
*                 Removed: The Lint checks that were added for the Win->Linux migration.
*                          API type based on filename. Not needed at this time.
*                          Comparison between common and not. -- not needed. Use Ant task and Git diff instead.
*                          Checks specific to code that should be in common-*.xml anyway such as SMTP HTML formatting.
* 2016-12-08 ALAACK first version of Mule lint.
*
**/
public class LintFuncMain {

	String g_currentFlow = "";
	Boolean g_amqpListMissPrefetch = false;
	Boolean g_amqpListMissAck = false;
	static String EOL="\n";
    Boolean g_hasAmqp = false;
    Boolean g_hasAmqpReplyToStop = false;
	StringBuffer oo = new StringBuffer();

	def String getLog() {
		return oo.toString();
	}
	
    def logit2(priority,msg,nodePath,appFileName,lineNo,line,skipnl) {
        //print 3,"--- Groovy script component needs a better name.",$appFileName,$lineNo,$line);
    
		oo.append("P$priority :: $nodePath $appFileName"+EOL);
		oo.append("   $msg"+EOL);

    }
    
    def showTree(Node n,int level,String nodePath, amqpArgs, String muleVersion) {
        String x = "";
        for (int i = 0 ; i < level ; i++) {
            x = x+ " ";
        }
        
        def kids = n.getChildNodes();

        for ( def idx=0 ; idx < kids.getLength() ; idx++) {
            Node kiddo = kids.item(idx);
            if ("#text".equals(kiddo.getNodeName())) {
                //                Main.log(x+"name:"+att.getNodeName()+" -> val:"+att.getNodeValue());
                //print(x+"ZZname:"+kiddo.getNodeName()+" -> val:"+kiddo.getNodeValue().trim() +" ["+kiddo.getNodeType()+"]"   +"\n");
            } else {
            
                // vvvv SET THE NODE PATH TEMPORARILY
                def String dname = kiddo.getAttributes()?.getNamedItem("doc:name")?.getNodeValue();
                if (dname == null) {
                    dname="";
                } else {
                    dname ="("+dname+")";
                }
                if (kiddo.getNodeName() == "flow" || kiddo.getNodeName() == "sub-flow") {
                    dname = "("+kiddo.getAttributes()?.getNamedItem("name")?.getNodeValue()+")";
                }
                def origNodePath=nodePath;
                nodePath = nodePath+' -> '+kiddo.getNodeName() + dname;
                // ^^^^ SET THE NODE PATH TEMPORARILY
                                   
                //                Main.log(x+"name:"+att.getNodeName());
                //print(x+"name:"+kiddo.getNodeName()+" ["+kiddo.getNodeType()+"]\n");

                HashMap hm = new HashMap();
                NamedNodeMap nnm = kiddo.getAttributes();
                // show attributes
                if (nnm!=null) {
                    for ( int jdx = 0 ; jdx < nnm.getLength() ; jdx++) {
                        Node natt = nnm.item(jdx);

                        //print ("att:"+natt.getNodeName()+" -> "+natt.getNodeValue()+"\n")
                        hm.put(natt.getNodeName(), natt.getNodeValue())
                    }
                }                
                def appFileName = "";
                def lineNo=0;
                def line="";
	            def g_currentFlow=""
                
                // Most of the action takes place here.
                switch (kiddo.getNodeName()) {
                    case 'scripting:component':
                        if (hm["doc:name"] == 'Groovy') {
                            logit2(3,"--- Groovy script component needs a better name.",nodePath,appFileName,idx-1,line,true);
                        }
                    break;
                    
                    case 'flow':
                    case 'sub-flow':
                        g_currentFlow=hm.name;
                    break;
                    
                    case 'amqp:listener':
                    	if (muleVersion == "4") {
                    		g_amqpListMissPrefetch = true;
                    		g_amqpListMissAck = true;
                    		
                    		if (hm["numberOfConsumers"] == null) {
								logit2(1,"--- AMQP missing numberOfConsumers=1.",nodePath,appFileName,idx-1,line,true);
	                        	//p1 issue
	                        	amqpArgs[2] = true
	                        }
                    		if (hm["createFallbackQueue"] != "false") {
								logit2(1,"--- AMQP createFallbackQueue != false.",nodePath,appFileName,idx-1,line,true);
	                        	//p1 issue
	                        	amqpArgs[2] = true
	                        }
                    		if (hm["recoverStrategy"] != "NO_REQUEUE") {
								logit2(1,"--- AMQP recoverStrategy != NO_REQUEUE.",nodePath,appFileName,idx-1,line,true);
	                        	//p1 issue
	                        	amqpArgs[2] = true
	                        }
                    		if (hm["ackMode"] != "MANUAL") {
								logit2(1,"--- AMQP ackMode != MANUAL.",nodePath,appFileName,idx-1,line,true);
	                        	//p1 issue
	                        	amqpArgs[2] = true
	                        }
                    	}
                    break;
                    
                    case 'amqp:ack':
                    	g_amqpListMissAck = false
                    break;
                    
                    case 'amqp:listener-quality-of-service-config':
                    	if (muleVersion == "4") {
                    		if (hm["prefetchCount"] != null) {
                    			g_amqpListMissPrefetch = false;
                    		}
                    	}
                    break;
                    
                    case 'sfdc:config':
						if (hm["disableSessionInvalidation"] == null || hm["disableSessionInvalidation"] == "false" ) {
							logit2(1,"--- SFDC Connector found but it needs to have disableSessionInvalidation=true.",nodePath,appFileName,idx-1,line,true);
	                        //p1 issue
	                        amqpArgs[2] = true
	                    }
						if (! (hm["password"] =~ /\$\{/) ) {
							logit2(1,"--- SFDC Connector has hardcoded password.",nodePath,appFileName,idx-1,line,true);
	                        //p1 issue
	                        amqpArgs[2] = true
						}
                    break;
                    
                    case 'when':
                        //print ("att: $hm.expression \n")                    
                        def express = hm.expression;
                        def matcher = (express =~ /.*?\s+or\s+.*/ )
                        if (matcher.size() > 0) {
                            logit2(3,"--- Do not use OR in an expression. Use || instead.",nodePath,appFileName,idx-1,line,true);
                        }
                        matcher = (express =~ /.*?\s+and\s+.*/ )
                        if (matcher.size() > 0) {
                            logit2(3,"--- Do not use AND in an expression. Use && instead.",nodePath,appFileName,idx-1,line,true);
                        }
                        matcher = (express =~ /#\[.*\]/ )
                        if (matcher.size() == 0) {
                            logit2(3,"--- Missing #[] in the expression. Use without #[] is deprecated.",nodePath,appFileName,idx-1,line,true);
                        }
                    break;
                    
                    case 'set-variable':
                    	if (hm["doc:name"] == null) {
	                            logit2(1,"--- set-variable without documentation name",nodePath,appFileName,idx-1,line,true);
                    	} else {
							String [] tmp = hm["doc:name"].split(" ");
							hm["doc:name"] = tmp[0];
	                        if (hm["variableName"] != hm["doc:name"]) {
	                            logit2(3,"--- A variable name should match the first part of the description. "+hm["variableName"]+" / "+hm["doc:name"],nodePath,appFileName,idx-1,line,true);
	                        }
                        }
                    break;
                    
                    case 'batch:job':
                        logit2(2,"Batch processing should not be used. Replace it with a for-each. See sfdc-sr-publisher or sfdc-servicecontract-publisher.",nodePath,appFileName,idx-1,line,true);
                    break;
                    
                    case 'http:listener-config':
                        logit2(1,"Applications should not define their own HTTP or HTTPS server configuration. Use the one from common.xml",nodePath,appFileName,idx-1,line,true);
	                    //p1 issue
	                    amqpArgs[2] = true
          			break;
          			
          			case 'db:config':
          				if (muleVersion=="4") {
							logit2(1,"Applications should NOT define their own DB Pool config. Use the one from the domain.",nodePath,appFileName,idx-1,line,true);
	                    	//p1 issue
	                    	amqpArgs[2] = true
          				}
          			break;
          			
          			case 'configuration-properties':
          			case 'secure-properties:config':
          				if (muleVersion == "4" && hm["file"] =~ /.*HolxDomain.properties$/ ) {
							logit2(1,"Applications should NOT define their own Property config for "+hm["file"]+". Use the one from the domain.",nodePath,appFileName,idx-1,line,true);
	                    	//p1 issue
	                    	amqpArgs[2] = true
          				}
          			break;
					
					case 'amqp:inbound-endpoint':
	                    amqpArgs[0] = true;
					break;
					
					case 'add-message-property':
						// amqp needs this set on inbound
						if (hm["key"] == 'MULE_REPLYTO_STOP' && hm["value"] == 'true') {
	                        amqpArgs[1] = true;
						}
					break;
					
					case 'amqp:outbound-endpoint':
						if (hm["exchange-pattern"] == "request-response") {
							logit2(1,"--- AMQP output connector has a REQUEST-RESPONSE exchange pattern, which means the publisher will wait for all subscribers to finish. The setting should be ONE-WAY (default).",nodePath,appFileName,idx-1,line,true);
	                        //p1 issue
	                        amqpArgs[2] = true
						}
                    break;

					case 'http:request-connection':
						// check for http/https in request config
						if (!(hm["doc:name"] =~ /.*agile.*/ || hm["doc:name"] =~ /.*lint-ignore:nonHTTPS.*/)) { // Allow Agile to use non-HTTPS
							if (hm["protocol"] != "HTTPS") {
		                        logit2(2,"Http request configurations need the protocol set to HTTPS: "+hm["protocol"],nodePath,appFileName,idx-1,line,true);
								//p1 issue
								//amqpArgs[2] = true
							}
						}
					break;
					
					case 'http:request-config':
						if (muleVersion == "4" && hm["responseTimeout"] == null) {
							logit2(2,"Http request configurations need a property set for the response timeout.",nodePath,appFileName,idx-1,line,true);
							//amqpArgs[2] = true
						}
					break;
			
                    case 'logger':
                        def level2=hm["level"];
                        def doc=hm["doc:name"];
                        def msg=hm["message"];
                        
                        def appType="unknown";
                        
                        // verify Logger has a transaction ID
                        if (
                        (! (msg =~ /(?i)recordId/ ))
                        &&
                        (! (msg =~ /(?i)messageId/ ))
                        &&
                        (! (msg =~ /(?i)msgId/ ))
                        &&
                        (! (msg =~ /vars.logPrefix/ ))
                        &&
                        (! (msg =~ /AMQP/))
                        &&
                        (! (msg =~ /BannerLog/))
                        &&
                        (! (doc =~ /BannerLog/))
                        &&
                        (! (msg =~ /records to process/ ))
                        &&
                        (! (appType == "l-svc"))
                        &&
                        (! (appType == "l-proxy"))
                        &&
                        (! (msg =~ /(?i)batchId/ ))
                        &&
                        (! (msg =~ /(?i)trxId/ ))
                        ) {
                            logit2(2,"--- Logger without recordID/batchId/trxId/logPrefix: "+msg ,nodePath,appFileName,idx-1,line,true);
                        }
                        
                        //# :: Logger level should appear in the Studio UI's message processor name when not the default, INFO.
                        if ((level2 == "DEBUG") && !(doc =~ / \(debug\)/)) {
                            logit2(3,"--- Logger: Consider putting ' (debug)' in the document name",nodePath,appFileName,idx-1,line,true);
                        }
                        if ((level2 == "TRACE") && !(doc =~ / \(trace\)/)) {
                            logit2(3,"--- Logger: Consider putting ' (trace)' in the document name",nodePath,appFileName,idx-1,line,true);
                        }
                
                        //# :: Logger message processors need a good description. The default description is simply "Logger", which isn't helpful.
                        if ((doc == "Logger")) {
                            logit2(3,"--- Logger: Needs a better description. ",nodePath,appFileName,idx-1,line,true);
                        }                        
                                
                    break;
                    
                }
                
				showTree(kiddo,level+1, nodePath, amqpArgs, muleVersion );
                nodePath=origNodePath; // RESET THE NODE PATH
            }
        }

		    return amqpArgs            
    }
    
    	/** print out post-tree items, if any. These checks typically span layers of the tree.
		There has to be a better way to do these but there aren't many so, meh. **/
	def globalChecks(amqpArgs) {
	    def appFileName = "";
	    def lineNo=0;
	    def line="";    
	    def idx=0;
	    if (amqpArgs[0] && !amqpArgs[1]) {
	        logit2(1,"--- AMPQ needs MULE_REPLYTO_STOP=true","",appFileName,idx-1,line,true);
	        //p1 issue
	        amqpArgs[2] = true
	    }
	    
	    if (g_amqpListMissPrefetch) {
	        logit2(1,"--- AMPQ needs prefetch=1","",appFileName,idx-1,line,true);
	        //p1 issue
	        amqpArgs[2] = true
	    }
	    if (g_amqpListMissAck) {
	        logit2(1,"--- AMPQ is missing manual Ack step","",appFileName,idx-1,line,true);
	        //p1 issue
	        amqpArgs[2] = true
	    }
	    

	    return amqpArgs
	}
	
	static usage() {
		print EOL;
		print EOL;
		print "Command line usage: java org.codehaus.groovy.tools.GroovyStarter --main com.holx.lint.MainLint <Date>          <XML File>     <3|4>"+EOL
		print 'Studio runtime config usage (program args):                      --main com.holx.lint.MainLint ${current_date} ${resource_loc} 4'+EOL;
		print 'muleutils ant build.xml:  ant -Denv.GROOVY_HOME=%GROOVY_HOME% -Dmule_version=4 lint-compile'
		print "exiting..."+EOL;
		print EOL;
		System.exit(1);
	}

    /** Starting point for the Lint utility */
    static main(args) {

        print "---------- ---------- ---------- ---------- ---------- ---------- ---------- ---------- "+EOL
		
		print "arg["+args.length+"]: "+args+EOL;

        File f = new File(".");
        print "file:"+f.getAbsolutePath()+EOL;
        //FileInputStream fis = new FileInputStream("resources/holx-southware-serviceorder-publisher.xml");
		
		if (args.length<2) {
			usage();
		}
		
		def muleVersion = "3"
		if (args.length > 2) {
			muleVersion = args[2]
		}
		
		//print("Mule version:"+muleVersion)
		
		def filename=args[1];
		if (! (filename =~ /.*xml/)) {
			print ("The specified file is not an XML file:"+args[1]+EOL);
			usage();
		}
		
		FileInputStream fis = new FileInputStream(args[1]);
		
        def text = org.codehaus.groovy.runtime.IOGroovyMethods.getText(fis);
        //print "x:"+text.substring(1,500);

        //def list = new XmlParser().parseText(text)
        //print "xml:"+list.xml.toString().substring(1,5000)

        print "---------- ---------- ---------- ---------- ---------- ---------- ---------- ---------- "+EOL

        def doc = groovy.xml.DOMBuilder.parse(new StringReader(text))
        //def doc = new XmlParser().parseText(text)
        def records = doc.documentElement
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        DocumentBuilder db = dbf.newDocumentBuilder();
        InputStream is = new ByteArrayInputStream(text.getBytes("UTF8"));
        Document dom = db.parse(is);
        Element doc2 = dom.getDocumentElement();

		//              amqp, amqp,  p1-issue
		def amqpArgs = [false,false,false]

		
		LintFuncMain inst=new LintFuncMain();
		
		inst.showTree(doc2,1,doc2.getNodeName(),amqpArgs,muleVersion);
		inst.globalChecks(amqpArgs);
        
        print inst.getLog() + "EOF"+EOL;
    }
}