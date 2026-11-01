package io.github.mangi.eta.agent.terminal

import java.io.File

/** Звичайний режим не залежить від батьківського каталогу терміналу, створеного Root у старій версії, і не змінює шлях і власника chroot.*/
internal object TerminalPrivateStorage {
    fun workspace(filesDir: File): File = directory(filesDir, "workspace")

    fun prootEnvironment(filesDir: File, distribution: LinuxDistribution): File =
        directory(filesDir, "proot/${distribution.wireName}")

    private fun directory(filesDir: File, relative: String): File {
        val independent = File(filesDir, "terminal-user/$relative")
        val legacy = File(filesDir, "terminal/$relative")
        // Якщо існує нормальне середовище, продовжуйте використовувати вихідне розташування; вибір шляху не зчитує кореневу авторизацію та не переміщує існуючі дані.
        return if (!independent.exists() && legacy.exists()) legacy else independent
    }

    fun isProotPath(path: String?): Boolean =
        path?.let { "/terminal-user/proot/" in it || "/terminal/proot/" in it } == true
}
