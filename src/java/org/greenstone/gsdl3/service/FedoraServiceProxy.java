/*
 *    ServiceRack.java
 *    Copyright (C) 2002 New Zealand Digital Library, http://www.nzdl.org
 *
 *    This program is free software; you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation; either version 2 of the License, or
 *    (at your option) any later version.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with this program; if not, write to the Free Software
 *    Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.greenstone.gsdl3.service;

// greenstone classes
import java.io.StringReader;
import java.util.HashMap;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.log4j.Logger;
import org.greenstone.gs3client.dlservices.DigitalLibraryServicesAPIA;
import org.greenstone.gs3client.dlservices.FedoraServicesAPIA;
import org.greenstone.gsdl3.core.MessageRouter;
import org.greenstone.gsdl3.util.Dictionary;
import org.greenstone.gsdl3.util.GSPath;
import org.greenstone.gsdl3.util.GSXML;
import org.greenstone.gsdl3.util.MacroResolver;
import org.greenstone.gsdl3.util.OID;
import org.greenstone.gsdl3.util.XMLConverter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import org.apache.commons.lang3.StringUtils;

/*
// greenstone classes
import org.greenstone.gsdl3.util.*;
import org.greenstone.gsdl3.core.*;

// for fedora
import org.greenstone.gs3client.dlservices.*;
import org.greenstone.fedora.services.FedoraGS3Exception.CancelledException;

// xml classes
import org.w3c.dom.Node; 
import org.w3c.dom.NodeList; 
import org.w3c.dom.Element; 
import org.w3c.dom.Document; 
import org.xml.sax.InputSource;
import javax.xml.parsers.*;
import org.apache.xpath.XPathAPI;

// general java classes
import java.io.Reader;
import java.io.StringReader;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.regex.*;
import java.lang.reflect.Method;
*/

import org.apache.log4j.*;

/**
 * FedoraServiceProxy - communicates with the FedoraGS3 interface.
 *
 * @author Anupama Krishnan
 */
public class FedoraServiceProxy
    extends ServiceRack implements OID.OIDTranslatable
{

    static Logger logger = Logger.getLogger(org.greenstone.gsdl3.service.FedoraServiceProxy.class.getName());
    protected MacroResolver macro_resolver = null;


    /** The handle to the fedora connection */
    private DigitalLibraryServicesAPIA fedoraServicesAPIA;

    private String prevLanguage = "";

    public void cleanUp() { 
	super.cleanUp();
    }
    
    /** sets the message router */
    public void setMessageRouter(MessageRouter m) {
       this.router = m;
       setLibraryName(m.getLibraryName());
    }

    /** the no-args constructor */
    public FedoraServiceProxy() {
	super();

	this.macro_resolver = new BasicTextMacroResolver();
    }
    

    /* configure the service module
     *
     * @param info the XML node <serviceRack name="XXX"/> with name equal
     * to the class name (of the subclass)
     *
     * must configure short_service_info_ and service_info_map_
     * @return true if configured ok
     * must be implemented in subclasses
     */
    /*public boolean configure(Element info) {
	return configure(info, null);
	}*/
    
    public boolean configure(Element info, Element extra_info) {
	// set up the class loader
		
	if (!super.configure(info, extra_info)){
	    return false;
	}

	// Try to instantiate a Fedora dl handle
	try {
	    // Fedora connection settings defaults. 
	    // Read host and port from global.properties, since by default, we expect the Greenstone server to be used
	    Properties globalProperties = new Properties();
	    globalProperties.load(Class.forName("org.greenstone.util.GlobalProperties").getClassLoader().getResourceAsStream("global.properties"));
	    String host = globalProperties.getProperty("tomcat.server", "localhost");
	    String port = globalProperties.getProperty("tomcat.port", "8383");
	    String protocol = "http";
	    String username = "fedoraIntCallUser"; //"fedoraAdmin"
	    String password = "changeme"; //"<user password>"

	    // See if buildConfig.xml overrides any of the defaults
	    // info is the <serviceRack> Element from buildConfig.xml (extra_info are the Elements of collectionConfig.xml)

	    NodeList nodes = info.getElementsByTagName("fedoraConnection");
	    if(nodes != null && nodes.getLength() > 0) {

		Element fedoraElement = (Element)nodes.item(0);
		if(fedoraElement.hasAttribute("protocol")) {
		    protocol = fedoraElement.getAttribute("protocol");
		}		
		if(fedoraElement.hasAttribute("host")) {
		    host = fedoraElement.getAttribute("host");
		}
		if(fedoraElement.hasAttribute("port")) {
		    port = fedoraElement.getAttribute("port");
		}
		if(fedoraElement.hasAttribute("username")) {
		    username = fedoraElement.getAttribute("username");
		}
		if(fedoraElement.hasAttribute("password")) {
		    password = fedoraElement.getAttribute("password");
		}		
	    }	

	    fedoraServicesAPIA = new FedoraServicesAPIA(protocol, host, Integer.parseInt(port), username, password);

	} catch(org.greenstone.fedora.services.FedoraGS3Exception.CancelledException e) {
	    // The user pressed cancel in the fedora services instantiation dialog
	    return false;
	} catch(Exception e) {
	    logger.error("Error instantiating the interface to the Fedora Repository:\n", e); // second parameter prints e's stacktrace
	    return false;
	} 

	
	// Need to put the available services into short_service_info
	// This is used by DefaultReceptionist.process() has an exception. But DefaultReceptionist.addExtraInfo() 
	// isn't helpful, and the problem actually already occurs in 
	// Receptionist.process() -> PageAction.process() -> MessageRouter.process() 
	// -> Collection/ServiceCluster.process() -> ServiceCluster.configureServiceRackList() 
	// -> ServiceRack.process() -> ServiceRack.processDescribe() -> ServiceRack.getServiceList().
	// ServiceRack.getServiceList() requires this ServiceRack's services to be filled into the 
	// short_service_info Element which needs to be done in this FedoraServiceProxy.configure().
	
	// get the display and format elements from the coll config file for
	// the classifiers
	AbstractBrowse.extractExtraClassifierInfo(info, extra_info);

	// Copied from IViaProxy.java:
	String collection = fedoraServicesAPIA.describeCollection(this.cluster_name);

	Element collNode = getResponseAsDOM(collection);
	Element serviceList = (Element)collNode.getElementsByTagName(GSXML.SERVICE_ELEM+GSXML.LIST_MODIFIER).item(0);

//this.short_service_info.appendChild(short_service_info.getOwnerDocument().importNode(serviceList, true));
	// we want the individual service Elements, not the serviceList Element which will wrap it later
	NodeList services = collNode.getElementsByTagName(GSXML.SERVICE_ELEM);
	for(int i = 0; i < services.getLength(); i++) {
	    Node service = services.item(i);
	    this.short_service_info.appendChild(short_service_info.getOwnerDocument().importNode(service, true));
	}

	// add some format info to service map if there is any
	String path = GSPath.appendLink(GSXML.SEARCH_ELEM, GSXML.FORMAT_ELEM);
	Element search_format = (Element) GSXML.getNodeByPath(extra_info, path);
	if (search_format != null) {
	    this.format_info_map.put("TextQuery", this.desc_doc.importNode(search_format, true));
	    this.format_info_map.put("FieldQuery", this.desc_doc.importNode(search_format, true));
	}
	
	// look for document display format
	path = GSPath.appendLink(GSXML.DISPLAY_ELEM, GSXML.FORMAT_ELEM);
	Element display_format = (Element)GSXML.getNodeByPath(extra_info, path);
	if (display_format != null) {
	    this.format_info_map.put("DocumentContentRetrieve", this.desc_doc.importNode(display_format, true));
	    // should we make a copy?
	}

	// the format info
	Element cb_format_info = this.desc_doc.createElement(GSXML.FORMAT_ELEM);
	boolean format_found = false;

	// look for classifier <browse><format>
	path = GSPath.appendLink(GSXML.BROWSE_ELEM, GSXML.FORMAT_ELEM);
	Element browse_format = (Element)GSXML.getNodeByPath(extra_info, path);
	if (browse_format != null) {
	    cb_format_info.appendChild(GSXML.duplicateWithNewName(this.desc_doc, browse_format, GSXML.DEFAULT_ELEM, true));
	    format_found = true;
	} 
	
	// add in to the description a simplified list of classifiers
	Element browse = (Element)GSXML.getChildByTagName(extra_info, "browse"); // the <browse>
	NodeList classifiers = browse.getElementsByTagName(GSXML.CLASSIFIER_ELEM);
	for(int i=0; i<classifiers.getLength(); i++) {
	    Element cl = (Element)classifiers.item(i);
	    Element new_cl = (Element)this.desc_doc.importNode(cl, false); // just import this node, not the children
	    
	    // get the format info out, and put inside a classifier element
	    Element format_cl = (Element)new_cl.cloneNode(false);
	    Element format = (Element)GSXML.getChildByTagName(cl, GSXML.FORMAT_ELEM);
	    if (format != null) {
		
		//copy all the children
		NodeList elems = format.getChildNodes();
		for (int j=0; j<elems.getLength();j++) {
		    format_cl.appendChild(this.desc_doc.importNode(elems.item(j), true));
		}
		cb_format_info.appendChild(format_cl);
		format_found = true;
	    }
	    	    
	}
	    
	if (format_found) {
	    this.format_info_map.put("ClassifierBrowse", cb_format_info);
	}
	

	// set up the macro resolver
	macro_resolver.setSiteDetails(this.site_http_address, this.cluster_name, this.getLibraryName());
	Element replacement_elem = (Element)GSXML.getChildByTagName(extra_info, "replaceList");
	if (replacement_elem != null) {
	    macro_resolver.addMacros(replacement_elem);
	}
	// look for any refs to global replace lists
	NodeList replace_refs_elems = extra_info.getElementsByTagName("replaceListRef");
	for (int i=0; i<replace_refs_elems.getLength(); i++) {
	    String id = ((Element)replace_refs_elems.item(i)).getAttribute("id");
	    if (!id.equals("")) {
		Element replace_list = GSXML.getNamedElement(this.router.config_info, "replaceList", "id", id);
		if (replace_list != null) {
		    macro_resolver.addMacros(replace_list);
		}
	    }
	}

	// configured ok
	return true;
    }

  
    /* "DocumentContentRetrieve", "DocumentMetadataRetrieve", "DocumentStructureRetrieve", 
      "TextQuery", "FieldQuery", "ClassifierBrowse", "ClassifierBrowseMetadataRetrieve" */

    protected Element processDocumentContentRetrieve(Element request) {
	String[] docIDs = parse(request, GSXML.DOC_NODE_ELEM, GSXML.NODE_ID_ATT); 
	String[] relLinks = parse(request, GSXML.DOC_NODE_ELEM, "externalURL");
	
	//logger.error("### request:"); 
	//logger.error(GSXML.elementToString(request, true));

	if(docIDs == null) {
	    logger.error("DocumentContentRetrieve request specified no doc nodes.\n");
	    return XMLConverter.newDOM().createElement(GSXML.RESPONSE_ELEM); // empty response
	} else {
	    for(int i = 0; i < docIDs.length; i++) {
		//logger.error("BEFORE: docIDs[" + i + "]: " + docIDs[i]);
		if(relLinks[i] != null && docIDs[i].startsWith("http://")) { // need to do a look up
		    docIDs[i] = translateExternalId(docIDs[i]);
		} else {
		    docIDs[i] = translateId(docIDs[i]);		    
		}
		//logger.error("AFTER: docIDs[" + i + "]: " + docIDs[i]);
	    }
	}
	
	String lang = request.getAttribute(GSXML.LANG_ATT);
	if(!lang.equals(prevLanguage)) {
	    prevLanguage = lang;
	    fedoraServicesAPIA.setLanguage(lang);
	}
	
	// first param (the collection) is not used by Fedora	
	Element response = getResponseAsDOM(fedoraServicesAPIA.retrieveDocumentContent(this.cluster_name, docIDs));

	
	// resolve any collection specific macros
	NodeList nodeContents = response.getElementsByTagName(GSXML.NODE_CONTENT_ELEM);
	for(int i = 0; i < nodeContents.getLength(); i++) {
	    Element nodeContent = (Element)nodeContents.item(i);
	    /*if(nodeContent != null) {
		nodeContent = (Element)nodeContent.getFirstChild(); // textNode
		}*/
	    //logger.error("GIRAFFE 1. content retrieve response - nodeContent: " + GSXML.nodeToFormattedString(nodeContent));
	    String docContent = nodeContent.getFirstChild().getNodeValue(); // getTextNode and get its contents.
	    //logger.error("GIRAFFE 2. content retrieve response - docContent: " + docContent);
	    
	    if(docContent != null) {
		// get document text and resolve and macros. Rel and external links have _httpextlink_ set by HTMLPlugin
		docContent = macro_resolver.resolve(docContent, lang, MacroResolver.SCOPE_TEXT, ""); // doc_id
		nodeContent.getFirstChild().setNodeValue(docContent);
		//logger.error("GIRAFFE 3. content retrieve response. Updated docContent: " + docContent);
	    }
	}

	return (Element)response.getElementsByTagName(GSXML.RESPONSE_ELEM).item(0); 
    }

    protected Element processDocumentStructureRetrieve(Element request) {
	String[] docIDs = parse(request, GSXML.DOC_NODE_ELEM, GSXML.NODE_ID_ATT);
	String[] relLinks = parse(request, GSXML.DOC_NODE_ELEM, "externalURL");	

	if(docIDs == null) {
	    logger.error("DocumentStructureRetrieve request specified no doc nodes.\n");
	    return XMLConverter.newDOM().createElement(GSXML.RESPONSE_ELEM); // empty response
	} else {
	    for(int i = 0; i < docIDs.length; i++) {
		//logger.error("BEFORE: docIDs[" + i + "]: " + docIDs[i]);
		if(relLinks[i] != null && docIDs[i].startsWith("http://")) { // need to do a look up
		    docIDs[i] = translateExternalId(docIDs[i]);
		} else {
		    docIDs[i] = translateId(docIDs[i]);
		}
	    }
	}

	NodeList params = request.getElementsByTagName(GSXML.PARAM_ELEM);
	String structure="";
	String info="";
	for(int i = 0; i < params.getLength(); i++) {
	    Element param = (Element)params.item(i);
	    if(param.getAttribute("name").equals("structure")) {
		structure = structure + param.getAttribute("value") + "|";
	    } else if(param.getAttribute("name").equals("info")) {
		info = info + param.getAttribute("value") + "|";
	    }
	}	
	
	String lang = request.getAttribute(GSXML.LANG_ATT);
	if(!lang.equals(prevLanguage)) {
	    prevLanguage = lang;
	    fedoraServicesAPIA.setLanguage(lang);
	}
	Element response = getResponseAsDOM(fedoraServicesAPIA.retrieveDocumentStructure(
		this.cluster_name, docIDs, new String[]{structure}, new String[]{info}));
	return (Element)response.getElementsByTagName(GSXML.RESPONSE_ELEM).item(0); 
    }

    protected Element processDocumentMetadataRetrieve(Element request) {
	String[] docIDs = parse(request, GSXML.DOC_NODE_ELEM, GSXML.NODE_ID_ATT);
	String[] relLinks = parse(request, GSXML.DOC_NODE_ELEM, "externalURL");

	if(docIDs == null) {
	    logger.error("DocumentMetadataRetrieve request specified no doc nodes.\n");
	    return XMLConverter.newDOM().createElement(GSXML.RESPONSE_ELEM); // empty response
	} else {
	    for(int i = 0; i < docIDs.length; i++) {
		//logger.error("**** relLinks[i]: " + relLinks[i]);
		//logger.error("**** docIDs[i]: " + docIDs[i]);
		if(relLinks[i] != null && docIDs[i].startsWith("http://")) { // need to do a look up
		    docIDs[i] = translateExternalId(docIDs[i]);
		} else {
		    docIDs[i] = translateId(docIDs[i]);
		}
		//logger.error("AFTER: docIDs[" + i + "]: " + docIDs[i]);
	    }
	}
	
	NodeList params = request.getElementsByTagName(GSXML.PARAM_ELEM);
	String[] metafields = {};
	if(params.getLength() > 0) {
	    metafields = new String[params.getLength()];
	    for(int i = 0; i < metafields.length; i++) {
		Element param = (Element)params.item(i);
		//if(param.hasAttribute(GSXML.NAME_ATT) && param.getAttribute(GSXML.NAME_ATT).equals("metadata") && param.hasAttribute(GSXML.VALUE_ATT)) {
		if(param.hasAttribute(GSXML.VALUE_ATT)){ 
		    metafields[i] = param.getAttribute(GSXML.VALUE_ATT);
		} else {
		    metafields[i] = "";
		}
	    }
	}

	String lang = request.getAttribute(GSXML.LANG_ATT);
	if(!lang.equals(prevLanguage)) {
	    prevLanguage = lang;
	    fedoraServicesAPIA.setLanguage(lang);
	}
	Element response = getResponseAsDOM(fedoraServicesAPIA.retrieveDocumentMetadata(
					    this.cluster_name, docIDs, metafields));
	return (Element)response.getElementsByTagName(GSXML.RESPONSE_ELEM).item(0); 
    }

    protected Element processClassifierBrowseMetadataRetrieve(Element request) {	
	String[] classIDs = parse(request, GSXML.CLASS_NODE_ELEM, GSXML.NODE_ID_ATT);
	//String[] relLinks = parse(request, GSXML.CLASS_NODE_ELEM, "externalURL");

	if(classIDs == null) {
	    logger.error("ClassifierBrowseMetadataRetrieve request specified no classifier nodes.\n");
	    return XMLConverter.newDOM().createElement(GSXML.RESPONSE_ELEM); // empty response
	} else {
	    for(int i = 0; i < classIDs.length; i++) {
		classIDs[i] = translateId(classIDs[i]);
	    }
	}
	
	NodeList params = request.getElementsByTagName(GSXML.PARAM_ELEM);
	String[] metafields = {};
	if(params.getLength() > 0) {
	    metafields = new String[params.getLength()];
	    for(int i = 0; i < metafields.length; i++) {
		Element param = (Element)params.item(i);
		if(param.hasAttribute(GSXML.VALUE_ATT)){ 
		    metafields[i] = param.getAttribute(GSXML.VALUE_ATT);
		} else {
		    metafields[i] = "";
		}
	    }
	}
	
	String lang = request.getAttribute(GSXML.LANG_ATT);
	if(!lang.equals(prevLanguage)) {
	    prevLanguage = lang;
	    fedoraServicesAPIA.setLanguage(lang);
	}
	Element response = getResponseAsDOM(fedoraServicesAPIA.retrieveBrowseMetadata(
	       this.cluster_name, "ClassifierBrowseMetadataRetrieve", classIDs, metafields));
	//logger.error("**** Response from retrieveBrowseMeta: " + GSXML.elementToString(response, true));
	return (Element)response.getElementsByTagName(GSXML.RESPONSE_ELEM).item(0);
    }

    protected Element processClassifierBrowse(Element request) {
	String collection = this.cluster_name;
	String lang = request.getAttribute(GSXML.LANG_ATT);
	if(!lang.equals(prevLanguage)) {
	    prevLanguage = lang;
	    fedoraServicesAPIA.setLanguage(lang);
	}

	NodeList classNodes = request.getElementsByTagName(GSXML.CLASS_NODE_ELEM);
	if(classNodes == null || classNodes.getLength() <= 0) {
	    logger.error("ClassifierBrowse request specified no classifier IDs.\n");
	    return XMLConverter.newDOM().createElement(GSXML.RESPONSE_ELEM); // empty response
	}
	String classifierIDs[] = new String[classNodes.getLength()];
	for(int i = 0; i < classifierIDs.length; i++) {
	    Element e = (Element)classNodes.item(i);
	    classifierIDs[i] = e.getAttribute(GSXML.NODE_ID_ATT);
	    classifierIDs[i] = translateId(classifierIDs[i]);	
	}
	
	NodeList params = request.getElementsByTagName(GSXML.PARAM_ELEM);
	String structure="";
	String info="";
	for(int i = 0; i < params.getLength(); i++) {
	    Element param = (Element)params.item(i);
	    if(param.getAttribute("name").equals("structure")) {
		structure = structure + param.getAttribute("value") + "|";
	    } else if(param.getAttribute("name").equals("info")) {
		info = info + param.getAttribute("value") + "|";
	    }
	}
	///structure = structure + "siblings"; //test for getting with classifier browse structure: siblings
	
	Element response 
	    = getResponseAsDOM(fedoraServicesAPIA.retrieveBrowseStructure(collection, "ClassifierBrowse", classifierIDs,
									  new String[] {structure}, new String[] {info}));
	//logger.error("**** FedoraServiceProxy - Response from retrieveBrowseStructure: " + GSXML.elementToString(response, true));	
	
	return (Element)response.getElementsByTagName(GSXML.RESPONSE_ELEM).item(0);
    }

    protected Element processTextQuery(Element request) {
	return processQuery(request, "TextQuery");
    }

    protected Element processFieldQuery(Element request) {
	return processQuery(request, "FieldQuery");
    }

    protected Element processQuery(Element request, String querytype) {
	String collection = this.cluster_name;

	String lang = request.getAttribute(GSXML.LANG_ATT);
	if(!lang.equals(prevLanguage)) {
	    prevLanguage = lang;
	    fedoraServicesAPIA.setLanguage(lang);
	}

	NodeList paramNodes = request.getElementsByTagName(GSXML.PARAM_ELEM);
	if(paramNodes.getLength() > 0) {
	    HashMap<String, String> params = new HashMap<String, String>(paramNodes.getLength()); 
	    for(int i = 0; i < paramNodes.getLength(); i++) {
		Element param = (Element)paramNodes.item(i);
		params.put(param.getAttribute(GSXML.NAME_ATT), param.getAttribute(GSXML.VALUE_ATT));
	    }

	    Element response = getResponseAsDOM(fedoraServicesAPIA.query(collection, querytype, params));
	    return (Element)response.getElementsByTagName(GSXML.RESPONSE_ELEM).item(0);
	} else {
	    logger.error("TextQuery request specified no parameters.\n");
	    return XMLConverter.newDOM().createElement(GSXML.RESPONSE_ELEM); // empty response
	}
    }

    // get the requested nodeIDs out of a request message
    protected String[] parse(Element request, String nodeType, String attribute) {	
	String[] nodevalues = null;
	int count = 0;

	Element docList = (Element) GSXML.getChildByTagName(request, nodeType+GSXML.LIST_MODIFIER);
	if (docList != null) {	    
	    NodeList docNodes = docList.getElementsByTagName(nodeType);
	    if(docNodes.getLength() > 0) {
		nodevalues = new String[docNodes.getLength()];
		for(int i = 0; i < nodevalues.length; i++) {
		    Element e = (Element)docNodes.item(i);
		    String id = e.getAttribute(attribute);
		    // Not sure why there are at times requests for hashXXX.dir, which is not a fedora PID
		    // To skip these: if not requesting an externalURL and if requesting a docNode, 
		    // then the ID has to contain the : character special to fedora PIDs
		    if(attribute == "externalURL" || (nodeType != GSXML.DOC_NODE_ELEM || id.contains(":"))) {
			nodevalues[count++] = id;
		    }
		}
	    }
	}

	if(count == 0) {
	    return null;
	}

	String[] tmp = new String[count];
	for(int i = 0; i < count; i++) {
	    tmp[i] = nodevalues[i];
	}
	nodevalues = null;
	nodevalues = tmp;

	return nodevalues;
    }


    /** if id ends in .fc, .pc etc, then translate it to the correct id 
     * For now (for testing things work) the default implementation is to just remove the suffix */
    protected String translateId(String id) {
	if (OID.needsTranslating(id)) {
	    return OID.translateOID(this, id); //return translateOID(id);
	}
	return id;
    }
    
    /** if an id is not a greenstone id (an external id) then translate 
     * it to a greenstone one 
     * default implementation: return the id. Custom implementation: 
     * the id is a url that maps to a fedorapid whose dc.title contains the required HASHID */
    protected String translateExternalId(String id) {
	//logger.error("*** to translate an external ID: " + id); /////return id;
	return this.externalId2OID(id);
    }

    /** converts an external id to greenstone OID. External ID is a URL link 
     * that, if relative, maps to a fedorapid that has an entry in fedora.
     * The dc:title meta for that fedorapid will contain the required OID. */
    public String externalId2OID(String extid) {
	if(extid.endsWith(".rt") && (extid.indexOf('.') != extid.lastIndexOf('.'))) { 
	       // .rt is not file extension, but Greenstone request for root of document
	       // not relevant for external ID
	       extid = extid.substring(0, extid.length()-3);
	}

	   // the following method is unique to FedoraServicesAPIA
	String response = ((FedoraServicesAPIA)fedoraServicesAPIA).getDocIDforURL(extid, this.cluster_name);
	if(response.indexOf(GSXML.ERROR_ELEM) != -1) {
	    logger.error("**** The following error occurred when trying to find externalID for ID " + extid);
	    logger.error(response);
	    return extid;
	}
	if(response.equals("")) {
	    return extid;
	} else {
	    return response;
	}
    }


  /** translates relative oids into proper oids:
   * .pr (parent), .rt (root) .fc (first child), .lc (last child),
   * .ns (next sibling), .ps (previous sibling) 
   * .np (next page), .pp (previous page) : links sections in the order that you'd read the document
   * a suffix is expected to be present so test before using 
   */
    public String processOID(String doc_id, String top, String suff, int sibling_num) {

    // send off request to get sibling etc. information from Fedora
    Element response = null;
    String[] children = null;
    if(doc_id.startsWith("CL")) { // classifiernode
	response = getResponseAsDOM(fedoraServicesAPIA.retrieveBrowseStructure(this.cluster_name, "ClassifierBrowse", new String[]{doc_id},
									       new String[]{"children"}, new String[]{"siblingPosition"}));
	NodeList nl = response.getElementsByTagName(GSXML.NODE_STRUCTURE_ELEM);
	if(nl.getLength() > 0) {
	    Element nodeStructure = (Element)nl.item(0);

	    if(nodeStructure != null) {
		Element root = (Element) GSXML.getChildByTagName(nodeStructure, GSXML.CLASS_NODE_ELEM);
		if(root != null) { // get children
		    NodeList classNodes = root.getElementsByTagName(GSXML.CLASS_NODE_ELEM);
		    if(classNodes != null) {
			children = new String[classNodes.getLength()];
			for(int i = 0; i < children.length; i++) {
			    Element child = (Element)classNodes.item(i);
			    children[i] = child.getAttribute(GSXML.NODE_ID_ATT);
			}
		    }
		}
	    }
	}
    } else { // documentnode
	response = getResponseAsDOM(fedoraServicesAPIA.retrieveDocumentStructure(this.cluster_name, new String[]{doc_id},
										 new String[]{"children"}, new String[]{"siblingPosition"}));
	String path = GSPath.createPath(new String[]{GSXML.RESPONSE_ELEM, GSXML.DOC_NODE_ELEM+GSXML.LIST_MODIFIER, 
					      GSXML.DOC_NODE_ELEM, GSXML.NODE_STRUCTURE_ELEM, GSXML.DOC_NODE_ELEM});	
	Element parentDocNode = (Element) GSXML.getNodeByPath(response, path);

	if (parentDocNode == null) {
	    return top;
	} // else 
	NodeList docNodes = parentDocNode.getElementsByTagName(GSXML.DOC_NODE_ELEM); // only children should remain, since that's what we requested
	if(docNodes.getLength() > 0) {
	    children = new String[docNodes.getLength()];
	    
	    for(int i = 0; i < children.length; i++) {
		Element e = (Element)docNodes.item(i);
		children[i] = e.getAttribute(GSXML.NODE_ID_ATT);
	    }
	} else { // return root node
	    children = new String[]{doc_id};
	}
    }
    
    if (suff.equals("fc")) {
      return children[0];
    } else if (suff.equals("lc")) {
      return children[children.length-1];
    } else {
      if (suff.equals("ss")) {
	return children[sibling_num-1];
      }
      // find the position that we are at.
      int i=0;
      while(i<children.length) {
	if (children[i].equals(top)) {
	  break;
	}
	i++;
      }
	    
      if (suff.equals("ns")) {
	if (i==children.length-1) {
	  return children[i];
	}
	return children[i+1];
      } else if (suff.equals("ps")) {
	if (i==0) {
	  return children[i];
	}
	return children[i-1];
      }
    }

    return top;
  }


    protected Element getResponseAsDOM(String response) {
	if(response == null) { // will not be the case, because an empty  
	    return null;    // response message will be sent instead
	}

	Element message = null;		
	try{
	    // turn the String xml response into a DOM tree:	
	    DocumentBuilder builder 
		= DocumentBuilderFactory.newInstance().newDocumentBuilder();
	    Document doc 
		= builder.parse(new InputSource(new StringReader(response)));
	    message = doc.getDocumentElement();
	} catch(Exception e){
	    if(response == null) {
		response = "";
	    }
	    logger.error("An error occurred while trying to parse the response: ");
	    logger.error(response);
	    logger.error(e.getMessage());
	}
	
	// Error elements in message will be processed outside of here, just return the message
	return message;
    }

    /* //process method for stylesheet requests    
    protected Element processFormat(Element request) {}	*/
    
    /* returns the service list for the subclass */
    /* protected Element getServiceList(String lang) {
	// for now, it is static and has no language stuff
	return (Element) this.short_service_info.cloneNode(true);
	}*/

    /** returns a specific service description */
  protected Element getServiceDescription(Document doc, String service, String lang, String subset) {
	if(!lang.equals(prevLanguage)) {
	    prevLanguage = lang;
	    fedoraServicesAPIA.setLanguage(lang);
	}
	String serviceResponse = fedoraServicesAPIA.describeService(service);
	Element response = getResponseAsDOM(serviceResponse);

	// should be no chance of an npe, since FedoraGS3 lists the services, so will have descriptions for each
	Element e = (Element)response.getElementsByTagName(GSXML.SERVICE_ELEM).item(0);
	e = (Element)doc.importNode(e, true);
	return e; 
    }

    /** overloaded version for no args case */
    protected String getTextString(String key, String lang) {
	return getTextString(key, lang, null, null);
    }

    protected String getTextString(String key, String lang, String dictionary) {
	return getTextString(key, lang, dictionary, null);
    }
    protected String getTextString(String key, String lang, String [] args) {
	return getTextString(key, lang, null, args);
    }
	
    /** getTextString - retrieves a language specific text string for the given
key and locale, from the specified resource_bundle (dictionary)
    */
    protected String getTextString(String key, String lang, String dictionary, String[] args) {

	// we want to use the collection class loader in case there are coll specific files
	if (dictionary != null) {
	    // just try the one specified dictionary
	    Dictionary dict = new Dictionary(dictionary, lang, this.class_loader);
	    String result = dict.get(key, args);
	    if (result == null) { // not found
		return "_"+key+"_";
	    }
	    return result;
	}

	// now we try class names for dictionary names
	String class_name = this.getClass().getName();
	class_name = class_name.substring(class_name.lastIndexOf('.')+1);
	Dictionary dict = new Dictionary(class_name, lang, this.class_loader);
	String result = dict.get(key, args);
	if (result != null) {
	    return result;
	}

	// we have to try super classes
	Class c = this.getClass().getSuperclass();
	while (result == null && c != null) {
	    class_name = c.getName();
	    class_name = class_name.substring(class_name.lastIndexOf('.')+1);
	    if (class_name.equals("ServiceRack")) {
		// this is as far as we go
		break;
	    }
	    dict = new Dictionary(class_name, lang, this.class_loader);
	    result = dict.get(key, args);
	    c = c.getSuperclass();
	}
	if (result == null) {
	    return "_"+key+"_";
	}
	return result;
	
    }

    protected String getMetadataNameText(String key, String lang) {

	String properties_name = "metadata_names";
	Dictionary dict = new Dictionary(properties_name, lang);
	
	String result = dict.get(key);
	if (result == null) { // not found
	    return null;
	} 
	return result;
    }

    public static class BasicTextMacroResolver extends MacroResolver {	
	private static final Pattern p_back_slash = Pattern.compile("\\\"");// create a pattern "\\\"", but it matches both " and \"

	public String resolve(String text, String lang, String scope, String doc_oid) 
	{

	    if (text == null || text.equals("")) {
		return text;
	    }	    
	    if (!scope.equals(SCOPE_TEXT) || text_macros.size()==0) {
		return text;
	    }

	    java.util.ArrayList macros = text_macros;
	    for (int i=0; i<macros.size(); i++) {
		String new_text = null;
		Macro m = (Macro)macros.get(i);
		
		if(m.type == TYPE_TEXT) {
		    // make sure we resolve any macros in the text

		    if(text.contains(m.macro)) {
			if (m.resolve) {
			    new_text = this.resolve(m.text, lang, scope, doc_oid);
			} else {
			    new_text = m.text;
			}
			text = StringUtils.replace(text, m.macro, new_text);//text = text.replaceAll(m.macro, new_text);
			if (m.macro.endsWith("\\\\")) { // to get rid of "\" from the string like: "src="http://www.greenstone.org:80/.../mw.gif\">"
			    Matcher m_slash = p_back_slash.matcher(text);
			    String clean_str = "";
			    int s=0;
			    while (m_slash.find()) {
				if (!text.substring(m_slash.end()-2, m_slash.end()-1).equals("\\")) {
				    clean_str = clean_str + text.substring(s,m_slash.end()-1); // it matches ", so get a substring before "
				}else{
				    clean_str = clean_str + text.substring(s,m_slash.end()-2);// it matches \", so get a substring before \
				}
				s = m_slash.end();// get the index of the last match
				clean_str = clean_str + "\"";
			    }
			    text = clean_str + text.substring(s,text.length());
			}
		    }
		}
	    }
	    return text;
	}
    }


}

