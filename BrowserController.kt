package com.personal.aicomputer.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.personal.aicomputer.tools.NetGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

data class BrowserState(
    val url: String = "",
    val title: String = "",
    val loading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val lastError: String? = null,
)

data class PageElement(val id: Int, val tag: String, val type: String, val label: String, val href: String)

/**
 * One in-app WebView that both the user (Browser screen) and the agent (browser_* tools) drive.
 *
 * Playwright cannot run inside an Android app process, so this is the "compatible browser automation
 * adapter": DOM actions are performed by injected JavaScript through WebView.evaluateJavascript.
 */
class BrowserController(private val appContext: Context) {
    private var web: WebView? = null
    private val state = MutableStateFlow(BrowserState())
    val browserState: StateFlow<BrowserState> = state.asStateFlow()
    private var loadWaiter: CompletableDeferred<String?>? = null

    /** Labels of the elements returned by the last browser_elements call, used to classify click risk. */
    @Volatile var lastLabels: Map<Int, String> = emptyMap()

    /** Must be called on the main thread. */
    @SuppressLint("SetJavaScriptEnabled")
    fun webView(): WebView {
        web?.let { return it }
        val w = WebView(appContext)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https" // block intent:, file:, content:, market: ... navigations
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                state.value = state.value.copy(url = url.orEmpty(), loading = true, lastError = null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                state.value = state.value.copy(
                    url = url.orEmpty(), title = view.title.orEmpty(), loading = false, progress = 100,
                    canGoBack = view.canGoBack(), canGoForward = view.canGoForward(),
                )
                loadWaiter?.complete(null)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val msg = "Page failed to load: ${error.description}"
                    state.value = state.value.copy(loading = false, lastError = msg)
                    loadWaiter?.complete(msg)
                }
            }
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                state.value = state.value.copy(progress = newProgress)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                state.value = state.value.copy(title = title.orEmpty())
            }
        }
        web = w
        return w
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }

    fun normalizeUrl(input: String): String {
        val t = input.trim()
        return when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(' ') || !t.contains('.') -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
            else -> "https://$t"
        }
    }

    /** Opens a URL (SSRF-checked) and waits for the load to finish. Returns a short status line. */
    suspend fun open(input: String): String {
        val url = normalizeUrl(input)
        val problem = withContext(Dispatchers.IO) { NetGuard.checkUrl(url) }
        if (problem != null) throw SecurityException(problem)
        val waiter = CompletableDeferred<String?>()
        onMain {
            loadWaiter = waiter
            webView().loadUrl(url)
        }
        val err = withTimeoutOrNull(35_000) { waiter.await() }
        val s = state.value
        return when {
            err != null -> "ERROR: $err"
            s.loading && s.progress < 100 -> "Still loading after 35 s (progress ${s.progress}%). Current: ${s.url}"
            else -> "Opened: ${s.title.ifBlank { "(no title)" }} — ${s.url}"
        }
    }

    suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView().evaluateJavascript(js) { raw -> if (cont.isActive) cont.resume(raw ?: "null") }
        }
    }

    /** evaluateJavascript returns JSON; a JS string result arrives as a quoted+escaped JSON string. */
    suspend fun evalString(js: String): String = decodeJsResult(eval(js))

    suspend fun readPage(maxChars: Int): String {
        val text = evalString("(function(){return document.body?document.body.innerText:''})()")
        val s = state.value
        val body = text.replace(Regex("\\n{3,}"), "\n\n").trim()
        return "URL: ${s.url}\nTitle: ${s.title}\n\n" + body.take(maxChars) + if (body.length > maxChars) "\n…[truncated, ${body.length} chars total]" else ""
    }

    suspend fun listElements(): String {
        val json = evalString(ELEMENTS_JS)
        val o = JSONObject(json)
        val arr = o.getJSONArray("elements")
        val labels = HashMap<Int, String>()
        val lines = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val id = e.getInt("id")
            val label = e.optString("label")
            labels[id] = label
            val extra = buildString {
                if (e.optString("type").isNotEmpty()) append(" type=${e.optString("type")}")
                if (e.optString("href").isNotEmpty()) append(" -> ${e.optString("href").take(120)}")
            }
            lines += "[$id] <${e.optString("tag")}>$extra \"$label\""
        }
        lastLabels = labels
        return "URL: ${o.optString("url")}\nTitle: ${o.optString("title")}\nInteractive elements (use the number as target):\n" + lines.joinToString("\n")
    }

    suspend fun click(target: String): String {
        val r = JSONObject(evalString(CLICK_JS.replace("__TARGET__", JSONObject.quote(target))))
        if (!r.optBoolean("ok")) throw IllegalStateException(r.optString("error", "click failed"))
        return "Clicked <${r.optString("tag")}> \"${r.optString("label")}\". Read the page again to verify the result."
    }

    suspend fun type(target: String, text: String, submit: Boolean): String {
        val js = TYPE_JS.replace("__TARGET__", JSONObject.quote(target)).replace("__TEXT__", JSONObject.quote(text)).replace("__SUBMIT__", submit.toString())
        val r = JSONObject(evalString(js))
        if (!r.optBoolean("ok")) throw IllegalStateException(r.optString("error", "type failed"))
        return "Typed ${text.length} characters into <${r.optString("tag")}> \"${r.optString("label")}\"${if (submit) " and submitted" else ""}."
    }

    suspend fun scroll(direction: String): String {
        val r = JSONObject(evalString(SCROLL_JS.replace("__DIR__", JSONObject.quote(direction))))
        return "Scrolled $direction: position ${r.optInt("y")} of ${r.optInt("h")} px."
    }

    suspend fun back(): String = onMain {
        val w = webView()
        if (w.canGoBack()) { w.goBack(); "Went back." } else "No previous page."
    }

    fun clearData() {
        web?.post { web?.clearCache(true); web?.clearHistory() }
        android.webkit.CookieManager.getInstance().removeAllCookies(null)
    }

    companion object {
        fun decodeJsResult(raw: String): String {
            if (raw == "null") return ""
            val v = try { JSONTokener(raw).nextValue() } catch (e: Exception) { return raw }
            return when (v) {
                is String -> v
                null, JSONObject.NULL -> ""
                else -> v.toString()
            }
        }

        private const val ELEMENTS_JS = """(function(){
document.querySelectorAll('[data-pac-id]').forEach(function(e){e.removeAttribute('data-pac-id');});
var out=[];var n=0;
var els=document.querySelectorAll('a[href],button,input,select,textarea,[role=button],[role=link],[role=checkbox],[role=tab],[onclick],summary');
function vis(e){var r=e.getBoundingClientRect();var s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none';}
for(var i=0;i<els.length&&n<80;i++){var e=els[i];if(!vis(e))continue;
 if(e.tagName==='INPUT'&&e.type==='hidden')continue;
 var t=(e.innerText||e.value||e.getAttribute('aria-label')||e.placeholder||e.title||e.name||'').trim().replace(/\s+/g,' ').slice(0,70);
 e.setAttribute('data-pac-id',String(n));
 out.push({id:n,tag:e.tagName.toLowerCase(),type:(e.getAttribute('type')||''),label:t,href:(e.tagName==='A'?e.href:'')});n++;}
return JSON.stringify({url:location.href,title:document.title,elements:out});})()"""

        private const val FIND_EL = """var t=__TARGET__;var el=null;
if(/^\d+${'$'}/.test(t))el=document.querySelector('[data-pac-id="'+t+'"]');
if(!el){try{el=document.querySelector(t);}catch(e){}}
if(!el){var all=document.querySelectorAll('a,button,input[type=submit],input[type=button],[role=button],[onclick],summary,input,textarea,select');var low=t.toLowerCase();
 for(var i=0;i<all.length;i++){var tx=(all[i].innerText||all[i].value||all[i].getAttribute('aria-label')||all[i].placeholder||all[i].name||'').trim().toLowerCase();if(tx&&tx.indexOf(low)>=0){el=all[i];break;}}}"""

        private const val CLICK_JS = """(function(){$FIND_EL
if(!el)return JSON.stringify({ok:false,error:'element not found; call browser_elements first'});
el.scrollIntoView({block:'center'});el.click();
return JSON.stringify({ok:true,tag:el.tagName.toLowerCase(),label:(el.innerText||el.value||'').trim().slice(0,60)});})()"""

        private const val TYPE_JS = """(function(){$FIND_EL
if(!el)return JSON.stringify({ok:false,error:'field not found; call browser_elements first'});
var meta=((el.type||'')+' '+(el.autocomplete||'')+' '+(el.name||'')+' '+(el.id||'')+' '+(el.getAttribute('aria-label')||'')).toLowerCase();
if(/password|one-time-code|otp|passcode|cvv|cvc|cc-number|cc-csc|captcha|pin\b/.test(meta))return JSON.stringify({ok:false,error:'BLOCKED: this looks like a password/OTP/card/CAPTCHA field. Ask the user to fill it in themselves.'});
el.scrollIntoView({block:'center'});el.focus();var v=__TEXT__;
if(el.isContentEditable){el.textContent=v;}else{var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el),'value');if(d&&d.set){d.set.call(el,v);}else{el.value=v;}}
el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));
if(__SUBMIT__){if(el.form){if(el.form.requestSubmit){el.form.requestSubmit();}else{el.form.submit();}}else{el.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));}}
return JSON.stringify({ok:true,tag:el.tagName.toLowerCase(),label:(el.placeholder||el.name||el.id||'').slice(0,50)});})()"""

        private const val SCROLL_JS = """(function(){var d=__DIR__;var h=window.innerHeight*0.8;
if(d==='down')window.scrollBy(0,h);else if(d==='up')window.scrollBy(0,-h);else if(d==='top')window.scrollTo(0,0);else if(d==='bottom')window.scrollTo(0,document.body.scrollHeight);
return JSON.stringify({y:Math.round(window.scrollY),h:document.documentElement.scrollHeight});})()"""
    }
}
