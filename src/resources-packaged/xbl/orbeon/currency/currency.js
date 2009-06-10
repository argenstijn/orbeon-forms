YAHOO.namespace("xbl.fr");
YAHOO.xbl.fr.Currency = {
    instances: {},

    init: function(target) {
        var container = YAHOO.util.Dom.getAncestorByClassName(target, "xbl-fr-currency");
        if (! YAHOO.xbl.fr.Currency.instances[container.id]) {

            // Get information from the DOM
            var xformsInputElement = YAHOO.util.Dom.getElementsByClassName("xbl-fr-currency-xforms-input", null, container)[0];
            var visibleInputElement = YAHOO.util.Dom.getElementsByClassName("xbl-fr-currency-visible-input", null, container)[0];
            var symbolElement = YAHOO.util.Dom.getElementsByClassName("xbl-fr-currency-prefix", null, container)[0];
            var prefix = ORBEON.xforms.Document.getValue(symbolElement.id) + " ";
            var digitsAfterDecimalElement = YAHOO.util.Dom.getElementsByClassName("xbl-fr-currency-digits-after-decimal", null, container)[0];
            var digitsAfterDecimal = ORBEON.xforms.Document.getValue(digitsAfterDecimalElement.id);
            var hasFocus = false;

            // Create instance
            var instance = {
                focus: function() {
                    hasFocus = true;
                    visibleInputElement.value = instance.currencyToNumber(visibleInputElement.value);
                },
                blur: function() {
                    hasFocus = false;
                    var cleanNumber = new Number(visibleInputElement.value).toString();
                    ORBEON.xforms.Document.setValue(xformsInputElement.id, visibleInputElement.value == "" || cleanNumber == "NaN" ? visibleInputElement.value : cleanNumber);
                    visibleInputElement.value = instance.numberToCurrency(visibleInputElement.value);
                },
                currencyToNumber: function(currency) {
                    if (currency.indexOf(prefix) == 0) {
                        var cleaned = currency.substring(prefix.length);
                        cleaned = cleaned.replace(new RegExp("[\\s,]", "g"), "");
                        return isNaN(new Number(cleaned)) ? currency : cleaned;
                    } else {
                        return currency;
                    }
                },
                numberToCurrency: function(number) {
                    var cleaned = number.replace(new RegExp("[\\s,]", "g"), "");
                    if (cleaned == "") {
                        return number;
                    } else {
                        var numberObject = new Number(cleaned);
                        if (isNaN(numberObject)) {
                            return number;
                        } else {
                            var fixed = numberObject.toFixed(digitsAfterDecimal);
                            var parts = fixed.split(".");
                            var regExp = /(\d+)(\d{3})/;
                            while (regExp.test(parts[0])) {
                                parts[0] = parts[0].replace(regExp, "$1" + "," + "$2");
                            }
                            var result = prefix + parts[0];
                            if (parts.length > 1)
                                result += "." + parts[1];
                            return result;
                        }
                    }
                },
                xformsToVisible: function() {
                    var xformsValue = ORBEON.xforms.Document.getValue(xformsInputElement.id);
                    // If there is an update in the value, and the field already has the focus, just populate with the
                    // XForms value without currency formatting
                    var currencyFormattedValue = this.numberToCurrency(xformsValue);
                    visibleInputElement.value = hasFocus ? this.currencyToNumber(currencyFormattedValue) : currencyFormattedValue;
                },
                prefixChanged: function() {
                    prefix = ORBEON.xforms.Document.getValue(symbolElement.id) + " ";
                    instance.xformsToVisible();
                },
                digitsAfterDecimalChanged: function() {
                    digitsAfterDecimal = ORBEON.xforms.Document.getValue(digitsAfterDecimalElement.id);
                    instance.xformsToVisible();
                }
            };

            // Initialize value of visible input
            instance.xformsToVisible();

            // Register listener
            YAHOO.util.Event.addFocusListener(visibleInputElement, instance.focus);
            YAHOO.util.Event.addBlurListener(visibleInputElement, instance.blur);
            YAHOO.xbl.fr.Currency.instances[container.id] = instance;
        }
    },

    valueChanged: function(target) {
        var container = YAHOO.util.Dom.getAncestorByClassName(target, "xbl-fr-currency");
        var instance = YAHOO.xbl.fr.Currency.instances[container.id];
        instance.xformsToVisible();
    },

    prefixChanged: function(target) {
        var container = YAHOO.util.Dom.getAncestorByClassName(target, "xbl-fr-currency");
        var instance = YAHOO.xbl.fr.Currency.instances[container.id];
        instance.prefixChanged();
    },

    digitsAfterDecimalChanged: function(target) {
        var container = YAHOO.util.Dom.getAncestorByClassName(target, "xbl-fr-currency");
        var instance = YAHOO.xbl.fr.Currency.instances[container.id];
        instance.digitsAfterDecimalChanged();
    }
};