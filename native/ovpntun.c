/*
 * ovpntun -- мост «тун-дескриптор приложения» <-> socketpair openvpn.
 *
 * openvpn в afunix-режиме (--dev-node unix:...) создаёт SOCK_DGRAM
 * socketpair и исполняет эту программу, отдавая один конец своим номером
 * в TUNTAP_SOCKET_FD. Второй конец нужен здесь же: дескриптор туннеля
 * принадлежит VpnService другого процесса и принимается из ZAPP_TUN_SOCK
 * сообщением SCM_RIGHTS -- та же схема, что у box (zapp_fd.go).
 *
 * Один datagram = один IP-пакет, как на самом tun: read(tun) -> send(pair),
 * recv(pair) -> write(tun). Инкапсуляция не нужна, SOCK_DGRAM выбран
 * именно ради поштучной доставки пакетов без собственных заголовков.
 *
 * Выход: SIGINT от openvpn при снятии туннеля (close_tun_afunix шлёт его
 * дочернему процессу) или EOF на паре -- openvpn закрыл свою сторону.
 *
 * Собирается build.sh (build_ovpntun) для всех ABI, кладётся в jniLibs
 * pack.sh как libovpntun.so и запускается openvpn'ом как обычный бинарь.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <poll.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static volatile sig_atomic_t stopping = 0;

static void
on_term(int sig)
{
    (void)sig;
    stopping = 1;
}

/**
 * Принимает тун-дескриптор с unix-сервиса ZAPP_TUN_SOCK.
 *
 * Сервис шлёт одну байтовую запись с SCM_RIGHTS; до её прихода сервер
 * может ещё подниматься, поэтому первые секунды -- с повторами.
 */
static int
recv_tun_fd(const char *path)
{
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof addr);
    addr.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof addr.sun_path) {
        fprintf(stderr, "ovpntun: socket path too long\n");
        return -1;
    }
    strcpy(addr.sun_path, path);

    int s = -1;
    for (int attempt = 0; attempt < 50 && !stopping; attempt++) {
        s = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
        if (s < 0) {
            perror("ovpntun: socket");
            return -1;
        }
        if (connect(s, (struct sockaddr *)&addr, sizeof addr) == 0) {
            break;
        }
        close(s);
        s = -1;
        usleep(100 * 1000);
    }
    if (s < 0) {
        fprintf(stderr, "ovpntun: cannot connect %s\n", path);
        return -1;
    }

    char byte = 0;
    char cbuf[CMSG_SPACE(sizeof(int))];
    struct iovec iov;
    iov.iov_base = &byte;
    iov.iov_len = 1;
    struct msghdr msg;
    memset(&msg, 0, sizeof msg);
    msg.msg_iov = &iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cbuf;
    msg.msg_controllen = sizeof cbuf;

    ssize_t n = recvmsg(s, &msg, 0);
    int fd = -1;
    if (n > 0) {
        for (struct cmsghdr *c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c)) {
            if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_RIGHTS) {
                memcpy(&fd, CMSG_DATA(c), sizeof fd);
                break;
            }
        }
    }
    close(s);
    if (fd < 0) {
        fprintf(stderr, "ovpntun: no tun fd in message from %s\n", path);
    }
    return fd;
}

int
main(void)
{
    const char *pair_env = getenv("TUNTAP_SOCKET_FD");
    if (!pair_env || !*pair_env) {
        fprintf(stderr, "ovpntun: TUNTAP_SOCKET_FD is not set\n");
        return 2;
    }
    int pair_fd = atoi(pair_env);
    if (pair_fd <= 2) {
        fprintf(stderr, "ovpntun: TUNTAP_SOCKET_FD looks wrong: %s\n", pair_env);
        return 2;
    }

    const char *sock = getenv("ZAPP_TUN_SOCK");
    int tun_fd = -1;
    if (sock && *sock) {
        tun_fd = recv_tun_fd(sock);
    } else {
        fprintf(stderr, "ovpntun: ZAPP_TUN_SOCK is not set\n");
    }
    if (tun_fd < 0) {
        return 3;
    }

    signal(SIGINT, on_term);
    signal(SIGTERM, on_term);
    signal(SIGPIPE, SIG_IGN);

    struct pollfd fds[2];
    fds[0].fd = tun_fd;
    fds[0].events = POLLIN;
    fds[0].revents = 0;
    fds[1].fd = pair_fd;
    fds[1].events = POLLIN;
    fds[1].revents = 0;

    unsigned char buf[65536];
    while (!stopping) {
        int ready = poll(fds, 2, 500);
        if (ready < 0) {
            if (errno == EINTR) {
                continue;
            }
            break;
        }
        if (ready == 0) {
            continue;
        }

        if (fds[0].revents & POLLIN) {
            ssize_t n = read(tun_fd, buf, sizeof buf);
            if (n > 0) {
                if (send(pair_fd, buf, (size_t)n, MSG_DONTWAIT) < 0 &&
                    errno != EAGAIN && errno != EINTR) {
                    break;
                }
            } else if (n == 0) {
                break;
            } else if (errno != EINTR && errno != EAGAIN) {
                break;
            }
        }
        if (fds[1].revents & POLLIN) {
            ssize_t n = recv(pair_fd, buf, sizeof buf, 0);
            if (n > 0) {
                ssize_t w = write(tun_fd, buf, (size_t)n);
                if (w < 0 && errno != EINTR && errno != EAGAIN) {
                    break;
                }
            } else if (n == 0) {
                break;
            } else if (errno != EINTR && errno != EAGAIN) {
                break;
            }
        }
        if (fds[0].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            break;
        }
        if (fds[1].revents & (POLLERR | POLLHUP | POLLNVAL)) {
            break;
        }
    }

    close(tun_fd);
    close(pair_fd);
    return 0;
}
