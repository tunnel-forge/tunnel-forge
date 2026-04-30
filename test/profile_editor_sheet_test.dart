import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:tunnel_forge/features/profiles/presentation/profile_editor_sheet.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';
import 'package:tunnel_forge/features/profiles/data/profile_store.dart';

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

  testWidgets('shows dns section when automatic dns is disabled', (
    tester,
  ) async {
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

    expect(find.byKey(const Key('dns_servers_section')), findsOneWidget);
    expect(find.text('Automatic'), findsOneWidget);
    expect(find.text('DNS servers'), findsNWidgets(3));
    expect(find.text('DNS 1 is primary. DNS 2 is fallback.'), findsOneWidget);
    expect(find.text('DNS 1'), findsOneWidget);
    expect(find.text('DNS 2'), findsOneWidget);
    expect(find.text('UDP'), findsOneWidget);
    expect(find.text('TLS'), findsOneWidget);
  });

  testWidgets('dns dropdown height matches dns text field', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-dns-size',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      dnsAutomatic: false,
      dns1Host: '1.1.1.1',
      dns1Protocol: DnsProtocol.dnsOverUdp,
      dns2Host: '8.8.8.8',
      dns2Protocol: DnsProtocol.dnsOverTls,
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);

    final dnsFieldSize = tester.getSize(
      find.byKey(const ValueKey('dns_input_DNS 1')),
    );
    final dnsDropdownSize = tester.getSize(
      find.byKey(const ValueKey('dns_protocol_DNS 1')),
    );

    expect(dnsDropdownSize.height, dnsFieldSize.height);
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
    expect(mtuField.decoration?.labelText, 'MTU');
    expect(mtuField.decoration?.hintText, '${Profile.defaultVpnMtu}');
    expect(mtuField.decoration?.helperMaxLines, 2);
    expect(
      mtuField.decoration?.helperText,
      'Range ${Profile.minVpnMtu}-${Profile.maxVpnMtu}. Use ${Profile.defaultVpnMtu} unless you need a smaller MTU.',
    );
  });

  testWidgets('password and psk visibility toggles are independent', (
    tester,
  ) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-secrets',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      dnsAutomatic: true,
      dns1Host: '',
      dns1Protocol: DnsProtocol.dnsOverUdp,
      dns2Host: '',
      dns2Protocol: DnsProtocol.dnsOverUdp,
    );
    await store.upsertProfile(profile, password: 'pw', psk: 'psk');

    await pumpHost(tester, store: store, profileId: profile.id);

    TextField fieldByLabel(String label) => tester.widget<TextField>(
      find.byWidgetPredicate(
        (widget) =>
            widget is TextField && widget.decoration?.labelText == label,
      ),
    );

    expect(fieldByLabel('Password').obscureText, isTrue);
    expect(fieldByLabel('IPsec PSK').obscureText, isTrue);
    expect(find.byTooltip('Show password'), findsOneWidget);
    expect(find.byTooltip('Show IPsec PSK'), findsOneWidget);

    await tester.tap(find.byTooltip('Show password'));
    await tester.pump();

    expect(fieldByLabel('Password').obscureText, isFalse);
    expect(fieldByLabel('IPsec PSK').obscureText, isTrue);
    expect(find.byTooltip('Hide password'), findsOneWidget);
    expect(find.byTooltip('Show IPsec PSK'), findsOneWidget);

    await tester.tap(find.byTooltip('Show IPsec PSK'));
    await tester.pump();

    expect(fieldByLabel('Password').obscureText, isFalse);
    expect(fieldByLabel('IPsec PSK').obscureText, isFalse);
    expect(find.byTooltip('Hide password'), findsOneWidget);
    expect(find.byTooltip('Hide IPsec PSK'), findsOneWidget);

    await tester.tap(find.byTooltip('Hide password'));
    await tester.pump();

    expect(fieldByLabel('Password').obscureText, isTrue);
    expect(fieldByLabel('IPsec PSK').obscureText, isFalse);
    expect(find.byTooltip('Show password'), findsOneWidget);
    expect(find.byTooltip('Hide IPsec PSK'), findsOneWidget);
  });

  testWidgets('shows inline error when mtu is invalid', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-invalid-mtu',
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

    await tester.enterText(find.byKey(const Key('mtu_field')), '');
    await tester.pump();

    expect(find.text('Enter an MTU value'), findsOneWidget);
  });

  testWidgets('shows inline error when mtu is out of range', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-invalid-mtu-range',
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

    await tester.enterText(
      find.byKey(const Key('mtu_field')),
      '${Profile.maxVpnMtu + 1}',
    );
    await tester.pump();

    expect(
      find.text(
        'MTU must be between ${Profile.minVpnMtu} and ${Profile.maxVpnMtu}',
      ),
      findsOneWidget,
    );
  });

  testWidgets('invalid mtu blocks save with a clear toast', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-invalid-mtu-save',
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

    await tester.enterText(
      find.byKey(const Key('mtu_field')),
      '${Profile.minVpnMtu - 1}',
    );
    await tester.pump();
    await tester.tap(find.text('Save'));
    await tester.pump();

    expect(find.byKey(const Key('app_toast')), findsOneWidget);
    expect(
      find.text(
        'MTU must be between ${Profile.minVpnMtu} and ${Profile.maxVpnMtu}',
      ),
      findsWidgets,
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
    expect(find.text('DNS 2: use hostname or IPv4'), findsWidgets);
  });

  testWidgets('automatic dns hides manual dns section', (tester) async {
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

    expect(find.byKey(const Key('dns_servers_section')), findsNothing);
    expect(find.text('DNS 1 is primary. DNS 2 is fallback.'), findsNothing);
    expect(find.text('DNS 1'), findsNothing);
    expect(find.text('DNS 2'), findsNothing);
  });

  testWidgets('automatic dns toggle hides and shows dns section', (
    tester,
  ) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-4',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      dnsAutomatic: false,
      dns1Host: '1.1.1.1',
      dns1Protocol: DnsProtocol.dnsOverUdp,
      dns2Host: '8.8.8.8',
      dns2Protocol: DnsProtocol.dnsOverTls,
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);

    expect(find.byKey(const Key('dns_servers_section')), findsOneWidget);

    await tester.tap(find.byType(CheckboxListTile));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('dns_servers_section')), findsNothing);

    await tester.tap(find.byType(CheckboxListTile));
    await tester.pumpAndSettle();

    expect(find.byKey(const Key('dns_servers_section')), findsOneWidget);
  });
}
