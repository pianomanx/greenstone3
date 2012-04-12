package org.greenstone.gsdl3;

import org.greenstone.gsdl3.comms.*;
import org.greenstone.gsdl3.core.*;
import org.greenstone.gsdl3.service.Authentication;
import org.greenstone.gsdl3.util.*;
import org.greenstone.gsdl3.action.PageAction; // used to get the default action
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.*;
import javax.servlet.*;
import javax.servlet.http.*;

import java.util.Enumeration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.lang.reflect.Type;
import java.util.Hashtable;
import org.apache.log4j.*;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

// Apache Commons
import org.apache.commons.lang3.*;

/**
 * a servlet to serve the greenstone library - we are using servlets instead of
 * cgi the init method is called only once - the first time the servlet classes
 * are loaded. Each time a request comes in to the servlet, the session() method
 * is called in a new thread (calls doGet/doPut etc) takes the a=p&p=home type
 * args and builds a simple request to send to its receptionist, which returns a
 * result in html, cos output=html is set in the request
 * 
 * 18/Jul/07 xiao modify to make the cached parameters collection-specific. Most
 * of the work is done in doGet(), except adding an inner class
 * UserSessionCache.
 * 
 * @see Receptionist
 */
public class LibraryServlet extends HttpServlet
{

	/** the receptionist to send messages to */
	protected Receptionist recept = null;

	/**
	 * the default language - is specified by setting a servlet param, otherwise
	 * DEFAULT_LANG is used
	 */
	protected String default_lang = null;

	/** Whether or not client-side XSLT support should be exposed */
	protected boolean supports_client_xslt = false;

	/**
	 * The default default - used if a default lang is not specified in the
	 * servlet params
	 */
	protected final String DEFAULT_LANG = "en";

	/** container Document to create XML Nodes */
	protected Document doc = null;

	/** a converter class to parse XML and create Docs */
	protected XMLConverter converter = null;

	/**
	 * the cgi stuff - the Receptionist can add new args to this
	 * 
	 * its used by the servlet to determine what args to save
	 */
	protected GSParams params = null;

	/**
	 * user id - new one per session. This doesn't work if session state is
	 * saved between restarts - this requires this value to be saved too.
	 */
	protected int next_user_id = 0;

	/**
	 * a hash that contains all the active session IDs mapped to the cached
	 * items It is updated whenever the whole site or a particular collection is
	 * reconfigured using the command a=s&sa=c or a=s&sa=c&c=xxx It is in the
	 * form: sid -> (UserSessionCache object)
	 */
	protected Hashtable session_ids_table = new Hashtable();

	/**
	 * the maximum interval that the cached info remains in session_ids_table
	 * (in seconds) This is set in web.xml
	 */
	protected int session_expiration = 1800;

	static Logger logger = Logger.getLogger(org.greenstone.gsdl3.LibraryServlet.class.getName());

	/** initialise the servlet */
	public void init(ServletConfig config) throws ServletException
	{
		// always call super.init;
		super.init(config);
		// disable preferences - does this work anyway??
		//System.setProperty("java.util.prefs.PreferencesFactory", "org.greenstone.gsdl3.util.DisabledPreferencesFactory");

		String library_name = config.getInitParameter(GSConstants.LIBRARY_NAME);
		String gsdl3_home = config.getInitParameter(GSConstants.GSDL3_HOME);
		String interface_name = config.getInitParameter(GSConstants.INTERFACE_NAME);

		String allowXslt = (String) config.getInitParameter(GSConstants.ALLOW_CLIENT_SIDE_XSLT);
		supports_client_xslt = allowXslt != null && allowXslt.equals("true");

		this.default_lang = config.getInitParameter(GSConstants.DEFAULT_LANG);
		String sess_expire = config.getInitParameter(GSXML.SESSION_EXPIRATION);

		if (sess_expire != null && !sess_expire.equals(""))
		{
			this.session_expiration = Integer.parseInt(sess_expire);
		}

		if (library_name == null || interface_name == null)
		{
			// must have this
			System.err.println("initialisation parameters not all set!");
			System.err.println(" you must have libraryname and interfacename");
			System.exit(1);
		}

		String site_name = config.getInitParameter(GSConstants.SITE_NAME);
		String remote_site_name = null;
		String remote_site_type = null;
		String remote_site_address = null;

		if (site_name == null)
		{
			// no site, try for communicator
			remote_site_name = config.getInitParameter("remote_site_name");
			remote_site_type = config.getInitParameter("remote_site_type");
			remote_site_address = config.getInitParameter("remote_site_address");
			if (remote_site_name == null || remote_site_type == null || remote_site_address == null)
			{
				System.err.println("initialisation paramters not all set!");
				System.err.println("if site_name is not set, then you must have remote_site_name, remote_site_type and remote_site_address set");
				System.exit(1);
			}
		}

		if (this.default_lang == null)
		{
			// choose english
			this.default_lang = DEFAULT_LANG;
		}

		HashMap config_params = new HashMap();

		config_params.put(GSConstants.LIBRARY_NAME, library_name);
		config_params.put(GSConstants.INTERFACE_NAME, interface_name);
		config_params.put(GSConstants.ALLOW_CLIENT_SIDE_XSLT, supports_client_xslt);

		if (site_name != null)
		{
			config_params.put(GSConstants.SITE_NAME, site_name);
		}
		this.converter = new XMLConverter();
		this.doc = this.converter.newDOM();

		// the receptionist -the servlet will talk to this
		String recept_name = (String) config.getInitParameter("receptionist_class");
		if (recept_name == null)
		{
			this.recept = new DefaultReceptionist();
		}
		else
		{
			try
			{
				this.recept = (Receptionist) Class.forName("org.greenstone.gsdl3.core." + recept_name).newInstance();
			}
			catch (Exception e)
			{ // cant use this new one, so use normal one
				System.err.println("LibraryServlet configure exception when trying to use a new Receptionist " + recept_name + ": " + e.getMessage());
				e.printStackTrace();
				this.recept = new DefaultReceptionist();
			}
		}
		this.recept.setConfigParams(config_params);

		// the receptionist uses a MessageRouter or Communicator to send its requests to. We either create a MessageRouter here for the designated site (if site_name set), or we create a Communicator for a remote site. The is given to teh Receptionist, and the servlet never talks to it again.directly.
		if (site_name != null)
		{
			String mr_name = (String) config.getInitParameter("messagerouter_class");
			MessageRouter message_router = null;
			if (mr_name == null)
			{ // just use the normal MR
				message_router = new MessageRouter();
			}
			else
			{ // try the specified one
				try
				{
					message_router = (MessageRouter) Class.forName("org.greenstone.gsdl3.core." + mr_name).newInstance();
				}
				catch (Exception e)
				{ // cant use this new one, so use normal one
					System.err.println("LibraryServlet configure exception when trying to use a new MessageRouter " + mr_name + ": " + e.getMessage());
					e.printStackTrace();
					message_router = new MessageRouter();
				}
			}

			message_router.setSiteName(site_name);
			message_router.setLibraryName(library_name);
			message_router.configure();
			this.recept.setMessageRouter(message_router);
		}
		else
		{
			// talking to a remote site, create a communicator
			Communicator communicator = null;
			// we need to create the XML to configure the communicator
			Element site_elem = this.doc.createElement(GSXML.SITE_ELEM);
			site_elem.setAttribute(GSXML.TYPE_ATT, remote_site_type);
			site_elem.setAttribute(GSXML.NAME_ATT, remote_site_name);
			site_elem.setAttribute(GSXML.ADDRESS_ATT, remote_site_address);

			if (remote_site_type.equals(GSXML.COMM_TYPE_SOAP_JAVA))
			{
				communicator = new SOAPCommunicator();
			}
			else
			{
				System.err.println("LibraryServlet.init Error: invalid Communicator type: " + remote_site_type);
				System.exit(1);
			}

			if (!communicator.configure(site_elem))
			{
				System.err.println("LibraryServlet.init Error: Couldn't configure communicator");
				System.exit(1);
			}
			this.recept.setMessageRouter(communicator);
		}

		// the params arg thingy

		String params_name = (String) config.getInitParameter("params_class");
		if (params_name == null)
		{
			this.params = new GSParams();
		}
		else
		{
			try
			{
				this.params = (GSParams) Class.forName("org.greenstone.gsdl3.util." + params_name).newInstance();
			}
			catch (Exception e)
			{
				System.err.println("LibraryServlet configure exception when trying to use a new params thing " + params_name + ": " + e.getMessage());
				e.printStackTrace();
				this.params = new GSParams();
			}
		}
		// pass it to the receptionist
		this.recept.setParams(this.params);
		this.recept.configure();

		//Allow the message router and the document to be accessed from anywhere in this servlet context
		this.getServletContext().setAttribute("GSRouter", this.recept.getMessageRouter());
		this.getServletContext().setAttribute("GSDocument", this.doc);
	}

	private void logUsageInfo(HttpServletRequest request)
	{
		String usageInfo = "";

		//session-info: get params stored in the session
		HttpSession session = request.getSession(true);
		Enumeration attributeNames = session.getAttributeNames();
		while (attributeNames.hasMoreElements())
		{
			String name = (String) attributeNames.nextElement();
			usageInfo += name + "=" + session.getAttribute(name) + " ";
		}

		//logged info = general-info + session-info
		usageInfo = request.getServletPath() + " " + //serlvet
		"[" + request.getQueryString() + "]" + " " + //the query string
		"[" + usageInfo.trim() + "]" + " " + // params stored in a session
		request.getRemoteAddr() + " " + //remote address
		request.getRequestedSessionId() + " " + //session id
		request.getHeader("user-agent") + " "; //the remote brower info

		logger.info(usageInfo);

	}

	public class UserSessionCache implements HttpSessionBindingListener
	{

		String session_id = "";

		/**
		 * a hash that maps the session ID to a hashtable that maps the
		 * coll_name to its parameters coll_name -> Hashtable (param_name ->
		 * param_value)
		 */
		protected Hashtable coll_name_params_table = null;

		public UserSessionCache(String id, Hashtable table)
		{
			session_id = id;
			coll_name_params_table = (table == null) ? new Hashtable() : table;
		}

		protected void cleanupCache(String coll_name)
		{
			if (coll_name_params_table.containsKey(coll_name))
			{
				coll_name_params_table.remove(coll_name);
			}
		}

		protected Hashtable getParamsTable()
		{
			return coll_name_params_table;
		}

		public void valueBound(HttpSessionBindingEvent event)
		{
			// Do nothing
		}

		public void valueUnbound(HttpSessionBindingEvent event)
		{
			if (session_ids_table.containsKey(session_id))
			{
				session_ids_table.remove(session_id);
			}
		}

		public int tableSize()
		{
			return (coll_name_params_table == null) ? 0 : coll_name_params_table.size();
		}

	}

	public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		logUsageInfo(request);

		Map<String, String[]> queryMap = request.getParameterMap();
		if (queryMap != null)
		{
			Iterator<String> queryIter = queryMap.keySet().iterator();
			boolean redirect = false;
			String href = null;
			String rl = null;
			String el = null;
			while (queryIter.hasNext())
			{
				String q = queryIter.next();
				if (q.equals(GSParams.EXTERNAL_LINK_TYPE))
				{
					el = queryMap.get(q)[0];
				}
				else if (q.equals(GSParams.HREF))
				{
					href = queryMap.get(q)[0];
					href = StringUtils.replace(href, "%2f", "/");
					href = StringUtils.replace(href, "%7e", "~");
					href = StringUtils.replace(href, "%3f", "?");
					href = StringUtils.replace(href, "%3A", "\\:");
				}
				else if (q.equals(GSParams.RELATIVE_LINK))
				{
					rl = queryMap.get(q)[0];
				}
			}

			//if query_string contains "el=direct", an href is specified, and its not a relative link, then the web page will be redirected to the external URl, otherwise a greenstone page with an external URL will be displayed
			//"rl=0" this is an external link
			//"rl=1" this is an internal link
			if ((href != null) && (rl.equals("0")))
			{// This is an external link, 

				if (el.equals("framed"))
				{
					//TODO **** how best to change to a=p&sa=html&c=collection&url=href
					// response.setContentType("text/xml");
					//response.sendRedirect("http://localhost:8383/greenstone3/gs3library?a=p&sa=html&c=external&url="+href);
				}
				else
				{
					// el = '' or direct
					//the web page is re-directed to the external URL (&el=&rl=0&href="http://...")
					response.setContentType("text/xml");
					response.sendRedirect(href);
				}
			}
		}

		// Nested Diagnostic Configurator to identify the client for
		HttpSession session = request.getSession(true);
		session.setMaxInactiveInterval(session_expiration);
		String uid = (String) session.getAttribute(GSXML.USER_ID_ATT);
		if (uid == null)
		{
			uid = "" + getNextUserId();
			session.setAttribute(GSXML.USER_ID_ATT, uid);
		}

		request.setCharacterEncoding("UTF-8");
		response.setContentType("text/html;charset=UTF-8");
		PrintWriter out = response.getWriter();

		String lang = request.getParameter(GSParams.LANGUAGE);
		if (lang == null || lang.equals(""))
		{
			// try the session cached lang
			lang = (String) session.getAttribute(GSParams.LANGUAGE);
			if (lang == null || lang.equals(""))
			{
				// still not set, use the default
				lang = this.default_lang;
			}
		}
		UserContext userContext = new UserContext();
		userContext.setLanguage(lang);
		userContext.setUserID(uid);

		// set the lang in the session
		session.setAttribute(GSParams.LANGUAGE, lang);

		String output = request.getParameter(GSParams.OUTPUT);
		if (output == null || output.equals(""))
		{
			output = "html"; // uses html by default
		}

		// If server output, force a switch to traditional interface
		//output = (output.equals("server")) ? "html" : output;

		// Force change the output mode if client-side XSLT is supported - server vs. client
		// BUT only if the library allows client-side transforms	
		if (supports_client_xslt)
		{
			// MUST be done before the xml_message is built
			Cookie[] cookies = request.getCookies();
			Cookie xsltCookie = null;

			// The client has cookies enabled and a value set - use it!
			if (cookies != null)
			{
				for (Cookie c : cookies)
				{
					if (c.getName().equals("supportsXSLT"))
					{
						xsltCookie = c;
						break;
					}
				}
				output = (xsltCookie != null && xsltCookie.getValue().equals("true") && output.equals("html")) ? "xsltclient" : output;
			}
		}

		// the request to the receptionist
		Element xml_message = this.doc.createElement(GSXML.MESSAGE_ELEM);
		Element xml_request = GSXML.createBasicRequest(this.doc, GSXML.REQUEST_TYPE_PAGE, "", userContext);
		xml_request.setAttribute(GSXML.OUTPUT_ATT, output);

		xml_message.appendChild(xml_request);

		String action = request.getParameter(GSParams.ACTION);
		String subaction = request.getParameter(GSParams.SUBACTION);
		String collection = request.getParameter(GSParams.COLLECTION);
		String document = request.getParameter(GSParams.DOCUMENT);
		String service = request.getParameter(GSParams.SERVICE);

		// We clean up the cache session_ids_table if system
		// commands are issued (and also don't need to do caching for this request)
		boolean should_cache = true;
		if (action != null && action.equals(GSParams.SYSTEM_ACTION))
		{
			should_cache = false;

			// we may want to remove all collection cache info, or just a specific collection
			boolean clean_all = true;
			String clean_collection = null;
			// system commands are to activate/deactivate stuff
			// collection param is in the sc parameter.
			// don't like the fact that it is hard coded here
			String coll = request.getParameter(GSParams.SYSTEM_CLUSTER);
			if (coll != null && !coll.equals(""))
			{
				clean_all = false;
				clean_collection = coll;
			}
			else
			{
				// check other system types
				if (subaction.equals("a") || subaction.equals("d"))
				{
					String module_name = request.getParameter("sn");
					if (module_name != null && !module_name.equals(""))
					{
						clean_all = false;
						clean_collection = module_name;
					}
				}
			}
			if (clean_all)
			{
				session_ids_table = new Hashtable();
				session.removeAttribute(GSXML.USER_SESSION_CACHE_ATT);
			}
			else
			{
				// just clean up info for clean_collection
				ArrayList cache_list = new ArrayList(session_ids_table.values());
				for (int i = 0; i < cache_list.size(); i++)
				{
					UserSessionCache cache = (UserSessionCache) cache_list.get(i);
					cache.cleanupCache(clean_collection);
				}

			}
		}

		// cache_key is the collection name, or service name
		String cache_key = collection;
		if (cache_key == null || cache_key.equals(""))
		{
			cache_key = service;
		}

		// logger.info("should_cache= " + should_cache);

		//clear the collection-specific cache in the session, since we have no way to know whether this session is 
		//about the same collection as the last session or not.
		Enumeration attributeNames = session.getAttributeNames();
		while (attributeNames.hasMoreElements())
		{
			String name = (String) attributeNames.nextElement();
			if (!name.equals(GSXML.USER_SESSION_CACHE_ATT) && !name.equals(GSParams.LANGUAGE) && !name.equals(GSXML.USER_ID_ATT))
			{
				session.removeAttribute(name);
			}
		}

		UserSessionCache session_cache = null;
		Hashtable param_table = null;
		Hashtable table = null;
		String sid = session.getId();
		if (should_cache == true && cache_key != null && !cache_key.equals(""))
		{
			if (session_ids_table.containsKey(sid))
			{
				session_cache = (UserSessionCache) session_ids_table.get(sid);
				param_table = session_cache.getParamsTable();
				logger.info("collections in table: " + tableToString(param_table));
				if (param_table.containsKey(cache_key))
				{
					//logger.info("existing table: " + collection);
					table = (Hashtable) param_table.get(cache_key);
				}
				else
				{
					table = new Hashtable();
					param_table.put(cache_key, table);
					//logger.info("new table: " + collection);
				}
			}
			else
			{
				param_table = new Hashtable();
				table = new Hashtable();
				param_table.put(cache_key, table);
				session_cache = new UserSessionCache(sid, param_table);
				session_ids_table.put(sid, session_cache);
				session.setAttribute(GSXML.USER_SESSION_CACHE_ATT, session_cache);
				//logger.info("new session id");
			}
		}

		if (action == null || action.equals(""))
		{
			// should we do all the following stuff if using default page?
			// display the home page  - the default page
			xml_request.setAttribute(GSXML.ACTION_ATT, "p");
			xml_request.setAttribute(GSXML.SUBACTION_ATT, PageAction.HOME_PAGE);
		}
		else
		{
			xml_request.setAttribute(GSXML.ACTION_ATT, action);
			if (subaction != null)
			{
				xml_request.setAttribute(GSXML.SUBACTION_ATT, subaction);
			}

			//  create the param list for the greenstone request - includes
			// the params from the current request and any others from the saved session
			Element xml_param_list = this.doc.createElement(GSXML.PARAM_ELEM + GSXML.LIST_MODIFIER);
			xml_request.appendChild(xml_param_list);

			for (String name : queryMap.keySet())
			{
				if (!name.equals(GSParams.ACTION) && !name.equals(GSParams.SUBACTION) && !name.equals(GSParams.LANGUAGE) && !name.equals(GSParams.OUTPUT))
				{// we have already dealt with these

					String value = "";
					String[] values = request.getParameterValues(name);
					value = values[0];
					if (values.length > 1)
					{
						for (int i = 1; i < values.length; i++)
						{
							value += "," + values[i];
						}
					}
					// either add it to the param list straight away, or save it to the session and add it later
					if (this.params.shouldSave(name) && table != null)
					{
						table.put(name, value);
					}
					else
					{
						Element param = this.doc.createElement(GSXML.PARAM_ELEM);
						param.setAttribute(GSXML.NAME_ATT, name);
						param.setAttribute(GSXML.VALUE_ATT, GSXML.xmlSafe(value));
						xml_param_list.appendChild(param);
					}
				}
			}
			//put everything in the table into the session
			// do we need to do this? why not just put from table into param list
			if (table != null)
			{
				Enumeration keys = table.keys();
				while (keys.hasMoreElements())
				{
					String name = (String) keys.nextElement();
					session.setAttribute(name, (String) table.get(name));
				}
			}

			// put in all the params from the session cache
			Enumeration params = session.getAttributeNames();
			while (params.hasMoreElements())
			{
				String name = (String) params.nextElement();

				if (!name.equals(GSXML.USER_SESSION_CACHE_ATT) && !name.equals(GSParams.LANGUAGE) && !name.equals(GSXML.USER_ID_ATT))
				{

					// lang and uid are stored but we dont want it in the param list cos its already in the request
					Element param = this.doc.createElement(GSXML.PARAM_ELEM);
					param.setAttribute(GSXML.NAME_ATT, name);
					String value = GSXML.xmlSafe((String) session.getAttribute(name));

					// ugly hack to undo : escaping
					value = StringUtils.replace(value, "%3A", "\\:");
					param.setAttribute(GSXML.VALUE_ATT, value);
					xml_param_list.appendChild(param);
				}
			}
		}

		if (!output.equals("html") && !output.equals("server") && !output.equals("xsltclient"))
		{
			response.setContentType("text/xml"); // for now use text
		}

		//Add custom HTTP headers if requested
		String httpHeadersParam = request.getParameter(GSParams.HTTPHEADERFIELDS);
		if (httpHeadersParam != null && httpHeadersParam.length() > 0)
		{
			Gson gson = new Gson();
			Type type = new TypeToken<List<Map<String, String>>>()
			{
			}.getType();
			List<Map<String, String>> httpHeaders = gson.fromJson(httpHeadersParam, type);
			if (httpHeaders != null && httpHeaders.size() > 0)
			{

				for (int j = 0; j < httpHeaders.size(); j++)
				{
					Map nameValueMap = (Map) httpHeaders.get(j);
					String name = (String) nameValueMap.get("name");
					String value = (String) nameValueMap.get("value");

					if (name != null && value != null)
					{
						response.setHeader(name, value);
					}
				}
			}
		}

		String requestedURL = request.getRequestURL().toString();
		String baseURL = "";
		if (requestedURL.indexOf(this.getServletName()) != -1)
		{
			baseURL = requestedURL.substring(0, requestedURL.indexOf(this.getServletName()));
			xml_request.setAttribute("baseURL", baseURL);
		}

		String fullURL;
		if (request.getQueryString() != null)
		{
			fullURL = requestedURL + "?" + request.getQueryString();
		}
		else
		{
			fullURL = requestedURL;
		}

		xml_request.setAttribute("remoteAddress", request.getRemoteAddr());
		xml_request.setAttribute("fullURL", fullURL.replace("&", "&amp;"));

		if (!runSecurityChecks(request, xml_request, userContext, out, baseURL, collection, document))
		{
			return;
		}

		Node xml_result = this.recept.process(xml_message);
		encodeURLs(xml_result, response);
		out.println(this.converter.getPrettyString(xml_result));

		displaySize(session_ids_table);

	} //end of doGet(HttpServletRequest, HttpServletResponse)

	private boolean runSecurityChecks(HttpServletRequest request, Element xml_request, UserContext userContext, PrintWriter out, String baseURL, String collection, String document) throws ServletException
	{
		//Check if we need to login or logout
		Map<String, String[]> params = request.getParameterMap();
		String[] username = params.get("username");
		String[] password = params.get("password");
		String[] logout = params.get("logout");

		if (logout != null)
		{
			request.logout();
		}

		if (username != null && password != null)
		{
			//We are changing to another user, so log out first
			if (request.getAuthType() != null)
			{
				request.logout();
			}

			try
			{
				password[0] = Authentication.hashPassword(password[0]);
				request.login(username[0], password[0]);
			}
			catch (Exception ex)
			{
				//The user entered in either the wrong username or the wrong password
				Element loginPageMessage = this.doc.createElement(GSXML.MESSAGE_ELEM);
				Element loginPageRequest = GSXML.createBasicRequest(this.doc, GSXML.REQUEST_TYPE_PAGE, "", userContext);
				loginPageRequest.setAttribute(GSXML.ACTION_ATT, "p");
				loginPageRequest.setAttribute(GSXML.SUBACTION_ATT, "login");
				loginPageRequest.setAttribute(GSXML.OUTPUT_ATT, "html");
				loginPageRequest.setAttribute(GSXML.BASE_URL, baseURL);
				loginPageMessage.appendChild(loginPageRequest);

				Element paramList = this.doc.createElement(GSXML.PARAM_ELEM + GSXML.LIST_MODIFIER);
				loginPageRequest.appendChild(paramList);

				Element messageParam = this.doc.createElement(GSXML.PARAM_ELEM);
				messageParam.setAttribute(GSXML.NAME_ATT, "loginMessage");
				messageParam.setAttribute(GSXML.VALUE_ATT, "Either your username or password was incorrect, please try again.");
				paramList.appendChild(messageParam);

				Element urlParam = this.doc.createElement(GSXML.PARAM_ELEM);
				urlParam.setAttribute(GSXML.NAME_ATT, "redirectURL");
				String queryString = "";
				if (request.getQueryString() != null)
				{
					queryString = "?" + request.getQueryString().replace("&", "&amp;");
				}
				urlParam.setAttribute(GSXML.VALUE_ATT, this.getServletName() + queryString);
				paramList.appendChild(urlParam);

				Node loginPageResponse = this.recept.process(loginPageMessage);
				out.println(this.converter.getPrettyString(loginPageResponse));

				return false;
			}
		}

		//If a user is logged in
		if (request.getAuthType() != null)
		{
			Element userInformation = this.doc.createElement(GSXML.USER_INFORMATION_ELEM);
			userInformation.setAttribute("username", request.getUserPrincipal().getName());

			Element userInfoMessage = this.doc.createElement(GSXML.MESSAGE_ELEM);
			Element userInfoRequest = GSXML.createBasicRequest(this.doc, GSXML.REQUEST_TYPE_SECURITY, "GetUserInformation", userContext);
			userInfoMessage.appendChild(userInfoRequest);

			Element paramList = this.doc.createElement(GSXML.PARAM_ELEM + GSXML.LIST_MODIFIER);
			userInfoRequest.appendChild(paramList);

			Element param = this.doc.createElement(GSXML.PARAM_ELEM);
			param.setAttribute(GSXML.NAME_ATT, GSXML.USERNAME_ATT);
			param.setAttribute(GSXML.VALUE_ATT, request.getUserPrincipal().getName());
			paramList.appendChild(param);

			Element userInformationResponse = (Element) GSXML.getChildByTagName(this.recept.process(userInfoMessage), GSXML.RESPONSE_ELEM);
			Element responseParamList = (Element) GSXML.getChildByTagName(userInformationResponse, GSXML.PARAM_ELEM + GSXML.LIST_MODIFIER);
			if (responseParamList == null)
			{
				logger.error("Can't get the groups for user " + request.getUserPrincipal().getName());
			}
			else
			{
				HashMap responseParams = GSXML.extractParams(responseParamList, true);
				String groups = (String) responseParams.get(GSXML.GROUPS_ATT);

				userInformation.setAttribute(GSXML.GROUPS_ATT, groups);
				xml_request.appendChild(userInformation);
			}
		}

		//If we are in a collection-related page then make sure this user is allowed to access it
		if (collection != null && !collection.equals(""))
		{
			//Get the security info for this collection
			Element securityMessage = this.doc.createElement(GSXML.MESSAGE_ELEM);
			Element securityRequest = GSXML.createBasicRequest(this.doc, GSXML.REQUEST_TYPE_SECURITY, collection, userContext);
			securityMessage.appendChild(securityRequest);
			if (document != null && !document.equals(""))
			{
				securityRequest.setAttribute(GSXML.NODE_OID, document);
			}

			Element securityResponse = (Element) GSXML.getChildByTagName(this.recept.process(securityMessage), GSXML.RESPONSE_ELEM);
			ArrayList<String> groups = GSXML.getGroupsFromSecurityResponse(securityResponse);

			//If guests are not allowed to access this page then check to see if the user is in a group that is allowed to access the page
			if (!groups.contains(""))
			{
				boolean found = false;
				for (String group : groups)
				{
					if (request.isUserInRole(group))
					{
						found = true;
						break;
					}
				}

				//The current user is not allowed to access the page so produce a login page
				if (!found)
				{
					Element loginPageMessage = this.doc.createElement(GSXML.MESSAGE_ELEM);
					Element loginPageRequest = GSXML.createBasicRequest(this.doc, GSXML.REQUEST_TYPE_PAGE, "", userContext);
					loginPageRequest.setAttribute(GSXML.ACTION_ATT, "p");
					loginPageRequest.setAttribute(GSXML.SUBACTION_ATT, "login");
					loginPageRequest.setAttribute(GSXML.OUTPUT_ATT, "html");
					loginPageRequest.setAttribute(GSXML.BASE_URL, baseURL);
					loginPageMessage.appendChild(loginPageRequest);

					Element paramList = this.doc.createElement(GSXML.PARAM_ELEM + GSXML.LIST_MODIFIER);
					loginPageRequest.appendChild(paramList);

					Element messageParam = this.doc.createElement(GSXML.PARAM_ELEM);
					messageParam.setAttribute(GSXML.NAME_ATT, "loginMessage");
					if (request.getAuthType() == null)
					{
						messageParam.setAttribute(GSXML.VALUE_ATT, "Please log in to view this page");
					}
					else
					{
						messageParam.setAttribute(GSXML.VALUE_ATT, "You are not in the correct group to view this page, would you like to log in as a different user?");
					}
					paramList.appendChild(messageParam);

					Element urlParam = this.doc.createElement(GSXML.PARAM_ELEM);
					urlParam.setAttribute(GSXML.NAME_ATT, "redirectURL");
					if (request.getQueryString() != null && request.getQueryString().length() > 0)
					{
						urlParam.setAttribute(GSXML.VALUE_ATT, this.getServletName() + "?" + request.getQueryString().replace("&", "&amp;"));
					}
					else
					{
						urlParam.setAttribute(GSXML.VALUE_ATT, this.getServletName());
					}
					paramList.appendChild(urlParam);

					Node loginPageResponse = this.recept.process(loginPageMessage);
					out.println(this.converter.getPrettyString(loginPageResponse));

					return false;
				}
			}
		}
		return true;
	}

	//a debugging method
	private void displaySize(Hashtable table)
	{
		if (table == null)
		{
			logger.info("cached table is null");
			return;
		}
		if (table.size() == 0)
		{
			logger.info("cached table size is zero");
			return;
		}
		int num_cached_coll = 0;
		ArrayList cache_list = new ArrayList(table.values());
		for (int i = 0; i < cache_list.size(); i++)
		{
			num_cached_coll += ((UserSessionCache) cache_list.get(i)).tableSize();
		}
		logger.info("Number of sessions : total number of cached collection info = " + table.size() + " : " + num_cached_coll);
	}

	/** merely a debugging method! */
	private String tableToString(Hashtable table)
	{
		String str = "";
		Enumeration keys = table.keys();
		while (keys.hasMoreElements())
		{
			String name = (String) keys.nextElement();
			str += name + ", ";
		}
		return str;
	}

	/**
	 * this goes through each URL and adds in a session id if needed-- its
	 * needed if the browser doesn't accept cookies also escapes things if
	 * needed
	 */
	protected void encodeURLs(Node dataNode, HttpServletResponse response)
	{

		if (dataNode == null)
		{
			return;
		}

		Element data = null;

		short nodeType = dataNode.getNodeType();
		if (nodeType == Node.DOCUMENT_NODE)
		{
			Document docNode = (Document) dataNode;
			data = docNode.getDocumentElement();
		}
		else
		{
			data = (Element) dataNode;
		}

		if (data != null)
		{

			// get all the <a> elements
			NodeList hrefs = data.getElementsByTagName("a");
			// Instead of calculating each iteration...
			int hrefscount = hrefs.getLength();

			for (int i = 0; hrefs != null && i < hrefscount; i++)
			{
				Element a = (Element) hrefs.item(i);
				// ugly hack to get rid of : in the args - interferes with session handling
				String href = a.getAttribute("href");
				if (!href.equals(""))
				{
					if (href.indexOf("?") != -1)
					{
						String[] parts = StringUtils.split(href, "\\?", -1);
						if (parts.length == 1)
						{
							parts[0] = StringUtils.replace(parts[0], ":", "%3A");
							href = "?" + parts[0];
						}
						else
						{
							parts[1] = StringUtils.replace(parts[1], ":", "%3A");
							href = parts[0] + "?" + parts[1];
						}

					}
					a.setAttribute("href", response.encodeURL(href));
				}
			}

			// now find any submit bits - get all the <form> elements
			NodeList forms = data.getElementsByTagName("form");
			int formscount = forms.getLength();
			for (int i = 0; forms != null && i < formscount; i++)
			{
				Element form = (Element) forms.item(i);
				form.setAttribute("action", response.encodeURL(form.getAttribute("action")));
			}
			// are these the only cases where URLs occur??
			// we should only do this for greenstone urls?
		}

	}

	synchronized protected int getNextUserId()
	{
		next_user_id++;
		return next_user_id;
	}

	public void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
	{
		doGet(request, response);
	}
}
