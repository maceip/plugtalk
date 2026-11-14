//! The native guard against the crashes.
//!
//! A crash inside the engine of the browser is a signal, not an exception:
//! the handler of Java never sees it and the process is killed by the
//! kernel.  The Java handler of `CrashGuard.kt` can therefore not report
//! it, and the user would see the application disappear without a word.
//!
//! This module installs a handler for the signals that kill a process
//! (`SIGSEGV`, `SIGABRT`, `SIGBUS`, `SIGILL`, `SIGFPE`) and writes a report
//! with them.  The report is written with the write(2) call of the C
//! library alone: the handler of a signal runs in a context where the
//! allocator of Rust is not safe to use any more, and a crash inside the
//! crash handler would lose the report.
//!
//! After the report is written the signal is given back to the default
//! handler of the platform, so that the system still writes its own trace
//! (the tombstone) and the process dies the way it would have died without
//! the guard.  The Java side reads the report of the last crash on the next
//! start and shows it to the user.

use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

/// The signals that are caught.
const SIGNALS: [i32; 5] = [
    libc::SIGSEGV,
    libc::SIGABRT,
    libc::SIGBUS,
    libc::SIGILL,
    libc::SIGFPE,
];

/// Whether the guard has been installed.
static INSTALLED: AtomicBool = AtomicBool::new(false);

/// The path of the file that holds the report of the crash.
static mut REPORT_PATH: [u8; 4096] = [0; 4096];

/// The length of the path in [`REPORT_PATH`].
static REPORT_PATH_LEN: AtomicUsize = AtomicUsize::new(0);

/// Install the guard.
///
/// The path is the file that the report is written to; the Java side reads
/// it on the next start of the application.
///
/// # Safety
///
/// The path has to stay valid for the whole life of the process; the
/// function copies it into a buffer of its own.
pub fn install(path: &str) {
    if INSTALLED.swap(true, Ordering::SeqCst) {
        return;
    }

    // The path is copied into a static buffer: the handler of the signal
    // runs long after this function has returned, and it cannot use the
    // allocator.  The last byte is the end of the string, so that the C
    // library can read it as a C string.
    let bytes = path.as_bytes();
    if bytes.len() >= 4096 {
        return;
    }
    // SAFETY: the guard is installed once, and the write below happens
    // before any signal can arrive (the engine is not running yet).
    unsafe {
        let slot = &raw mut REPORT_PATH;
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), (*slot).as_mut_ptr(), bytes.len());
        (*slot)[bytes.len()] = 0;
        REPORT_PATH_LEN.store(bytes.len(), Ordering::SeqCst);
    }

    for signal in SIGNALS {
        // SAFETY: `action` is a valid structure and the handler is a plain
        // function with the signature that the C library expects.
        unsafe {
            let mut action: libc::sigaction = std::mem::zeroed();
            action.sa_sigaction = handler as libc::sighandler_t;
            action.sa_flags = libc::SA_RESTART;
            libc::sigemptyset(&mut action.sa_mask);
            libc::sigaction(signal, &action, std::ptr::null_mut());
        }
    }
}

/// The handler of the signals.
///
/// # Safety
///
/// The function is called by the C library in the context of a signal.
unsafe extern "C" fn handler(signal: i32) {
    write_report(signal);

    // The default handler of the platform takes over: the system writes
    // its own trace and the process dies the way it would have died
    // without the guard.
    // SAFETY: the handler of the signal is being replaced while it runs;
    // this is what the C library documents for this use.
    unsafe {
        let mut action: libc::sigaction = std::mem::zeroed();
        action.sa_sigaction = libc::SIG_DFL;
        libc::sigemptyset(&mut action.sa_mask);
        libc::sigaction(signal, &action, std::ptr::null_mut());
        libc::raise(signal);
    }
}

/// Write the report of the crash.
///
/// The write uses the C library alone, because the handler of a signal runs
/// in a context where the allocator of Rust is not safe to use.
fn write_report(signal: i32) {
    let name = name_of(signal);
    let text = format!(
        "Trap stopped inside the native code of the browser.\n\
         \n\
         When:  {}\n\
         Type:  a crash of the native code ({})\n\
         Why:  the engine received the signal {} ({}).\n\
         \n\
         The report was written by the native guard of Trap, in the\n\
         moment of the crash.  The system writes a second trace of the\n\
         crash, with the frames of the native code, into the log of the\n\
         device; it is the trace that says where the engine stopped.\n",
        timestamp(),
        name,
        signal,
        name,
    );

    let bytes = text.as_bytes();

    // SAFETY: the path was copied into a static buffer when the guard was
    // installed, and it is null terminated there.
    let path = unsafe {
        let slot = &raw const REPORT_PATH;
        let len = REPORT_PATH_LEN.load(Ordering::SeqCst);
        if len == 0 {
            return;
        }
        std::ffi::CStr::from_ptr((*slot).as_ptr().cast())
    };

    // SAFETY: the path is a valid C string and the file is opened with
    // the write(2) call of the C library.
    unsafe {
        let fd = libc::open(
            path.as_ptr(),
            libc::O_WRONLY | libc::O_CREAT | libc::O_TRUNC,
            0o600,
        );
        if fd >= 0 {
            libc::write(fd, bytes.as_ptr().cast(), bytes.len());
            libc::close(fd);
        }
    }
}

/// The name of a signal.
fn name_of(signal: i32) -> &'static str {
    match signal {
        libc::SIGSEGV => "a segmentation fault",
        libc::SIGABRT => "an abort",
        libc::SIGBUS => "a bus error",
        libc::SIGILL => "an illegal instruction",
        libc::SIGFPE => "a floating point exception",
        _ => "an unknown signal",
    }
}

/// The moment of the crash, as a readable text.
///
/// The clock of the C library is used: it is the only one that is safe to
/// call in the handler of a signal.
fn timestamp() -> String {
    // SAFETY: the clock of the C library is always safe to call.
    let now = unsafe { libc::time(std::ptr::null_mut()) };
    if now == -1 {
        return "an unknown moment".to_string();
    }
    // SAFETY: `gmtime_r` writes into the structure it is given, and the
    // structure is owned by this function.
    unsafe {
        let mut parts: libc::tm = std::mem::zeroed();
        if libc::gmtime_r(&now, &mut parts).is_null() {
            return "an unknown moment".to_string();
        }
        format!(
            "{:04}-{:02}-{:02} {:02}:{:02}:{:02} UTC",
            parts.tm_year + 1900,
            parts.tm_mon + 1,
            parts.tm_mday,
            parts.tm_hour,
            parts.tm_min,
            parts.tm_sec,
        )
    }
}
