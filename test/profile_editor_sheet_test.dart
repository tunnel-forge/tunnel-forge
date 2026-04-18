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

  testWidgets('shows multi-dns helper copy for profile editor', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-1',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      dns: '1.1.1.1, 8.8.8.8',
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);

    expect(find.text('DNS servers'), findsOneWidget);
    expect(
      find.text('2 resolvers configured. Proxy mode falls back in order.'),
      findsOneWidget,
    );
  });

  testWidgets('invalid dns entry blocks save with a snackbar', (tester) async {
    final store = await buildStore();
    const profile = Profile(
      id: 'profile-2',
      displayName: 'Office',
      server: 'vpn.example.com',
      user: 'alice',
      dns: '1.1.1.1',
    );
    await store.upsertProfile(profile, password: 'pw', psk: '');

    await pumpHost(tester, store: store, profileId: profile.id);

    await tester.enterText(
      find.byType(TextField).at(5),
      '1.1.1.1, example.com',
    );
    await tester.pump();
    await tester.tap(find.text('Save'));
    await tester.pump();

    expect(find.byType(SnackBar), findsWidgets);
    expect(
      find.text('DNS server "example.com" is not a valid IPv4 address'),
      findsWidgets,
    );
  });
}
