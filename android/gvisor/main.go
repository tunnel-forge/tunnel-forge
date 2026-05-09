package main

import "C"

// cgo is required for //export and C.int even though this file does not call C functions directly.

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

const nicID tcpip.NICID = 1
const tcpProtocol = 6
const tcpFlagFin = 0x01
const tcpFlagSyn = 0x02
const tcpFlagRst = 0x04
const tcpFlagAck = 0x10

type tcpSession struct {
	conn net.Conn
}

// tcpFlowKey is stored in network byte order and matches packets as they appear on the IPv4 wire.
type tcpFlowKey struct {
	src   [4]byte
	dst   [4]byte
	sport uint16
	dport uint16
}

type ipv4TCPPacket struct {
	flow  tcpFlowKey
	flags byte
}

// tcpOpenDiag records packet-level evidence for one TCP open attempt.
//
// Kotlin includes this in failure logs when a connect times out, resets, or is canceled. The
// counters make it possible to distinguish "no SYN left gVisor" from "SYN went out but no reply".
type tcpOpenDiag struct {
	openID        int
	remoteIPv4    [4]byte
	remotePort    uint16
	localPort     uint16
	synCount      int
	synAckCount   int
	rstCount      int
	finCount      int
	startedAt     time.Time
	lastObserved  time.Time
	failureCode   int
	failureReason string
}

type gvisorState struct {
	mu                  sync.Mutex
	stack               *stack.Stack
	linkEP              *channel.Endpoint
	cancel              context.CancelFunc
	sessions            map[int]*tcpSession
	pendingOpens        map[int]*tcpOpenDiag
	pendingOpenCancels  map[int]context.CancelFunc
	flowToOpenAttemptID map[tcpFlowKey]int
	completedOpenDiags  map[int]string
	completedOpenOrder  []int
	nextID              int
	nextOpenAttemptID   int
	running             bool
	lastOpenDiagnostics string

	outboundPackets atomic.Uint64
	inboundPackets  atomic.Uint64
	openAttempts    atomic.Uint64
	openOK          atomic.Uint64
	openFailed      atomic.Uint64
	openTimeouts    atomic.Uint64
	openImmediate   atomic.Uint64
	openResets      atomic.Uint64
	openInternal    atomic.Uint64
	openCanceled    atomic.Uint64
	openSynOut      atomic.Uint64
	openSynAckIn    atomic.Uint64
	openRstIn       atomic.Uint64
}

// state is process-global because the Android app loads this package as one c-shared library.
// All pointer-like gVisor objects and maps are protected by state.mu; atomic counters are read
// lock-free for stats snapshots.
var state gvisorState

func bytesFromPtr(ptr unsafe.Pointer, length C.int) []byte {
	if ptr == nil || length <= 0 {
		return nil
	}
	return C.GoBytes(ptr, length)
}

func ipv4AddrFromOctets(octets []byte) tcpip.Address {
	var raw [4]byte
	copy(raw[:], octets)
	return tcpip.AddrFrom4(raw)
}

//export tf_gvisor_start
func tf_gvisor_start(clientIPv4 unsafe.Pointer, mtu C.int) C.int {
	ip := bytesFromPtr(clientIPv4, 4)
	if len(ip) != 4 || mtu < 576 || mtu > 1500 {
		return -1
	}

	state.mu.Lock()
	defer state.mu.Unlock()

	if state.running {
		stopLocked()
	}

	// The channel endpoint is the packet boundary between native PPP/L2TP and gVisor netstack.
	linkEP := channel.New(4096, uint32(mtu), "")
	s := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	if err := s.CreateNIC(nicID, linkEP); err != nil {
		return -2
	}
	protocolAddr := tcpip.ProtocolAddress{
		Protocol: ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddressWithPrefix{
			Address:   ipv4AddrFromOctets(ip),
			PrefixLen: 32,
		},
	}
	if err := s.AddProtocolAddress(nicID, protocolAddr, stack.AddressProperties{}); err != nil {
		return -3
	}
	s.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: nicID}})

	ctx, cancel := context.WithCancel(context.Background())
	state.stack = s
	state.linkEP = linkEP
	state.cancel = cancel
	state.sessions = make(map[int]*tcpSession)
	state.pendingOpens = make(map[int]*tcpOpenDiag)
	state.pendingOpenCancels = make(map[int]context.CancelFunc)
	state.flowToOpenAttemptID = make(map[tcpFlowKey]int)
	state.completedOpenDiags = make(map[int]string)
	state.completedOpenOrder = nil
	state.nextID = 1
	state.nextOpenAttemptID = 1
	state.running = true
	state.lastOpenDiagnostics = ""
	state.outboundPackets.Store(0)
	state.inboundPackets.Store(0)
	state.openAttempts.Store(0)
	state.openOK.Store(0)
	state.openFailed.Store(0)
	state.openTimeouts.Store(0)
	state.openImmediate.Store(0)
	state.openResets.Store(0)
	state.openInternal.Store(0)
	state.openCanceled.Store(0)
	state.openSynOut.Store(0)
	state.openSynAckIn.Store(0)
	state.openRstIn.Store(0)

	go func() {
		<-ctx.Done()
		linkEP.Close()
	}()

	return 0
}

func stopLocked() {
	if state.cancel != nil {
		state.cancel()
	}
	for id, session := range state.sessions {
		_ = session.conn.Close()
		delete(state.sessions, id)
	}
	for id, cancel := range state.pendingOpenCancels {
		cancel()
		delete(state.pendingOpenCancels, id)
	}
	if state.linkEP != nil {
		state.linkEP.Close()
	}
	state.stack = nil
	state.linkEP = nil
	state.cancel = nil
	state.sessions = nil
	state.pendingOpens = nil
	state.pendingOpenCancels = nil
	state.flowToOpenAttemptID = nil
	state.completedOpenDiags = nil
	state.completedOpenOrder = nil
	state.running = false
}

//export tf_gvisor_stop
func tf_gvisor_stop() {
	state.mu.Lock()
	defer state.mu.Unlock()
	stopLocked()
}

//export tf_gvisor_inject_inbound
func tf_gvisor_inject_inbound(packet unsafe.Pointer, length C.int) C.int {
	data := bytesFromPtr(packet, length)
	if len(data) == 0 {
		return -1
	}
	state.mu.Lock()
	linkEP := state.linkEP
	running := state.running
	state.mu.Unlock()
	if !running || linkEP == nil {
		return -2
	}
	// Inbound is from the negotiated PPP/L2TP tunnel into gVisor.
	pkt := stack.NewPacketBuffer(stack.PacketBufferOptions{
		Payload: buffer.MakeWithData(data),
	})
	defer pkt.DecRef()
	linkEP.InjectInbound(ipv4.ProtocolNumber, pkt)
	state.inboundPackets.Add(1)
	noteInboundPacket(data)
	return 0
}

//export tf_gvisor_read_outbound
func tf_gvisor_read_outbound(out unsafe.Pointer, maxLen C.int, timeoutMs C.int) C.int {
	if out == nil || maxLen <= 0 {
		return -1
	}
	state.mu.Lock()
	linkEP := state.linkEP
	running := state.running
	state.mu.Unlock()
	if !running || linkEP == nil {
		return -2
	}

	deadline := time.Now().Add(time.Duration(timeoutMs) * time.Millisecond)
	for {
		pkt := linkEP.Read()
		if pkt != nil {
			defer pkt.DecRef()
			buf := pkt.ToBuffer()
			data := buf.Flatten()
			if len(data) > int(maxLen) {
				return -3
			}
			// Outbound is from gVisor toward PPP/L2TP. The caller owns the output buffer.
			noteOutboundPacket(data)
			copy(unsafe.Slice((*byte)(out), int(maxLen)), data)
			state.outboundPackets.Add(1)
			return C.int(len(data))
		}
		if timeoutMs <= 0 || time.Now().After(deadline) {
			return 0
		}
		time.Sleep(5 * time.Millisecond)
	}
}

//export tf_gvisor_tcp_open
func tf_gvisor_tcp_open(remoteIPv4 unsafe.Pointer, port C.int, timeoutMs C.int) C.int {
	state.mu.Lock()
	openID := state.nextOpenAttemptID
	state.nextOpenAttemptID++
	if state.nextOpenAttemptID <= 0 {
		state.nextOpenAttemptID = 1
	}
	state.mu.Unlock()
	return tf_gvisor_tcp_open_cancelable(C.int(openID), remoteIPv4, port, timeoutMs)
}

//export tf_gvisor_tcp_open_cancelable
func tf_gvisor_tcp_open_cancelable(openID C.int, remoteIPv4 unsafe.Pointer, port C.int, timeoutMs C.int) C.int {
	ip := bytesFromPtr(remoteIPv4, 4)
	if openID <= 0 || len(ip) != 4 || port <= 0 || port > 65535 {
		return -1
	}
	state.openAttempts.Add(1)
	state.mu.Lock()
	s := state.stack
	running := state.running
	if state.pendingOpens[int(openID)] != nil {
		state.mu.Unlock()
		state.openFailed.Add(1)
		state.openImmediate.Add(1)
		return -1
	}
	open := beginOpenLocked(int(openID), ip, uint16(port))
	state.mu.Unlock()
	if !running || s == nil {
		state.openFailed.Add(1)
		state.openImmediate.Add(1)
		finishOpenFailure(open, -2, "stack-not-running")
		return -2
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	state.mu.Lock()
	if state.pendingOpenCancels == nil {
		state.pendingOpenCancels = make(map[int]context.CancelFunc)
	}
	state.pendingOpenCancels[open] = cancel
	state.mu.Unlock()
	// Bind only the local NIC; gVisor selects the ephemeral source port that diagnostics observe.
	defer func() {
		cancel()
		state.mu.Lock()
		delete(state.pendingOpenCancels, open)
		state.mu.Unlock()
	}()
	conn, err := gonet.DialTCPWithBind(
		ctx,
		s,
		tcpip.FullAddress{NIC: nicID},
		tcpip.FullAddress{NIC: nicID, Addr: ipv4AddrFromOctets(ip), Port: uint16(port)},
		ipv4.ProtocolNumber,
	)
	if err != nil {
		state.openFailed.Add(1)
		switch ctx.Err() {
		case context.DeadlineExceeded:
			state.openTimeouts.Add(1)
			finishOpenFailure(open, -4, "timeout")
			return -4
		case context.Canceled:
			state.openCanceled.Add(1)
			finishOpenFailure(open, -7, "client-aborted")
			return -7
		}
		code, reason := classifyOpenError(err)
		switch code {
		case -5:
			state.openResets.Add(1)
		case -6:
			state.openImmediate.Add(1)
		default:
			state.openInternal.Add(1)
		}
		finishOpenFailure(open, code, reason)
		return C.int(code)
	}

	state.mu.Lock()
	delete(state.pendingOpenCancels, open)
	id := state.nextID
	state.nextID++
	if state.nextID <= 0 {
		state.nextID = 1
	}
	state.sessions[id] = &tcpSession{conn: conn}
	finishOpenSuccessLocked(open, id)
	state.mu.Unlock()
	state.openOK.Add(1)
	return C.int(id)
}

//export tf_gvisor_tcp_cancel_open
func tf_gvisor_tcp_cancel_open(openID C.int) C.int {
	if openID <= 0 {
		return -1
	}
	state.mu.Lock()
	cancel := state.pendingOpenCancels[int(openID)]
	state.mu.Unlock()
	if cancel == nil {
		return -2
	}
	cancel()
	return 0
}

func beginOpenLocked(openID int, remoteIPv4 []byte, remotePort uint16) int {
	if state.pendingOpens == nil {
		state.pendingOpens = make(map[int]*tcpOpenDiag)
	}
	var ip [4]byte
	copy(ip[:], remoteIPv4)
	state.pendingOpens[openID] = &tcpOpenDiag{
		openID:     openID,
		remoteIPv4: ip,
		remotePort: remotePort,
		startedAt:  time.Now(),
	}
	return openID
}

func finishOpenSuccessLocked(openID int, sessionID int) {
	diag := state.pendingOpens[openID]
	if diag == nil {
		return
	}
	diag.failureCode = 0
	diag.failureReason = "ok"
	storeCompletedOpenDiagLocked(openID, formatOpenDiagLocked(diag, sessionID))
	removeOpenMappingsLocked(openID, diag)
	delete(state.pendingOpens, openID)
	delete(state.pendingOpenCancels, openID)
}

func finishOpenFailure(openID int, code int, reason string) {
	state.mu.Lock()
	defer state.mu.Unlock()
	diag := state.pendingOpens[openID]
	if diag == nil {
		storeCompletedOpenDiagLocked(openID, fmt.Sprintf("openDiag openId=%d sessionId=0 code=%d reason=%s missing=true", openID, code, reason))
		return
	}
	diag.failureCode = code
	diag.failureReason = reason
	storeCompletedOpenDiagLocked(openID, formatOpenDiagLocked(diag, 0))
	removeOpenMappingsLocked(openID, diag)
	delete(state.pendingOpens, openID)
	delete(state.pendingOpenCancels, openID)
}

func storeCompletedOpenDiagLocked(openID int, summary string) {
	if state.completedOpenDiags == nil {
		state.completedOpenDiags = make(map[int]string)
	}
	state.completedOpenDiags[openID] = summary
	state.completedOpenOrder = append(state.completedOpenOrder, openID)
	// Keep bounded history for Kotlin requests without retaining every completed open forever.
	for len(state.completedOpenOrder) > 128 {
		oldest := state.completedOpenOrder[0]
		state.completedOpenOrder = state.completedOpenOrder[1:]
		delete(state.completedOpenDiags, oldest)
	}
	state.lastOpenDiagnostics = summary
}

func removeOpenMappingsLocked(attempt int, diag *tcpOpenDiag) {
	if diag.localPort == 0 || state.flowToOpenAttemptID == nil {
		return
	}
	for flow, mappedAttempt := range state.flowToOpenAttemptID {
		if mappedAttempt == attempt {
			delete(state.flowToOpenAttemptID, flow)
		}
	}
}

func classifyOpenError(err error) (int, string) {
	if errors.Is(err, net.ErrClosed) {
		return -2, "local-close"
	}
	if errors.Is(err, syscall.ECONNRESET) {
		return -5, "reset"
	}
	if errors.Is(err, syscall.ENETUNREACH) || errors.Is(err, syscall.EHOSTUNREACH) {
		return -6, "unreachable"
	}
	msg := strings.ToLower(err.Error())
	switch {
	case strings.Contains(msg, "reset"):
		return -5, "reset"
	case strings.Contains(msg, "unreachable"):
		return -6, "unreachable"
	default:
		return -3, "internal"
	}
}

// sessionByID deliberately returns the session without holding state.mu; net.Conn is safe for
// concurrent read/write, and close removes future lookups while unblocking current operations.
func sessionByID(id C.int) (*tcpSession, bool) {
	state.mu.Lock()
	defer state.mu.Unlock()
	session, ok := state.sessions[int(id)]
	return session, ok
}

//export tf_gvisor_tcp_read
func tf_gvisor_tcp_read(id C.int, out unsafe.Pointer, maxLen C.int, timeoutMs C.int) C.int {
	if out == nil || maxLen <= 0 {
		return -1
	}
	session, ok := sessionByID(id)
	if !ok {
		return -2
	}
	_ = session.conn.SetReadDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond))
	// Read directly into the C-owned buffer to avoid an extra allocation on the hot path.
	buf := unsafe.Slice((*byte)(out), int(maxLen))
	n, err := session.conn.Read(buf)
	if err != nil {
		if errors.Is(err, io.EOF) {
			return 0
		}
		if errors.Is(err, net.ErrClosed) {
			return -2
		}
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			return -4
		}
		if errors.Is(err, syscall.ECONNRESET) {
			return -5
		}
		return -3
	}
	return C.int(n)
}

//export tf_gvisor_tcp_write
func tf_gvisor_tcp_write(id C.int, data unsafe.Pointer, length C.int, timeoutMs C.int) C.int {
	if data == nil || length < 0 {
		return -1
	}
	session, ok := sessionByID(id)
	if !ok {
		return -2
	}
	payload := bytesFromPtr(data, length)
	_ = session.conn.SetWriteDeadline(time.Now().Add(time.Duration(timeoutMs) * time.Millisecond))
	n, err := session.conn.Write(payload)
	if err != nil {
		if errors.Is(err, net.ErrClosed) {
			return -2
		}
		if ne, ok := err.(net.Error); ok && ne.Timeout() {
			return -4
		}
		if errors.Is(err, syscall.ECONNRESET) {
			return -5
		}
		return -3
	}
	return C.int(n)
}

//export tf_gvisor_tcp_close
func tf_gvisor_tcp_close(id C.int) {
	state.mu.Lock()
	session := state.sessions[int(id)]
	delete(state.sessions, int(id))
	state.mu.Unlock()
	if session != nil {
		_ = session.conn.Close()
	}
}

//export tf_gvisor_stats
func tf_gvisor_stats(out unsafe.Pointer, count C.int) C.int {
	if out == nil || count < 16 {
		return -1
	}
	state.mu.Lock()
	active := len(state.sessions)
	pending := len(state.pendingOpens)
	running := state.running
	queued := 0
	if state.linkEP != nil {
		queued = state.linkEP.NumQueued()
	}
	state.mu.Unlock()
	stats := unsafe.Slice((*C.int)(out), int(count))
	// Positional ABI: keep this order aligned with Kotlin formatGvisorStats and native callers.
	stats[0] = boolInt(running)
	stats[1] = C.int(active)
	stats[2] = C.int(queued)
	stats[3] = C.int(state.openAttempts.Load())
	stats[4] = C.int(state.openOK.Load())
	stats[5] = C.int(state.openFailed.Load())
	stats[6] = C.int(state.outboundPackets.Load())
	stats[7] = C.int(state.inboundPackets.Load())
	stats[8] = C.int(pending)
	stats[9] = C.int(state.openTimeouts.Load())
	stats[10] = C.int(state.openImmediate.Load())
	stats[11] = C.int(state.openResets.Load())
	stats[12] = C.int(state.openInternal.Load())
	stats[13] = C.int(state.openCanceled.Load())
	stats[14] = C.int(state.openSynOut.Load())
	stats[15] = C.int(state.openSynAckIn.Load())
	if count > 16 {
		stats[16] = C.int(state.openRstIn.Load())
		return 17
	}
	return 16
}

//export tf_gvisor_last_open_diagnostics
func tf_gvisor_last_open_diagnostics(out unsafe.Pointer, count C.int) C.int {
	if out == nil || count <= 0 {
		return -1
	}
	state.mu.Lock()
	diag := state.lastOpenDiagnostics
	state.mu.Unlock()
	buf := unsafe.Slice((*byte)(out), int(count))
	if len(buf) == 0 {
		return -1
	}
	n := copy(buf, []byte(diag))
	if n >= len(buf) {
		n = len(buf) - 1
	}
	buf[n] = 0
	return C.int(n)
}

//export tf_gvisor_open_diagnostics
func tf_gvisor_open_diagnostics(openID C.int, out unsafe.Pointer, count C.int) C.int {
	if openID <= 0 || out == nil || count <= 0 {
		return -1
	}
	state.mu.Lock()
	diag := state.completedOpenDiags[int(openID)]
	state.mu.Unlock()
	buf := unsafe.Slice((*byte)(out), int(count))
	if len(buf) == 0 {
		return -1
	}
	n := copy(buf, []byte(diag))
	if n >= len(buf) {
		n = len(buf) - 1
	}
	buf[n] = 0
	return C.int(n)
}

func parseIPv4TCPPacket(data []byte) (ipv4TCPPacket, bool) {
	if len(data) < 40 || data[0]>>4 != 4 {
		return ipv4TCPPacket{}, false
	}
	ihl := int(data[0]&0x0f) * 4
	if ihl < 20 || ihl+20 > len(data) {
		return ipv4TCPPacket{}, false
	}
	totalLen := int(data[2])<<8 | int(data[3])
	if totalLen < ihl+20 || totalLen > len(data) {
		return ipv4TCPPacket{}, false
	}
	if data[9] != tcpProtocol {
		return ipv4TCPPacket{}, false
	}
	tcpHeaderLen := int(data[ihl+12]>>4) * 4
	if tcpHeaderLen < 20 || ihl+tcpHeaderLen > totalLen {
		return ipv4TCPPacket{}, false
	}
	var pkt ipv4TCPPacket
	copy(pkt.flow.src[:], data[12:16])
	copy(pkt.flow.dst[:], data[16:20])
	pkt.flow.sport = uint16(data[ihl])<<8 | uint16(data[ihl+1])
	pkt.flow.dport = uint16(data[ihl+2])<<8 | uint16(data[ihl+3])
	pkt.flags = data[ihl+13]
	return pkt, true
}

func noteOutboundPacket(data []byte) {
	pkt, ok := parseIPv4TCPPacket(data)
	if !ok || pkt.flags&tcpFlagSyn == 0 || pkt.flags&tcpFlagAck != 0 {
		return
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	diag := findPendingOpenForOutboundSynLocked(pkt)
	if diag == nil {
		return
	}
	diag.localPort = pkt.flow.sport
	diag.synCount++
	diag.lastObserved = time.Now()
	if state.flowToOpenAttemptID == nil {
		state.flowToOpenAttemptID = make(map[tcpFlowKey]int)
	}
	// Map the final 4-tuple after gVisor chooses a local port.
	state.flowToOpenAttemptID[pkt.flow] = diag.openID
	state.openSynOut.Add(1)
}

func findPendingOpenForOutboundSynLocked(pkt ipv4TCPPacket) *tcpOpenDiag {
	if attempt, ok := state.flowToOpenAttemptID[pkt.flow]; ok {
		return state.pendingOpens[attempt]
	}
	var selected *tcpOpenDiag
	for _, diag := range state.pendingOpens {
		if diag.remotePort != pkt.flow.dport || diag.remoteIPv4 != pkt.flow.dst {
			continue
		}
		if diag.localPort != 0 && diag.localPort != pkt.flow.sport {
			continue
		}
		if selected == nil || diag.startedAt.Before(selected.startedAt) {
			selected = diag
		}
	}
	return selected
}

func noteInboundPacket(data []byte) {
	pkt, ok := parseIPv4TCPPacket(data)
	if !ok {
		return
	}
	reverse := tcpFlowKey{
		src:   pkt.flow.dst,
		dst:   pkt.flow.src,
		sport: pkt.flow.dport,
		dport: pkt.flow.sport,
	}
	state.mu.Lock()
	defer state.mu.Unlock()
	// Reverse lookup matches remote replies to the outbound SYN tuple recorded above.
	attempt := state.flowToOpenAttemptID[reverse]
	diag := state.pendingOpens[attempt]
	if diag == nil {
		return
	}
	diag.lastObserved = time.Now()
	if pkt.flags&tcpFlagSyn != 0 && pkt.flags&tcpFlagAck != 0 {
		diag.synAckCount++
		state.openSynAckIn.Add(1)
	}
	if pkt.flags&tcpFlagRst != 0 {
		diag.rstCount++
		state.openRstIn.Add(1)
	}
	if pkt.flags&tcpFlagFin != 0 {
		diag.finCount++
	}
}

func formatOpenDiagLocked(diag *tcpOpenDiag, sessionID int) string {
	return fmt.Sprintf(
		"openDiag openId=%d sessionId=%d code=%d reason=%s remotePort=%d localPort=%d syn=%d synAck=%d rst=%d fin=%d elapsedMs=%d lastPacketAgeMs=%d",
		diag.openID,
		sessionID,
		diag.failureCode,
		diag.failureReason,
		diag.remotePort,
		diag.localPort,
		diag.synCount,
		diag.synAckCount,
		diag.rstCount,
		diag.finCount,
		time.Since(diag.startedAt).Milliseconds(),
		lastPacketAgeMs(diag),
	)
}

func lastPacketAgeMs(diag *tcpOpenDiag) int64 {
	if diag.lastObserved.IsZero() {
		return -1
	}
	return time.Since(diag.lastObserved).Milliseconds()
}

func boolInt(v bool) C.int {
	if v {
		return 1
	}
	return 0
}

func main() {
}
