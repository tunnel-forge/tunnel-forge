import 'dart:async';

import 'package:flutter/services.dart';

import 'profile_transfer.dart';
import 'profile_transfer_contract.dart';

class ProfileTransferBridge {
  ProfileTransferBridge({MethodChannel? channel})
    : _channel =
          channel ?? const MethodChannel(ProfileTransferContract.channel);

  final MethodChannel _channel;
  final StreamController<IncomingProfileTransfer> _incoming =
      StreamController<IncomingProfileTransfer>.broadcast();
  bool _started = false;

  Stream<IncomingProfileTransfer> get incomingTransfers => _incoming.stream;

  Future<List<IncomingProfileTransfer>> start() async {
    if (_started) return const [];
    _started = true;
    _channel.setMethodCallHandler(_onMethodCall);
    try {
      final raw = await _channel.invokeListMethod<Object?>(
        ProfileTransferContract.consumePendingTransfers,
      );
      final pending = <IncomingProfileTransfer>[];
      for (final entry in raw ?? const <Object?>[]) {
        final transfer = IncomingProfileTransfer.tryFromMap(entry);
        if (transfer != null) pending.add(transfer);
      }
      return pending;
    } on MissingPluginException {
      return const [];
    } on PlatformException {
      return const [];
    }
  }

  Future<void> dispose() async {
    _channel.setMethodCallHandler(null);
    await _incoming.close();
  }

  Future<Object?> _onMethodCall(MethodCall call) async {
    if (call.method != ProfileTransferContract.onIncomingTransfer) return null;
    final transfer = IncomingProfileTransfer.tryFromMap(call.arguments);
    if (transfer != null && !_incoming.isClosed) {
      _incoming.add(transfer);
    }
    return null;
  }
}
