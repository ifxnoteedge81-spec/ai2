package com.personal.aicomputer.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.personal.aicomputer.security.SensitiveApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Android Device Automation Controller (separate from the internal Linux workspace).
 *
 * Limits that are inherent to Android and are NOT worked around:
 *  - protected windows (FLAG_SECURE: banking, DRM, some login screens) expose no content;
 *  - actions are only "dispatched" - the OS does not tell us whether the target app did what we wanted,
 *    so every action should be verified by observing the screen afterwards;
 *  - banking/wallet/password-manager/system-settings/installer/own-app screens are refused by policy.
 */
class HandsController(private val appContext: Context) {
    data class Outcome(val ok: Boolean, val text: String)

    private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    private fun service(): HandsService? = HandsService.instance

    fun isEnabled(): Boolean = service() != null

    fun foregroundPackage(): String? = service()?.rootInActiveWindow?.packageName?.toString()

    private fun notEnabled() = Outcome(
        false,
        "Virtual Hands is not enabled. Ask the user to turn on \"Personal AI Computer – Virtual Hands\" in Android Settings > Accessibility. The agent cannot enable it.",
    )

    /** Returns a refusal message when the foreground app is protected, otherwise null. */
    private fun guardForeground(): String? {
        val pkg = foregroundPackage()
        return if (SensitiveApps.isBlocked(pkg)) SensitiveApps.explain(pkg) else null
    }

    private fun screenSize(): Pair<Int, Int> = appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    fun status(): String {
        if (!isEnabled()) return notEnabled().text
        val (w, h) = screenSize()
        val pkg = foregroundPackage()
        return "Virtual Hands enabled. Screen ${w}x$h px. Foreground app: ${pkg ?: "unknown"}${if (SensitiveApps.isBlocked(pkg)) " (PROTECTED: observation and control refused)" else ""}"
    }

    suspend fun observe(maxNodes: Int = 120): Outcome = withContext(Dispatchers.Default) {
        val svc = service() ?: return@withContext notEnabled()
        val root = svc.rootInActiveWindow
            ?: return@withContext Outcome(false, "No accessible content on screen (the screen may be locked, protected or changing). Wait a moment and try again.")
        val pkg = root.packageName?.toString()
        if (SensitiveApps.isBlocked(pkg)) return@withContext Outcome(false, SensitiveApps.explain(pkg))

        val nodes = ArrayList<AccessibilityNodeInfo>()
        val lines = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo) {
            if (nodes.size >= maxNodes || !n.isVisibleToUser) return
            val text = if (n.isPassword) "(password field – content hidden)" else n.text?.toString()?.take(120)
            val desc = n.contentDescription?.toString()?.take(120)
            val interesting = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable || !text.isNullOrBlank() || !desc.isNullOrBlank()
            if (interesting) {
                val r = Rect().also { n.getBoundsInScreen(it) }
                val flags = buildString {
                    if (n.isClickable) append(" click")
                    if (n.isEditable) append(" edit")
                    if (n.isScrollable) append(" scroll")
                    if (n.isCheckable) append(if (n.isChecked) " checked" else " unchecked")
                }
                lines += "[${nodes.size}] ${n.className?.toString()?.substringAfterLast('.') ?: "View"}" +
                    (if (!text.isNullOrBlank()) " \"$text\"" else "") + (if (!desc.isNullOrBlank()) " {$desc}" else "") +
                    " @(${r.centerX()},${r.centerY()})" + flags
                nodes += n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        lastNodes = nodes
        val (w, h) = screenSize()
        Outcome(true, "App: $pkg  Screen: ${w}x$h\n" + if (lines.isEmpty()) "(no visible text or controls)" else lines.joinToString("\n"))
    }

    private suspend fun gesture(path: Path, duration: Long): Boolean = suspendCancellableCoroutine { cont ->
        val svc = service()
        if (svc == null) { cont.resume(false); return@suspendCancellableCoroutine }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceAtLeast(1))).build()
        val dispatched = svc.dispatchGesture(
            g,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            },
            null,
        )
        if (!dispatched && cont.isActive) cont.resume(false)
    }

    private fun inScreen(x: Int, y: Int): Boolean = screenSize().let { (w, h) -> x in 0 until w && y in 0 until h }

    suspend fun tapXY(x: Int, y: Int, count: Int = 1, holdMs: Long = 60): Outcome {
        if (!isEnabled()) return notEnabled()
        guardForeground()?.let { return Outcome(false, it) }
        if (!inScreen(x, y)) return Outcome(false, "Coordinates ($x,$y) are outside the screen ${screenSize().first}x${screenSize().second}.")
        var okAll = true
        repeat(count.coerceIn(1, 2)) { i ->
            val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            if (!gesture(p, holdMs)) okAll = false
            if (i == 0 && count > 1) delay(90)
        }
        delay(350)
        return Outcome(okAll, if (okAll) "Gesture dispatched at ($x,$y)${if (holdMs > 400) " (long press)" else ""}. Foreground: ${foregroundPackage()}. Observe the screen to verify." else "Gesture was cancelled or rejected by Android.")
    }

    private fun nodeAt(index: Int): AccessibilityNodeInfo? = lastNodes.getOrNull(index)

    suspend fun tapNode(index: Int, longPress: Boolean = false): Outcome = withContext(Dispatchers.Default) {
        if (!isEnabled()) return@withContext notEnabled()
        guardForeground()?.let { return@withContext Outcome(false, it) }
        val node = nodeAt(index) ?: return@withContext Outcome(false, "No node [$index]. Call device_observe first and use a listed index.")
        if (!node.refresh()) return@withContext Outcome(false, "Node [$index] is stale (the screen changed). Call device_observe again.")
        if (longPress) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            return@withContext tapXY(r.centerX(), r.centerY(), 1, 700)
        }
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target != null) {
            val accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(350)
            return@withContext Outcome(accepted, if (accepted) "Click accepted on node [$index]. Foreground: ${foregroundPackage()}. Observe the screen to verify." else "The app refused the click on node [$index].")
        }
        val r = Rect().also { node.getBoundsInScreen(it) }
        tapXY(r.centerX(), r.centerY())
    }

    suspend fun tapText(text: String): Outcome = withContext(Dispatchers.Default) {
        val svc = service() ?: return@withContext notEnabled()
        guardForeground()?.let { return@withContext Outcome(false, it) }
        val root = svc.rootInActiveWindow ?: return@withContext Outcome(false, "No accessible content on screen.")
        val match = root.findAccessibilityNodeInfosByText(text).firstOrNull { it.isVisibleToUser }
            ?: return@withContext Outcome(false, "No visible element containing \"$text\". Call device_observe to see what is on screen.")
        var target: AccessibilityNodeInfo? = match
        while (target != null && !target.isClickable) target = target.parent
        if (target != null) {
            val accepted = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(350)
            Outcome(accepted, if (accepted) "Clicked \"$text\". Foreground: ${foregroundPackage()}. Observe the screen to verify." else "The app refused the click on \"$text\".")
        } else {
            val r = Rect().also { match.getBoundsInScreen(it) }
            tapXY(r.centerX(), r.centerY())
        }
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Outcome {
        if (!isEnabled()) return notEnabled()
        guardForeground()?.let { return Outcome(false, it) }
        if (!inScreen(x1, y1) || !inScreen(x2, y2)) return Outcome(false, "Swipe coordinates must be inside the ${screenSize().first}x${screenSize().second} screen.")
        val p = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val okG = gesture(p, durationMs.coerceIn(50, 3000))
        delay(350)
        return Outcome(okG, if (okG) "Swipe ($x1,$y1)→($x2,$y2) dispatched. Observe the screen to verify." else "Swipe was cancelled or rejected by Android.")
    }

    suspend fun scroll(down: Boolean): Outcome = withContext(Dispatchers.Default) {
        val svc = service() ?: return@withContext notEnabled()
        guardForeground()?.let { return@withContext Outcome(false, it) }
        val root = svc.rootInActiveWindow ?: return@withContext Outcome(false, "No accessible content on screen.")
        fun findScrollable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isScrollable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> findScrollable(c)?.let { return it } }
            return null
        }
        val sc = findScrollable(root)
        if (sc != null) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val accepted = sc.performAction(action)
            delay(350)
            if (accepted) return@withContext Outcome(true, "Scrolled ${if (down) "down" else "up"}. Observe the screen to verify.")
        }
        val (w, h) = screenSize()
        val (y1, y2) = if (down) (h * 0.7).toInt() to (h * 0.3).toInt() else (h * 0.3).toInt() to (h * 0.7).toInt()
        swipe(w / 2, y1, w / 2, y2, 400)
    }

    suspend fun typeText(text: String, index: Int?, append: Boolean): Outcome = withContext(Dispatchers.Default) {
        val svc = service() ?: return@withContext notEnabled()
        guardForeground()?.let { return@withContext Outcome(false, it) }
        var target: AccessibilityNodeInfo? = if (index != null) nodeAt(index) else svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null && index == null) {
            fun firstEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (n.isEditable && n.isVisibleToUser) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { c -> firstEditable(c)?.let { return it } }
                return null
            }
            target = svc.rootInActiveWindow?.let { firstEditable(it) }
        }
        if (target == null) return@withContext Outcome(false, "No text field found. Tap a field first (device_tap) or pass a node index from device_observe.")
        if (!target.refresh()) return@withContext Outcome(false, "That field is stale. Call device_observe again.")
        if (target.isPassword) return@withContext Outcome(false, "BLOCKED: password field. The user must type passwords and codes themselves.")
        if (!target.isEditable) return@withContext Outcome(false, "That element is not an editable text field.")
        val existing = if (append) target.text?.toString().orEmpty() else ""
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, existing + text) }
        val accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        delay(250)
        target.refresh()
        val now = target.text?.toString().orEmpty()
        val verified = now.contains(text)
        Outcome(accepted && verified, if (accepted && verified) "Typed ${text.length} characters (verified in field)." else "Typing ${if (accepted) "was accepted but the field does not show the text" else "was rejected by the app"}.")
    }

    suspend fun globalKey(key: String): Outcome {
        val svc = service() ?: return notEnabled()
        val action = when (key.lowercase()) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            else -> return Outcome(false, "Unknown key '$key'. Use back, home or recents.")
        }
        val okA = svc.performGlobalAction(action)
        delay(400)
        return Outcome(okA, if (okA) "Pressed $key. Foreground: ${foregroundPackage()}." else "Android rejected the $key action.")
    }

    suspend fun launchApp(query: String): Outcome = withContext(Dispatchers.Default) {
        val svc = service() ?: return@withContext notEnabled()
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val q = query.trim()
        val matches = pm.queryIntentActivities(intent, 0).filter {
            it.activityInfo.packageName.equals(q, true) || it.loadLabel(pm).toString().contains(q, true)
        }
        val best = matches.firstOrNull { it.activityInfo.packageName.equals(q, true) || it.loadLabel(pm).toString().equals(q, true) } ?: matches.firstOrNull()
            ?: return@withContext Outcome(false, "No launchable app matches \"$q\".")
        val pkg = best.activityInfo.packageName
        if (SensitiveApps.isBlocked(pkg)) return@withContext Outcome(false, SensitiveApps.explain(pkg))
        val launch = pm.getLaunchIntentForPackage(pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return@withContext Outcome(false, "$pkg has no launch intent.")
        try {
            svc.startActivity(launch)
        } catch (e: Exception) {
            return@withContext Outcome(false, "Android blocked starting $pkg from the background: ${e.message}. Ask the user to open it.")
        }
        delay(900)
        Outcome(true, "Launch of ${best.loadLabel(pm)} ($pkg) requested. Foreground now: ${foregroundPackage()}. Observe the screen to verify.")
    }
}
