package main

import (
	"context"
	"strings"
	"testing"
	"time"
)

func TestParseIPv4TCPPacketExtractsFlowAndFlags(t *testing.T) {
	packet := buildTCPPacket([4]byte{10, 0, 0, 2}, [4]byte{203, 0, 113, 10}, 42000, 443, tcpFlagSyn)

	parsed, ok := parseIPv4TCPPacket(packet)
	if !ok {
		t.Fatal("parseIPv4TCPPacket returned false")
	}
	if parsed.flow.src != [4]byte{10, 0, 0, 2} || parsed.flow.dst != [4]byte{203, 0, 113, 10} {
		t.Fatalf("unexpected flow addresses: %+v", parsed.flow)
	}
	if parsed.flow.sport != 42000 || parsed.flow.dport != 443 {
		t.Fatalf("unexpected ports: %+v", parsed.flow)
	}
	if parsed.flags != tcpFlagSyn {
		t.Fatalf("unexpected flags: 0x%x", parsed.flags)
	}
}

func TestParseIPv4TCPPacketIgnoresMalformedAndNonTCP(t *testing.T) {
	if _, ok := parseIPv4TCPPacket([]byte{0x45}); ok {
		t.Fatal("short packet parsed")
	}
	packet := buildTCPPacket([4]byte{10, 0, 0, 2}, [4]byte{203, 0, 113, 10}, 42000, 443, tcpFlagSyn)
	packet[9] = 17
	if _, ok := parseIPv4TCPPacket(packet); ok {
		t.Fatal("non-TCP packet parsed")
	}
	packet[9] = tcpProtocol
	packet[3] = 39
	if _, ok := parseIPv4TCPPacket(packet); ok {
		t.Fatal("malformed total length parsed")
	}
}

func TestOpenDiagnosticsMatchSynSynAckAndRst(t *testing.T) {
	resetGvisorDiagnosticsForTest()
	state.mu.Lock()
	attempt := beginOpenLocked(1, []byte{203, 0, 113, 10}, 443)
	state.mu.Unlock()

	outbound := buildTCPPacket([4]byte{10, 0, 0, 2}, [4]byte{203, 0, 113, 10}, 42000, 443, tcpFlagSyn)
	noteOutboundPacket(outbound)
	noteOutboundPacket(outbound)
	inboundSynAck := buildTCPPacket([4]byte{203, 0, 113, 10}, [4]byte{10, 0, 0, 2}, 443, 42000, tcpFlagSyn|tcpFlagAck)
	inboundRst := buildTCPPacket([4]byte{203, 0, 113, 10}, [4]byte{10, 0, 0, 2}, 443, 42000, tcpFlagRst|tcpFlagAck)
	noteInboundPacket(inboundSynAck)
	noteInboundPacket(inboundRst)

	state.mu.Lock()
	diag := state.pendingOpens[attempt]
	state.mu.Unlock()
	if diag == nil {
		t.Fatal("missing pending open diagnostics")
	}
	if diag.localPort != 42000 || diag.synCount != 2 || diag.synAckCount != 1 || diag.rstCount != 1 {
		t.Fatalf("unexpected diagnostics: %+v", diag)
	}
	if state.openSynOut.Load() != 2 || state.openSynAckIn.Load() != 1 || state.openRstIn.Load() != 1 {
		t.Fatalf(
			"unexpected counters syn=%d synAck=%d rst=%d",
			state.openSynOut.Load(),
			state.openSynAckIn.Load(),
			state.openRstIn.Load(),
		)
	}
}

func TestFinishOpenFailureStoresDiagnosticSummary(t *testing.T) {
	resetGvisorDiagnosticsForTest()
	state.mu.Lock()
	attempt := beginOpenLocked(1, []byte{203, 0, 113, 10}, 443)
	state.mu.Unlock()

	noteOutboundPacket(buildTCPPacket([4]byte{10, 0, 0, 2}, [4]byte{203, 0, 113, 10}, 42000, 443, tcpFlagSyn))
	finishOpenFailure(attempt, -4, "timeout")

	state.mu.Lock()
	summary := state.completedOpenDiags[attempt]
	_, pending := state.pendingOpens[attempt]
	state.mu.Unlock()
	if pending {
		t.Fatal("pending open was not removed after failure")
	}
	if summary == "" {
		t.Fatal("last open diagnostics was empty")
	}
	for _, want := range []string{"openId=1", "sessionId=0", "code=-4", "reason=timeout", "remotePort=443", "localPort=42000", "syn=1"} {
		if !strings.Contains(summary, want) {
			t.Fatalf("diagnostics %q missing %q", summary, want)
		}
	}
}

func TestCancelPendingOpenInvokesRegisteredCancel(t *testing.T) {
	resetGvisorDiagnosticsForTest()
	called := false
	state.mu.Lock()
	state.pendingOpenCancels[7] = func() { called = true }
	state.mu.Unlock()

	if got := tf_gvisor_tcp_cancel_open(7); got != 0 {
		t.Fatalf("cancel result=%d, want 0", got)
	}
	if !called {
		t.Fatal("registered cancel was not called")
	}
}

func TestCompletedDiagnosticsArePerOpen(t *testing.T) {
	resetGvisorDiagnosticsForTest()
	state.mu.Lock()
	first := beginOpenLocked(101, []byte{203, 0, 113, 10}, 443)
	second := beginOpenLocked(202, []byte{198, 51, 100, 20}, 8443)
	state.mu.Unlock()

	finishOpenFailure(first, -4, "timeout")
	finishOpenFailure(second, -7, "client-aborted")

	state.mu.Lock()
	firstDiag := state.completedOpenDiags[first]
	secondDiag := state.completedOpenDiags[second]
	state.mu.Unlock()
	if !strings.Contains(firstDiag, "openId=101") || !strings.Contains(firstDiag, "code=-4") || !strings.Contains(firstDiag, "remotePort=443") {
		t.Fatalf("unexpected first diagnostics: %q", firstDiag)
	}
	if !strings.Contains(secondDiag, "openId=202") || !strings.Contains(secondDiag, "code=-7") || !strings.Contains(secondDiag, "remotePort=8443") {
		t.Fatalf("unexpected second diagnostics: %q", secondDiag)
	}
}

func resetGvisorDiagnosticsForTest() {
	state.mu.Lock()
	defer state.mu.Unlock()
	state.pendingOpens = make(map[int]*tcpOpenDiag)
	state.pendingOpenCancels = make(map[int]context.CancelFunc)
	state.flowToOpenAttemptID = make(map[tcpFlowKey]int)
	state.completedOpenDiags = make(map[int]string)
	state.completedOpenOrder = nil
	state.nextOpenAttemptID = 1
	state.lastOpenDiagnostics = ""
	state.openCanceled.Store(0)
	state.openSynOut.Store(0)
	state.openSynAckIn.Store(0)
	state.openRstIn.Store(0)
}

func buildTCPPacket(src, dst [4]byte, sport, dport uint16, flags byte) []byte {
	packet := make([]byte, 40)
	packet[0] = 0x45
	packet[2] = 0
	packet[3] = 40
	packet[8] = 64
	packet[9] = tcpProtocol
	copy(packet[12:16], src[:])
	copy(packet[16:20], dst[:])
	packet[20] = byte(sport >> 8)
	packet[21] = byte(sport)
	packet[22] = byte(dport >> 8)
	packet[23] = byte(dport)
	packet[32] = 0x50
	packet[33] = flags
	return packet
}

func TestLastPacketAgeIsMinusOneWhenNoPacketsObserved(t *testing.T) {
	diag := &tcpOpenDiag{startedAt: time.Now()}
	if got := lastPacketAgeMs(diag); got != -1 {
		t.Fatalf("lastPacketAgeMs=%d, want -1", got)
	}
}
