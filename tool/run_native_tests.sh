#!/usr/bin/env sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CPP="${ROOT}/android/app/src/main/cpp"
OUT="${ROOT}/build/native_test"
mkdir -p "${OUT}"

if command -v cmake >/dev/null 2>&1; then
  cmake -S "${ROOT}/test/native" -B "${OUT}"
  cmake --build "${OUT}"
  ctest --test-dir "${OUT}" --output-on-failure
else
  echo "cmake not found; compiling native tests with cc" >&2
  cc -std=c99 -Wall -Wextra -o "${OUT}/test_endian" \
    "${ROOT}/test/native/test_endian.c" "${CPP}/util_endian.c" -I"${CPP}"
  "${OUT}/test_endian"
  cc -std=c99 -Wall -Wextra -o "${OUT}/test_l2tp_avps" \
    "${ROOT}/test/native/test_l2tp_avps.c" "${CPP}/l2tp_avps.c" "${CPP}/util_endian.c" -I"${CPP}"
  "${OUT}/test_l2tp_avps"
  cc -std=c99 -Wall -Wextra -o "${OUT}/test_ppp_lcp" \
    "${ROOT}/test/native/test_ppp_lcp.c" "${CPP}/util_endian.c" -I"${CPP}"
  "${OUT}/test_ppp_lcp"
  cc -std=c99 -Wall -Wextra -o "${OUT}/test_ppp_ipcp" \
    "${ROOT}/test/native/test_ppp_ipcp.c" "${CPP}/util_endian.c" -I"${CPP}"
  "${OUT}/test_ppp_ipcp"
  cc -std=c99 -Wall -Wextra -o "${OUT}/test_ppp_frame" \
    "${ROOT}/test/native/test_ppp_frame.c"
  "${OUT}/test_ppp_frame"
  cc -std=c99 -Wall -Wextra -o "${OUT}/test_nat_t_keepalive" \
    "${ROOT}/test/native/test_nat_t_keepalive.c" "${CPP}/nat_t_keepalive.c" -I"${CPP}"
  "${OUT}/test_nat_t_keepalive"
fi
