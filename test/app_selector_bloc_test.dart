import 'package:bloc_test/bloc_test.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:tunnel_forge/features/app_selector/presentation/bloc/app_selector_bloc.dart';
import 'package:tunnel_forge/features/profiles/domain/profile_models.dart';

void main() {
  blocTest<AppSelectorBloc, AppSelectorState>(
    'select all requested before load completes applies to loaded apps',
    build: () => AppSelectorBloc(
      loadApps: () async {
        await Future<void>.delayed(const Duration(milliseconds: 1));
        return const [
          CandidateApp(packageName: 'a.pkg', label: 'App A'),
          CandidateApp(packageName: 'b.pkg', label: 'App B'),
        ];
      },
      initialSelection: <String>{},
    ),
    act: (bloc) async {
      bloc.add(const AppSelectorStarted());
      bloc.add(const AppSelectorSelectAllRequested());
      await Future<void>.delayed(const Duration(milliseconds: 5));
    },
    expect: () => [
      const AppSelectorState(selected: <String>{}, selectAllPending: true),
      const AppSelectorState(
        loading: false,
        apps: [
          CandidateApp(packageName: 'a.pkg', label: 'App A'),
          CandidateApp(packageName: 'b.pkg', label: 'App B'),
        ],
        selected: {'a.pkg', 'b.pkg'},
      ),
    ],
  );
}
