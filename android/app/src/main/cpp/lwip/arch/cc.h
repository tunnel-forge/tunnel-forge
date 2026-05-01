#ifndef TUNNEL_FORGE_LWIP_ARCH_CC_H
#define TUNNEL_FORGE_LWIP_ARCH_CC_H

#include <stdio.h>
#include <stdlib.h>

#define BYTE_ORDER LITTLE_ENDIAN

#define LWIP_PLATFORM_ASSERT(x) abort()
#define LWIP_PLATFORM_DIAG(x)                                                                                          \
  do {                                                                                                                 \
    printf x;                                                                                                          \
  } while (0)

#endif
