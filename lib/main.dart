import 'package:flutter/widgets.dart';

import 'app/app.dart';

export 'app/app.dart' show TunnelForgeApp;

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  installGlobalErrorHooks();
  runApp(const TunnelForgeApp());
}
