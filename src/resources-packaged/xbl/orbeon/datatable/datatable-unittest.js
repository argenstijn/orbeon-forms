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

var accordionAccessMethod = "api";

YAHOO.tool.TestRunner.add(new YAHOO.tool.TestCase({


    name: "datatable",

    isOpenAccordionCase: function(targetId) {
        var dd = YAHOO.util.Dom.get('my-accordion$d-' + targetId);
        return YAHOO.util.Dom.hasClass(dd, 'a-m-d-expand');
    },

    toggleAccordionCase: function (targetId) {
        var dt = YAHOO.util.Dom.get('my-accordion$t-' + targetId);
        YAHOO.util.UserAction.click(dt, {clientX: 1});
    },

    openAccordionCase: function (targetId, callback) {
        if (accordionAccessMethod == 'css') {

            var dt = YAHOO.util.Dom.get('my-accordion$t-' + targetId);
            var dd = YAHOO.util.Dom.get('my-accordion$d-' + targetId);
            YAHOO.util.Dom.addClass(dt, 'a-m-d-expand');
            YAHOO.util.Dom.addClass(dd, 'a-m-d-expand');

        } else if (accordionAccessMethod == 'api') {

            var dl = YAHOO.util.Dom.get('my-accordion$dl');
            var dt = YAHOO.util.Dom.get('my-accordion$t-' + targetId);
            var dd = YAHOO.util.Dom.get('my-accordion$d-' + targetId);
            AccordionMenu.expandCase(dl, dt, dd);

        } else /* click */ {

            if (!this.isOpenAccordionCase(targetId)) {
                this.toggleAccordionCase(targetId);
            }
        }

        // Check if the action has been done and call back
        if (this.isOpenAccordionCase(targetId)) {
            if (callback) {
                callback.call();
            }
        } else {
            this.wait(function() {
                this.openAccordionCase(targetId, callback);
            }, 10);
        }
    },

    closeAccordionCase: function (targetId, callback) {
        if (accordionAccessMethod == 'css') {

            var dt = YAHOO.util.Dom.get('my-accordion$t-' + targetId);
            var dd = YAHOO.util.Dom.get('my-accordion$d-' + targetId);
            YAHOO.util.Dom.removeClass(dt, 'a-m-d-expand');
            YAHOO.util.Dom.removeClass(dd, 'a-m-d-expand');

        } else if (accordionAccessMethod == 'api') {

            var dl = YAHOO.util.Dom.get('my-accordion$dl');
            var dt = YAHOO.util.Dom.get('my-accordion$t-' + targetId);
            var dd = YAHOO.util.Dom.get('my-accordion$d-' + targetId);
            AccordionMenu.collapseCase(dl, dt, dd);

        } else /* click */ {
            if (this.isOpenAccordionCase(targetId)) {
                this.toggleAccordionCase(targetId);
            }
        }

        // Check if the action has been done and call back
        if (!this.isOpenAccordionCase(targetId)) {
            if (callback) {
                callback.call();
            }
        } else {
            this.wait(function() {
                this.closeAccordionCase(targetId, callback);
            }, 10);
        }
    },

    checkCellWidth: function(cell) {
        var resizerliner = ORBEON.widgets.datatable.utils.getFirstChildByTagAndClassName(cell, 'div', 'yui-dt-resizerliner');
        var liner;
        if (resizerliner == null) {
            var liner = ORBEON.widgets.datatable.utils.getFirstChildByTagAndClassName(cell, 'div', 'yui-dt-liner');
        } else {
            var liner = ORBEON.widgets.datatable.utils.getFirstChildByTagAndClassName(resizerliner, 'div', 'yui-dt-liner');
        }
        var cssWidth = YAHOO.util.Dom.getStyle(liner, 'width');
        var actualWidth = (liner.clientWidth - 20) + "px";
        YAHOO.util.Assert.areEqual(cssWidth, actualWidth, 'CSS (' + cssWidth + ') and actual (' + liner.clientWidth + 'px) width should differ by exactly 20 px.');
    },

    checkRowWidth: function(row) {
        for (var icol = 0; icol < row.cells.length; icol++) {
            var cell = row.cells[icol];
            this.checkCellWidth(cell);
        }
    },

    checkTableAndContainerWidths: function(table) {
        var tableWidth = YAHOO.util.Dom.getStyle(table, 'width');
        var tableWidthValue = parseInt(tableWidth.substr(0, tableWidth.length - 2)) ;
        var headerContainerWidth = YAHOO.util.Dom.getStyle(table.parentNode, 'width');
        var mainContainerWidth = YAHOO.util.Dom.getStyle(table.parentNode.parentNode, 'width');
        if (headerContainerWidth == 'auto')  {
            headerContainerWidth = tableWidth;
        }
        var mainContainerWidthValue = parseInt(mainContainerWidth.substr(0, mainContainerWidth.length - 2)) ;

        YAHOO.util.Assert.areEqual(tableWidthValue + 2, mainContainerWidthValue, 'Table (' + tableWidth + ') and main container (' + mainContainerWidth + ') widths should differ by 2 pixels.');
        YAHOO.util.Assert.areEqual(headerContainerWidth, tableWidth, 'Header (' + headerContainerWidth + ') and table (' + tableWidth + ') widths should be equal.');
    },

    resizeColumn: function(th, offset, step) {
        var resizerliner = ORBEON.widgets.datatable.utils.getFirstChildByTagAndClassName(th, 'div', 'yui-dt-resizerliner');
        var resizer = ORBEON.widgets.datatable.utils.getFirstChildByTagAndClassName(resizerliner, 'div', 'yui-dt-resizer');
        var region = YAHOO.util.Region.getRegion(resizer);
        YAHOO.util.UserAction.mousedown(resizer, {clientX: region.right, clientY: region.top});
        var x;
        if (step == undefined) {
            step = offset;
        } else if (step * offset < 0) {
            step = -step;
        }
        for (x = region.right; (offset < 0 && x >= region.right + offset) || (offset > 0 && x <= region.right + offset); x = x + step) {
            YAHOO.util.UserAction.mousemove(resizer, {clientX: x, clientY: region.top});
        }
        YAHOO.util.UserAction.mouseup(resizer, {clientX: x, clientY: region.top});
        return x - region.right;
    },

    checkHorizontalScrollbar: function (elt) {
        YAHOO.util.Assert.isTrue(elt.clientHeight + 15 < elt.offsetHeight, 'Element has no horizontal scroll bar (clientHeight: ' + elt.clientHeight + ', offsetHeight: ' + elt.offsetHeight + ')');
    },

    checkEmbeddedWidthAndHeight: function (elt, parentWidth, parentHeight) {
        var region =  YAHOO.util.Region.getRegion(elt);
        var width;
        var height;
        if (region != null && region.right != null && region.left != null) {
            width = region.right - region.left;
            height = region.bottom - region.top;
        } else {
            width = parentWidth;
            height = parentHeight ;
        }
  
        if (parentWidth != undefined) {
            YAHOO.util.Assert.isTrue(parentWidth >= width, 'Node ' + elt.nodeName + '.' + elt.className + ', width (' + width + ") is larger than its parent's " + elt.parentNode.nodeName + '.' + elt.parentNode.className + " width (" + parentWidth + ')');
        }
        if (parentHeight != undefined) {
            YAHOO.util.Assert.isTrue(parentHeight >= height, 'Node ' + elt.nodeName + '.' + elt.className + ', height (' + height + ") is larger than its parent's " + elt.parentNode.nodeName + '.' + elt.parentNode.className + " height (" + parentHeight + ')');
        }
        var children = elt.childNodes;
        for (var i = 0; i < children.length; i++) {
            var child = children[i];
            if (child.nodeType == 1) {
                this.checkEmbeddedWidthAndHeight(child, width, height);
            }
        }
    },



    test314217: function() {
        var thiss = this;
        thiss.openAccordionCase('_314217', function() {
            var tbody = YAHOO.util.Dom.get('my-accordion$table-314217$table-314217-tbody');
            var bodyContainer = tbody.parentNode.parentNode;
            thiss.checkHorizontalScrollbar(bodyContainer);
            thiss.closeAccordionCase('_314217');
        });
    },

    testWidths: function() {
        var thiss = this;
        this.openAccordionCase('widths', function() {
            var table = YAHOO.util.Dom.get('my-accordion$table-widths$table-widths-table');
            thiss.checkRowWidth(table.tHead.rows[0]);
            thiss.checkTableAndContainerWidths(YAHOO.util.Dom.get('my-accordion$table-widths$table-widths-table'));
            thiss.closeAccordionCase('widths');
        });
    },

    testWidthsResizeable: function() {
        var thiss = this;
        this.openAccordionCase('widths-resizeable', function() {
            var table = YAHOO.util.Dom.get('my-accordion$table-widths-resizeable$table-widths-resizeable-table');
            thiss.checkRowWidth(table.tHead.rows[0]);
            thiss.checkTableAndContainerWidths(table);
            thiss.closeAccordionCase('widths-resizeable');
        });
    },

    testWidthsResizeable100pxRight: function() {
        var thiss = this;
        this.openAccordionCase('widths-resizeable', function() {
            var table = YAHOO.util.Dom.get('my-accordion$table-widths-resizeable$table-widths-resizeable-table');
            var th1 = table.tHead.rows[0].cells[0];
            var th2 = table.tHead.rows[0].cells[1];
            var width1 = th1.clientWidth;
            var width2 = th2.clientWidth;
            thiss.resizeColumn(th2, 100, 10);
            thiss.checkTableAndContainerWidths(table);
            YAHOO.util.Assert.areEqual(width1, th1.clientWidth, "The width of the first column shouldn't change (before: " + width1 + ", after: " + width2 + ").");
            YAHOO.util.Assert.areEqual(width2 + 100, th2.clientWidth, "The width of the second column should be " + (width2 + 100) + ", not " + th2.clientWidth);
            thiss.checkRowWidth(table.tHead.rows[0]);
            thiss.closeAccordionCase('widths-resizeable');
        });
    },

    testWidthsResizeable100pxLeft: function() {
        var thiss = this;
        this.openAccordionCase('widths-resizeable', function() {
            var table = YAHOO.util.Dom.get('my-accordion$table-widths-resizeable$table-widths-resizeable-table');
            var th1 = table.tHead.rows[0].cells[0];
            var th2 = table.tHead.rows[0].cells[1];
            var width1 = th1.clientWidth;
            var width2 = th2.clientWidth;
            thiss.resizeColumn(th2, -100, 10);
            thiss.checkTableAndContainerWidths(table);
            YAHOO.util.Assert.areEqual(width1, th1.clientWidth, "The wdith of the first column shouldn't change (before: " + width1 + ", after: " + width2 + ").");
            YAHOO.util.Assert.areEqual(width2 - 100, th2.clientWidth, "The width of the second column should be " + (width2 - 100) + ", not " + th2.clientWidth);
            thiss.checkRowWidth(table.tHead.rows[0]);
            thiss.closeAccordionCase('widths-resizeable');
        });
    },

    testWidthsResizeable10MorePxLeft: function() {
        var thiss = this;
        this.openAccordionCase('widths-resizeable', function() {
            var table = YAHOO.util.Dom.get('my-accordion$table-widths-resizeable$table-widths-resizeable-table');
            var th1 = table.tHead.rows[0].cells[0];
            var th2 = table.tHead.rows[0].cells[1];
            var width1 = th1.clientWidth;
            var width2 = th2.clientWidth;
            thiss.resizeColumn(th2, -10);
            thiss.checkTableAndContainerWidths(table);
            thiss.checkRowWidth(table.tHead.rows[0]);
            YAHOO.util.Assert.areEqual(width1, th1.clientWidth, "The wdith of the first column shouldn't change (before: " + width1 + ", after: " + width2 + ").");
            YAHOO.util.Assert.areEqual(width2 - 10, th2.clientWidth, "The width of the second column should be " + (width2 - 10) + ", not " + th2.clientWidth);
            thiss.closeAccordionCase('widths-resizeable');
        });
    },

    test314216: function() {
        var thiss = this;
        this.openAccordionCase('_314216', function() {
            var th = YAHOO.util.Dom.get('my-accordion$table-314216$th-314216-2');
            var resizerliner = ORBEON.widgets.datatable.utils.getFirstChildByTagAndClassName(th, 'div', 'yui-dt-resizerliner');
            var liner = ORBEON.widgets.datatable.utils.getFirstChildByTagAndClassName(resizerliner, 'div', 'yui-dt-liner');
            var resizer = ORBEON.widgets.datatable.utils.getFirstChildByTagAndClassName(resizerliner, 'div', 'yui-dt-resizer');
            thiss.resizeColumn(th, -100, 5);
            YAHOO.util.Assert.isTrue(th.clientWidth > 0, 'The column width should be greater than 0, not ' + th.clientWidth);
            thiss.checkTableAndContainerWidths(YAHOO.util.Dom.get('my-accordion$table-314216$table-314216-table'));
            thiss.checkCellWidth(th);
            thiss.closeAccordionCase('_314216');
        });
    },

    test314209: function() {
        var thiss = this;
        this.closeAccordionCase('_314209', function() {
            thiss.openAccordionCase('_314209', function() {
                var table = YAHOO.util.Dom.get('my-accordion$table-314209$table-314209-table');
                var visibility = YAHOO.util.Dom.getStyle(table, 'visibility');
                YAHOO.util.Assert.isTrue(visibility == 'visible' || visibility == 'inherit', 'Visibility should be visible or inherit, not ' + visibility);
                // unfortunately, I haven't found any way to check that the table is actually visible!
                thiss.closeAccordionCase('_314209');
            });

        });
    },

    test314211: function() {
        var thiss = this;
        this.openAccordionCase('_314211', function() {
            var table = YAHOO.util.Dom.get('my-accordion$table-314211$table-314211-table');
            YAHOO.util.Assert.isTrue(table.clientWidth < 300, 'The table width (' + table.clientWidth + "px) should be small, let's say < 300px...");
            thiss.closeAccordionCase('_314211');

        });
    },

    test314174: function() {
        this.openAccordionCase('_314174');
        //TODO: test something here!
        this.closeAccordionCase('_314174');
    },

    test314210: function() {
        var thiss = this;
        this.openAccordionCase('_314210', function() {
            var headerTable = YAHOO.util.Dom.get('my-accordion$table-314210$table-314210-table');
            YAHOO.util.Assert.isTrue(headerTable.clientWidth > headerTable.parentNode.clientWidth, 'The table header width (' + headerTable.clientWidth + 'px) should be larger than its container width (' + headerTable.parentNode.clientWidth + 'px).');
            thiss.closeAccordionCase('_314210');
        });
    },

    test314292: function() {
        var thiss = this;
        thiss.openAccordionCase('_314292', function() {
            window.resizeBy(-50, 0);
            thiss.wait(function() {
                var table = YAHOO.util.Dom.get('my-accordion$table-314292$table-314292-table');
                // The following test seems to detect the root cause of this bug
                thiss.checkEmbeddedWidthAndHeight(table.parentNode.parentNode);
                thiss.checkTableAndContainerWidths(table);
                var tableX = YAHOO.util.Dom.getX(table);
                var containerX = YAHOO.util.Dom.getX(table.parentNode.parentNode);
                // The next one actually checks that the table does not overlap the border of the main container
                // but is isn't 100% reliable
                // YAHOO.util.Assert.areEqual(containerX, tableX - 1, 'The table left (' + tableX + ") should be 1 px right to the container left (" + containerX + ')');
                window.resizeBy(50, 0);
                thiss.closeAccordionCase('_314292');
            }, 500);

        });
    },

    EOS: ""
}));

ORBEON.xforms.Events.orbeonLoadedEvent.subscribe(function() {
    AccordionMenu.setting('my-accordion$dl', {animation: true, seconds: 0.001, openedIds: [], dependent: false, easeOut: false});
    if (parent && parent.TestManager) {
        parent.TestManager.load();
    } else {
        new YAHOO.tool.TestLogger();
        YAHOO.tool.TestRunner.run();
    }
});
