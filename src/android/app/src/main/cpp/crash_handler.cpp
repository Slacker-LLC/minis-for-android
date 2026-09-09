// T283 — native crash → file (NDK signal handler).
//
// Registered at app startup from MinisApp.onCreate via JNI. Catches
// fatal signals raised inside JNI / minisd-adjacent native code /
// pty_bridge / any other native code, writes a one-shot text report to
// the configured logs dir, then hands the signal to the handler that
// was installed before us — debuggerd — so the system tombstone is
// also generated and the app exits like normal.
//
// [T-android-crash-observability] Resetting to SIG_DFL and re-raising
// after our handler runs skips Android's previously-installed debuggerd
// handler. That loses the system tombstone, native backtrace, and the
// "Abort message:" line that is often the most useful SIGABRT evidence.
//
// Two changes preserve that evidence:
//   1. Save the previous handler per signal and CHAIN to it instead of
//      SIG_DFL, so debuggerd can write the real tombstone.
//   2. Write a small unwound backtrace into our own summary, so the
//      app-visible log still contains raw frame addresses even when no
//      one can fetch /data/tombstones.
//
// Strict async-signal-safety: only signal-safe libc calls are used for
// the primary summary. _Unwind_Backtrace is not on POSIX's
// async-signal-safe list, so it runs only after the summary is already
// written and only on the first handler entry.

#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <cstdio>
#include <ctime>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <android/log.h>
#include <unwind.h>

#define LOG_TAG "MinisCrashHandler"

// Plenty of headroom for "<logs_dir>/native-crash-YYYY-MM-DD_HH-MM-SS.log".
static char g_log_dir[512] = {0};

// Reentrancy guard. If the handler crashes itself, the second signal is
// handed straight to the handler that existed before ours.
static volatile sig_atomic_t g_in_handler = 0;

// [T-android-crash-observability] The handlers installed before us —
// on Android that is normally debuggerd, which writes /data/tombstones/.
// Indexed by signal number so each signal chains to its own predecessor.
static struct sigaction g_prev[NSIG];
static volatile sig_atomic_t g_prev_valid[NSIG];

// Frame collector for _Unwind_Backtrace. Fixed capacity, no allocation.
struct BacktraceState {
    void** frames;
    int count;
    int capacity;
};

static _Unwind_Reason_Code unwind_cb(struct _Unwind_Context* ctx, void* arg) {
    BacktraceState* st = static_cast<BacktraceState*>(arg);
    const uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc != 0) {
        if (st->count >= st->capacity) return _URC_END_OF_STACK;
        st->frames[st->count++] = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}

// Append "  #NN 0x…" lines for the current stack. Best-effort: an
// unwind that fails or returns nothing simply yields no lines, and the
// summary above it has already been written to disk regardless.
static void write_backtrace(int fd) {
    void* frames[32];
    BacktraceState st = { frames, 0, 32 };
    _Unwind_Backtrace(unwind_cb, &st);
    if (st.count <= 0) return;

    const char* hdr = "\nBacktrace (raw PCs — symbolize with:\n"
                      "  ndk-stack -sym <symbols-dir> , or\n"
                      "  llvm-addr2line -Cfe <lib>.so <addr>):\n";
    write(fd, hdr, strlen(hdr));

    for (int i = 0; i < st.count; i++) {
        char line[64];
        const int n = snprintf(line, sizeof(line), "  #%02d %p\n", i,
                               frames[i]);
        if (n > 0) write(fd, line, static_cast<size_t>(n));
    }
}

// Signal name lookup — strsignal() is NOT async-signal-safe on all
// libc implementations, so use a hardcoded table.
static const char* signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGSYS:  return "SIGSYS";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN";
    }
}

// [T-android-crash-observability] Pass the signal on to whoever held it
// before us — debuggerd in a normal app process — so the tombstone (and
// with it the "Abort message:" line) still gets written.
//
// SA_SIGINFO handlers are re-invoked with the original siginfo/context
// so the predecessor sees exactly what the kernel delivered. For a plain
// sa_handler we call it directly. Only if there genuinely was no callable
// prior handler do we fall back to reset-and-raise.
static void chain_to_previous(int sig, siginfo_t* info, void* ctx) {
    if (sig > 0 && sig < NSIG && g_prev_valid[sig]) {
        const struct sigaction& prev = g_prev[sig];
        if ((prev.sa_flags & SA_SIGINFO) && prev.sa_sigaction != nullptr) {
            prev.sa_sigaction(sig, info, ctx);
            return;
        }
        if (prev.sa_handler != SIG_DFL && prev.sa_handler != SIG_IGN &&
            prev.sa_handler != nullptr) {
            prev.sa_handler(sig);
            return;
        }
    }

    struct sigaction dfl{};
    dfl.sa_handler = SIG_DFL;
    sigemptyset(&dfl.sa_mask);
    sigaction(sig, &dfl, nullptr);
    raise(sig);
}

static void crash_signal_handler(int sig, siginfo_t* info, void* ctx) {
    // Reentrancy: if we're already in the handler, hand straight over.
    // Avoids an infinite loop if the crash reporter itself faults while
    // still preserving the platform's own crash handling.
    if (g_in_handler) {
        chain_to_previous(sig, info, ctx);
        return;
    }
    g_in_handler = 1;

    if (g_log_dir[0] == 0) {
        chain_to_previous(sig, info, ctx);
        return;
    }

    // Build the per-crash filename. time(NULL) is async-signal-safe;
    // localtime_r is too on bionic. snprintf is documented async-safe
    // by POSIX.
    time_t now = time(nullptr);
    struct tm tm_buf;
    localtime_r(&now, &tm_buf);

    char path[640];
    snprintf(path, sizeof(path),
        "%s/native-crash-%04d-%02d-%02d_%02d-%02d-%02d.log",
        g_log_dir,
        tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
        tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec);

    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        chain_to_previous(sig, info, ctx);
        return;
    }

    // Thread name — the single most useful missing field. /proc/self/comm is a
    // 16-byte name that immediately says WHICH subsystem died (e.g. "Jit thread
    // pool", "native-offload-", "RenderThread").
    char comm[64] = {0};
    {
        int cfd = open("/proc/self/comm", O_RDONLY);
        if (cfd >= 0) {
            ssize_t r = read(cfd, comm, sizeof(comm) - 1);
            if (r > 0) {
                comm[r] = 0;
                for (ssize_t i = 0; i < r; i++) if (comm[i] == '\n') comm[i] = 0;
            }
            close(cfd);
        }
    }
    if (comm[0] == 0) strncpy(comm, "(unknown)", sizeof(comm) - 1);

    const int tid = (int)syscall(SYS_gettid);
    const int code = info ? info->si_code : 0;

    // si_addr is ONLY a fault address for fault-type si_codes. For SI_USER(0) /
    // SI_QUEUE(-1) / SI_TKILL(-6) the kernel fills the _kill{pid,uid} arm of the
    // SAME siginfo union, so reading si_addr yields a meaningless "address".
    const bool addr_is_fault = !(code == 0 || code == -1 || code == -6);

    char buf[1600];
    int n;
    if (addr_is_fault) {
        n = snprintf(buf, sizeof(buf),
            "=== Minis Native Crash ===\n"
            "Time: %04d-%02d-%02d %02d:%02d:%02d\n"
            "Signal: %d (%s)\n"
            "si_code: %d\n"
            "Fault addr: %p\n"
            "PID: %d  TID: %d  Thread: %s\n"
            "\n"
            "(Tombstone with full backtrace written by Android system to "
            "/data/tombstones/ — adb pull or `adb bugreport`.)\n",
            tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
            tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec,
            sig, signal_name(sig), code,
            info ? info->si_addr : nullptr,
            getpid(), tid, comm);
    } else {
        n = snprintf(buf, sizeof(buf),
            "=== Minis Native Crash ===\n"
            "Time: %04d-%02d-%02d %02d:%02d:%02d\n"
            "Signal: %d (%s)\n"
            "si_code: %d (%s)\n"
            "Sent by: pid=%d uid=%d%s\n"
            "Fault addr: n/a (not a fault signal — see note)\n"
            "PID: %d  TID: %d  Thread: %s\n"
            "\n"
            "NOTE: this signal was SENT, not raised by a memory fault, so\n"
            "si_addr carries no address. SIGABRT with si_code=-1 and\n"
            "sender pid == our own pid is the ordinary signature of abort()\n"
            "inside this process (libc assertion, Scudo heap check, ART\n"
            "runtime abort, or a C++ uncaught exception).\n"
            "The REASON is not in siginfo — look for the abort message:\n"
            "  * logcat around this timestamp, tags: scudo / libc / DEBUG\n"
            "    (Scudo prints e.g. \"Scudo ERROR: invalid chunk state\")\n"
            "  * the tombstone's \"Abort message:\" line\n"
            "(Tombstone with full backtrace written by Android system to "
            "/data/tombstones/ — adb pull or `adb bugreport`.)\n",
            tm_buf.tm_year + 1900, tm_buf.tm_mon + 1, tm_buf.tm_mday,
            tm_buf.tm_hour, tm_buf.tm_min, tm_buf.tm_sec,
            sig, signal_name(sig),
            code,
            code == 0 ? "SI_USER" : (code == -1 ? "SI_QUEUE/abort()" : "SI_TKILL"),
            info ? info->si_pid : 0, info ? (int)info->si_uid : 0,
            (info && info->si_pid == getpid()) ? " (this process — self-abort)" : "",
            getpid(), tid, comm);
    }

    if (n > 0) {
        ssize_t written = 0;
        while (written < n) {
            ssize_t w = write(fd, buf + written, n - written);
            if (w <= 0) break;
            written += w;
        }
    }

    // Backtrace LAST. If unwinding is unsafe on a particular device, the
    // primary summary above has already been persisted.
    write_backtrace(fd);
    close(fd);

    // Hand off to Android's pre-existing crash handler so debuggerd still
    // produces the system tombstone and Abort message.
    chain_to_previous(sig, info, ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_openminis_app_crash_NativeCrashHandler_nativeInstall(
        JNIEnv* env, jobject /*thiz*/, jstring jLogDir) {
    if (jLogDir == nullptr) return;
    const char* dir = env->GetStringUTFChars(jLogDir, nullptr);
    if (dir == nullptr) return;
    strncpy(g_log_dir, dir, sizeof(g_log_dir) - 1);
    g_log_dir[sizeof(g_log_dir) - 1] = 0;
    env->ReleaseStringUTFChars(jLogDir, dir);

    // mkdir is fine here — we're on the JVM thread, not in a signal.
    mkdir(g_log_dir, 0755);

    struct sigaction sa{};
    sa.sa_sigaction = crash_signal_handler;
    // SA_ONSTACK gives stack-overflow crashes a chance to run the handler on
    // the alternate signal stack that Android/debuggerd normally installs.
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    // Register for the native failure signals we already capture, but save
    // each predecessor so the platform handler is not destroyed.
    static const int kSignals[] = {
        SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGSYS,
    };
    for (size_t i = 0; i < sizeof(kSignals) / sizeof(kSignals[0]); i++) {
        const int s = kSignals[i];
        struct sigaction old{};
        if (sigaction(s, &sa, &old) == 0) {
            g_prev[s] = old;
            g_prev_valid[s] = 1;
        }
    }

    __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
        "installed: dir=%s (chaining to prior handlers)", g_log_dir);
}
