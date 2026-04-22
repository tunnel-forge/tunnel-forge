import 'package:bloc/bloc.dart';
import 'package:equatable/equatable.dart';

sealed class HomeNavEvent extends Equatable {
  const HomeNavEvent();

  @override
  List<Object?> get props => const [];
}

final class HomeNavChanged extends HomeNavEvent {
  const HomeNavChanged(this.index);

  final int index;

  @override
  List<Object?> get props => [index];
}

class HomeNavState extends Equatable {
  const HomeNavState({this.index = 0});

  final int index;

  @override
  List<Object?> get props => [index];
}

class HomeNavBloc extends Bloc<HomeNavEvent, HomeNavState> {
  HomeNavBloc() : super(const HomeNavState()) {
    on<HomeNavChanged>((event, emit) {
      emit(HomeNavState(index: event.index));
    });
  }
}
