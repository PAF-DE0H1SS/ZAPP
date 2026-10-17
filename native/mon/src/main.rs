//! zappmon -- монитор соединений ZAPP.
//!
//! Long-lived демон: сам читает таблицы /proc (без спавна ss/lsof на
//! каждый опрос) и шлёт снимки клиенту по protobuf-IPC: TCP loopback,
//! фрейм = u32 LE длина + письмо из daemon.proto. Номер порта -- первой
//! (и единственной) строкой stdout: клиент подключается сам, без файлов
//! и гонок за свободный порт. Родивший процесс JVM убивает демона через
//! shutdown hook; если JVM умерла по-другому -- демон выходит сам после
//! [CLIENT_GRACE] простоя.

mod procfs;
mod wire;

use std::io::Read;
use std::net::{TcpListener, TcpStream};
use std::time::{Duration, Instant};

/// Версия протокола: Hello клиента обязан её назвать.
const PROTO_VERSION: u32 = 1;

/// Пауза между снимками.
const TICK_MS: u64 = 500;

/// Сколько ждать нового клиента после разрыва, прежде чем выйти.
const CLIENT_GRACE: Duration = Duration::from_secs(5);

fn main() {
    let listener = match TcpListener::bind("127.0.0.1:0") {
        Ok(listener) => listener,
        Err(e) => {
            eprintln!("zappmon: bind: {e}");
            std::process::exit(1);
        }
    };
    let port = match listener.local_addr() {
        Ok(addr) => addr.port(),
        Err(e) => {
            eprintln!("zappmon: local_addr: {e}");
            std::process::exit(1);
        }
    };
    println!("{port}");
    let _ = listener.set_nonblocking(true);

    let mut collector = procfs::Collector::new();
    let mut idle_until = Instant::now() + CLIENT_GRACE;
    loop {
        match listener.accept() {
            Ok((stream, _)) => {
                serve(stream, &mut collector);
                idle_until = Instant::now() + CLIENT_GRACE;
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                if Instant::now() >= idle_until {
                    break;
                }
                std::thread::sleep(Duration::from_millis(50));
            }
            Err(e) => {
                eprintln!("zappmon: accept: {e}");
                break;
            }
        }
    }
}

/// Обслуживает одного клиента: сверяет версию и шлёт снимки, пока
/// запись не провалится (клиент ушёл).
fn serve(mut stream: TcpStream, collector: &mut procfs::Collector) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    if read_hello(&mut stream).is_none() {
        return;
    }
    let _ = stream.set_write_timeout(Some(Duration::from_secs(3)));
    loop {
        let (rows, note) = collector.collect();
        let payload = wire::encode_snapshot(&rows, now_ms(), note);
        if wire::write_frame(&mut stream, &payload).is_err() {
            return;
        }
        std::thread::sleep(Duration::from_millis(TICK_MS));
    }
}

/// Hello клиента; None -- чужой держатель порта или обрыв.
fn read_hello(stream: &mut TcpStream) -> Option<u32> {
    let mut header = [0u8; 4];
    stream.read_exact(&mut header).ok()?;
    let len = u32::from_le_bytes(header) as usize;
    if len > wire::MAX_FRAME {
        return None;
    }
    let mut hello = vec![0u8; len];
    stream.read_exact(&mut hello).ok()?;
    wire::parse_hello_version(&hello).filter(|version| *version == PROTO_VERSION)
}

fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
