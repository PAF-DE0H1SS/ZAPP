//! proto3-wire: varint, length-delimited и письма схемы daemon.proto.
//!
//! Схема -- daemon.proto; здесь только wire-формат: тег = (поле << 3) |
//! тип, varint — 7 бит на байт с признаком продолжения, length-delimited
//! -- varint-длина + байты. Никаких генераторов: клиент JVM пишет и
//! читает тем же форматом, и совместимость сторон проверяется живым
//! round-trip (тесты Rust и JVM гоняют письма друг через друга).

use std::io::Write;
use std::net::TcpStream;

use crate::procfs::Conn;

/// Предел размера письма: снимок с тысячами строк и так мегабайт.
pub const MAX_FRAME: usize = 1 << 24;

const WIRE_VARINT: u64 = 0;
const WIRE_LEN: u64 = 2;

/// varint: 7 бит на байт, старший -- признак продолжения.
pub fn put_varint(out: &mut Vec<u8>, mut value: u64) {
    loop {
        let byte = (value & 0x7f) as u8;
        value >>= 7;
        if value == 0 {
            out.push(byte);
            return;
        }
        out.push(byte | 0x80);
    }
}

/// Разбор varint; null -- письмо оборвано или переполнено.
pub fn get_varint(data: &[u8], pos: &mut usize) -> Option<u64> {
    let mut result: u64 = 0;
    let mut shift = 0u32;
    loop {
        let byte = *data.get(*pos)?;
        *pos += 1;
        result |= ((byte & 0x7f) as u64) << shift;
        if byte & 0x80 == 0 {
            return Some(result);
        }
        shift += 7;
        if shift > 63 {
            return None;
        }
    }
}

fn put_tag(out: &mut Vec<u8>, field: u32, wire_type: u64) {
    put_varint(out, ((field as u64) << 3) | wire_type);
}

fn put_len_field(out: &mut Vec<u8>, field: u32, bytes: &[u8]) {
    put_tag(out, field, WIRE_LEN);
    put_varint(out, bytes.len() as u64);
    out.extend_from_slice(bytes);
}

/// Версия из Hello (клиент -> демон): поле 1, varint.
pub fn parse_hello_version(data: &[u8]) -> Option<u32> {
    let mut pos = 0usize;
    let mut version = None;
    while pos < data.len() {
        let tag = get_varint(data, &mut pos)?;
        let (field, wire_type) = (tag >> 3, tag & 0x7);
        match (field, wire_type) {
            (1, WIRE_VARINT) => version = Some(get_varint(data, &mut pos)? as u32),
            (_, WIRE_VARINT) => {
                get_varint(data, &mut pos)?;
            }
            (_, WIRE_LEN) => {
                let len = get_varint(data, &mut pos)? as usize;
                pos = pos.checked_add(len)?;
                if pos > data.len() {
                    return None;
                }
            }
            _ => return None,
        }
    }
    version
}

/// Снимок: at_ms=1, rows=2 (повторяющееся сообщение), note=3.
pub fn encode_snapshot(rows: &[Conn], at_ms: u64, note: u32) -> Vec<u8> {
    let mut out = Vec::new();
    put_tag(&mut out, 1, WIRE_VARINT);
    put_varint(&mut out, at_ms);
    for row in rows {
        let mut msg = Vec::new();
        put_len_field(&mut msg, 1, row.proto.as_bytes());
        put_tag(&mut msg, 2, WIRE_VARINT);
        put_varint(&mut msg, row.local_port as u64);
        put_len_field(&mut msg, 3, row.remote.as_bytes());
        put_len_field(&mut msg, 4, row.owner.as_bytes());
        put_len_field(&mut out, 2, &msg);
    }
    put_tag(&mut out, 3, WIRE_VARINT);
    put_varint(&mut out, note as u64);
    out
}

/// Фрейм: u32 LE длина + письмо.
pub fn write_frame(stream: &mut TcpStream, payload: &[u8]) -> std::io::Result<()> {
    stream.write_all(&(payload.len() as u32).to_le_bytes())?;
    stream.write_all(payload)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn varint_known_encodings() {
        // Классические значения, зафиксированные и на JVM-стороне.
        let mut out = Vec::new();
        put_varint(&mut out, 0);
        assert_eq!(out, vec![0u8]);
        let mut out = Vec::new();
        put_varint(&mut out, 300);
        assert_eq!(out, vec![0xac, 0x02]);
        let mut out = Vec::new();
        put_varint(&mut out, u64::MAX);
        assert_eq!(out, vec![0xff; 9].into_iter().chain([0x01u8]).collect::<Vec<_>>());
    }

    #[test]
    fn varint_round_trip() {
        for value in [0u64, 1, 127, 128, 300, 16383, 16384, 1 << 32, u64::MAX] {
            let mut pos = 0;
            let mut out = Vec::new();
            put_varint(&mut out, value);
            assert_eq!(get_varint(&out, &mut pos), Some(value), "value {value}");
            assert_eq!(pos, out.len());
        }
    }

    #[test]
    fn varint_rejects_truncated_and_overlong() {
        let mut pos = 0;
        assert_eq!(get_varint(&[0x80], &mut pos), None, "обрыв посреди числа");
        pos = 0;
        let overlong = [0xffu8; 11];
        assert_eq!(get_varint(&overlong, &mut pos), None, "слишком длинный varint");
    }

    #[test]
    fn hello_parses_version_field() {
        // Hello{version=1}: тег поля 1 (0x08) + varint 1.
        assert_eq!(parse_hello_version(&[0x08, 0x01]), Some(1));
        // Неизвестные поля пропускаются, версия читается.
        assert_eq!(parse_hello_version(&[0x10, 0x05, 0x08, 0x02]), Some(2));
        // Пустое письмо -- версии нет.
        assert_eq!(parse_hello_version(&[]), None);
        // Оборванный varint -- письмо брак.
        assert_eq!(parse_hello_version(&[0x08, 0xff]), None);
    }

    #[test]
    fn snapshot_round_trip() {
        let rows = vec![
            Conn {
                proto: "tcp",
                local_port: 443,
                remote: "1.2.3.4:9".to_string(),
                owner: "curl".to_string(),
            },
            Conn {
                proto: "udp6",
                local_port: 53,
                remote: "-".to_string(),
                owner: String::new(),
            },
        ];
        let encoded = encode_snapshot(&rows, 1700000000000, 1);
        let (at_ms, decoded, note) = decode_snapshot(&encoded).expect("round-trip");
        assert_eq!(at_ms, 1700000000000);
        assert_eq!(note, 1);
        assert_eq!(decoded.len(), 2);
        assert_eq!(decoded[0].proto, "tcp");
        assert_eq!(decoded[0].local_port, 443);
        assert_eq!(decoded[0].remote, "1.2.3.4:9");
        assert_eq!(decoded[0].owner, "curl");
        assert_eq!(decoded[1].remote, "-");
    }

    /// Обратный разбор снимка: тем же wire-форматом, что и JVM-декодер.
    fn decode_snapshot(data: &[u8]) -> Option<(u64, Vec<Conn>, u32)> {
        let mut pos = 0usize;
        let mut at_ms = 0u64;
        let mut note = 0u32;
        let mut rows = Vec::new();
        while pos < data.len() {
            let tag = get_varint(data, &mut pos)?;
            let (field, wire_type) = (tag >> 3, tag & 0x7);
            match (field, wire_type) {
                (1, WIRE_VARINT) => at_ms = get_varint(data, &mut pos)?,
                (2, WIRE_LEN) => {
                    let len = get_varint(data, &mut pos)? as usize;
                    let msg = data.get(pos..pos + len)?;
                    pos += len;
                    rows.push(decode_conn(msg)?);
                }
                (3, WIRE_VARINT) => note = get_varint(data, &mut pos)? as u32,
                (_, WIRE_VARINT) => {
                    get_varint(data, &mut pos)?;
                }
                (_, WIRE_LEN) => {
                    let len = get_varint(data, &mut pos)? as usize;
                    pos = pos.checked_add(len)?;
                    if pos > data.len() {
                        return None;
                    }
                }
                _ => return None,
            }
        }
        Some((at_ms, rows, note))
    }

    fn decode_conn(data: &[u8]) -> Option<Conn> {
        let mut pos = 0usize;
        let mut proto = String::new();
        let mut local_port = 0u32;
        let mut remote = String::new();
        let mut owner = String::new();
        while pos < data.len() {
            let tag = get_varint(data, &mut pos)?;
            let (field, wire_type) = (tag >> 3, tag & 0x7);
            match (field, wire_type) {
                (1, WIRE_LEN) | (3, WIRE_LEN) | (4, WIRE_LEN) => {
                    let len = get_varint(data, &mut pos)? as usize;
                    let text = std::str::from_utf8(data.get(pos..pos + len)?).ok()?.to_string();
                    pos += len;
                    match field {
                        1 => proto = text,
                        3 => remote = text,
                        _ => owner = text,
                    }
                }
                (2, WIRE_VARINT) => local_port = get_varint(data, &mut pos)? as u32,
                (_, WIRE_VARINT) => {
                    get_varint(data, &mut pos)?;
                }
                _ => return None,
            }
        }
        Some(Conn {
            proto: proto_static(&proto),
            local_port,
            remote,
            owner,
        })
    }

    fn proto_static(name: &str) -> &'static str {
        match name {
            "tcp" => "tcp",
            "tcp6" => "tcp6",
            "udp" => "udp",
            "udp6" => "udp6",
            other => unreachable!("неожиданный proto в тесте: {other}"),
        }
    }
}
