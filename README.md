# Password Manager

A native Android password manager built with Kotlin and Jetpack Compose. Passwords are encrypted at rest using the Android Keystore (AES-256-GCM) — the encryption key never leaves the device's secure hardware. This is a vibe-coded project developed iteratively using Android Studio and AI-assisted tools.

## Features

- **Vault screen** — scrollable list of all saved passwords
- **Add password screen** — title/website, username, and password fields
- **Password generator** — one-tap 16-character strong password generation
- **Tap to reveal** — passwords shown as `••••••••` until tapped
- **Copy to clipboard** — copy decrypted password with one button when revealed
- **Delete** — swipe-free single-tap delete per entry
- **AES-256-GCM encryption** — passwords encrypted via Android Keystore before being stored in the local Room database

## Architecture

```
com.example.passwordmanager/
├── VaultApplication.kt          # Hilt app entry point
├── MainActivity.kt              # Nav host (vault → add routes)
├── data/
│   ├── PasswordEntry.kt         # Room entity (id, title, username, encryptedPassword)
│   ├── PasswordDao.kt           # Room DAO — getAllPasswords(), insert, delete
│   ├── AppDatabase.kt           # Room database (vault_db, v1)
│   └── CryptoManager.kt        # AES-256-GCM encrypt/decrypt via Android Keystore
├── di/
│   └── DataModule.kt            # Hilt module — provides DB, DAO, CryptoManager
└── ui/
    ├── VaultScreen.kt           # Vault list UI + PasswordCard composable
    ├── AddPasswordScreen.kt     # Add/generate password form
    └── VaultViewModel.kt        # Hilt ViewModel — passwords StateFlow, add/delete/decrypt
```

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

## Getting Started

1. Clone the repository:
   ```bash
   git clone https://github.com/flyboy-byte/password-manager.git
   ```
2. Open the project root in **Android Studio** (Ladybug / 2024.2 or newer recommended).
3. Let Gradle sync complete.
4. Run on an emulator (API 24+) or a physical device.

> Encryption uses the Android Keystore. The AES-256-GCM key is hardware-bound and will not survive a factory reset or app reinstall — exported backups are disabled by default for this reason.

## License

MIT — see [LICENSE](LICENSE) for details.
