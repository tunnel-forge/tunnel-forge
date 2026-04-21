import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/profile_editor_sheet.dart';
import 'package:tunnel_forge/profile_models.dart';
import 'package:tunnel_forge/profile_store.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  Future<ProfileStore> buildStore() async {
    SharedPreferences.setMockInitialValues({});
    final prefs = await SharedPreferences.getInstance();
    await prefs.clear();
    return ProfileStore(
      prefsOverride: prefs,
      secretsOverride: MemorySecretStore(),
    );
  }

  Future<void> pumpHost(
    WidgetTester tester, {
    required ProfileStore store,
    required String profileId,
  }) async {
    await tester.pumpWidget(
      MaterialApp(
        home: Builder(
          builder: (context) => Scaffold(
            body: Center(
              child: FilledButton(
                onPressed: () {
                  ProfileEditorSheet.show(
                    context,
                    profileId: profileId,
                    store: store,
                  );
                },
                child: const Text('Open'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('Open'));
    await tester.pumpAndSettle();
  }

  testWidgets('shows automatic dns section for profile editor', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-1',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      dnsAutomatic: false,
      dns1Host: '1.1.1.1',
      dns1Protocol: DnsProtocol.dnsOverUdp,
      dns2Host: 'dns.example.com',
      dns2Protocol: DnsProtocol.dnsOverTls,
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);

    expect(find.text('Automatic'), findsOneWidget);
    expect(find.text('DNS servers'), findsNWidgets(3));
    expect(find.text('DNS 1 is primary. DNS 2 is fallback.'), findsOneWidget);
    expect(find.text('DNS 1'), findsOneWidget);
    expect(find.text('DNS 2'), findsOneWidget);
    expect(find.text('UDP'), findsOneWidget);
    expect(find.text('TLS'), findsOneWidget);
  });

  testWidgets('shows clearer mtu hint and helper copy', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-mtu',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      dnsAutomatic: true,
      dns1Host: '',
      dns1Protocol: DnsProtocol.dnsOverUdp,
      dns2Host: '',
      dns2Protocol: DnsProtocol.dnsOverUdp,
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);

    final mtuField = tester.widgetList<TextField>(find.byType(TextField)).last;
    expect(mtuField.decoration?.hintText, 'Default ${Profile.defaultVpnMtu}');
    expect(
      mtuField.decoration?.helperText,
      'Range ${Profile.minVpnMtu}-${Profile.maxVpnMtu}. Use ${Profile.defaultVpnMtu} unless you need a smaller MTU.',
    );
  });

  testWidgets('invalid dns entry blocks save with a toast', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-2',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      dnsAutomatic: false,
      dns1Host: '1.1.1.1',
      dns1Protocol: DnsProtocol.dnsOverUdp,
      dns2Host: '',
      dns2Protocol: DnsProtocol.dnsOverUdp,
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);

    await tester.enterText(find.byType(TextField).at(6), 'bad/path');
    await tester.pump();
    await tester.tap(find.text('Save'));
    await tester.pump();

    expect(find.byKey(const Key('app_toast')), findsOneWidget);
    expect(
      find.text(
        'DNS 2 server "bad/path" must be a hostname or IPv4 address for DNS-over-UDP',
      ),
      findsWidgets,
    );
  });

  testWidgets('automatic dns disables manual dns fields', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-3',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      dnsAutomatic: true,
      dns1Host: '',
      dns1Protocol: DnsProtocol.dnsOverUdp,
      dns2Host: '',
      dns2Protocol: DnsProtocol.dnsOverUdp,
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);

    final fields = tester.widgetList<TextField>(find.byType(TextField)).toList();
    expect(fields[5].enabled, isFalse);
    expect(fields[6].enabled, isFalse);
  });
}
