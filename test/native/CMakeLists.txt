cmake_minimum_required(VERSION 3.22)
project(tunnel_forge_native_test C)

set(CMAKE_C_STANDARD 99)

add_executable(test_endian test_endian.c ../../android/app/src/main/cpp/util_endian.c)
target_include_directories(test_endian PRIVATE ../../android/app/src/main/cpp)

enable_testing()
add_test(NAME endian COMMAND test_endian)

add_executable(test_l2tp_avps test_l2tp_avps.c ../../android/app/src/main/cpp/l2tp_avps.c
 ../../android/app/src/main/cpp/util_endian.c)
target_include_directories(test_l2tp_avps PRIVATE ../../android/app/src/main/cpp)
add_test(NAME l2tp_avps COMMAND test_l2tp_avps)

add_executable(test_ppp_lcp test_ppp_lcp.c ../../android/app/src/main/cpp/util_endian.c)
target_include_directories(test_ppp_lcp PRIVATE ../../android/app/src/main/cpp)
add_test(NAME ppp_lcp COMMAND test_ppp_lcp)

add_executable(test_ppp_ipcp test_ppp_ipcp.c ../../android/app/src/main/cpp/util_endian.c)
target_include_directories(test_ppp_ipcp PRIVATE ../../android/app/src/main/cpp)
add_test(NAME ppp_ipcp COMMAND test_ppp_ipcp)

add_executable(test_ppp_frame test_ppp_frame.c)
add_test(NAME ppp_frame COMMAND test_ppp_frame)

add_executable(test_nat_t_keepalive test_nat_t_keepalive.c ../../android/app/src/main/cpp/nat_t_keepalive.c)
target_include_directories(test_nat_t_keepalive PRIVATE ../../android/app/src/main/cpp)
add_test(NAME nat_t_keepalive COMMAND test_nat_t_keepalive)
