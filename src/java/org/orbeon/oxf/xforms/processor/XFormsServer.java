/**
 * Copyright (C) 2009 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.processor;

import org.apache.commons.pool.ObjectPool;
import org.apache.log4j.Logger;
import org.dom4j.Document;
import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.externalcontext.ResponseAdapter;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.ProcessorImpl;
import org.orbeon.oxf.processor.ProcessorInputOutputInfo;
import org.orbeon.oxf.processor.ProcessorOutput;
import org.orbeon.oxf.processor.serializer.CachedSerializer;
import org.orbeon.oxf.util.ContentHandlerOutputStream;
import org.orbeon.oxf.util.IndentedLogger;
import org.orbeon.oxf.util.LoggerFactory;
import org.orbeon.oxf.util.NetUtils;
import org.orbeon.oxf.xforms.*;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.controls.XFormsSelectControl;
import org.orbeon.oxf.xforms.event.XFormsEvents;
import org.orbeon.oxf.xforms.itemset.Itemset;
import org.orbeon.oxf.xforms.state.XFormsDocumentCache;
import org.orbeon.oxf.xforms.state.XFormsState;
import org.orbeon.oxf.xforms.state.XFormsStateManager;
import org.orbeon.oxf.xforms.submission.XFormsModelSubmission;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.orbeon.oxf.xml.SAXStore;
import org.orbeon.oxf.xml.TransformerUtils;
import org.orbeon.oxf.xml.XMLUtils;
import org.orbeon.oxf.xml.dom4j.Dom4jUtils;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.util.*;

/**
 * The XForms Server processor handles client requests, including events, and either returns an XML
 * response, or returns a response through the ExternalContext.
 */
public class XFormsServer extends ProcessorImpl {

    public static final boolean USE_SEPARATE_LOGGERS = false;
    public static final String LOGGING_CATEGORY = "server";
    private static final Logger logger = LoggerFactory.createLogger(XFormsServer.class);

    private static final String INPUT_REQUEST = "request";
    //private static final String OUTPUT_RESPONSE = "response"; // optional

    public static final Map<String, String> XFORMS_NAMESPACES = new HashMap<String, String>();
    static {
        XFORMS_NAMESPACES.put(XFormsConstants.XFORMS_SHORT_PREFIX, XFormsConstants.XFORMS_NAMESPACE_URI);
        XFORMS_NAMESPACES.put(XFormsConstants.XML_EVENTS_PREFIX, XFormsConstants.XML_EVENTS_NAMESPACE_URI);
        XFORMS_NAMESPACES.put(XFormsConstants.XXFORMS_SHORT_PREFIX, XFormsConstants.XXFORMS_NAMESPACE_URI);
    }

    public static Logger getLogger() {
        return logger;
    }

    public XFormsServer() {
        addInputInfo(new ProcessorInputOutputInfo(INPUT_REQUEST)); // optional
        //addOutputInfo(new ProcessorInputOutputInfo(OUTPUT_RESPONSE)); // optional
    }

    /**
     * Case where an XML response must be generated.
     */
    public ProcessorOutput createOutput(final String outputName) {
        final ProcessorOutput output = new ProcessorImpl.ProcessorOutputImpl(getClass(), outputName) {
            public void readImpl(final PipelineContext pipelineContext, ContentHandler contentHandler) {
                doIt(pipelineContext, contentHandler);
            }
        };
        addOutput(outputName, output);
        return output;
    }

    /**
     * Case where the response is generated through the ExternalContext (submission with replace="all").
     */
    public void start(PipelineContext pipelineContext) {
        doIt(pipelineContext, null);
    }

    private void doIt(final PipelineContext pipelineContext, ContentHandler contentHandler) {

        final Element filesElement;
        final Element actionElement;
        final List<Element> serverEventsElements;

        // Use request input provided by client
        final Document requestDocument = readInputAsDOM4J(pipelineContext, INPUT_REQUEST);

        // Get action
        actionElement = requestDocument.getRootElement().element(XFormsConstants.XXFORMS_ACTION_QNAME);

        // Get files if any (those come from xforms-server-submit.xpl upon submission)
        filesElement = requestDocument.getRootElement().element(XFormsConstants.XXFORMS_FILES_QNAME);

        // Get server events if any
        serverEventsElements = Dom4jUtils.elements(requestDocument.getRootElement(), XFormsConstants.XXFORMS_SERVER_EVENTS_QNAME);

        // Get events requested by the client
        final List<Element> eventElements = new ArrayList<Element>();

        // Gather server events first if any
        int serverEventsCount = 0;
        if (serverEventsElements != null && serverEventsElements.size() > 0) {
            for (Element element: serverEventsElements) {

                final Document serverEventsDocument = XFormsUtils.decodeXML(pipelineContext, element.getStringValue());
                final List<Element> xxformsEventElements = Dom4jUtils.elements(serverEventsDocument.getRootElement(), XFormsConstants.XXFORMS_EVENT_QNAME);
                serverEventsCount += xxformsEventElements.size();
                eventElements.addAll(xxformsEventElements);
            }
        }

        // Gather client events if any
        if (actionElement != null) {
            eventElements.addAll(Dom4jUtils.elements(actionElement, XFormsConstants.XXFORMS_EVENT_QNAME));
        }

        // Logger used for heartbeat and response
        final IndentedLogger indentedLogger = XFormsContainingDocument.getIndentedLogger(XFormsServer.getLogger(), XFormsServer.getLogger(), LOGGING_CATEGORY);

        // Check for message where there is only the heartbeat event
        if (eventElements.size() == 1) {
            final Element eventElement = eventElements.get(0);
            final String eventName = eventElement.attributeValue("name");
            if (eventName.equals(XFormsEvents.XXFORMS_SESSION_HEARTBEAT)) {

                // Hit session if it exists (it's probably not even necessary to do so)
                final ExternalContext externalContext = (ExternalContext) pipelineContext.getAttribute(PipelineContext.EXTERNAL_CONTEXT);
                final ExternalContext.Session session = externalContext.getSession(false);

                if (indentedLogger.isDebugEnabled()) {
                    if (session != null)
                        indentedLogger.logDebug("heartbeat", "received heartbeat from client for session: " + session.getId());
                    else
                        indentedLogger.logDebug("heartbeat", "received heartbeat from client (no session available).");
                }

                // Output simple resulting document
                try {
                    final ContentHandlerHelper ch = new ContentHandlerHelper(contentHandler);
                    ch.startDocument();
                    contentHandler.startPrefixMapping("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI);
                    ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "event-response");
                    ch.endElement();
                    contentHandler.endPrefixMapping("xxf");
                    ch.endDocument();
                } catch (SAXException e) {
                    throw new OXFException(e);
                }

                // Don't do anything else
                return;
            }
        }

        // Get static state
        final String encodedClientStaticStateString;
        {
            final Element staticStateElement = requestDocument.getRootElement().element(XFormsConstants.XXFORMS_STATIC_STATE_QNAME);
            encodedClientStaticStateString = staticStateElement.getTextTrim();
        }

        // Get dynamic state
        final String encodedClientDynamicStateString;
        {
            final Element dynamicStateElement = requestDocument.getRootElement().element(XFormsConstants.XXFORMS_DYNAMIC_STATE_QNAME);
            encodedClientDynamicStateString = dynamicStateElement.getTextTrim();
        }

        // Decode state
        final XFormsStateManager.XFormsDecodedClientState xformsDecodedClientState
                = XFormsStateManager.decodeClientState(pipelineContext, encodedClientStaticStateString, encodedClientDynamicStateString);

        // Get initial dynamic state for "all events" event if needed
        final XFormsStateManager.XFormsDecodedClientState xformsDecodedInitialClientState;
        {
            final Element initialDynamicStateElement = requestDocument.getRootElement().element(XFormsConstants.XXFORMS_INITIAL_DYNAMIC_STATE_QNAME);
            if (initialDynamicStateElement != null) {
                final String encodedClientInitialDynamicStateString = initialDynamicStateElement.getTextTrim();

                xformsDecodedInitialClientState
                    = XFormsStateManager.decodeClientState(pipelineContext, encodedClientStaticStateString, encodedClientInitialDynamicStateString);
            } else {
                xformsDecodedInitialClientState = null;
            }
        }

        // Get or create containing document
        final XFormsContainingDocument containingDocument;
        if (XFormsProperties.isCacheDocument()) {
            // Obtain containing document through cache
            containingDocument = XFormsDocumentCache.instance().find(pipelineContext, xformsDecodedClientState.getXFormsState());
        } else {
            // Otherwise we recreate the containing document from scratch
            containingDocument = new XFormsContainingDocument(pipelineContext, xformsDecodedClientState.getXFormsState());
        }

        // The synchronization is tricky: certainly, once we get a document, we don't want to allow multiple threads to
        // it at the same time as a document is clearly not thread-safe. What could happen though in theory is two Ajax
        // requests for the same document, one found in the cache, the other one not (because just expired due to other
        // thread's activity). This would create a new document and would produce unexpected results. However, the
        // client is meant to send only one Ajax request at a time, which should make this case very unlikely.

        // Another situation is that if the ContentHandler is null, then the event is the second pass of a submission
        // with replace="all" and does not modify the Containing Document. The event should in fact go straight to the
        // Submission object. It should therefore be safe not to synchronize in this case. But do we want to take the
        // risk?

//        final Object documentSynchronizationObject = (contentHandler != null) ? containingDocument : new Object();
        synchronized (containingDocument) {
            final IndentedLogger eventsIndentedLogger = containingDocument.getIndentedLogger(XFormsEvents.LOGGING_CATEGORY);
            try {
                // Run events if any
                final Map<String, String> valueChangeControlIds = new HashMap<String, String>();
                boolean allEvents = false;
                final ExternalContext externalContext = (ExternalContext) pipelineContext.getAttribute(PipelineContext.EXTERNAL_CONTEXT);
                final boolean isNoscript = XFormsProperties.isNoscript(containingDocument);

                // Set URL rewriter resource path information based on information in static state
                pipelineContext.setAttribute(PipelineContext.PATH_MATCHERS, containingDocument.getStaticState().getVersionedPathMatchers());

                if (eventElements.size() > 0) {
                    // NOTE: We store here the last xxforms-value-change-with-focus-change event so
                    // we can coalesce values in case several such events are sent for the same
                    // control. The client should not send us such series of events, but currently
                    // it may happen.
                    String lastSourceControlId = null;
                    String lastValueChangeEventValue = null;

                    // Find an output stream for xforms:submission[@replace = 'all']
                    final ExternalContext.Response response;
                    if (contentHandler != null) {
                        // If a response is written, it will be through a conversion to XML first
                        final ContentHandlerOutputStream contentHandlerOutputStream = new ContentHandlerOutputStream(contentHandler);
                        response = new ResponseAdapter() {

                            private String charset;
                            private PrintWriter printWriter;

                            public OutputStream getOutputStream() throws IOException {
                                return contentHandlerOutputStream;
                            }

                            public PrintWriter getWriter() throws IOException {
                                // Return this just because Tomcat 5.5, when doing a servlet forward, may ask for one, just to close it!
                                if (printWriter == null) {
                                    printWriter = new PrintWriter(new OutputStreamWriter(contentHandlerOutputStream, charset != null ? charset : CachedSerializer.DEFAULT_ENCODING));
                                }
                                return printWriter;
                            }

                            public void setContentType(String contentType) {
                                try {
                                    // Assume that content type is always set, otherwise this won't work
                                    charset = NetUtils.getContentTypeCharset(contentType);
                                    contentHandlerOutputStream.startDocument(contentType);
                                } catch (SAXException e) {
                                    throw new OXFException(e);
                                }
                            }

                            public Object getNativeResponse() {
                                return externalContext.getNativeResponse();
                            }

                            public void setHeader(String name, String value) {
                                // TODO: It is not sound that we output headers here as they should be passed to the
                                // binary document in the pipeline instead.
                                externalContext.getResponse().setHeader(name, value);
                            }
                        };
                    } else {
                        // We get the actual output response
                        response = externalContext.getResponse();
                    }

                    // Iterate through events to:
                    // 1. Reorder events if needed for noscript mode
                    // 2. Detect whether we got xxforms-online
                    boolean hasXXFormsOnline = false;
                    {
                        final XFormsStaticState staticState = containingDocument.getStaticState();

                        List<Element> noscriptActivateEvents = null;
                        Map<String, String> noscriptValueIds = null;

                        for (Iterator i = eventElements.iterator(); i.hasNext();) {
                            final Element eventElement = (Element) i.next();
                            final String eventName = eventElement.attributeValue("name");

                            if (XFormsEvents.XXFORMS_ONLINE.equals(eventName)) {
                                // We got an xxforms-online event
                                hasXXFormsOnline = true;
                            }

                            if (isNoscript && XFormsEvents.XXFORMS_VALUE_OR_ACTIVATE.equals(eventName)) {
                                // Special event for noscript mode
                                final String sourceControlId = eventElement.attributeValue("source-control-id");
                                if (!staticState.isValueControl(sourceControlId)) {
                                    // This is most likely a trigger or submit which will translate into a DOMActivate,
                                    // so we move it to the end so that value change events are committed to the
                                    // instance before that.
                                    i.remove();
                                    if (noscriptActivateEvents == null)
                                        noscriptActivateEvents = new ArrayList<Element>();
                                    noscriptActivateEvents.add(eventElement);
                                } else {
                                    // This is a value event, just remember the id
                                    if (noscriptValueIds == null)
                                        noscriptValueIds = new HashMap<String, String>();
                                    noscriptValueIds.put(sourceControlId, "");
                                }
                            }
                        }

                        // Special handling of checkboxes blanking in noscript mode
                        if (isNoscript) {
                            final Map<String, XFormsControl> selectFullControls = containingDocument.getControls().getCurrentControlTree().getSelectFullControls();

                            if (selectFullControls != null) {

                                for (Map.Entry<String, XFormsControl> currentEntry: selectFullControls.entrySet()) {
                                    final String currentEffectiveId = currentEntry.getKey();
                                    final XFormsSelectControl currentControl = (XFormsSelectControl) currentEntry.getValue();

                                    if (currentControl != null
                                            && (noscriptValueIds == null || noscriptValueIds.get(currentEffectiveId) == null) // control did not have a value set by other events
                                            && currentControl.isRelevant() && !currentControl.isReadonly()) {                 // control is relevant and not readonly

                                        // <xxforms:event name="xxforms-value-or-activate" source-control-id="my-effective-id"/>
                                        final Element newEventElement = Dom4jUtils.createElement(XFormsConstants.XXFORMS_EVENT_QNAME.getQualifiedName(), XFormsConstants.XXFORMS_EVENT_QNAME.getNamespaceURI());
                                        newEventElement.addAttribute("name", XFormsEvents.XXFORMS_VALUE_OR_ACTIVATE);
                                        newEventElement.addAttribute("source-control-id", currentEffectiveId);

                                        // Append the blanking event
                                        eventElements.add(newEventElement);
                                    }
                                }
                            }

                            // Append all noscript activation events
                            if (noscriptActivateEvents != null) {
                                eventElements.addAll(noscriptActivateEvents);
                            }
                        }

                        if (hasXXFormsOnline)
                            eventsIndentedLogger.logDebug("offline", "got xxforms-online event, enabling optimized handling of event sequence");
                    }

                    // Start external events
                    containingDocument.startExternalEventsSequence(pipelineContext, response, hasXXFormsOnline);
                    eventsIndentedLogger.startHandleOperation("", "handling external events");
                    
                    // Iterate through all events to dispatch them
                    int eventElementIndex = 0;
                    for (Iterator i = eventElements.iterator(); i.hasNext(); eventElementIndex++) {
                        final Element eventElement = (Element) i.next();

                        // Whether this event is trusted, that is whether this event was a server event. Server events
                        // are processed first, so are at the beginning of eventElements.
                        boolean isTrustedEvent = eventElementIndex < serverEventsCount;

                        final String eventName = eventElement.attributeValue("name");
                        final String sourceTargetId = eventElement.attributeValue("source-control-id");
                        final boolean bubbles = !"false".equals(eventElement.attributeValue("bubbles"));// default is true
                        final boolean cancelable = !"false".equals(eventElement.attributeValue("cancelable"));// default is true

                        final String otherControlId = eventElement.attributeValue("other-control-id");
                        final String dndStart = eventElement.attributeValue("dnd-start");
                        final String dndEnd = eventElement.attributeValue("dnd-end");

                        final String value = eventElement.getText();

                        if (XFormsEvents.XXFORMS_ALL_EVENTS_REQUIRED.equals(eventName)) {
                            // Special event telling us to resend the client all the events since initialization
                            if (xformsDecodedInitialClientState == null)
                                throw new OXFException("Got xxforms-all-events-required event without initial dynamic state.");

                            // Remember that we got this event
                            allEvents = true;

                        } else if (sourceTargetId != null && eventName != null) {
                            // A normal event is passed
                            if (eventName.equals(XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE) && otherControlId == null) {
                                // xxforms-value-change-with-focus-change event

                                // The goal of the code below is to coalesce multiple sequential value changes for the
                                // same control. Not sure if this is still needed.
                                if (lastSourceControlId == null) {
                                    // Remember event
                                    lastSourceControlId = sourceTargetId;
                                    lastValueChangeEventValue = value;
                                } else if (lastSourceControlId.equals(sourceTargetId)) {
                                    // Update event
                                    lastValueChangeEventValue = value;
                                } else {
                                    // Send old event
                                    containingDocument.executeExternalEvent(pipelineContext, false, XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE, lastSourceControlId,
                                            true, true, null, lastValueChangeEventValue, filesElement, null, null, hasXXFormsOnline);
                                    // Remember new event
                                    lastSourceControlId = sourceTargetId;
                                    lastValueChangeEventValue = value;
                                }
                            } else {
                                // Other normal events

                                // xxforms-offline requires initial dynamic state
                                if (eventName.equals(XFormsEvents.XXFORMS_OFFLINE) && xformsDecodedInitialClientState == null)
                                    throw new OXFException("Got xxforms-offline event without initial dynamic state.");

                                if (lastSourceControlId != null) {
                                    // Send old event
                                    containingDocument.executeExternalEvent(pipelineContext, false, XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE, lastSourceControlId,
                                            true, true, null, lastValueChangeEventValue, filesElement, null, null, hasXXFormsOnline);
                                    lastSourceControlId = null;
                                    lastValueChangeEventValue = null;
                                }
                                // Send new event
                                containingDocument.executeExternalEvent(pipelineContext, isTrustedEvent, eventName, sourceTargetId,
                                        bubbles, cancelable, otherControlId, value, filesElement, dndStart, dndEnd, hasXXFormsOnline);
                            }

                            if (eventName.equals(XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE)) {
                                // Remember id of controls for which value changed
                                valueChangeControlIds.put(sourceTargetId, "");
                            }
                        } else if (!(sourceTargetId == null && eventName == null)) {
                            // Error case
                            throw new OXFException("<event> element must either have source-control-id and name attributes, or no attribute.");
                        }
                    }
                    // Flush stored event if needed
                    if (lastSourceControlId != null) {
                        // Send old event
                        containingDocument.executeExternalEvent(pipelineContext, false, XFormsEvents.XXFORMS_VALUE_CHANGE_WITH_FOCUS_CHANGE, lastSourceControlId,
                                true, true, null, lastValueChangeEventValue, filesElement, null, null, hasXXFormsOnline);
                    }

                    // End external events
                    containingDocument.endExternalEventsSequence(pipelineContext, hasXXFormsOnline);
                    eventsIndentedLogger.endHandleOperation();

                    // Check for background asynchronous submissions
                    if (containingDocument.hasBackgroundAsynchronousSubmissions()) {
                        containingDocument.startOutermostActionHandler();
                        containingDocument.processBackgroundAsynchronousSubmissions(pipelineContext);
                        containingDocument.endOutermostActionHandler(pipelineContext);
                    }
                }

                if (contentHandler != null) {
                    // Create resulting document if there is a ContentHandler
                    if (containingDocument.isGotSubmissionReplaceAll() && (isNoscript || XFormsProperties.isAjaxPortlet(containingDocument))) {
                        // NOP: Response already sent out by a submission
                        // TODO: Something similar should also be done for submission during initialization
                        indentedLogger.logDebug("response", "handling noscript or Ajax portlet response for submission with replace=\"all\"");
                    } else if (!isNoscript) {
                        // This is an Ajax response
                        indentedLogger.startHandleOperation("response", "handling regular Ajax response");
                        outputAjaxResponse(containingDocument, indentedLogger, valueChangeControlIds, pipelineContext, contentHandler, xformsDecodedClientState, xformsDecodedInitialClientState, allEvents, false, false, false);
                        indentedLogger.endHandleOperation();
                    } else {
                        // Noscript mode
                        indentedLogger.startHandleOperation("response", "handling noscript response");
                        outputNoscriptResponse(containingDocument, indentedLogger, pipelineContext, contentHandler, xformsDecodedClientState, allEvents, externalContext);
                        indentedLogger.endHandleOperation();
                    }

                    // Process asynchronous submissions if any
                    containingDocument.processForegroundAsynchronousSubmissions();
                } else {
                    // This is the second pass of a submission with replace="all". We make it so that the document is
                    // not modified. However, we must then return it to its pool.

                    indentedLogger.logDebug("response", "handling NOP response for submission with replace=\"all\"");

                    if (XFormsProperties.isCacheDocument()) {
                        XFormsDocumentCache.instance().add(pipelineContext, xformsDecodedClientState.getXFormsState(), containingDocument);
                    }
                }
            } catch (Throwable e) {
                // If an exception is caught, we need to discard the object as its state may be inconsistent
                final ObjectPool sourceObjectPool = containingDocument.getSourceObjectPool();
                if (sourceObjectPool != null) {
                    indentedLogger.logDebug("", "containing document cache: throwable caught, discarding document from pool.");
                    try {
                        sourceObjectPool.invalidateObject(containingDocument);
                        containingDocument.setSourceObjectPool(null);
                    } catch (Exception e1) {
                        throw new OXFException(e1);
                    }
                }
                throw new OXFException(e);
            }
        }
    }

    /**
     * Output an XHTML response for the noscript mode.
     *
     * @param containingDocument            containing document
     * @param pipelineContext               pipeline context
     * @param contentHandler                content handler for the XHTML result
     * @param xformsDecodedClientState      incoming client state
     * @param allEvents                     whether to handle all events [TODO: is this needed in this mode?]
     * @param externalContext               external context
     * @throws IOException
     * @throws SAXException
     */
    private void outputNoscriptResponse(XFormsContainingDocument containingDocument, IndentedLogger indentedLogger, PipelineContext pipelineContext,
                                               ContentHandler contentHandler, XFormsStateManager.XFormsDecodedClientState xformsDecodedClientState,
                                               boolean allEvents, ExternalContext externalContext) throws IOException, SAXException {
        // This will also cache the containing document if needed
        // QUESTION: Do we actually need to cache if a xforms:submission[@replace = 'all'] happened?
        final XFormsState encodedClientState = XFormsStateManager.getEncodedClientStateDoCache(containingDocument, pipelineContext, xformsDecodedClientState, allEvents);

        final List loads = containingDocument.getLoadsToRun();
        if (loads != null && loads.size() > 0) {
            // Handle xforms:load response

            // Get first load only
            final XFormsContainingDocument.Load load = (XFormsContainingDocument.Load) loads.get(0);

            // Send redirect
            final String redirectResource = load.getResource();
            indentedLogger.logDebug("response", "handling noscript redirect response for xforms:load", "url", redirectResource);
            // Set isNoRewrite to true, because the resource is either a relative path or already contains the servlet context
            externalContext.getResponse().sendRedirect(redirectResource, null, false, false, true);

            // Still send out a null document to signal that no further processing must take place
            XMLUtils.streamNullDocument(contentHandler);
        } else {
            // Response will be Ajax response or XHTML document
            final SAXStore xhtmlDocument = containingDocument.getStaticState().getXHTMLDocument();
            if (xhtmlDocument == null)
                throw new OXFException("Missing XHTML document in static state for noscript mode.");// shouldn't happen!

            indentedLogger.logDebug("response", "handling noscript response for XHTML output");
            XFormsToXHTML.outputResponseDocument(pipelineContext, externalContext, indentedLogger, xhtmlDocument,
                    containingDocument, contentHandler, encodedClientState);
        }
    }

    /**
     * Output an Ajax response for the regular Ajax mode.
     *
     * @param containingDocument                containing document
     * @param indentedLogger                    logger
     * @param valueChangeControlIds             control ids for which the client sent a value change
     * @param pipelineContext                   pipeline context
     * @param contentHandler                    content handler for the Ajax result
     * @param xformsDecodedClientState          incoming client state
     * @param xformsDecodedInitialClientState   initial client state for all events mode
     * @param allEvents                         whether to handle all events
     * @param isOfflineEvents                   whether to output going offline information
     * @param testOutputStaticState             for testing purposes
     * @param testOutputAllActions              for testing purposes
     */
    public static void outputAjaxResponse(XFormsContainingDocument containingDocument, IndentedLogger indentedLogger,Map valueChangeControlIds,
                                PipelineContext pipelineContext, ContentHandler contentHandler, XFormsStateManager.XFormsDecodedClientState xformsDecodedClientState,
                                XFormsStateManager.XFormsDecodedClientState xformsDecodedInitialClientState,
                                boolean allEvents, boolean isOfflineEvents, boolean testOutputStaticState, boolean testOutputAllActions) {

        final ExternalContext externalContext = (ExternalContext) pipelineContext.getAttribute(PipelineContext.EXTERNAL_CONTEXT);
        final XFormsControls xformsControls = containingDocument.getControls();

        try {
            final ContentHandlerHelper ch = new ContentHandlerHelper(contentHandler);
            ch.startDocument();
            contentHandler.startPrefixMapping("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI);
            ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "event-response");

            // Compute automatic events
            boolean requireClientSubmission = false;
            String submissionServerEvents = null;
            {
                final XFormsModelSubmission activeSubmission = containingDocument.getClientActiveSubmission();
                final List<XFormsContainingDocument.Load> loads = containingDocument.getLoadsToRun();
                if (activeSubmission != null || (loads != null && loads.size() > 0)) {
                    final Document eventsDocument = Dom4jUtils.createDocument();
                    final Element eventsElement = eventsDocument.addElement(XFormsConstants.XXFORMS_EVENTS_QNAME);

                    // Check for xxforms-submit event
                    {
                        if (activeSubmission != null) {
                            final Element eventElement = eventsElement.addElement(XFormsConstants.XXFORMS_EVENT_QNAME);
                            eventElement.addAttribute("source-control-id", activeSubmission.getEffectiveId());
                            eventElement.addAttribute("name", XFormsEvents.XXFORMS_SUBMIT);
                            requireClientSubmission = true;
                        }
                    }
                    // Check for xxforms-load event (for portlet mode only!)
                    {
                        if (loads != null && loads.size() > 0) {
                            for (XFormsContainingDocument.Load load: loads) {
                                if (load.isReplace() && load.isPortletLoad() && !NetUtils.urlHasProtocol(load.getResource()) && !"resource".equals(load.getUrlType())) {
                                    // We need to submit the event so that the portlet can load the new path
                                    final Element eventElement = eventsElement.addElement(XFormsConstants.XXFORMS_EVENT_QNAME);
                                    eventElement.addAttribute("source-control-id", XFormsContainingDocument.CONTAINING_DOCUMENT_PSEUDO_ID);
                                    eventElement.setText(load.getResource());
                                    // NOTE: don't care about the target for portlets
                                    eventElement.addAttribute("name", XFormsEvents.XXFORMS_LOAD);
                                    requireClientSubmission = true;

                                    break;
                                }
                            }
                        }
                    }
                    // Encode events so that the client cannot send back arbitrary events
                    if (requireClientSubmission)
                        submissionServerEvents = XFormsUtils.encodeXML(pipelineContext, eventsDocument, false);
                }
            }

            // Rebuild and evaluate controls if needed, before we compute the dynamic state
            // NOTE: This is in case rebuilding controls modifies repeat indexes. We want the indexes to be included in the state further down.that
            if (allEvents || containingDocument.isDirtySinceLastRequest() || testOutputAllActions) {// TODO: Why do we rebuild anyway in case of allEvents?
                xformsControls.updateControlBindingsIfNeeded(pipelineContext);
                xformsControls.evaluateControlValuesIfNeeded(pipelineContext);
            }

            // Get encoded state to send to the client (unless computing the list of offline events)
            if (!isOfflineEvents) {
                // State ready to send to the client
                final XFormsState encodedClientState;
                // Whether the incoming state handling mode is different from the outgoing state handling mode
                final boolean isMustChangeStateHandling;
                if (containingDocument.goingOffline()) {
                    // We force client state if we are going offline, and do not cache as it is likely that the result won't be used soon
                    encodedClientState = XFormsStateManager.getEncryptedSerializedClientState(containingDocument, pipelineContext, xformsDecodedClientState);
                    // Outgoing mode is always "client", so in effect we are changing when the regular mode is not client
                    isMustChangeStateHandling = !xformsDecodedClientState.isClientStateHandling();
                } else {
                    // This will also cache the containing document if needed
                    encodedClientState = XFormsStateManager.getEncodedClientStateDoCache(containingDocument, pipelineContext, xformsDecodedClientState, allEvents);
                    isMustChangeStateHandling = xformsDecodedClientState.isClientStateHandling() != XFormsProperties.isClientStateHandling(containingDocument);
                }

                // Output static state when changing state handling mode, or when testing
                if (isMustChangeStateHandling || testOutputStaticState) {
                    ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "static-state", new String[] { "container-type", externalContext.getRequest().getContainerType() });
                    // NOTE: Should output static state the same way as XFormsToXHTML does, but it's just for tests for for now it's ok
                    ch.text(encodedClientState.getStaticState());
                    ch.endElement();
                }

                // Output dynamic state
                {
                    ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "dynamic-state");
                    ch.text(encodedClientState.getDynamicState());
                    ch.endElement();
                }
            }

            // Output action
            {
                final XFormsContainingDocument initialContainingDocument;
                if (!allEvents) {
                    initialContainingDocument = null;
                } else {
                    initialContainingDocument = new XFormsContainingDocument(pipelineContext, xformsDecodedInitialClientState.getXFormsState(), containingDocument.getStaticState());
                }

                ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "action");

                // Output new controls values and associated information
                final Map<String, Itemset> itemsetsFull1 = new LinkedHashMap<String, Itemset>();
                final Map<String, Itemset> itemsetsFull2 = new LinkedHashMap<String, Itemset>();
                {
                    ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "control-values");

                    if (!allEvents) {
                        // Common case

                        // Only output changes if needed
                        if (containingDocument.isDirtySinceLastRequest() || testOutputAllActions) {
                            final ControlTree currentControlTree = xformsControls.getCurrentControlTree();
                            diffControls(pipelineContext, ch, containingDocument, indentedLogger, testOutputAllActions ? null : xformsControls.getInitialControlTree().getChildren(), currentControlTree.getChildren(), itemsetsFull1, itemsetsFull2, valueChangeControlIds);
                        }
                    } else {
                        // Reload / back case: diff between current state and initial state as obtained from initial dynamic state
                        final ControlTree currentControlTree = xformsControls.getCurrentControlTree();
                        final ControlTree initialControlTree = initialContainingDocument.getControls().getCurrentControlTree();
                        diffControls(pipelineContext, ch, containingDocument, indentedLogger, initialControlTree.getChildren(), currentControlTree.getChildren(), itemsetsFull1, itemsetsFull2, null);
                    }

                    ch.endElement();
                }

                // Output repeat indexes information
                {
                    // Output index updates
                    final XFormsStaticState staticState = containingDocument.getStaticState();
                    if (!allEvents) {
                        if (!testOutputAllActions) {
                            if (containingDocument.isDirtySinceLastRequest()) {
                                // Only diff if controls are dirty
                                diffIndexState(ch, xformsControls.getInitialControlTree().getInitialMinimalRepeatIdToIndex(staticState),
                                        xformsControls.getCurrentControlTree().getMinimalRepeatIdToIndex(staticState));
                            }
                        }
                    } else {
                        final ControlTree currentControlTree = xformsControls.getCurrentControlTree();
                        final ControlTree initialControlTree = initialContainingDocument.getControls().getCurrentControlTree();
                        diffIndexState(ch, initialControlTree.getInitialMinimalRepeatIdToIndex(staticState),
                                currentControlTree.getMinimalRepeatIdToIndex(staticState));
                    }
                }

                // Output itemset information
                if (allEvents || containingDocument.isDirtySinceLastRequest() || testOutputAllActions) {
                    // Diff itemset information
                    final Map<String, Itemset> itemsetUpdate = diffItemsets(itemsetsFull1, itemsetsFull2);
                    // TODO: Handle allEvents case. Something wrong here?
                    outputItemsets(pipelineContext, ch, itemsetUpdate);
                }

                // Output server events
                if (submissionServerEvents != null) {
                    ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "server-events");
                    ch.text(submissionServerEvents);
                    ch.endElement();
                }
                {
                    final List<XFormsContainingDocument.DelayedEvent> delayedEvents = containingDocument.getDelayedEvents();
                    if (delayedEvents != null && delayedEvents.size() > 0) {
                        final long currentTime = System.currentTimeMillis();
                        for (XFormsContainingDocument.DelayedEvent delayedEvent: delayedEvents) {
                            ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "server-events",
                                    new String[] {
                                            "delay",  Long.toString(delayedEvent.getTime() - currentTime),
                                            "show-progress", Boolean.toString(delayedEvent.isShowProgress()),
                                            "progress-message", delayedEvent.isShowProgress() ? delayedEvent.getProgressMessage() : null
                                    });
                            ch.text(delayedEvent.getEncodedDocument(pipelineContext));
                            ch.endElement();
                        }
                    }
                }

                // Check if we want to require the client to perform a form submission
                if (requireClientSubmission) {
                    outputSubmissionInfo(ch, containingDocument.getClientActiveSubmission());
                }

                // TODO: the following should be ordered in the order they were requested
                // Output messages to display
                {
                    final List<XFormsContainingDocument.Message> messages = containingDocument.getMessagesToRun();
                    if (messages != null) {
                        outputMessagesInfo(ch, messages);
                    }
                }

                // Output loads
                {
                    final List<XFormsContainingDocument.Load> loads = containingDocument.getLoadsToRun();
                    if (loads != null && loads.size() > 0) {
                        outputLoadsInfo(ch, loads);
                    }
                }

                // Output scripts
                {
                    final List<XFormsContainingDocument.Script> scripts = containingDocument.getScriptsToRun();
                    if (scripts != null) {
                        outputScriptsInfo(ch, scripts);
                    }
                }

                // Output focus instruction
                {
                    final String focusEffectiveControlId = containingDocument.getClientFocusEffectiveControlId();
                    if (focusEffectiveControlId != null) {
                        outputFocusInfo(ch, focusEffectiveControlId);
                    }
                }

                // Output help instruction
                {
                    final String helpEffectiveControlId = containingDocument.getClientHelpEffectiveControlId();
                    if (helpEffectiveControlId != null) {
                        outputHelpInfo(ch, helpEffectiveControlId);
                    }
                }

                // Output go offline instruction (unless computing the list of offline events)
                {
                    if (containingDocument.goingOffline() && !isOfflineEvents) {
                        ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "offline");

                        if (xformsDecodedInitialClientState != null) {
                            // Do not run this if xformsDecodedInitialClientState is null. The general rule is that
                            // when going offline, xformsDecodedInitialClientState must not be null so we can compute
                            // the list of diffs to send to the client. However, there is one exception: going back
                            // offline in response to an Ajax request asking to go online: currently, the client is
                            // unable to pass back the initial dynamic state, so we are unable to produce the list of
                            // changes. So we just response xxforms:offline and that's it. The client must then not go
                            // back online.


                            // Send all the changes between the initial state and the time the form go offline, so when
                            // reloading the page the client can apply those changes when the page is reloaded from the
                            // client-side database.
                            final StringWriter writer = new StringWriter();
                            final TransformerHandler identity = TransformerUtils.getIdentityTransformerHandler();
                            identity.getTransformer().setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");// no XML decl.
                            identity.setResult(new StreamResult(writer));

                            // Output response into writer. We ask for all events, and passing a flag telling that we are
                            // processing offline events so as to avoid recursion

                            outputAjaxResponse(containingDocument, indentedLogger, valueChangeControlIds, pipelineContext, identity, xformsDecodedClientState, xformsDecodedInitialClientState, true, true, false, false);

                            // List of events needed to update the page from the time the page was initially sent to the
                            // client until right before sending the xxforms:offline event.
                            ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "events");
                            ch.text(writer.toString());
                            ch.endElement();

                            // List of offline bind mappings. This allows the client to perform simple handling of
                            // validation, relevance, readonly, and required for xforms:bind[@xxforms:offline = 'true'].
                            ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "mappings");
                            final String offlineBindMappings = XFormsModelBinds.getOfflineBindMappings(containingDocument);
                            ch.text(offlineBindMappings);
                            ch.endElement();
                        }

                        ch.endElement();
                    }
                }

                ch.endElement();
            }

            ch.endElement();
            contentHandler.endPrefixMapping("xxf");
            ch.endDocument();
        } catch (SAXException e) {
            throw new OXFException(e);
        }
    }

    private static void diffIndexState(ContentHandlerHelper ch, Map<String, Integer> initialRepeatIdToIndex,
                                       Map<String, Integer> currentRepeatIdToIndex) {
        if (currentRepeatIdToIndex.size() != 0) {
            boolean found = false;
            for (Map.Entry<String, Integer> currentEntry: currentRepeatIdToIndex.entrySet()) {
                final String repeatId = currentEntry.getKey();
                final Integer newIndex = currentEntry.getValue();

                // Output information if there is a difference
                final Integer oldIndex = initialRepeatIdToIndex.get(repeatId);// may be null if there was no iteration
                if (!newIndex.equals(oldIndex)) {

                    if (!found) {
                        ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "repeat-indexes");
                        found = true;
                    }

                    ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "repeat-index",
                            new String[] {"id", repeatId, "old-index", (oldIndex != null) ? oldIndex.toString() : "0", "new-index", newIndex.toString()});
                }
            }
            if (found)
                ch.endElement();
        }
    }

    public static Map<String, Itemset> diffItemsets(Map<String, Itemset> itemsetsFull1, Map<String, Itemset> itemsetsFull2) {
        Map<String, Itemset> itemsetUpdate;
        if (itemsetsFull2 == null) {
            // There is no update in the first place
            itemsetUpdate = null;
        } else if (itemsetsFull1 == null) {
            // There was nothing before, return update
            itemsetUpdate = itemsetsFull2;
        } else {
            // Merge differences
            itemsetUpdate = new LinkedHashMap<String, Itemset>();

            for (Map.Entry<String, Itemset> currentEntry: itemsetsFull2.entrySet()) {
                final String itemsetId = currentEntry.getKey();
                final Itemset newItems = currentEntry.getValue();

                final Itemset existingItems = itemsetsFull1.get(itemsetId);
                if (existingItems == null || !existingItems.equals(newItems)) {
                    // No existing items or new items are different from existing items
                    itemsetUpdate.put(itemsetId, newItems);
                }
            }
        }
        return itemsetUpdate;
    }

    public static void diffControls(PipelineContext pipelineContext, ContentHandlerHelper ch, XFormsContainingDocument containingDocument,
                                    IndentedLogger indentedLogger,
                                    List<XFormsControl> state1, List<XFormsControl> state2,
                                    Map<String, Itemset> itemsetsFull1, Map<String, Itemset> itemsetsFull2,
                                    Map valueChangeControlIds) {
        indentedLogger.startHandleOperation("", "computing differences");
        if (XFormsProperties.isOptimizeRelevance(containingDocument)) {
            new NewControlsComparator(pipelineContext, ch, containingDocument, itemsetsFull1, itemsetsFull2, valueChangeControlIds).diff(state1, state2);
        } else {
            new OldControlsComparator(pipelineContext, ch, containingDocument, itemsetsFull1, itemsetsFull2, valueChangeControlIds).diff(state1, state2);
        }
        indentedLogger.endHandleOperation();
    }

    private static void outputSubmissionInfo(ContentHandlerHelper ch, XFormsModelSubmission activeSubmission) {
//        final String clientSubmissionURL;
        final String target;

        // activeSubmission submission can be null when are running as a portlet and handling an <xforms:load>, which
        // when executed from within a portlet is ran as very much like the replace="all" submissions.
        final String activeSubmissionReplace = activeSubmission == null ? "all" : activeSubmission.getReplace();
        final String activeSubmissionResolvedXXFormsTarget = activeSubmission == null ? null : activeSubmission.getResolvedXXFormsTarget();
        final String activeSubmissionShowProgress = (activeSubmission == null || activeSubmission.isShowProgress()) ? null : "false";

        if ("all".equals(activeSubmissionReplace)) {
            // Replace all

            // TODO: Set action ("action", clientSubmissionURL,) to destination page for local submissions? (http://tinyurl.com/692f7r)
            // TODO: Should we keep the default submission path for separate deployment?
//            // The submission path is actually defined by the oxf:page-flow processor and its configuration
//            OXFProperties.PropertySet propertySet = OXFProperties.instance().getPropertySet(XMLConstants.PAGE_FLOW_PROCESSOR_QNAME);
//            final String submissionPath = propertySet.getString(PageFlowControllerProcessor.XFORMS_SUBMISSION_PATH_PROPERTY_NAME,
//                    PageFlowControllerProcessor.XFORMS_SUBMISSION_PATH_DEFAULT_VALUE);
//
//            clientSubmissionURL = externalContext.getResponse().rewriteResourceURL(submissionPath, false);
            target = activeSubmissionResolvedXXFormsTarget;
        } else {
            // Replace instance
//            clientSubmissionURL = externalContext.getRequest().getRequestURL();
            target = null;
        }

        // Signal that we want a POST to the XForms server
        ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "submission",
                new String[]{
                        "method", "POST",
                        "show-progress", activeSubmissionShowProgress,
                        "replace", activeSubmissionReplace,
                        (target != null) ? "target" : null, target
                });
    }

    private static void outputMessagesInfo(ContentHandlerHelper ch, List<XFormsContainingDocument.Message> messages) {
        for (XFormsContainingDocument.Message message: messages) {
            ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "message",
                    new String[]{"level", message.getLevel()});
            ch.text(message.getMessage());
            ch.endElement();
        }
    }

    public static void outputLoadsInfo(ContentHandlerHelper ch, List<XFormsContainingDocument.Load> loads) {
        for (XFormsContainingDocument.Load load: loads) {
            if (!(load.isReplace() && load.isPortletLoad() && !NetUtils.urlHasProtocol(load.getResource()) && !"resource".equals(load.getUrlType()))) {
                ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "load",
                        new String[]{ "resource", load.getResource(), (load.getTarget() != null) ? "target" : null, load.getTarget(), "show", load.isReplace() ? "replace" : "new", "show-progress", load.isShowProgress() ? null : "false" });
            }
        }
    }

    public static void outputScriptsInfo(ContentHandlerHelper ch, List<XFormsContainingDocument.Script> scripts) {
        for (XFormsContainingDocument.Script script: scripts) {
            ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "script",
                    new String[]{ "name", script.getFunctionName(), "target-id", script.getEventTargetId(), "observer-id", script.getEventObserverId() });
        }
    }

    private static void outputFocusInfo(ContentHandlerHelper ch, String focusEffectiveControlId) {
        ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "setfocus",
                new String[]{"control-id", focusEffectiveControlId});
    }

    private static void outputHelpInfo(ContentHandlerHelper ch, String helpEffectiveControlId) {
        ch.element("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "help",
                new String[]{"control-id", helpEffectiveControlId});
    }

    private static void outputItemsets(PipelineContext pipelineContext, ContentHandlerHelper ch,
                                       Map<String, Itemset> itemsetIdToItemsetInfoMap) {
        if (itemsetIdToItemsetInfoMap != null && itemsetIdToItemsetInfoMap.size() > 0) {
            // There are some xforms:itemset controls

            ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "itemsets");
            for (Map.Entry<String, Itemset> currentEntry: itemsetIdToItemsetInfoMap.entrySet()) {
                final String itemsetId = currentEntry.getKey();
                final Itemset itemset = currentEntry.getValue();

                ch.startElement("xxf", XFormsConstants.XXFORMS_NAMESPACE_URI, "itemset", new String[]{"id", itemsetId});
                final String result = itemset.getJSONTreeInfo(pipelineContext, null, false, null);// TODO: pass LocationData
                if (result.length() > 0)
                    ch.text(result);
                ch.endElement();
            }
            ch.endElement();
        }
    }
}
