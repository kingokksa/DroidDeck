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

    private fun keep(root: File, pack: Pack?): Boolean {
        val have = record(root)
        if (pack == null) return have == null
        if (have?.tag != pack.tag || have.sha256 != pack.sha256) return false
        return File(root, "$LOCALES/${sanitize(pack.tag)}").isDirectory
    }

    private fun directory(root: File, tag: String): File? =
        DesktopFileRemoval.safeFile(root, "$LOCALES/${sanitize(tag)}")

    @Synchronized
    fun sync(context: Context, listener: LinuxRuntimeInstaller.ProgressListener? = null): String? {
        val root = LinuxRuntime.rootDir(context)
        if (!DesktopCatalog.desktopInstalled(context)) return null
        val wanted = wanted(context)
        val pack = if (wanted.isEmpty()) null else {
            val index = text(indexUrl())
                ?: return context.getString(R.string.user_apps_download_failed)
            parse(index)[wanted]
        }
        if (pack == null && wanted.isNotEmpty()) Log.w(TAG, "the desktop has no $wanted catalogs")
        if (keep(root, pack)) return null
        if (pack != null) fetch(context, root, pack, listener)?.let { return it }
        val previous = record(root)
        if (previous != null && previous.tag != (pack?.tag ?: "")) directory(root, previous.tag)?.let(FileUtils::delete)
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
        return have?.tag != wanted || !File(root, "$LOCALES/${sanitize(wanted)}").isDirectory
    }

    private fun text(url: String): String? {
        repeat(3) { attempt ->
            Downloader.downloadString(url)?.takeIf { it.isNotBlank() }?.let { return it }
            if (attempt < 2) runCatching { Thread.sleep(1_500L * (attempt + 1)) }
        }
        return null
    }

    private fun retry(step: () -> Boolean): Boolean {
        repeat(3) { attempt ->
            if (step()) return true
            if (attempt < 2) runCatching { Thread.sleep(1_500L * (attempt + 1)) }
        }
        return false
    }

    private fun fetch(context: Context, root: File, pack: Pack, listener: LinuxRuntimeInstaller.ProgressListener?): String? {
        val name = "$ARCHIVE-${sanitize(pack.tag)}.tar.zst"
        val download = File(context.cacheDir, name)
        val ok = retry {
            Downloader.downloadFile(packUrl(pack.tag), download, true) { fraction ->
                listener?.onProgress(LinuxRuntimeInstaller.Step.DOWNLOADING, name, if (fraction < 0) -1 else Math.round(fraction * 100f))
            }
        }
        if (!ok) {
            download.delete()
            return context.getString(R.string.user_apps_download_failed)
        }
        if (!pack.sha256.equals(runCatching { Hashes.sha256(download) }.getOrNull(), ignoreCase = true)) {
            download.delete()
            return context.getString(R.string.deskpkg_checksum_mismatch)
        }
        directory(root, pack.tag)?.let(FileUtils::delete)
        if (!LinuxRuntimeInstaller.extract(context, download, root, listener)) {
            download.delete()
            return context.getString(R.string.deskpkg_extract_failed)
        }
        download.delete()
        return null
    }

    fun clear(root: File) {
        record(root)?.let { directory(root, it.tag)?.let(FileUtils::delete) }
        File(root, STATE).delete()
    }
}
