package com.example.passwordmanager.service

import android.app.assist.AssistStructure

import android.util.Log
import android.view.View
import android.view.autofill.AutofillId

class AssistStructureParser {
    var webDomain: String? = null
    var usernameNodes = mutableListOf<AssistStructure.ViewNode>()
    var passwordNodes = mutableListOf<AssistStructure.ViewNode>()

    companion object {
        private const val TAG = "AssistParser"
    }

    fun parse(structure: AssistStructure) {
        val windowCount = structure.windowNodeCount
        for (i in 0 until windowCount) {
            val windowNode = structure.getWindowNodeAt(i)
            traverseNode(windowNode.rootViewNode)
        }
    }

    private fun traverseNode(node: AssistStructure.ViewNode) {
        // 1. Better Domain Detection
        if (node.webDomain != null) {
            webDomain = node.webDomain
            Log.d(TAG, "Found Web Domain: $webDomain")
        }

        // Fallback: If domain is null, check for URL in text (common in browser URL bars)
        if (webDomain == null) {
            val nodeText = node.text?.toString() ?: ""
            if (nodeText.startsWith("http") || nodeText.contains(".")) {
                // Heuristic for domain name
                val possibleDomain = nodeText.substringBefore("/").substringAfter("://").removePrefix("www.")
                if (possibleDomain.contains(".") && possibleDomain.length > 3) {
                    webDomain = possibleDomain
                    Log.d(TAG, "Heuristic Web Domain: $webDomain")
                }
            }
        }

        // 2. Comprehensive Field Detection
        val hints = node.autofillHints?.toList() ?: emptyList()
        val hintText = node.hint?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val idEntry = node.idEntry?.lowercase() ?: ""
        val inputType = node.inputType
        val className = node.className ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""

        // Check if it's a password field
        val isPassword = hints.contains(View.AUTOFILL_HINT_PASSWORD) ||
                idEntry.contains("password") ||
                hintText.contains("password") ||
                contentDesc.contains("password") ||
                className.contains("password", ignoreCase = true) ||
                text.contains("password") ||
                (inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
                (inputType and android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0 ||
                (inputType and android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD) != 0

        // Check if it's a username/email field
        val isUsername = hints.contains(View.AUTOFILL_HINT_USERNAME) ||
                hints.contains(View.AUTOFILL_HINT_EMAIL_ADDRESS) ||
                idEntry.contains("username") || idEntry.contains("email") || idEntry.contains("login") ||
                hintText.contains("username") || hintText.contains("email") ||
                contentDesc.contains("username") || contentDesc.contains("email") ||
                className.contains("user", ignoreCase = true) || className.contains("email", ignoreCase = true) ||
                text.contains("username") || text.contains("email") ||
                (inputType and android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) != 0 ||
                (inputType and android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) != 0

        if (isPassword) {
            passwordNodes.add(node)
            Log.d(TAG, "Password field detected: id=$idEntry, hint=$hintText")
        } else if (isUsername) {
            usernameNodes.add(node)
            Log.d(TAG, "Username field detected: id=$idEntry, hint=$hintText")
        }

        // Recursively crawl children
        for (i in 0 until node.childCount) {
            traverseNode(node.getChildAt(i))
        }
    }
}
