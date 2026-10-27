//! Rules layer for the Android app: Brave's adblock-rust behind a small JNI surface.
//!
//! Mirrors engine/src/host/rules.mjs (which runs the same crate through adblock-rs). The Kotlin
//! side is app.serene.engine.RulesEngine. Everything crosses the boundary as strings or JSON so
//! the surface stays tiny; the engine handle is a leaked Box<Engine> passed around as a jlong.
use std::collections::HashSet;
use std::panic::{catch_unwind, AssertUnwindSafe};

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::{PermissionMask, Resource};
use adblock::Engine;
use jni::objects::{JBooleanArray, JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde_json::{json, Value};

/// Permission bit for uBO's trusted scriptlets (engine/build.mjs sets it on those resources).
const TRUSTED: PermissionMask = PermissionMask::from_bits(1);

/// Build an engine from filter list texts, each with whether it is trusted (may inject uBO's
/// trusted scriptlets), plus uBO's scriptlets (engine/dist/scriptlets.json).
pub fn build(lists: Vec<(String, bool)>, resources_json: &str) -> Engine {
    let mut set = FilterSet::new(false);
    for (text, trusted) in lists {
        let permissions = if trusted { TRUSTED } else { PermissionMask::default() };
        set.add_filter_list(text, ParseOptions { permissions, ..ParseOptions::default() });
    }
    let mut engine = Engine::new_with_filter_set(set);
    use_resources(&mut engine, resources_json);
    engine
}

/// scriptlets.json is `{ "source": ..., "resources": [...] }`; a bare array works too.
fn parse_resources(resources_json: &str) -> Option<Vec<Resource>> {
    let mut v: Value = serde_json::from_str(resources_json).ok()?;
    let arr = match v.get_mut("resources") {
        Some(r) => r.take(),
        None => v,
    };
    serde_json::from_value(arr).ok()
}

fn use_resources(engine: &mut Engine, resources_json: &str) {
    if let Some(res) = parse_resources(resources_json) {
        engine.use_resources(res);
    }
}

pub fn check(engine: &Engine, url: &str, source: &str, kind: &str) -> bool {
    match Request::new(url, source, kind, "GET") {
        Ok(req) => engine.check_network_request(&req).should_block(),
        Err(_) => false,
    }
}

/// Same shape as RulesEngine.cosmetic() in rules.mjs.
pub fn cosmetic(engine: &Engine, url: &str) -> Value {
    let r = engine.url_cosmetic_resources(url);
    let procedural: Vec<Value> = r
        .procedural_actions
        .iter()
        .filter_map(|s| serde_json::from_str(s).ok())
        .collect();
    json!({
        "hideSelectors": r.hide_selectors.into_iter().collect::<Vec<_>>(),
        "procedural": procedural,
        "exceptions": r.exceptions.into_iter().collect::<Vec<_>>(),
        "script": r.injected_script,
        "generichide": r.generichide,
    })
}

pub fn generic(engine: &Engine, classes: &[String], ids: &[String], exceptions: &[String]) -> Vec<String> {
    let ex: HashSet<String> = exceptions.iter().cloned().collect();
    engine.hidden_class_id_selectors(classes, ids, &ex)
}

// ------------------------------------------------------------------ JNI

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    if s.is_null() {
        return String::new();
    }
    env.get_string(s).map(|s| s.into()).unwrap_or_default()
}

fn jstrings(env: &mut JNIEnv, arr: &JObjectArray) -> Vec<String> {
    if arr.is_null() {
        return Vec::new();
    }
    let n = env.get_array_length(arr).unwrap_or(0);
    let mut out = Vec::with_capacity(n as usize);
    for i in 0..n {
        if let Ok(o) = env.get_object_array_element(arr, i) {
            let s = JString::from(o);
            out.push(jstr(env, &s));
            let _ = env.delete_local_ref(s);
        }
    }
    out
}

fn new_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// The handle points at a Box<Engine> created by nativeCreate/nativeDeserialize.
unsafe fn engine<'a>(handle: jlong) -> Option<&'a Engine> {
    (handle as *const Engine).as_ref()
}

fn into_handle(engine: Engine) -> jlong {
    Box::into_raw(Box::new(engine)) as jlong
}

#[no_mangle]
pub extern "system" fn Java_app_serene_engine_RulesEngine_nativeCreate(
    mut env: JNIEnv,
    _: JClass,
    lists: JObjectArray,
    trusted: JBooleanArray,
    resources: JString,
) -> jlong {
    let texts = jstrings(&mut env, &lists);
    let mut flags = vec![JNI_FALSE; texts.len()];
    if !trusted.is_null() {
        let _ = env.get_boolean_array_region(&trusted, 0, &mut flags);
    }
    let lists = texts.into_iter().zip(flags).map(|(t, f)| (t, f == JNI_TRUE)).collect();
    let resources = jstr(&mut env, &resources);
    catch_unwind(move || into_handle(build(lists, &resources))).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_app_serene_engine_RulesEngine_nativeDeserialize(
    env: JNIEnv,
    _: JClass,
    data: JByteArray,
    resources: JString,
) -> jlong {
    let mut env = env;
    let Ok(bytes) = env.convert_byte_array(&data) else { return 0 };
    let resources = jstr(&mut env, &resources);
    catch_unwind(move || {
        let mut e = Engine::default();
        if e.deserialize(&bytes).is_err() {
            return 0;
        }
        use_resources(&mut e, &resources);
        into_handle(e)
    })
    .unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_app_serene_engine_RulesEngine_nativeSerialize(
    env: JNIEnv,
    _: JClass,
    handle: jlong,
) -> jbyteArray {
    let Some(e) = (unsafe { engine(handle) }) else { return std::ptr::null_mut() };
    let bytes = catch_unwind(AssertUnwindSafe(|| e.serialize())).unwrap_or_default();
    env.byte_array_from_slice(&bytes).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_app_serene_engine_RulesEngine_nativeDestroy(_: JNIEnv, _: JClass, handle: jlong) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut Engine) });
    }
}

#[no_mangle]
pub extern "system" fn Java_app_serene_engine_RulesEngine_nativeCheck(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    url: JString,
    source: JString,
    kind: JString,
) -> jboolean {
    let Some(e) = (unsafe { engine(handle) }) else { return JNI_FALSE };
    let (url, source, kind) = (jstr(&mut env, &url), jstr(&mut env, &source), jstr(&mut env, &kind));
    match catch_unwind(AssertUnwindSafe(|| check(e, &url, &source, &kind))) {
        Ok(true) => JNI_TRUE,
        _ => JNI_FALSE,
    }
}

#[no_mangle]
pub extern "system" fn Java_app_serene_engine_RulesEngine_nativeCosmetic(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    url: JString,
) -> jstring {
    let Some(e) = (unsafe { engine(handle) }) else { return std::ptr::null_mut() };
    let url = jstr(&mut env, &url);
    let out = catch_unwind(AssertUnwindSafe(|| cosmetic(e, &url).to_string())).unwrap_or_default();
    new_jstring(&mut env, &out)
}

#[no_mangle]
pub extern "system" fn Java_app_serene_engine_RulesEngine_nativeGeneric(
    mut env: JNIEnv,
    _: JClass,
    handle: jlong,
    classes: JObjectArray,
    ids: JObjectArray,
    exceptions: JObjectArray,
) -> jstring {
    let Some(e) = (unsafe { engine(handle) }) else { return std::ptr::null_mut() };
    let (classes, ids, exceptions) = (jstrings(&mut env, &classes), jstrings(&mut env, &ids), jstrings(&mut env, &exceptions));
    let out = catch_unwind(AssertUnwindSafe(|| json!(generic(e, &classes, &ids, &exceptions)).to_string())).unwrap_or_default();
    new_jstring(&mut env, &out)
}

#[cfg(test)]
mod tests {
    use super::*;

    const LIST: &str = "||cdn.cookielaw.org^$script\n##.cookie-banner\nexample.com###nl-modal\nexample.com##.promo:has-text(Subscribe)";

    #[test]
    fn network_and_cosmetic() {
        let e = build(vec![(LIST.to_string(), false)], "[]");
        assert!(check(&e, "https://cdn.cookielaw.org/otSDKStub.js", "https://example.com/", "script"));
        assert!(!check(&e, "https://example.com/app.js", "https://example.com/", "script"));
        let c = cosmetic(&e, "https://www.example.com/a");
        assert_eq!(c["hideSelectors"], json!(["#nl-modal"]));
        assert_eq!(c["procedural"].as_array().unwrap().len(), 1);
        let g = generic(&e, &["cookie-banner".into(), "x".into()], &[], &[]);
        assert_eq!(g, vec![".cookie-banner".to_string()]);
    }

    #[test]
    fn serialize_roundtrip() {
        let e = build(vec![(LIST.to_string(), false)], "[]");
        let mut d = Engine::default();
        d.deserialize(&e.serialize()).unwrap();
        assert!(check(&d, "https://cdn.cookielaw.org/otSDKStub.js", "https://example.com/", "script"));
    }

    #[test]
    fn trusted_scriptlets_need_a_trusted_list() {
        // Stand-ins in the shape engine/build.mjs writes: function-style, trusted ones with permission 1.
        let b64 = |s: &str| base64_encode(s.as_bytes());
        let res = json!({ "source": "test", "resources": [
            { "name": "set-constant.js", "aliases": ["set.js"], "kind": { "mime": "application/javascript" }, "content": b64("function setConstant(a, b) {}") },
            { "name": "trusted-click-element.js", "kind": { "mime": "application/javascript" }, "content": b64("function trustedClickElement(a) {}"), "permission": 1 },
        ]})
        .to_string();
        let rules = "example.com##+js(set, foo, false)\nexample.com##+js(trusted-click-element, #reject)";
        let script = |trusted| cosmetic(&build(vec![(rules.to_string(), trusted)], &res), "https://example.com/")["script"].as_str().unwrap().to_string();
        let (untrusted, trusted) = (script(false), script(true));
        assert!(untrusted.contains("setConstant(\"foo\", \"false\")"), "{untrusted}");
        assert!(!untrusted.contains("trustedClickElement(\""));
        assert!(trusted.contains("trustedClickElement(\"#reject\")"), "{trusted}");
    }

    fn base64_encode(data: &[u8]) -> String {
        const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        let mut out = String::new();
        for c in data.chunks(3) {
            let n = c.iter().enumerate().fold(0u32, |n, (i, &b)| n | (b as u32) << (16 - 8 * i));
            for i in 0..4 {
                out.push(if i <= c.len() { T[(n >> (18 - 6 * i) & 63) as usize] as char } else { '=' });
            }
        }
        out
    }
}

/// Real lists: `SERENE_LISTS_DIR=<dir with the three lists> cargo test --release -- --ignored`.
#[cfg(test)]
mod real_lists {
    use super::*;

    #[test]
    #[ignore]
    fn real_lists_parse_and_match() {
        let dir = std::env::var("SERENE_LISTS_DIR").expect("SERENE_LISTS_DIR");
        let read = |n: &str| std::fs::read_to_string(format!("{dir}/{n}")).unwrap();
        let lists = [("fanboy-annoyance.txt", false), ("annoyances-cookies.txt", true), ("annoyances-others.txt", true)]
            .map(|(n, trusted)| (read(n), trusted))
            .to_vec();
        let resources = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../../engine/dist/scriptlets.json")).unwrap();
        assert!(parse_resources(&resources).is_some_and(|r| !r.is_empty()), "scriptlets.json must deserialize");
        let t = std::time::Instant::now();
        let e = build(lists.clone(), &resources);
        eprintln!("built in {:?}, serialized {} bytes", t.elapsed(), e.serialize().len());
        let c = cosmetic(&e, "https://www.stackoverflow.com/");
        assert!(!c["hideSelectors"].as_array().unwrap().is_empty() || !c["script"].as_str().unwrap().is_empty());
        assert!(generic(&e, &[], &["onetrust-banner-sdk".into()], &[]).contains(&"#onetrust-banner-sdk".to_string()));
        let t = std::time::Instant::now();
        let mut d = Engine::default();
        d.deserialize(&e.serialize()).unwrap();
        eprintln!("deserialized in {:?}", t.elapsed());
        // uBO Cookie Notices' trusted scriptlets survive the saved engine (FilterLists reuses it).
        use_resources(&mut d, &resources);
        let site = lists[1].0.lines().find_map(|l| {
            let (domains, _) = l.split_once("##+js(trusted-click-element,")?;
            let d = domains.split(',').next()?;
            d.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-').then(|| d.to_string())
        });
        let site = site.expect("a trusted-click-element rule");
        let script = cosmetic(&d, &format!("https://{site}/"))["script"].as_str().unwrap().to_string();
        assert!(script.contains("trustedClickElement("), "no trusted-click-element on {site}");
    }
}
