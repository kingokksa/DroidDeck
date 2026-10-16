package com.droiddeck.launcher.runtime

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.core.Downloader
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import java.io.File

object DesktopLanguage {
    private const val TAG = "DesktopLanguage"
    private const val STATE = ".droiddeck-desktop-language"
    private const val INDEX = "desktop-kde.i18n.txt"
    private const val ARCHIVE = "desktop-kde-i18n"
    private const val LOCALES = "usr/share/locale"
    private const val OWNED = ".droiddeck-pack"
    private const val ATTEMPTS = 3
    private const val TIMEOUT_MS = 120_000
    private const val COOLDOWN_MS = 300_000

    @Volatile
    private var failingUntil = 0L

    internal class Pack(val tag: String, val sha256: String)

    fun wanted(context: Context): String =
        AppLanguage.desktopTag(AppLanguage.selectedTag(context)).takeIf { it.isNotEmpty() && it != "en" }.orEmpty()

    fun locale(context: Context): String = AppLanguage.linuxLocale(AppLanguage.selectedTag(context)).orEmpty()

    private fun base() = BuildConfig.DESKTOP_KDE_URL.substringBeforeLast('/')

    internal fun indexUrl() = base() + "/" + INDEX

    internal fun packUrl(tag: String) = base() + "/" + ARCHIVE + "-" + sanitize(tag) + ".tar.zst"

    internal fun sanitize(tag: String) = tag.replace(Regex("[^A-Za-z0-9@._-]"), "_")

    internal fun parse(body: String): Map<String, Pack> {
        val packs = LinkedHashMap<String, Pack>()
        for (line in body.lineSequence()) {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 3 || !Regex("^[0-9a-f]{64}$").matches(fields[1])) continue
            packs[fields[0]] = Pack(fields[0], fields[1])
        }
        return packs
    }

    private fun record(root: File): Pack? {
        val fields = FileUtils.readString(File(root, STATE))?.trim().orEmpty().split(Regex("\\s+"))
        if (fields.size < 2 || !Regex("^[0-9a-f]{64}$").matches(fields[1])) return null
        return Pack(fields[0], fields[1])
    }

    private fun installed(root: File, tag: String, sha256: String): Boolean {
        val dir = directory(root, tag) ?: return false
        val marker = File(dir, OWNED)
        return marker.isFile && FileUtils.readString(marker)?.trim() == sha256 && File(dir, "LC_MESSAGES").isDirectory
    }

    private fun keep(root: File, pack: Pack?): Boolean {
        val have = record(root)
        if (pack == null) return have == null
        if (have?.tag != pack.tag || have.sha256 != pack.sha256) return false
        return installed(root, pack.tag, pack.sha256)
    }

    private fun directory(root: File, tag: String): File? =
        DesktopFileRemoval.safeFile(root, "$LOCALES/${sanitize(tag)}")

    private fun forget(root: File, tag: String) {
        val dir = directory(root, tag) ?: return
        if (File(dir, OWNED).isFile) FileUtils.delete(dir)
    }

    @Synchronized
    fun sync(context: Context, listener: LinuxRuntimeInstaller.ProgressListener? = null): String? {
        if (System.currentTimeMillis() < failingUntil) return context.getString(R.string.user_apps_download_failed)
        return run(context, listener, ATTEMPTS)
    }

    @Synchronized
    fun prepare(context: Context, listener: LinuxRuntimeInstaller.ProgressListener? = null): String? =
        run(context, listener, 1)

    private fun run(context: Context, listener: LinuxRuntimeInstaller.ProgressListener?, attempts: Int): String? {
        val problem = install(context, listener, attempts)
        failingUntil = if (problem == null) 0L else System.currentTimeMillis() + COOLDOWN_MS
        return problem
    }

    private fun install(context: Context, listener: LinuxRuntimeInstaller.ProgressListener?, attempts: Int): String? {
        val root = LinuxRuntime.rootDir(context)
        if (!DesktopCatalog.desktopInstalled(context)) return null
        val wanted = wanted(context)
        val pack = if (wanted.isEmpty()) null else {
            val index = text(indexUrl(), attempts)
                ?: return context.getString(R.string.user_apps_download_failed)
            parse(index)[wanted]
        }
        if (pack == null && wanted.isNotEmpty()) Log.w(TAG, "the desktop has no $wanted catalogs")
        if (keep(root, pack)) return null
        if (pack != null) fetch(context, root, pack, listener, attempts)?.let { return it }
        val previous = record(root)
        if (previous != null && previous.tag != (pack?.tag ?: "")) forget(root, previous.tag)
        if (!FileUtils.writeString(File(root, STATE), pack?.let { "${it.tag} ${it.sha256}" } ?: ""))
            return context.getString(R.string.deskpkg_install_failed)
        Log.i(TAG, "desktop language: ${pack?.tag ?: "en"}")
        return null
    }

    fun needed(context: Context): Boolean {
        if (!DesktopCatalog.desktopInstalled(context)) return false
        val root = LinuxRuntime.rootDir(context)
        val have = record(root)
        val wanted = wanted(context)
        if (wanted.isEmpty()) return have != null
        if (have == null || have.tag != wanted) return true
        return !installed(root, wanted, have.sha256)
    }

    private fun text(url: String, attempts: Int): String? {
        repeat(attempts) { attempt ->
            Downloader.downloadString(url, TIMEOUT_MS)?.takeIf { it.isNotBlank() }?.let { return it }
            if (attempt < attempts - 1) runCatching { Thread.sleep(2_000L * (attempt + 1)) }
        }
        return null
    }

    private fun retry(attempts: Int, step: () -> Boolean): Boolean {
        repeat(attempts) { attempt ->
            if (step()) return true
            if (attempt < attempts - 1) runCatching { Thread.sleep(2_000L * (attempt + 1)) }
        }
        return false
    }

    private fun fetch(context: Context, root: File, pack: Pack, listener: LinuxRuntimeInstaller.ProgressListener?, attempts: Int): String? {
        val name = "$ARCHIVE-${sanitize(pack.tag)}.tar.zst"
        val download = File(context.cacheDir, name)
        val ok = retry(attempts) {
            Downloader.downloadFile(packUrl(pack.tag), download, true, TIMEOUT_MS) { fraction ->
                listener?.onProgress(LinuxRuntimeInstaller.Step.DOWNLOADING, name, if (fraction < 0) -1 else Math.round(fraction * 100f))
            }
        }
        if (!ok) return context.getString(R.string.user_apps_download_failed)
        if (!pack.sha256.equals(runCatching { Hashes.sha256(download) }.getOrNull(), ignoreCase = true)) {
            download.delete()
            return context.getString(R.string.deskpkg_checksum_mismatch)
        }
        forget(root, pack.tag)
        if (!LinuxRuntimeInstaller.extract(context, download, root, listener)) {
            download.delete()
            return context.getString(R.string.deskpkg_extract_failed)
        }
        download.delete()
        val marker = File(root, "$LOCALES/${sanitize(pack.tag)}/$OWNED")
        if (!FileUtils.writeString(marker, pack.sha256)) return context.getString(R.string.deskpkg_install_failed)
        return null
    }

    fun clear(root: File) {
        record(root)?.let { forget(root, it.tag) }
        File(root, STATE).delete()
    }
}
