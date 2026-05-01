cmake_minimum_required(VERSION 3.22.1)
project(tunnel_engine C)

set(CMAKE_C_STANDARD 99)

# Keep clang-tidy opt-in because it is slower than a normal native build and can
# surface analyzer findings unrelated to the code a developer is currently touching.
option(TUNNEL_FORGE_CLANG_TIDY "Run clang-tidy for tunnel_engine C sources" OFF)
if(TUNNEL_FORGE_CLANG_TIDY)
  if(NOT CLANG_TIDY_COMMAND)
    find_program(CLANG_TIDY_COMMAND NAMES clang-tidy REQUIRED)
  endif()
  set(TUNNEL_FORGE_CLANG_TIDY_COMMAND
      "${CLANG_TIDY_COMMAND};--config-file=${CMAKE_CURRENT_LIST_DIR}/../../../../../.clang-tidy")
endif()

include(FetchContent)
set(ENABLE_PROGRAMS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(MBEDTLS_FATAL_WARNINGS OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
  mbedtls
  URL https://github.com/Mbed-TLS/mbedtls/archive/refs/tags/v2.28.9.tar.gz
)
FetchContent_MakeAvailable(mbedtls)
if(TARGET mbedcrypto)
  target_compile_definitions(mbedcrypto PRIVATE MBEDTLS_MD4_C)
endif()

FetchContent_Declare(
  lwip
  URL https://github.com/lwip-tcpip/lwip/archive/refs/tags/STABLE-2_2_0_RELEASE.tar.gz
)
FetchContent_MakeAvailable(lwip)

set(LWIP_CORE_SOURCES
  ${lwip_SOURCE_DIR}/src/core/def.c
  ${lwip_SOURCE_DIR}/src/core/inet_chksum.c
  ${lwip_SOURCE_DIR}/src/core/init.c
  ${lwip_SOURCE_DIR}/src/core/ip.c
  ${lwip_SOURCE_DIR}/src/core/mem.c
  ${lwip_SOURCE_DIR}/src/core/memp.c
  ${lwip_SOURCE_DIR}/src/core/netif.c
  ${lwip_SOURCE_DIR}/src/core/pbuf.c
  ${lwip_SOURCE_DIR}/src/core/raw.c
  ${lwip_SOURCE_DIR}/src/core/stats.c
  ${lwip_SOURCE_DIR}/src/core/sys.c
  ${lwip_SOURCE_DIR}/src/core/tcp.c
  ${lwip_SOURCE_DIR}/src/core/tcp_in.c
  ${lwip_SOURCE_DIR}/src/core/tcp_out.c
  ${lwip_SOURCE_DIR}/src/core/timeouts.c
  ${lwip_SOURCE_DIR}/src/core/udp.c
  ${lwip_SOURCE_DIR}/src/core/ipv4/autoip.c
  ${lwip_SOURCE_DIR}/src/core/ipv4/dhcp.c
  ${lwip_SOURCE_DIR}/src/core/ipv4/etharp.c
  ${lwip_SOURCE_DIR}/src/core/ipv4/icmp.c
  ${lwip_SOURCE_DIR}/src/core/ipv4/igmp.c
  ${lwip_SOURCE_DIR}/src/core/ipv4/ip4.c
  ${lwip_SOURCE_DIR}/src/core/ipv4/ip4_addr.c
  ${lwip_SOURCE_DIR}/src/core/ipv4/ip4_frag.c
  ${lwip_SOURCE_DIR}/src/netif/ethernet.c
)

add_library(
  tunnel_engine
  SHARED
  vpn_jni.c
  tunnel_loop.c
  packet_endpoint.c
  proxy_lwip.c
  nat_t_keepalive.c
  util.c
  util_endian.c
  modp2048_data.c
  ikev1.c
  esp_udp.c
  l2tp_avps.c
  l2tp.c
  ppp_mschap.c
  ppp.c
  ${LWIP_CORE_SOURCES}
)

if(TUNNEL_FORGE_CLANG_TIDY)
  set_property(TARGET tunnel_engine PROPERTY C_CLANG_TIDY "${TUNNEL_FORGE_CLANG_TIDY_COMMAND}")
endif()

target_include_directories(
  tunnel_engine
  PRIVATE
    ${mbedtls_SOURCE_DIR}/include
    ${lwip_SOURCE_DIR}/src/include
    ${CMAKE_CURRENT_LIST_DIR}/lwip
)

target_link_libraries(
  tunnel_engine
  PRIVATE mbedtls mbedx509 mbedcrypto log
)

# Inner L2TP-in-UDP checksum: RFC 768 allows 0 = unused. Some kernels drop wrong computed checksums after ESP decap.
# A/B: set OFF (or pass -DTUNNEL_FORGE_ESP_INNER_UDP_NO_CHECKSUM=0 from Gradle) to compute checksums.
option(TUNNEL_FORGE_ESP_INNER_UDP_NO_CHECKSUM "Use inner UDP checksum 0 (interop)" ON)
if(TUNNEL_FORGE_ESP_INNER_UDP_NO_CHECKSUM)
  target_compile_definitions(tunnel_engine PRIVATE TUNNEL_FORGE_ESP_INNER_UDP_NO_CHECKSUM=1)
endif()

# Logs keymat_i/keymat_r once after Quick Mode; compare to `ip xfrm state` on the VPN gateway (secrets in logcat).
option(TUNNEL_FORGE_DEBUG_ESP_KEYMAT "Log ESP KEYMAT after IKE QM (debug only)" OFF)
if(TUNNEL_FORGE_DEBUG_ESP_KEYMAT)
  target_compile_definitions(tunnel_engine PRIVATE TUNNEL_FORGE_DEBUG_ESP_KEYMAT=1)
endif()

# KEYMAT variant selector for controlled interop trials:
# 1=A (per-SA), 2=B (single-stream Ni|Nr split), 3=C (single-stream Nr|Ni split)
set(TUNNEL_FORGE_KEYMAT_VARIANT "1" CACHE STRING "Active KEYMAT variant (1=A,2=B,3=C)")
set_property(CACHE TUNNEL_FORGE_KEYMAT_VARIANT PROPERTY STRINGS 1 2 3)
target_compile_definitions(tunnel_engine PRIVATE TUNNEL_FORGE_KEYMAT_VARIANT=${TUNNEL_FORGE_KEYMAT_VARIANT})
