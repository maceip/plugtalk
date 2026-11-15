//! Optional JNI upcalls from background Rust tasks into `dev.onionvault.OvBridge`.

#[cfg(target_os = "android")]
use std::sync::{Mutex, OnceLock};

#[cfg(target_os = "android")]
static JAVA_VM: OnceLock<Mutex<Option<usize>>> = OnceLock::new();

#[cfg(target_os = "android")]
static BRIDGE_CLASS: OnceLock<jni::objects::GlobalRef> = OnceLock::new();

/// Resolve and cache `dev.onionvault.OvBridge` on a Java thread (`nativeInit`).
#[cfg(target_os = "android")]
pub fn cache_bridge_class(env: &mut jni::JNIEnv) {
    if BRIDGE_CLASS.get().is_some() {
        return;
    }
    let class = env
        .find_class("dev/onionvault/OvBridge")
        .and_then(|class| env.new_global_ref(class));
    match class {
        Ok(global) => {
            let _ = BRIDGE_CLASS.set(global);
        }
        Err(_) => {
            let _ = env.exception_clear();
            log::debug!("dev.onionvault.OvBridge is not present; skipping optional JNI callbacks");
        }
    }
}

/// Argument passed to [`call_native`].
pub enum NativeArg {
    String(String),
    Bool(bool),
    Int(i32),
}

/// Store the application `JavaVM` pointer for Android Keystore and optional callbacks.
#[cfg(target_os = "android")]
pub fn set_java_vm(vm: jni::JavaVM) {
    let slot = JAVA_VM.get_or_init(|| Mutex::new(None));
    if let Ok(mut guard) = slot.lock() {
        *guard = Some(vm.get_java_vm_pointer() as usize);
    }
}

/// Obtain a `jni::JavaVM` handle previously registered by [`set_java_vm`].
#[cfg(target_os = "android")]
pub fn java_vm() -> Option<jni::JavaVM> {
    let pointer = java_vm_pointer()?;
    // SAFETY: `pointer` came from `JavaVM::get_java_vm_pointer()` in this process.
    unsafe { jni::JavaVM::from_raw(pointer) }.ok()
}

/// Raw `*mut jni::sys::JavaVM` pointer.
#[cfg(target_os = "android")]
pub fn java_vm_pointer() -> Option<*mut jni::sys::JavaVM> {
    let slot = JAVA_VM.get_or_init(|| Mutex::new(None));
    let guard = slot.lock().ok()?;
    let pointer = *guard.as_ref()?;
    Some(pointer as *mut jni::sys::JavaVM)
}

/// Best-effort static callback into `dev.onionvault.OvBridge` inside a bounded
/// JNI local frame, clearing any exception if the optional callback method is absent.
#[cfg(target_os = "android")]
pub fn call_native(name: &str, args: &[NativeArg]) {
    let Some(vm) = java_vm() else {
        return;
    };
    let Some(global) = BRIDGE_CLASS.get() else {
        return;
    };
    let Ok(mut env) = vm.attach_current_thread() else {
        return;
    };

    let _ = env.with_local_frame(16, |env| -> Result<(), jni::errors::Error> {
        let class: &jni::objects::JClass = global.as_obj().into();
        let res = match name {
            "onDomScan" | "onNavigation" | "showError" | "onMutationResult" | "onPageSnapshot" => {
                let text = match args.first() {
                    Some(NativeArg::String(v)) => safe(v),
                    _ => String::new(),
                };
                let jtext = string_arg(env, &text);
                env.call_static_method(class, name, "(Ljava/lang/String;)V", &[(&jtext).into()])
                    .map(|_| ())
            }
            "onTorProgress" => {
                let percent = match args.first() {
                    Some(NativeArg::Int(v)) => *v,
                    _ => 0,
                };
                let stage = match args.get(1) {
                    Some(NativeArg::String(v)) => safe(v),
                    _ => String::new(),
                };
                let jstage = string_arg(env, &stage);
                env.call_static_method(
                    class,
                    "onTorProgress",
                    "(ILjava/lang/String;)V",
                    &[percent.into(), (&jstage).into()],
                )
                .map(|_| ())
            }
            _ => Ok(()),
        };

        if res.is_err() || env.exception_check().unwrap_or(false) {
            let _ = env.exception_clear();
        }
        Ok(())
    });
}

#[cfg(target_os = "android")]
fn string_arg<'local>(env: &mut jni::JNIEnv<'local>, value: &str) -> jni::objects::JString<'local> {
    env.new_string(value).unwrap_or_else(|_| {
        let _ = env.exception_clear();
        env.new_string("")
            .expect("JVM cannot allocate an empty string")
    })
}

#[cfg(target_os = "android")]
fn safe(value: &str) -> String {
    value.replace('\u{0}', "")
}

#[cfg(not(target_os = "android"))]
pub fn call_native(name: &str, _args: &[NativeArg]) {
    log::debug!("ignoring native callback {name} on non-Android host");
}
