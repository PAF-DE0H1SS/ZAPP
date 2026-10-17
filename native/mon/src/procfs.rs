//! Таблицы соединений без внешних утилит: /proc/net + inode -> процесс.
//!
//! Это сердце демона: `ss`/`lsof` спавнит процессы на каждый опрос, тут
//! всё читается напрямую из procfs. Иноды сокетов живут долго, поэтому
//! карта inode->процесс строится не каждый тик, а не чаще раза в
//! [SCAN_TTL]; между сканами свежие сокеты показываются без владельца.

use std::collections::HashMap;
use std::fs;
use std::time::{Duration, Instant};

/// note из daemon.proto.
pub const NOTE_OK: u32 = 1;
pub const NOTE_NO_PROC: u32 = 2;

/// Одна строка снимка.
pub struct Conn {
    pub proto: &'static str,
    pub local_port: u32,
    pub remote: String,
    pub owner: String,
}

/// Строка таблицы: сокет и его инода (владелец резолвится позже).
struct Entry {
    conn: Conn,
    inode: u64,
}

/// Полный скан /proc не чаще этого срока.
const SCAN_TTL: Duration = Duration::from_secs(2);

pub struct Collector {
    owners: HashMap<u64, String>,
    last_scan: Option<Instant>,
}

impl Collector {
    pub fn new() -> Self {
        Collector {
            owners: HashMap::new(),
            last_scan: None,
        }
    }

    /// Снимок: строки таблиц и note о читаемости procfs.
    pub fn collect(&mut self) -> (Vec<Conn>, u32) {
        let (mut entries, note) = self.tables();
        if note == NOTE_OK
            && entries
                .iter()
                .any(|e| e.inode != 0 && !self.owners.contains_key(&e.inode))
        {
            self.scan_proc();
        }
        for entry in &mut entries {
            entry.conn.owner = self
                .owners
                .get(&entry.inode)
                .cloned()
                .unwrap_or_else(|| "-".to_string());
        }
        (entries.into_iter().map(|e| e.conn).collect(), note)
    }

    /// Четыре таблицы /proc/net одной пачкой.
    fn tables(&self) -> (Vec<Entry>, u32) {
        let mut entries = Vec::new();
        for (file, proto) in [
            ("tcp", "tcp"),
            ("tcp6", "tcp6"),
            ("udp", "udp"),
            ("udp6", "udp6"),
        ] {
            let text = match fs::read_to_string(format!("/proc/net/{file}")) {
                Ok(text) => text,
                // procfs не смонтирован (песочница): читать нечего.
                Err(_) => return (entries, NOTE_NO_PROC),
            };
            parse_table(&text, proto, &mut entries);
        }
        (entries, NOTE_OK)
    }

    /// Полный обход /proc: pid -> comm -> сокеты его файловых дескрипторов.
    fn scan_proc(&mut self) {
        if let Some(at) = self.last_scan {
            if at.elapsed() < SCAN_TTL {
                return;
            }
        }
        self.last_scan = Some(Instant::now());
        let Ok(pids) = fs::read_dir("/proc") else { return };
        for pid in pids.flatten() {
            let name = pid.file_name();
            let pid = name.to_string_lossy().to_string();
            if !pid.bytes().all(|b| b.is_ascii_digit()) {
                continue;
            }
            let comm = fs::read_to_string(format!("/proc/{pid}/comm"))
                .map(|c| c.trim_end().to_string())
                .unwrap_or_else(|_| pid.clone());
            let Ok(fds) = fs::read_dir(format!("/proc/{pid}/fd")) else {
                continue;
            };
            for fd in fds.flatten() {
                let Ok(target) = fs::read_link(fd.path()) else {
                    continue;
                };
                let target = target.to_string_lossy();
                let Some(inode) = target
                    .strip_prefix("socket:[")
                    .and_then(|rest| rest.strip_suffix(']'))
                    .and_then(|num| num.parse::<u64>().ok())
                else {
                    continue;
                };
                self.owners.entry(inode).or_insert_with(|| comm.clone());
            }
        }
    }
}

/// Разбор одной таблицы: sl, local, rem, st, ..., inode (поле 10).
fn parse_table(text: &str, proto: &'static str, out: &mut Vec<Entry>) {
    for line in text.lines().skip(1) {
        let fields: Vec<&str> = line.split_whitespace().collect();
        if fields.len() < 10 {
            continue;
        }
        let Some((_, local_port)) = split_address(fields[1]) else {
            continue;
        };
        // Слушатель и датаграмма без адресата: нулевой порт, как у ss.
        let remote = match split_address(fields[2]) {
            Some((ip, port)) if port != 0 => format_address(&ip, port),
            _ => "-".to_string(),
        };
        out.push(Entry {
            conn: Conn {
                proto,
                local_port,
                remote,
                owner: String::new(),
            },
            inode: fields[9].parse().unwrap_or(0),
        });
    }
}

/// "0100007F:0035" -> ("127.0.0.1", 53); v6 -- 32 hex, слова little-endian.
fn split_address(addr: &str) -> Option<(String, u32)> {
    let (ip, port_hex) = addr.split_once(':')?;
    let port = u32::from_str_radix(port_hex, 16).ok()?;
    let ip = match ip.len() {
        8 => format_ipv4(ip)?,
        32 => format_ipv6(ip)?,
        _ => return None,
    };
    Some((ip, port))
}

/// Адрес: v4 "a.b.c.d:port", v6 "[x:x]:port" -- в стиле ss.
fn format_address(ip: &str, port: u32) -> String {
    if ip.contains(':') {
        format!("[{ip}]:{port}")
    } else {
        format!("{ip}:{port}")
    }
}

fn format_ipv4(hex: &str) -> Option<String> {
    let word = u32::from_str_radix(hex, 16).ok()?;
    let b = word.to_le_bytes();
    Some(format!("{}.{}.{}.{}", b[0], b[1], b[2], b[3]))
}

fn format_ipv6(hex: &str) -> Option<String> {
    let mut groups = [0u16; 8];
    for (index, chunk) in hex.as_bytes().chunks(8).enumerate() {
        let word = u32::from_str_radix(std::str::from_utf8(chunk).ok()?, 16).ok()?;
        let b = word.to_le_bytes();
        groups[index * 2] = u16::from_be_bytes([b[0], b[1]]);
        groups[index * 2 + 1] = u16::from_be_bytes([b[2], b[3]]);
    }
    Some(groups_to_string(&groups, longest_zero_run(&groups)))
}

/// Самая длинная серия нулевых групп; сжимается в "::".
fn longest_zero_run(groups: &[u16; 8]) -> Option<(usize, usize)> {
    let mut best: Option<(usize, usize)> = None;
    let mut start = 0usize;
    let mut len = 0usize;
    for (i, group) in groups.iter().enumerate() {
        if *group == 0 {
            if len == 0 {
                start = i;
            }
            len += 1;
            if len > 1 && best.map(|(_, best_len)| len > best_len).unwrap_or(true) {
                best = Some((start, len));
            }
        } else {
            len = 0;
        }
    }
    best
}

/// Группы в строку с "::" вместо серии нулей (одиночный ноль не жмётся).
fn groups_to_string(groups: &[u16; 8], skip: Option<(usize, usize)>) -> String {
    let mut out = String::new();
    let mut i = 0;
    while i < 8 {
        if let Some((start, len)) = skip {
            if i == start {
                out.push_str("::");
                i += len;
                continue;
            }
        }
        if !out.is_empty() && !out.ends_with(':') {
            out.push(':');
        }
        out.push_str(&format!("{:x}", groups[i]));
        i += 1;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    const TCP_HEADER: &str =
        "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode";

    #[test]
    fn parses_listen_and_established_rows() {
        let text = format!(
            "{TCP_HEADER}\n\
             0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000     0        0 20481 1 0000000000000000 100 0 0 10 0\n\
             1: 0100007F:0016 0100007F:C350 01 00000000:00000000 00:00000000 00000000  1000        0 20482 1 0000000000000000 100 0 0 10 0\n\
             2: 00000000:0035 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 20483 1 0000000000000000 100 0 0 10 0"
        );
        let mut entries = Vec::new();
        parse_table(&text, "tcp", &mut entries);
        assert_eq!(entries.len(), 3);
        assert_eq!(entries[0].conn.local_port, 8080);
        assert_eq!(entries[0].conn.remote, "-", "слушатель без адресата");
        assert_eq!(entries[0].inode, 20481);
        assert_eq!(entries[1].conn.remote, "127.0.0.1:50000");
        assert_eq!(entries[2].conn.local_port, 53, "dns-слушатель на0");
    }

    #[test]
    fn parses_ipv6_loopback() {
        let text = format!(
            "{TCP_HEADER}\n\
             0: 00000000000000000000000001000000:01BB 00000000000000000000000001000000:9C40 01 00000000:00000000 00:00000000 00000000     0        0 30001 1 0000000000000000 100 0 0 10 0"
        );
        let mut entries = Vec::new();
        parse_table(&text, "tcp6", &mut entries);
        assert_eq!(entries[0].conn.local_port, 443);
        assert_eq!(entries[0].conn.remote, "[::1]:40000");
    }

    #[test]
    fn formats_addresses_like_ss() {
        assert_eq!(split_address("0100007F:0035"), Some(("127.0.0.1".into(), 53)));
        assert_eq!(
            split_address("00000000000000000000000001000000:01BB"),
            Some(("::1".into(), 443))
        );
        // 00000000:0000 -- слушатель, порт 0, адреса нет.
        assert_eq!(split_address("00000000:0000"), Some(("0.0.0.0".into(), 0)));
        assert_eq!(split_address("bogus"), None);
        assert_eq!(format_address("::1", 80), "[::1]:80");
        assert_eq!(format_address("10.0.0.1", 80), "10.0.0.1:80");
    }

    #[test]
    fn compresses_ipv6_zero_runs() {
        // Длинная серия жмётся, одиночный ноль -- нет.
        assert_eq!(groups_to_string(&[0, 0, 0, 0, 0, 0, 0, 1], longest_zero_run(&[0, 0, 0, 0, 0, 0, 0, 1])), "::1");
        assert_eq!(groups_to_string(&[0xfe80, 0, 0, 0, 0, 0, 0, 1], longest_zero_run(&[0xfe80, 0, 0, 0, 0, 0, 0, 1])), "fe80::1");
        assert_eq!(groups_to_string(&[0, 0, 0, 0, 0, 0, 0, 0], longest_zero_run(&[0, 0, 0, 0, 0, 0, 0, 0])), "::");
        let one = [0, 0, 0, 0, 0, 0, 0, 2];
        assert_eq!(groups_to_string(&one, longest_zero_run(&one)), "::2", "одиночный ноль не жмётся");
        let flat = [0x2001, 0xdb8, 0, 0, 1, 0, 0, 1];
        assert_eq!(groups_to_string(&flat, longest_zero_run(&flat)), "2001:db8::1:0:0:1");
    }

    #[test]
    fn garbage_lines_are_skipped() {
        let text = format!("{TCP_HEADER}\nnot a table line\n0: broken\n");
        let mut entries = Vec::new();
        parse_table(&text, "tcp", &mut entries);
        assert!(entries.is_empty());
    }
}
