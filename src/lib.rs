//! Trap: shared Rust library (`libonion_vault.so`) exporting the `OvNative` JNI ABI.
//!
//! All 39 `Java_dev_onionvault_OvNative_*` entry points live here and delegate
//! to [`ov_core::jni_api`].  Zero `libcef.so` symbols are linked.

pub use ov_core;

/// Package version of `libonion_vault.so`.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

#[cfg(target_os = "android")]
mod jni_entry_points {
    use jni::objects::JObject;
    use jni::sys::{jboolean, jint, jlong, jstring};
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
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeClassifyCdpDom(env, this, cdp_dom_json)
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
            env, this, user_id, passphrase,
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
            env, this, armored, passphrase,
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
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeShutdown(env: JNIEnv, this: JObject) {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeShutdown(env, this)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeAllowClearnet(
        env: JNIEnv,
        this: JObject,
        pattern: jstring,
        ttl_seconds: jlong,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeAllowClearnet(
            env,
            this,
            pattern,
            ttl_seconds,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeDenyClearnet(
        env: JNIEnv,
        this: JObject,
        host: jstring,
    ) -> jboolean {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeDenyClearnet(env, this, host)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeListClearnetRulesJson(
        env: JNIEnv,
        this: JObject,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeListClearnetRulesJson(env, this)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeRevokeClearnetRule(
        env: JNIEnv,
        this: JObject,
        pattern: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeRevokeClearnetRule(env, this, pattern)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeClearClearnetRules(
        env: JNIEnv,
        this: JObject,
    ) -> jboolean {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeClearClearnetRules(env, this)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrCreateWalletJson(
        env: JNIEnv,
        this: JObject,
        label: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrCreateWalletJson(env, this, label)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrDeleteWallet(
        env: JNIEnv,
        this: JObject,
    ) -> jboolean {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrDeleteWallet(env, this)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrExportSeedJson(
        env: JNIEnv,
        this: JObject,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrExportSeedJson(env, this)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrFetchBtcSwapQuotesJson(
        env: JNIEnv,
        this: JObject,
        btc_amount_decimal: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrFetchBtcSwapQuotesJson(
            env,
            this,
            btc_amount_decimal,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrNewSubaddressJson(
        env: JNIEnv,
        this: JObject,
        label: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrNewSubaddressJson(env, this, label)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrParse402ChallengeJson(
        env: JNIEnv,
        this: JObject,
        www_authenticate_header: jstring,
        fallback_meta_json: jstring,
        page_url: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrParse402ChallengeJson(
            env,
            this,
            www_authenticate_header,
            fallback_meta_json,
            page_url,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrPay402Json(
        env: JNIEnv,
        this: JObject,
        challenge_json: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrPay402Json(
            env,
            this,
            challenge_json,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrProveTxJson(
        env: JNIEnv,
        this: JObject,
        txid: jstring,
        recipient_address: jstring,
        message: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrProveTxJson(
            env,
            this,
            txid,
            recipient_address,
            message,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrQrMatrixJson(
        env: JNIEnv,
        this: JObject,
        payload: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrQrMatrixJson(env, this, payload)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrRestoreWalletJson(
        env: JNIEnv,
        this: JObject,
        mnemonic: jstring,
        restore_height: jlong,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrRestoreWalletJson(
            env,
            this,
            mnemonic,
            restore_height,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrSendJson(
        env: JNIEnv,
        this: JObject,
        destination_address: jstring,
        amount_decimal_or_atomic: jstring,
        sweep_all: jboolean,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrSendJson(
            env,
            this,
            destination_address,
            amount_decimal_or_atomic,
            sweep_all,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrSetDaemonEndpointJson(
        env: JNIEnv,
        this: JObject,
        endpoint_url: jstring,
        mode: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrSetDaemonEndpointJson(
            env,
            this,
            endpoint_url,
            mode,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrStatusJson(
        env: JNIEnv,
        this: JObject,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrStatusJson(env, this)
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrSwapsActionJson(
        env: JNIEnv,
        this: JObject,
        action_json: jstring,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrSwapsActionJson(
            env,
            this,
            action_json,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_dev_onionvault_OvNative_nativeXmrSyncJson(
        env: JNIEnv,
        this: JObject,
    ) -> jstring {
        ov_core::jni_api::Java_dev_onionvault_OvNative_nativeXmrSyncJson(env, this)
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn the_version_is_not_empty() {
        assert!(!super::VERSION.is_empty());
    }

    #[test]
    fn jni_symbol_table_matches_exact_39_exports_in_both_files() {
        let root_src = include_str!("lib.rs");
        let core_src = include_str!("../crates/ov-core/src/lib.rs");

        let expected = [
            "Java_dev_onionvault_OvNative_nativeAllowClearnet",
            "Java_dev_onionvault_OvNative_nativeCheckUrlPolicy",
            "Java_dev_onionvault_OvNative_nativeClassifyCdpDom",
            "Java_dev_onionvault_OvNative_nativeClassifyText",
            "Java_dev_onionvault_OvNative_nativeClearClearnetRules",
            "Java_dev_onionvault_OvNative_nativeDecrypt",
            "Java_dev_onionvault_OvNative_nativeDeleteKey",
            "Java_dev_onionvault_OvNative_nativeDenyClearnet",
            "Java_dev_onionvault_OvNative_nativeEncrypt",
            "Java_dev_onionvault_OvNative_nativeExportPublicKey",
            "Java_dev_onionvault_OvNative_nativeGenerateKey",
            "Java_dev_onionvault_OvNative_nativeGetRequireBiometric",
            "Java_dev_onionvault_OvNative_nativeImportKey",
            "Java_dev_onionvault_OvNative_nativeInit",
            "Java_dev_onionvault_OvNative_nativeLastInitError",
            "Java_dev_onionvault_OvNative_nativeListClearnetRulesJson",
            "Java_dev_onionvault_OvNative_nativeListKeysJson",
            "Java_dev_onionvault_OvNative_nativeResetTorIsolation",
            "Java_dev_onionvault_OvNative_nativeRevokeClearnetRule",
            "Java_dev_onionvault_OvNative_nativeSetHiddenServiceOnly",
            "Java_dev_onionvault_OvNative_nativeSetRequireBiometric",
            "Java_dev_onionvault_OvNative_nativeShutdown",
            "Java_dev_onionvault_OvNative_nativeSocksProxy",
            "Java_dev_onionvault_OvNative_nativeTorStatusJson",
            "Java_dev_onionvault_OvNative_nativeXmrCreateWalletJson",
            "Java_dev_onionvault_OvNative_nativeXmrDeleteWallet",
            "Java_dev_onionvault_OvNative_nativeXmrExportSeedJson",
            "Java_dev_onionvault_OvNative_nativeXmrFetchBtcSwapQuotesJson",
            "Java_dev_onionvault_OvNative_nativeXmrNewSubaddressJson",
            "Java_dev_onionvault_OvNative_nativeXmrParse402ChallengeJson",
            "Java_dev_onionvault_OvNative_nativeXmrPay402Json",
            "Java_dev_onionvault_OvNative_nativeXmrProveTxJson",
            "Java_dev_onionvault_OvNative_nativeXmrQrMatrixJson",
            "Java_dev_onionvault_OvNative_nativeXmrRestoreWalletJson",
            "Java_dev_onionvault_OvNative_nativeXmrSendJson",
            "Java_dev_onionvault_OvNative_nativeXmrSetDaemonEndpointJson",
            "Java_dev_onionvault_OvNative_nativeXmrStatusJson",
            "Java_dev_onionvault_OvNative_nativeXmrSwapsActionJson",
            "Java_dev_onionvault_OvNative_nativeXmrSyncJson",
        ];
        assert_eq!(expected.len(), 39);

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
