package tun

import (
	"fmt"
	"net"
	"os"
	"strconv"
	"time"

	"golang.org/x/sys/unix"
)

// tunFdFromEnv возвращает номер файлового дескриптора туннеля.
//
// Основной путь -- ZAPP_TUN_SOCK: путь к unix-сокету, с которого fd
// принимается сообщением SCM_RIGHTS. Android-вариант обязателен: спавн
// дочернего процесса через ProcessBuilder закрывает все унаследованные
// дескрипторы > 2, поэтому номер fd из окружения (ZAPP_TUN_FD) в ребёнке
// никогда не появится.
//
// ZAPP_TUN_FD -- запасной путь для прямого запуска из shell/root: номер
// берётся из окружения как есть.
//
// Ноль означает «открывай как обычно»: на Linux это /dev/net/tun.
func tunFdFromEnv() int {
	if path := os.Getenv("ZAPP_TUN_SOCK"); path != "" {
		fd, err := recvFdFromSocket(path)
		if err != nil {
			fmt.Fprintf(os.Stderr, "zapp tun fd: recv from %s: %v\n", path, err)
			return 0
		}
		return fd
	}
	raw := os.Getenv("ZAPP_TUN_FD")
	if raw == "" {
		return 0
	}
	fd, err := strconv.Atoi(raw)
	if err != nil || fd <= 0 {
		return 0
	}
	return fd
}

// recvFdFromSocket подключается к unix-сокету и принимает первый
// файловый дескриптор из SCM_RIGHTS.
func recvFdFromSocket(path string) (int, error) {
	conn, err := net.DialTimeout("unix", path, 3*time.Second)
	if err != nil {
		return 0, err
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(5 * time.Second)); err != nil {
		return 0, err
	}
	unixConn, isUnix := conn.(*net.UnixConn)
	if !isUnix {
		return 0, fmt.Errorf("not a unix connection")
	}
	buf := make([]byte, 1)
	oob := make([]byte, unix.CmsgSpace(4))
	_, oobn, _, _, err := unixConn.ReadMsgUnix(buf, oob)
	if err != nil {
		return 0, err
	}
	messages, err := unix.ParseSocketControlMessage(oob[:oobn])
	if err != nil {
		return 0, err
	}
	for _, message := range messages {
		if message.Header.Level == unix.SOL_SOCKET && message.Header.Type == unix.SCM_RIGHTS {
			fds, err := unix.ParseUnixRights(&message)
			if err == nil && len(fds) > 0 && fds[0] > 0 {
				return fds[0], nil
			}
		}
	}
	return 0, fmt.Errorf("no file descriptor in message")
}
