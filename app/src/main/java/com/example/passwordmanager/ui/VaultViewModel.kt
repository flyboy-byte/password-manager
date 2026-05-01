package com.example.passwordmanager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.passwordmanager.data.CryptoManager
import com.example.passwordmanager.data.PasswordDao
import com.example.passwordmanager.data.PasswordEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val passwordDao: PasswordDao,
    private val cryptoManager: CryptoManager,
) : ViewModel() {

    // Expose the list of passwords as a Flow
    val passwords: StateFlow<List<PasswordEntry>> = passwordDao.getAllPasswords()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun addPassword(title: String, username: String, plaintext: String) {
        viewModelScope.launch {
            try {
                // Encrypt the password before saving
                val encrypted = cryptoManager.encrypt(plaintext.toByteArray(Charsets.UTF_8))
                
                val entry = PasswordEntry(
                    title = title,
                    username = username,
                    encryptedPassword = encrypted
                )
                passwordDao.insertPassword(entry)
            } catch (e: Exception) {
                // In a real app, emit error state to UI
                e.printStackTrace()
            }
        }
    }

    fun decryptPassword(entry: PasswordEntry): String {
        return try {
            val decryptedBytes = cryptoManager.decrypt(entry.encryptedPassword)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            "Error decrypting"
        }
    }

    fun deletePassword(entry: PasswordEntry) {
        viewModelScope.launch {
            passwordDao.deletePassword(entry)
        }
    }
}
