package com.personal.aicomputer.security

/**
 * Apps the Virtual Hands tools refuse to observe or touch.
 * Includes this app itself (so the agent can never tap its own approval dialog) and system screens
 * that could be used to grant permissions or install software without the user.
 */
object SensitiveApps {
    const val OWN_PACKAGE_PREFIX = "com.personal.aicomputer"

    private val exact = setOf(
        "com.android.settings",
        "com.android.systemui",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.apps.authenticator2",
        "com.android.vending",
    )
    private val contains = listOf(
        "bank", "wallet", "bkash", "nagad", "upay", "paypal", "payoneer", "binance",
        "authenticator", "keepass", "bitwarden", "lastpass", "1password", "dashlane", "keeper",
    )

    fun isBlocked(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val p = pkg.lowercase()
        if (p.startsWith(OWN_PACKAGE_PREFIX)) return true
        if (p in exact) return true
        return contains.any { p.contains(it) }
    }

    fun explain(pkg: String?): String =
        "The foreground app ($pkg) is protected: the agent may not observe or control banking, wallet, " +
            "password-manager, system-settings, installer or its own screens. Ask the user to do this step."
}
