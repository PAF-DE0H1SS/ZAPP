//! Нативное ядро ZAPP: сверка правил firewall (reconcile) и SHA-256.
//!
//! Клиент -- C++-мост JNI (`app/src/main/cpp/zapp_jni.cpp`), он же
//! склеивает план со строками в массив для Kotlin. Решение о сверке
//! живёт здесь: чистая логика множеств без JVM, потоков и устройства,
//! целиком под `cargo test`.
//!
//! Формат плана на границе: строки вида `+rule` (добавить) и
//! `-rule` (удалить) через `\n` -- разбор на стороне C++/Kotlin,
//! сериализаторов не требуется.

use std::collections::HashSet;
use std::ffi::{c_char, CStr, CString};

pub mod sha256;
pub use sha256::{zapp_sha256_file, zapp_sha256_hex};

/// План сверки: сначала недостающее, потом лишнее.
///
/// `desired` -- что должно быть в цепочке, `actual` -- что прочитано
/// из ядра. Добавления идут первыми (удаления не должны съедать то,
/// что вот-вот вернётся), порядок внутри каждой группы -- как в
/// источниках, дубли схлопываются по первому вхождению: столько же
/// мутаций отдал бы эквивалентный план на множествах.
///
/// Возвращает буфер `"+rule\n-rule\n..."`; владение -- у вызывающего,
/// освобождать через [`zapp_string_free`]. null на выходе -- формат
/// плана не собрался (в правиле оказался перевод строки; из iptables
/// такого не приходит, но молчаливой порчи плана не хотим): вызывающий
/// обязан свалиться в свой фолбэк.
///
/// # Безопасность
/// Указатели берутся на время вызова: C++ держит массивы живыми до
/// возврата, `CStr` не копирует. Пустой массив -- пустой план.
#[no_mangle]
pub extern "C" fn zapp_reconcile_plan(
    desired: *const *const c_char,
    desired_len: usize,
    actual: *const *const c_char,
    actual_len: usize,
) -> *mut c_char {
    let (Some(desired), Some(actual)) = (unsafe { str_slice(desired, desired_len) },
                                          unsafe { str_slice(actual, actual_len) }) else {
        return std::ptr::null_mut();
    };
    let plan = reconcile_plan(&desired, &actual);
    if plan.iter().any(|(_, rule)| rule.contains('\n')) {
        return std::ptr::null_mut();
    }
    let joined = plan
        .iter()
        .map(|(add, rule)| format!("{}{}", if *add { '+' } else { '-' }, rule))
        .collect::<Vec<_>>()
        .join("\n");
    match CString::new(joined) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Освобождает буфер, отданный любым FFI-функцией crate
/// (`zapp_reconcile_plan`, `zapp_sha256_hex`, `zapp_sha256_file`).
#[no_mangle]
pub extern "C" fn zapp_string_free(p: *mut c_char) {
    if !p.is_null() {
        // SAFETY: буфер пришёл из CString::into_raw и освобождается ровно один раз.
        drop(unsafe { CString::from_raw(p) });
    }
}

/// Ржавая середина: план по двум наборам строк. Порядок и дедуп -- см. док.
fn reconcile_plan(desired: &[String], actual: &[String]) -> Vec<(bool, String)> {
    let want: HashSet<&str> = desired.iter().map(String::as_str).collect();
    let have: HashSet<&str> = actual.iter().map(String::as_str).collect();
    let mut plan = Vec::new();
    let mut seen = HashSet::new();
    for rule in desired {
        if !seen.insert(rule.as_str()) {
            continue;
        }
        if !have.contains(rule.as_str()) {
            plan.push((true, rule.clone()));
        }
    }
    seen.clear();
    for rule in actual {
        if !seen.insert(rule.as_str()) {
            continue;
        }
        if !want.contains(rule.as_str()) {
            plan.push((false, rule.clone()));
        }
    }
    plan
}

/// Массив C-строк -> вектор строк; null внутри -- null на выходе.
unsafe fn str_slice<'a>(ptr: *const *const c_char, len: usize) -> Option<Vec<String>> {
    if ptr.is_null() && len != 0 {
        return None;
    }
    let mut out = Vec::with_capacity(len);
    for i in 0..len {
        // SAFETY: вызывающий гарантирует len живых элементов.
        let raw = unsafe { *ptr.add(i) };
        if raw.is_null() {
            return None;
        }
        // SAFETY: элемент -- валидная NUL-строка, живая до возврата.
        let s = unsafe { CStr::from_ptr(raw) };
        out.push(s.to_string_lossy().into_owned());
    }
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(items: &[&str]) -> Vec<String> {
        items.iter().map(|s| s.to_string()).collect()
    }

    fn render(plan: &[(bool, String)]) -> Vec<String> {
        plan.iter()
            .map(|(add, rule)| format!("{}{}", if *add { '+' } else { '-' }, rule))
            .collect()
    }

    #[test]
    fn пустой_факт_даёт_все_добавления() {
        let plan = reconcile_plan(&v(&["-d 10.0.0.0/8 -j RETURN", "-p tcp -j NFQUEUE"]), &[]);
        assert_eq!(
            render(&plan),
            vec!["+-d 10.0.0.0/8 -j RETURN", "+-p tcp -j NFQUEUE"]
        );
    }

    #[test]
    fn лишнее_удаляется_добавления_первыми() {
        let plan = reconcile_plan(&v(&["a", "b"]), &v(&["b", "c"]));
        assert_eq!(render(&plan), vec!["+a", "-c"]);
    }

    #[test]
    fn совпадающие_наборы_пустой_план() {
        let plan = reconcile_plan(&v(&["a", "b"]), &v(&["b", "a"]));
        assert!(plan.is_empty());
    }

    #[test]
    fn дубли_схлопываются_по_первому_вхождению() {
        let plan = reconcile_plan(&v(&["x", "x", "y"]), &v(&["y", "y"]));
        assert_eq!(render(&plan), vec!["+x"]);
        let plan = reconcile_plan(&v(&["y"]), &v(&["y", "z", "z"]));
        assert_eq!(render(&plan), vec!["-z"]);
    }

    #[test]
    fn порядок_источника_сохраняется() {
        let plan = reconcile_plan(&v(&["m", "a", "z"]), &v(&["b", "c"]));
        assert_eq!(render(&plan), vec!["+m", "+a", "+z", "-b", "-c"]);
    }

    #[test]
    fn ffi_круговой_возврат_и_освобождение() {
        let desired = [CString::new("a").unwrap(), CString::new("b").unwrap()];
        let actual = [CString::new("b").unwrap(), CString::new("c").unwrap()];
        let dptr: Vec<*const c_char> = desired.iter().map(|s| s.as_ptr()).collect();
        let aptr: Vec<*const c_char> = actual.iter().map(|s| s.as_ptr()).collect();
        let out = zapp_reconcile_plan(dptr.as_ptr(), dptr.len(), aptr.as_ptr(), aptr.len());
        assert!(!out.is_null());
        let text = unsafe { CStr::from_ptr(out) }.to_str().unwrap().to_string();
        zapp_string_free(out);
        assert_eq!(text, "+a\n-c");
    }

    #[test]
    fn ffi_правило_с_переносом_отдаёт_null() {
        // Перенос строки внутри правила ломает формат плана: пустой
        // null на выходе надёжнее, чем план, разрезанный пополам.
        let desired = [CString::new("a\nb").unwrap()];
        let dptr: Vec<*const c_char> = desired.iter().map(|s| s.as_ptr()).collect();
        let out = zapp_reconcile_plan(dptr.as_ptr(), dptr.len(), std::ptr::null(), 0);
        assert!(out.is_null());
    }

    #[test]
    fn ffi_пустые_массивы_дают_пустой_план() {
        let out = zapp_reconcile_plan(std::ptr::null(), 0, std::ptr::null(), 0);
        assert!(!out.is_null());
        let text = unsafe { CStr::from_ptr(out) }.to_str().unwrap().to_string();
        zapp_string_free(out);
        assert_eq!(text, "");
    }
}
