package com.z.imab

import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Keeps the budget key in the system's secure storage: the login Keychain on a Mac (through the
 * built-in `security` tool), Windows data protection (DPAPI, tied to your Windows account) on
 * Windows. The secret always goes through the tool's standard input, never its command line.
 * Elsewhere nothing is stored and the passphrase is asked when IMAB starts.
 */
internal class DesktopKeyVault(
    private val dataDir: File,
    /** A test copy (IMAB_DATA_DIR) gets its own Keychain item. */
    private val service: String = "IMAB budget key" +
        (System.getenv("IMAB_DATA_DIR")?.let { " (" + Integer.toHexString(it.hashCode()) + ")" } ?: ""),
) {
    private val dpapiFile = File(dataDir, "budget-key.dpapi")

    fun load(): String? = runCatching {
        val encoded = when {
            isMac -> run(listOf("/usr/bin/security", "find-generic-password", "-a", ACCOUNT, "-s", service, "-w"))
            isWindows -> dpapiFile.takeIf { it.isFile }?.let {
                run(powershell(UNPROTECT), input = it.readText())
            }
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()

    fun save(value: String): Boolean = runCatching {
        // Base64 so the Keychain command needs no quoting.
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        when {
            isMac -> {
                run(listOf("/usr/bin/security", "-i"), input = "add-generic-password -U -a $ACCOUNT -s \"$service\" -w $encoded\n")
                load() == value
            }
            isWindows -> {
                val protected = run(powershell(PROTECT), input = encoded)?.trim().orEmpty()
                if (protected.isEmpty()) return false
                dpapiFile.writeText(protected)
                load() == value
            }
            else -> false
        }
    }.getOrDefault(false)

    fun delete() {
        runCatching {
            if (isMac) run(listOf("/usr/bin/security", "delete-generic-password", "-a", ACCOUNT, "-s", service))
            dpapiFile.delete()
        }
    }

    /** Runs a command, feeding [input] to it; returns its output, or null if it failed. */
    private fun run(command: List<String>, input: String? = null): String? {
        val p = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        p.outputStream.use { out -> input?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
        val output = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return null
        }
        return output.takeIf { p.exitValue() == 0 }
    }

    private fun powershell(script: String) = listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)

    private companion object {
        const val ACCOUNT = "imab"
        const val PROTECT =
            "\$s = [Console]::In.ReadToEnd().Trim(); ConvertTo-SecureString \$s -AsPlainText -Force | ConvertFrom-SecureString"
        const val UNPROTECT =
            "\$e = [Console]::In.ReadToEnd().Trim(); \$ss = ConvertTo-SecureString \$e; " +
                "[Runtime.InteropServices.Marshal]::PtrToStringBSTR([Runtime.InteropServices.Marshal]::SecureStringToBSTR(\$ss))"
    }
}
