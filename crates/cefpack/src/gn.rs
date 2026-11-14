//! The GN arguments of an Android build of CEF.
//!
//! Chromium's build system knows Android; CEF's build adds a handful of
//! arguments of its own (`use_cef` and the switch that picks the C++ API
//! implementation over Chromium's).  The argument list is kept in the
//! manifest, because it is a property of the build and not of the host, and
//! because a reviewer has to be able to read it.
//!
//! The arguments are *not* guessed at runtime.  `gn gen` is given exactly
//! what the manifest says, and the bundler prints the whole list before it
//! runs, so a wrong argument shows up in the log of the build and not in a
//! crash on a device.

use std::collections::BTreeMap;

/// The arguments that every Android build of CEF needs.
pub fn base_args(target_cpu: &str, min_sdk: u32) -> Vec<String> {
    vec![
        // The target.
        "target_os=\"android\"".to_string(),
        format!("target_cpu=\"{target_cpu}\""),
        "is_android=true".to_string(),
        // CEF's own layer: the C++ API implementation and the C API shim.
        "use_cef=true".to_string(),
        // The build of the library, not of a browser.
        "is_client=false".to_string(),
        "is_server=false".to_string(),
        "is_official_build=true".to_string(),
        // The Android build of Chromium needs the NDK sysroot and the
        // Android framework, not the host sysroot.
        "use_sysroot=false".to_string(),
        "use_custom_libcxx=true".to_string(),
        "use_lld=true".to_string(),
        // The libraries of the platform.
        "is_clang=true".to_string(),
        "is_debug=false".to_string(),
        "symbol_level=0".to_string(),
        "treat_warnings_as_errors=false".to_string(),
        "enable_nacl=false".to_string(),
        "enable_plugin=false".to_string(),
        // The PGO phase of the official build needs the profiles of a
        // previous build; the bundler does not have them.
        "chrome_pgo_phase=0".to_string(),
        // The API level of the application.
        format!("android_min_sdk_version={min_sdk}"),
    ]
}

/// The arguments of a debug build.
pub fn debug_args() -> Vec<String> {
    vec![
        "is_debug=true".to_string(),
        "symbol_level=1".to_string(),
        "is_official_build=false".to_string(),
    ]
}

/// Turn an argument list into the `key=value` map that `gn gen` expects.
pub fn to_map(args: &[String]) -> BTreeMap<String, String> {
    args.iter()
        .filter_map(|arg| {
            arg.split_once('=')
                .map(|(k, v)| (k.to_string(), v.trim_matches('"').to_string()))
        })
        .collect()
}

/// Render the argument list as the single string that `gn gen --args` takes.
pub fn to_args_string(args: &[String]) -> String {
    args.join(" ")
}

/// Parse the argument list back from a string, for the audit of a build.
pub fn parse_args_string(text: &str) -> BTreeMap<String, String> {
    to_map(
        &text
            .split_whitespace()
            .map(|s| s.to_string())
            .collect::<Vec<_>>(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_base_arguments_name_the_target_and_the_cef_layer() {
        let args = base_args("arm64", 29);
        assert!(args.contains(&"target_os=\"android\"".to_string()));
        assert!(args.contains(&"target_cpu=\"arm64\"".to_string()));
        assert!(args.contains(&"use_cef=true".to_string()));
        assert!(args.contains(&"android_min_sdk_version=29".to_string()));
    }

    #[test]
    fn a_debug_build_replaces_the_release_flags() {
        let args = base_args("arm64", 29);
        let debug = debug_args();
        assert!(
            !args.contains(&"is_debug=false".to_string())
                || debug.contains(&"is_debug=true".to_string())
        );
        assert!(debug.contains(&"is_official_build=false".to_string()));
    }

    #[test]
    fn the_map_drops_the_quotes_and_the_last_value_wins() {
        let args = vec![
            "target_os=\"android\"".to_string(),
            "is_debug=false".to_string(),
            "is_debug=true".to_string(),
        ];
        let map = to_map(&args);
        assert_eq!(map.get("target_os").map(String::as_str), Some("android"));
        assert_eq!(map.get("is_debug").map(String::as_str), Some("true"));
    }

    #[test]
    fn the_argument_string_round_trips() {
        let args = base_args("arm64", 29);
        let text = to_args_string(&args);
        assert_eq!(parse_args_string(&text), to_map(&args));
    }
}
