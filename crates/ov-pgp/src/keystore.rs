//! Android Keystore backed master wrapping key.
//!
//! The master wrapping key is an AES-256-GCM key that lives inside the
//! `AndroidKeyStore`.  It is generated with `KeyGenParameterSpec` (GCM block
//! mode, no padding, 256 bit key size) and can be configured to require a
//! user authentication (biometric or device credential) before every use.
//!
//! The TSK wrapping itself is performed by the Keystore: Rust hands the
//! plaintext to `Cipher.doFinal` and receives the ciphertext back.  The
//! master key therefore never leaves the secure hardware.
//!
//! On non-Android platforms the Keystore is not available and the caller has
//! to fall back to [`crate::master_key::SoftwareMasterKey`].

#[cfg(target_os = "android")]
use anyhow::anyhow;
use anyhow::{bail, Result};

use crate::master_key::MasterKeyProvider;

/// Default alias of the master wrapping key (no user authentication required).
pub const DEFAULT_ALIAS: &str = "onionvault.master.wrap.v1";

/// Alias of the user-authenticated (biometric / device credential) master wrapping key.
pub const BIOMETRIC_ALIAS: &str = "onionvault.master.wrap.biometric.v1";

/// `KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT`.
#[cfg(target_os = "android")]
const PURPOSE_ENCRYPT_DECRYPT: i32 = 0x1 | 0x2;

/// `Cipher.ENCRYPT_MODE`.
#[cfg(target_os = "android")]
const CIPHER_ENCRYPT_MODE: i32 = 1;

/// `Cipher.DECRYPT_MODE`.
#[cfg(target_os = "android")]
const CIPHER_DECRYPT_MODE: i32 = 2;

/// GCM authentication tag length in bits.
#[cfg(target_os = "android")]
const GCM_TAG_BITS: i32 = 128;

/// Length of the GCM authentication tag in bytes.
#[cfg(target_os = "android")]
const GCM_TAG_BYTES: usize = 16;

/// Length of the GCM nonce that the Keystore generates.
#[cfg(target_os = "android")]
const IV_LEN: usize = 12;

/// How long the master key stays unlocked after the user confirmed the
/// prompt of the native UI.
#[cfg(target_os = "android")]
const AUTH_VALIDITY_SECONDS: i32 = 30;

/// Extract and clear any pending Java exception on `env` so the thread can
/// safely return across JNI or make subsequent JNI calls without aborting ART.
#[cfg(target_os = "android")]
fn jni_err(env: &mut jni::JNIEnv<'_>, context: &str, err: jni::errors::Error) -> anyhow::Error {
    if env.exception_check().unwrap_or(false) {
        let detail = if let Ok(throwable) = env.exception_occurred() {
            let _ = env.exception_clear();
            if !throwable.is_null() {
                match env.call_method(&throwable, "toString", "()Ljava/lang/String;", &[]) {
                    Ok(val) => match val.l() {
                        Ok(obj) if !obj.is_null() => {
                            let jstr = jni::objects::JString::from(obj);
                            env.get_string(&jstr)
                                .ok()
                                .map(|s| s.to_string_lossy().into_owned())
                        }
                        _ => None,
                    },
                    Err(_) => {
                        let _ = env.exception_clear();
                        None
                    }
                }
            } else {
                None
            }
        } else {
            let _ = env.exception_clear();
            None
        };

        if let Some(java_msg) = detail {
            return anyhow!("{context}: {java_msg}");
        }
    }
    anyhow!("{context}: {err}")
}

/// A master key that is kept inside the Android Keystore.
#[cfg(target_os = "android")]
pub struct AndroidKeystoreMasterKey {
    vm: jni::JavaVM,
    alias: String,
    require_user_auth: bool,
}

#[cfg(target_os = "android")]
impl AndroidKeystoreMasterKey {
    /// Open (and create if necessary) the master key in the Keystore.
    pub fn open(vm: jni::JavaVM, alias: &str, require_user_auth: bool) -> Result<Self> {
        let provider = Self {
            vm,
            alias: alias.to_string(),
            require_user_auth,
        };
        provider.ensure_key()?;
        Ok(provider)
    }

    /// The alias of the Keystore entry.
    pub fn alias(&self) -> &str {
        &self.alias
    }

    /// `true` when the Keystore entry demands a user authentication.
    pub fn requires_user_authentication(&self) -> bool {
        self.require_user_auth
    }

    /// Create the Keystore entry if it does not exist yet.
    fn ensure_key(&self) -> Result<()> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|e| anyhow!("cannot attach to the Java VM: {e}"))?;

        env.with_local_frame(32, |env| -> Result<()> {
            let keystore = open_keystore(env)?;
            let alias = alias_string(env, &self.alias)?;
            let exists = env
                .call_method(
                    &keystore,
                    "containsAlias",
                    "(Ljava/lang/String;)Z",
                    &[(&alias).into()],
                )
                .map_err(|e| jni_err(env, "cannot query the Android Keystore", e))?
                .z()
                .map_err(|e| anyhow!("invalid containsAlias return type: {e}"))?;
            if exists {
                return Ok(());
            }

            generate_key(env, &self.alias, self.require_user_auth)
        })
    }
}

#[cfg(target_os = "android")]
impl MasterKeyProvider for AndroidKeystoreMasterKey {
    fn wrap(&self, plaintext: &[u8]) -> Result<Vec<u8>> {
        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|e| anyhow!("cannot attach to the Java VM: {e}"))?;

        env.with_local_frame(32, |env| -> Result<Vec<u8>> {
            let keystore = open_keystore(env)?;
            let key = load_key(env, &keystore, &self.alias)?;

            let cipher = new_cipher(env)?;
            env.call_method(
                &cipher,
                "init",
                "(ILjava/security/Key;)V",
                &[CIPHER_ENCRYPT_MODE.into(), (&key).into()],
            )
            .map_err(|e| jni_err(env, "cannot initialise the Keystore cipher", e))?;

            let iv_obj = env
                .call_method(&cipher, "getIv", "()[B", &[])
                .map_err(|e| jni_err(env, "cannot read the GCM nonce", e))?
                .l()
                .map_err(|e| anyhow!("invalid getIv return value: {e}"))?;
            if iv_obj.is_null() {
                bail!("the Keystore cipher returned a null GCM IV");
            }
            let iv = jni::objects::JByteArray::from(iv_obj);
            let iv = env
                .convert_byte_array(&iv)
                .map_err(|e| jni_err(env, "cannot copy the GCM nonce", e))?;
            if iv.len() != IV_LEN {
                bail!(
                    "the Keystore returned a {}-byte nonce, expected {IV_LEN}",
                    iv.len()
                );
            }

            let plaintext_array = env
                .byte_array_from_slice(plaintext)
                .map_err(|e| jni_err(env, "cannot allocate plaintext byte array", e))?;
            let ct_obj = env
                .call_method(&cipher, "doFinal", "([B)[B", &[(&plaintext_array).into()])
                .map_err(|e| jni_err(env, "the Keystore refused to encrypt the secret key", e))?
                .l()
                .map_err(|e| anyhow!("invalid doFinal return value: {e}"))?;
            if ct_obj.is_null() {
                bail!("the Keystore cipher returned null ciphertext");
            }
            let ciphertext = jni::objects::JByteArray::from(ct_obj);
            let ciphertext = env
                .convert_byte_array(&ciphertext)
                .map_err(|e| jni_err(env, "cannot read the ciphertext", e))?;

            let mut blob = Vec::with_capacity(iv.len() + ciphertext.len());
            blob.extend_from_slice(&iv);
            blob.extend_from_slice(&ciphertext);
            Ok(blob)
        })
    }

    fn unwrap(&self, blob: &[u8]) -> Result<Vec<u8>> {
        if blob.len() < IV_LEN + GCM_TAG_BYTES {
            bail!(
                "the wrapped blob is too short ({} bytes, need at least {})",
                blob.len(),
                IV_LEN + GCM_TAG_BYTES
            );
        }
        let (iv, ciphertext) = blob.split_at(IV_LEN);

        let mut env = self
            .vm
            .attach_current_thread()
            .map_err(|e| anyhow!("cannot attach to the Java VM: {e}"))?;

        env.with_local_frame(32, |env| -> Result<Vec<u8>> {
            let keystore = open_keystore(env)?;
            let key = load_key(env, &keystore, &self.alias)?;

            let cipher = new_cipher(env)?;
            let spec_class = env
                .find_class("javax/crypto/spec/GCMParameterSpec")
                .map_err(|e| jni_err(env, "cannot find GCMParameterSpec", e))?;
            let iv_array = env
                .byte_array_from_slice(iv)
                .map_err(|e| jni_err(env, "cannot allocate GCM IV byte array", e))?;
            let spec = env
                .new_object(
                    &spec_class,
                    "(I[B)V",
                    &[GCM_TAG_BITS.into(), (&iv_array).into()],
                )
                .map_err(|e| jni_err(env, "cannot build the GCM parameters", e))?;

            env.call_method(
                &cipher,
                "init",
                "(ILjava/security/Key;Ljava/security/spec/AlgorithmParameterSpec;)V",
                &[CIPHER_DECRYPT_MODE.into(), (&key).into(), (&spec).into()],
            )
            .map_err(|e| jni_err(env, "cannot initialise the Keystore cipher", e))?;

            let ciphertext_array = env
                .byte_array_from_slice(ciphertext)
                .map_err(|e| jni_err(env, "cannot allocate ciphertext byte array", e))?;
            let pt_obj = env
                .call_method(&cipher, "doFinal", "([B)[B", &[(&ciphertext_array).into()])
                .map_err(|e| jni_err(env, "the Keystore refused to decrypt the secret key", e))?
                .l()
                .map_err(|e| anyhow!("invalid doFinal return value: {e}"))?;
            if pt_obj.is_null() {
                bail!("the Keystore cipher returned null plaintext");
            }
            let plaintext = jni::objects::JByteArray::from(pt_obj);
            env.convert_byte_array(&plaintext)
                .map_err(|e| jni_err(env, "cannot read the plaintext", e))
        })
    }

    fn describe(&self) -> String {
        format!(
            "Android Keystore ({}, user authentication {})",
            self.alias,
            if self.require_user_auth {
                "required"
            } else {
                "not required"
            }
        )
    }
}

/// Open and load the `AndroidKeyStore` provider.
#[cfg(target_os = "android")]
fn open_keystore<'local>(env: &mut jni::JNIEnv<'local>) -> Result<jni::objects::JObject<'local>> {
    let class = env
        .find_class("java/security/KeyStore")
        .map_err(|e| jni_err(env, "cannot find java.security.KeyStore", e))?;
    let name = env
        .new_string("AndroidKeyStore")
        .map_err(|e| jni_err(env, "cannot create the provider name", e))?;
    let keystore = env
        .call_static_method(
            &class,
            "getInstance",
            "(Ljava/lang/String;)Ljava/security/KeyStore;",
            &[(&name).into()],
        )
        .map_err(|e| jni_err(env, "cannot obtain the AndroidKeyStore", e))?
        .l()
        .map_err(|e| anyhow!("invalid KeyStore.getInstance return value: {e}"))?;
    if keystore.is_null() {
        bail!("KeyStore.getInstance(\"AndroidKeyStore\") returned null");
    }
    let null = jni::objects::JObject::null();
    env.call_method(
        &keystore,
        "load",
        "(Ljava/security/KeyStore$LoadStoreParameter;)V",
        &[(&null).into()],
    )
    .map_err(|e| jni_err(env, "cannot load the AndroidKeyStore", e))?;
    Ok(keystore)
}

/// Fetch the key from the Keystore.
#[cfg(target_os = "android")]
fn load_key<'local>(
    env: &mut jni::JNIEnv<'local>,
    keystore: &jni::objects::JObject<'local>,
    alias: &str,
) -> Result<jni::objects::JObject<'local>> {
    let key_alias = alias_string(env, alias)?;
    let null = jni::objects::JObject::null();
    let key = env
        .call_method(
            keystore,
            "getKey",
            "(Ljava/lang/String;[C)Ljava/security/Key;",
            &[(&key_alias).into(), (&null).into()],
        )
        .map_err(|e| jni_err(env, &format!("cannot read key {alias} from Keystore"), e))?
        .l()
        .map_err(|e| anyhow!("invalid KeyStore.getKey return value: {e}"))?;
    if key.is_null() {
        bail!("Keystore alias {alias:?} does not exist or returned null");
    }
    Ok(key)
}

/// Create a fresh AES-256-GCM key inside the Keystore.
#[cfg(target_os = "android")]
#[allow(deprecated)]
fn generate_key(env: &mut jni::JNIEnv<'_>, alias: &str, require_user_auth: bool) -> Result<()> {
    let generator_class = env
        .find_class("javax/crypto/KeyGenerator")
        .map_err(|e| jni_err(env, "cannot find javax.crypto.KeyGenerator", e))?;
    let algorithm = env
        .new_string("AES")
        .map_err(|e| jni_err(env, "cannot create the algorithm name", e))?;
    let provider = env
        .new_string("AndroidKeyStore")
        .map_err(|e| jni_err(env, "cannot create the provider name", e))?;
    let generator = env
        .call_static_method(
            &generator_class,
            "getInstance",
            "(Ljava/lang/String;Ljava/lang/String;)Ljavax/crypto/KeyGenerator;",
            &[(&algorithm).into(), (&provider).into()],
        )
        .map_err(|e| jni_err(env, "cannot obtain an AndroidKeyStore key generator", e))?
        .l()
        .map_err(|e| anyhow!("invalid KeyGenerator.getInstance return value: {e}"))?;
    if generator.is_null() {
        bail!("KeyGenerator.getInstance returned null");
    }

    let builder_class = env
        .find_class("android/security/keystore/KeyGenParameterSpec$Builder")
        .map_err(|e| jni_err(env, "cannot find KeyGenParameterSpec.Builder", e))?;
    let key_alias = alias_string(env, alias)?;
    let builder = env
        .new_object(
            &builder_class,
            "(Ljava/lang/String;I)V",
            &[(&key_alias).into(), PURPOSE_ENCRYPT_DECRYPT.into()],
        )
        .map_err(|e| jni_err(env, "cannot build the KeyGenParameterSpec builder", e))?;

    let builder = set_string_array_method(env, &builder, "setBlockModes", "GCM")?;
    let builder = set_string_array_method(env, &builder, "setEncryptionPaddings", "NoPadding")?;

    let builder = env
        .call_method(
            &builder,
            "setKeySize",
            "(I)Landroid/security/keystore/KeyGenParameterSpec$Builder;",
            &[256.into()],
        )
        .map_err(|e| jni_err(env, "cannot set the key size", e))?
        .l()
        .map_err(|e| anyhow!("invalid setKeySize return value: {e}"))?;

    let mut builder = env
        .call_method(
            &builder,
            "setUserAuthenticationRequired",
            "(Z)Landroid/security/keystore/KeyGenParameterSpec$Builder;",
            &[jni::objects::JValue::Bool(u8::from(require_user_auth))],
        )
        .map_err(|e| jni_err(env, "cannot configure user authentication", e))?
        .l()
        .map_err(|e| anyhow!("invalid setUserAuthenticationRequired return value: {e}"))?;

    if require_user_auth {
        builder = env
            .call_method(
                &builder,
                "setUserAuthenticationValidityDurationSeconds",
                "(I)Landroid/security/keystore/KeyGenParameterSpec$Builder;",
                &[AUTH_VALIDITY_SECONDS.into()],
            )
            .map_err(|e| jni_err(env, "cannot configure authentication validity duration", e))?
            .l()
            .map_err(|e| {
                anyhow!("invalid setUserAuthenticationValidityDurationSeconds return value: {e}")
            })?;
    }

    let spec = env
        .call_method(
            &builder,
            "build",
            "()Landroid/security/keystore/KeyGenParameterSpec;",
            &[],
        )
        .map_err(|e| jni_err(env, "cannot build the KeyGenParameterSpec", e))?
        .l()
        .map_err(|e| anyhow!("invalid Builder.build return value: {e}"))?;

    env.call_method(
        &generator,
        "init",
        "(Ljava/security/spec/AlgorithmParameterSpec;)V",
        &[(&spec).into()],
    )
    .map_err(|e| jni_err(env, "cannot initialise the key generator", e))?;

    let generated = env
        .call_method(&generator, "generateKey", "()Ljavax/crypto/SecretKey;", &[])
        .map_err(|e| jni_err(env, "the AndroidKeyStore refused to generate the key", e))?
        .l()
        .map_err(|e| anyhow!("invalid generateKey return value: {e}"))?;
    if generated.is_null() {
        bail!("KeyGenerator.generateKey returned null");
    }

    Ok(())
}

/// Call a `Builder` method that takes a `String[]` argument.
#[cfg(target_os = "android")]
fn set_string_array_method<'local>(
    env: &mut jni::JNIEnv<'local>,
    builder: &jni::objects::JObject<'local>,
    method: &str,
    value: &str,
) -> Result<jni::objects::JObject<'local>> {
    let text = env
        .new_string(value)
        .map_err(|e| jni_err(env, &format!("cannot create {value:?}"), e))?;
    let array = env
        .new_object_array(1, "java/lang/String", &text)
        .map_err(|e| jni_err(env, "cannot create string array", e))?;
    let signature = "([Ljava/lang/String;)Landroid/security/keystore/KeyGenParameterSpec$Builder;";
    let result = env
        .call_method(builder, method, signature, &[(&array).into()])
        .map_err(|e| jni_err(env, &format!("{method} failed"), e))?
        .l()
        .map_err(|e| anyhow!("invalid {method} return value: {e}"))?;
    Ok(result)
}

/// Create an `AES/GCM/NoPadding` cipher.
#[cfg(target_os = "android")]
fn new_cipher<'local>(env: &mut jni::JNIEnv<'local>) -> Result<jni::objects::JObject<'local>> {
    let class = env
        .find_class("javax/crypto/Cipher")
        .map_err(|e| jni_err(env, "cannot find javax.crypto.Cipher", e))?;
    let transformation = env
        .new_string("AES/GCM/NoPadding")
        .map_err(|e| jni_err(env, "cannot create transformation name", e))?;
    let cipher = env
        .call_static_method(
            &class,
            "getInstance",
            "(Ljava/lang/String;)Ljavax/crypto/Cipher;",
            &[(&transformation).into()],
        )
        .map_err(|e| jni_err(env, "cannot obtain AES/GCM cipher", e))?
        .l()
        .map_err(|e| anyhow!("invalid Cipher.getInstance return value: {e}"))?;
    if cipher.is_null() {
        bail!("Cipher.getInstance(\"AES/GCM/NoPadding\") returned null");
    }
    Ok(cipher)
}

/// Helper that creates a `java.lang.String`.
#[cfg(target_os = "android")]
fn alias_string<'local>(
    env: &mut jni::JNIEnv<'local>,
    alias: &str,
) -> Result<jni::objects::JString<'local>> {
    env.new_string(alias)
        .map_err(|e| jni_err(env, "cannot create key alias", e))
}

/// The Keystore is only available on Android; everywhere else this helper
/// explains the situation instead of silently degrading.
#[cfg(not(target_os = "android"))]
pub fn open(_alias: &str, _require_user_auth: bool) -> Result<Box<dyn MasterKeyProvider>> {
    bail!("the Android Keystore is only available on Android")
}
