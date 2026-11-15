//! Trap: shared Rust library (`libonion_vault.so`) exporting the `OvNative` JNI ABI.
//!
//! All 19 `Java_dev_onionvault_OvNative_*` entry points live here and delegate
//! to [`ov_core::jni_api`].  Zero `libcef.so` symbols are linked.

pub use ov_core;

/// Package version of `libonion_vault.so`.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[cfg(target_os = "android")]
mod jni_entry_points {
    use jni::objects::JObject;
    use jni::sys::{jboolean, jint, jstring};
    use jni::JNIEnv;

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeInit(
        env: JNIEnv,
        this: JObject,
        data_dir: jstring,
        cache_dir: jstring,
        hidden_service_only: jboolean,
    ) -> jboolean {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeInit(
            env,
            this,
            data_dir,
            cache_dir,
            hidden_service_only,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeLastInitError(
        env: JNIEnv,
        this: JObject,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeLastInitError(env, this)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeSocksProxy(
        env: JNIEnv,
        this: JObject,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeSocksProxy(env, this)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeTorStatusJson(
        env: JNIEnv,
        this: JObject,
        current_url: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeTorStatusJson(env, this, current_url)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeSetHiddenServiceOnly(
        env: JNIEnv,
        this: JObject,
        enabled: jboolean,
    ) {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeSetHiddenServiceOnly(
            env, this, enabled,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeResetTorIsolation(
        env: JNIEnv,
        this: JObject,
        target_url_or_empty: jstring,
    ) -> jint {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeResetTorIsolation(
            env,
            this,
            target_url_or_empty,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeCheckUrlPolicy(
        env: JNIEnv,
        this: JObject,
        url: jstring,
        hidden_service_only: jboolean,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeCheckUrlPolicy(
            env,
            this,
            url,
            hidden_service_only,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeClassifyCdpDom(
        env: JNIEnv,
        this: JObject,
        cdp_dom_json: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeClassifyCdpDom(
            env,
            this,
            cdp_dom_json,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeClassifyText(
        env: JNIEnv,
        this: JObject,
        text: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeClassifyText(env, this, text)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeListKeysJson(
        env: JNIEnv,
        this: JObject,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeListKeysJson(env, this)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeGenerateKey(
        env: JNIEnv,
        this: JObject,
        user_id: jstring,
        passphrase: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeGenerateKey(
            env,
            this,
            user_id,
            passphrase,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeImportKey(
        env: JNIEnv,
        this: JObject,
        armored: jstring,
        passphrase: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeImportKey(
            env,
            this,
            armored,
            passphrase,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeExportPublicKey(
        env: JNIEnv,
        this: JObject,
        fingerprint_or_empty: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeExportPublicKey(
            env,
            this,
            fingerprint_or_empty,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeDeleteKey(
        env: JNIEnv,
        this: JObject,
        fingerprint: jstring,
    ) -> jboolean {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeDeleteKey(env, this, fingerprint)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeEncrypt(
        env: JNIEnv,
        this: JObject,
        plaintext: jstring,
        recipient_fingerprint: jstring,
        signer_fingerprint_or_empty: jstring,
        passphrase: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeEncrypt(
            env,
            this,
            plaintext,
            recipient_fingerprint,
            signer_fingerprint_or_empty,
            passphrase,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeDecrypt(
        env: JNIEnv,
        this: JObject,
        armored_ciphertext: jstring,
        passphrase: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeDecrypt(
            env,
            this,
            armored_ciphertext,
            passphrase,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeSetRequireBiometric(
        env: JNIEnv,
        this: JObject,
        required: jboolean,
    ) -> jboolean {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeSetRequireBiometric(
            env, this, required,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeGetRequireBiometric(
        env: JNIEnv,
        this: JObject,
    ) -> jboolean {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeGetRequireBiometric(env, this)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeShutdown(
        env: JNIEnv,
        this: JObject,
    ) {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeShutdown(env, this)
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn the_version_is_not_empty() {
        assert!(!super::VERSION.is_empty());
    }

    #[test]
    fn jni_symbol_table_matches_exact_19_exports_in_both_files() {
        let root_src = include_str!("lib.rs");
        let core_src = include_str!("../crates/ov-core/src/lib.rs");

        let expected = [
            "Java_dev_onionvault_OvNative_nativeCheckUrlPolicy",
            "Java_dev_onionvault_OvNative_nativeClassifyCdpDom",
            "Java_dev_onionvault_OvNative_nativeClassifyText",
            "Java_dev_onionvault_OvNative_nativeDecrypt",
            "Java_dev_onionvault_OvNative_nativeDeleteKey",
            "Java_dev_onionvault_OvNative_nativeEncrypt",
            "Java_dev_onionvault_OvNative_nativeExportPublicKey",
            "Java_dev_onionvault_OvNative_nativeGenerateKey",
            "Java_dev_onionvault_OvNative_nativeGetRequireBiometric",
            "Java_dev_onionvault_OvNative_nativeImportKey",
            "Java_dev_onionvault_OvNative_nativeInit",
            "Java_dev_onionvault_OvNative_nativeLastInitError",
            "Java_dev_onionvault_OvNative_nativeListKeysJson",
            "Java_dev_onionvault_OvNative_nativeResetTorIsolation",
            "Java_dev_onionvault_OvNative_nativeSetHiddenServiceOnly",
            "Java_dev_onionvault_OvNative_nativeSetRequireBiometric",
            "Java_dev_onionvault_OvNative_nativeShutdown",
            "Java_dev_onionvault_OvNative_nativeSocksProxy",
            "Java_dev_onionvault_OvNative_nativeTorStatusJson",
        ];

        for (label, content) in [("src/lib.rs", root_src), ("ov-core/src/lib.rs", core_src)] {
            let mut found: Vec<&str> = content
                .lines()
                .filter_map(|line| {
                    let trimmed = line.trim();
                    let rest = trimmed.strip_prefix("pub extern \"system\" fn ")?;
                    let name = rest.split('(').next()?.trim();
                    name.starts_with("Java_").then_some(name)
                })
                .collect();
            found.sort_unstable();
            assert_eq!(found, expected, "JNI symbol mismatch in {label}");
        }
    }
}
