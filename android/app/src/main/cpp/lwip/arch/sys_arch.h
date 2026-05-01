#ifndef TUNNEL_FORGE_LWIP_ARCH_SYS_ARCH_H
#define TUNNEL_FORGE_LWIP_ARCH_SYS_ARCH_H

typedef unsigned int sys_prot_t;

#define SYS_ARCH_DECL_PROTECT(lev) sys_prot_t lev
#define SYS_ARCH_PROTECT(lev)     \
  do {                            \
    (lev) = 0;                    \
  } while (0)
#define SYS_ARCH_UNPROTECT(lev) \
  do {                          \
    (void)(lev);                \
  } while (0)

#endif
