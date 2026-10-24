//! Rules layer for the Android app: Brave's adblock-rust behind a small JNI surface.
//!
//! Mirrors engine/src/host/rules.mjs (which runs the same crate through adblock-rs). The Kotlin
//! side is app.serene.engine.RulesEngine. Everything crosses the boundary as strings or JSON so
//! the surface stays tiny; the engine handle is a leaked Box<Engine> passed around as a jlong.
use std::collections::HashSet;
use std::panic::{catch_unwind, AssertUnwindSafe};

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::resources::Resource;
use adblock::Engine;
use jni::objects::{JByteArray, JClass, JObjectArray, JString};
use jni::sys::{jboolean, jbyteArray, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde_json::{json, Value};

/// Build an engine from filter list texts plus Brave's resources.json (uBO scriptlets).
pub fn build(lists: Vec<String>, resources_json: &str) -> Engine {
    let mut set = FilterSet::new(false);
    for text in lists {
        set.add_filter_list(text, ParseOptions::default());
    }
    let mut engine = Engine::new_with_filter_set(set);
    use_resources(&mut engine, resources_json);
    engine
}

fn use_resources(engine: &mut Engine, resources_json: &str) {
    if let Ok(res) = serde_json::from_str::<Vec<Resource>>(resources_json) {
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
    resources: JString,
) -> jlong {
    let lists = jstrings(&mut env, &lists);
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
        let e = build(vec![LIST.to_string()], "[]");
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
        let e = build(vec![LIST.to_string()], "[]");
        let mut d = Engine::default();
        d.deserialize(&e.serialize()).unwrap();
        assert!(check(&d, "https://cdn.cookielaw.org/otSDKStub.js", "https://example.com/", "script"));
    }
}

/// Real lists: `SERENE_LISTS_DIR=<dir with the three lists + resources.json> cargo test --release -- --ignored`.
#[cfg(test)]
mod real_lists {
    use super::*;

    #[test]
    #[ignore]
    fn real_lists_parse_and_match() {
        let dir = std::env::var("SERENE_LISTS_DIR").expect("SERENE_LISTS_DIR");
        let read = |n: &str| std::fs::read_to_string(format!("{dir}/{n}")).unwrap();
        let lists = ["fanboy-annoyance.txt", "annoyances-cookies.txt", "annoyances-others.txt"].map(read).to_vec();
        let resources = read("resources.json");
        assert!(serde_json::from_str::<Vec<Resource>>(&resources).is_ok(), "resources.json must deserialize");
        let t = std::time::Instant::now();
        let e = build(lists, &resources);
        eprintln!("built in {:?}, serialized {} bytes", t.elapsed(), e.serialize().len());
        let c = cosmetic(&e, "https://www.stackoverflow.com/");
        assert!(!c["hideSelectors"].as_array().unwrap().is_empty() || !c["script"].as_str().unwrap().is_empty());
        assert!(generic(&e, &[], &["onetrust-banner-sdk".into()], &[]).contains(&"#onetrust-banner-sdk".to_string()));
        let t = std::time::Instant::now();
        let mut d = Engine::default();
        d.deserialize(&e.serialize()).unwrap();
        eprintln!("deserialized in {:?}", t.elapsed());
    }
}
