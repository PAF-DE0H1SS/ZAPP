//! SHA-256 (FIPS 180-4): байты и файлы -> hex.
//!
//! Стремительный API (`Sha256`) кормится кусками и отдаёт 32 байта;
//! `sha256_hex` -- обёртка над ним для коротких входов. Клиент --
//! C++-мост JNI; освобождение строк -- через [`zapp_string_free`](crate::zapp_string_free).

use std::ffi::{c_char, CStr, CString};
use std::io::Read;

/// Константы раундов: первые 32 бита дробных частей кубических корней
/// первых 64 простых чисел.
const K: [u32; 64] = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
];

/// Потоковый хеш: накапливает вход по частям, `finalize` закрывает
/// дополнением из спецификации (0x80, нули до 56 байта блока, длина
/// сообщения в битах big-endian).
pub struct Sha256 {
    state: [u32; 8],
    buf: [u8; 64],
    buf_len: usize,
    /// Всего байт; длина в битах = `bytes * 8` по модулю 2^64,
    /// переполнение -- по спецификации, не ошибка.
    bytes: u64,
}

impl Default for Sha256 {
    fn default() -> Self {
        Self::new()
    }
}

impl Sha256 {
    /// Начальное состояние (первые 32 бита квадратных корней первых
    /// восьми простых чисел).
    pub fn new() -> Self {
        Sha256 {
            state: [
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
                0x5be0cd19,
            ],
            buf: [0; 64],
            buf_len: 0,
            bytes: 0,
        }
    }

    /// Кормит вход; можно сколько угодно раз, куски не обязаны
    /// совпадать с границами блоков.
    pub fn update(&mut self, mut data: &[u8]) {
        self.bytes = self.bytes.wrapping_add(data.len() as u64);
        while !data.is_empty() {
            let take = (64 - self.buf_len).min(data.len());
            self.buf[self.buf_len..self.buf_len + take].copy_from_slice(&data[..take]);
            self.buf_len += take;
            data = &data[take..];
            if self.buf_len == 64 {
                // Копия, чтобы compress могла взять &mut self.
                let block = self.buf;
                self.compress(&block);
                self.buf_len = 0;
            }
        }
    }

    /// Закрывает хеш; после вызова хешер закрыт (по значению).
    pub fn finalize(mut self) -> [u8; 32] {
        let bits = self.bytes.wrapping_mul(8);
        let n = self.buf_len;
        self.buf[n] = 0x80;
        if n < 56 {
            self.buf[n + 1..56].fill(0);
            self.buf[56..64].copy_from_slice(&bits.to_be_bytes());
            let block = self.buf;
            self.compress(&block);
        } else {
            // Места не хватает: первый блок добиваем нулями,
            // поле длины уезжает в начало следующего.
            self.buf[n + 1..64].fill(0);
            let block = self.buf;
            self.compress(&block);
            self.buf[..56].fill(0);
            self.buf[56..64].copy_from_slice(&bits.to_be_bytes());
            let block = self.buf;
            self.compress(&block);
        }
        let mut out = [0u8; 32];
        for (i, word) in self.state.iter().enumerate() {
            out[i * 4..i * 4 + 4].copy_from_slice(&word.to_be_bytes());
        }
        out
    }

    /// Один блок: расписание из 64 слов, затем 64 раунда.
    fn compress(&mut self, block: &[u8; 64]) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([
                block[4 * i],
                block[4 * i + 1],
                block[4 * i + 2],
                block[4 * i + 3],
            ]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16]
                .wrapping_add(s0)
                .wrapping_add(w[i - 7])
                .wrapping_add(s1);
        }
        let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = self.state;
        for i in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ (!e & g);
            let t1 = h
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[i])
                .wrapping_add(w[i]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let t2 = s0.wrapping_add(maj);
            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(t1);
            d = c;
            c = b;
            b = a;
            a = t1.wrapping_add(t2);
        }
        for (i, v) in [a, b, c, d, e, f, g, h].into_iter().enumerate() {
            self.state[i] = self.state[i].wrapping_add(v);
        }
    }
}

/// Хеш байтов: 64 hex-символа нижним регистром.
pub fn sha256_hex(data: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(data);
    to_hex(&h.finalize())
}

fn to_hex(bytes: &[u8]) -> String {
    const ТАБЛИЦА: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for &b in bytes {
        out.push(ТАБЛИЦА[(b >> 4) as usize] as char);
        out.push(ТАБЛИЦА[(b & 0x0f) as usize] as char);
    }
    out
}

/// Hex-хеш байтов. null-вход с ненулевой длиной -- null на выходе
/// (нечего хешировать); null с нулевой длиной -- хеш пустого входа.
#[no_mangle]
pub extern "C" fn zapp_sha256_hex(data: *const u8, len: usize) -> *mut c_char {
    let bytes: &[u8] = if len == 0 {
        &[]
    } else if data.is_null() {
        return std::ptr::null_mut();
    } else {
        // SAFETY: вызывающий гарантирует len живых байт по указателю.
        unsafe { std::slice::from_raw_parts(data, len) }
    };
    CString::new(sha256_hex(bytes))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

/// Hex-хеш файла кусками по 64 КиБ. null при невалидном пути или
/// ошибке чтения: вызывающий уходит в свой фолбэк.
#[no_mangle]
pub extern "C" fn zapp_sha256_file(path: *const c_char) -> *mut c_char {
    if path.is_null() {
        return std::ptr::null_mut();
    }
    // SAFETY: вызывающий гарантирует живую NUL-строку на время вызова.
    let path_str = unsafe { CStr::from_ptr(path) }.to_string_lossy().into_owned();
    let mut file = match std::fs::File::open(&path_str) {
        Ok(f) => f,
        Err(_) => return std::ptr::null_mut(),
    };
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 64 * 1024];
    loop {
        match file.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => hasher.update(&buf[..n]),
            Err(_) => return std::ptr::null_mut(),
        }
    }
    CString::new(to_hex(&hasher.finalize()))
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::zapp_string_free;
    use std::sync::atomic::{AtomicU64, Ordering};

    const ПУСТОЙ: &str = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    const ABC: &str = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    const ДВА_БЛОКА: &str =
        "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1";
    const МИЛЛИОН_A: &str =
        "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0";

    #[test]
    fn пустой_вход() {
        assert_eq!(sha256_hex(b""), ПУСТОЙ);
    }

    #[test]
    fn вход_abc() {
        assert_eq!(sha256_hex(b"abc"), ABC);
    }

    #[test]
    fn двухблочный_448_битный() {
        let msg = b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
        assert_eq!(sha256_hex(msg), ДВА_БЛОКА);
    }

    #[test]
    fn миллион_раз_a() {
        let data = vec![b'a'; 1_000_000];
        assert_eq!(sha256_hex(&data), МИЛЛИОН_A);
    }

    #[test]
    fn порезанный_ввод_равен_целому() {
        let data: Vec<u8> = (0..200u32).map(|i| (i % 251) as u8).collect();
        let mut h = Sha256::new();
        for chunk in data.chunks(7) {
            h.update(chunk);
        }
        let mut one = Sha256::new();
        one.update(&data);
        assert_eq!(h.finalize(), one.finalize());
    }

    #[test]
    fn файловый_хеш_совпадает_с_хешем_байтов() {
        // Больше 64 КиБ: чтение кусками перекрывает несколько буферов.
        let data: Vec<u8> = (0..200_000u32).map(|i| (i * 7 % 256) as u8).collect();
        static СЧЁТЧИК: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "zapp_sha256_{}_{}.bin",
            std::process::id(),
            СЧЁТЧИК.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, &data).unwrap();

        let c_path = CString::new(path.to_str().unwrap()).unwrap();
        let out = zapp_sha256_file(c_path.as_ptr());
        assert!(!out.is_null());
        let hex = unsafe { CStr::from_ptr(out) }.to_str().unwrap().to_string();
        zapp_string_free(out);

        std::fs::remove_file(&path).unwrap();
        assert_eq!(hex, sha256_hex(&data));
    }

    #[test]
    fn ffi_байты_круговой() {
        let out = zapp_sha256_hex(b"abc".as_ptr(), 3);
        assert!(!out.is_null());
        let hex = unsafe { CStr::from_ptr(out) }.to_str().unwrap().to_string();
        zapp_string_free(out);
        assert_eq!(hex, ABC);
    }

    #[test]
    fn ffi_null_входы_без_паники() {
        assert!(zapp_sha256_hex(std::ptr::null(), 5).is_null());
        assert!(zapp_sha256_file(std::ptr::null()).is_null());
        assert!(zapp_sha256_file(CString::new("/нет/такого").unwrap().as_ptr()).is_null());
        // null с нулевой длиной -- валидный пустой вход.
        let out = zapp_sha256_hex(std::ptr::null(), 0);
        assert!(!out.is_null());
        let hex = unsafe { CStr::from_ptr(out) }.to_str().unwrap().to_string();
        zapp_string_free(out);
        assert_eq!(hex, ПУСТОЙ);
    }
}
