package com.threadbare.client.web

import android.content.Context
import com.threadbare.client.R

/**
 * The offline / failed-load page.
 *
 * Built as a data URL with no external references at all — no font, no image,
 * no stylesheet. A network error page that itself needs the network is a joke,
 * and one that fetches anything would be a beacon.
 */
object ErrorPage {

    fun html(context: Context, url: String?, description: String?): String {
        val title = context.getString(R.string.error_title)
        val body = context.getString(R.string.error_body)
        val detail = listOfNotNull(url, description)
            .joinToString(" — ")
            .let(::escape)

        return """
            <!doctype html>
            <html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
            <style>
              :root { color-scheme: light dark; }
              body {
                margin: 0; padding: 12vh 24px;
                font: 16px/1.5 system-ui, -apple-system, sans-serif;
                background: #ffffff; color: #1a1a1a;
              }
              h1 { font-size: 20px; margin: 0 0 8px; }
              p { margin: 0 0 12px; }
              code { font-size: 12px; opacity: .65; overflow-wrap: anywhere; }
              @media (prefers-color-scheme: dark) {
                body { background: #14120f; color: #ddd8d0; }
              }
            </style></head>
            <body>
              <h1>${escape(title)}</h1>
              <p>${escape(body)}</p>
              <code>$detail</code>
            </body></html>
        """.trimIndent()
    }

    private fun escape(s: String?): String = (s ?: "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
