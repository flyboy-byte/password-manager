package com.example.passwordmanager.service

import android.app.assist.AssistStructure
import android.util.Log
import android.view.View

class AssistStructureParser {
    var webDomain: String? = null
    var usernameNodes = mutableListOf<AssistStructure.ViewNode>()
    var passwordNodes = mutableListOf<AssistStructure.ViewNode>()

    // Evidence gathering metadata
    var htmlInfoCount = 0
    var inputTypeMap = mutableMapOf<Int, Int>()

    companion object {
        private const val TAG = "AssistParser"

        private val BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.focus",
            "com.sec.android.app.sbrowser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.brave.browser"
        )

        private val URL_BAR_IDS = listOf(
            "url_bar",
            "omnibox",
            "location_bar",
            "search_box",
            "address_bar"
        )

        // Denylist for Chrome/browser shell IDs that must never be treated as username/password fields.
        private val CHROME_SHELL_ID_DENYLIST = setOf(
            "custom_tabs_handle_view_stub",
            "url_bar",
            "toolbar",
            "toolbar_container",
            "location_bar_frame_layout",
            "title_url_container",
            "message_container",
            "tab_switcher_view_holder_stub",
            "tab_hover_card_holder_stub",
            "compositor_view_holder"
        )

        private val PASSWORD_NEGATIVE = setOf(
            "reply",
            "comment",
            "message",
            "chat",
            "post",
            "note",
            "search",
            "topic",
            "review"
        )

        private val USERNAME_NEGATIVE = setOf(
            "search",
            "reply",
            "comment",
            "message",
            "chat",
            "post",
            "note"
        )

        private val USERNAME_KEYWORDS = setOf(
            "username",
            "email",
            "login",
            "identifier",
            "account",
            "userid",
            "user id",
            "phone",
            "handle"
        )

        private val PASSWORD_KEYWORDS = setOf(
            "password",
            "passcode",
            "passwd",
            "pwd",
            "secret",
            "new password",
            "current-password"
        )

        private val POTENTIAL_INPUT_CLASS_SUBSTRINGS = listOf(
            "edittext",
            "edit_text",
            "edit",
            "text",
            "input"
        )

        private val POTENTIAL_INPUT_ID_SUBSTRINGS = listOf(
            "user",
            "email",
            "login",
            "pass",
            "pwd",
            "password"
        )
    }

    fun parse(structure: AssistStructure) {
        webDomain = null
        usernameNodes.clear()
        passwordNodes.clear()
        htmlInfoCount = 0
        inputTypeMap.clear()

        val packageName = structure.activityComponent?.packageName.orEmpty().lowercase()
        val browserLike = packageName in BROWSER_PACKAGES

        Log.d(TAG, "parse:start pkg=$packageName browserLike=$browserLike windows=${structure.windowNodeCount}")

        val windowCount = structure.windowNodeCount
        for (i in 0 until windowCount) {
            val windowNode = structure.getWindowNodeAt(i)
            traverseNode(windowNode.rootViewNode, browserLike)
        }

        Log.d(
            TAG,
            "parse:done pkg=$packageName browserLike=$browserLike webDomain=$webDomain usernameNodes=${usernameNodes.size} passwordNodes=${passwordNodes.size}"
        )

        if (browserLike && webDomain.isNullOrBlank()) {
            Log.d(TAG, "domain:finalResult webDomain=null reasons:")
        }
    }

    private fun traverseNode(node: AssistStructure.ViewNode, browserLike: Boolean) {
        // 1) Native domain when present
        if (!node.webDomain.isNullOrBlank()) {
            if (webDomain == null) {
                webDomain = normalizeDomain(node.webDomain!!)
                Log.d(TAG, "domain:native webDomain=$webDomain")
            }
        }

        // Core node signals
        val hints = node.autofillHints?.map { it.lowercase() } ?: emptyList()
        val hintText = node.hint?.lowercase().orEmpty()
        val contentDesc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val idEntry = node.idEntry?.lowercase().orEmpty()
        val className = node.className?.lowercase().orEmpty()
        val inputType = node.inputType
        if (inputType != 0) {
            inputTypeMap[inputType] = inputTypeMap.getOrDefault(inputType, 0) + 1
        }
        val nodeText = node.text?.toString()?.trim().orEmpty()
        val nodeTextLc = nodeText.lowercase()

        // Browser url_bar domain extraction logging (never credential classification)
        val looksLikeUrlBar = URL_BAR_IDS.any { idEntry.contains(it) }
        if (browserLike && webDomain == null && looksLikeUrlBar) {
            Log.d(TAG, "domain:urlBarInspect id=$idEntry textAvailable=${nodeText.isNotBlank()} text='$nodeText'")
        }

        // 2) Domain fallback from text/url-bar-like nodes
        if (webDomain == null) {
            val idSuggestsUrlBar = URL_BAR_IDS.any { idEntry.contains(it) }
            if (browserLike && idSuggestsUrlBar) {
                Log.d(
                    TAG,
                    "domain:attemptFromUrlBar id=$idEntry urlBarTextAvailable=${nodeText.isNotBlank()}"
                )
            }

            val fromText = extractDomainFromText(nodeText)
            if (browserLike && idSuggestsUrlBar) {
                Log.d(TAG, "domain:urlBarParse candidate=${fromText ?: "null"}")
            }

            if (fromText != null) {
                webDomain = fromText
                val source = if (idSuggestsUrlBar) "urlbar" else "text"
                Log.d(TAG, "domain:heuristic source=$source idEntry=$idEntry value=$webDomain")
            }
        }

        // HTML signals
        val htmlAttrs = mutableMapOf<String, String>()
        node.htmlInfo?.let { htmlInfo ->
            htmlInfoCount++
            htmlInfo.attributes?.forEach { pair ->
                val k = pair.first?.lowercase() ?: return@forEach
                val v = pair.second?.lowercase() ?: ""
                htmlAttrs[k] = v
            }
        }
        val htmlType = htmlAttrs["type"].orEmpty()
        val htmlName = htmlAttrs["name"].orEmpty()
        val htmlId = htmlAttrs["id"].orEmpty()
        val htmlAutocomplete = htmlAttrs["autocomplete"].orEmpty()
        val htmlAriaLabel = htmlAttrs["aria-label"].orEmpty()
        val htmlPlaceholder = htmlAttrs["placeholder"].orEmpty()

        val combined = listOf(
            idEntry,
            hintText,
            contentDesc,
            className,
            nodeTextLc,
            htmlType,
            htmlName,
            htmlId,
            htmlAutocomplete,
            htmlAriaLabel,
            htmlPlaceholder,
            hints.joinToString(" ")
        ).joinToString(" ")

        val hasPasswordKeyword = PASSWORD_KEYWORDS.any { combined.contains(it) }
        val hasUsernameKeyword = USERNAME_KEYWORDS.any { combined.contains(it) }

        val hasPasswordNegative = PASSWORD_NEGATIVE.any { combined.contains(it) }
        val hasUsernameNegative = USERNAME_NEGATIVE.any { combined.contains(it) }

        val denyChromeShell = CHROME_SHELL_ID_DENYLIST.any { idEntry.contains(it) }

        val isEditable = className.contains("edittext") ||
            className.contains("edit") ||
            className.contains("input") ||
            className.contains("text")

        val hasUsernameStrongSignal =
            hints.contains(View.AUTOFILL_HINT_USERNAME.lowercase()) ||
            hints.contains(View.AUTOFILL_HINT_EMAIL_ADDRESS.lowercase()) ||
            htmlType == "email" ||
            htmlAutocomplete.contains("username") ||
            htmlAutocomplete.contains("email") ||
            (hasUsernameKeyword && !hasUsernameNegative)

        val hasPasswordStrongSignal =
            hints.contains(View.AUTOFILL_HINT_PASSWORD.lowercase()) ||
            htmlType == "password" ||
            htmlAutocomplete.contains("current-password") ||
            htmlAutocomplete.contains("new-password") ||
            (hasPasswordKeyword && !hasPasswordNegative)

        val inputTypeIsUsername =
            (inputType and android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) != 0 ||
                (inputType and android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) != 0

        val inputTypeIsPassword =
            (inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
                (inputType and android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0

        val isPassword =
            !denyChromeShell &&
                !hasPasswordNegative &&
                (
                    hasPasswordStrongSignal ||
                        (isEditable && inputTypeIsPassword)
                    )

        val isUsername =
            !denyChromeShell &&
                !hasUsernameNegative &&
                (
                    hasUsernameStrongSignal ||
                        (isEditable && inputTypeIsUsername)
                    )

        val looksLikeField =
            className.contains("edit") || className.contains("text") || className.contains("input") ||
                idEntry.isNotBlank() || hints.isNotEmpty() ||
                htmlType.isNotBlank() || htmlName.isNotBlank() || htmlId.isNotBlank() ||
                inputType != 0 ||
                (browserLike && (hasUsernameKeyword || hasPasswordKeyword || URL_BAR_IDS.any { idEntry.contains(it) }))

        if (looksLikeField) {
            Log.d(
                TAG,
                "node: class=$className id=$idEntry inputType=$inputType hints=${hints.joinToString("|")} " +
                    "hint=$hintText text=${nodeText.take(60)} htmlType=$htmlType htmlName=$htmlName htmlId=$htmlId auto=$htmlAutocomplete negPwd=$hasPasswordNegative negUser=$hasUsernameNegative"
            )
        }

        // Never treat url_bar as credential field; it’s only for domain extraction.
        if (browserLike && looksLikeUrlBar && (isUsername || isPassword)) {
            Log.d(TAG, "rejectField id=$idEntry reason=deny-urlbar-never-credential")
        }

        // Potential input debug
        val potentialInputByClass = POTENTIAL_INPUT_CLASS_SUBSTRINGS.any { className.contains(it) }
        val potentialInputById = POTENTIAL_INPUT_ID_SUBSTRINGS.any { idEntry.contains(it) }
        val potentialInputByType = inputType != 0 || nodeTextLc.isNotBlank()

        if (browserLike && (potentialInputByClass && (potentialInputById || potentialInputByType))) {
            Log.d(
                TAG,
                "POTENTIAL_INPUT: class=$className id=$idEntry inputType=$inputType hints=${hints.joinToString("|")} hint=$hintText " +
                    "text=${nodeText.take(60)} htmlType=$htmlType htmlAutocomplete=$htmlAutocomplete"
            )
        }

        if (browserLike && looksLikeField && (isUsername || isPassword).not()) {
            val reason = if (denyChromeShell) {
                "deny-chrome-shell-id"
            } else if (!isEditable && inputType == 0 && hints.isEmpty() && htmlType.isBlank()) {
                "not-editable-no-autofill-no-html"
            } else if (!hasUsernameStrongSignal && !hasPasswordStrongSignal) {
                "no-strong-username-or-password-signal"
            } else {
                "rejected-unknown"
            }
            Log.d(TAG, "rejectField id=$idEntry reason=$reason")
        }

        when {
            isPassword && !looksLikeUrlBar -> {
                Log.d(TAG, "classify:PASSWORD class=$className id=$idEntry htmlType=$htmlType auto=$htmlAutocomplete")
                passwordNodes.add(node)
            }
            isUsername && !looksLikeUrlBar -> {
                Log.d(TAG, "classify:USERNAME class=$className id=$idEntry htmlType=$htmlType auto=$htmlAutocomplete")
                usernameNodes.add(node)
            }
        }

        for (i in 0 until node.childCount) {
            traverseNode(node.getChildAt(i), browserLike)
        }
    }

    private fun extractDomainFromText(text: String): String? {
        if (text.isBlank()) return null

        val token = text
            .split(' ', '\n', '\t')
            .firstOrNull { it.contains('.') }
            ?.trim()
            ?.removePrefix("https://")
            ?.removePrefix("http://")
            ?.removePrefix("www.")
            ?.substringBefore('/')
            ?.substringBefore('?')
            ?.substringBefore('#')
            ?: return null

        return token.takeIf { it.contains('.') && it.length > 3 && !it.contains(' ') }
            ?.let { normalizeDomain(it) }
    }

    private fun normalizeDomain(raw: String): String {
        return raw.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
    }
}
