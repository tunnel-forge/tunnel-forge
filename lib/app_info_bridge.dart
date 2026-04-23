import 'package:flutter/services.dart';

import 'app_info_contract.dart';

class AppInfoBridge {
  AppInfoBridge({MethodChannel? channel})
    : _channel = channel ?? const MethodChannel(AppInfoContract.channel);

  final MethodChannel _channel;

  Future<({String? versionName, String? buildNumber})>
  loadInstalledVersion() async {
    final raw = await _channel.invokeMapMethod<String, Object?>(
      AppInfoContract.getInstalledVersion,
    );
    return (
      versionName: raw?[AppInfoContract.argVersionName]?.toString(),
      buildNumber: raw?[AppInfoContract.argBuildNumber]?.toString(),
    );
  }
}
