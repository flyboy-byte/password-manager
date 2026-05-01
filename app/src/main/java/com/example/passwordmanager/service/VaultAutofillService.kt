package com.example.passwordmanager.service

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.*
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
import javax.inject.Inject

@AndroidEntryPoint
class VaultAutofillService : AutofillService() {

    @Inject
    lateinit var passwordDao: PasswordDao

    @Inject
    lateinit var cryptoManager: CryptoManager

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val context = request.fillContexts.lastOrNull() ?: return
        val structure = context.structure

        val parser = AssistStructureParser()
        parser.parse(structure)

        if (parser.usernameNodes.isEmpty() && parser.passwordNodes.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        serviceScope.launch {
            val domainOrPackage = parser.webDomain ?: parser.packageName ?: ""
            val allEntries = passwordDao.getAllPasswordsSync()
            
            // Filter entries matching the domain/package
            val matchedEntries = allEntries.filter { entry ->
                entry.title.contains(domainOrPackage, ignoreCase = true)
            }

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

                    val presentation = RemoteViews(packageName, R.layout.autofill_suggestion).apply {
                        setTextViewText(R.id.autofill_username, entry.username)
                        setTextViewText(R.id.autofill_domain, entry.title)
                    }

                    // Map the username and password nodes to their AutofillIds
                    parser.usernameNodes.forEach { node ->
                        datasetBuilder.setValue(
                            node.autofillId!!,
                            AutofillValue.forText(entry.username),
                            presentation
                        )
                    }

                    parser.passwordNodes.forEach { node ->
                        datasetBuilder.setValue(
                            node.autofillId!!,
                            AutofillValue.forText(String(decryptedPassword)),
                            presentation
                        )
                    }

                    responseBuilder.addDataset(datasetBuilder.build())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            // For Save: we set save info to prompt for new passwords if none were autofilled
            val saveInfoBuilder = SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_PASSWORD or SaveInfo.SAVE_DATA_TYPE_USERNAME,
                arrayOf(
                    *(parser.usernameNodes.mapNotNull { it.autofillId }.toTypedArray()),
                    *(parser.passwordNodes.mapNotNull { it.autofillId }.toTypedArray())
                )
            )
            responseBuilder.setSaveInfo(saveInfoBuilder.build())

            withContext(Dispatchers.Main) {
                callback.onSuccess(responseBuilder.build())
            }
        }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        val context = request.fillContexts.lastOrNull() ?: return
        val structure = context.structure

        val parser = AssistStructureParser()
        parser.parse(structure)

        var username = ""
        var password = ""

        parser.usernameNodes.firstOrNull()?.let { node ->
            username = node.autofillValue?.textValue?.toString() ?: node.text?.toString() ?: ""
        }
        
        parser.passwordNodes.firstOrNull()?.let { node ->
            password = node.autofillValue?.textValue?.toString() ?: node.text?.toString() ?: ""
        }

        val domainOrPackage = parser.webDomain ?: parser.packageName ?: "Unknown App"

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
                    
                    withContext(Dispatchers.Main) {
                        callback.onSuccess()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        callback.onFailure(e.message)
                    }
                }
            }
        } else {
            callback.onSuccess()
        }
    }
}
