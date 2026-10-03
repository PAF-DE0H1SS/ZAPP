package route

// ZAPP: обёртка над sing-tun netlink-монитором для Android.
// SELinux запрещает untrusted_app bind() netlink-групп (b/155595000):
// RouteSubscribe/LinkSubscribe падают с EACCES/EPERM, и запуск box
// прерывается с голой ошибкой "permission denied". В этом случае
// переходим на периодический опрос (каждые 3 секунды): колбэки
// defaultInterfaceMonitor получают те же события обновления интерфейса.

import (
	"errors"
	"net"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-tun"
	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/sing/common/x/list"
)

type zappNetworkUpdateMonitor struct {
	inner     tun.NetworkUpdateMonitor
	logger    logger.Logger
	access    sync.Mutex
	callbacks list.List[tun.NetworkUpdateCallback]
	close     chan struct{}
	useInner  bool
}

func newZappNetworkUpdateMonitor(log logger.Logger) (tun.NetworkUpdateMonitor, error) {
	// SELinux Android: untrusted_app не имеет bind на netlink_route_socket
	// (b/155595000) -- ни подписка на группы, ни dump маршрутов. Если и
	// простой net.Interfaces() не проходит, мониторы бесполезны: возвращаем
	// os.ErrInvalid, и NewNetworkManager их не создаёт (штатная ветка).
	if _, err := net.Interfaces(); err != nil && isNetlinkBanErr(err) {
		log.Warn("netlink unavailable (Android SELinux), interface monitoring disabled: ", err)
		return nil, os.ErrInvalid
	}
	inner, err := tun.NewNetworkUpdateMonitor(log)
	if err != nil {
		log.Warn("netlink monitor unavailable, interface polling only: ", err)
		inner = nil
	}
	return &zappNetworkUpdateMonitor{
		inner:  inner,
		logger: log,
		close:  make(chan struct{}),
	}, nil
}

func isNetlinkBanErr(err error) bool {
	if errors.Is(err, os.ErrPermission) || errors.Is(err, tun.ErrNetlinkBanned) {
		return true
	}
	msg := err.Error()
	return strings.Contains(msg, "permission denied") || strings.Contains(msg, "operation not permitted")
}

func (m *zappNetworkUpdateMonitor) Start() error {
	if m.inner != nil {
		err := m.inner.Start()
		if err == nil {
			m.useInner = true
			m.inner.RegisterCallback(m.emit)
			return nil
		}
		if !isNetlinkBanErr(err) {
			return err
		}
		m.logger.Warn("netlink subscribe denied (Android SELinux), polling interfaces instead: ", err)
	}
	go m.pollLoop()
	return nil
}

func (m *zappNetworkUpdateMonitor) pollLoop() {
	ticker := time.NewTicker(3 * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-m.close:
			return
		case <-ticker.C:
			m.emit()
		}
	}
}

func (m *zappNetworkUpdateMonitor) emit() {
	m.access.Lock()
	callbacks := m.callbacks.Array()
	m.access.Unlock()
	for _, callback := range callbacks {
		callback()
	}
}

func (m *zappNetworkUpdateMonitor) RegisterCallback(callback tun.NetworkUpdateCallback) *list.Element[tun.NetworkUpdateCallback] {
	m.access.Lock()
	defer m.access.Unlock()
	return m.callbacks.PushBack(callback)
}

func (m *zappNetworkUpdateMonitor) UnregisterCallback(element *list.Element[tun.NetworkUpdateCallback]) {
	m.access.Lock()
	defer m.access.Unlock()
	m.callbacks.Remove(element)
}

func (m *zappNetworkUpdateMonitor) Close() error {
	select {
	case <-m.close:
		return os.ErrClosed
	default:
		close(m.close)
	}
	if m.useInner && m.inner != nil {
		return m.inner.Close()
	}
	return nil
}
