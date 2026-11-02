import CryptoKit
import Foundation
import Security
import ComposeApp

/**
 * What the shared Kotlin code can't reach directly: AES-GCM from CryptoKit, and the Keychain for
 * the budget's key. The sealed format is nonce (12 bytes) + ciphertext + tag (16 bytes), the same
 * as Android and desktop produce, so every device opens the same encrypted budget.
 */
final class IosNativeServices: NSObject, NativeServices {
    func sealAesGcm(key: Data, plaintext: Data, associatedData: Data) -> Data? {
        guard key.count == 32 else { return nil }
        return try? AES.GCM.seal(plaintext, using: SymmetricKey(data: key), authenticating: associatedData).combined
    }

    func openAesGcm(key: Data, sealed: Data, associatedData: Data) -> Data? {
        guard key.count == 32, let box = try? AES.GCM.SealedBox(combined: sealed) else { return nil }
        return try? AES.GCM.open(box, using: SymmetricKey(data: key), authenticating: associatedData)
    }

    // The key stays on this device: not synced to iCloud Keychain, not in backups that could be
    // restored elsewhere, and only readable once the phone has been unlocked after starting.
    private let query: [String: Any] = [
        kSecClass as String: kSecClassGenericPassword,
        kSecAttrService as String: "IMAB budget key",
        kSecAttrAccount as String: "budget",
    ]

    func loadKey() -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess, let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func saveKey(value: String) -> Bool {
        SecItemDelete(query as CFDictionary)
        var q = query
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    func deleteKey() {
        SecItemDelete(query as CFDictionary)
    }
}
