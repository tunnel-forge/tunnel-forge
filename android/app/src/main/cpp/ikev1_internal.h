#ifndef TUNNEL_FORGE_IKEV1_INTERNAL_H
#define TUNNEL_FORGE_IKEV1_INTERNAL_H

/*
 * ISAKMP payload and exchange-type constants for ikev1.c only (RFC 2408, 2409, NAT-D RFC 3947).
 * Not part of the public JNI/engine API.
 */

#include <stddef.h>
#include <stdint.h>

/* ISAKMP payload types (RFC 2408) */
#define IKE_PT_NONE 0
#define IKE_PT_SA 1
#define IKE_PT_P 2
#define IKE_PT_T 3
#define IKE_PT_KE 4
#define IKE_PT_ID 5
#define IKE_PT_CERT 6
#define IKE_PT_CR 7
#define IKE_PT_HASH 8
#define IKE_PT_SIG 9
#define IKE_PT_NONCE 10
#define IKE_PT_NOTIFY 11
#define IKE_PT_DELETE 12
#define IKE_PT_VID 13
#define IKE_PT_NAT_D 20 /* RFC 3947 */

#define IKE_EXCH_MAIN 2
#define IKE_EXCH_AGGRESSIVE 4
#define IKE_EXCH_INFO 5
#define IKE_EXCH_QUICK 32

#define IKE_FLAG_ENC 0x01

/* ISAKMP notify types (RFC 2408/2409) */
#define IKE_N_INVALID_HASH_INFORMATION 23

// Vendor ID: MD5 of "RFC 3947"; first 16 bytes used by strongSwan/libreswan.
#define IKE_VID_RFC3947_LEN 16

#endif
