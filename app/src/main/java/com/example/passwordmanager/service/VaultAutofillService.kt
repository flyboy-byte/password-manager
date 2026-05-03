package com.example.passwordmanager.service

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.*
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.example.passwordmanager.R
import com.example.passwordmanager.data.CryptoManager
import com.example.passwordmanager.data.PasswordDao
import com.example.passwordmanager.data.PasswordEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class VaultAutofillService : AutofillService() {

    @Inject
    lateinit var passwordDao: PasswordDao

    @Inject
    lateinit var cryptoManager: CryptoManager

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "VaultAutofill"
    }

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        val context = request.fillContexts.lastOrNull() ?: return
        val structure = context.structure

        val parser = AssistStructureParser()
        parser.parse(structure)

        val packageName = structure.activityComponent.packageName
        val domain = parser.webDomain

        Log.d(TAG, "Fill Request - App: $packageName, Web Domain: $domain")
        Log.d(TAG, "Found ${parser.usernameNodes.size} username fields and ${parser.passwordNodes.size} password fields")

        if (parser.usernameNodes.isEmpty() && parser.passwordNodes.isEmpty()) {
            Log.d(TAG, "No login fields detected, skipping.")
            callback.onSuccess(null)
            return
        }

        serviceScope.launch {
            val allEntries = passwordDao.getAllPasswordsSync()
            
            // Log domain for debugging
            Log.d(TAG, "Searching for matches for: $domain")

            // Smart Filter: Match title against domain OR package name
            val matchedEntries = allEntries.filter { entry ->
                isSmartMatch(entry.title, domain, packageName)
            }

            Log.d(TAG, "Matched ${matchedEntries.size} entries from vault")

            if (matchedEntries.isEmpty()) {
                withContext(Dispatchers.Main) {
                    callback.onSuccess(null)
                }
                return@launch
            }

            val responseBuilder = FillResponse.Builder()

            for (entry in matchedEntries) {
                try {
                    val decryptedPassword = cryptoManager.decrypt(entry.encryptedPassword)
                    val datasetBuilder = Dataset.Builder()

                    val presentation = RemoteViews(this@VaultAutofillService.packageName, R.layout.autofill_suggestion).apply {
                        setTextViewText(R.id.autofill_username, entry.username)
                        setTextViewText(R.id.autofill_domain, entry.title)
                    }

                    var addedValue = false
                    // Map the username and password nodes to their AutofillIds
                    parser.usernameNodes.forEach { node ->
                        datasetBuilder.setValue(
                            node.autofillId!!,
                            AutofillValue.forText(entry.username),
                            presentation
                        )
                        addedValue = true
                    }

                    parser.passwordNodes.forEach { node ->
                        datasetBuilder.setValue(
                            node.autofillId!!,
                            AutofillValue.forText(String(decryptedPassword)),
                            presentation
                        )
                        addedValue = true
                    }

                    if (addedValue) {
                        responseBuilder.addDataset(datasetBuilder.build())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error building dataset for ${entry.title}", e)
                }
            }
            
            // Allow saving new passwords
            val ids = (parser.usernameNodes + parser.passwordNodes).mapNotNull { it.autofillId }
            if (ids.isNotEmpty()) {
                val saveInfoBuilder = SaveInfo.Builder(
                    SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME,
                    ids.toTypedArray()
                )
                responseBuilder.setSaveInfo(saveInfoBuilder.build())
            }

            val response = responseBuilder.build()
            withContext(Dispatchers.Main) {
                callback.onSuccess(response)
            }
        }
    }

    private fun isSmartMatch(vaultTitle: String, webDomain: String?, appPackage: String): Boolean {
        val title = vaultTitle.lowercase(Locale.ROOT).trim()
        if (title.isEmpty()) return false

        val pkg = appPackage.lowercase(Locale.ROOT).trim()
        val domain = webDomain?.lowercase(Locale.ROOT)?.removePrefix("www.")?.trim()

        // 1. Exact matches are prioritized
        if (pkg == title || domain == title) return true

        // 2. Check Package Segments
        // e.g., vaultTitle="ChatGPT" matches pkg="com.openai.chatgpt"
        val pkgParts = pkg.split(".")
            .filter { it !in listOf("com", "org", "net", "android", "google", "apps", "mobile") }
        if (pkgParts.any { it.contains(title) || title.contains(it) }) return true

        // 3. Check Domain Segments
        // e.g., vaultTitle="Spotify" matches domain="open.spotify.com"
        if (domain != null) {
            val domainParts = domain.split(".")
            if (domainParts.any { it == title || it.contains(title) || title.contains(it) }) return true
        }

        // 4. Broad Substring Match (Fallback)
        if (pkg.contains(title) || title.contains(pkg)) return true

        return false
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        Log.d(TAG, "Save Request Triggered")
        val context = request.fillContexts.lastOrNull() ?: return
        val structure = context.structure

        val parser = AssistStructureParser()
        parser.parse(structure)

        var username = ""
        var password = ""

        // Extract values from nodes
        parser.usernameNodes.forEach { node ->
            val value = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            if (!value.isNullOrBlank()) username = value
        }
        
        parser.passwordNodes.forEach { node ->
            val value = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            if (!value.isNullOrBlank()) password = value
        }

        val domainOrPackage = parser.webDomain ?: structure.activityComponent.packageName ?: "Unknown"

        Log.d(TAG, "Attempting to save: User=$username, Source=$domainOrPackage")

        if (username.isNotBlank() && password.isNotBlank()) {
            serviceScope.launch {
                try {
                    val encryptedPassword = cryptoManager.encrypt(password.toByteArray())
                    val newEntry = PasswordEntry(
                        title = domainOrPackage,
                        username = username,
                        encryptedPassword = encryptedPassword
                    )
                    passwordDao.insertPassword(newEntry)
                    Log.d(TAG, "Successfully saved new password for $domainOrPackage")
                    withContext(Dispatchers.Main) {
                        callback.onSuccess()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save password", e)
                    withContext(Dispatchers.Main) {
                        callback.onFailure(e.message)
                    }
                }
            }
        } else {
            Log.d(TAG, "Save aborted: empty username or password")
            callback.onSuccess()
        }
    }
}
