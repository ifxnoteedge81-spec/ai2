package com.personal.aicomputer.security

/**
 * Permission levels from the product spec:
 *  SAFE       = Level 1, runs automatically inside the authorised workspace.
 *  CONFIRM    = Level 2, the user must approve in the UI.
 *  RESTRICTED = Level 3, never performed automatically.
 */
enum class Risk { SAFE, CONFIRM, RESTRICTED }

data class Decision(
    val risk: Risk,
    val reason: String = "",
    /** Approval category; "allow for this task" is remembered per category. */
    val category: String = "general",
)
