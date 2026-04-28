import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum AppLanguage {
  english('en', 'English', 'English'),
  persian('fa', 'Persian', 'فارسی');

  const AppLanguage(this.code, this.englishName, this.nativeName);

  final String code;
  final String englishName;
  final String nativeName;

  Locale get locale => Locale(code);

  static AppLanguage fromCode(String? code) {
    return switch (code) {
      'fa' => AppLanguage.persian,
      _ => AppLanguage.english,
    };
  }
}

class AppLanguageController extends ChangeNotifier {
  AppLanguageController() {
    _load();
  }

  static const String _prefsKey = 'app_language_v1';

  AppLanguage _language = AppLanguage.english;

  AppLanguage get language => _language;

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final next = AppLanguage.fromCode(prefs.getString(_prefsKey));
    if (next == _language) return;
    _language = next;
    AppText.setLanguage(next);
    notifyListeners();
  }

  Future<void> setLanguage(AppLanguage language) async {
    if (language == _language) return;
    _language = language;
    AppText.setLanguage(language);
    notifyListeners();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefsKey, language.code);
  }

  static AppLanguageController of(BuildContext context) {
    final scope = context
        .dependOnInheritedWidgetOfExactType<AppLanguageScope>();
    assert(scope != null, 'No AppLanguageScope found in context.');
    return scope!.controller;
  }
}

class AppLanguageScope extends InheritedNotifier<AppLanguageController> {
  const AppLanguageScope({
    super.key,
    required AppLanguageController controller,
    required super.child,
  }) : super(notifier: controller);

  AppLanguageController get controller => notifier!;
}

class AppLocalizations {
  const AppLocalizations(this.language);

  final AppLanguage language;

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  static const List<Locale> supportedLocales = [Locale('en'), Locale('fa')];

  static AppLocalizations of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations) ??
        AppText.current;
  }
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  bool isSupported(Locale locale) {
    return AppLocalizations.supportedLocales.any(
      (supported) => supported.languageCode == locale.languageCode,
    );
  }

  @override
  Future<AppLocalizations> load(Locale locale) {
    final language = AppLanguage.fromCode(locale.languageCode);
    AppText.setLanguage(language);
    return SynchronousFuture<AppLocalizations>(AppText.current);
  }

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

class AppText {
  AppText._();

  static AppLocalizations current = const AppLocalizations(AppLanguage.english);

  static void setLanguage(AppLanguage language) {
    current = AppLocalizations(language);
  }

  static bool get isFa => current.language == AppLanguage.persian;
  static String pick(String en, String fa) => isFa ? fa : en;
}

extension AppStrings on AppLocalizations {
  bool get isRtl => language == AppLanguage.persian;
  String get appTitle => 'TunnelForge';
  String get vpn => AppText.pick('VPN', 'وی‌پی‌ان');
  String get logs => AppText.pick('Logs', 'گزارش‌ها');
  String get settings => AppText.pick('Settings', 'تنظیمات');
  String get cancel => AppText.pick('Cancel', 'لغو');
  String get delete => AppText.pick('Delete', 'حذف');
  String get close => AppText.pick('Close', 'بستن');
  String get save => AppText.pick('Save', 'ذخیره');
  String get done => AppText.pick('Done', 'انجام شد');
  String get continueLabel => AppText.pick('Continue', 'ادامه');
  String get confirm => AppText.pick('Confirm', 'تایید');
  String confirmInSeconds(int seconds) =>
      AppText.pick('Confirm in ${seconds}s', 'تایید تا $seconds ثانیه');
  String get dismiss => AppText.pick('Dismiss', 'بستن');
  String get languageLabel => AppText.pick('Language', 'زبان');
  String get chooseLanguage =>
      AppText.pick('Choose your language', 'انتخاب زبان');
  String get languageSubtitle => AppText.pick(
    'Set the language for every screen before you continue.',
    'زبان برنامه را انتخاب کنید. همه بخش‌ها با همین زبان نمایش داده می‌شوند.',
  );
  String get languageBody => AppText.pick(
    'This choice updates the app immediately. You can change it later from Settings.',
    'بعدا هم می‌توانید از تنظیمات، زبان را تغییر دهید.',
  );
  String get english => 'English';
  String get persian => 'فارسی';
  String get notice => AppText.pick('Notice', 'اطلاعیه');
  String get onboardingIntroSubtitle => AppText.pick(
    'Legacy L2TP support for modern Android, with setup and connection management in one place.',
    'این نرم‌افزار کمک می‌کند اتصال‌های L2TP را روی اندرویدهای جدید راحت‌تر بسازید و مدیریت کنید.',
  );
  String get onboardingIntroP1 => AppText.pick(
    'Create and organize profiles, switch between connections, and keep the essentials close at hand.',
    'پروفایل‌های مختلف را بسازید، نام‌گذاری کنید و هر وقت لازم بود سریع بین آن‌ها جابه‌جا شوید.',
  );
  String get onboardingIntroP2 => AppText.pick(
    'Designed for environments that still depend on L2TP, without forcing an outdated setup experience.',
    'اگر شبکه یا سرویس شما هنوز به L2TP نیاز دارد، لازم نیست با تنظیمات پیچیده و پراکنده درگیر شوید.',
  );
  String get onboardingIntroP3 => AppText.pick(
    'A focused way to manage legacy VPN access on current Android devices.',
    'همه چیز ساده نگه داشته شده تا فقط روی ساختن و وصل شدن تمرکز کنید.',
  );
  String get securityNoticeTitle =>
      AppText.pick('Review security notice', 'نکته امنیتی');
  String get securityNoticeSubtitle => AppText.pick(
    'L2TP/IPsec is provided for legacy compatibility, is not considered secure by modern standards, and should only be used when stronger protocols are not available.',
    'L2TP/IPsec برای سازگاری با سرویس‌های قدیمی پشتیبانی می‌شود. این روش، انتخاب امن و مدرن امروز نیست.',
  );
  String get securityReadOnlySubtitle => AppText.pick(
    'L2TP should only be used when no stronger option is available.',
    'از L2TP فقط وقتی استفاده کنید که گزینه بهتری در دسترس نیست.',
  );
  String get securityP1 => AppText.pick(
    'This protocol remains available to support older infrastructure, not as a recommended default for new deployments.',
    'بعضی شبکه‌ها هنوز فقط با L2TP کار می‌کنند. پشتیبانی از آن برای همین شرایط در برنامه قرار گرفته است.',
  );
  String get securityP2 => AppText.pick(
    'It should be treated as easier to decrypt than modern VPN protocols. If your network supports WireGuard, OpenVPN, or another modern alternative, that should be the preferred choice.',
    'اگر برای همان سرویس WireGuard، OpenVPN یا گزینه جدیدتری دارید، بهتر است از همان استفاده کنید.',
  );
  String get securityReadOnlyP1 => AppText.pick(
    'L2TP/IPsec no longer meets modern security expectations for sensitive traffic.',
    'L2TP/IPsec برای داده‌های حساس انتخاب مناسبی نیست.',
  );
  String get securityReadOnlyP2 => AppText.pick(
    'Use this option only when compatibility is required and no stronger protocol is available.',
    'فقط زمانی از آن استفاده کنید که سرویس شما گزینه بهتری ارائه نمی‌دهد.',
  );
  String get introCheckbox =>
      AppText.pick('Keep VPN profiles organized.', 'پروفایل‌ها مرتب می‌مانند.');
  String get riskCheckbox => AppText.pick(
    'I understand the L2TP risk.',
    'محدودیت امنیتی L2TP را می‌دانم.',
  );
  String get compatibilityOnly => AppText.pick(
    'Use only when compatibility requires it',
    'فقط برای سرویس‌های قدیمی',
  );
  String get compatibilityBody => AppText.pick(
    'Proceed only if this connection depends on L2TP/IPsec, you understand that it is not a secure modern protocol, and no stronger option is available for the same service.',
    'اگر سرویس شما فقط با L2TP/IPsec وصل می‌شود، می‌توانید ادامه دهید. در غیر این صورت، استفاده از یک پروتکل جدیدتر انتخاب بهتری است.',
  );

  String get appearance => AppText.pick('Appearance', 'ظاهر');
  String get system => AppText.pick('System', 'سیستم');
  String get light => AppText.pick('Light', 'روشن');
  String get dark => AppText.pick('Dark', 'تاریک');
  String get connectionMode => AppText.pick('Connection mode', 'حالت اتصال');
  String get localProxy => AppText.pick('Local proxy', 'پروکسی محلی');
  String get splitTunneling =>
      AppText.pick('Split tunneling', 'تونل‌سازی تفکیکی');
  String get enableSplitTunneling =>
      AppText.pick('Enable split tunneling', 'فعال کردن تونل‌سازی تفکیکی');
  String splitTunnelApply(String mode) => AppText.pick(
    'Apply the selected app list using $mode mode.',
    'فهرست برنامه‌های انتخاب‌شده با حالت $mode اعمال می‌شود.',
  );
  String get splitTunnelRouteAll => AppText.pick(
    'Route all apps through the VPN and keep both app lists saved for later.',
    'همه برنامه‌ها از وی‌پی‌ان عبور می‌کنند و هر دو فهرست برنامه برای بعد ذخیره می‌شود.',
  );
  String get mode => AppText.pick('Mode', 'حالت');
  String get inclusive => AppText.pick('Inclusive', 'شامل');
  String get exclusive => AppText.pick('Exclusive', 'مستثنی');
  String get inclusiveDescription => AppText.pick(
    'Inclusive routes only the apps you choose through the VPN.',
    'حالت شامل فقط برنامه‌هایی را که انتخاب می‌کنید از وی‌پی‌ان عبور می‌دهد.',
  );
  String get exclusiveDescription => AppText.pick(
    'Exclusive routes all apps through the VPN except the apps you choose.',
    'حالت مستثنی همه برنامه‌ها را به جز برنامه‌های انتخابی شما از وی‌پی‌ان عبور می‌دهد.',
  );
  String get selectAppsUsingVpn =>
      AppText.pick('Select apps using VPN', 'انتخاب برنامه‌های داخل وی‌پی‌ان');
  String get selectAppsOutsideVpn => AppText.pick(
    'Select apps outside VPN',
    'انتخاب برنامه‌های خارج از وی‌پی‌ان',
  );
  String get noAppsUseVpn => AppText.pick(
    'No apps selected. Choose at least one app to use the VPN.',
    'برنامه‌ای انتخاب نشده است. حداقل یک برنامه را برای استفاده از وی‌پی‌ان انتخاب کنید.',
  );
  String appsWillUseVpn(int count) => AppText.pick(
    '$count app${count == 1 ? '' : 's'} will use the VPN.',
    '$count برنامه از وی‌پی‌ان استفاده می‌کند.',
  );
  String get noAppsBypassVpn => AppText.pick(
    'No apps selected. All apps will continue using the VPN.',
    'برنامه‌ای انتخاب نشده است. همه برنامه‌ها همچنان از وی‌پی‌ان استفاده می‌کنند.',
  );
  String appsWillBypassVpn(int count) => AppText.pick(
    '$count app${count == 1 ? '' : 's'} will bypass the VPN.',
    '$count برنامه وی‌پی‌ان را دور می‌زند.',
  );
  String get httpPort => AppText.pick('HTTP port', 'درگاه HTTP');
  String get socksPort => AppText.pick('SOCKS5 port', 'درگاه SOCKS5');
  String get allowLan =>
      AppText.pick('Allow connections from LAN', 'اجازه اتصال از شبکه محلی');
  String get allowLanOn => AppText.pick(
    'Detect a shareable local IPv4 and expose both listeners to devices on the same Wi-Fi or hotspot when available.',
    'یک IPv4 محلی قابل اشتراک را شناسایی می‌کند و در صورت امکان هر دو شنونده را برای دستگاه‌های همان وای‌فای یا هات‌اسپات در دسترس می‌گذارد.',
  );
  String get allowLanOff => AppText.pick(
    'Keep both listeners on this device only.',
    'هر دو شنونده فقط روی همین دستگاه می‌مانند.',
  );
  String get proxyPortsDifferent => AppText.pick(
    'HTTP and SOCKS5 ports must be different before connecting.',
    'درگاه‌های HTTP و SOCKS5 قبل از اتصال باید متفاوت باشند.',
  );
  String get proxyEndpoints =>
      AppText.pick('Proxy endpoints', 'نقطه‌های پایانی پروکسی');
  String get lanClientsCanUse => AppText.pick(
    'LAN clients on the same Wi-Fi or hotspot can use this address.',
    'دستگاه‌های همان وای‌فای یا هات‌اسپات می‌توانند از این نشانی استفاده کنند.',
  );
  String get lanSharingEnabled => AppText.pick(
    'LAN sharing is enabled for this session.',
    'اشتراک‌گذاری شبکه محلی برای این نشست فعال است.',
  );
  String get connectToDetectLan => AppText.pick(
    'Connect to detect the current LAN IP for other devices.',
    'برای تشخیص IP شبکه محلی جهت دستگاه‌های دیگر وصل شوید.',
  );
  String get lanDisabled =>
      AppText.pick('LAN access is disabled.', 'دسترسی شبکه محلی غیرفعال است.');
  String get connectivityCheck =>
      AppText.pick('Connectivity check', 'بررسی اتصال');
  String get statusCheckUrl =>
      AppText.pick('Status check URL', 'نشانی بررسی وضعیت');
  String get statusCheckHelp => AppText.pick(
    'Used for the status check after you connect. Tap the badge anytime to refresh.',
    'پس از اتصال برای بررسی وضعیت استفاده می‌شود. هر زمان برای تازه‌سازی روی نشان بزنید.',
  );
  String get connectivityUrl => AppText.pick('Connectivity URL', 'نشانی اتصال');
  String get connectivityTimeout =>
      AppText.pick('Connectivity timeout (ms)', 'مهلت اتصال (میلی‌ثانیه)');
  String get timeoutHelp => AppText.pick(
    'Maximum time to wait before marking the check unreachable.',
    'بیشترین زمان انتظار پیش از ناموفق دانستن بررسی.',
  );
  String get l2tpSecurityNotice =>
      AppText.pick('L2TP security notice', 'اطلاعیه امنیتی L2TP');
  String get reviewL2tpNotice => AppText.pick(
    'Review the L2TP/IPsec compatibility notice.',
    'اطلاعیه سازگاری L2TP/IPsec را مرور کنید.',
  );
  String get updates => AppText.pick('Updates', 'به‌روزرسانی‌ها');
  String get refresh => AppText.pick('Refresh', 'تازه‌سازی');
  String get openReleasePage =>
      AppText.pick('Open release page', 'باز کردن صفحه انتشار');
  String get versionUnavailable =>
      AppText.pick('Version unavailable', 'نسخه در دسترس نیست');
  String version(String value) => AppText.pick('Version $value', 'نسخه $value');
  String get about => AppText.pick('About', 'درباره');
  String get aboutText => AppText.pick(
    'TunnelForge keeps L2TP/IPsec usable on modern Android, with profile management, local proxy access, per-app routing, and clear connection logs in one place.',
    'TunnelForge برای زمانی است که هنوز به اتصال L2TP/IPsec نیاز دارید. پروفایل‌ها، پروکسی محلی، مسیریابی برنامه‌ها و گزارش اتصال را یکجا و بدون شلوغی مدیریت می‌کند.',
  );
  String get telegramTitle => AppText.pick('Telegram', 'تلگرام');
  String get githubTitle => AppText.pick('GitHub', 'گیت‌هاب');
  String get checkingUpdates =>
      AppText.pick('Checking for updates', 'در حال بررسی به‌روزرسانی');
  String get updateAvailable =>
      AppText.pick('Update available', 'به‌روزرسانی در دسترس است');
  String get buildNewer =>
      AppText.pick('Installed build is newer', 'نسخه نصب‌شده جدیدتر است');
  String get updateCheckUnavailable => AppText.pick(
    'Update check unavailable',
    'بررسی به‌روزرسانی در دسترس نیست',
  );
  String get appUpToDate =>
      AppText.pick('App is up to date', 'برنامه به‌روز است');
  String get checkForUpdates =>
      AppText.pick('Check for updates', 'بررسی به‌روزرسانی');
  String get lookingUpRelease => AppText.pick(
    'Looking up the latest published GitHub release.',
    'در حال دریافت آخرین انتشار GitHub.',
  );
  String latestReleaseRunning(String latest, String installed) => AppText.pick(
    'Latest release: $latest. You are running $installed.',
    'آخرین انتشار: $latest. نسخه نصب‌شده شما $installed است.',
  );
  String latestPublished(String latest) => AppText.pick(
    'You are running the latest published release: $latest.',
    'شما آخرین انتشار منتشرشده را اجرا می‌کنید: $latest.',
  );
  String buildNewerThanLatest(String latest) => AppText.pick(
    'This build is newer than the latest GitHub release ($latest).',
    'این نسخه از آخرین انتشار GitHub جدیدتر است ($latest).',
  );
  String latestCannotCompare(String latest, String reason) => AppText.pick(
    'Latest release: $latest. $reason',
    'آخرین انتشار: $latest. $reason',
  );
  String get unknownBuild => AppText.pick('an unknown build', 'یک نسخه نامشخص');
  String get couldNotCheckUpdates => AppText.pick(
    'Couldn\'t check for updates right now.',
    'اکنون امکان بررسی به‌روزرسانی نیست.',
  );
  String updateCheckError(String message) {
    if (!isRtl) return message;
    if (message.startsWith('GitHub Releases returned HTTP ')) {
      final status = message
          .replaceFirst('GitHub Releases returned HTTP ', '')
          .replaceFirst('.', '');
      return 'سرور GitHub پاسخ ناموفق برگرداند ($status).';
    }
    return switch (message) {
      'Network error while contacting GitHub Releases.' =>
        'اتصال به GitHub برای بررسی به‌روزرسانی برقرار نشد.',
      'GitHub Releases request timed out.' =>
        'بررسی به‌روزرسانی بیش از حد طول کشید.',
      'Secure connection to GitHub Releases failed.' =>
        'اتصال امن به GitHub برقرار نشد.',
      'GitHub Releases returned malformed data.' =>
        'پاسخ GitHub قابل خواندن نبود.',
      'GitHub Releases returned no usable releases.' =>
        'انتشار قابل استفاده‌ای در GitHub پیدا نشد.',
      'Unexpected error while checking GitHub Releases.' =>
        'هنگام بررسی به‌روزرسانی خطای غیرمنتظره‌ای رخ داد.',
      'Installed version unavailable, so this build cannot be compared.' =>
        installedVersionUnavailableCompare,
      _ => message,
    };
  }

  String get checkGithubReleases => AppText.pick(
    'Check GitHub releases to see whether a newer build is available.',
    'انتشارهای GitHub را بررسی کنید تا مشخص شود نسخه جدیدتری هست یا نه.',
  );
  String get installedVersionUnavailableCompare => AppText.pick(
    'Installed version unavailable, so this build cannot be compared.',
    'نسخه نصب‌شده در دسترس نیست؛ بنابراین این نسخه قابل مقایسه نیست.',
  );

  String get activeProfile => AppText.pick('Active profile', 'پروفایل فعال');
  String get noSavedProfile =>
      AppText.pick('No saved profile', 'پروفایل ذخیره‌شده‌ای نیست');
  String get quickConnect => AppText.pick('Quick connect', 'اتصال سریع');
  String get createFirstProfile =>
      AppText.pick('Create your first profile', 'اولین پروفایل خود را بسازید');
  String get selectSavedProfile => AppText.pick(
    'Select a saved profile',
    'یک پروفایل ذخیره‌شده انتخاب کنید',
  );
  String get selectProfile => AppText.pick('Select profile', 'انتخاب پروفایل');
  String get ready => AppText.pick('Ready', 'آماده');
  String get connected => AppText.pick('Connected', 'متصل');
  String get proxyReady => AppText.pick('Proxy ready', 'پروکسی آماده است');
  String get working => AppText.pick('Working...', 'در حال کار...');
  String get connectingTapCancel => AppText.pick(
    'Connecting... tap to cancel',
    'در حال اتصال... برای لغو بزنید',
  );
  String get disconnecting =>
      AppText.pick('Disconnecting...', 'در حال قطع اتصال...');
  String get canceling => AppText.pick('Canceling...', 'در حال لغو...');
  String get tapToCheck => AppText.pick('Tap to check', 'برای بررسی بزنید');
  String get checking => AppText.pick('Checking...', 'در حال بررسی...');
  String get unreachable => AppText.pick('Unreachable', 'غیرقابل دسترس');
  String get logLevel => AppText.pick('Log level', 'سطح گزارش');
  String get wordWrapOff => AppText.pick(
    'Turn off word wrap (wide lines scroll sideways)',
    'خاموش کردن شکست خط',
  );
  String get wordWrapOn =>
      AppText.pick('Turn on word wrap', 'روشن کردن شکست خط');
  String get copyVisible => AppText.pick('Copy visible', 'کپی موارد نمایان');
  String get shareDebugLogs =>
      AppText.pick('Share debug logs', 'اشتراک‌گذاری گزارش‌های دیباگ');
  String get clear => AppText.pick('Clear', 'پاک کردن');
  String get noLogsMatch =>
      AppText.pick('No logs match this level', 'گزارشی با این سطح نیست');
  String get noActivityYet =>
      AppText.pick('No activity yet', 'هنوز فعالیتی نیست');
  String get differentLogLevel => AppText.pick(
    'Try a different log level to see more entries.',
    'برای دیدن موارد بیشتر سطح گزارش دیگری را امتحان کنید.',
  );
  String get logsWillAppear => AppText.pick(
    'Connection events and diagnostics will appear here.',
    'رویدادهای اتصال و عیب‌یابی اینجا نمایش داده می‌شوند.',
  );
  String get latest => AppText.pick('Latest', 'آخرین');

  String get profiles => AppText.pick('Profiles', 'پروفایل‌ها');
  String get addProfile => AppText.pick('Add profile', 'افزودن پروفایل');
  String get createNewProfile =>
      AppText.pick('Create new profile', 'ساخت پروفایل جدید');
  String get importFromFile =>
      AppText.pick('Import from file', 'وارد کردن از فایل');
  String get importFromClipboard =>
      AppText.pick('Import from clipboard', 'وارد کردن از کلیپ‌بورد');
  String get noProfilesYet => AppText.pick(
    'No profiles yet. Tap + to create or import your first profile.',
    'هنوز پروفایلی ندارید. برای ساخت یا وارد کردن اولین پروفایل روی + بزنید.',
  );
  String get profileActions =>
      AppText.pick('Profile actions', 'کنش‌های پروفایل');
  String get copyShareLink =>
      AppText.pick('Copy share link', 'کپی پیوند اشتراک');
  String get exportTfp => AppText.pick('Export .tfp', 'خروجی .tfp');
  String get editProfile => AppText.pick('Edit profile', 'ویرایش پروفایل');
  String get deleteProfile => AppText.pick('Delete profile', 'حذف پروفایل');
  String get deleteProfileQuestion =>
      AppText.pick('Delete profile?', 'پروفایل حذف شود؟');
  String get deleteProfileBody => AppText.pick(
    'This will remove the server and saved credentials from this device.',
    'این کار سرور و اطلاعات ورود ذخیره‌شده را از این دستگاه حذف می‌کند.',
  );
  String get profileSaved => AppText.pick('Profile saved', 'پروفایل ذخیره شد');
  String get profileRefreshTimedOut => AppText.pick(
    'Profile list refresh timed out',
    'زمان تازه‌سازی فهرست پروفایل‌ها تمام شد',
  );
  String get profileImportTimedOut => AppText.pick(
    'Profile import timed out',
    'زمان وارد کردن پروفایل تمام شد',
  );
  String get couldNotReadSelectedFile => AppText.pick(
    'Couldn\'t read the selected file',
    'فایل انتخاب‌شده خوانده نشد',
  );
  String get couldNotImportTfp =>
      AppText.pick('Couldn\'t import .tfp file', 'فایل .tfp وارد نشد');
  String get clipboardEmpty =>
      AppText.pick('Clipboard is empty', 'کلیپ‌بورد خالی است');
  String get clipboardInvalidProfile => AppText.pick(
    'Clipboard does not contain a valid TunnelForge profile',
    'کلیپ‌بورد شامل پروفایل معتبر TunnelForge نیست.',
  );
  String get couldNotImportClipboard => AppText.pick(
    'Couldn\'t import a profile from the clipboard',
    'پروفایل از کلیپ‌بورد وارد نشد',
  );

  String get profileDetails =>
      AppText.pick('Profile details', 'جزئیات پروفایل');
  String get name => AppText.pick('Name', 'نام');
  String get nameHint => AppText.pick('e.g. Office VPN', 'مثلا VPN دفتر');
  String get server => AppText.pick('Server', 'سرور');
  String get serverHint =>
      AppText.pick('Hostname or IP address', 'نام میزبان یا نشانی IP');
  String get username => AppText.pick('Username', 'نام کاربری');
  String get password => AppText.pick('Password', 'رمز عبور');
  String get ipsecPsk => AppText.pick('IPsec PSK', 'کلید مشترک IPsec');
  String get ipsecPskHint => AppText.pick(
    'Leave blank if your server doesn\'t use IPsec',
    'اگر سرور شما از IPsec استفاده نمی‌کند خالی بگذارید',
  );
  String get automatic => AppText.pick('Automatic', 'خودکار');
  String get automaticDnsHelp => AppText.pick(
    'Receive DNS configuration automatically from the VPN server during PPP negotiation.',
    'پیکربندی DNS هنگام مذاکره PPP به صورت خودکار از سرور وی‌پی‌ان دریافت می‌شود.',
  );
  String get dnsServers => AppText.pick('DNS servers', 'سرورهای DNS');
  String get dnsHelp => AppText.pick(
    'DNS 1 is primary. DNS 2 is fallback.',
    'DNS 1 اصلی است. DNS 2 پشتیبان است.',
  );
  String mtuHelper(int min, int max, int defaultValue) => AppText.pick(
    'Range $min-$max. Use $defaultValue unless you need a smaller MTU.',
    'بازه $min تا $max. مگر اینکه به MTU کوچک‌تری نیاز دارید، از $defaultValue استفاده کنید.',
  );

  String get selectAll => AppText.pick('Select all', 'انتخاب همه');
  String get clearAll => AppText.pick('Clear all', 'پاک کردن همه');
  String get searchApps =>
      AppText.pick('Search by name or package', 'جستجو بر اساس نام یا بسته');
  String get noLaunchableApps => AppText.pick(
    'No launchable apps found. If this is wrong, check that the app can query other packages on your Android version.',
    'برنامه قابل اجرا پیدا نشد. اگر این درست نیست، بررسی کنید برنامه روی نسخه اندروید شما اجازه دیدن بسته‌های دیگر را دارد.',
  );
  String get noMatches => AppText.pick('No matches.', 'موردی پیدا نشد.');
  String get appsUsingVpn =>
      AppText.pick('Apps using VPN', 'برنامه‌های داخل وی‌پی‌ان');
  String get appsOutsideVpn =>
      AppText.pick('Apps outside VPN', 'برنامه‌های خارج از وی‌پی‌ان');
  String get onlySelectedAppsVpn => AppText.pick(
    'Only the selected apps will use the VPN.',
    'فقط برنامه‌های انتخاب‌شده از وی‌پی‌ان استفاده می‌کنند.',
  );
  String get selectedAppsBypass => AppText.pick(
    'Selected apps will bypass the VPN and use the normal network.',
    'برنامه‌های انتخاب‌شده وی‌پی‌ان را دور می‌زنند و از شبکه عادی استفاده می‌کنند.',
  );
}
