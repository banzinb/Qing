package com.zhousl.aether.data.browser

import org.json.JSONObject

/**
 * Small synchronous JavaScript probes used by the embedded WebView backend and by the router's
 * condition waits.
 *
 * Everything here returns a JSON string so a single decode path works for both
 * `WebView.evaluateJavascript` and Chromium CDP `Runtime.evaluate` (the shared scripts in
 * `SharedBrowserProtocol.kt` follow the same convention).
 */
object BrowserProbeScripts {
    /** `{ok, count, visible_count, url, title}` for a CSS selector. Never throws. */
    fun elementProbeScript(selector: String): String {
        val selectorLiteral = JSONObject.quote(selector)
        return """
            (() => {
              let nodes = [];
              try {
                nodes = Array.from(document.querySelectorAll($selectorLiteral));
              } catch (error) {
                return JSON.stringify({ok:false,error:'Invalid CSS selector: ' + String(error),count:0,visible_count:0});
              }
              const isVisible = (element) => {
                const rect = element.getBoundingClientRect();
                if (rect.width <= 0 || rect.height <= 0) return false;
                const style = window.getComputedStyle(element);
                if (!style) return true;
                if (style.visibility === 'hidden' || style.display === 'none') return false;
                if (Number(style.opacity) === 0) return false;
                return true;
              };
              return JSON.stringify({
                ok:true,
                count:nodes.length,
                visible_count:nodes.filter(isVisible).length,
                url:location.href,
                title:document.title
              });
            })()
        """.trimIndent()
    }

    /** Installs a DOM mutation counter once per document. */
    val domMutationInstallScript: String = """
        (() => {
          if (!window.__qingDomProbe) {
            window.__qingDomProbe = {mutations:0};
            const target = document.documentElement || document;
            new MutationObserver((records) => {
              window.__qingDomProbe.mutations += records.length;
            }).observe(target, {childList:true,subtree:true,attributes:true,characterData:true});
          }
          return JSON.stringify({ok:true,installed:true,mutations:window.__qingDomProbe.mutations});
        })()
    """.trimIndent()

    /** Reads the mutation counter installed by [domMutationInstallScript]. */
    val domMutationProbeScript: String = """
        (() => {
          const probe = window.__qingDomProbe;
          return JSON.stringify({
            ok:true,
            mutations:probe ? probe.mutations : 0,
            ready_state:document.readyState,
            element_count:document.querySelectorAll('*').length,
            url:location.href,
            title:document.title
          });
        })()
    """.trimIndent()

    /** `{ok, ready_state, url, title}` - cheap liveness check used while waiting for navigations. */
    val readyStateProbeScript: String = """
        (() => JSON.stringify({
          ok:true,
          ready_state:document.readyState,
          url:location.href,
          title:document.title
        }))()
    """.trimIndent()

    /**
     * Click with a hit test.
     *
     * The shared `browserClickScript` calls `element.click()` and always reports success. This
     * variant refuses to report a click that cannot land: disabled controls, `pointer-events:none`,
     * zero-size elements and elements covered by another node are reported as `blocked_reason`
     * instead of a silent success.
     */
    fun clickVerifyScript(selector: String, x: Double?, y: Double?): String {
        val selectorLiteral = selector.takeIf { it.isNotBlank() }?.let { JSONObject.quote(it) } ?: "null"
        val xLiteral = x?.toString() ?: "null"
        val yLiteral = y?.toString() ?: "null"
        return """
            (() => {
              const selector = $selectorLiteral;
              const normalizedX = $xLiteral;
              const normalizedY = $yLiteral;
              const element = selector
                ? document.querySelector(selector)
                : (normalizedX == null || normalizedY == null
                    ? null
                    : document.elementFromPoint(
                        Math.max(0, Math.min(window.innerWidth - 1, normalizedX * window.innerWidth / 1000)),
                        Math.max(0, Math.min(window.innerHeight - 1, normalizedY * window.innerHeight / 1000))
                      ));
              if (!element) return JSON.stringify({ok:false,clicked:false,error:'Element not found.'});
              element.scrollIntoView({block:'center',inline:'center'});
              const rect = element.getBoundingClientRect();
              const centerX = rect.left + rect.width / 2;
              const centerY = rect.top + rect.height / 2;
              const blocked = [];
              if (element.disabled) blocked.push('disabled');
              if (element.getAttribute('aria-disabled') === 'true') blocked.push('aria_disabled');
              if (rect.width <= 0 || rect.height <= 0) blocked.push('zero_size');
              const style = window.getComputedStyle(element);
              if (style && style.pointerEvents === 'none') blocked.push('pointer_events_none');
              const covered = document.elementFromPoint(
                Math.max(0, Math.min(window.innerWidth - 1, centerX)),
                Math.max(0, Math.min(window.innerHeight - 1, centerY))
              );
              const reachable = !!covered && (covered === element || element.contains(covered) || covered.contains(element));
              if (!reachable) blocked.push('covered_by_other_element');
              const summary = (element.innerText || element.textContent || '').trim().slice(0, 200);
              if (blocked.length) {
                return JSON.stringify({
                  ok:false,
                  clicked:false,
                  tag:element.tagName,
                  text:summary,
                  blocked_reason:blocked.join(','),
                  cursor_x:Math.round(centerX),
                  cursor_y:Math.round(centerY),
                  url:location.href
                });
              }
              const urlBefore = location.href;
              element.focus?.();
              element.click();
              return JSON.stringify({
                ok:true,
                clicked:true,
                verified:true,
                tag:element.tagName,
                text:summary,
                cursor_x:Math.round(centerX),
                cursor_y:Math.round(centerY),
                cursor_animation_duration_ms:220,
                url_before_click:urlBefore,
                url:location.href,
                next_step:'The click was dispatched. Re-read the page (get_page_info / get_text) before assuming the task finished.'
              });
            })()
        """.trimIndent()
    }
}
