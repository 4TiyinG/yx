package app.dsh.mobile.engine

import android.content.Context
import app.dsh.mobile.DshApp
import java.io.File

/**
 * Linux 环境伪装：底层仍是原生 Android，只做“识别层”兼容。
 *
 * 通过 NODE_OPTIONS=--require 预加载脚本，让插件看到 Debian/Linux 的
 * platform / os.type / os.release / /etc/os-release / apt-get 等；
 * 不启动容器、不改变 bionic ABI。
 */
object FakeLinuxEnv {
    private const val KEY = "fake_linux_env"

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(DshApp.PREFS_UI, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }

    /** 幂等部署 preload + 命令桩，返回 fake-linux 目录。 */
    fun ensure(ctx: Context): File {
        val dir = File(EngineConfig.engineRoot(ctx), "fake-linux")
        val bin = File(dir, "bin")
        dir.mkdirs()
        bin.mkdirs()
        write(File(dir, "preload.js"), PRELOAD)
        write(File(bin, "uname"), SH_UNAME)
        write(File(bin, "lsb_release"), SH_LSB)
        write(File(bin, "apt-get"), SH_APT)
        write(File(bin, "dpkg"), SH_DPKG)
        return dir
    }

    private fun write(file: File, content: String) {
        if (!file.isFile || file.readText() != content) file.writeText(content)
        file.setExecutable(true, false)
    }

    private val PRELOAD = """
// [dsh-android] fake Linux environment — native Android, identification layer only.
(function () {
  try {
    // 包管理器（corepack/pnpm/npm/yarn）不能伪装：否则会被误判为 glibc Linux，
    // 去执行不存在的 pnpm-native，导致插件系统 ENOENT。
    var pm = ((process.argv || []).join(' ') + ' ' + String(process.env.npm_execpath || '')).toLowerCase();
    if (/corepack|pnpm|npm|yarn/.test(pm)) return;
    var fs = require('fs');
    var os = require('os');
    var cp = require('child_process');
    var RELEASE = '5.15.0-dsh';
    var OS_RELEASE = 'PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"\nNAME="Debian GNU/Linux"\nVERSION_ID="12"\nVERSION="12 (bookworm)"\nID=debian\nHOME_URL="https://www.debian.org/"\n';
    function def(obj, key, val) {
      try { Object.defineProperty(obj, key, { value: val, configurable: true, writable: true }); } catch (e) {}
    }
    def(process, 'platform', 'linux');
    os.platform = function () { return 'linux'; };
    os.type = function () { return 'Linux'; };
    os.release = function () { return RELEASE; };
    os.version = function () { return '#1 SMP PREEMPT_DYNAMIC dsh-mobile'; };
    os.machine = function () { return process.arch === 'arm64' ? 'aarch64' : 'x86_64'; };

    var readFileSync = fs.readFileSync;
    fs.readFileSync = function (p) {
      if (typeof p === 'string') {
        if (p === '/etc/os-release' || p === '/usr/lib/os-release') return Buffer.from(OS_RELEASE);
        if (p === '/etc/debian_version') return Buffer.from('12.5\n');
        if (p === '/etc/lsb-release') return Buffer.from('DISTRIB_ID=Debian\nDISTRIB_RELEASE=12\nDISTRIB_CODENAME=bookworm\n');
      }
      return readFileSync.apply(fs, arguments);
    };
    var existsSync = fs.existsSync;
    fs.existsSync = function (p) {
      if (p === '/etc/os-release' || p === '/usr/lib/os-release' ||
          p === '/usr/bin/apt-get' || p === '/usr/bin/dpkg' || p === '/usr/bin/lsb_release') return true;
      return existsSync.apply(fs, arguments);
    };

    var LSB = 'Distributor ID:\tDebian\nDescription:\tDebian GNU/Linux 12 (bookworm)\nRelease:\t12\nCodename:\tbookworm\n';
    var UNAME = 'Linux dsh-mobile 5.15.0-dsh #1 SMP PREEMPT_DYNAMIC aarch64 GNU/Linux\n';
    function fakeOutput(cmd) {
      var s = String(cmd || '');
      if (/^\s*lsb_release\b/.test(s)) return LSB;
      if (/^\s*uname\b/.test(s)) return UNAME;
      if (/^\s*dpkg\s+--print-architecture\b/.test(s)) return 'arm64\n';
      if (/^\s*apt-get\s+(-v|--version)\b/.test(s)) return 'apt 2.6.1 (arm64)\n';
      return null;
    }
    var execSync = cp.execSync;
    cp.execSync = function (cmd) {
      var fake = fakeOutput(cmd);
      if (fake !== null) return Buffer.from(fake);
      return execSync.apply(cp, arguments);
    };
    var exec = cp.exec;
    cp.exec = function (cmd, a, b, c) {
      var fake = fakeOutput(cmd);
      if (fake === null) return exec.apply(cp, arguments);
      var cb = typeof a === 'function' ? a : (typeof b === 'function' ? b : (typeof c === 'function' ? c : null));
      if (cb) { process.nextTick(function () { cb(null, fake, ''); }); return { on: function(){}, kill: function(){} }; }
      return fake;
    };

    try {
      var report = process.report;
      if (report && typeof report.getReport === 'function') {
        var getReport = report.getReport.bind(report);
        report.getReport = function () {
          var r = getReport();
          try { if (r && r.header) r.header.glibcVersionRuntime = '2.36'; } catch (e) {}
          return r;
        };
      }
    } catch (e) {}
  } catch (e) {}
})();
"""

    private val SH_UNAME = """#!/system/bin/sh
echo "Linux dsh-mobile 5.15.0-dsh #1 SMP PREEMPT_DYNAMIC aarch64 GNU/Linux"
"""

    private val SH_LSB = """#!/system/bin/sh
echo "Distributor ID:	Debian"
echo "Description:	Debian GNU/Linux 12 (bookworm)"
echo "Release:	12"
echo "Codename:	bookworm"
"""

    private val SH_APT = """#!/system/bin/sh
case "${'$'}1" in
  -v|--version)
    echo "apt 2.6.1 (arm64)"
    exit 0
    ;;
esac
echo "E: fake-linux: native Android cannot install Debian packages; use Extension Center" >&2
exit 100
"""

    private val SH_DPKG = """#!/system/bin/sh
case "${'$'}1" in
  --print-architecture)
    echo "arm64"
    exit 0
    ;;
  -l|--list)
    echo "Desired=Unknown/Install/Remove/Purge/Hold"
    echo "||/ Name Version Architecture Description"
    exit 0
    ;;
esac
exit 1
"""
}