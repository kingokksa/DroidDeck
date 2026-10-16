import re
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


def read(path):
    return path.read_text(encoding='utf-8')


BUILD = ROOT / 'tools/desktop-kde/build.sh'
WORKFLOW = ROOT / '.github/workflows/build-desktop-kde.yml'
DESKTOP = ROOT / 'tools/linuxfs/desktop/droiddeck-desktop'
STEAM_LAUNCH = ROOT / 'tools/linuxfs/overlay/usr/local/bin/droiddeck-steam-launch'
SESSION_SERVICE = ROOT / 'app/src/main/java/com/droiddeck/launcher/session/SessionService.kt'
LANGUAGE_KT = ROOT / 'app/src/main/java/com/droiddeck/launcher/runtime/DesktopLanguage.kt'
APP_LANGUAGE_KT = ROOT / 'app/src/main/java/com/droiddeck/launcher/core/AppLanguage.kt'
MAIN_ACTIVITY = ROOT / 'app/src/main/java/com/droiddeck/launcher/MainActivity.kt'
SESSION_ACTIVITY = ROOT / 'app/src/main/java/com/droiddeck/launcher/SessionActivity.kt'

CATALOGUES = {
    'ar', 'bg', 'cs', 'da', 'de', 'el', 'es', 'fi', 'fr', 'hi', 'hu', 'id', 'it', 'ja', 'ko', 'ms',
    'nb', 'nl', 'pl', 'pt', 'pt_BR', 'ro', 'ru', 'sv', 'th', 'tr', 'uk', 'vi', 'zh_CN', 'zh_TW',
}


def desktop_tag(tag):
    overrides = {'es-419': 'es', 'pt-PT': 'pt', 'zh-CN': 'zh_CN', 'zh-TW': 'zh_TW', 'zh-HK': 'zh_TW'}
    return overrides.get(tag, tag.replace('-', '_'))


class DesktopLanguageBuildTest(unittest.TestCase):
    def setUp(self):
        self.build = read(BUILD)

    def test_the_main_package_keeps_english_and_every_other_language_becomes_a_pack(self):
        loop = self.build.split('usr/share/locale/*/', 1)[1].split('rm -rf "$work/root/usr/share/doc"', 1)[0]
        self.assertIn("case $loc in en*) continue ;; esac", loop)
        self.assertIn('[ -d "$dir/LC_MESSAGES" ] || continue', loop)
        self.assertIn('desktop-kde-i18n-$loc.tar.zst', loop)
        self.assertIn('> "$out/desktop-kde.i18n.txt"', loop)
        self.assertIn('find "$work/root/usr/share/locale" -mindepth 1 -maxdepth 1 -type d ! -name \'en*\'',
                      self.build.split('i18n.txt"', 1)[1])

    def test_the_index_lines_carry_what_the_app_verifies(self):
        record = re.search(r"printf '%s %s %s\\n' \"\$loc\" \"\$\(sha256sum \"\$file\" \| cut -d' ' -f1\)\" \"\$\(stat -c %s \"\$file\"\)\"",
                           self.build)
        self.assertIsNotNone(record)
        line = 'de ' + 'a' * 64 + ' 262144'
        fields = line.split()
        self.assertEqual(3, len(fields))
        self.assertTrue(re.fullmatch(r'[0-9a-f]{64}', fields[1]))
        self.assertEqual(262144, int(fields[2]))

    def test_the_workflow_ships_the_index_and_the_packs(self):
        workflow = read(WORKFLOW)
        self.assertIn('out/desktop-kde.i18n.txt', workflow)
        self.assertEqual(1, len([line for line in workflow.splitlines()
                                 if line.strip() == 'out/desktop-kde-i18n-*.tar.zst']))
        release = workflow.split('gh release create', 1)[1]
        self.assertIn('out/desktop-kde.i18n.txt', release)
        self.assertIn('out/desktop-kde-i18n-*.tar.zst', release)

    def test_the_app_reads_the_packs_from_the_same_release(self):
        source = read(LANGUAGE_KT)
        self.assertIn('BuildConfig.DESKTOP_KDE_URL.substringBeforeLast(\'/\')', source)
        self.assertIn('"desktop-kde.i18n.txt"', source)
        self.assertIn('"desktop-kde-i18n"', source)
        self.assertIn('"usr/share/locale"', source)

    def test_the_app_only_claims_the_catalogue_directories_it_wrote(self):
        source = read(LANGUAGE_KT)
        self.assertIn('private const val OWNED = ".droiddeck-pack"', source)
        self.assertIn('private fun installed(root: File, tag: String, sha256: String): Boolean', source)
        self.assertIn('private fun forget(root: File, tag: String)', source)
        self.assertIn('if (File(dir, OWNED).isFile) FileUtils.delete(dir)', source)
        self.assertIn('return !installed(root, wanted, have.sha256)', source)
        self.assertIn('FileUtils.writeString(marker, pack.sha256)', source)

    def test_every_offered_language_has_a_catalogue_the_packages_actually_ship(self):
        source = read(APP_LANGUAGE_KT)
        supported = re.findall(r'"([^"]+)"', re.search(r'val supported: List<String> = listOf\((.*?)\n    \)',
                                                       source, re.S).group(1))
        self.assertIn('zh-HK', supported)
        for tag in supported:
            if tag == 'en':
                continue
            self.assertIn(desktop_tag(tag), CATALOGUES, tag)


class DesktopLanguageSessionTest(unittest.TestCase):
    def setUp(self):
        self.desktop = read(DESKTOP)

    def test_the_desktop_localizes_only_after_the_locale_data_exists(self):
        block = self.desktop.split('name=${BL_DESKTOP_LANG:-}', 1)[1].split('\nfind "$XDG_RUNTIME_DIR"', 1)[0]
        self.assertIn('localedef -i', block)
        self.assertIn('cd /usr/lib/locale && localedef', block)
        self.assertIn('/usr/lib/locale/.droiddeck-locale', block)
        self.assertIn('[ -e "$stamp" ] || name=', block)
        self.assertLess(block.index('[ -e "$stamp" ] || name='), block.index('export LANG=$name'))
        self.assertIn('BL_DESKTOP_MESSAGES', block)

    def test_the_desktop_tells_plasma_which_catalogue_to_use(self):
        block = self.desktop.split('name=${BL_DESKTOP_LANG:-}', 1)[1].split('\nfind "$XDG_RUNTIME_DIR"', 1)[0]
        self.assertIn("printf '[Formats]\\nLANG=%s\\n[Translations]\\nLANGUAGE=%s\\n'", block)
        self.assertIn("printf '[Formats]\\nLANG=C.UTF-8\\n'", block)
        self.assertIn('>"$HOME/.config/plasma-localerc"', block)
        self.assertLess(block.index('export LANG=$name LANGUAGE=$messages'), block.index('plasma-localerc'))
        self.assertIn('catalogs $(ls "$catalogs" 2>/dev/null | wc -l) files, plasmashell', block)

    def test_the_stamp_directory_belongs_to_the_desktop(self):
        self.assertIn('/usr/lib/locale/.droiddeck-locale/$name', read(DESKTOP))

    def test_a_game_session_from_the_desktop_keeps_the_apps_locale(self):
        launcher = read(STEAM_LAUNCH)
        branch = launcher.split('if [ "$ui" = desktop ]', 1)[1].split('exec /usr/local/bin/droiddeck-session steam', 1)[0]
        self.assertIn('export LANG=C.UTF-8', branch)
        self.assertIn('unset LANGUAGE LC_ALL', branch)

    def test_the_session_hands_the_desktop_both_the_locale_and_its_catalogue(self):
        source = read(SESSION_SERVICE)
        self.assertIn('"BL_DESKTOP_LANG=" + com.droiddeck.launcher.runtime.DesktopLanguage.locale(this)', source)
        self.assertIn('"BL_DESKTOP_MESSAGES=" + com.droiddeck.launcher.runtime.DesktopLanguage.wanted(this)', source)
        language = read(LANGUAGE_KT)
        self.assertIn('AppLanguage.linuxLocale(AppLanguage.selectedTag(context))', language)
        self.assertIn('AppLanguage.desktopTag(AppLanguage.selectedTag(context))', language)

    def test_the_packs_are_fetched_and_retried(self):
        language = read(LANGUAGE_KT)
        self.assertIn('Downloader.downloadString(url)', language)
        self.assertIn('Downloader.downloadFile(packUrl(pack.tag), download, true)', language)
        self.assertIn('private const val ATTEMPTS = 3', language)
        self.assertIn('private const val COOLDOWN_MS = 300_000', language)
        self.assertIn('if (!ok) return context.getString(R.string.user_apps_download_failed)', language)

    def test_the_launch_never_queues_behind_a_running_sync(self):
        language = read(LANGUAGE_KT)
        self.assertIn('private const val PREPARE_WAIT_MS = 75_000L', language)
        self.assertIn('guarded(context, listener, 1, PREPARE_WAIT_MS)', language)
        self.assertIn('lock.tryLock(waitMs', language)

    def test_the_launcher_retries_the_sync_every_time_it_comes_back(self):
        resume = read(MAIN_ACTIVITY).split('override fun onResume()', 1)[1].split('override fun onStart()', 1)[0]
        self.assertIn('syncDesktopLanguage()', resume)

    def test_the_desktop_checks_its_translations_before_it_starts(self):
        session = read(SESSION_ACTIVITY)
        self.assertIn('com.droiddeck.launcher.runtime.DesktopLanguage.needed(this)', session)
        self.assertIn('com.droiddeck.launcher.runtime.DesktopLanguage.prepare(this, progressFor(DESKTOP_LINES, 0))', session)
        self.assertLess(session.index('if (!catalog.desktopInstalled(this))'), session.index('DesktopLanguage.prepare'))
        language = read(LANGUAGE_KT)
        self.assertIn('fun prepare(context: Context, listener: LinuxRuntimeInstaller.ProgressListener? = null): String? =', language)
        self.assertIn('guarded(context, listener, 1, PREPARE_WAIT_MS)', language)


if __name__ == '__main__':
    unittest.main()
