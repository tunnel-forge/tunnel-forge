import 'package:flutter/services.dart';

import '../domain/app_exit_controller.dart';

class SystemAppExitController implements AppExitController {
  @override
  Future<void> closeApp() => SystemNavigator.pop();
}
