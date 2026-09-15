package com.threadbare.client.web

/**
 * Do Not Track and Global Privacy Control.
 *
 * Neither is enforceable and Reddit is under no obligation to honour either.
 * They are sent anyway because GPC in particular carries legal weight in some
 * jurisdictions, and because sending them costs one header each.
 *
 * Two halves, and both are needed: the headers are attached to top-level loads,
 * and the JS-visible properties are shimmed so a script that reads
 * `navigator.globalPrivacyControl` sees the same answer the header gave.
 */
object PrivacySignals {

    val headers: Map<String, String> = mapOf(
        "DNT" to "1",
        "Sec-GPC" to "1",
    )

    val script: String = """
        (function () {
          'use strict';
          function def(name, value) {
            try {
              Object.defineProperty(navigator, name, {
                get: function () { return value; },
                configurable: true,
                enumerable: true
              });
            } catch (e) { /* not fatal */ }
          }
          def('doNotTrack', '1');
          def('globalPrivacyControl', true);
        })();
    """.trimIndent()
}
