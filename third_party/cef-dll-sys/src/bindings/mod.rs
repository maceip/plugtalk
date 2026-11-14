#[cfg(all(target_os = "linux", target_arch = "x86_64"))]
mod x86_64_unknown_linux_gnu;
#[cfg(all(target_os = "linux", target_arch = "x86_64"))]
pub use x86_64_unknown_linux_gnu::*;

#[cfg(all(target_os = "linux", target_arch = "aarch64"))]
mod aarch64_unknown_linux_gnu;
#[cfg(all(target_os = "linux", target_arch = "aarch64"))]
pub use aarch64_unknown_linux_gnu::*;

#[cfg(all(target_os = "linux", target_arch = "arm"))]
mod arm_unknown_linux_gnueabi;
#[cfg(all(target_os = "linux", target_arch = "arm"))]
pub use arm_unknown_linux_gnueabi::*;

#[cfg(all(target_os = "windows", target_arch = "x86_64"))]
mod x86_64_pc_windows_msvc;
#[cfg(all(target_os = "windows", target_arch = "x86_64"))]
pub use x86_64_pc_windows_msvc::*;

#[cfg(all(target_os = "windows", target_arch = "x86"))]
mod i686_pc_windows_msvc;
#[cfg(all(target_os = "windows", target_arch = "x86"))]
pub use i686_pc_windows_msvc::*;

#[cfg(all(target_os = "windows", target_arch = "aarch64"))]
mod aarch64_pc_windows_msvc;
#[cfg(all(target_os = "windows", target_arch = "aarch64"))]
pub use aarch64_pc_windows_msvc::*;

#[cfg(all(target_os = "macos", target_arch = "x86_64"))]
mod x86_64_apple_darwin;
#[cfg(all(target_os = "macos", target_arch = "x86_64"))]
pub use x86_64_apple_darwin::*;

#[cfg(all(target_os = "macos", target_arch = "aarch64"))]
mod aarch64_apple_darwin;
#[cfg(all(target_os = "macos", target_arch = "aarch64"))]
pub use aarch64_apple_darwin::*;

// ---------------------------------------------------------------- Android --
// The declarations of the C API for `aarch64-linux-android` are generated
// from the headers of the engine that is shipped, by `cefpack bindgen`.  They
// are *not* the desktop declarations: the C API is versioned, and the layout
// of the structures changes between the versions.  The build script checks
// that the bindings and the engine agree on the API version.
#[cfg(all(target_os = "android", target_arch = "aarch64"))]
mod aarch64_linux_android;
#[cfg(all(target_os = "android", target_arch = "aarch64"))]
pub use aarch64_linux_android::*;
