import XCTest
import ComposeApp
@testable import IMAB

/// The Swift half of encryption and key storage, used through the shared Kotlin code as the app does.
final class NativeServicesTests: XCTestCase {
    private let services = IosNativeServices()

    override func setUp() {
        NativeServicesKt.installServicesForTests(services: services)
    }

    func testKotlinCryptoSealsAndOpensThroughCryptoKit() throws {
        let crypto = Crypto.shared
        let key = crypto.randomBytes(count: 32)
        let aad = "imab-encrypted|v1|header".data(using: .utf8)!.kotlinBytes
        let sealed = crypto.encrypt(key: key, plaintext: "Corner Bakery -12.34".data(using: .utf8)!.kotlinBytes, associatedData: aad)
        XCTAssertEqual(Int(sealed.size), 12 + 20 + 16, "nonce + text + tag, like Android and desktop")
        let opened = crypto.decrypt(key: key, sealed: sealed, associatedData: aad)
        XCTAssertEqual(String(data: opened.data, encoding: .utf8), "Corner Bakery -12.34")
    }

    func testWrongKeyOrChangedDataIsRejected() {
        let key = Data(repeating: 7, count: 32)
        let aad = Data("header".utf8)
        let sealed = services.sealAesGcm(key: key, plaintext: Data("budget".utf8), associatedData: aad)!
        XCTAssertNil(services.openAesGcm(key: Data(repeating: 8, count: 32), sealed: sealed, associatedData: aad))
        XCTAssertNil(services.openAesGcm(key: key, sealed: sealed, associatedData: Data("other header".utf8)))
        var changed = sealed
        changed[14] ^= 1
        XCTAssertNil(services.openAesGcm(key: key, sealed: changed, associatedData: aad))
        XCTAssertNil(services.sealAesGcm(key: Data(repeating: 1, count: 16), plaintext: Data(), associatedData: aad), "only 256-bit keys")
    }

    func testTheKeychainKeepsReplacesAndForgetsTheKey() {
        services.deleteKey()
        XCTAssertNil(services.loadKey())
        XCTAssertTrue(services.saveKey(value: "first"))
        XCTAssertEqual(services.loadKey(), "first")
        XCTAssertTrue(services.saveKey(value: "second"))
        XCTAssertEqual(services.loadKey(), "second")
        services.deleteKey()
        XCTAssertNil(services.loadKey())
    }
}

private extension Data {
    var kotlinBytes: KotlinByteArray {
        let out = KotlinByteArray(size: Int32(count))
        for (i, b) in enumerated() { out.set(index: Int32(i), value: Int8(bitPattern: b)) }
        return out
    }
}

private extension KotlinByteArray {
    var data: Data { Data((0..<size).map { UInt8(bitPattern: get(index: $0)) }) }
}
