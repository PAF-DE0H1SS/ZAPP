#ifndef ZAPP_BIONIC_COMPAT_H
#define ZAPP_BIONIC_COMPAT_H
/*
 * Принудительный первый include для кросс-сборки под bionic (Android).
 * glibc-ориентированный код netfilter-библиотек рассчитывает на заголовки,
 * которых bionic не даёт или даёт в другом месте:
 *   - <linux/netfilter/nfnetlink.h> -- включаем ПЕРВЫМ, чтобы
 *     libnfnetlink/linux_nfnetlink.h не дублировал его определения;
 *   - ETH_P_* -- bionic <netinet/if_ether.h> их не выдаёт, включаем
 *     <linux/if_ether.h>;
 *   - IPTOS_TOS/IPTOS_PREC -- из bionic <netinet/ip.h> удалены.
 */
#include <linux/netfilter/nfnetlink.h>
#include <linux/if_ether.h>

#ifndef IPTOS_TOS
#define IPTOS_TOS(tos)	((tos) & 0x1e)
#endif
#ifndef IPTOS_PREC
#define IPTOS_PREC(tos)	((tos) & 0xe0)
#endif

#endif /* ZAPP_BIONIC_COMPAT_H */
