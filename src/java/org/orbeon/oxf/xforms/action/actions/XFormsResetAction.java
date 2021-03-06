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
package org.orbeon.oxf.xforms.action.actions;

import org.dom4j.Element;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.util.PropertyContext;
import org.orbeon.oxf.xforms.XFormsContainingDocument;
import org.orbeon.oxf.xforms.XFormsModel;
import org.orbeon.oxf.xforms.XFormsUtils;
import org.orbeon.oxf.xforms.action.XFormsAction;
import org.orbeon.oxf.xforms.action.XFormsActionInterpreter;
import org.orbeon.oxf.xforms.event.XFormsEventObserver;
import org.orbeon.oxf.xforms.event.events.XFormsResetEvent;
import org.orbeon.oxf.xforms.xbl.XBLContainer;
import org.orbeon.saxon.om.Item;

/**
 * 10.1.11 The reset Element
 */
public class XFormsResetAction extends XFormsAction {
    public void execute(XFormsActionInterpreter actionInterpreter, PropertyContext propertyContext, String targetId,
                        XFormsEventObserver eventObserver, Element actionElement,
                        boolean hasOverriddenContext, Item overriddenContext) {

        final XBLContainer container = actionInterpreter.getXBLContainer();
        final XFormsContainingDocument containingDocument = actionInterpreter.getContainingDocument();

        final String modelId = XFormsUtils.namespaceId(containingDocument, actionElement.attributeValue("model"));

        final Object modelObject = container.findModelByStaticId(modelId);// TODO: FIXME do like other model-related actions
        if (modelObject instanceof XFormsModel) {
            final XFormsModel model = (XFormsModel) modelObject;
            container.dispatchEvent(propertyContext, new XFormsResetEvent(containingDocument, model));

            // "the reset action takes effect immediately and clears all of the flags."
            model.setAllDeferredFlags(false);
            containingDocument.getControls().markDirtySinceLastRequest(true);
        } else {
            throw new OXFException("xforms:reset model attribute must point to an xforms:model element.");
        }
    }
}
