# Password Manager

A native Android password manager built with Kotlin and Jetpack Compose. Passwords are encrypted at rest using the Android Keystore (AES-256-GCM) — the encryption key never leaves the device's secure hardware. Includes a system-level autofill service that surfaces credentials directly in other apps' login fields.

This is a vibe-coded project developed iteratively using Android Studio and AI-assisted tools.

## Features

- **Vault screen** — scrollable list of all saved passwords
- **Add password screen** — title/website, username, and password fields
- **Password generator** — one-tap 16-character strong password generation
- **Tap to reveal** — passwords shown as `••••••••` until tapped
- **Copy to clipboard** — copy decrypted password with one button when revealed
- **Delete** — single-tap delete per entry
- **AES-256-GCM encryption** — passwords encrypted via Android Keystore before being stored in the local Room database
- **Autofill service** — detects username/password fields in any app or browser, matches saved entries by domain/package name, and fills credentials without leaving the current app. Also prompts to save new credentials when a login form is submitted.

## Architecture

```
com.example.passwordmanager/
├── VaultApplication.kt              # @HiltAndroidApp entry point
├── MainActivity.kt                  # NavHost (vault → add routes)
├── data/
│   ├── PasswordEntry.kt             # Room entity: id, title, username, encryptedPassword
│   ├── PasswordDao.kt               # getAllPasswords() Flow, getAllPasswordsSync(), insert, delete
│   ├── AppDatabase.kt               # Room DB ("vault_db", version 1)
│   └── CryptoManager.kt            # AES-256-GCM via Android Keystore ("vault_key")
├── di/
│   └── DataModule.kt                # Hilt SingletonComponent: CryptoManager, AppDatabase, PasswordDao
├── service/
│   ├── VaultAutofillService.kt      # AutofillService: fill & save credentials system-wide
│   └── AssistStructureParser.kt     # Walks AssistStructure tree, identifies username/password nodes
└── ui/
    ├── VaultScreen.kt               # Password list, tap-to-reveal, copy, delete + FAB
    ├── AddPasswordScreen.kt         # Form: title, username, password; generator; save
    ├── VaultViewModel.kt            # @HiltViewModel; passwords StateFlow; add/delete/decrypt
    └── theme/
        ├── Color.kt
        ├── Theme.kt
        └── Type.kt
```

### Autofill flow

1. Android calls `VaultAutofillService.onFillRequest()` when a login form is detected.
2. `AssistStructureParser` walks the view tree and collects nodes matching username/email and password hints.
3. Entries are fetched synchronously from Room and filtered by `title.contains(webDomain or packageName)`.
4. Matching entries are returned as `Dataset` objects using a `RemoteViews` suggestion chip.
5. When the user submits a new login, `onSaveRequest()` encrypts and persists the credentials.

> To enable: System Settings → Passwords & accounts → Autofill service → select **Vault Autofill**.

## Stack

| Layer | Library | Version |
|---|---|---|
| Language | Kotlin | 2.2.10 |
| UI | Jetpack Compose + Material 3 | BOM 2026.02.01 |
| DI | Hilt | 2.59.2 |
| Database | Room | 2.8.4 |
| Code gen | KSP | 2.2.10-2.0.2 |
| Navigation | Navigation Compose + Hilt Nav | 2.7.7 / 1.2.0 |
| Min SDK | Android 7.0 | API 24 |
| Target SDK | | API 36 |
| Build tools | AGP | 9.2.0 |
| JVM | Java 21 | |

## Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/flyboy-byte/password-manager.git
   ```
2. Open the project root in **Android Studio** (Ladybug / 2024.2 or newer recommended).
3. Let Gradle sync complete.
4. Run on an emulator (API 24+) or a physical device.

> Encryption uses the Android Keystore. The AES-256-GCM key is hardware-bound and will not survive a factory reset or app reinstall — exported backups are disabled by default.

## License

MIT — see [LICENSE](LICENSE) for details.
