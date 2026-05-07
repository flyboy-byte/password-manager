package com.example.passwordmanager.service

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.*
import android.util.Log
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.example.passwordmanager.R
import com.example.passwordmanager.data.CryptoManager
import com.example.passwordmanager.data.PasswordDao
import com.example.passwordmanager.data.PasswordEntry
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.reflect.Method
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
        val contexts = request.fillContexts
        if (contexts.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        serviceScope.launch {
            var bestResponse: FillResponse? = null

            contexts.forEachIndexed { ctxIndex, ctx ->
                val structure = ctx.structure
                val parser = AssistStructureParser()
                parser.parse(structure)

                val pkg = structure.activityComponent.packageName
                val domain = parser.webDomain
                val focusedId = getFocusedIdCompat(ctx)

                // Credential Metadata Evidence Gathering
                val inputTypeSummary = parser.inputTypeMap.entries.joinToString(", ") { "${it.key}:${it.value}" }
                Log.d(
                    TAG,
                    "EVIDENCE [ctx=$ctxIndex]: pkg=$pkg domain=$domain focusedId=$focusedId " +
                        "htmlCount=${parser.htmlInfoCount} inputTypes=[$inputTypeSummary] " +
                        "usernames=${parser.usernameNodes.size} passwords=${parser.passwordNodes.size}"
                )

                if (parser.usernameNodes.isEmpty() && parser.passwordNodes.isEmpty()) {
                    Log.d(TAG, "ctx[$ctxIndex]: No credential fields found, skipping dataset building.")
                    return@forEachIndexed
                }

                val responseBuilder = FillResponse.Builder()
                val allEntries = passwordDao.getAllPasswordsSync()
                val rankedMatches = SmartMatcher.rankMatches(allEntries, domain, pkg)
                val matchedEntries = rankedMatches.map { it.entry }

                Log.d(TAG, "ctx[$ctxIndex]: matched=${matchedEntries.size} entries for domain=$domain pkg=$pkg")

                val inlineRequest = request.inlineSuggestionsRequest

                if (matchedEntries.isEmpty()) {
                    val feedbackTitle = if (domain != null) "No match for $domain" else "No match for this app"
                    addFeedbackDataset(responseBuilder, feedbackTitle, parser, inlineRequest)
                } else {
                    for (entry in matchedEntries) {
                        try {
                            val decryptedPassword = cryptoManager.decrypt(entry.encryptedPassword)
                            addPasswordDataset(responseBuilder, entry, String(decryptedPassword), parser, inlineRequest)
                        } catch (e: Exception) {
                            Log.e(TAG, "ctx[$ctxIndex]: Decryption/Dataset error", e)
                        }
                    }
                }

                val ids = (parser.usernameNodes + parser.passwordNodes).mapNotNull { it.autofillId }
                if (ids.isNotEmpty()) {
                    val saveInfoBuilder = SaveInfo.Builder(
                        SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME,
                        ids.toTypedArray(),
                    )
                    // Modern flag: trigger save even if views just become invisible (e.g. navigation)
                    saveInfoBuilder.setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE)
                    responseBuilder.setSaveInfo(saveInfoBuilder.build())
                }

                val currentResponse = try { responseBuilder.build() } catch (e: Exception) { null }
                
                // If this context has the focused field, it's our best bet.
                if (currentResponse != null && (bestResponse == null || focusedId != null)) {
                    bestResponse = currentResponse
                }
            }

            withContext(Dispatchers.Main) {
                callback.onSuccess(bestResponse)
            }
        }
    }

    private fun addPasswordDataset(
        builder: FillResponse.Builder,
        entry: PasswordEntry,
        decryptedPassword: String,
        parser: AssistStructureParser,
        inlineRequest: InlineSuggestionsRequest?,
    ) {
        val datasetBuilder = Dataset.Builder()
        val presentation = RemoteViews(packageName, R.layout.autofill_suggestion).apply {
            setTextViewText(R.id.autofill_username, entry.username)
            setTextViewText(R.id.autofill_domain, entry.title)
        }

        // Add Inline Suggestion support if requested by the system/keyboard (Android 11+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            inlineRequest != null && inlineRequest.inlinePresentationSpecs.isNotEmpty()
        ) {
            try {
                val spec = inlineRequest.inlinePresentationSpecs[0]
                val dummyIntent = PendingIntent.getActivity(this, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
                val inlinePresentation = InlinePresentation(
                    InlineSuggestionUi.newContentBuilder(dummyIntent)
                        .setTitle(entry.username)
                        .setSubtitle(entry.title)
                        .build().slice,
                    spec,
                    false
                )
                datasetBuilder.setInlinePresentation(inlinePresentation)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build inline presentation", e)
            }
        }

        parser.usernameNodes.forEach { node ->
            node.autofillId?.let { id ->
                datasetBuilder.setValue(id, AutofillValue.forText(entry.username), presentation)
            }
        }
        parser.passwordNodes.forEach { node ->
            node.autofillId?.let { id ->
                datasetBuilder.setValue(id, AutofillValue.forText(decryptedPassword), presentation)
            }
        }
        builder.addDataset(datasetBuilder.build())
    }

    private fun addFeedbackDataset(
        builder: FillResponse.Builder,
        message: String,
        parser: AssistStructureParser,
        inlineRequest: InlineSuggestionsRequest?,
    ) {
        val datasetBuilder = Dataset.Builder()
        val presentation = RemoteViews(packageName, R.layout.autofill_suggestion).apply {
            setTextViewText(R.id.autofill_username, "Vault")
            setTextViewText(R.id.autofill_domain, message)
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R &&
            inlineRequest != null && inlineRequest.inlinePresentationSpecs.isNotEmpty()
        ) {
            try {
                val spec = inlineRequest.inlinePresentationSpecs[0]
                val dummyIntent = PendingIntent.getActivity(this, 0, Intent(), PendingIntent.FLAG_IMMUTABLE)
                val inlinePresentation = InlinePresentation(
                    InlineSuggestionUi.newContentBuilder(dummyIntent)
                        .setTitle("Vault")
                        .setSubtitle(message)
                        .build().slice,
                    spec,
                    false
                )
                datasetBuilder.setInlinePresentation(inlinePresentation)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to build feedback inline presentation", e)
            }
        }

        val firstId = parser.usernameNodes.firstOrNull()?.autofillId ?: parser.passwordNodes.firstOrNull()?.autofillId
        firstId?.let { id ->
            datasetBuilder.setValue(id, null, presentation)
            builder.addDataset(datasetBuilder.build())
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val context = request.fillContexts.lastOrNull() ?: return
        val parser = AssistStructureParser()
        parser.parse(context.structure)

        var username = ""
        var password = ""

        parser.usernameNodes.forEach { node ->
            val value = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            if (!value.isNullOrBlank()) username = value
        }
        parser.passwordNodes.forEach { node ->
            val value = node.autofillValue?.textValue?.toString() ?: node.text?.toString()
            if (!value.isNullOrBlank()) password = value
        }

        val source = parser.webDomain ?: context.structure.activityComponent.packageName

        Log.d(TAG, "onSaveRequest: source=$source usernameBlank=${username.isBlank()} passwordBlank=${password.isBlank()}")

        if (username.isNotBlank() && password.isNotBlank()) {
            serviceScope.launch {
                try {
                    val encrypted = cryptoManager.encrypt(password.toByteArray())

                    val existing = passwordDao.getAllPasswordsSync().firstOrNull {
                        it.title.equals(source, ignoreCase = true) && it.username.equals(username, ignoreCase = true)
                    }

                    if (existing != null) {
                        Log.d(TAG, "Updating existing credential id=${existing.id} title=${existing.title} username=${existing.username}")
                        passwordDao.insertPassword(
                            PasswordEntry(
                                id = existing.id,
                                title = source,
                                username = username,
                                encryptedPassword = encrypted,
                            ),
                        )
                    } else {
                        Log.d(TAG, "Inserting new credential title=$source username=$username")
                        passwordDao.insertPassword(
                            PasswordEntry(
                                title = source,
                                username = username,
                                encryptedPassword = encrypted,
                            ),
                        )
                    }

                    withContext(Dispatchers.Main) { callback.onSuccess() }
                } catch (e: Exception) {
                    Log.e(TAG, "onSaveRequest failed", e)
                    withContext(Dispatchers.Main) { callback.onFailure(e.message) }
                }
            }
        } else {
            Log.d(TAG, "onSaveRequest skipped insert: missing username or password")
            callback.onSuccess()
        }
    }

    /**
     * Evidence-gathering helper: obtain focusedId via reflection so we compile with minSdk 26.
     */
    private fun getFocusedIdCompat(ctx: FillContext): AutofillId? {
        return try {
            val m: Method = FillContext::class.java.getDeclaredMethod("getFocusedId")
            @Suppress("UNCHECKED_CAST")
            m.invoke(ctx) as? AutofillId
        } catch (_: Throwable) {
            null
        }
    }
}
