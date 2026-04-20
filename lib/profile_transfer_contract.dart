/// Host/channel contract for Android profile transfer intents and file handoff.
abstract final class ProfileTransferContract {
  static const String channel =
      'io.github.evokelektrique.tunnelforge/profileTransfer';

  /// Flutter -> host: returns a list of pending transfer/error maps.
  static const String consumePendingTransfers = 'consumePendingTransfers';

  /// Host -> Flutter: pushes one transfer/error map after an external open/share event.
  static const String onIncomingTransfer = 'onIncomingTransfer';

  static const String argType = 'type';
  static const String argData = 'data';
  static const String argMessage = 'message';
  static const String argSource = 'source';

  static const String typeTfpJson = 'tfpJson';
  static const String typeTfUri = 'tfUri';
  static const String typeError = 'error';
}
