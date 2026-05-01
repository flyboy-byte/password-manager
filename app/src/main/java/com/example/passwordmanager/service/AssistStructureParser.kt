package com.example.passwordmanager.service

import android.app.assist.AssistStructure

class AssistStructureParser {
    var webDomain: String? = null
    var packageName: String? = null
    var usernameNodes = mutableListOf<AssistStructure.ViewNode>()
    var passwordNodes = mutableListOf<AssistStructure.ViewNode>()

    fun parse(structure: AssistStructure) {
        val windowCount = structure.windowNodeCount
        for (i in 0 until windowCount) {
            val windowNode = structure.getWindowNodeAt(i)
            val rootNode = windowNode.rootViewNode
            traverseNode(rootNode)
        }
    }

    private fun traverseNode(node: AssistStructure.ViewNode) {
        if (packageName == null && node.idPackage != null) {
            packageName = node.idPackage
        }
        
        if (webDomain == null && node.webDomain != null) {
            webDomain = node.webDomain
        }

        val autofillHints = node.autofillHints
        val hint = node.hint?.toString()?.lowercase() ?: ""
        val text = (node.text?.toString() ?: node.autofillValue?.textValue?.toString() ?: "").lowercase()

        val isUsernameHint = autofillHints?.contains(android.view.View.AUTOFILL_HINT_USERNAME) == true ||
                hint.contains("username") || hint.contains("email") || text.contains("username") || text.contains("email")
                
        val isPasswordHint = autofillHints?.contains(android.view.View.AUTOFILL_HINT_PASSWORD) == true ||
                hint.contains("password") || text.contains("password")

        if (isUsernameHint) {
            usernameNodes.add(node)
        } else if (isPasswordHint) {
            passwordNodes.add(node)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChildAt(i)
            traverseNode(child)
        }
    }
}
