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
package org.orbeon.oxf.xforms.processor.handlers;

import org.apache.commons.collections.map.CompositeMap;
import org.dom4j.Element;
import org.orbeon.oxf.common.Version;
import org.orbeon.oxf.util.IndentedLogger;
import org.orbeon.oxf.util.URLRewriterUtils;
import org.orbeon.oxf.xforms.*;
import org.orbeon.oxf.xforms.control.XFormsControl;
import org.orbeon.oxf.xforms.control.controls.XXFormsDialogControl;
import org.orbeon.oxf.xforms.event.XFormsEvents;
import org.orbeon.oxf.xforms.processor.XFormsFeatures;
import org.orbeon.oxf.xforms.processor.XFormsResourceServer;
import org.orbeon.oxf.xforms.state.XFormsStateManager;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.orbeon.oxf.xml.ElementHandlerController;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;

import java.util.*;

/**
 * Handle xhtml:head.
 */
public class XHTMLHeadHandler extends XFormsBaseHandler {

    private String formattingPrefix;

    public XHTMLHeadHandler() {
        super(false, true);
    }

    public void start(String uri, String localname, String qName, Attributes attributes) throws SAXException {

        final ContentHandler contentHandler = handlerContext.getController().getOutput();

        // Register control handlers on controller
        {
            final ElementHandlerController controller = handlerContext.getController();
            controller.registerHandler(XXFormsTextHandler.class.getName(), XFormsConstants.XXFORMS_NAMESPACE_URI, "text");
        }

        // Declare xmlns:f
        formattingPrefix = handlerContext.findFormattingPrefixDeclare();

        // Open head element
        contentHandler.startElement(uri, localname, qName, attributes);

        final ContentHandlerHelper helper = new ContentHandlerHelper(contentHandler);
        final String xhtmlPrefix = XMLUtils.prefixFromQName(qName); // current prefix for XHTML

        // Gather information about appearances of controls which use Script
        // Map<String controlName, Map<String appearanceOrMediatype, List<String effectiveId>>>
        // TODO: This would probably be done better, and more correctly, based on statically-gathered information in XFormsStaticState
        final Map<String, Map<String, List<String>>> javaScriptControlsAppearancesMap = new HashMap<String, Map<String, List<String>>>();
        {
            final XFormsControls xformsControls = containingDocument.getControls();
            xformsControls.visitAllControls(new XFormsControls.XFormsControlVisitorListener() {
                public void startVisitControl(XFormsControl control) {
                    final String controlName = control.getName();

                    // Don't run JavaScript initialization if the control is static readonly (could change in the
                    // future if some static readonly controls require JS initialization)
                    final boolean hasJavaScriptInitialization = control.hasJavaScriptInitialization() && !control.isStaticReadonly();
                    if (hasJavaScriptInitialization) {
                        Map<String, List<String>> listForControlNameMap = javaScriptControlsAppearancesMap.get(controlName);
                        if (listForControlNameMap == null) {
                            listForControlNameMap = new HashMap<String, List<String>>();
                            javaScriptControlsAppearancesMap.put(control.getName(), listForControlNameMap);
                        }
                        final String controlAppearanceOrMediatype;
                        {
                            final String controlAppearance = control.getAppearance();
                            controlAppearanceOrMediatype = (controlAppearance != null) ? controlAppearance : control.getMediatype();
                        }

                        List<String> idsForAppearanceOrMediatypeList = listForControlNameMap.get(controlAppearanceOrMediatype);
                        if (idsForAppearanceOrMediatypeList == null) {
                            idsForAppearanceOrMediatypeList = new ArrayList<String>();
                            listForControlNameMap.put(controlAppearanceOrMediatype, idsForAppearanceOrMediatypeList);
                        }
                        idsForAppearanceOrMediatypeList.add(control.getEffectiveId());
                    }
                }

                public void endVisitControl(XFormsControl xformsControl) {}
            });
        }

        // Create prefix for combined resources if needed
        final boolean isMinimal = XFormsProperties.isMinimalResources(containingDocument);
        final boolean isVersionedResources = URLRewriterUtils.isResourcesVersioned();
        final String combinedResourcesPrefix = XFormsFeatures.getCombinedResourcesPrefix(containingDocument, javaScriptControlsAppearancesMap, isMinimal, isVersionedResources);

        final boolean isCombineResources = XFormsProperties.isCombinedResources(containingDocument);
        final boolean isCacheCombinedResources = isCombineResources && XFormsProperties.isCacheCombinedResources();

        final IndentedLogger resourcesIndentedLogger = XFormsResourceServer.getIndentedLogger();
        if (isCombineResources) {
            resourcesIndentedLogger.logDebug("", "creating xhtml:head with combined resources");
            if (isCacheCombinedResources) {
                resourcesIndentedLogger.logDebug("", "attempting to cache combined resources");
            }
        }

        // Stylesheets
        final AttributesImpl attributesImpl = new AttributesImpl();
        {
            // Main CSS resources
            if (isCombineResources) {
                final String combinedResourceName = combinedResourcesPrefix + ".css";

                attributesImpl.clear();
                final String[] attributesList = new String[] { "rel", "stylesheet", "href", combinedResourceName, "type", "text/css", "media", "all" };
                ContentHandlerHelper.populateAttributes(attributesImpl, attributesList);
                helper.element(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "link", attributesImpl);

                if (isCacheCombinedResources) {
                    // Attempt to cache combined resources
                    // Do it at this point so that deployments using an HTTP server front-end can access the resource on disk directly
                    final List<XFormsFeatures.ResourceConfig> resources = XFormsFeatures.getCSSResources(containingDocument, javaScriptControlsAppearancesMap);
                    final long combinedLastModified = XFormsResourceServer.computeCombinedLastModified(resources, isMinimal);
                    XFormsResourceServer.cacheResources(resourcesIndentedLogger, resources, pipelineContext, combinedResourceName, combinedLastModified, true, isMinimal);
                }
            } else {
                for (final XFormsFeatures.ResourceConfig resourceConfig: XFormsFeatures.getCSSResources(containingDocument, javaScriptControlsAppearancesMap)) {
                    // Only include stylesheet if needed
                    attributesImpl.clear();
                    final String[] attributesList = new String[]{"rel", "stylesheet", "href", resourceConfig.getResourcePath(isMinimal), "type", "text/css", "media", "all"};
                    ContentHandlerHelper.populateAttributes(attributesImpl, attributesList);
                    helper.element(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "link", attributesImpl);
                }
            }

            // XBL resources
            final List<Element> xblStyles = containingDocument.getStaticState().getXblBindings().getXBLStyles();
            if (xblStyles != null) {
                for (final Element styleElement: xblStyles) {
                    attributesImpl.clear();
                    if (styleElement.attributeValue("src") != null) {
                        // xhtml:link
                        final String[] attributesList = new String[]{"rel", "stylesheet", "href", styleElement.attributeValue("src"), "type", "text/css", "media", styleElement.attributeValue("media")};
                        ContentHandlerHelper.populateAttributes(attributesImpl, attributesList);
                        helper.element(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "link", attributesImpl);
                    } else {
                        // xhtml:style
                        final String[] attributesList = new String[]{"rel", "stylesheet", "type", "text/css", "media", styleElement.attributeValue("media")};
                        ContentHandlerHelper.populateAttributes(attributesImpl, attributesList);
                        helper.startElement(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "style", attributesImpl);
                        helper.text(styleElement.getText());
                        helper.endElement();
                    }
                }
            }
        }

        // Scripts
        // TODO: Have option to put this at the bottom of the page. See theme-plain.xsl and http://developer.yahoo.com/performance/rules.html#js_bottom -->
        if (!handlerContext.isNoScript() && !XFormsProperties.isReadonly(containingDocument)) {

            // Main JavaScript resources
            if (isCombineResources) {
                final String combinedResourceName = combinedResourcesPrefix + ".js";

                attributesImpl.clear();
                final String[] attributesList = new String[] { "type", "text/javascript", "src", combinedResourceName };
                ContentHandlerHelper.populateAttributes(attributesImpl, attributesList);
                helper.element(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "script", attributesImpl);

                if (isCacheCombinedResources) {
                    // Attempt to cache combined resources
                    // Do it at this point so that deployments using an HTTP server front-end can access the resource on disk directly
                    final List<XFormsFeatures.ResourceConfig> resources = XFormsFeatures.getJavaScriptResources(containingDocument, javaScriptControlsAppearancesMap);
                    final long combinedLastModified = XFormsResourceServer.computeCombinedLastModified(resources, isMinimal);
                    XFormsResourceServer.cacheResources(resourcesIndentedLogger, resources, pipelineContext, combinedResourceName, combinedLastModified, false, isMinimal);
                }

            } else {
                for (final XFormsFeatures.ResourceConfig resourceConfig: XFormsFeatures.getJavaScriptResources(containingDocument, javaScriptControlsAppearancesMap)) {
                    // Only include stylesheet if needed
                    attributesImpl.clear();
                    final String[] attributesList = new String[]{"type", "text/javascript", "src", resourceConfig.getResourcePath(isMinimal)};
                    ContentHandlerHelper.populateAttributes(attributesImpl, attributesList);
                    helper.element(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "script", attributesImpl);
                }
            }

            // XBL scripts
            final List<Element> xblScripts = containingDocument.getStaticState().getXblBindings().getXBLScripts();
            if (xblScripts != null) {
                for (final Element scriptElement: xblScripts) {
                    attributesImpl.clear();
                    if (scriptElement.attributeValue("src") != null) {
                        // xhtml:script with @src
                        final String[] attributesList = new String[]{"type", "text/javascript", "src", scriptElement.attributeValue("src")};
                        ContentHandlerHelper.populateAttributes(attributesImpl, attributesList);
                        helper.element(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "script", attributesImpl);
                    } else {
                        // xhtml:script without @src
                        final String[] attributesList = new String[]{"type", "text/javascript"};
                        ContentHandlerHelper.populateAttributes(attributesImpl, attributesList);
                        helper.startElement(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "script", attributesImpl);
                        helper.text(scriptElement.getText());
                        helper.endElement();
                    }
                }
            }

            // Configuration properties
            {
                final Map clientPropertiesMap;
                {
                    // Dynamic properties
                    final Map<String, Object> dynamicProperties = new HashMap<String, Object>();
                    {
                        // Heartbeat delay
                        {
                            final XFormsProperties.PropertyDefinition propertyDefinition = XFormsProperties.getPropertyDefinition(XFormsProperties.SESSION_HEARTBEAT_DELAY_PROPERTY);
                            final long heartbeatDelay = XFormsStateManager.getHeartbeatDelay(containingDocument, handlerContext.getExternalContext());
                            if (heartbeatDelay != ((Number) propertyDefinition.getDefaultValue()).longValue())
                                dynamicProperties.put(XFormsProperties.SESSION_HEARTBEAT_DELAY_PROPERTY, heartbeatDelay);
                        }

                        // Produce JavaScript paths for use on the client
                        {
                            // FCKeditor path
                            {
                                final XFormsProperties.PropertyDefinition propertyDefinition = XFormsProperties.getPropertyDefinition(XFormsProperties.FCK_EDITOR_BASE_PATH_PROPERTY);
                                final String fckEditorPath = isVersionedResources ? "/" + Version.getVersion() + propertyDefinition.getDefaultValue() : (String) propertyDefinition.getDefaultValue();
                                if (!fckEditorPath.equals(propertyDefinition.getDefaultValue()))
                                    dynamicProperties.put(XFormsProperties.FCK_EDITOR_BASE_PATH_PROPERTY, fckEditorPath);
                            }
                        }

                        // Help events
                        {
                            final boolean hasHandlerForXFormsHelp = containingDocument.getControls().hasHandlerForEvent(XFormsEvents.XFORMS_HELP);
                            if (hasHandlerForXFormsHelp) {
                                dynamicProperties.put(XFormsProperties.HELP_HANDLER_PROPERTY, Boolean.TRUE);
                            }
                        }

                        // Application version
                        {
                            // This is not an XForms property but we want to expose it on the client
                            if (isVersionedResources != URLRewriterUtils.RESOURCES_VERSIONED_DEFAULT)
                                dynamicProperties.put(URLRewriterUtils.RESOURCES_VERSIONED_PROPERTY, Boolean.toString(isVersionedResources));

                            if (isVersionedResources) {
                                final String applicationVersion = URLRewriterUtils.getApplicationResourceVersion();
                                if (applicationVersion != null) {
                                    // This is not an XForms property but we want to expose it on the client 
                                    dynamicProperties.put(URLRewriterUtils.RESOURCES_VERSION_NUMBER_PROPERTY, applicationVersion);
                                }
                            }
                        }

                        // Offline mode
//                        if (containingDocument.getStaticState().isHasOfflineSupport()) {
//                            dynamicProperties.put(XFormsProperties.OFFLINE_SUPPORT_PROPERTY, Boolean.TRUE);
//                        }
                    }

                    final Map<String, Object> nonDefaultProperties = containingDocument.getStaticState().getNonDefaultProperties();
                    clientPropertiesMap = new CompositeMap(new Map[] { nonDefaultProperties, dynamicProperties });
                }
                
                StringBuilder sb = null;
                if (clientPropertiesMap.size() > 0) {

                    for (Object o: clientPropertiesMap.entrySet()) {
                        final Map.Entry currentEntry = (Map.Entry) o;
                        final String propertyName = (String) currentEntry.getKey();
                        final Object propertyValue = currentEntry.getValue();

                        final XFormsProperties.PropertyDefinition propertyDefinition = XFormsProperties.getPropertyDefinition(propertyName);
                        if (propertyDefinition != null && propertyDefinition.isPropagateToClient()
                                || URLRewriterUtils.RESOURCES_VERSION_NUMBER_PROPERTY.equals(propertyName)
                                || URLRewriterUtils.RESOURCES_VERSIONED_PROPERTY.equals(propertyName)) {

                            if (sb == null) {
                                // First property found
                                helper.startElement(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "script", new String[]{
                                        "type", "text/javascript"});
                                sb = new StringBuilder("var opsXFormsProperties = {");
                            } else {
                                // Subsequent property found
                                sb.append(',');
                            }

                            sb.append('\"');
                            sb.append(propertyName);
                            sb.append("\":");
                            if (propertyValue instanceof String) {
                                // This is a string, add quotes
                                sb.append('\"');
                                sb.append(propertyValue.toString());
                                sb.append('\"');
                            } else {
                                // Don't need quotes
                                sb.append(propertyValue.toString());
                            }
                        }
                    }

                    if (sb != null) {
                        // Close everything
                        sb.append("};");
                        helper.text(sb.toString());
                        helper.endElement();
                    }
                }
            }

            // User-defined scripts (with xxforms:script)
            final Map<String, String> scriptsToDeclare = containingDocument.getScripts();
            final String focusElementId = containingDocument.getClientFocusEffectiveControlId();
            final List<XFormsContainingDocument.Message> messagesToRun = containingDocument.getMessagesToRun();
            final List<XXFormsDialogControl> dialogsToOpen = new ArrayList<XXFormsDialogControl>(); {
                final Map<String, XFormsControl> dialogsMap = containingDocument.getControls().getCurrentControlTree().getDialogControls();
                if (dialogsMap != null && dialogsMap.size() > 0) {
                    for (final XFormsControl control: dialogsMap.values()) {
                        final XXFormsDialogControl dialogControl = (XXFormsDialogControl) control;
                        if (dialogControl.isVisible()) {
                            dialogsToOpen.add(dialogControl);
                        }
                    }
                }
            }
            if (scriptsToDeclare != null || focusElementId != null || messagesToRun != null || dialogsToOpen.size() > 0) {
                helper.startElement(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "script", new String[] {
                    "type", "text/javascript"});

                if (scriptsToDeclare != null) {
                    for (final Map.Entry<String, String> currentEntry: scriptsToDeclare.entrySet()) {
                        helper.text("\nfunction " + XFormsUtils.scriptIdToScriptName(currentEntry.getKey()) + "(event) {\n");
                        helper.text(currentEntry.getValue());
                        helper.text("}\n");
                    }
                }

                final List<XFormsContainingDocument.Script> scriptsToRun = containingDocument.getScriptsToRun();

                if (scriptsToRun != null || focusElementId != null || messagesToRun != null || dialogsToOpen.size() > 0) {
                    final StringBuilder sb = new StringBuilder("\nfunction xformsPageLoadedServer() { ");

                    // Initial setfocus if present
                    if (focusElementId != null) {
                        sb.append("ORBEON.xforms.Controls.setFocus(\"");
                        sb.append(focusElementId);
                        sb.append("\");");
                    }

                    // Initial xxforms:script executions if present
                    if (scriptsToRun != null) {
                        for (final XFormsContainingDocument.Script script: scriptsToRun) {
                            sb.append("ORBEON.xforms.Server.callUserScript(\"");
                            sb.append(script.getFunctionName());
                            sb.append("\",\"");
                            sb.append(script.getEventTargetId());
                            sb.append("\",\"");
                            sb.append(script.getEventObserverId());
                            sb.append("\");");
                        }
                    }

                    // Initial xforms:message to run if present
                    if (messagesToRun != null) {
                        for (final XFormsContainingDocument.Message message: messagesToRun) {
                            if ("modal".equals(message.getLevel())) {
                                // TODO: should not call directly alert() but a client-side method
                                sb.append("alert(\"");
                                sb.append(XFormsUtils.escapeJavaScript(message.getMessage()));
                                sb.append("\");");
                            }
                        }
                    }

                    // Initial dialogs to open
                    if (dialogsToOpen.size() > 0) {
                        for (final XXFormsDialogControl dialogControl: dialogsToOpen) {
                            sb.append("ORBEON.xforms.Controls.showDialog(\"");
                            sb.append(dialogControl.getEffectiveId());
                            sb.append("\", ");
                            if (dialogControl.getNeighborControlId() != null) {
                                sb.append('"');
                                sb.append(dialogControl.getNeighborControlId());
                                sb.append('"');
                            } else {
                                sb.append("null");
                            }
                            sb.append(");");
                        }
                    }

                    sb.append(" }");

                    helper.text(sb.toString());
                }

                helper.endElement();
            }

            // Store information about "special" controls that need JavaScript initialization
            {
                helper.startElement(xhtmlPrefix, XMLConstants.XHTML_NAMESPACE_URI, "script", new String[] {
                        "type", "text/javascript"});

                // Produce JSON output
                if (javaScriptControlsAppearancesMap.size() > 0) {
                    final StringBuilder sb = new StringBuilder("var opsXFormsControls = {\"controls\":{");

                    for (Iterator<Map.Entry<String,Map<String,List<String>>>> i = javaScriptControlsAppearancesMap.entrySet().iterator(); i.hasNext();) {
                        final Map.Entry<String,Map<String,List<String>>> currentEntry1 = i.next();
                        final String controlName = currentEntry1.getKey();
                        final Map<String, List<String>> controlMap = currentEntry1.getValue();

                        sb.append("\"");
                        sb.append(controlName);
                        sb.append("\":{");

                        for (Iterator<Map.Entry<String,List<String>>> j = controlMap.entrySet().iterator(); j.hasNext();) {
                            final Map.Entry<String,List<String>> currentEntry2 = j.next();
                            final String controlAppearance = currentEntry2.getKey();
                            final List<String> idsForAppearanceList = currentEntry2.getValue();

                            sb.append('"');
                            sb.append(controlAppearance != null ? controlAppearance : "");
                            sb.append("\":[");

                            for (Iterator<String> k = idsForAppearanceList.iterator(); k.hasNext();) {
                                final String controlId = k.next();
                                sb.append('"');
                                sb.append(controlId);
                                sb.append('"');
                                if (k.hasNext())
                                    sb.append(',');
                            }

                            sb.append(']');
                            if (j.hasNext())
                                sb.append(',');
                        }

                        sb.append("}");
                        if (i.hasNext())
                            sb.append(',');
                    }

                    sb.append("}};");

                    helper.text(sb.toString());
                }

                helper.endElement();
            }
        }
    }

    public void end(String uri, String localname, String qName) throws SAXException {

        // Close head element
        final ContentHandler contentHandler = handlerContext.getController().getOutput();
        contentHandler.endElement(uri, localname, qName);

        // Undeclare xmlns:f
        handlerContext.findFormattingPrefixUndeclare(formattingPrefix);
    }
}
